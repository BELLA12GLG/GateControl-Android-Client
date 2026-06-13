package com.gatecontrol.android.service

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.tunnel.PortStrategy
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "connect the tunnel, retrying with different
 * ports if it fails".
 *
 * ## Why this exists
 *
 * The v1 implementation had **three** independent connect paths — initial
 * connect in [com.gatecontrol.android.ui.vpn.VpnViewModel.connect], reconnect
 * triggered by [com.gatecontrol.android.tunnel.TunnelMonitor], and a manual
 * user-initiated rotation. Each path:
 *   - kept its own [com.gatecontrol.android.tunnel.PortRotator] instance with
 *     independent state — ports tried in one path were re-tried in another;
 *   - re-implemented the connect-retry-on-failure loop with subtle
 *     differences in backoff, error handling, and persistence;
 *   - had its own way of remembering "the last successful port".
 *
 * This coordinator centralises that. All three callers now go through
 * [connectWithRetry], so we have ONE state machine, ONE history of attempted
 * ports per session, and ONE persistence path for the last successful port.
 *
 * ## Concurrency model
 *
 * A single [Mutex] serialises all connect requests. A second call to
 * [connectWithRetry] while one is in-flight will wait for the first to
 * settle; this is intentional — concurrent connects would step on each
 * other's GoBackend state.
 *
 * [skipCurrent] is non-blocking: it sets a flag observed by the in-flight
 * attempt loop. The current attempt's pending connect is left to settle
 * (cancelling mid-connect is a known source of GoBackend BackendException),
 * but the *next* iteration of the loop picks a different port.
 *
 * ## Events for UI
 *
 * [events] is a hot SharedFlow that the UI subscribes to for toasts /
 * progress indicators. Events are buffered (replay=0, extraBufferCapacity=8)
 * so a brief UI absence doesn't lose them, but only the most recent matter
 * for UX so we don't replay history on resubscribe.
 */
@Singleton
class PortRotationCoordinator @Inject constructor(
    private val tunnelManager: TunnelManager,
    private val settingsRepository: SettingsRepository,
) {

    private val mutex = Mutex()

    @Volatile
    private var skipRequested: Boolean = false

    /** The port we are currently connected on (null = original / unknown). */
    private val _activePort = MutableStateFlow<Int?>(null)
    val activePort: StateFlow<Int?> = _activePort.asStateFlow()

    /** Attempt progress for the UI ("Trying port X (attempt Y of Z)"). */
    private val _currentAttempt = MutableStateFlow<AttemptInfo?>(null)
    val currentAttempt: StateFlow<AttemptInfo?> = _currentAttempt.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * Connect, retrying with progressively different ports if WireGuard fails.
     *
     * @param rawConfig the unmodified WireGuard config string (Endpoint port
     *   will be rewritten on each attempt).
     * @param splitConfig the resolved split-tunnel + network preferences
     *   payload — passed straight through to TunnelManager.
     * @param maxAttempts ceiling on attempts including the first
     *   "try original port" attempt. Default 8.
     * @param preferredFirstPort if non-null, try this port first instead of
     *   the original. Used by initial-connect to retry a previously-known
     *   working port without re-discovering it.
     * @param excludePorts ports the caller wants to skip entirely — typically
     *   the port the user just rotated AWAY from. Seeded into the history
     *   set so [PortStrategy] never suggests them, AND used to override
     *   [preferredFirstPort] / [originalPort] fall-back: if either is in
     *   this set, we skip straight to PortStrategy candidates.
     *
     * @return [Outcome] describing whether we connected, on which port, or
     *   ran out of attempts. Never throws; failures are reported via Outcome
     *   so the caller doesn't need a try/catch.
     */
    suspend fun connectWithRetry(
        rawConfig: String,
        splitConfig: SplitTunnelConfig,
        maxAttempts: Int = 8,
        preferredFirstPort: Int? = null,
        excludePorts: Set<Int> = emptySet(),
    ): Outcome = mutex.withLock {
        skipRequested = false

        if (rawConfig.isEmpty()) {
            return@withLock Outcome.NoConfig
        }

        val originalPort = try {
            TunnelConfig.parse(rawConfig).getServerPort()
        } catch (e: Exception) {
            Timber.w(e, "Coordinator: could not parse original port, using 51820")
            51820
        }

        // History seeded with excludePorts so the strategy and the
        // fall-back-to-original logic both treat them as "already tried".
        val history = excludePorts.toMutableSet()
        var attempt = 0

        // ── Attempt 1: preferred-first or original port ──────────────────
        // If the preferred port is in excludePorts (e.g. user manually
        // rotated away from it), skip the preferred and try original.
        // If original is ALSO excluded, skip straight to PortStrategy.
        val firstPort = when {
            preferredFirstPort != null && preferredFirstPort !in excludePorts ->
                preferredFirstPort
            originalPort !in excludePorts -> originalPort
            else -> null  // both excluded, jump to strategy
        }
        if (firstPort != null) {
            attempt++
            if (tryPort(rawConfig, splitConfig, firstPort, attempt, maxAttempts)) {
                persistSuccess(firstPort, originalPort)
                return@withLock Outcome.Connected(firstPort)
            }
            history += firstPort
        }
        // If the preferred-first wasn't the original AND original isn't
        // excluded, fall back to original on next attempt before going
        // random — original might just have been a momentary blip.
        if (preferredFirstPort != null
            && preferredFirstPort != originalPort
            && originalPort !in excludePorts
            && originalPort !in history
        ) {
            attempt++
            if (attempt <= maxAttempts) {
                if (tryPort(rawConfig, splitConfig, originalPort, attempt, maxAttempts)) {
                    persistSuccess(originalPort, originalPort)
                    return@withLock Outcome.Connected(originalPort)
                }
                history += originalPort
            }
        }

        // ── Subsequent attempts: PortStrategy ────────────────────────────
        while (attempt < maxAttempts) {
            attempt++
            val nextPort = PortStrategy.next(originalPort, history)
                ?: run {
                    Timber.w("Coordinator: port strategy exhausted at attempt=$attempt")
                    _events.tryEmit(Event.Exhausted)
                    _currentAttempt.value = null
                    return@withLock Outcome.Exhausted(history.size)
                }
            if (tryPort(rawConfig, splitConfig, nextPort, attempt, maxAttempts)) {
                persistSuccess(nextPort, originalPort)
                return@withLock Outcome.Connected(nextPort)
            }
            history += nextPort
        }

        _events.tryEmit(Event.Failed(attempt))
        _currentAttempt.value = null
        return@withLock Outcome.Failed(attempt)
    }

    /**
     * Ask the coordinator to abandon the current port and try a different
     * one on the next iteration. Non-blocking. Has no effect if no connect
     * sequence is currently running.
     *
     * Used by the manual "rotate port" UI button and by [TunnelMonitor] when
     * it detects a stall while in the steady-connected state.
     */
    fun skipCurrent() {
        skipRequested = true
    }

    /** Clear the persisted "last successful port" — call on disconnect. */
    suspend fun resetPersistedPort() {
        settingsRepository.clearSuccessfulPort()
        _activePort.value = null
    }

    // ───────────────────────────────────────────────────────────────────
    // Private helpers
    // ───────────────────────────────────────────────────────────────────

    private suspend fun tryPort(
        rawConfig: String,
        splitConfig: SplitTunnelConfig,
        port: Int,
        attempt: Int,
        maxAttempts: Int,
    ): Boolean {
        if (skipRequested) {
            Timber.d("Coordinator: skipping port=$port due to skipRequested")
            skipRequested = false
            return false
        }

        _currentAttempt.value = AttemptInfo(port, attempt, maxAttempts)
        _events.tryEmit(Event.Trying(port, attempt, maxAttempts))

        val configToUse = replaceEndpointPort(rawConfig, port)
        return try {
            tunnelManager.connect(configToUse, splitConfig)
            Timber.i("Coordinator: connected on port=$port (attempt=$attempt)")
            _activePort.value = port
            _currentAttempt.value = null
            _events.tryEmit(Event.Connected(port))
            true
        } catch (e: Exception) {
            Timber.w(e, "Coordinator: connect failed on port=$port (attempt=$attempt)")
            // Brief backoff so we don't hammer the network — but short enough
            // the user doesn't feel the app is hung.
            delay(attempt.coerceAtMost(3) * 500L)
            false
        }
    }

    private suspend fun persistSuccess(port: Int, originalPort: Int) {
        if (port != originalPort) {
            try {
                settingsRepository.saveSuccessfulPort(port)
            } catch (e: Exception) {
                Timber.w(e, "Coordinator: failed to persist successful port")
            }
        } else {
            // Connected on the original port — clear any stale override.
            try {
                settingsRepository.clearSuccessfulPort()
            } catch (_: Exception) {}
        }
    }

    /**
     * Rewrite the `Endpoint = host:port` line in a WireGuard config string.
     *
     * Supports three endpoint syntaxes:
     *   - "example.com:51820"     — plain hostname or IPv4
     *   - "[2001:db8::1]:51820"   — bracketed IPv6 (canonical)
     *   - "2001:db8::1:51820"     — bare IPv6 (last-colon split, ambiguous
     *     but we use it because some configs come this way)
     */
    internal fun replaceEndpointPort(config: String, port: Int): String =
        config.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("Endpoint", ignoreCase = true) || !trimmed.contains("=")) {
                return@joinToString line
            }
            val eqIndex = line.indexOf('=')
            val prefix = line.substring(0, eqIndex + 1)
            val value = line.substring(eqIndex + 1).trim()
            val hostPart = when {
                value.startsWith("[") -> {
                    // Bracketed IPv6: keep brackets and everything before the
                    // close-bracket; discard whatever port follows.
                    val close = value.indexOf(']')
                    if (close < 0) value.substringBeforeLast(":") // malformed; best effort
                    else value.substring(0, close + 1)
                }
                else -> value.substringBeforeLast(":")
            }
            "$prefix $hostPart:$port"
        }

    // ───────────────────────────────────────────────────────────────────
    // Public types
    // ───────────────────────────────────────────────────────────────────

    data class AttemptInfo(val port: Int, val attempt: Int, val maxAttempts: Int)

    sealed class Outcome {
        data class Connected(val port: Int) : Outcome()
        data class Failed(val attempts: Int) : Outcome()
        data class Exhausted(val portsTried: Int) : Outcome()
        data object NoConfig : Outcome()
    }

    sealed class Event {
        data class Trying(val port: Int, val attempt: Int, val maxAttempts: Int) : Event()
        data class Connected(val port: Int) : Event()
        data class Failed(val attempts: Int) : Event()
        data object Exhausted : Event()
    }
}
