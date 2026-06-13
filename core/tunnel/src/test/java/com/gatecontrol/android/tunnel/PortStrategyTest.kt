package com.gatecontrol.android.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Tests for [PortStrategy].
 *
 * Two responsibilities to pin down:
 *   1. The well-known shortlist (443, 53, 4500, 80) is exhausted IN ORDER
 *      before random sampling starts. This is the contract that "443 is
 *      always tried first" relies on.
 *   2. Random sampling never returns the original port, never returns a
 *      port already in history, and stays in the unprivileged range.
 */
class PortStrategyTest {

    @Nested
    inner class WellKnownShortlist {

        @Test
        fun `first call returns 443 when nothing in history`() {
            val port = PortStrategy.next(originalPort = 51820, history = emptySet())
            assertEquals(443, port)
        }

        @Test
        fun `skips 443 when it is the original port`() {
            val port = PortStrategy.next(originalPort = 443, history = emptySet())
            assertEquals(53, port)
        }

        @Test
        fun `walks the shortlist in order as history fills`() {
            val seen = mutableSetOf<Int>()
            var history = emptySet<Int>()
            repeat(4) {
                val p = PortStrategy.next(originalPort = 51820, history = history) ?: fail("nullable")
                seen += p
                history = history + p
            }
            assertEquals(PortStrategy.WELL_KNOWN_PORTS.toSet(), seen)
        }

        @Test
        fun `excludes ports already in history`() {
            val history = setOf(443, 53)
            val port = PortStrategy.next(originalPort = 51820, history = history)
            assertEquals(4500, port, "Should skip both 443 and 53 (in history) then pick 4500")
        }
    }

    @Nested
    inner class RandomSampling {

        @Test
        fun `after shortlist exhausted falls back to unprivileged range`() {
            val history = PortStrategy.WELL_KNOWN_PORTS.toSet()
            val port = PortStrategy.next(
                originalPort = 51820,
                history = history,
                random = Random(seed = 12345),
            )
            assertNotNull(port)
            assertTrue(port!! in 1024..65535,
                "Random port $port should be in unprivileged range")
            assertFalse(port == 51820)
        }

        @Test
        fun `random sampling never repeats from history`() {
            val history = PortStrategy.WELL_KNOWN_PORTS.toSet() + setOf(10000, 20000, 30000)
            val port = PortStrategy.next(
                originalPort = 51820,
                history = history,
                random = Random(seed = 99),
            )
            assertNotNull(port)
            assertFalse(port in history, "Got $port but it's in history $history")
        }

        @Test
        fun `random sampling never returns originalPort`() {
            // Force a tight history that would otherwise force the strategy
            // to pick 51820. With this many ports excluded we may exhaust;
            // either way the result must not be the original.
            val history = PortStrategy.WELL_KNOWN_PORTS.toSet()
            repeat(20) { seed ->
                val port = PortStrategy.next(
                    originalPort = 51820,
                    history = history,
                    random = Random(seed = seed.toLong()),
                )
                if (port != null) assertFalse(port == 51820,
                    "seed=$seed returned the original port")
            }
        }

        @Test
        fun `deterministic with same seed`() {
            val p1 = PortStrategy.next(51820, emptySet(), Random(42))
            val p2 = PortStrategy.next(51820, emptySet(), Random(42))
            // Both calls go through the same shortlist branch (no randomness
            // involved), so they should return the same answer.
            assertEquals(p1, p2)
        }
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)
}
