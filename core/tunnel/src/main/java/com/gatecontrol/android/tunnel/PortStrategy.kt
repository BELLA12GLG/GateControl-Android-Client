package com.gatecontrol.android.tunnel

import kotlin.random.Random

/**
 * Stateless port selection — pure random from the unprivileged range,
 * excluding ports already tried this attempt sequence.
 *
 * ## v6.6 design change: "pure random"
 *
 * v6.4–v6.5 special-cased a 4-port "well-known shortlist" (443, 53, 4500,
 * 80) and tried those IN ORDER before falling back to random ports. That
 * meant rotating from one of them would always land on the next in the
 * list — users observed "two ports ping-ponging" because consecutive
 * rotates kept hitting the small ordered shortlist.
 *
 * v6.6 removes the shortlist privilege entirely. Random sampling from
 * 1024..65535 means well-known ports CAN come up — they're still in the
 * sample space — they just don't get preferential ordering. Each attempt
 * is genuinely independent of the previous one (modulo the [history]
 * exclusion that prevents within-sequence repeats).
 *
 * Trade-off: in restrictive networks, 443/53 have higher success rates
 * than arbitrary ports. We accept a slightly lower first-attempt success
 * rate in exchange for the user-perceived behavior of "rotating actually
 * rotates to something different each time."
 *
 * ## API contract
 *
 * The strategy returns ONE port per call. The coordinator is responsible
 * for remembering which ports were already tried and passing them as
 * [history] so the strategy doesn't repeat itself within an attempt
 * sequence.
 *
 * `random` is exposed only for tests — production code calls [next]
 * without it and gets cryptographic-quality randomness from
 * [Random.Default].
 */
object PortStrategy {

    /**
     * Well-known UDP ports that commonly traverse restrictive firewalls.
     * v6.6: these are NO LONGER tried preferentially. Kept exposed for
     * external code that wants to display them as suggestions in a UI.
     */
    val WELL_KNOWN_PORTS: List<Int> = listOf(443, 53, 4500, 80)

    /** Total candidate space size (used for max-attempt heuristics). */
    const val MAX_REASONABLE_ATTEMPTS = 16

    /**
     * Pick the next port to try — uniformly random from 1024..65535,
     * excluding the original port and any ports in [history].
     *
     * @param originalPort The port from the original WireGuard config.
     *   Excluded from the result; even if [history] is empty, the original
     *   is treated as "already tried" so the strategy never re-suggests it.
     * @param history Ports already tried this attempt sequence. The returned
     *   port is guaranteed not to be in this set.
     * @param random Source of randomness; defaults to [Random.Default].
     *   Tests can pass a seeded [Random] for determinism.
     * @return The next port to try, or null if every reasonable candidate
     *   has been exhausted (history is too tight). Callers should give up
     *   at null rather than loop infinitely.
     */
    fun next(
        originalPort: Int,
        history: Set<Int>,
        random: Random = Random.Default,
    ): Int? {
        val excluded = history + originalPort

        // Pure random from the unprivileged range. The size of the range
        // (~64k) makes the chance of repeated collisions extremely small
        // even when history accumulates dozens of entries, so 128 retries
        // is plenty without spinning. If history covers enough of the
        // range to actually exhaust this loop, the user has been rotating
        // hundreds of times and we should give up.
        repeat(128) {
            val candidate = random.nextInt(1024, 65536)
            if (candidate !in excluded) return candidate
        }
        return null
    }
}
