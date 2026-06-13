package com.gatecontrol.android.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Monitors the WireGuard tunnel health.
 *
 * ## Two-tier detection
 *
 *   Tier 1 — Local rx-only traffic check (every [trafficCheckIntervalMs], 5 s).
 *     Reads rxBytes only from [localStats]. txBytes is intentionally ignored:
 *     when the server port is blocked the client keeps sending keepalive/
 *     handshake packets (tx rises) but never receives a reply (rx stalls).
 *     Rising rx is the only reliable local signal that the tunnel is passing
 *     traffic. Zero network I/O, safe to run frequently.
 *
 *   Tier 2 — Handshake staleness check (after [stallCyclesBeforeHandshakeCheck]
 *     consecutive no-rx cycles, ~30 s by default).
 *     Queries the WireGuard backend for the latest handshake timestamp; only
 *     runs when rx has been idle to keep backend queries rare.
 *
 * ## v2: monitor is purely a detector
 *
 * Unlike v1 this class does NOT perform disconnect / reconnect itself.
 * When it detects a stall it emits exactly one [StallEvent] and stops
 * watching until [start] is called again. The coordinator decides what
 * to do with the signal — typically:
 *   1. disconnect the tunnel,
 *   2. call PortRotationCoordinator.connectWithRetry,
 *   3. restart the monitor with the new connection.
 *
 * This separation fixes a real v1 bug where the monitor emitted
 * `disconnectEvent` to nobody — there was no collector for it — yet the
 * monitor then proceeded to emit `reconnectEvent` 1 s later assuming the
 * tunnel had been torn down. The two-event protocol relied on coordination
 * that did not exist.
 */
class TunnelMonitor(
    private val trafficCheckIntervalMs: Long = 5_000L,
    private val stallCyclesBeforeHandshakeCheck: Int = 6,
    private val maxHandshakeAgeSec: Long = 180L,
) {
    /**
     * Single stall notification. Emitted once when the monitor detects the
     * tunnel is no longer passing traffic AND the latest handshake is older
     * than [maxHandshakeAgeSec]. The monitor stops itself after emitting.
     */
    data class StallEvent(val reason: Reason) {
        enum class Reason { HANDSHAKE_STALE, RX_FROZEN_NO_HANDSHAKE_DATA }
    }

    private val _stallEvents = MutableSharedFlow<StallEvent>(extraBufferCapacity = 4)
    val stallEvents: SharedFlow<StallEvent> = _stallEvents.asSharedFlow()

    private val _statsEvents = MutableSharedFlow<TunnelStats>(extraBufferCapacity = 4)
    val statsEvents: SharedFlow<TunnelStats> = _statsEvents.asSharedFlow()

    private var monitorJob: Job? = null

    /**
     * Start the monitor loop. Cancels any existing loop first.
     *
     * @param scope          Coroutine scope that owns the loop.
     * @param statsProvider  Suspending call to query the WireGuard backend
     *                       (Tier 2 — used only when rx is idle).
     * @param localStats     Non-suspending snapshot of the latest TunnelStats
     *                       (Tier 1). Pass null to skip the fast path.
     */
    fun start(
        scope: CoroutineScope,
        statsProvider: suspend () -> TunnelStats?,
        localStats: (() -> TunnelStats)? = null,
    ) {
        stop()
        monitorJob = scope.launch {
            var lastRxBytes = -1L
            var noRxCycles = 0

            while (true) {
                delay(trafficCheckIntervalMs)

                // ── Tier 1: rx-only local check ─────────────────────────────
                if (localStats != null) {
                    val snap = localStats()
                    val rxMoving = lastRxBytes >= 0 && snap.rxBytes > lastRxBytes
                    lastRxBytes = snap.rxBytes

                    if (rxMoving) {
                        noRxCycles = 0
                        _statsEvents.emit(snap)
                        continue
                    }
                    noRxCycles++
                    if (noRxCycles < stallCyclesBeforeHandshakeCheck) continue
                    noRxCycles = 0
                }

                // ── Tier 2: handshake staleness ─────────────────────────────
                val stats = try { statsProvider() } catch (_: Exception) { continue }
                if (stats == null) continue

                if (isHandshakeStale(stats.lastHandshakeEpoch, maxHandshakeAgeSec)) {
                    _stallEvents.emit(StallEvent(StallEvent.Reason.HANDSHAKE_STALE))
                    // Stop watching — the coordinator will restart us after
                    // it has re-established the tunnel. Continuing to loop
                    // here would emit duplicate stall events during the
                    // reconnect attempt window.
                    return@launch
                } else {
                    lastRxBytes = stats.rxBytes
                    _statsEvents.emit(stats)
                }
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    companion object {
        fun isHandshakeStale(epochSeconds: Long, maxAgeSec: Long): Boolean {
            if (epochSeconds == 0L) return true
            val nowSeconds = System.currentTimeMillis() / 1000
            return (nowSeconds - epochSeconds) > maxAgeSec
        }
    }
}
