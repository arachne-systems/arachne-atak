package dev.arachne.atak

/** Tracks a [JoinRetryBackoff] attempt count for one retry target (e.g. the
 * workspace currently being joined), independent of any other in-flight
 * retry loop.
 *
 * The awkward part this class exists to get right: the scheduled action for
 * a retry loop can itself have a side effect that clears unrelated retry
 * state. Concretely, `WorkspaceController.resumePreJoin()` unconditionally
 * calls `closeSession()` -- which tears down and resets controller fields --
 * on every call, success or failure, before it does anything else. If the
 * next scheduled-attempt count were committed *before* invoking that action,
 * the action's own reset would immediately wipe it back to zero on every
 * poll, and the interval would silently stay flat forever even though the
 * math in [JoinRetryBackoff] is correct (see PR #90 review, FUT-30).
 *
 * The fix is ordering: read [attemptFor] before running the action, then
 * call [commit] after the action returns, so it re-establishes the intended
 * post-attempt value regardless of what the action did to this counter in
 * between (including calling [reset] on it directly, as `closeSession()`
 * does).
 */
internal class IdempotentRetryCounter {
    var attempt: Int = 0
        private set
    var target: List<Byte>? = null
        private set

    /** Clears tracked state. `closeSession()` calls this on every retry
     * counter -- including as a side effect of the very action a poll loop
     * is about to run -- so a caller must not treat state read before an
     * action as still valid without re-committing it after. */
    fun reset() { attempt = 0; target = null }

    /** The attempt number to use for [id] right now: 0 for a target this
     * counter has not seen (including one cleared by an intervening
     * [reset]), otherwise the count carried over from the last [commit]. */
    fun attemptFor(id: List<Byte>): Int = if (target == id) attempt else 0

    /** Commits [id] as the current target with the attempt *after*
     * [usedAttempt] (the value [attemptFor] returned before the caller's
     * action ran). Call this after the action, not before: if the action
     * reset this counter as a side effect, this restores the value the
     * caller actually intends, so backoff keeps accumulating across polls
     * instead of resetting every time. */
    fun commit(id: List<Byte>, usedAttempt: Int) { target = id; attempt = usedAttempt + 1 }
}
