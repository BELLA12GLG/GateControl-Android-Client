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
import com.gatecontrol.android.service.PortRotationCoordinator
import com.gatecontrol.android.service.TunnelConnector
import com.gatecontrol.android.tunnel.SplitTunnelConfig
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
    private lateinit var tunnelConnector: TunnelConnector
    private lateinit var portRotationCoordinator: PortRotationCoordinator
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
        tunnelConnector = mockk(relaxed = true)

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
        // Default: split-tunnel disabled. Individual tests can override.
        coEvery { tunnelConnector.resolveSplitTunnelConfig(any()) } returns SplitTunnelConfig()

        // v6: PortRotationCoordinator stub. VpnViewModel reads activePort/
        // currentAttempt as part of its public API surface (UI binds to them),
        // so they must be valid StateFlows even when the test doesn't care.
        portRotationCoordinator = mockk(relaxed = true)
        every { portRotationCoordinator.activePort } returns MutableStateFlow<Int?>(null)
        every { portRotationCoordinator.currentAttempt } returns
            MutableStateFlow<PortRotationCoordinator.AttemptInfo?>(null)

        viewModel = VpnViewModel(
            setupRepository = setupRepository,
            settingsRepository = settingsRepository,
            licenseRepository = licenseRepository,
            apiClientProvider = apiClientProvider,
            tunnelManager = tunnelManager,
            tunnelConnector = tunnelConnector,
            portRotationCoordinator = portRotationCoordinator,
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
    fun `connect delegates to PortRotationCoordinator`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns SAMPLE_WG_CONFIG
        coEvery { portRotationCoordinator.connectWithRetry(any(), any(), any(), any()) } returns
            PortRotationCoordinator.Outcome.Connected(51820)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            portRotationCoordinator.connectWithRetry(
                rawConfig = match { it.contains("Endpoint") },
                splitConfig = any(),
                maxAttempts = any(),
                preferredFirstPort = any(),
            )
        }
    }

    @Test
    fun `connect does nothing when config is empty`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns ""

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) {
            portRotationCoordinator.connectWithRetry(any(), any(), any(), any())
        }
    }

    @Test
    fun `connect passes persisted port to coordinator as preferredFirstPort`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns SAMPLE_WG_CONFIG
        every { settingsRepository.getLastSuccessfulPort() } returns flowOf(443)
        coEvery { portRotationCoordinator.connectWithRetry(any(), any(), any(), any()) } returns
            PortRotationCoordinator.Outcome.Connected(443)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            portRotationCoordinator.connectWithRetry(
                rawConfig = any(),
                splitConfig = any(),
                maxAttempts = any(),
                preferredFirstPort = 443,
            )
        }
    }

    @Test
    fun `connect passes null preferredFirstPort when no persisted port`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns SAMPLE_WG_CONFIG
        every { settingsRepository.getLastSuccessfulPort() } returns flowOf(0)
        coEvery { portRotationCoordinator.connectWithRetry(any(), any(), any(), any()) } returns
            PortRotationCoordinator.Outcome.Connected(51820)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            portRotationCoordinator.connectWithRetry(
                rawConfig = any(),
                splitConfig = any(),
                maxAttempts = any(),
                preferredFirstPort = null,
            )
        }
    }

    // Regression test for the "split-tunnel silently ignored" bug:
    // VpnViewModel must obtain its SplitTunnelConfig from TunnelConnector
    // (the single authoritative source), not by constructing an empty one
    // or duplicating the parsing logic. With the v6 coordinator refactor,
    // we verify the resolved SplitTunnelConfig is the one passed to the
    // coordinator (which in turn passes it to TunnelManager).
    @Test
    fun `connect uses split-tunnel config resolved by TunnelConnector`() = runTest {
        every { setupRepository.getWireGuardConfig() } returns SAMPLE_WG_CONFIG
        val resolved = SplitTunnelConfig(
            mode = "exclude",
            networks = listOf("192.168.1.0/24"),
            apps = listOf("com.example.app"),
        )
        coEvery { tunnelConnector.resolveSplitTunnelConfig(any()) } returns resolved
        coEvery { portRotationCoordinator.connectWithRetry(any(), any(), any(), any()) } returns
            PortRotationCoordinator.Outcome.Connected(51820)

        viewModel.connect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            portRotationCoordinator.connectWithRetry(
                rawConfig = any(),
                splitConfig = match<SplitTunnelConfig> {
                    it.mode == "exclude" &&
                        it.networks == listOf("192.168.1.0/24") &&
                        it.apps == listOf("com.example.app")
                },
                maxAttempts = any(),
                preferredFirstPort = any(),
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
    fun `disconnect resets coordinator's persisted port`() = runTest {
        // v6: clearing the persisted port now goes through the coordinator
        // (which owns activePort state). The coordinator's resetPersistedPort()
        // is what calls settingsRepository.clearSuccessfulPort() internally.
        viewModel.disconnect()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { portRotationCoordinator.resetPersistedPort() }
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
