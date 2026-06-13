package com.gatecontrol.android.tunnel

/**
 * Resolved configuration passed from app layer down to TunnelManager when
 * connecting. Despite the name, it carries both split-tunnel state and a
 * couple of orthogonal network preferences (IP protocol & DNS overrides) —
 * keeping them in one place avoids changing every connect call site.
 *
 * Defaults match the legacy behavior: no split tunneling, no IP-family
 * filtering, DNS comes from the WireGuard config.
 */
data class SplitTunnelConfig(
    /** Split-tunnel mode: "off" | "exclude" | "include". */
    val mode: String = "off",
    /** Networks (CIDRs only — labels stripped at the data layer). */
    val networks: List<String> = emptyList(),
    /** Package names of apps the mode applies to. */
    val apps: List<String> = emptyList(),
    /**
     * IP-family preference for the WireGuard endpoint resolution and the
     * AllowedIPs filter inside the tunnel. One of:
     *   - "auto"            — system default (typically IPv4-preferred)
     *   - "ipv6_preferred"  — sort IPv6 first when both families resolve
     *   - "ipv4_only"       — strip IPv6 from endpoint resolution and AllowedIPs
     *   - "ipv6_only"       — strip IPv4 from endpoint resolution and AllowedIPs
     */
    val ipProtocol: String = "auto",
    /**
     * User-supplied DNS servers. When non-empty, these REPLACE the DNS
     * servers from the WireGuard config (`DNS = ...` in [Interface]).
     * Empty list means "use WireGuard config DNS as-is".
     *
     * Both IPv4 and IPv6 addresses are accepted; the network builder will
     * filter them per [ipProtocol] before applying.
     */
    val dnsServers: List<String> = emptyList(),
    /**
     * Static hosts overrides (host → IP). Consulted by TunnelManager during
     * Endpoint resolution. v6.5: lets the user pin the server's hostname to a
     * specific IP so the tunnel comes up even when system DNS is blocked /
     * poisoned. Hosts are stored lower-cased; comparison is case-insensitive.
     *
     * NOTE: this only affects the tunnel layer's resolution of the WireGuard
     * Endpoint string. Traffic flowing THROUGH the tunnel after it's up is
     * still resolved by the system / tunnel DNS server — Android doesn't let
     * an unprivileged app override that.
     */
    val staticHosts: Map<String, String> = emptyMap(),
)
