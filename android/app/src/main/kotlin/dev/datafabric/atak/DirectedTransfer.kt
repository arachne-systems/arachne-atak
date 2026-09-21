package dev.arachne.atak

/** Tracks recipient activity, not a file's age or size. Import success is a
 * separate, terminal result; activity never means delivered or installed. */
internal class DirectedTransfer(recipients: Set<String>, now: Long) {
    private val waiting = recipients.associateWith { now }.toMutableMap()
    private var failed = false

    init { require(recipients.isNotEmpty()) }

    val outcome: Boolean? get() = if (waiting.isEmpty()) !failed else null

    fun activity(recipient: String, now: Long): Boolean {
        if (recipient !in waiting) return false
        waiting[recipient] = now
        return true
    }

    fun result(recipient: String, success: Boolean) {
        if (waiting.remove(recipient) != null) failed = failed || !success
    }

    fun expire(now: Long) {
        val expired = waiting.filterValues { now - it >= IDLE_MS }.keys
        expired.forEach { result(it, false) }
    }

    companion object {
        const val IDLE_MS = 120_000L
        const val REPORT_MS = 10_000L
        fun requireSize(size: Long) { require(size > 0) { "Package is empty or has an invalid size." } }
    }
}
