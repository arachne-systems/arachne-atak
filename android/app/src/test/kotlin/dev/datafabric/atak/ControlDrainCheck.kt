package dev.arachne.atak

/** Drain-loop checks for the work-signal host: drains until empty, yields to
 * queued work between slices, and collapses repeated signals. No Android or
 * native dependencies. */
fun main() {
    drainsUntilStepReportsNothing()
    yieldsToQueuedWorkAfterOneSlice()
    signalsWhileScheduledCollapseToOneRun()
    idleOnlyWhenNothingIsScheduledOrRunning()
    println("ControlDrainCheck: 4 checks passed")
}

private fun runAll(queue: ArrayDeque<Runnable>) {
    while (queue.isNotEmpty()) queue.removeFirst().run()
}

private fun drainsUntilStepReportsNothing() {
    val queue = ArrayDeque<Runnable>()
    var work = 5
    var steps = 0
    val drain = ControlDrain(32, { steps++; work-- > 0 }) { queue.addLast(it) }
    drain.signal()
    runAll(queue)
    check(steps == 6) { "expected five handled plus one empty step, got $steps" }
}

private fun yieldsToQueuedWorkAfterOneSlice() {
    val queue = ArrayDeque<Runnable>()
    var work = 100
    val order = mutableListOf<String>()
    val drain = ControlDrain(32, { order += "step"; work-- > 0 }) { queue.addLast(it) }
    drain.signal()
    queue.addLast(Runnable { order += "user-tap" })
    runAll(queue)
    // The tap was queued behind slice one and must run before slice two.
    check(order.indexOf("user-tap") == 32) { "user tap ran at ${order.indexOf("user-tap")}, not after slice one" }
    check(order.count { it == "step" } == 101) { "expected 101 steps, got ${order.count { it == "step" }}" }
}

private fun signalsWhileScheduledCollapseToOneRun() {
    val queue = ArrayDeque<Runnable>()
    var steps = 0
    val drain = ControlDrain(32, { steps++; false }) { queue.addLast(it) }
    drain.signal(); drain.signal(); drain.signal()
    check(queue.size == 1) { "three signals queued ${queue.size} runs, expected 1" }
    runAll(queue)
    check(steps == 1) { "expected one step, got $steps" }
}

/** The backup tick polls only while the drain is idle, so a tick that finds
 * work is a real missed signal, not the tick running between two slices. */
private fun idleOnlyWhenNothingIsScheduledOrRunning() {
    val queue = ArrayDeque<Runnable>()
    var drain: ControlDrain? = null
    var idleDuringStep: Boolean? = null
    drain = ControlDrain(32, { idleDuringStep = drain!!.idle; false }) { queue.addLast(it) }
    check(drain.idle) { "a new drain must be idle" }
    drain.signal()
    check(!drain.idle) { "a scheduled drain must not be idle" }
    runAll(queue)
    check(idleDuringStep == false) { "a running drain must not be idle" }
    check(drain.idle) { "a finished drain must be idle" }
}
