package com.gatecontrol.android.service

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [PortRotationCoordinator].
 *
 * Most tests stub TunnelManager.connect to either succeed or throw, which
 * exercises the coordinator's retry loop without needing the WireGuard
 * backend running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortRotationCoordinatorTest {

    private lateinit var tunnelManager: TunnelManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var coordinator: PortRotationCoordinator

    private val sampleConfig =
        "[Interface]\nPrivateKey=abc\nAddress=10.0.0.1/32\n\n" +
        "[Peer]\nPublicKey=xyz\nEndpoint=1.2.3.4:51820\nAllowedIPs=0.0.0.0/0"

    @BeforeEach
    fun setUp() {
        tunnelManager = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        coEvery { settingsRepository.saveSuccessfulPort(any()) } returns Unit
        coEvery { settingsRepository.clearSuccessfulPort() } returns Unit
        coordinator = PortRotationCoordinator(tunnelManager, settingsRepository)
    }

    // ── replaceEndpointPort ──────────────────────────────────────────────────

    @Test
    fun `replaceEndpointPort swaps IPv4 endpoint port`() {
        val result = coordinator.replaceEndpointPort("Endpoint = 1.2.3.4:51820", 443)
        assertTrue(result.contains("1.2.3.4:443"))
        assertFalse(result.contains(":51820"))
    }

    @Test
    fun `replaceEndpointPort swaps bracketed IPv6 endpoint port`() {
        // The v1 implementation split on lastIndexOf(':') which would
        // break "[2001:db8::1]:51820" into "[2001:db8::1" + ":51820" —
        // truncating the closing bracket. v6 handles brackets specifically.
        val result = coordinator.replaceEndpointPort("Endpoint = [2001:db8::1]:51820", 443)
        assertTrue(result.contains("[2001:db8::1]:443"),
            "Bracket must be preserved; got: $result")
        assertFalse(result.contains(":51820"))
    }

    @Test
    fun `replaceEndpointPort leaves non-Endpoint lines unchanged`() {
        val config = "PrivateKey = abc\nEndpoint = 1.2.3.4:51820\nAllowedIPs = 0.0.0.0/0"
        val result = coordinator.replaceEndpointPort(config, 8080)
        assertTrue(result.contains("PrivateKey = abc"))
        assertTrue(result.contains("AllowedIPs = 0.0.0.0/0"))
        assertTrue(result.contains("1.2.3.4:8080"))
    }

    @Test
    fun `replaceEndpointPort handles hostname endpoint`() {
        val result = coordinator.replaceEndpointPort("Endpoint = vpn.example.com:51820", 53)
        assertTrue(result.contains("vpn.example.com:53"))
    }

    // ── connectWithRetry outcomes ────────────────────────────────────────────

    @Test
    fun `connectWithRetry returns Connected when first attempt succeeds`() = runTest {
        // tunnelManager.connect returns Unit (success) by default via relaxed mock
        val outcome = coordinator.connectWithRetry(sampleConfig, SplitTunnelConfig())
        assertTrue(outcome is PortRotationCoordinator.Outcome.Connected)
        assertEquals(51820, (outcome as PortRotationCoordinator.Outcome.Connected).port)
    }

    @Test
    fun `connectWithRetry returns NoConfig on empty config`() = runTest {
        val outcome = coordinator.connectWithRetry("", SplitTunnelConfig())
        assertTrue(outcome is PortRotationCoordinator.Outcome.NoConfig)
    }

    @Test
    fun `connectWithRetry rotates ports when initial connect throws`() = runTest {
        // First call throws (port 51820 fails), second call succeeds (port 443)
        var callCount = 0
        coEvery { tunnelManager.connect(any<String>(), any<SplitTunnelConfig>()) } answers {
            callCount++
            if (callCount == 1) throw RuntimeException("fake failure on original port")
            // success
        }
        val outcome = coordinator.connectWithRetry(sampleConfig, SplitTunnelConfig(), maxAttempts = 4)
        assertTrue(outcome is PortRotationCoordinator.Outcome.Connected,
            "Should retry on a different port after first failure; got $outcome")
        assertEquals(443, (outcome as PortRotationCoordinator.Outcome.Connected).port,
            "Should connect on 443 (the first well-known port)")
    }

    @Test
    fun `connectWithRetry persists successful port when not original`() = runTest {
        var callCount = 0
        coEvery { tunnelManager.connect(any<String>(), any<SplitTunnelConfig>()) } answers {
            callCount++
            if (callCount == 1) throw RuntimeException("original port blocked")
        }
        coordinator.connectWithRetry(sampleConfig, SplitTunnelConfig(), maxAttempts = 3)
        coVerify { settingsRepository.saveSuccessfulPort(443) }
    }

    @Test
    fun `connectWithRetry clears persisted port when original succeeds`() = runTest {
        // No failures — first attempt (on original port) succeeds.
        coordinator.connectWithRetry(sampleConfig, SplitTunnelConfig())
        coVerify { settingsRepository.clearSuccessfulPort() }
    }

    @Test
    fun `connectWithRetry uses preferredFirstPort first`() = runTest {
        coordinator.connectWithRetry(
            rawConfig = sampleConfig,
            splitConfig = SplitTunnelConfig(),
            preferredFirstPort = 8443,
        )
        // First connect call should have used port 8443
        coVerify {
            tunnelManager.connect(match<String> { it.contains("1.2.3.4:8443") }, any<SplitTunnelConfig>())
        }
    }

    @Test
    fun `connectWithRetry falls back to original after preferred fails`() = runTest {
        var callCount = 0
        coEvery { tunnelManager.connect(any<String>(), any<SplitTunnelConfig>()) } answers {
            callCount++
            if (callCount == 1) throw RuntimeException("preferred port doesn't work anymore")
            // 2nd call (original) succeeds
        }
        val outcome = coordinator.connectWithRetry(
            sampleConfig, SplitTunnelConfig(),
            preferredFirstPort = 8443,
            maxAttempts = 4,
        )
        assertTrue(outcome is PortRotationCoordinator.Outcome.Connected)
        assertEquals(51820, (outcome as PortRotationCoordinator.Outcome.Connected).port,
            "Should fall back to original after preferred fails")
    }

    @Test
    fun `connectWithRetry returns Failed when all attempts exhausted`() = runTest {
        coEvery { tunnelManager.connect(any<String>(), any<SplitTunnelConfig>()) } throws
            RuntimeException("every port blocked")
        val outcome = coordinator.connectWithRetry(
            sampleConfig, SplitTunnelConfig(),
            maxAttempts = 3,
        )
        assertTrue(outcome is PortRotationCoordinator.Outcome.Failed,
            "Should be Failed after exhausting maxAttempts; got $outcome")
    }

    // ── excludePorts (manual-rotate bug fix) ─────────────────────────────────
    //
    // Regression test: when the user manually rotates ports away from the
    // currently-active port, the coordinator MUST NOT just reconnect on the
    // same port. Pre-fix bug: passing preferredFirstPort=null caused fallback
    // to originalPort, which was often the same port the user was on.

    @Test
    fun `excludePorts skips the excluded port entirely`() = runTest {
        // User is on port 51820 (original), wants to rotate AWAY from it.
        // The coordinator should NOT try 51820, must jump to PortStrategy.
        val outcome = coordinator.connectWithRetry(
            rawConfig = sampleConfig,
            splitConfig = SplitTunnelConfig(),
            preferredFirstPort = null,
            excludePorts = setOf(51820),
        )
        assertTrue(outcome is PortRotationCoordinator.Outcome.Connected)
        val connectedPort = (outcome as PortRotationCoordinator.Outcome.Connected).port
        assertEquals(443, connectedPort,
            "Should skip excluded 51820 and connect on first well-known port 443")
    }

    @Test
    fun `excludePorts skips both preferred and original if both excluded`() = runTest {
        // User was on 8443 (persisted), wants to rotate. Original is 51820.
        // Excluding 8443 only — coordinator still tries 51820 fallback first.
        // To test the "both excluded" case we exclude both.
        val outcome = coordinator.connectWithRetry(
            rawConfig = sampleConfig,
            splitConfig = SplitTunnelConfig(),
            preferredFirstPort = 8443,
            excludePorts = setOf(8443, 51820),
        )
        assertTrue(outcome is PortRotationCoordinator.Outcome.Connected)
        assertEquals(443, (outcome as PortRotationCoordinator.Outcome.Connected).port,
            "Should skip both excluded ports and jump straight to PortStrategy")
    }

    @Test
    fun `excludePorts ignores preferred but still tries original`() = runTest {
        // Manual rotate scenario: user was on persistedPort=8443, original
        // is 51820. excludePorts has 8443 only. Coordinator should skip
        // 8443 but DOES try 51820 (it might be a different physical path).
        var attemptedPorts = mutableListOf<Int>()
        coEvery { tunnelManager.connect(any<String>(), any<SplitTunnelConfig>()) } answers {
            val cfg = firstArg<String>()
            val match = Regex("""Endpoint\s*=\s*[^:]+:(\d+)""").find(cfg)
            match?.groupValues?.get(1)?.toIntOrNull()?.let { attemptedPorts.add(it) }
            // succeed on whichever port we land on
        }
        val outcome = coordinator.connectWithRetry(
            rawConfig = sampleConfig,
            splitConfig = SplitTunnelConfig(),
            preferredFirstPort = 8443,
            excludePorts = setOf(8443),
        )
        assertTrue(outcome is PortRotationCoordinator.Outcome.Connected)
        assertFalse(8443 in attemptedPorts, "Excluded port 8443 must not be tried")
        assertEquals(51820, attemptedPorts.first(),
            "Original port should be the first thing tried after excluding 8443")
    }
}
