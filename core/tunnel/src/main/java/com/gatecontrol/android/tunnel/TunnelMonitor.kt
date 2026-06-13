package com.gatecontrol.android.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Monitors the WireGuard tunnel health using a two-tier detection strategy:
 *
 *  Tier 1 — Local rx-only traffic check (every [trafficCheckIntervalMs], default 5 s)
 *      Reads **rxBytes only** from the in-memory [TunnelStats] snapshot.
 *      txBytes is intentionally ignored: when the server port is blocked the
 *      client keeps sending keepalive/handshake packets (tx rises) but never
 *      receives a reply (rx stalls).  A rising rx counter is therefore the
 *      only reliable local signal that the tunnel is actually passing traffic.
 *      Zero network I/O — safe to run frequently.
 *
 *  Tier 2 — Handshake staleness check (after [stallCyclesBeforeHandshakeCheck]
 *      consecutive no-rx cycles, ~30 s by default)
 *      Queries the WireGuard backend for the latest handshake timestamp.
 *      Only triggered when rx has been idle, keeping backend calls rare.
 *
 * When the handshake is stale the monitor emits [reconnectEvent] with a
 * [ReconnectRequest] that carries the next [suggestedPort] from [PortRotator].
 * The ViewModel replaces the Endpoint port in-memory and reconnects — the
 * original stored config string is never mutated.
 *
 * Screen-off / Doze behaviour
 * ----------------------------
 * Both tiers run inside [VpnForegroundService.serviceScope], which survives
 * screen-off as a foreground service.
 */
class TunnelMonitor(
    /** Interval between local rx-traffic polls (Tier 1). */
    private val trafficCheckIntervalMs: Long = 5_000L,
    /**
     * How many consecutive no-rx cycles before escalating to a handshake check.
     * Default 6 × 5 s = 30 s of rx silence triggers Tier 2.
     */
    private val stallCyclesBeforeHandshakeCheck: Int = 6,
    private val maxHandshakeAgeSec: Long = 180L,
    private val maxReconnectAttempts: Int = 10,
    private val failuresBeforeDisconnect: Int = 3,
) {
    // ── Public event streams ──────────────────────────────────────────────────

    private val _disconnectEvent = MutableSharedFlow<Unit>()
    val disconnectEvent: SharedFlow<Unit> = _disconnectEvent.asSharedFlow()

    private val _reconnectEvent = MutableSharedFlow<ReconnectRequest>()
    val reconnectEvent: SharedFlow<ReconnectRequest> = _reconnectEvent.asSharedFlow()

    private val _statsEvent = MutableSharedFlow<TunnelStats>()
    val statsEvent: SharedFlow<TunnelStats> = _statsEvent.asSharedFlow()

    private var monitorJob: Job? = null

    /**
     * @param attempt       1-based attempt number
     * @param maxAttempts   ceiling before giving up
     * @param suggestedPort port the PortRotator recommends; null = keep original
     */
    data class ReconnectRequest(
        val attempt: Int,
        val maxAttempts: Int,
        val suggestedPort: Int? = null,
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * @param scope          Coroutine scope that owns the monitor loop.
     * @param statsProvider  Suspending call that queries the WireGuard backend
     *                       (Tier 2 — used only when rx has been idle).
     * @param localStats     Non-suspending snapshot of the most-recent
     *                       [TunnelStats] held in memory (Tier 1 — rx check only).
     *                       Pass null to skip the fast-path check.
     * @param portRotator    Optional rotator; when provided each
     *                       [ReconnectRequest] carries the next candidate port.
     */
    fun start(
        scope: CoroutineScope,
        statsProvider: suspend () -> TunnelStats?,
        localStats: (() -> TunnelStats)? = null,
        portRotator: PortRotator? = null,
    ) {
        stop()
        monitorJob = scope.launch {
            var lastRxBytes = -1L   // -1 = not yet initialised
            var noRxCycles = 0
            var consecutiveHandshakeFailures = 0

            while (true) {
                delay(trafficCheckIntervalMs)

                // ── Tier 1: rx-only local check ───────────────────────────────
                // txBytes deliberately excluded: a blocked server port causes tx
                // to keep rising (client retries) while rx stalls completely.
                // Only a rising rxBytes proves the tunnel is actually working.
                if (localStats != null) {
                    val snap = localStats()
                    val rxMoving = lastRxBytes >= 0 && snap.rxBytes > lastRxBytes
                    lastRxBytes = snap.rxBytes

                    if (rxMoving) {
                        // Receiving data — tunnel is healthy.
                        noRxCycles = 0
                        consecutiveHandshakeFailures = 0
                        _statsEvent.emit(snap)
                        continue
                    }

                    // rx has not grown this cycle
                    noRxCycles++
                    if (noRxCycles < stallCyclesBeforeHandshakeCheck) {
                        // Still within the grace period — don't escalate yet.
                        continue
                    }
                    // Grace period expired — fall through to Tier 2.
                    noRxCycles = 0
                }

                // ── Tier 2: handshake staleness check ────────────────────────
                // statsProvider queries the local WireGuard kernel interface.
                // It should never need a server URL, but wrap in try/catch so
                // any unexpected exception doesn't get misread as a failure and
                // trigger an unwanted reconnect (e.g. when no server is set up).
                val stats = try {
                    statsProvider()
                } catch (e: Exception) {
                    noRxCycles = 0
                    continue
                }
                // null means handshake data is unavailable (e.g. reflection not
                // supported on this device/library version). Skip Tier 2 entirely
                // — health detection relies solely on Tier 1 rx traffic check.
                if (stats == null) continue
                val isFailure = isHandshakeStale(stats.lastHandshakeEpoch, maxHandshakeAgeSec)

                if (isFailure) {
                    consecutiveHandshakeFailures++
                    if (consecutiveHandshakeFailures >= failuresBeforeDisconnect) {
                        _disconnectEvent.emit(Unit)

                        // 等待 ViewModel 完成 disconnect() 后再发重连请求。
                        // 无间隔时 disconnect() 和 connect() 可能并发执行，
                        // 加剧 GoBackend 的 BackendException 风险。
                        delay(1_000L)

                        for (attempt in 0 until maxReconnectAttempts) {
                            val suggestedPort = portRotator?.nextPort()
                            _reconnectEvent.emit(
                                ReconnectRequest(
                                    attempt = attempt + 1,
                                    maxAttempts = maxReconnectAttempts,
                                    suggestedPort = suggestedPort,
                                )
                            )

                            delay(calculateBackoffMs(attempt))

                            val retryStats = try { statsProvider() } catch (_: Exception) { null }
                            val recovered = retryStats != null &&
                                !isHandshakeStale(retryStats.lastHandshakeEpoch, maxHandshakeAgeSec)

                            if (recovered) {
                                consecutiveHandshakeFailures = 0
                                lastRxBytes = retryStats!!.rxBytes
                                portRotator?.reset()
                                _statsEvent.emit(retryStats)
                                break
                            }

                            if (attempt == maxReconnectAttempts - 1) {
                                return@launch  // give up
                            }
                        }
                    }
                } else {
                    consecutiveHandshakeFailures = 0
                    lastRxBytes = stats!!.rxBytes
                    _statsEvent.emit(stats)
                }
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    companion object {
        fun calculateBackoffMs(attempt: Int): Long {
            // 基准从 2_000 提升到 8_000：
            // WireGuard 握手超时约 5 s，ViewModel 收到 reconnectEvent 后异步执行
            // connect()，2 s 退避时间不足以等到握手完成，导致 statsProvider() 查到
            // 的仍是旧握手时间戳，有效端口被误判为失败并跳过。
            // 8 s > 5 s 握手超时 + 网络延迟，确保 recovered 判断基于真实结果。
            val base = 8_000L
            val computed = base * Math.pow(1.5, attempt.toDouble())
            return minOf(computed.toLong(), 60_000L)
        }

        fun shouldReconnect(attempt: Int, maxAttempts: Int): Boolean = attempt < maxAttempts

        fun isHandshakeStale(epochSeconds: Long, maxAgeSec: Long): Boolean {
            if (epochSeconds == 0L) return true
            val nowSeconds = System.currentTimeMillis() / 1000
            return (nowSeconds - epochSeconds) > maxAgeSec
        }
    }
}
