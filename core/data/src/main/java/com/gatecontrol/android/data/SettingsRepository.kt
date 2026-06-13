package com.gatecontrol.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    companion object {
        val THEME = stringPreferencesKey("theme")
        val LOCALE = stringPreferencesKey("locale")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val KILL_SWITCH = booleanPreferencesKey("kill_switch")
        val SPLIT_TUNNEL_ENABLED = booleanPreferencesKey("split_tunnel_enabled")
        val SPLIT_TUNNEL_ROUTES = stringPreferencesKey("split_tunnel_routes")
        val SPLIT_TUNNEL_APPS = stringPreferencesKey("split_tunnel_apps")
        // New split-tunnel keys (v2 JSON format)
        val SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
        val SPLIT_TUNNEL_NETWORKS = stringPreferencesKey("split_tunnel_networks")
        val SPLIT_TUNNEL_APPS_V2 = stringPreferencesKey("split_tunnel_apps_v2")
        val SPLIT_TUNNEL_ADMIN_LOCKED = booleanPreferencesKey("split_tunnel_admin_locked")
        val CHECK_INTERVAL = intPreferencesKey("check_interval")
        val CONFIG_POLL_INTERVAL = intPreferencesKey("config_poll_interval")
        // Network preferences (v4.7)
        // IP_PROTOCOL — one of: "auto" (default, IPv4-preferred dual-stack),
        //   "ipv6_preferred", "ipv4_only", "ipv6_only".
        // DNS_PRIMARY / DNS_SECONDARY — empty string means "use system DNS".
        //   When non-empty, must be a valid IPv4 or IPv6 address.
        val IP_PROTOCOL = stringPreferencesKey("ip_protocol")
        val DNS_PRIMARY = stringPreferencesKey("dns_primary")
        val DNS_SECONDARY = stringPreferencesKey("dns_secondary")
        // v6.2: lets the user disable file logging from Settings. Default
        // is true (logging on) so behavior matches pre-v6.2. Logcat output
        // (Timber.DebugTree) is unaffected — only FileLoggingTree honors it.
        val LOGGING_ENABLED = booleanPreferencesKey("logging_enabled")
        // v6.4: App-layer DNS resolution preferences
        // STATIC_HOSTS_JSON: JSON-encoded Map<String, String> of host → IP.
        //   Empty string = "{}" = no static entries.
        // DOH_UPSTREAM_URL: e.g. "https://1.1.1.1/dns-query" — empty = system DNS.
        // DNS_CACHE_ENABLED / DNS_CACHE_TTL_SECONDS: per-host result cache in
        //   OkHttp Dns. TTL is the user-controlled global, not DNS-record TTL.
        val STATIC_HOSTS_JSON = stringPreferencesKey("static_hosts_json")
        val DOH_UPSTREAM_URL = stringPreferencesKey("doh_upstream_url")
        val DNS_CACHE_ENABLED = booleanPreferencesKey("dns_cache_enabled")
        val DNS_CACHE_TTL_SECONDS = intPreferencesKey("dns_cache_ttl_seconds")
    }

    fun getTheme(): Flow<String> = dataStore.data.map { it[THEME] ?: "system" }

    fun getLocale(): Flow<String> = dataStore.data.map { prefs ->
        prefs[LOCALE] ?: run {
            // Default to system language, fall back to English if not supported
            val sysLang = java.util.Locale.getDefault().language
            if (sysLang == "de") "de" else "en"
        }
    }

    fun getAutoConnect(): Flow<Boolean> = dataStore.data.map { it[AUTO_CONNECT] ?: false }

    fun getKillSwitch(): Flow<Boolean> = dataStore.data.map { it[KILL_SWITCH] ?: false }

    fun getSplitTunnelEnabled(): Flow<Boolean> =
        dataStore.data.map { it[SPLIT_TUNNEL_ENABLED] ?: false }

    fun getSplitTunnelRoutes(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_ROUTES] ?: "" }

    fun getSplitTunnelApps(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_APPS] ?: "" }

    // New split-tunnel getters (v2 JSON format)
    fun getSplitTunnelMode(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_MODE] ?: "off" }

    fun getSplitTunnelNetworks(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_NETWORKS] ?: "[]" }

    fun getSplitTunnelAppsV2(): Flow<String> =
        dataStore.data.map { it[SPLIT_TUNNEL_APPS_V2] ?: "[]" }

    fun getSplitTunnelAdminLocked(): Flow<Boolean> =
        dataStore.data.map { it[SPLIT_TUNNEL_ADMIN_LOCKED] ?: false }

    fun getCheckInterval(): Flow<Int> = dataStore.data.map { it[CHECK_INTERVAL] ?: 30 }

    fun getConfigPollInterval(): Flow<Int> =
        dataStore.data.map { it[CONFIG_POLL_INTERVAL] ?: 300 }

    suspend fun setTheme(value: String) {
        dataStore.edit { it[THEME] = value }
    }

    suspend fun setLocale(value: String) {
        dataStore.edit { it[LOCALE] = value }
    }

    suspend fun setAutoConnect(value: Boolean) {
        dataStore.edit { it[AUTO_CONNECT] = value }
    }

    suspend fun setKillSwitch(value: Boolean) {
        dataStore.edit { it[KILL_SWITCH] = value }
    }

    suspend fun setSplitTunnelEnabled(value: Boolean) {
        dataStore.edit { it[SPLIT_TUNNEL_ENABLED] = value }
    }

    suspend fun setSplitTunnelRoutes(value: String) {
        dataStore.edit { it[SPLIT_TUNNEL_ROUTES] = value }
    }

    suspend fun setSplitTunnelApps(value: String) {
        dataStore.edit { it[SPLIT_TUNNEL_APPS] = value }
    }

    // New split-tunnel setters (v2 JSON format)
    suspend fun setSplitTunnelMode(mode: String) {
        dataStore.edit { it[SPLIT_TUNNEL_MODE] = mode }
    }

    suspend fun setSplitTunnelNetworks(json: String) {
        dataStore.edit { it[SPLIT_TUNNEL_NETWORKS] = json }
    }

    suspend fun setSplitTunnelAppsV2(json: String) {
        dataStore.edit { it[SPLIT_TUNNEL_APPS_V2] = json }
    }

    suspend fun setSplitTunnelAdminLocked(locked: Boolean) {
        dataStore.edit { it[SPLIT_TUNNEL_ADMIN_LOCKED] = locked }
    }

    /**
     * Migrates old split-tunnel preferences (v1) to new JSON format (v2).
     * Only runs once: when old keys exist but new keys don't.
     */
    suspend fun migrateSplitTunnelIfNeeded() {
        dataStore.edit { prefs ->
            val oldEnabled = prefs[SPLIT_TUNNEL_ENABLED]
            if (oldEnabled != null && prefs[SPLIT_TUNNEL_MODE] == null) {
                // Mode: old enabled=true was include-mode (only these routes through VPN)
                prefs[SPLIT_TUNNEL_MODE] = if (oldEnabled) "include" else "off"

                // Routes: newline/comma-separated CIDRs → JSON with empty labels
                val oldRoutes = prefs[SPLIT_TUNNEL_ROUTES] ?: ""
                if (oldRoutes.isNotBlank()) {
                    val networks = oldRoutes.split("\n", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { """{"cidr":"$it","label":""}""" }
                    prefs[SPLIT_TUNNEL_NETWORKS] = "[${networks.joinToString(",")}]"
                }

                // Apps: comma/newline-separated packages → JSON with empty labels
                val oldApps = prefs[SPLIT_TUNNEL_APPS] ?: ""
                if (oldApps.isNotBlank()) {
                    val apps = oldApps.split("\n", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { """{"package":"$it","label":""}""" }
                    prefs[SPLIT_TUNNEL_APPS_V2] = "[${apps.joinToString(",")}]"
                }

                // Clear old keys
                prefs.remove(SPLIT_TUNNEL_ENABLED)
                prefs.remove(SPLIT_TUNNEL_ROUTES)
                prefs.remove(SPLIT_TUNNEL_APPS)
            }
        }
    }

    suspend fun setCheckInterval(value: Int) {
        val clamped = value.coerceIn(5, 300)
        dataStore.edit { it[CHECK_INTERVAL] = clamped }
    }

    suspend fun setConfigPollInterval(value: Int) {
        val clamped = value.coerceIn(30, 3600)
        dataStore.edit { it[CONFIG_POLL_INTERVAL] = clamped }
    }

    // ── Network preferences (v4.7) ─────────────────────────────────────────
    // IP_PROTOCOL accepted values: "auto", "ipv6_preferred", "ipv4_only", "ipv6_only".
    // "auto" matches the legacy behavior (IPv4-preferred dual stack).

    fun getIpProtocol(): Flow<String> =
        dataStore.data.map { it[IP_PROTOCOL] ?: "auto" }

    suspend fun setIpProtocol(value: String) {
        val allowed = setOf("auto", "ipv6_preferred", "ipv4_only", "ipv6_only")
        val normalized = if (value in allowed) value else "auto"
        dataStore.edit { it[IP_PROTOCOL] = normalized }
    }

    // Empty string means "use system DNS". Tunnel layer is responsible for
    // applying these via wg-quick's DNS= field or VpnService.Builder.addDnsServer.
    fun getDnsPrimary(): Flow<String> =
        dataStore.data.map { it[DNS_PRIMARY] ?: "" }

    fun getDnsSecondary(): Flow<String> =
        dataStore.data.map { it[DNS_SECONDARY] ?: "" }

    suspend fun setDnsPrimary(value: String) {
        dataStore.edit { it[DNS_PRIMARY] = value.trim() }
    }

    suspend fun setDnsSecondary(value: String) {
        dataStore.edit { it[DNS_SECONDARY] = value.trim() }
    }

    // ── Logging toggle (v6.2) ─────────────────────────────────────────────
    // FileLoggingTree honors this flag via a @Volatile read on every log line;
    // when off, file writes are skipped entirely. Logcat (Timber.DebugTree on
    // debuggable builds) is unaffected.

    fun getLoggingEnabled(): Flow<Boolean> =
        dataStore.data.map { it[LOGGING_ENABLED] ?: true }

    suspend fun setLoggingEnabled(value: Boolean) {
        dataStore.edit { it[LOGGING_ENABLED] = value }
    }

    // ── App-layer DNS preferences (v6.4) ──────────────────────────────────
    // These control how the App's OkHttp REST calls resolve hostnames.
    // They are SEPARATE from the WireGuard tunnel DNS (DNS_PRIMARY/SECONDARY).

    /** JSON-encoded Map<String, String> of static host → IP entries. */
    fun getStaticHostsJson(): Flow<String> =
        dataStore.data.map { it[STATIC_HOSTS_JSON] ?: "{}" }

    suspend fun setStaticHostsJson(json: String) {
        dataStore.edit { it[STATIC_HOSTS_JSON] = json }
    }

    /** DoH endpoint URL, e.g. "https://1.1.1.1/dns-query". Empty = system DNS. */
    fun getDohUpstreamUrl(): Flow<String> =
        dataStore.data.map { it[DOH_UPSTREAM_URL] ?: "" }

    suspend fun setDohUpstreamUrl(url: String) {
        dataStore.edit { it[DOH_UPSTREAM_URL] = url.trim() }
    }

    /** Whether the app caches DNS results. Default true. */
    fun getDnsCacheEnabled(): Flow<Boolean> =
        dataStore.data.map { it[DNS_CACHE_ENABLED] ?: true }

    suspend fun setDnsCacheEnabled(value: Boolean) {
        dataStore.edit { it[DNS_CACHE_ENABLED] = value }
    }

    /** Cache TTL in seconds. Default 3600 (1 hour). User-selectable presets:
     *  300 (5 min), 1800 (30 min), 3600 (1 hour), 21600 (6 hour). */
    fun getDnsCacheTtlSeconds(): Flow<Int> =
        dataStore.data.map { it[DNS_CACHE_TTL_SECONDS] ?: 3600 }

    suspend fun setDnsCacheTtlSeconds(value: Int) {
        // Clamp to a sane range — the UI presents 4 fixed options but we
        // accept anything in [60, 86400] (1 min - 1 day) for forward compat.
        val clamped = value.coerceIn(60, 86_400)
        dataStore.edit { it[DNS_CACHE_TTL_SECONDS] = clamped }
    }

    // ── Port rotation（内存持有，不写 DataStore）─────────────────────────────
    // 频繁写入 DataStore 会增加强制重启时 proto 文件损坏的概率。
    // 该值只是本次运行的优化提示：跳过端口重试，进程重启后从原始端口重新探测即可。

    @Volatile private var lastSuccessfulPort: Int = 0

    fun getLastSuccessfulPort(): Flow<Int> =
        kotlinx.coroutines.flow.flow { emit(lastSuccessfulPort) }

    suspend fun saveSuccessfulPort(port: Int) {
        lastSuccessfulPort = port
    }

    suspend fun clearSuccessfulPort() {
        lastSuccessfulPort = 0
    }
}
