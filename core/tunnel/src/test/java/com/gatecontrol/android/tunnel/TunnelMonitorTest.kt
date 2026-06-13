package com.gatecontrol.android.tunnel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [TunnelMonitor].
 *
 * v6 dropped the backoff/reconnect-orchestration helpers from this class
 * (calculateBackoffMs, shouldReconnect, ReconnectRequest) — those concerns
 * moved to [com.gatecontrol.android.service.PortRotationCoordinator]. What
 * remains is just the stall-detection logic.
 */
class TunnelMonitorTest {

    @Test
    fun `isHandshakeStale detects old handshake beyond maxAgeSec`() {
        val staleEpoch = (System.currentTimeMillis() / 1000) - 200
        assertTrue(TunnelMonitor.isHandshakeStale(staleEpoch, 180L))
    }

    @Test
    fun `isHandshakeStale accepts fresh handshake within maxAgeSec`() {
        val freshEpoch = (System.currentTimeMillis() / 1000) - 5
        assertFalse(TunnelMonitor.isHandshakeStale(freshEpoch, 180L))
    }

    @Test
    fun `isHandshakeStale treats zero epoch as stale`() {
        // Zero epoch is what the WireGuard backend returns before the very
        // first handshake completes. Treating it as stale means the monitor
        // does not consider a brand-new never-connected tunnel "healthy".
        assertTrue(TunnelMonitor.isHandshakeStale(0L, 180L))
    }

    @Test
    fun `tx-rising-but-rx-stalled scenario is still detected via handshake`() {
        // Documents the design decision: when the server port is blocked,
        // tx keeps rising (client retries keepalives) but rx stalls. The
        // monitor's rx-only Tier 1 catches this, then Tier 2 confirms via
        // handshake staleness. This test pins the second half of that
        // pipeline.
        val blockedPortScenario = TunnelStats(
            rxBytes = 1000L,
            txBytes = 99999L,
            lastHandshakeEpoch = (System.currentTimeMillis() / 1000) - 300,
        )
        assertTrue(
            TunnelMonitor.isHandshakeStale(blockedPortScenario.lastHandshakeEpoch, 180L),
            "Stale handshake should be detected even when txBytes is rising",
        )
    }
}
