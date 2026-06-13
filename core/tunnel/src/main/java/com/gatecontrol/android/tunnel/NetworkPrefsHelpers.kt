package com.gatecontrol.android.tunnel

/**
 * Pure-function helpers for [TunnelManager] to apply user network
 * preferences (IP protocol + DNS overrides) without leaking the
 * "wrong" address family to the underlying physical network.
 *
 * ## The leak this prevents
 *
 * Naive "ipv6_only" semantics would just strip IPv4 from AllowedIPs.
 * On Android that's a security hole: routes NOT listed in AllowedIPs
 * fall through to the underlying network (WiFi/cellular), so any
 * IPv4-only destination would bypass the VPN entirely, exposing the
 * device's real IPv4 address and leaking DNS.
 *
 * The correct fix is *two-sided*:
 *
 *   1. **Interface inner address** (`Address = ...` in [Interface]):
 *      strictly filtered to the requested family. Removing the IPv4
 *      address means the tunnel interface has no IPv4 source address,
 *      so the kernel cannot construct outbound IPv4 packets from it —
 *      they get dropped at socket level.
 *
 *   2. **AllowedIPs** (route table for VpnService): keep BOTH families
 *      and add a catch-all blackhole route for the rejected family.
 *      The off-family route still points into the tunnel interface;
 *      because that interface has no inner address of that family,
 *      the packet hits the blackhole inside the tunnel rather than
 *      escaping out the underlying network. This is the standard
 *      "routing blackhole" pattern that VPN clients use to enforce
 *      single-stack mode without leaks.
 *
 * Kept here as an `object` of stateless functions so the unit-test
 * suite can verify each branch without standing up the WireGuard
 * backend or any `InetAddress` resolver (those live in TunnelManager).
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
     * Strict-filter DNS server list to the requested family.
     *
     * In single-stack mode the kernel can't actually USE a v4 DNS server
     * (no v4 source address available — see [filterInterfaceAddresses]),
     * but advertising one to the system resolver would still cause it to
     * try, fail, and potentially fall back to non-VPN DNS. Strip them.
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
     * Strict-filter the WireGuard interface's inner addresses (the
     * `Address = ...` line). Keeping only one family here is what
     * actually prevents the kernel from emitting packets of the other
     * family — the "blackhole" half of leak prevention.
     *
     * Empty result is returned as-is (TunnelManager handles this case
     * by falling back to "auto" with a logged warning, so the tunnel
     * can at least come up if user picked a family the WG config
     * doesn't carry).
     */
    fun filterInterfaceAddresses(addresses: String, ipProtocol: String): String {
        if (ipProtocol == "auto" || ipProtocol == "ipv6_preferred") return addresses
        val cidrs = addresses.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val kept = cidrs.filter { cidr ->
            val isV6 = cidr.contains(":")
            when (ipProtocol) {
                "ipv4_only" -> !isV6
                "ipv6_only" -> isV6
                else -> true
            }
        }
        return kept.joinToString(",")
    }

    /**
     * Build the effective AllowedIPs list with leak prevention.
     *
     * This is the function that fixes the "ipv6_only leaks IPv4" hole.
     * Unlike [filterInterfaceAddresses] which STRICTLY drops the rejected
     * family, here we KEEP both families:
     *
     *   - The requested-family CIDRs route traffic into the tunnel as
     *     normal (these will actually be transmitted).
     *   - A catch-all of the rejected family (0.0.0.0/0 for ipv6_only,
     *     ::/0 for ipv4_only) is ADDED to the route table. This forces
     *     packets of the rejected family into the tunnel interface,
     *     where they will be dropped because the interface has no
     *     source address of that family (see [filterInterfaceAddresses]).
     *
     * Without this catch-all, rejected-family packets would fall through
     * to the underlying physical network, leaking the device's real IP.
     *
     * For "auto" / "ipv6_preferred" the input list is returned unchanged.
     */
    fun applyAllowedIpsWithLeakGuard(cidrs: List<String>, ipProtocol: String): List<String> {
        if (ipProtocol == "auto" || ipProtocol == "ipv6_preferred") return cidrs
        val rejectedFamilyBlackhole = when (ipProtocol) {
            "ipv4_only" -> "::/0"        // route all IPv6 into the tunnel → dropped
            "ipv6_only" -> "0.0.0.0/0"   // route all IPv4 into the tunnel → dropped
            else -> null
        } ?: return cidrs

        // Keep all original CIDRs (they include the requested-family
        // catch-all and any specific subnets) AND add the blackhole.
        // De-dupe so we don't emit "::/0,::/0" if input already had it.
        return (cidrs + rejectedFamilyBlackhole).distinct()
    }

    /**
     * Filter a raw comma-separated AllowedIPs string by IP family then
     * apply leak guard. Used by TunnelManager in "off" mode (preserve
     * the original `AllowedIPs = ...` from the WG config).
     *
     * For "ipv4_only" / "ipv6_only", any extra entries that are not of
     * the requested family AND not the catch-all "0.0.0.0/0" / "::/0"
     * are stripped — they would otherwise just be unused noise in the
     * route table. The catch-all for the rejected family is always added.
     */
    fun applyAllowedIpsStringWithLeakGuard(allowedIps: String, ipProtocol: String): String {
        if (ipProtocol == "auto" || ipProtocol == "ipv6_preferred") return allowedIps
        val cidrs = allowedIps.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // Drop non-requested-family specific subnets (they're noise — kernel
        // would route them into the tunnel where they get dropped anyway).
        // Keep the catch-all only if it matches the requested family; the
        // blackhole for the rejected family is added next.
        val requestedFamilyOnly = cidrs.filter { cidr ->
            val isV6 = cidr.contains(":")
            when (ipProtocol) {
                "ipv4_only" -> !isV6
                "ipv6_only" -> isV6
                else -> true
            }
        }
        // If user's WG config has no requested-family CIDR at all, add the
        // requested-family catch-all so there's at least one usable route.
        val withRequestedCatchall = if (requestedFamilyOnly.isEmpty()) {
            when (ipProtocol) {
                "ipv4_only" -> listOf("0.0.0.0/0")
                "ipv6_only" -> listOf("::/0")
                else -> requestedFamilyOnly
            }
        } else requestedFamilyOnly

        return applyAllowedIpsWithLeakGuard(withRequestedCatchall, ipProtocol).joinToString(",")
    }
}
