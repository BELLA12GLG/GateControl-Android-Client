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
import com.gatecontrol.android.service.TunnelStateHolder
import com.gatecontrol.android.tunnel.PortRotator
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tunnel.TunnelStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val licenseRepository: LicenseRepository,
    private val apiClientProvider: ApiClientProvider,
    private val tunnelManager: TunnelManager,
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

    // ── Port rotation state ───────────────────────────────────────────────────

    private val _activePort = MutableStateFlow<Int?>(null)
    val activePort: StateFlow<Int?> = _activePort.asStateFlow()

    private var activePort: Int?
        get() = _activePort.value
        set(value) { _activePort.value = value }

    private var portRotator: PortRotator? = null
    private var lastSplitTunnelConfig: SplitTunnelConfig = SplitTunnelConfig()

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

        viewModelScope.launch {
            TunnelStateHolder.reconnectEvents.collect { req ->
                Timber.i(
                    "VpnViewModel: reconnect requested attempt=${req.attempt}/${req.maxAttempts}" +
                        " suggestedPort=${req.suggestedPort}"
                )
                reconnectWithPort(req.suggestedPort)
            }
        }
    }

    // ── 连接 / 断开 ───────────────────────────────────────────────────────────

    fun connect() {
        viewModelScope.launch {
            val rawConfig = setupRepository.getWireGuardConfig()
            if (rawConfig.isEmpty()) {
                Timber.w("VpnViewModel: no WireGuard config available")
                return@launch
            }

            val originalPort = try {
                TunnelConfig.parse(rawConfig).getServerPort()
            } catch (_: Exception) { 51820 }

            portRotator = PortRotator(originalPort)

            val lastSuccessfulPort = settingsRepository.getLastSuccessfulPort().first()
            val configToConnect = if (lastSuccessfulPort != 0 && lastSuccessfulPort != originalPort) {
                Timber.d("VpnViewModel: trying persisted port $lastSuccessfulPort")
                activePort = lastSuccessfulPort
                replaceEndpointPort(rawConfig, lastSuccessfulPort)
            } else {
                activePort = null
                rawConfig
            }

            // 服务器模式：拉取 split-tunnel preset；纯 WG 模式：用空配置
            val serverUrl = setupRepository.getServerUrl()
            val splitConfig = if (setupRepository.isWireGuardOnlyMode()) {
                SplitTunnelConfig()
            } else {
                // 服务器模式：预解析 DNS
                if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
                    try {
                        val host = java.net.URI(serverUrl).host
                        if (host != null) apiClientProvider.preResolveDns(host)
                    } catch (_: Exception) {}
                }
                resolveSplitTunnelConfig(serverUrl)
            }
            lastSplitTunnelConfig = splitConfig

            // 首次连接：失败后自动轮换端口重试，最多尝试5个候选端口
            var connected = false
            try {
                tunnelManager.connect(configToConnect, splitConfig)
                connected = true
                Timber.d("VpnViewModel: tunnel connect requested")
                if (!setupRepository.isWireGuardOnlyMode()) {
                    reportDeviceHostname(serverUrl)
                }
            } catch (e: Exception) {
                Timber.w(e, "VpnViewModel: initial connect failed, starting port rotation")
            }

            if (!connected) {
                val rotator = portRotator ?: return@launch
                val maxAttempts = minOf(rotator.candidateCount, 5)
                for (attempt in 1..maxAttempts) {
                    val nextPort = rotator.nextPort()
                    Timber.d("VpnViewModel: retry attempt=$attempt port=$nextPort")
                    val retryConfig = replaceEndpointPort(rawConfig, nextPort)
                    try {
                        tunnelManager.connect(retryConfig, splitConfig)
                        activePort = nextPort
                        settingsRepository.saveSuccessfulPort(nextPort)
                        Timber.i("VpnViewModel: connected on retry port=$nextPort")
                        connected = true
                        if (!setupRepository.isWireGuardOnlyMode()) {
                            reportDeviceHostname(serverUrl)
                        }
                        break
                    } catch (e: Exception) {
                        Timber.w(e, "VpnViewModel: retry failed on port=$nextPort")
                        delay(1_000L * attempt) // 递增等待：1s, 2s, 3s...
                    }
                }
                if (!connected) {
                    Timber.e("VpnViewModel: all initial connect attempts failed")
                }
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

    // ── Port rotation ─────────────────────────────────────────────────────────

    private suspend fun reconnectWithPort(port: Int?) {
        val rawConfig = setupRepository.getWireGuardConfig()
        if (rawConfig.isEmpty()) return

        val configToUse = if (port != null) {
            activePort = port
            replaceEndpointPort(rawConfig, port)
        } else {
            activePort = null
            rawConfig
        }

        try {
            tunnelManager.connect(configToUse, lastSplitTunnelConfig)
            Timber.d("VpnViewModel: reconnected on port ${port ?: "original"}")
            if (port != null) {
                settingsRepository.saveSuccessfulPort(port)
                Timber.i("VpnViewModel: persisted successful port $port")
            }
        } catch (e: Exception) {
            Timber.e(e, "VpnViewModel: reconnect failed on port $port")
        }
    }

    internal fun replaceEndpointPort(config: String, port: Int): String =
        config.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("Endpoint", ignoreCase = true) && trimmed.contains("=")) {
                val eqIndex = line.indexOf('=')
                val prefix = line.substring(0, eqIndex + 1)
                val value = line.substring(eqIndex + 1).trim()
                val hostPart = when {
                    value.startsWith("[") -> value.substringBeforeLast(":")
                    else -> value.substringBeforeLast(":")
                }
                "$prefix $hostPart:$port"
            } else {
                line
            }
        }

    private suspend fun resetPortState() {
        activePort = null
        portRotator?.reset()
        settingsRepository.clearSuccessfulPort()
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

    // ── Split-tunnel（仅服务器模式）───────────────────────────────────────────

    private suspend fun resolveSplitTunnelConfig(serverUrl: String): SplitTunnelConfig {
        var splitTunnelConfig = SplitTunnelConfig()
        try {
            var adminPresetActive = false
            if (serverUrl.isNotEmpty()) {
                try {
                    val client = apiClientProvider.getClient(serverUrl)
                    val preset = client.getSplitTunnelPreset()
                    if (preset.ok && preset.mode != "off" && preset.source != "none") {
                        settingsRepository.setSplitTunnelMode(preset.mode)
                        val arr = JSONArray()
                        preset.networks.forEach {
                            arr.put(JSONObject().put("cidr", it.cidr).put("label", it.label))
                        }
                        settingsRepository.setSplitTunnelNetworks(arr.toString())
                        settingsRepository.setSplitTunnelAdminLocked(preset.locked)
                        adminPresetActive = true
                        val userApps = settingsRepository.getSplitTunnelAppsV2().first()
                        splitTunnelConfig = SplitTunnelConfig(
                            mode = preset.mode,
                            networks = preset.networks.map { it.cidr },
                            apps = parseSplitAppsJson(userApps),
                        )
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Split-tunnel preset fetch failed")
                }
            }
            if (!adminPresetActive) {
                val mode = settingsRepository.getSplitTunnelMode().first()
                if (mode != "off") {
                    val networksJson = settingsRepository.getSplitTunnelNetworks().first()
                    val appsJson = settingsRepository.getSplitTunnelAppsV2().first()
                    splitTunnelConfig = SplitTunnelConfig(
                        mode = mode,
                        networks = parseSplitNetworksJsonToCidrs(networksJson),
                        apps = parseSplitAppsJson(appsJson),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Split-tunnel config load failed")
        }
        return splitTunnelConfig
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

    private fun parseSplitNetworksJsonToCidrs(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("cidr") }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse split-tunnel networks JSON")
            emptyList()
        }
    }

    private fun parseSplitAppsJson(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("package") }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse split-tunnel apps JSON")
            emptyList()
        }
    }
}
