package dev.arachne.atak

import java.util.concurrent.atomic.AtomicBoolean

/** Runs [step] until it reports no work, in slices, on the caller's serial queue.
 *
 * A slice that is used up re-queues itself at the tail, so user operations and
 * publications queued meanwhile run between slices. That is a yield, not a wait:
 * no delay is ever scheduled. */
internal class ControlDrain(
    private val slice: Int = 32,
    private val step: () -> Boolean,
    private val requeue: (Runnable) -> Unit,
) {
    private val scheduled = AtomicBoolean(false)
    @Volatile private var running = false

    /** Nothing scheduled and no slice running. The backup tick polls only then. */
    val idle: Boolean get() = !scheduled.get() && !running

    /** Safe from any thread. Many signals before one run collapse into that run. */
    fun signal() {
        if (scheduled.compareAndSet(false, true)) requeue(Runnable { run() })
    }

    private fun run() {
        running = true
        try {
            // Clear first: a signal that lands during this run schedules the next one.
            scheduled.set(false)
            repeat(slice) { if (!step()) return }
            signal()
        } finally {
            running = false
        }
    }
}
