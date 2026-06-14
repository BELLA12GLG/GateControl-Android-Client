package com.gatecontrol.android.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-layer DNS resolver for OkHttp.
 *
 * Resolution chain (each step short-circuits the next on success):
 *   1. **Static hosts** — user-supplied host → IP overrides. Honors both IPv4
 *      and IPv6 literals. Hit takes precedence over everything else, even
 *      when DoH is configured — this is how a user pins the server hostname
 *      to a known IP to bypass DNS poisoning entirely.
 *   2. **Cache** — per-host result cache with user-controlled TTL. Hit
 *      returns immediately without any network I/O.
 *   3. **Upstream** — either OkHttp's [DnsOverHttps] (if configured) or
 *      [Dns.SYSTEM]. Result is written to cache when [cacheEnabled].
 *   4. **VPN-internal address filter** — any addresses in 10.8.0.0/16 are
 *      stripped because the system resolver may return them when the
 *      WireGuard tunnel is up; they're not reachable from the GateControl
 *      app (which is excluded from the tunnel).
 *
 * ## Threading
 *
 * `lookup()` is called from OkHttp's call-execution threads (synchronous,
 * non-suspending). Config snapshots are kept in [AtomicReference] so a
 * reconfigure mid-request doesn't tear; ongoing lookups complete with the
 * snapshot they started with.
 *
 * ## What this DOES NOT do
 *
 * It only affects the App's OkHttp REST calls — *not* the WireGuard tunnel
 * DNS. Once the tunnel is up, traffic from other apps on the device goes
 * through the system resolver pointed at the tunnel's `DNS=...` server,
 * which is OS-level and outside this resolver's reach.
 */
@Singleton
class DnsResolver @Inject constructor() {

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val configRef = AtomicReference(Config())

    // Lazy-built DoH client. Rebuilt only when the upstream URL changes;
    // OkHttp recommends reusing the same DnsOverHttps instance.
    private var dohClient: DnsOverHttps? = null
    private var dohClientUrl: String = ""
    private val dohLock = Any()

    /** Snapshot of the user's resolver configuration. */
    data class Config(
        val staticHosts: Map<String, List<InetAddress>> = emptyMap(),
        val dohUpstreamUrl: String = "",
        val cacheEnabled: Boolean = true,
        val cacheTtlSeconds: Int = 3600,
    )

    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAt: Long)

    /**
     * Replace the configuration. Safe to call from any thread at any time.
     * Cache is preserved across reconfigures; if the user wants a fresh
     * resolution they should also call [clearCache].
     */
    fun updateConfig(config: Config) {
        configRef.set(config)
    }

    fun clearCache() {
        cache.clear()
    }

    /**
     * v6.6: snapshot of one cache entry, surfaced to the UI for the
     * "DNS cache details" screen. [remainingSeconds] is computed at
     * the moment of the dump; entries may expire moments after.
     */
    data class CacheSnapshot(
        val host: String,
        val ips: List<String>,
        val remainingSeconds: Long,
        val isStatic: Boolean,
    )

    /**
     * Return the current cache contents PLUS static hosts as a sorted list:
     * static entries first (with `remainingSeconds = Long.MAX_VALUE` to flag
     * them as "never expire"), then live cache entries sorted by host name.
     *
     * Called by the UI; the data is a snapshot — subsequent resolves may
     * change what's in the cache. The UI should provide a refresh affordance.
     */
    fun dumpCache(): List<CacheSnapshot> {
        val now = System.currentTimeMillis()
        val config = configRef.get()

        val staticEntries = config.staticHosts.map { (host, addrs) ->
            CacheSnapshot(
                host = host,
                ips = addrs.mapNotNull { it.hostAddress },
                remainingSeconds = Long.MAX_VALUE,
                isStatic = true,
            )
        }
        val cacheEntries = cache.entries.mapNotNull { (host, entry) ->
            val remaining = (entry.expiresAt - now) / 1000
            if (remaining <= 0) return@mapNotNull null  // already expired
            CacheSnapshot(
                host = host,
                ips = entry.addresses.mapNotNull { it.hostAddress },
                remainingSeconds = remaining,
                isStatic = false,
            )
        }
        return (staticEntries + cacheEntries).sortedBy { it.host }
    }

    /**
     * Remove a single host's cached entry. Static-host overrides are NOT
     * touched — they're configuration, not cache. The UI's "delete" button
     * for a static entry must go through the SettingsViewModel /
     * SettingsRepository path instead.
     *
     * Returns true if there was an entry to remove.
     */
    fun removeFromCache(host: String): Boolean =
        cache.remove(host.lowercase()) != null

    /** OkHttp [Dns] adapter — install this on the OkHttp client. */
    val asOkHttpDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
    }

    fun resolve(hostname: String): List<InetAddress> {
        val config = configRef.get()
        // Normalize once — used by both static hosts and cache lookup so the
        // two paths agree on key shape, and so removeFromCache(host) matches
        // entries stored from resolves with mixed-case hostnames.
        val key = hostname.lowercase()

        // 1. Static hosts (exact match, case-insensitive)
        config.staticHosts[key]?.let { return it }

        // 2. Cache lookup
        if (config.cacheEnabled) {
            val entry = cache[key]
            if (entry != null && entry.expiresAt > System.currentTimeMillis()) {
                return entry.addresses
            }
        }

        // 3. Upstream resolution
        val resolved = resolveUpstream(hostname, config)
        if (resolved.isEmpty()) {
            throw UnknownHostException("No DNS records for $hostname")
        }

        // 4. Store in cache (when enabled). VPN-internal addresses ARE
        // stored too — filtering happens on return so that legitimately
        // local destinations (some users have 10.x in their home LAN) work
        // when the tunnel is down.
        if (config.cacheEnabled) {
            cache[key] = CacheEntry(
                addresses = resolved,
                expiresAt = System.currentTimeMillis() + (config.cacheTtlSeconds * 1000L),
            )
        }
        return resolved
    }

    private fun resolveUpstream(hostname: String, config: Config): List<InetAddress> {
        // Try DoH first when configured. Fall back to system DNS on ANY
        // DoH error (TLS, HTTP 5xx, malformed response) — better to resolve
        // than to fail the whole request because the DoH endpoint hiccuped.
        if (config.dohUpstreamUrl.isNotBlank()) {
            try {
                val doh = getOrBuildDohClient(config.dohUpstreamUrl)
                if (doh != null) {
                    return doh.lookup(hostname)
                }
            } catch (e: Exception) {
                Timber.w(e, "DoH lookup failed for %s, falling back to system DNS", hostname)
            }
        }

        // System resolver — strip VPN-internal addresses for the reason
        // documented in ApiClientProvider (legacy comment): when WG is up
        // the GateControl app is excluded from the tunnel but Android may
        // cache the WG-internal resolver's answer system-wide.
        return try {
            Dns.SYSTEM.lookup(hostname).filter { addr ->
                val ip = addr.hostAddress ?: ""
                !ip.startsWith("10.8.")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getOrBuildDohClient(url: String): DnsOverHttps? {
        synchronized(dohLock) {
            if (dohClient != null && dohClientUrl == url) return dohClient
            return try {
                val httpUrl = url.toHttpUrl()
                val client = DnsOverHttps.Builder()
                    .client(OkHttpClient())
                    .url(httpUrl)
                    .build()
                dohClient = client
                dohClientUrl = url
                client
            } catch (e: Exception) {
                Timber.w(e, "Failed to build DoH client for %s", url)
                null
            }
        }
    }

    companion object {
        /**
         * Parse a JSON-encoded host map to a [Config.staticHosts] map.
         * Invalid entries are skipped with a log; the function never throws.
         *
         * Input: {"server.example.com":"1.2.3.4", "alt.example.com":"::1"}
         * Output: lowercased host → list of one InetAddress
         *
         * Uses Gson rather than org.json because the latter is stubbed-out
         * on the JVM (Android-only impl), which breaks unit tests.
         */
        fun parseStaticHostsJson(json: String): Map<String, List<InetAddress>> {
            if (json.isBlank() || json == "{}") return emptyMap()
            return try {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                val raw: Map<String, String>? = com.google.gson.Gson().fromJson(json, type)
                if (raw == null) return emptyMap()
                val out = mutableMapOf<String, List<InetAddress>>()
                for ((host, ipStr) in raw) {
                    if (host.isBlank() || ipStr.isBlank()) continue
                    try {
                        val addr = InetAddress.getByName(ipStr.trim())
                        out[host.lowercase()] = listOf(addr)
                    } catch (_: Exception) {
                        Timber.w("Static hosts: invalid IP for %s: %s", host, ipStr)
                    }
                }
                out
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse static hosts JSON")
                emptyMap()
            }
        }

        /**
         * UI-friendly variant of [parseStaticHostsJson]. Returns a raw
         * host → IP-string map without doing [InetAddress] resolution —
         * the UI layer just needs to display what the user typed.
         */
        fun parseStaticHostsJsonAsStringMap(json: String): Map<String, String> {
            if (json.isBlank() || json == "{}") return emptyMap()
            return try {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                val raw: Map<String, String>? = com.google.gson.Gson().fromJson(json, type)
                raw?.filter { it.key.isNotBlank() && it.value.isNotBlank() }
                    ?.mapKeys { it.key.lowercase() }
                    ?.toSortedMap()
                    ?.toMap()
                    ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }

        /**
         * Encode a host → IP map to JSON. The reverse of [parseStaticHostsJson].
         * Only emits the first address per host (multi-address static hosts
         * aren't supported through the UI).
         */
        fun encodeStaticHostsJson(map: Map<String, String>): String {
            val cleaned = map.filterKeys { it.isNotBlank() }
                .filterValues { it.isNotBlank() }
                .mapKeys { it.key.lowercase() }
            return com.google.gson.Gson().toJson(cleaned)
        }
    }
}
