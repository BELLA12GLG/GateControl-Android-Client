package com.gatecontrol.android.common

/**
 * IP address validation.
 *
 * [Validation.validateIp] only matches IPv4; this object covers both
 * IPv4 and IPv6 (including loopback ::1 and the "::" notation for
 * runs of zero groups). Use this for DNS server fields and any other
 * UI that should accept either family.
 *
 * IPv6 detection delegates to [java.net.InetAddress] via the textual
 * pattern check first — Compose preview cannot resolve hostnames, so
 * we never let InetAddress.getByName see anything that looks like
 * a name; we only feed it strings that already match the IPv6 hex
 * shape.
 */
object IpValidation {

    private val IPV4_REGEX = Regex(
        """^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$"""
    )

    fun isValidIpv4(value: String): Boolean {
        val match = IPV4_REGEX.matchEntire(value) ?: return false
        return match.groupValues.drop(1).all { it.toInt() in 0..255 }
    }

    /**
     * Returns true for any well-formed IPv6 literal:
     *   - 8 groups of 1-4 hex digits, colon-separated
     *   - "::" compression for one run of zero groups
     *   - IPv4-mapped form like ::ffff:192.0.2.1
     *   - link-local with zone id is rejected (we don't accept "%eth0" suffixes)
     */
    fun isValidIpv6(value: String): Boolean {
        if (value.contains('%')) return false   // strip zone-id forms
        if (value.contains(' ')) return false
        if (!value.contains(':')) return false
        if (value.length > 45) return false
        // Quick character check
        val allowed = value.all { it == ':' || it == '.' || it.isDigit() ||
                                  it in 'a'..'f' || it in 'A'..'F' }
        if (!allowed) return false
        return try {
            val addr = java.net.InetAddress.getByName(value)
            addr is java.net.Inet6Address
        } catch (_: Exception) {
            false
        }
    }

    /** Accepts IPv4 or IPv6 textual addresses. */
    fun isValidIpAddress(value: String): Boolean =
        isValidIpv4(value) || isValidIpv6(value)
}
