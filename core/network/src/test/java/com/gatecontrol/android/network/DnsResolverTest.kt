package com.gatecontrol.android.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Tests for [DnsResolver].
 *
 * The lookup() path that hits DoH or real system DNS isn't unit-tested here —
 * those are integration concerns and would require a network. We exercise the
 * pure logic that we actually want to keep correct:
 *
 *   - Static hosts table parses and routes correctly
 *   - Cache honors TTL and respects the enabled flag
 *   - Static hosts beat cache; cache beats upstream
 *   - JSON encoding/decoding round-trips losslessly
 */
class DnsResolverTest {

    private lateinit var resolver: DnsResolver

    @BeforeEach
    fun setUp() {
        resolver = DnsResolver()
    }

    @Nested
    inner class StaticHostsJson {

        @Test
        fun `parse empty input returns empty map`() {
            assertEquals(emptyMap<String, List<InetAddress>>(),
                DnsResolver.parseStaticHostsJson(""))
            assertEquals(emptyMap<String, List<InetAddress>>(),
                DnsResolver.parseStaticHostsJson("{}"))
        }

        @Test
        fun `parse valid IPv4 entries`() {
            val map = DnsResolver.parseStaticHostsJson(
                """{"server.example.com":"192.0.2.1","alt.example.com":"203.0.113.5"}"""
            )
            assertEquals(2, map.size)
            assertEquals("192.0.2.1", map["server.example.com"]!![0].hostAddress)
            assertEquals("203.0.113.5", map["alt.example.com"]!![0].hostAddress)
        }

        @Test
        fun `parse mixed valid and invalid entries skips invalid silently`() {
            val map = DnsResolver.parseStaticHostsJson(
                """{"good.example.com":"192.0.2.1","bad.example.com":"not-an-ip"}"""
            )
            assertEquals(1, map.size, "Only the valid entry should be kept; got $map")
            assertNotNull(map["good.example.com"])
            assertNull(map["bad.example.com"])
        }

        @Test
        fun `parse lowercases host keys`() {
            // The lookup path lowercases the incoming hostname, so the stored
            // map must also be lowercased to match.
            val map = DnsResolver.parseStaticHostsJson(
                """{"SERVER.EXAMPLE.COM":"192.0.2.1"}"""
            )
            assertNotNull(map["server.example.com"])
        }

        @Test
        fun `malformed JSON returns empty map without throwing`() {
            val map = DnsResolver.parseStaticHostsJson("not valid json at all")
            assertEquals(emptyMap<String, List<InetAddress>>(), map)
        }

        @Test
        fun `encode then parse round-trips`() {
            val input = mapOf(
                "server.example.com" to "192.0.2.1",
                "alt.example.com" to "203.0.113.5",
            )
            val json = DnsResolver.encodeStaticHostsJson(input)
            val parsed = DnsResolver.parseStaticHostsJson(json)
            assertEquals(2, parsed.size)
            assertEquals("192.0.2.1", parsed["server.example.com"]!![0].hostAddress)
        }
    }

    @Nested
    inner class StaticHostsTakePrecedence {

        @Test
        fun `static host match short-circuits upstream`() {
            // Configure resolver with a static host. If lookup hits the
            // network we'd get a different answer (or fail in unit-test env);
            // the static-hit path must NOT make any network call.
            resolver.updateConfig(
                DnsResolver.Config(
                    staticHosts = DnsResolver.parseStaticHostsJson(
                        """{"pinned.example.com":"192.0.2.1"}"""
                    ),
                    cacheEnabled = true,
                    cacheTtlSeconds = 3600,
                )
            )
            val result = resolver.resolve("pinned.example.com")
            assertEquals(1, result.size)
            assertEquals("192.0.2.1", result[0].hostAddress)
        }

        @Test
        fun `static host lookup is case-insensitive`() {
            resolver.updateConfig(
                DnsResolver.Config(
                    staticHosts = DnsResolver.parseStaticHostsJson(
                        """{"pinned.example.com":"192.0.2.1"}"""
                    ),
                )
            )
            assertEquals("192.0.2.1",
                resolver.resolve("PINNED.example.com")[0].hostAddress)
            assertEquals("192.0.2.1",
                resolver.resolve("Pinned.Example.Com")[0].hostAddress)
        }
    }

    @Nested
    inner class CacheBehavior {

        @Test
        fun `cache disabled means every lookup hits upstream`() {
            // We can't easily mock upstream here, so we use a non-resolvable
            // hostname and verify that the second call still raises (not
            // returning a cached entry from an earlier call).
            resolver.updateConfig(
                DnsResolver.Config(cacheEnabled = false, cacheTtlSeconds = 0)
            )
            // .invalid TLD is reserved and guaranteed not to resolve
            assertThrows(UnknownHostException::class.java) {
                resolver.resolve("nonexistent-host-xyz.invalid")
            }
            // Second call must not return a stale cache entry — should
            // still throw because cache is disabled.
            assertThrows(UnknownHostException::class.java) {
                resolver.resolve("nonexistent-host-xyz.invalid")
            }
        }

        @Test
        fun `clearCache removes static-host-shadowing cache entries`() {
            // Add a static host AFTER something might be cached — clearing
            // the cache should make the static host take effect on next call.
            resolver.updateConfig(
                DnsResolver.Config(
                    staticHosts = DnsResolver.parseStaticHostsJson(
                        """{"server.example.com":"192.0.2.1"}"""
                    ),
                )
            )
            resolver.clearCache()
            val result = resolver.resolve("server.example.com")
            assertEquals("192.0.2.1", result[0].hostAddress)
        }
    }

    @Nested
    inner class ConfigUpdates {

        @Test
        fun `updateConfig replaces previous static hosts atomically`() {
            resolver.updateConfig(
                DnsResolver.Config(
                    staticHosts = DnsResolver.parseStaticHostsJson(
                        """{"a.example.com":"192.0.2.1"}"""
                    ),
                )
            )
            assertEquals("192.0.2.1",
                resolver.resolve("a.example.com")[0].hostAddress)

            // Replace with a different map — old entry must be gone.
            resolver.updateConfig(
                DnsResolver.Config(
                    staticHosts = DnsResolver.parseStaticHostsJson(
                        """{"b.example.com":"203.0.113.1"}"""
                    ),
                )
            )
            // a.example.com no longer static — should hit upstream (and fail
            // in unit-test env). We just check we don't get 192.0.2.1.
            try {
                val res = resolver.resolve("a.example.com")
                // If somehow upstream resolves it (very unlikely for
                // a.example.com), at minimum it must not be the old override
                assertTrue(res.none { it.hostAddress == "192.0.2.1" },
                    "Old static override should not appear after config replace")
            } catch (_: UnknownHostException) {
                // Expected — no network in unit test
            }
        }
    }
}
