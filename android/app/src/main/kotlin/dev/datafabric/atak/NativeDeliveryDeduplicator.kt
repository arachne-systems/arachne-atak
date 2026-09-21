package dev.arachne.atak

/** Coalesce the same protected broadcast while overlapping workspaces hand it
 * to ATAK. Failed handoffs remain retryable through either workspace. */
internal class NativeDeliveryDeduplicator(private val capacity: Int = 1024) {
    private val accepted = LinkedHashSet<String>()
    private val pending = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()

    init { require(capacity > 0) }

    fun deliver(id: String, complete: (Boolean) -> Unit, inject: ((Boolean) -> Unit) -> Unit) {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "Invalid native delivery identity" }
        var first = false
        var duplicate = false
        synchronized(this) {
            if (id in accepted) duplicate = true
            else pending[id]?.add(complete) ?: run {
                pending[id] = mutableListOf(complete)
                first = true
            }
        }
        if (duplicate) { complete(true); return }
        if (!first) return
        val replied = java.util.concurrent.atomic.AtomicBoolean()
        fun finish(success: Boolean) {
            if (!replied.compareAndSet(false, true)) return
            val callbacks = synchronized(this) {
                val values = pending.remove(id).orEmpty()
                if (success) {
                    accepted.add(id)
                    if (accepted.size > capacity) accepted.remove(accepted.first())
                }
                values
            }
            callbacks.forEach { it(success) }
        }
        try { inject(::finish) }
        catch (error: Throwable) { finish(false); throw error }
    }
}
