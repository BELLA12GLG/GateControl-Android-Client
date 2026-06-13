package com.gatecontrol.android.tunnel

import kotlin.random.Random

/**
 * Stateless port selection.
 *
 * Replaces the v1 [PortRotator] fixed-list approach with a dynamic strategy:
 *
 *   1. **Original port** is tried first when starting a fresh attempt sequence.
 *      The caller's own [TunnelConfig] already has it, so this strategy is
 *      only consulted *after* the original fails — but exposing the original
 *      separately makes higher layers easier to reason about.
 *
 *   2. **Well-known port shortlist** — the small set of UDP ports commonly
 *      permitted through restrictive networks (443, 53, 80, 4500). These are
 *      tried in order before anything random because they have an order-of-
 *      magnitude higher hit rate than random ports on captive WiFi / cellular
 *      DPI networks.
 *
 *   3. **Random unprivileged port** — uniformly sampled from 1024..65535,
 *      excluding the original port, the well-known shortlist, and any ports
 *      previously tried this attempt sequence (caller tracks history).
 *
 * The strategy returns ONE port per call. The coordinator is responsible for
 * remembering which ports were already tried and passing them as [history]
 * so the strategy doesn't repeat itself within a single attempt sequence.
 *
 * `seed` is exposed only for tests — production code calls [next] without
 * it and gets cryptographic-quality randomness from [Random.Default].
 */
object PortStrategy {

    /**
     * Well-known UDP ports that commonly traverse restrictive firewalls.
     * Tried in order before falling back to random ports.
     *
     * Picked specifically:
     *   - 443  — HTTPS; nearly always allowed, often misclassified as TLS
     *   - 53   — DNS; allowed by most captive portals
     *   - 4500 — IPSec NAT-T; allowed by many cellular carriers
     *   - 80   — HTTP; widely allowed (last resort, often DPI-inspected)
     */
    val WELL_KNOWN_PORTS: List<Int> = listOf(443, 53, 4500, 80)

    /** Total candidate space size (used for max-attempt heuristics). */
    const val MAX_REASONABLE_ATTEMPTS = 16

    /**
     * Pick the next port to try.
     *
     * @param originalPort The port from the original WireGuard config.
     *   Excluded from the result because the caller would have already
     *   tried it (or is explicitly trying *not* to use it).
     * @param history Ports already tried this attempt sequence. The returned
     *   port is guaranteed not to be in this set.
     * @param random Source of randomness; defaults to [Random.Default].
     *   Tests can pass a seeded [Random] for determinism.
     * @return The next port to try, or null if every reasonable candidate
     *   has been exhausted. Callers should give up at null rather than
     *   loop infinitely.
     */
    fun next(
        originalPort: Int,
        history: Set<Int>,
        random: Random = Random.Default,
    ): Int? {
        val excluded = history + originalPort
        // First exhaust the well-known shortlist in order.
        WELL_KNOWN_PORTS.firstOrNull { it !in excluded }?.let { return it }

        // Then random from the unprivileged range. Cap retries so we don't
        // spin forever when history covers most of the space — in practice
        // we give up well before then via maxAttempts in the coordinator.
        repeat(64) {
            val candidate = random.nextInt(1024, 65536)
            if (candidate !in excluded) return candidate
        }
        // Truly exhausted (or impossibly tight history) — give up.
        return null
    }
}
