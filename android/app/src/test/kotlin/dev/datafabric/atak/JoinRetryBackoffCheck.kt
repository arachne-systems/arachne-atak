package dev.arachne.atak

import java.io.File
import kotlin.random.Random

/** Deterministic backoff-schedule and request-count checks; no Android or
 * network mocks. Mirrors FUT-30: a joiner must not resend full admission
 * requests on a flat 5 s interval forever. */
fun main() {
    // AC: "A no-reply joiner follows the same curve: ... intervals 5, 10, 20,
    // 40, 60, 60 s (+/-20% jitter)".
    val expectedSeconds = listOf(5L, 10L, 20L, 40L, 60L, 60L)
    expectedSeconds.forEachIndexed { attempt, seconds ->
        val raw = JoinRetryBackoff.rawIntervalMs(attempt)
        check(raw == seconds * 1000) { "attempt $attempt expected ${seconds}s raw, got ${raw}ms" }
    }
    // Jittered output must stay within +/-20% of the raw doubling curve for
    // many samples (covers both RNG tails).
    val random = Random(1234)
    repeat(500) {
        expectedSeconds.forEachIndexed { attempt, seconds ->
            val raw = seconds * 1000
            val jittered = JoinRetryBackoff.intervalMs(attempt, random)
            check(jittered in (raw * 8 / 10)..(raw * 12 / 10)) {
                "attempt $attempt jittered=$jittered outside +/-20% of raw=$raw"
            }
        }
    }

    // AC: "A test counts requests in a 5-minute simulated wait: <= 12 on the
    // fix, ~60 today." Use the fastest possible schedule (-20% jitter on
    // every attempt) so the bound holds even in the worst case, not just on
    // average.
    val fiveMinutesMs = 5 * 60 * 1000L
    var elapsed = 0L
    var attempt = 0
    var requests = 1 // the initial send at t=0
    while (true) {
        val fastest = JoinRetryBackoff.rawIntervalMs(attempt) * 8 / 10
        elapsed += fastest
        if (elapsed >= fiveMinutesMs) break
        requests++
        attempt++
    }
    check(requests <= 12) { "worst-case request count in 5 minutes was $requests, expected <= 12" }
    check(requests > 4) { "backoff simulation produced a suspiciously low count: $requests" }

    // AC: "No fixed sub-second retry remains: a unit test asserts every
    // scheduled interval in NearbyInvitations.kt and InvitationActivity.kt is
    // >= 5 s and grows on consecutive failures." Static source scan: every
    // postDelayed/schedule/scheduleWithFixedDelay call's delay argument must
    // be derived from JoinRetryBackoff/intervalMs -- a bare integer literal
    // below the floor is rejected outright, and anything else that isn't
    // visibly derived from JoinRetryBackoff is flagged too (a real call site
    // wired straight to JoinRetryBackoff.intervalMs(...) never matched the
    // old integer-literal-only regex, so that scan silently scanned nothing).
    val root = findRepoRoot()
    val controller = File(root, "android/app/src/main/kotlin/dev/datafabric/atak/WorkspaceController.kt").readText()
    // Compact checkpoint discovery is now a Rust-owned pending exchange. The
    // adapter must not schedule a separate pre-checkpoint retry loop.
    val preJoin = controller.substringAfter("private fun pollPreJoin()").substringBefore("private fun hydrateInvitation")
    check("JoinRetryBackoff" !in preJoin && "scheduleJoinDeadline" !in preJoin) {
        "compact checkpoint discovery must not have a Kotlin retry timer"
    }
    val compactJoin = controller.substringAfter("private fun beginCompactJoin").substringBefore("private fun pollPreJoin")
    check("PendingInvitationStore" !in compactJoin && "fetch_invitation_checkpoint" !in compactJoin) {
        "compact join secrets and checkpoint discovery must stay in Rust"
    }
    check("memberId = pending.getJSONObject(\"member\").getJSONArray(\"id\").bytes()" in compactJoin) {
        "compact join must retain the member identity for completed state"
    }
    val completion = controller.substringAfter("\"workspace_joined\" -> {").substringBefore("\"admission_waiting\"")
    check("preJoin = false" in completion) { "completed compact join must leave the pre-join catalog phase" }
    val pushedCompletion = controller.substringAfter("\"awaiting_join_save\" -> {").substringBefore("\"workspace_joined\" -> {")
    check("commitJoin" in pushedCompletion && "WORKSPACE_JOIN_SAVED_FROM_PUSH" in pushedCompletion) {
        "a pushed admission result must be durably committed before the join is marked complete"
    }
    val maintenance = controller.substringAfter("worker.scheduleWithFixedDelay({").substringBefore("}, 250, 250")
    check("pollPreJoin" !in maintenance && "pollPendingJoin" !in maintenance) {
        "workspace join progress must be driven by the native work signal, not the maintenance timer"
    }
    val signal = controller.substringAfter("private fun onWorkSignal() {").substringBefore("\n    }")
    check("pollPreJoin" in signal || "pollPendingJoin" in signal) {
        "workspace join progress has no native work-signal trigger"
    }
    val connections = File(root, "android/app/src/main/kotlin/dev/datafabric/atak/WorkspaceConnections.kt").readText()
    check("postDelayed" !in connections && "checkPauses" !in connections) {
        "workspace close completion must be signalled by the owner, not polled by a pause timer"
    }
    check("onOwnerClosed" in connections && "onClosed" in controller) {
        "workspace close has no owner shutdown callback seam"
    }
    val scanned = listOf(
        File(root, "android/app/src/main/kotlin/dev/datafabric/atak/NearbyInvitations.kt"),
        File(root, "android/app/src/main/kotlin/dev/datafabric/atak/InvitationActivity.kt"),
    )
    val scheduleCallNames = listOf("postDelayed", "scheduleWithFixedDelay", "schedule")
    var callSitesScanned = 0
    for (file in scanned) {
        check(file.exists()) { "missing $file" }
        val text = file.readText()
        for (name in scheduleCallNames) {
            var searchFrom = 0
            while (true) {
                val idx = text.indexOf("$name(", searchFrom)
                if (idx < 0) break
                val openParen = idx + name.length
                val close = matchingParen(text, openParen)
                    ?: error("${file.name}: unbalanced parens for $name( at offset $idx")
                val args = splitTopLevelArgs(text.substring(openParen + 1, close))
                searchFrom = close + 1
                if (args.isEmpty()) continue
                val delayArg = args.last().trim()
                if (delayArg.isEmpty()) continue
                callSitesScanned++
                val literal = Regex("""^\d+$""").matches(delayArg)
                if (literal) {
                    check(delayArg.toLong() >= JoinRetryBackoff.BASE_MS) {
                        "${file.name}: $name(...) schedules a $delayArg ms delay, below the " +
                            "${JoinRetryBackoff.BASE_MS} ms floor"
                    }
                } else {
                    val derived = delayArg.contains("JoinRetryBackoff") || delayArg.contains("intervalMs")
                    check(derived) {
                        "${file.name}: $name(...) delay argument \"$delayArg\" is not derived from " +
                            "JoinRetryBackoff/intervalMs and is not a literal >= ${JoinRetryBackoff.BASE_MS} ms"
                    }
                }
            }
        }
    }
    check(callSitesScanned > 0) {
        "no postDelayed/schedule/scheduleWithFixedDelay call sites were scanned in $scanned -- the scan is not " +
            "exercising anything"
    }

    println("PASS: joiner backoff doubles 5s->60s with +/-20% jitter; <=12 requests in 5 minutes worst case; " +
        "compact checkpoint discovery is Rust-owned; $callSitesScanned scheduling call site(s) in " +
        "NearbyInvitations.kt/InvitationActivity.kt all " +
        "derive their delay from JoinRetryBackoff/intervalMs or a literal >= the floor")
}

private fun findRepoRoot(): File {
    var dir = File(".").absoluteFile
    while (!File(dir, "android/app/src/main/kotlin/dev/datafabric/atak/WorkspaceController.kt").exists()) {
        dir = dir.parentFile ?: error("repo root not found from ${File(".").absolutePath}")
    }
    return dir
}

/** Returns the index of the ')' matching the '(' at [openParenIndex], or null
 * if the parens never balance. Only tracks '(' / ')' depth -- braces and
 * brackets inside (e.g. a lambda body) don't affect where the call's own
 * closing paren falls. */
private fun matchingParen(text: String, openParenIndex: Int): Int? {
    require(text[openParenIndex] == '(')
    var depth = 0
    for (i in openParenIndex until text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return null
}

/** Splits a call's argument text on top-level commas, ignoring commas nested
 * inside (), {} or []. */
private fun splitTopLevelArgs(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val args = mutableListOf<String>()
    var depth = 0
    var start = 0
    for (i in text.indices) {
        when (text[i]) {
            '(', '{', '[' -> depth++
            ')', '}', ']' -> depth--
            ',' -> if (depth == 0) {
                args.add(text.substring(start, i))
                start = i + 1
            }
        }
    }
    args.add(text.substring(start))
    return args
}
