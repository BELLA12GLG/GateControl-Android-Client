package com.gatecontrol.android.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Tests for [PortStrategy].
 *
 * v6.6 changed the contract from "well-known shortlist first, then random"
 * to "pure random from 1024..65535". These tests pin down the new behavior:
 *
 *   - Returned port is always in 1024..65535
 *   - Returned port is never the original port
 *   - Returned port is never in history
 *   - With a fully-random source, results vary across calls (no fixed ordering)
 *   - With a seeded random, results are deterministic
 */
class PortStrategyTest {

    @Nested
    inner class RandomSampling {

        @Test
        fun `returned port is always in the unprivileged range`() {
            // Run many times with different seeds to be confident.
            repeat(50) { seed ->
                val port = PortStrategy.next(
                    originalPort = 51820,
                    history = emptySet(),
                    random = Random(seed = seed.toLong()),
                )
                assertNotNull(port)
                assertTrue(port!! in 1024..65535,
                    "seed=$seed returned $port, outside 1024..65535")
            }
        }

        @Test
        fun `never returns the original port`() {
            // Even with biased random sources, the original port must be excluded.
            repeat(50) { seed ->
                val port = PortStrategy.next(
                    originalPort = 51820,
                    history = emptySet(),
                    random = Random(seed = seed.toLong()),
                )
                if (port != null) assertFalse(port == 51820,
                    "seed=$seed returned the original port")
            }
        }

        @Test
        fun `never returns a port in history`() {
            val history = setOf(443, 53, 4500, 80, 10000, 20000, 30000, 8443)
            repeat(50) { seed ->
                val port = PortStrategy.next(
                    originalPort = 51820,
                    history = history,
                    random = Random(seed = seed.toLong()),
                )
                if (port != null) assertFalse(port in history,
                    "seed=$seed returned $port which is in history $history")
            }
        }

        @Test
        fun `well-known ports are NOT preferentially ordered`() {
            // v6.6 contract: 443 / 53 / 4500 / 80 have no privilege. Across many
            // calls with different seeds, we should see a variety of ports —
            // NOT always 443 first. If the strategy were still using the
            // well-known shortlist, every fresh call (empty history) would
            // return 443.
            val seen = mutableSetOf<Int>()
            repeat(20) { seed ->
                val port = PortStrategy.next(
                    originalPort = 51820,
                    history = emptySet(),
                    random = Random(seed = seed.toLong()),
                )
                if (port != null) seen += port
            }
            // With 20 different seeds drawing from a 64k range, we should
            // get many distinct values — definitely not "all 443".
            assertTrue(seen.size > 5,
                "Only saw ${seen.size} distinct ports across 20 seeds — strategy appears non-random: $seen")
            // And not ALL of them should be 443 either (which would suggest
            // the old well-known-first behavior is still in effect).
            assertTrue(seen.size > 1 || 443 !in seen,
                "Strategy always returned 443 — regression to v6.4 well-known-first?")
        }

        @Test
        fun `deterministic with same seed`() {
            val p1 = PortStrategy.next(51820, emptySet(), Random(42))
            val p2 = PortStrategy.next(51820, emptySet(), Random(42))
            assertEquals(p1, p2)
        }

        @Test
        fun `returns null when history saturates the candidate space`() {
            // Build a history that covers virtually every unprivileged port.
            // The strategy gives up after 128 retries — it shouldn't loop forever.
            val nearlyAll = (1024..65535).toSet()
            val port = PortStrategy.next(
                originalPort = 51820,
                history = nearlyAll,
                random = Random(seed = 1),
            )
            assertEquals(null, port,
                "Strategy must return null when no candidate remains")
        }
    }

    @Nested
    inner class HistoryExclusion {

        @Test
        fun `history union with originalPort is fully respected`() {
            // Edge case: original is in history. Treated as excluded once, not twice.
            val port = PortStrategy.next(
                originalPort = 443,
                history = setOf(443, 53),
                random = Random(seed = 7),
            )
            assertNotNull(port)
            assertFalse(port!! in setOf(443, 53))
        }

        @Test
        fun `large history still produces a candidate within retry budget`() {
            // ~200 ports excluded; retry budget is 128. The 64k range makes
            // collision probability tiny — we should always find a candidate.
            val largeHistory = (1024..1223).toSet()  // 200 ports
            val port = PortStrategy.next(
                originalPort = 51820,
                history = largeHistory,
                random = Random(seed = 5),
            )
            assertNotNull(port, "Should find a candidate with 200-port history in a 64k range")
            assertFalse(port!! in largeHistory)
        }
    }
}
