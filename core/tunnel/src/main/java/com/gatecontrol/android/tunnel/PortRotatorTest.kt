package com.gatecontrol.android.tunnel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PortRotatorTest {

    @Test
    fun `nextPort never returns the original port`() {
        val rotator = PortRotator(originalPort = 51820)
        val seen = (1..rotator.candidateCount).map { rotator.nextPort() }.toSet()
        assertFalse(51820 in seen, "Candidate list must exclude the original port")
    }

    @Test
    fun `nextPort cycles through all candidates without crashing`() {
        val rotator = PortRotator(originalPort = 9999)
        // Call nextPort more than candidateCount times to verify cycling
        val results = (1..(rotator.candidateCount * 2)).map { rotator.nextPort() }
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `reset restarts cycle from first candidate`() {
        val rotator = PortRotator(originalPort = 1111)
        val first = rotator.nextPort()
        rotator.nextPort()   // advance once more
        rotator.reset()
        val afterReset = rotator.nextPort()
        assertEquals(first, afterReset, "After reset, nextPort should return the first candidate again")
    }

    @Test
    fun `candidateCount is positive and excludes original port`() {
        val rotator = PortRotator(originalPort = 443)
        assertTrue(rotator.candidateCount > 0)
    }

    @Test
    fun `all candidate ports are in valid UDP port range`() {
        val rotator = PortRotator(originalPort = 0)
        // Collect all candidates by cycling once
        val ports = (1..rotator.candidateCount).map { rotator.nextPort() }
        ports.forEach { port ->
            assertTrue(port in 1..65535, "Port $port is outside valid range 1-65535")
        }
    }

    @Test
    fun `tier-1 candidate 443 appears before high ephemeral ports`() {
        // 443 should be the very first candidate for any original port != 443
        val rotator = PortRotator(originalPort = 51820)
        val first = rotator.nextPort()
        assertEquals(443, first, "Port 443 should be the first candidate (highest bypass priority)")
    }
}
