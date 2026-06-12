package com.gatecontrol.android.tunnel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TunnelMonitorTest {

    // ── Companion helper tests (unchanged behaviour) ──────────────────────────

    @Test
    fun `calculateBackoffMs returns 2000ms for attempt 0`() {
        assertEquals(2000L, TunnelMonitor.calculateBackoffMs(0))
    }

    @Test
    fun `calculateBackoffMs returns 3000ms for attempt 1`() {
        assertEquals(3000L, TunnelMonitor.calculateBackoffMs(1))
    }

    @Test
    fun `calculateBackoffMs caps at 60000ms for high attempts`() {
        assertEquals(60_000L, TunnelMonitor.calculateBackoffMs(100))
    }

    @Test
    fun `calculateBackoffMs increases exponentially`() {
        val a0 = TunnelMonitor.calculateBackoffMs(0)
        val a1 = TunnelMonitor.calculateBackoffMs(1)
        val a2 = TunnelMonitor.calculateBackoffMs(2)
        assertTrue(a1 > a0)
        assertTrue(a2 > a1)
        assertEquals(3000L, a1)
        assertEquals(4500L, a2)
    }

    @Test
    fun `shouldReconnect returns true when attempt is within max`() {
        assertTrue(TunnelMonitor.shouldReconnect(0, 10))
        assertTrue(TunnelMonitor.shouldReconnect(9, 10))
    }

    @Test
    fun `shouldReconnect returns false at or beyond max attempts`() {
        assertFalse(TunnelMonitor.shouldReconnect(10, 10))
        assertFalse(TunnelMonitor.shouldReconnect(11, 10))
    }

    @Test
    fun `isHandshakeStale detects old handshake beyond maxAgeSec`() {
        val staleEpoch = (System.currentTimeMillis() / 1000) - 200
        assertTrue(TunnelMonitor.isHandshakeStale(staleEpoch, 180L))
    }

    @Test
    fun `isHandshakeStale accepts fresh handshake within 10 seconds`() {
        val freshEpoch = (System.currentTimeMillis() / 1000) - 5
        assertFalse(TunnelMonitor.isHandshakeStale(freshEpoch, 180L))
    }

    @Test
    fun `isHandshakeStale treats zero epoch as stale`() {
        assertTrue(TunnelMonitor.isHandshakeStale(0L, 180L))
    }

    // ── ReconnectRequest includes suggestedPort ───────────────────────────────

    @Test
    fun `ReconnectRequest default suggestedPort is null`() {
        val req = TunnelMonitor.ReconnectRequest(attempt = 1, maxAttempts = 10)
        assertNull(req.suggestedPort)
    }

    @Test
    fun `ReconnectRequest carries explicit suggested port`() {
        val req = TunnelMonitor.ReconnectRequest(attempt = 2, maxAttempts = 10, suggestedPort = 443)
        assertEquals(443, req.suggestedPort)
    }
}

    // ── Tier 1 rx-only logic ──────────────────────────────────────────────────

    @Test
    fun `isHandshakeStale — rx-only rationale comment verification`() {
        // When a port is blocked:
        //   tx keeps rising (client retries keepalives) — NOT a health signal
        //   rx stops       — the real signal that replies stopped arriving
        // This test documents the design decision: only rxBytes matters.
        val blockedPortScenario = TunnelStats(
            rxBytes = 1000L,   // stopped here — port blocked, no replies
            txBytes = 99999L,  // keeps growing — client retrying, means nothing
            lastHandshakeEpoch = (System.currentTimeMillis() / 1000) - 300  // stale
        )
        assertTrue(
            TunnelMonitor.isHandshakeStale(blockedPortScenario.lastHandshakeEpoch, 180L),
            "Stale handshake should be detected even when txBytes is rising"
        )
    }
