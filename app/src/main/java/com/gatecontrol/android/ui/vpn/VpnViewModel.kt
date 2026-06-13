package com.gatecontrol.android.ui.vpn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.PermissionFlags
import com.gatecontrol.android.network.TrafficStats
import com.gatecontrol.android.network.VpnService
import com.gatecontrol.android.service.PortRotationCoordinator
import com.gatecontrol.android.service.TunnelConnector
import com.gatecontrol.android.service.TunnelStateHolder
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.TunnelStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val licenseRepository: LicenseRepository,
    private val apiClientProvider: ApiClientProvider,
    private val tunnelManager: TunnelManager,
    private val tunnelConnector: TunnelConnector,
    // v6: single coordinator for all connect-with-retry flows.
    // Replaces VpnViewModel's own PortRotator + reconnectWithPort + manualRotate.
    private val portRotationCoordinator: PortRotationCoordinator,
) : ViewModel() {

    val tunnelState: StateFlow<TunnelState> = tunnelManager.state

    private val _stats = MutableStateFlow(TunnelStats())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    private val _trafficUsage = MutableStateFlow<TrafficStats?>(null)
    val trafficUsage: StateFlow<TrafficStats?> = _trafficUsage.asStateFlow()

    private val _permissions = MutableStateFlow(
        PermissionFlags(services = false, traffic = false, dns = false, rdp = false)
    )
    val permissions: StateFlow<PermissionFlags> = _permissions.asStateFlow()

    private val _services = MutableStateFlow<List<VpnService>>(emptyList())
    val services: StateFlow<List<VpnService>> = _services.asStateFlow()

    val killSwitchEnabled: StateFlow<Boolean> = settingsRepository.getKillSwitch()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var monitoringStarted = false

    private val _tokenInvalid = MutableStateFlow(false)
    val tokenInvalid: StateFlow<Boolean> = _tokenInvalid.asStateFlow()

    private val _peerDisabled = MutableStateFlow(false)
    val peerDisabled: StateFlow<Boolean> = _peerDisabled.asStateFlow()

    // ── 是否为纯 WireGuard 模式（无服务器） ─────────────────────────────────
    val isWireGuardOnlyMode: Boolean
        get() = setupRepository.isWireGuardOnlyMode()

    // ── Port rotation state — owned by PortRotationCoordinator ───────────────

    /** The port we're currently connected on (null = original / unknown). */
    val activePort: StateFlow<Int?> = portRotationCoordinator.activePort

    /** Attempt progress for the UI (e.g. "Trying port 443 (3/8)"). */
    val currentAttempt: StateFlow<PortRotationCoordinator.AttemptInfo?> =
        portRotationCoordinator.currentAttempt

    /**
     * One-shot toast messages — primarily used by Monitor-triggered reconnect
     * outcomes that the user should know about ("port rotation failed —
     * tunnel is down").
     */
    private val _toastMessages = MutableSharedFlow<ToastMessage>(extraBufferCapacity = 4)
    val toastMessages: SharedFlow<ToastMessage> = _toastMessages.asSharedFlow()

    /** Cached split-tunnel + network preferences from the most recent connect. */
    private var lastSplitTunnelConfig: SplitTunnelConfig = SplitTunnelConfig()

    /** Distinguishes the kind of reconnect outcome the UI should announce. */
    enum class ToastMessage {
        RECONNECT_FAILED,
        RECONNECT_EXHAUSTED,
        ROTATE_FAILED,
    }

    // ── Token 验证（仅服务器模式）────────────────────────────────────────────

    fun validateToken() {
        // 纯 WireGuard 模式无需验证 token
        if (setupRepository.isWireGuardOnlyMode()) return

        val serverUrl = setupRepository.getServerUrl()
        val token = setupRepository.getApiToken()
        if (serverUrl.isEmpty() || token.isEmpty()) return

        viewModelScope.launch {
            try {
                apiClientProvider.getClient(serverUrl).ping()
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401 || e.code() == 403) {
                    Timber.w("Token invalid (HTTP ${e.code()}) — clearing config")
                    setupRepository.clear()
                    apiClientProvider.invalidate()
                    _tokenInvalid.value = true
                }
            } catch (e: Exception) {
                Timber.d("Token validation skipped (offline): ${e.message}")
            }
        }
    }

    // ── 监控 ──────────────────────────────────────────────────────────────────

    fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

        // 仅服务器模式需要预解析 DNS
        if (!setupRepository.isWireGuardOnlyMode()) {
            viewModelScope.launch {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
                    try {
                        val host = java.net.URI(serverUrl).host
                        if (host != null) apiClientProvider.preResolveDns(host)
                    } catch (_: Exception) {}
                }
            }
        }

        viewModelScope.launch {
            tunnelManager.state.collect { state ->
                TunnelStateHolder.isConnected = state is TunnelState.Connected
                TunnelStateHolder.serverHost = serverHost
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                if (tunnelState.value is TunnelState.Connected) {
                    tunnelManager.getStatistics()?.let { _stats.value = it }
                }
            }
        }

        // 对等节点检查仅在服务器模式下执行
        if (!setupRepository.isWireGuardOnlyMode()) {
            viewModelScope.launch {
                while (isActive) {
                    delay(60_000)
                    if (tunnelState.value is TunnelState.Connected) checkPeerEnabled()
                }
            }
        }
    }

    // ── 连接 / 断开 ───────────────────────────────────────────────────────────

    /**
     * Connect the tunnel. Delegates to [PortRotationCoordinator] which:
     *   1. tries a persisted "last successful port" first (if any),
     *   2. falls back to the original port,
     *   3. rotates through [com.gatecontrol.android.tunnel.PortStrategy]
     *      candidates if the above fail.
     *
     * All retry logic, persistence, and progress tracking live in the
     * coordinator — this method just wires the WireGuard config + split
     * tunnel config in.
     */
    fun connect() {
        viewModelScope.launch {
            val rawConfig = setupRepository.getWireGuardConfig()
            if (rawConfig.isEmpty()) {
                Timber.w("VpnViewModel: no WireGuard config available")
                return@launch
            }

            // Resolve split-tunnel + network preferences via the single
            // authoritative source (TunnelConnector) — same path used by
            // BootReceiver, Tile, and Monitor-triggered reconnect.
            val serverUrl = setupRepository.getServerUrl()
            val splitConfig = tunnelConnector.resolveSplitTunnelConfig(serverUrl)
            lastSplitTunnelConfig = splitConfig

            val persistedPort = settingsRepository.getLastSuccessfulPort().first()
            val preferred = persistedPort.takeIf { it != 0 }

            val outcome = portRotationCoordinator.connectWithRetry(
                rawConfig = rawConfig,
                splitConfig = splitConfig,
                preferredFirstPort = preferred,
            )
            when (outcome) {
                is PortRotationCoordinator.Outcome.Connected -> {
                    if (!setupRepository.isWireGuardOnlyMode()) {
                        reportDeviceHostname(serverUrl)
                    }
                }
                is PortRotationCoordinator.Outcome.Failed,
                is PortRotationCoordinator.Outcome.Exhausted -> {
                    Timber.e("VpnViewModel: initial connect failed — outcome=$outcome")
                }
                PortRotationCoordinator.Outcome.NoConfig -> Unit
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                tunnelManager.disconnect()
                _stats.value = TunnelStats()
                if (!setupRepository.isWireGuardOnlyMode()) {
                    apiClientProvider.clearDnsCache()
                }
                resetPortState()
                Timber.d("VpnViewModel: tunnel disconnected")
            } catch (e: Exception) {
                Timber.e(e, "VpnViewModel: disconnect failed")
            }
        }
    }

    // ── Port rotation orchestration ─────────────────────────────────────────

    /**
     * Entry point invoked by VpnForegroundService when [TunnelMonitor] emits
     * a stall event. Re-resolves the latest split-tunnel + network preferences
     * (they might have changed since [connect]), then asks the coordinator to
     * reconnect, starting fresh from the persisted last-successful port.
     *
     * Toast is fired on failure so the user knows the auto-recovery failed —
     * unlike the v1 behavior where reconnect failures were silent except in
     * the log.
     */
    suspend fun handleMonitorStall() {
        Timber.i("VpnViewModel: monitor reported stall — re-running connectWithRetry")
        val rawConfig = setupRepository.getWireGuardConfig()
        if (rawConfig.isEmpty()) return

        // disconnect first so the new connect doesn't fight a half-alive
        // tunnel — GoBackend gets unhappy with overlapping setState calls.
        try { tunnelManager.disconnect() } catch (_: Exception) {}
        delay(300L) // mirror the legacy "give VPN fd time to release" delay

        val serverUrl = setupRepository.getServerUrl()
        val splitConfig = tunnelConnector.resolveSplitTunnelConfig(serverUrl)
        lastSplitTunnelConfig = splitConfig

        val persisted = settingsRepository.getLastSuccessfulPort().first().takeIf { it != 0 }
        val outcome = portRotationCoordinator.connectWithRetry(
            rawConfig = rawConfig,
            splitConfig = splitConfig,
            preferredFirstPort = persisted,
        )
        when (outcome) {
            is PortRotationCoordinator.Outcome.Connected -> {
                Timber.i("VpnViewModel: monitor-triggered reconnect succeeded port=${outcome.port}")
            }
            is PortRotationCoordinator.Outcome.Failed -> {
                _toastMessages.tryEmit(ToastMessage.RECONNECT_FAILED)
            }
            is PortRotationCoordinator.Outcome.Exhausted -> {
                _toastMessages.tryEmit(ToastMessage.RECONNECT_EXHAUSTED)
            }
            PortRotationCoordinator.Outcome.NoConfig -> Unit
        }
    }

    /**
     * User-initiated "try a different port" — abandons the current port and
     * runs the coordinator's retry loop, forcing the strategy to skip BOTH
     * the currently active port AND the original WG-config port.
     *
     * Why exclude both:
     *   v6.1 only excluded the active port. Result: if the user rotated
     *   51820 → 443, then clicked rotate again, Coordinator would see
     *   `activePort=443, excludePorts={443}` and fall back to `originalPort
     *   = 51820` (which is NOT in excludePorts). So rotate-from-443 would
     *   land on 51820 — appearing to "rotate back to the original port".
     *
     *   The user's intent for manual rotate is "try a DIFFERENT port" not
     *   "any port that isn't this one". Excluding both ports the user has
     *   already seen forces PortStrategy to pick a third option.
     */
    fun manualRotatePort() {
        viewModelScope.launch {
            Timber.i("VpnViewModel: manual port rotate requested")
            val rawConfig = setupRepository.getWireGuardConfig()
            if (rawConfig.isEmpty()) return@launch

            // Capture the port we want to rotate AWAY from BEFORE we
            // disconnect — disconnect() may null _activePort.
            val currentPort = portRotationCoordinator.activePort.value

            // Also exclude the original config port — see kdoc above.
            val originalPort = try {
                com.gatecontrol.android.tunnel.TunnelConfig.parse(rawConfig).getServerPort()
            } catch (_: Exception) { 51820 }

            val excludeSet = buildSet<Int> {
                if (currentPort != null) add(currentPort)
                add(originalPort)
            }
            Timber.d("VpnViewModel: rotate excluding ports $excludeSet (current=$currentPort, original=$originalPort)")

            try { tunnelManager.disconnect() } catch (_: Exception) {}
            delay(300L)

            val serverUrl = setupRepository.getServerUrl()
            val splitConfig = tunnelConnector.resolveSplitTunnelConfig(serverUrl)
            lastSplitTunnelConfig = splitConfig

            val outcome = portRotationCoordinator.connectWithRetry(
                rawConfig = rawConfig,
                splitConfig = splitConfig,
                preferredFirstPort = null,
                excludePorts = excludeSet,
            )
            when (outcome) {
                is PortRotationCoordinator.Outcome.Connected -> Unit
                else -> _toastMessages.tryEmit(ToastMessage.ROTATE_FAILED)
            }
        }
    }

    private suspend fun resetPortState() {
        portRotationCoordinator.resetPersistedPort()
        Timber.d("VpnViewModel: port state reset to original")
    }

    // ── 对等节点检查（仅服务器模式）──────────────────────────────────────────

    private suspend fun checkPeerEnabled() {
        try {
            val serverUrl = setupRepository.getServerUrl()
            if (serverUrl.isEmpty()) return
            val peerId = setupRepository.getPeerId()
            if (peerId <= 0) return
            val response = apiClientProvider.getClient(serverUrl).getPeerInfo(peerId)
            if (response.ok && !response.peer.enabled) {
                Timber.w("Peer disabled on server (id=$peerId) — disconnecting tunnel")
                tunnelManager.disconnect()
                _stats.value = TunnelStats()
                apiClientProvider.clearDnsCache()
                resetPortState()
                _peerDisabled.value = true
            }
        } catch (e: Exception) {
            Timber.d("Peer status check failed (offline): ${e.message}")
        }
    }

    // ── 辅助功能 ──────────────────────────────────────────────────────────────

    fun toggleKillSwitch(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKillSwitch(enabled)
            Timber.d("VpnViewModel: kill-switch set to $enabled")
        }
    }

    fun loadTrafficStats() {
        if (setupRepository.isWireGuardOnlyMode()) return
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) return@launch
                val peerId = setupRepository.getPeerId()
                if (peerId <= 0) return@launch
                val response = apiClientProvider.getClient(serverUrl).getTraffic(peerId)
                if (response.ok) _trafficUsage.value = response.traffic
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: failed to load traffic stats")
            }
        }
    }

    fun loadServices() {
        if (setupRepository.isWireGuardOnlyMode()) return
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) return@launch
                val response = apiClientProvider.getClient(serverUrl).getServices()
                if (response.ok) _services.value = response.services
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: failed to load services")
            }
        }
    }

    val serverHost: String?
        get() {
            val config = setupRepository.getWireGuardConfig()
            if (config.isEmpty()) return null
            return try {
                TunnelConfig.parse(config).getServerHost()
            } catch (_: Exception) { null }
        }

    fun runDnsLeakTest(onResult: (String) -> Unit) {
        if (setupRepository.isWireGuardOnlyMode()) {
            onResult("DNS leak test not available in WireGuard-only mode")
            return
        }
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) { onResult("No server configured"); return@launch }
                val response = apiClientProvider.getClient(serverUrl).dnsCheck()
                onResult(
                    if (response.ok) "DNS: ${response.vpnDns} (Subnet: ${response.vpnSubnet})"
                    else "DNS check failed"
                )
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: DNS leak test failed")
                onResult("DNS test error: ${e.localizedMessage}")
            }
        }
    }

    fun invalidateApiClients() = apiClientProvider.invalidate()

    fun loadPermissions() {
        if (setupRepository.isWireGuardOnlyMode()) return
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) return@launch
                val response = apiClientProvider.getClient(serverUrl).getPermissions()
                if (response.ok) {
                    val flags = response.permissions
                    _permissions.value = flags
                    licenseRepository.updatePermissions(
                        services = flags.services,
                        traffic = flags.traffic,
                        dns = flags.dns,
                        rdp = flags.rdp,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: failed to load permissions")
            }
        }
    }

    private fun reportDeviceHostname(serverUrl: String) {
        if (serverUrl.isEmpty() || (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://"))) {
            return
        }
        viewModelScope.launch {
            try {
                val sanitized = com.gatecontrol.android.common.HostnameSanitizer
                    .sanitize(android.os.Build.MODEL)
                if (sanitized.isNullOrBlank()) return@launch
                val response = apiClientProvider.getClient(serverUrl)
                    .reportHostname(com.gatecontrol.android.network.HostnameReportRequest(sanitized))
                Timber.d("Hostname report: assigned=${response.assigned} changed=${response.changed}")
            } catch (e: Exception) {
                Timber.d(e, "Hostname report skipped: ${e.message}")
            }
        }
    }
}
