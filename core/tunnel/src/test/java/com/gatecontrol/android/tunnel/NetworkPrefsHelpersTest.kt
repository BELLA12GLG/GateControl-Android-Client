package com.gatecontrol.android.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [NetworkPrefsHelpers].
 *
 * The most important suite here is [LeakPrevention] — it pins down the
 * exact behavior that prevents the device's real IPv4/IPv6 address from
 * leaking when the user picks single-stack mode.
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
        fun `unbracketed ipv6 with port falls back to lastIndexOf split`() {
            // Ambiguous without brackets. splitHostPort uses lastIndexOf(':')
            // so a v6 endpoint without brackets WILL parse but may split
            // incorrectly. WG configs should always bracket v6 endpoints.
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
    }

    @Nested
    inner class FilterInterfaceAddresses {

        @Test
        fun `auto returns unchanged`() {
            val raw = "10.8.0.2/32, fd00:8::2/128"
            assertEquals(raw, NetworkPrefsHelpers.filterInterfaceAddresses(raw, "auto"))
        }

        @Test
        fun `ipv4_only strips inner v6 address`() {
            // This is the critical leak-prevention step: removing the inner
            // v4 address means the kernel can't emit outbound v4 packets.
            assertEquals("10.8.0.2/32",
                NetworkPrefsHelpers.filterInterfaceAddresses(
                    "10.8.0.2/32,fd00:8::2/128", "ipv4_only"))
        }

        @Test
        fun `ipv6_only strips inner v4 address`() {
            assertEquals("fd00:8::2/128",
                NetworkPrefsHelpers.filterInterfaceAddresses(
                    "10.8.0.2/32,fd00:8::2/128", "ipv6_only"))
        }

        @Test
        fun `ipv6_only on v4-only config returns empty`() {
            // TunnelManager catches the empty case and falls back to "auto"
            // with a warning, so the tunnel still comes up.
            assertEquals("",
                NetworkPrefsHelpers.filterInterfaceAddresses("10.8.0.2/32", "ipv6_only"))
        }

        @Test
        fun `whitespace is trimmed`() {
            assertEquals("10.8.0.2/32",
                NetworkPrefsHelpers.filterInterfaceAddresses(
                    "  10.8.0.2/32  ,  fd00:8::2/128  ", "ipv4_only"))
        }
    }

    @Nested
    inner class LeakPrevention {
        // ── This is the suite that pins down the fix for the IPv4 leak ──

        @Test
        fun `ipv6_only KEEPS ipv4 catchall in AllowedIPs as a blackhole`() {
            // The whole point of leak prevention: even in "ipv6_only" mode
            // we MUST route 0.0.0.0/0 into the tunnel. Otherwise IPv4
            // traffic falls through to the underlying network and the
            // device's real IPv4 leaks. The interface has no inner v4
            // address (see FilterInterfaceAddresses), so packets get
            // dropped at the tunnel, not transmitted.
            val result = NetworkPrefsHelpers.applyAllowedIpsWithLeakGuard(
                listOf("::/0", "fd00::/8"), "ipv6_only",
            )
            assertTrue("0.0.0.0/0" in result,
                "ipv6_only must keep 0.0.0.0/0 as IPv4 blackhole to prevent leak; got $result")
            assertTrue("::/0" in result, "must keep IPv6 catchall; got $result")
        }

        @Test
        fun `ipv4_only KEEPS ipv6 catchall in AllowedIPs as a blackhole`() {
            val result = NetworkPrefsHelpers.applyAllowedIpsWithLeakGuard(
                listOf("0.0.0.0/0", "10.0.0.0/8"), "ipv4_only",
            )
            assertTrue("::/0" in result,
                "ipv4_only must keep ::/0 as IPv6 blackhole to prevent leak; got $result")
            assertTrue("0.0.0.0/0" in result, "must keep IPv4 catchall; got $result")
        }

        @Test
        fun `auto leaves AllowedIPs unchanged`() {
            val input = listOf("0.0.0.0/0", "::/0")
            assertEquals(input, NetworkPrefsHelpers.applyAllowedIpsWithLeakGuard(input, "auto"))
        }

        @Test
        fun `ipv6_preferred leaves AllowedIPs unchanged`() {
            val input = listOf("10.0.0.0/8", "fd00::/8")
            assertEquals(input,
                NetworkPrefsHelpers.applyAllowedIpsWithLeakGuard(input, "ipv6_preferred"))
        }

        @Test
        fun `de-dupe so we don't add a second catchall`() {
            // If user's AllowedIPs already has ::/0, ipv4_only shouldn't
            // add another one.
            val result = NetworkPrefsHelpers.applyAllowedIpsWithLeakGuard(
                listOf("0.0.0.0/0", "::/0"), "ipv4_only",
            )
            assertEquals(1, result.count { it == "::/0" },
                "::/0 should appear exactly once; got $result")
        }

        @Test
        fun `raw string variant — ipv6_only on dual-stack input keeps blackhole`() {
            val result = NetworkPrefsHelpers.applyAllowedIpsStringWithLeakGuard(
                "0.0.0.0/0, ::/0", "ipv6_only",
            )
            // Should still contain both — ::/0 is the real route, 0.0.0.0/0
            // is now the blackhole.
            assertTrue("0.0.0.0/0" in result, "v4 blackhole must remain; got $result")
            assertTrue("::/0" in result, "v6 catchall must remain; got $result")
        }

        @Test
        fun `raw string variant — ipv4_only strips noisy v6 specifics but keeps v6 catchall`() {
            // The v6-specific CIDR (fd00::/8) is just noise in ipv4-only mode
            // and should be stripped to keep the route table clean. The v6
            // catch-all is the blackhole and MUST be there.
            val result = NetworkPrefsHelpers.applyAllowedIpsStringWithLeakGuard(
                "0.0.0.0/0, ::/0, fd00::/8", "ipv4_only",
            )
            assertTrue("0.0.0.0/0" in result, "v4 catchall must remain; got $result")
            assertTrue("::/0" in result, "v6 blackhole must remain; got $result")
            assertFalse("fd00::/8" in result,
                "v6 specific noise should be stripped (blackhole catchall covers it); got $result")
        }

        @Test
        fun `raw string variant — ipv6_only on v4-only config still has a usable v6 route`() {
            // User picked ipv6_only but their WG config has no v6 CIDR.
            // We add ::/0 so the tunnel still has a route, plus the v4
            // blackhole. This is "make the tunnel come up" mode — user
            // can then fix their config.
            val result = NetworkPrefsHelpers.applyAllowedIpsStringWithLeakGuard(
                "0.0.0.0/0", "ipv6_only",
            )
            assertTrue("::/0" in result, "must have a v6 route; got $result")
            assertTrue("0.0.0.0/0" in result, "must have v4 blackhole; got $result")
        }
    }
}
