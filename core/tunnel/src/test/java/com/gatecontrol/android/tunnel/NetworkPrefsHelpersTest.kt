package com.gatecontrol.android.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [NetworkPrefsHelpers].
 *
 * These cover the pure-function pieces used by [TunnelManager] when applying
 * user network preferences (IP protocol + DNS overrides). The actual DNS
 * resolution path (InetAddress.getAllByName) lives in TunnelManager itself
 * and is not exercised here — it requires real network state.
 */
class NetworkPrefsHelpersTest {

    @Nested
    inner class SplitHostPort {

        @Test
        fun `plain hostname with port`() {
            assertEquals("example.com" to 51820,
                NetworkPrefsHelpers.splitHostPort("example.com:51820"))
        }

        @Test
        fun `ipv4 with port`() {
            assertEquals("192.0.2.1" to 51820,
                NetworkPrefsHelpers.splitHostPort("192.0.2.1:51820"))
        }

        @Test
        fun `bracketed ipv6 with port`() {
            assertEquals("2001:db8::1" to 51820,
                NetworkPrefsHelpers.splitHostPort("[2001:db8::1]:51820"))
        }

        @Test
        fun `bracketed loopback v6`() {
            assertEquals("::1" to 80,
                NetworkPrefsHelpers.splitHostPort("[::1]:80"))
        }

        @Test
        fun `missing port returns null`() {
            assertNull(NetworkPrefsHelpers.splitHostPort("example.com"))
        }

        @Test
        fun `unbracketed ipv6 with port is ambiguous - splits on last colon`() {
            // "2001:db8::1:51820" lacks brackets, so we can't tell if the last
            // ":" is a port separator or part of the address. splitHostPort
            // splits on the LAST ":", giving host="2001:db8::1" port=51820 —
            // which happens to be correct here, but for "2001:db8::1234" with
            // no explicit port it would split into host="2001:db8:" port=null
            // and return null. Callers MUST pass IPv6 endpoints bracketed.
            assertEquals(
                "2001:db8::1" to 51820,
                NetworkPrefsHelpers.splitHostPort("2001:db8::1:51820"),
            )
        }

        @Test
        fun `non-numeric port returns null`() {
            assertNull(NetworkPrefsHelpers.splitHostPort("example.com:abc"))
        }

        @Test
        fun `unclosed bracket returns null`() {
            assertNull(NetworkPrefsHelpers.splitHostPort("[2001:db8::1"))
        }

        @Test
        fun `empty host returns null`() {
            assertNull(NetworkPrefsHelpers.splitHostPort(":51820"))
            assertNull(NetworkPrefsHelpers.splitHostPort("[]:51820"))
        }
    }

    @Nested
    inner class FilterDns {
        private val mixed = listOf("1.1.1.1", "2606:4700:4700::1111", "8.8.8.8")

        @Test
        fun `auto passes everything through`() {
            assertEquals(mixed, NetworkPrefsHelpers.filterDnsByProtocol(mixed, "auto"))
        }

        @Test
        fun `ipv6_preferred also passes everything through`() {
            assertEquals(mixed, NetworkPrefsHelpers.filterDnsByProtocol(mixed, "ipv6_preferred"))
        }

        @Test
        fun `ipv4_only strips v6`() {
            assertEquals(listOf("1.1.1.1", "8.8.8.8"),
                NetworkPrefsHelpers.filterDnsByProtocol(mixed, "ipv4_only"))
        }

        @Test
        fun `ipv6_only strips v4`() {
            assertEquals(listOf("2606:4700:4700::1111"),
                NetworkPrefsHelpers.filterDnsByProtocol(mixed, "ipv6_only"))
        }

        @Test
        fun `empty input returns empty regardless of protocol`() {
            for (p in listOf("auto", "ipv6_preferred", "ipv4_only", "ipv6_only")) {
                assertEquals(emptyList<String>(),
                    NetworkPrefsHelpers.filterDnsByProtocol(emptyList(), p))
            }
        }
    }

    @Nested
    inner class FilterCidrs {
        private val mixed = listOf("0.0.0.0/0", "::/0", "10.0.0.0/8", "fd00::/8")

        @Test
        fun `auto passes through`() {
            assertEquals(mixed, NetworkPrefsHelpers.filterCidrsByProtocol(mixed, "auto"))
        }

        @Test
        fun `ipv4_only keeps only v4 cidrs`() {
            assertEquals(listOf("0.0.0.0/0", "10.0.0.0/8"),
                NetworkPrefsHelpers.filterCidrsByProtocol(mixed, "ipv4_only"))
        }

        @Test
        fun `ipv6_only keeps only v6 cidrs`() {
            assertEquals(listOf("::/0", "fd00::/8"),
                NetworkPrefsHelpers.filterCidrsByProtocol(mixed, "ipv6_only"))
        }

        @Test
        fun `ipv4_only on all-v6 input falls back to 0_0_0_0`() {
            // WireGuard library rejects empty AllowedIPs — the helper inserts
            // a safe family default so the tunnel can still come up.
            assertEquals(listOf("0.0.0.0/0"),
                NetworkPrefsHelpers.filterCidrsByProtocol(listOf("::/0"), "ipv4_only"))
        }

        @Test
        fun `ipv6_only on all-v4 input falls back to default v6 route`() {
            assertEquals(listOf("::/0"),
                NetworkPrefsHelpers.filterCidrsByProtocol(listOf("0.0.0.0/0"), "ipv6_only"))
        }
    }

    @Nested
    inner class FilterAllowedIps {

        @Test
        fun `comma-separated raw string is split, filtered, and rejoined`() {
            assertEquals("0.0.0.0/0,10.0.0.0/8",
                NetworkPrefsHelpers.filterAllowedIpsByProtocol(
                    "0.0.0.0/0, ::/0, 10.0.0.0/8", "ipv4_only"))
        }

        @Test
        fun `whitespace around entries is trimmed`() {
            assertEquals("::/0,fd00::/8",
                NetworkPrefsHelpers.filterAllowedIpsByProtocol(
                    "  ::/0  ,  fd00::/8  ,  10.0.0.0/8  ", "ipv6_only"))
        }

        @Test
        fun `auto returns unchanged`() {
            val raw = "0.0.0.0/0, ::/0"
            assertEquals(raw, NetworkPrefsHelpers.filterAllowedIpsByProtocol(raw, "auto"))
        }

        @Test
        fun `ipv4_only on all-v6 input falls back to default v4 route`() {
            assertEquals("0.0.0.0/0",
                NetworkPrefsHelpers.filterAllowedIpsByProtocol(
                    "::/0, fd00::/8", "ipv4_only"))
        }
    }
}
