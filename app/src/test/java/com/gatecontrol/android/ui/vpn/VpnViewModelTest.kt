package com.gatecontrol.android.ui.vpn

import app.cash.turbine.test
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PermissionFlags
import com.gatecontrol.android.network.PermissionsResponse
import com.gatecontrol.android.network.SplitTunnelPresetResponse
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var setupRepository: SetupRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var licenseRepository: LicenseRepository
    private lateinit var apiClientProvider: ApiClientProvider
    private lateinit var apiClient: ApiClient
    private lateinit var tunnelManager: TunnelManager
    private lateinit var viewModel: VpnViewModel

    private val SAMPLE_WG_CONFIG =
        "[Interface]\nPrivateKey=abc\nAddress=10.0.0.1/32\n\n[Peer]\nPublicKey=xyz\nEndpoint=1.2.3.4:51820\nAllowedIPs=0.0.0.0/0"

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        setupRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        licenseRepository = mockk(relaxed = true)
        apiClientProvider = mockk(relaxed = true)
        apiClient = mockk(relaxed = true)
        tunnelManager = mockk(relaxed = true)

        every { settingsRepository.getKillSwitch() } returns flowOf(false)
        every { settingsRepository.getSplitTunnelEnabled() } returns flowOf(false)
        every { settingsRepository.getSplitTunnelRoutes() } returns flowOf("")
        every { settingsRepository.getSplitTunnelApps() } returns flowOf("")
        every { settingsRepository.getSplitTunnelMode() } returns flowOf("off")
        every { settingsRepository.getSplitTunnelNetworks() } returns flowOf("[]")
        every { settingsRepository.getSplitTunnelAppsV2() } returns flowOf("[]")
        every { settingsRepository.getLastSuccessfulPort() } returns flowOf(0)
        every { setupRepository.getServerUrl() } returns "https://gate.example.com"
        every { setupRepository.getPeerId() } returns 42
        coEvery { apiClient.getSplitTunnelPreset() } returns SplitTunnelPresetResponse(
            ok = true, mode = "off", networks = emptyList(), locked = false, source = "none"
        )
        every { apiClientProvider.getClient(any()) } returns apiClient
        every { tunnelManager.state } returns MutableStateFlow(TunnelState.Disconnected)
        every { tunnelManager.stats } returns MutableStateFlow(com.gatecontrol.android.tunnel.TunnelStats())

        viewModel = VpnViewModel(
            setupRepository = setupRepository,
            settingsRepository = settingsRepository,
            licenseRepository = licenseRepository,
            apiClientProvider = apiClientProvider,
            tunnelManager = tunnelManager,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── State tests ───────────────────────────────────────────────────────────

    @Test
    fun `initial state is Disconnected`() = runTest {
        viewModel.tunnelState.test {
            assertInstanceOf(TunnelState.Disconnected::class.java, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── connect ───────────────────────────────────────────────────────────────

    @Test
    fun `connect calls tunnelManager with config`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns SAMPLE_WG_CONFIG

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { tunnelManager.connect(any(), any<com.gatecontrol.android.tunnel.SplitTunnelConfig>()) }
    }

    @Test
    fun `connect does nothing when config is empty`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns ""

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) {
            tunnelManager.connect(any(), any<com.gatecontrol.android.tunnel.SplitTunnelConfig>())
        }
    }

    @Test
    fun `connect uses persisted successful port when available`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns SAMPLE_WG_CONFIG
        every { settingsRepository.getLastSuccessfulPort() } returns flowOf(443)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        // tunnelManager.connect should have been called with config containing :443
        coVerify {
            tunnelManager.connect(
                match { it.contains(":443") },
                any<com.gatecontrol.android.tunnel.SplitTunnelConfig>(),
            )
        }
    }

    @Test
    fun `connect uses original port when no persisted port`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns SAMPLE_WG_CONFIG
        every { settingsRepository.getLastSuccessfulPort() } returns flowOf(0)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            tunnelManager.connect(
                match { it.contains(":51820") },
                any<com.gatecontrol.android.tunnel.SplitTunnelConfig>(),
            )
        }
    }

    // ── disconnect ────────────────────────────────────────────────────────────

    @Test
    fun `disconnect calls tunnelManager disconnect`() = runTest {
        viewModel.disconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { tunnelManager.disconnect() }
    }

    @Test
    fun `disconnect clears successful port`() = runTest {
        viewModel.disconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.clearSuccessfulPort() }
    }

    // ── replaceEndpointPort ───────────────────────────────────────────────────

    @Test
    fun `replaceEndpointPort swaps IPv4 endpoint port`() {
        val config = "Endpoint = 1.2.3.4:51820"
        val result = viewModel.replaceEndpointPort(config, 443)
        assertTrue(result.contains("1.2.3.4:443"), "Expected '1.2.3.4:443' in result: $result")
        assertFalse(result.contains(":51820"), "Old port should be gone")
    }

    @Test
    fun `replaceEndpointPort swaps IPv6 bracketed endpoint port`() {
        val config = "Endpoint = [2001:db8::1]:51820"
        val result = viewModel.replaceEndpointPort(config, 443)
        assertTrue(result.contains("[2001:db8::1]:443"), "Expected IPv6 endpoint with new port")
        assertFalse(result.contains(":51820"), "Old port should be gone")
    }

    @Test
    fun `replaceEndpointPort leaves non-Endpoint lines unchanged`() {
        val config = "PrivateKey = abc\nEndpoint = 1.2.3.4:51820\nAllowedIPs = 0.0.0.0/0"
        val result = viewModel.replaceEndpointPort(config, 8080)
        assertTrue(result.contains("PrivateKey = abc"))
        assertTrue(result.contains("AllowedIPs = 0.0.0.0/0"))
        assertTrue(result.contains("1.2.3.4:8080"))
    }

    @Test
    fun `replaceEndpointPort handles hostname endpoint`() {
        val config = "Endpoint = vpn.example.com:51820"
        val result = viewModel.replaceEndpointPort(config, 53)
        assertTrue(result.contains("vpn.example.com:53"))
        assertFalse(result.contains(":51820"))
    }

    // ── Kill-switch ───────────────────────────────────────────────────────────

    @Test
    fun `toggleKillSwitch true saves true to settings`() = runTest {
        viewModel.toggleKillSwitch(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.setKillSwitch(true) }
    }

    @Test
    fun `toggleKillSwitch false saves false to settings`() = runTest {
        viewModel.toggleKillSwitch(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsRepository.setKillSwitch(false) }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    @Test
    fun `loadPermissions updates license repository`() = runTest {
        val flags = PermissionFlags(services = true, traffic = true, dns = false, rdp = true)
        coEvery { apiClient.getPermissions() } returns PermissionsResponse(
            ok = true, permissions = flags, scopes = listOf("services", "traffic", "rdp"),
        )

        viewModel.loadPermissions()
        testDispatcher.scheduler.advanceUntilIdle()

        verify {
            licenseRepository.updatePermissions(
                services = true, traffic = true, dns = false, rdp = true,
            )
        }
    }

    @Test
    fun `loadPermissions updates permissions state flow`() = runTest {
        val flags = PermissionFlags(services = true, traffic = false, dns = true, rdp = false)
        coEvery { apiClient.getPermissions() } returns PermissionsResponse(
            ok = true, permissions = flags, scopes = listOf("services", "dns"),
        )

        viewModel.permissions.test {
            val initial = awaitItem()
            assertFalse(initial.services)

            viewModel.loadPermissions()
            testDispatcher.scheduler.advanceUntilIdle()

            val updated = awaitItem()
            assertTrue(updated.services)
            assertTrue(updated.dns)
            assertFalse(updated.traffic)
            assertFalse(updated.rdp)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadPermissions does nothing when server URL is empty`() = runTest {
        every { setupRepository.getServerUrl() } returns ""

        viewModel.loadPermissions()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { apiClient.getPermissions() }
    }
}
