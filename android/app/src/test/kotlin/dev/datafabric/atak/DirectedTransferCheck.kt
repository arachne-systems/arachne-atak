package dev.arachne.atak

/** Deterministic monotonic-clock checks; no Android or network mocks. */
fun main() {
    val large = 101L * 1024 * 1024
    listOf(large, 5L * 1024 * 1024 * 1024, Long.MAX_VALUE).forEach(DirectedTransfer::requireSize)
    listOf(0L, -1L).forEach { check(runCatching { DirectedTransfer.requireSize(it) }.isFailure) }

    val transfer = DirectedTransfer(setOf("slow", "silent"), 0)
    // A real receiver remains active for ten minutes, crossing the old 90 s
    // deadline, and continues after a different recipient becomes silent.
    for (time in 10_000L..600_000L step 10_000) {
        transfer.activity("slow", time)
        transfer.expire(time)
        check(transfer.outcome == null)
    }
    transfer.result("silent", true) // A late result cannot undo an expired recipient.
    transfer.result("outsider", true)
    check(transfer.outcome == null)
    transfer.result("slow", true)
    check(transfer.outcome == false)
    transfer.activity("silent", 700_000)
    transfer.result("silent", true)
    check(transfer.outcome == false)

    val healthy = DirectedTransfer(setOf("receiver"), 0)
    for (time in 10_000L..600_000L step 10_000) {
        healthy.activity("receiver", time)
        healthy.expire(time)
        check(healthy.outcome == null) // Activity is not import success.
    }
    healthy.result("receiver", true)
    healthy.result("receiver", false) // Duplicate failure cannot change the result.
    check(healthy.outcome == true)

    val stalled = DirectedTransfer(setOf("receiver"), 0)
    stalled.activity("outsider", DirectedTransfer.IDLE_MS - 1)
    stalled.expire(DirectedTransfer.IDLE_MS - 1)
    check(stalled.outcome == null)
    stalled.expire(DirectedTransfer.IDLE_MS)
    check(stalled.outcome == false)
    println("PASS: uncapped positive lengths; >90 s active transfer; silent-peer isolation; idle cleanup; late/duplicate/foreign results")
}
