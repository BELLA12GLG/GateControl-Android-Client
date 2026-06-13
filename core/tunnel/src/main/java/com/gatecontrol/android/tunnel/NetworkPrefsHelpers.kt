package com.gatecontrol.android.tunnel

/**
 * Pure-function helpers used by [TunnelManager] when applying user network
 * preferences (IP protocol + DNS overrides) to a [SplitTunnelConfig].
 *
 * Kept here as an `object` of stateless functions so the unit-test suite
 * can verify each branch without standing up the WireGuard backend or any
 * `InetAddress` resolver (those parts live in TunnelManager itself).
 */
object NetworkPrefsHelpers {

    /**
     * Split "host:port" or "[v6]:port" into a (host, port) pair. Returns null
     * if the string isn't recognisable as a host:port pair, in which case
     * callers should fall back to using the original string verbatim.
     *
     * Handles:
     *   - "example.com:51820"           -> ("example.com", 51820)
     *   - "192.0.2.1:51820"             -> ("192.0.2.1", 51820)
     *   - "[2001:db8::1]:51820"         -> ("2001:db8::1", 51820)
     *
     * Does NOT handle bare IPv6 without brackets (ambiguous with the port
     * separator) — those need to come in already bracketed.
     */
    fun splitHostPort(endpoint: String): Pair<String, Int>? {
        return try {
            if (endpoint.startsWith("[")) {
                val close = endpoint.indexOf(']')
                if (close < 0) return null
                val host = endpoint.substring(1, close)
                if (host.isEmpty()) return null
                if (close + 1 >= endpoint.length || endpoint[close + 1] != ':') return null
                val port = endpoint.substring(close + 2).toIntOrNull() ?: return null
                host to port
            } else {
                val colon = endpoint.lastIndexOf(':')
                if (colon < 0) return null
                val host = endpoint.substring(0, colon)
                if (host.isEmpty()) return null
                val port = endpoint.substring(colon + 1).toIntOrNull() ?: return null
                host to port
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Keep DNS entries whose IP family matches the protocol preference.
     * "auto" and "ipv6_preferred" pass everything through unchanged.
     */
    fun filterDnsByProtocol(dnsList: List<String>, ipProtocol: String): List<String> {
        if (ipProtocol == "auto" || ipProtocol == "ipv6_preferred") return dnsList
        return dnsList.filter { dns ->
            val isV6 = dns.contains(":")
            when (ipProtocol) {
                "ipv4_only" -> !isV6
                "ipv6_only" -> isV6
                else -> true
            }
        }
    }

    /**
     * Filter an AllowedIPs raw comma-separated string by IP family.
     * Used when preserving an existing AllowedIPs value (off mode / empty
     * exclude). Empty result is replaced with a safe family default.
     */
    fun filterAllowedIpsByProtocol(allowedIps: String, ipProtocol: String): String {
        if (ipProtocol == "auto" || ipProtocol == "ipv6_preferred") return allowedIps
        val cidrs = allowedIps.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return filterCidrsByProtocol(cidrs, ipProtocol).joinToString(",")
    }

    /**
     * Per-CIDR family filter. When the chosen family removes every CIDR we
     * fall back to a single catch-all route for that family so the tunnel
     * still has a route table the WireGuard library will accept (it rejects
     * empty AllowedIPs lists).
     */
    fun filterCidrsByProtocol(cidrs: List<String>, ipProtocol: String): List<String> {
        if (ipProtocol == "auto" || ipProtocol == "ipv6_preferred") return cidrs
        val filtered = cidrs.filter { cidr ->
            val isV6 = cidr.contains(":")
            when (ipProtocol) {
                "ipv4_only" -> !isV6
                "ipv6_only" -> isV6
                else -> true
            }
        }
        if (filtered.isEmpty()) {
            return when (ipProtocol) {
                "ipv4_only" -> listOf("0.0.0.0/0")
                "ipv6_only" -> listOf("::/0")
                else -> cidrs
            }
        }
        return filtered
    }
}
