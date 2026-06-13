package com.gatecontrol.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.tunnel.WgConfigValidator
import com.gatecontrol.android.common.Validation
import org.json.JSONArray
import org.json.JSONObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

enum class ConnectionTestStatus {
    Idle, Testing, Success, Failure
}

data class SettingsUiState(
    val theme: String = "dark",
    val locale: String = "de",
    val autoConnect: Boolean = false,
    val killSwitch: Boolean = false,
    // Deprecated v1 fields — retained for backward compatibility with persisted
    // preferences and test fixtures. The UI binds to splitTunnelMode (below);
    // these are no longer read by the connect path. See TunnelConnector.
    @Deprecated("Use splitTunnelMode instead", ReplaceWith("splitTunnelMode != \"off\""))
    val splitTunnelEnabled: Boolean = false,
    @Deprecated("Use splitTunnelNetworks instead")
    val splitTunnelRoutes: String = "",
    @Deprecated("Use splitTunnelAppsV2 instead")
    val splitTunnelApps: String = "",
    // V2 split-tunnel state (current). This is what the connect path reads.
    val splitTunnelMode: String = "off",
    val splitTunnelNetworks: List<NetworkEntry> = emptyList(),
    val splitTunnelAppsV2: List<String> = emptyList(),
    val splitTunnelAdminLocked: Boolean = false,
    val checkInterval: Int = 30,
    val configPollInterval: Int = 300,
    val serverUrl: String = "",
    val apiToken: String = "",
    val connectionTestStatus: ConnectionTestStatus = ConnectionTestStatus.Idle,
    val isLoading: Boolean = false,
    val appVersion: String = "",
    val error: String? = null,
    val success: String? = null,
    val isPro: Boolean = false,
    val licenseStatus: String = "",
    // Network preferences (v4.7)
    val ipProtocol: String = "auto",
    val dnsPrimary: String = "",
    val dnsSecondary: String = "",
    // Diagnostics (v6.2)
    val loggingEnabled: Boolean = true,
    // App-layer DNS (v6.4) — these affect OkHttp REST calls only,
    // NOT the WireGuard tunnel DNS.
    val staticHosts: Map<String, String> = emptyMap(),
    val dohUpstreamUrl: String = "",
    val dnsCacheEnabled: Boolean = true,
    val dnsCacheTtlSeconds: Int = 3600,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val apiClientProvider: ApiClientProvider,
    private val licenseRepository: LicenseRepository,
    private val dnsResolver: com.gatecontrol.android.network.DnsResolver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadInitialState()
        refreshLicense()
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            combine(
                settingsRepository.getTheme(),
                settingsRepository.getLocale(),
                settingsRepository.getAutoConnect(),
                settingsRepository.getKillSwitch(),
                settingsRepository.getSplitTunnelEnabled()
            ) { theme, locale, autoConnect, killSwitch, splitTunnelEnabled ->
                _uiState.update {
                    @Suppress("DEPRECATION")
                    it.copy(
                        theme = theme,
                        locale = locale,
                        autoConnect = autoConnect,
                        killSwitch = killSwitch,
                        splitTunnelEnabled = splitTunnelEnabled
                    )
                }
            }.collect {}
        }

        viewModelScope.launch {
            settingsRepository.getSplitTunnelRoutes().collect { routes ->
                @Suppress("DEPRECATION")
                _uiState.update { it.copy(splitTunnelRoutes = routes) }
            }
        }

        viewModelScope.launch {
            settingsRepository.getSplitTunnelApps().collect { apps ->
                @Suppress("DEPRECATION")
                _uiState.update { it.copy(splitTunnelApps = apps) }
            }
        }

        // V2 split-tunnel loading
        viewModelScope.launch {
            settingsRepository.migrateSplitTunnelIfNeeded()
            settingsRepository.getSplitTunnelMode().collect { mode ->
                _uiState.update { it.copy(splitTunnelMode = mode) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getSplitTunnelNetworks().collect { json ->
                val networks = parseSplitNetworksJson(json)
                _uiState.update { it.copy(splitTunnelNetworks = networks) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getSplitTunnelAppsV2().collect { json ->
                val apps = parseSplitAppsJson(json)
                _uiState.update { it.copy(splitTunnelAppsV2 = apps) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getSplitTunnelAdminLocked().collect { locked ->
                _uiState.update { it.copy(splitTunnelAdminLocked = locked) }
            }
        }

        viewModelScope.launch {
            settingsRepository.getCheckInterval().collect { interval ->
                _uiState.update { it.copy(checkInterval = interval) }
            }
        }

        viewModelScope.launch {
            settingsRepository.getConfigPollInterval().collect { interval ->
                _uiState.update { it.copy(configPollInterval = interval) }
            }
        }

        // Network preferences (v4.7)
        viewModelScope.launch {
            settingsRepository.getIpProtocol().collect { proto ->
                _uiState.update { it.copy(ipProtocol = proto) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getDnsPrimary().collect { addr ->
                _uiState.update { it.copy(dnsPrimary = addr) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getDnsSecondary().collect { addr ->
                _uiState.update { it.copy(dnsSecondary = addr) }
            }
        }
        viewModelScope.launch {
            settingsRepository.getLoggingEnabled().collect { on ->
                _uiState.update { it.copy(loggingEnabled = on) }
            }
        }

        // v6.4 — App-layer DNS preferences. Each preference has its own
        // collector so DataStore mutations are picked up independently,
        // but we MUST push the combined snapshot to DnsResolver on every
        // change so its asOkHttpDns adapter has the latest config.
        viewModelScope.launch {
            settingsRepository.getStaticHostsJson().collect { json ->
                val map = parseStaticHostsJsonForUi(json)
                _uiState.update { it.copy(staticHosts = map) }
                pushDnsResolverConfig()
            }
        }
        viewModelScope.launch {
            settingsRepository.getDohUpstreamUrl().collect { url ->
                _uiState.update { it.copy(dohUpstreamUrl = url) }
                pushDnsResolverConfig()
            }
        }
        viewModelScope.launch {
            settingsRepository.getDnsCacheEnabled().collect { on ->
                _uiState.update { it.copy(dnsCacheEnabled = on) }
                pushDnsResolverConfig()
            }
        }
        viewModelScope.launch {
            settingsRepository.getDnsCacheTtlSeconds().collect { ttl ->
                _uiState.update { it.copy(dnsCacheTtlSeconds = ttl) }
                pushDnsResolverConfig()
            }
        }

        _uiState.update {
            it.copy(
                serverUrl = setupRepository.getServerUrl(),
                apiToken = setupRepository.getApiToken()
            )
        }
    }

    /**
     * Read the current UiState DNS settings and push a fresh [DnsResolver.Config]
     * snapshot. Called from every DNS-preference collector AND from the explicit
     * setters below — DataStore writes propagate back through the collectors so
     * one of those paths is redundant in steady state, but the setter-side push
     * makes the change visible to in-flight OkHttp calls without waiting for
     * DataStore round-trip latency.
     */
    private fun pushDnsResolverConfig() {
        val s = _uiState.value
        dnsResolver.updateConfig(
            com.gatecontrol.android.network.DnsResolver.Config(
                staticHosts = com.gatecontrol.android.network.DnsResolver
                    .parseStaticHostsJson(
                        com.gatecontrol.android.network.DnsResolver
                            .encodeStaticHostsJson(s.staticHosts)
                    ),
                dohUpstreamUrl = s.dohUpstreamUrl,
                cacheEnabled = s.dnsCacheEnabled,
                cacheTtlSeconds = s.dnsCacheTtlSeconds,
            )
        )
    }

    private fun parseStaticHostsJsonForUi(json: String): Map<String, String> =
        com.gatecontrol.android.network.DnsResolver
            .parseStaticHostsJsonAsStringMap(json)

    fun setTheme(theme: String) {
        viewModelScope.launch {
            settingsRepository.setTheme(theme)
            _uiState.update { it.copy(theme = theme) }
        }
    }

    fun setLocale(locale: String) {
        viewModelScope.launch {
            settingsRepository.setLocale(locale)
            _uiState.update { it.copy(locale = locale) }
        }
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoConnect(enabled)
            _uiState.update { it.copy(autoConnect = enabled) }
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKillSwitch(enabled)
            _uiState.update { it.copy(killSwitch = enabled) }
        }
    }

    // ── Network preferences (v4.7) ─────────────────────────────────────────

    fun setIpProtocol(value: String) {
        viewModelScope.launch {
            settingsRepository.setIpProtocol(value)
            _uiState.update { it.copy(ipProtocol = value) }
        }
    }

    fun setDnsPrimary(value: String) {
        viewModelScope.launch {
            settingsRepository.setDnsPrimary(value)
            _uiState.update { it.copy(dnsPrimary = value.trim()) }
        }
    }

    fun setDnsSecondary(value: String) {
        viewModelScope.launch {
            settingsRepository.setDnsSecondary(value)
            _uiState.update { it.copy(dnsSecondary = value.trim()) }
        }
    }

    /**
     * Toggle the file-logging tree. Persists to DataStore and applies
     * immediately to [com.gatecontrol.android.FileLoggingTree] so the
     * change takes effect on the very next log line without restart.
     */
    fun setLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLoggingEnabled(enabled)
            com.gatecontrol.android.FileLoggingTree.setEnabled(enabled)
            _uiState.update { it.copy(loggingEnabled = enabled) }
        }
    }

    // ── App-layer DNS setters (v6.4) ─────────────────────────────────────────
    //
    // Each setter does three things in this order:
    //   1. Persist to DataStore (durable across restarts).
    //   2. Update _uiState (instant UI feedback, no DataStore round-trip).
    //   3. Push the new config to DnsResolver so in-flight OkHttp lookups
    //      see the change immediately, not after the collector fires.

    fun addStaticHost(host: String, ip: String) {
        val trimmedHost = host.trim().lowercase()
        val trimmedIp = ip.trim()
        if (trimmedHost.isBlank() || trimmedIp.isBlank()) return
        viewModelScope.launch {
            val current = _uiState.value.staticHosts.toMutableMap()
            current[trimmedHost] = trimmedIp
            val json = com.gatecontrol.android.network.DnsResolver
                .encodeStaticHostsJson(current)
            settingsRepository.setStaticHostsJson(json)
            _uiState.update { it.copy(staticHosts = current.toSortedMap().toMap()) }
            pushDnsResolverConfig()
            dnsResolver.clearCache()   // invalidate so the override takes effect now
        }
    }

    fun removeStaticHost(host: String) {
        val key = host.lowercase()
        viewModelScope.launch {
            val current = _uiState.value.staticHosts.toMutableMap()
            if (current.remove(key) == null) return@launch
            val json = com.gatecontrol.android.network.DnsResolver
                .encodeStaticHostsJson(current)
            settingsRepository.setStaticHostsJson(json)
            _uiState.update { it.copy(staticHosts = current.toMap()) }
            pushDnsResolverConfig()
            dnsResolver.clearCache()
        }
    }

    fun setDohUpstreamUrl(url: String) {
        val trimmed = url.trim()
        viewModelScope.launch {
            settingsRepository.setDohUpstreamUrl(trimmed)
            _uiState.update { it.copy(dohUpstreamUrl = trimmed) }
            pushDnsResolverConfig()
            dnsResolver.clearCache()   // results from old upstream may be wrong now
        }
    }

    fun setDnsCacheEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDnsCacheEnabled(enabled)
            _uiState.update { it.copy(dnsCacheEnabled = enabled) }
            pushDnsResolverConfig()
            if (!enabled) dnsResolver.clearCache()
        }
    }

    fun setDnsCacheTtlSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setDnsCacheTtlSeconds(seconds)
            _uiState.update { it.copy(dnsCacheTtlSeconds = seconds) }
            pushDnsResolverConfig()
            // Don't clear cache on TTL change — existing entries keep their
            // old expiry, new entries get the new TTL. Less surprising than
            // wiping a live cache when the user nudges a slider.
        }
    }

    /** User-facing "delete all cached entries". */
    fun clearDnsCache() {
        dnsResolver.clearCache()
    }

    /**
     * @deprecated UI no longer uses this toggle — it binds to [setSplitTunnelMode] instead.
     *   Retained only for SettingsViewModelTest backward compatibility. The connect path
     *   reads [SettingsRepository.getSplitTunnelMode], so this setter has no effect on
     *   whether split-tunnel is active.
     */
    @Deprecated("Use setSplitTunnelMode instead", ReplaceWith("setSplitTunnelMode(if (enabled) \"exclude\" else \"off\")"))
    fun setSplitTunnelEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSplitTunnelEnabled(enabled)
            @Suppress("DEPRECATION")
            _uiState.update { it.copy(splitTunnelEnabled = enabled) }
        }
    }

    fun setCheckInterval(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setCheckInterval(seconds)
        }
    }

    fun setConfigPollInterval(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setConfigPollInterval(seconds)
        }
    }

    fun testConnection(url: String, token: String) {
        viewModelScope.launch {
            val safeUrl = ensureHttps(url)
            if (safeUrl.isBlank()) {
                _uiState.update { it.copy(connectionTestStatus = ConnectionTestStatus.Failure) }
                return@launch
            }
            _uiState.update { it.copy(connectionTestStatus = ConnectionTestStatus.Testing) }
            try {
                val client = apiClientProvider.getClient(safeUrl)
                val response = client.ping()
                _uiState.update {
                    it.copy(
                        connectionTestStatus = if (response.ok) ConnectionTestStatus.Success
                        else ConnectionTestStatus.Failure
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Connection test failed")
                _uiState.update { it.copy(connectionTestStatus = ConnectionTestStatus.Failure) }
            }
        }
    }

    private fun ensureHttps(url: String): String {
        if (url.isBlank()) return url
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return "https://$url"
    }

    fun saveServer(url: String, token: String) {
        val url = ensureHttps(url)
        if (!Validation.validateServerUrl(url)) {
            _uiState.update { it.copy(error = "Invalid server URL") }
            return
        }
        if (!Validation.validateApiToken(token)) {
            _uiState.update { it.copy(error = "Invalid API token") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                apiClientProvider.invalidate()
                val client = apiClientProvider.getClient(url)

                val peerId = setupRepository.getPeerId()
                if (peerId > 0) {
                    client.ping()
                }

                setupRepository.save(url, token, peerId.coerceAtLeast(0))
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        serverUrl = url,
                        apiToken = token,
                        connectionTestStatus = ConnectionTestStatus.Success
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save server settings")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message,
                        connectionTestStatus = ConnectionTestStatus.Failure
                    )
                }
            }
        }
    }

    /** @deprecated Use [setSplitTunnelNetworks] instead. Kept for test backward compatibility. */
    @Deprecated("Use setSplitTunnelNetworks instead")
    fun saveSplitRoutes(routes: String) {
        val validRoutes = Validation.parseSplitRoutes(routes)
        val cleaned = validRoutes.joinToString("\n")
        viewModelScope.launch {
            settingsRepository.setSplitTunnelRoutes(cleaned)
            @Suppress("DEPRECATION")
            _uiState.update { it.copy(splitTunnelRoutes = cleaned) }
        }
    }

    /** @deprecated Use [setSplitTunnelAppsV2] instead. Kept for backward compatibility. */
    @Deprecated("Use setSplitTunnelAppsV2 instead")
    fun setSplitTunnelApps(apps: String) {
        viewModelScope.launch {
            settingsRepository.setSplitTunnelApps(apps)
            @Suppress("DEPRECATION")
            _uiState.update { it.copy(splitTunnelApps = apps) }
        }
    }

    fun setSplitTunnelMode(mode: String) {
        _uiState.update { it.copy(splitTunnelMode = mode) }
        viewModelScope.launch { settingsRepository.setSplitTunnelMode(mode) }
    }

    fun setSplitTunnelNetworks(networks: List<NetworkEntry>) {
        _uiState.update { it.copy(splitTunnelNetworks = networks) }
        viewModelScope.launch {
            val arr = JSONArray()
            networks.forEach { arr.put(JSONObject().put("cidr", it.cidr).put("label", it.label)) }
            settingsRepository.setSplitTunnelNetworks(arr.toString())
        }
    }

    fun setSplitTunnelAppsV2(apps: List<String>) {
        _uiState.update { it.copy(splitTunnelAppsV2 = apps) }
        viewModelScope.launch {
            val arr = JSONArray()
            apps.forEach { arr.put(JSONObject().put("package", it).put("label", "")) }
            settingsRepository.setSplitTunnelAppsV2(arr.toString())
        }
    }

    private fun parseSplitNetworksJson(json: String): List<NetworkEntry> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                NetworkEntry(obj.getString("cidr"), obj.optString("label", ""))
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Failed to parse split-tunnel networks JSON")
            emptyList()
        }
    }

    private fun parseSplitAppsJson(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("package") }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Failed to parse split-tunnel apps JSON")
            emptyList()
        }
    }

    fun refreshLicense() {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isBlank()) {
                    _uiState.update { it.copy(licenseStatus = "No server configured") }
                    return@launch
                }
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.getPermissions()
                if (response.ok) {
                    val perms = response.permissions
                    licenseRepository.updatePermissions(
                        services = perms.services,
                        traffic = perms.traffic,
                        dns = perms.dns,
                        rdp = perms.rdp,
                    )
                    val isPro = perms.rdp || perms.traffic || perms.dns
                    _uiState.update {
                        it.copy(
                            isPro = isPro,
                            licenseStatus = if (isPro) "Pro" else "Community",
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "License refresh failed")
                _uiState.update { it.copy(error = "License refresh failed: ${e.localizedMessage}") }
            }
        }
    }

    private val _requestFilePicker = kotlinx.coroutines.flow.MutableStateFlow(false)
    val requestFilePicker: kotlinx.coroutines.flow.StateFlow<Boolean> = _requestFilePicker

    fun requestConfigImport() {
        _requestFilePicker.value = true
    }

    fun onFilePickerLaunched() {
        _requestFilePicker.value = false
    }

    fun importConfigFromUri(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val input = context.contentResolver.openInputStream(uri)
                val config = input?.bufferedReader()?.readText() ?: return@launch
                input.close()
                val validation = WgConfigValidator.validate(config)
                if (!validation.ok) {
                    Timber.w("importConfigFromUri rejected: %s", validation.errors.joinToString(", "))
                    _uiState.update {
                        it.copy(error = context.getString(R.string.setup_invalid_config))
                    }
                    return@launch
                }
                setupRepository.saveWireGuardConfig(config)
                _uiState.update { it.copy(error = null, success = "Config imported successfully") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Import failed: ${e.message}") }
            }
        }
    }

    fun exportLogs(cacheDir: File): File? {
        return try {
            val logFile = File(cacheDir, "gatecontrol-logs.txt")
            val logDir = File(cacheDir, "logs")
            if (logDir.exists()) {
                val logs = logDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.firstOrNull()
                if (logs != null) {
                    logs.copyTo(logFile, overwrite = true)
                    logFile
                } else {
                    logFile.writeText("No logs available")
                    logFile
                }
            } else {
                logFile.writeText("No log directory found")
                logFile
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to export logs")
            null
        }
    }
}
