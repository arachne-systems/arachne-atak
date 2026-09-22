package dev.arachne.atak

import kotlin.random.Random

/** Exponential backoff for admission/discovery retry loops.
 *
 * `request_admission` replays the session's exact persisted pending request;
 * a repeat call returns the retained reply rather than enqueuing a new
 * attempt (crates/fabric-android/README.md, "Admission over authenticated
 * Iroh control"). Retrying it on a short fixed interval is therefore not a
 * correctness requirement -- it only affects how quickly a joiner notices a
 * result -- so this backoff can safely grow without losing retained state.
 *
 */
internal object JoinRetryBackoff {
    const val BASE_MS = 5_000L
    const val CAP_MS = 60_000L
    private const val JITTER = 0.2
    // baseMs << 4 (80s) already exceeds CAP_MS; clamp the exponent so the
    // shift never has to grow further for a caller that never resets.
    private const val MAX_EXPONENT = 4

    /** The un-jittered doubling interval: [baseMs], [baseMs] * 2, ... capped
     * at [capMs]. [attempt] is 0-based: 0 is the delay before the first
     * retry after the initial send. */
    fun rawIntervalMs(attempt: Int, baseMs: Long = BASE_MS, capMs: Long = CAP_MS): Long {
        val exponent = attempt.coerceIn(0, MAX_EXPONENT)
        return (baseMs shl exponent).coerceAtMost(capMs)
    }

    /** [rawIntervalMs] with +/-20% jitter applied, so many devices retrying
     * at once do not stay in lockstep. */
    fun intervalMs(attempt: Int, random: Random = Random.Default, baseMs: Long = BASE_MS, capMs: Long = CAP_MS): Long {
        val raw = rawIntervalMs(attempt, baseMs, capMs)
        val factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * JITTER
        return (raw * factor).toLong().coerceAtLeast(1)
    }
}
