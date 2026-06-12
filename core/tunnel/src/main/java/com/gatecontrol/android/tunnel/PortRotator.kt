package com.gatecontrol.android.tunnel

/**
 * Manages port rotation for WireGuard reconnection attempts.
 *
 * Strategy (ordered by likelihood of bypassing firewalls/QoS):
 *  1. Well-known ports commonly allowed through restrictive firewalls (443, 80, 53 …)
 *  2. Common WireGuard ports
 *  3. High ephemeral ports (less likely to be throttled by carrier DPI)
 *
 * The original port is always excluded from the candidate list so the first
 * rotation attempt tries something genuinely different.
 */
class PortRotator(val originalPort: Int) {

    private val candidates: List<Int> = buildList {
        // Tier 1 — well-known ports that most firewalls let through
        addAll(listOf(443, 80, 53, 8080, 8443, 1194, 4500, 500, 1701, 4096))
        // Tier 2 — common WireGuard / VPN ports
        addAll(listOf(51820, 51821, 13231, 13233, 7777, 9999))
        // Tier 3 — stable high-port candidates (not random so list is deterministic)
        addAll(listOf(49152, 50000, 51000, 52000, 53000, 54321, 55000, 62000))
    }
        .filter { it != originalPort }
        .distinct()

    private var index = 0

    /** Returns the next candidate port, cycling through the list. */
    fun nextPort(): Int {
        val port = candidates[index % candidates.size]
        index++
        return port
    }

    /** Resets rotation back to the first candidate. */
    fun reset() {
        index = 0
    }

    /** Total number of distinct candidate ports available. */
    val candidateCount: Int get() = candidates.size
}
