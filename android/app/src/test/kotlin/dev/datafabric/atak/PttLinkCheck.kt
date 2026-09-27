package dev.arachne.atak

import java.io.File

/** Pure-Kotlin checks for the plugin-link-v2 wire pieces in PttLink.kt: the
 * per-workspace ticket store and the OFFER extras builder. No Android
 * classpath is available here, so PttWorkspaceBridge's broadcast/RPC wiring
 * itself is exercised by assembleCivDebug and device testing, not this check. */
fun main() {
    fun store(): PttTicketStore {
        var next = 0
        return PttTicketStore(nextToken = { "token-${next++}" })
    }

    val w1 = "1".repeat(64)
    val w2 = "2".repeat(64)

    // Ticket per workspace: W1 and W2 each hold their own independent, valid ticket.
    run {
        val tickets = store()
        val now = 1_000_000L
        val ticketW1 = tickets.issue(w1, now)
        val ticketW2 = tickets.issue(w2, now)
        check(ticketW1 != ticketW2) { "tickets for two different workspaces must not collide" }
        check(tickets.consume(ticketW2, w2, now)) { "W2's own ticket must validate against W2" }
        check(tickets.consume(ticketW1, w1, now)) { "W1's own ticket must validate against W1, independent of W2" }
    }

    // A ticket only validates for the workspace it was issued for.
    run {
        val tickets = store()
        val now = 0L
        val ticket = tickets.issue(w1, now)
        check(!tickets.consume(ticket, w2, now)) { "a ticket must be rejected for a workspace it was not issued for" }
        check(tickets.consume(ticket, w1, now)) { "the wrong-workspace attempt above must not have consumed the real ticket" }
    }

    // Reissuing for W1 invalidates only W1's previous ticket, never W2's.
    run {
        val tickets = store()
        val now = 1_000_000L
        val firstW1 = tickets.issue(w1, now)
        val ticketW2 = tickets.issue(w2, now)
        val secondW1 = tickets.issue(w1, now + 1)
        check(firstW1 != secondW1) { "reissuing for W1 must mint a new token" }
        check(!tickets.consume(firstW1, w1, now + 1)) { "W1's old ticket must be invalidated by reissuing W1" }
        check(tickets.consume(ticketW2, w2, now + 1)) { "W2's ticket must survive a reissue for W1" }
        check(tickets.consume(secondW1, w1, now + 1)) { "W1's newest ticket must still validate" }
    }

    // Expiry rejection.
    run {
        val tickets = store()
        val now = 5_000L
        val ticket = tickets.issue(w1, now, lifetimeMs = 1_000L)
        check(!tickets.consume(ticket, w1, now + 1_001L)) { "an expired ticket must be rejected" }
    }
    run {
        val tickets = store()
        val now = 5_000L
        val ticket = tickets.issue(w1, now, lifetimeMs = 1_000L)
        check(tickets.consume(ticket, w1, now + 999L)) { "a ticket still inside its lifetime must be accepted" }
    }

    // Single use: consuming a ticket removes it.
    run {
        val tickets = store()
        val now = 42L
        val ticket = tickets.issue(w1, now)
        check(tickets.consume(ticket, w1, now)) { "first use of a fresh ticket must succeed" }
        check(!tickets.consume(ticket, w1, now)) { "a used ticket must be removed, not reusable" }
    }

    // invalidate() drops only the named workspace's outstanding ticket.
    run {
        val tickets = store()
        val now = 0L
        val ticketW1 = tickets.issue(w1, now)
        val ticketW2 = tickets.issue(w2, now)
        tickets.invalidate(w1)
        check(!tickets.consume(ticketW1, w1, now)) { "invalidate() must drop that workspace's ticket" }
        check(tickets.consume(ticketW2, w2, now)) { "invalidate() must not affect other workspaces" }
    }

    // A compromised or buggy plugin cannot retain an unbounded number of live tickets.
    run {
        var next = 0
        val tickets = PttTicketStore(nextToken = { "bounded-${next++}" }, maxOutstanding = 2)
        val first = tickets.issue(w1, 0L)
        val second = tickets.issue(w2, 0L)
        val thirdWorkspace = "3".repeat(64)
        val third = tickets.issue(thirdWorkspace, 0L)
        check(!tickets.consume(first, w1, 0L)) { "the oldest ticket must be evicted at the bound" }
        check(tickets.consume(second, w2, 0L)) { "newer tickets must survive bounded eviction" }
        check(tickets.consume(third, thirdWorkspace, 0L)) { "the newest ticket must survive bounded eviction" }
    }

    // The real (non-test) token generator produces 64 lowercase hex characters.
    run {
        val tickets = PttTicketStore()
        val token = tickets.issue(w1, 0L)
        check(Regex("^[0-9a-f]{64}$").matches(token)) { "a real ticket token must be 64 lowercase hex characters, got: $token" }
    }

    // Offer extras builder: exactly the 8 extras from the spec, with the right types and values.
    run {
        val proofMarker = Any()
        val extras = PttOfferExtras.build(
            workspace = "a".repeat(64), workspaceName = "Blue Team", memberName = "Rook",
            port = 40123, ticket = "b".repeat(64), expiresAt = 9_999_999_999L, proof = proofMarker,
        )
        val expectedKeys = setOf("protocol", "workspace", "workspaceName", "memberName", "port", "ticket", "expiresAt", "proof")
        check(extras.size == 8 && extras.keys == expectedKeys) { "offer must carry exactly these 8 extras, got: ${extras.keys}" }
        check(extras["protocol"] == 1 && extras.getValue("protocol") is Int) { "protocol must be the Int 1" }
        check(extras["workspace"] == "a".repeat(64) && extras.getValue("workspace") is String) { "workspace must be a String" }
        check(extras["workspaceName"] == "Blue Team" && extras.getValue("workspaceName") is String) { "workspaceName must be a String" }
        check(extras["memberName"] == "Rook" && extras.getValue("memberName") is String) { "memberName must be a String" }
        check(extras["port"] == 40123 && extras.getValue("port") is Int) { "port must be an Int" }
        check(extras["ticket"] == "b".repeat(64) && extras.getValue("ticket") is String) { "ticket must be a String" }
        check(extras["expiresAt"] == 9_999_999_999L && extras.getValue("expiresAt") is Long) { "expiresAt must be a Long" }
        check(extras["proof"] === proofMarker) { "proof must be passed through unchanged, whatever type it is" }
    }

    // Protocol mismatch: answersRequest is the actual gate handleRequestOffers uses; it must
    // accept only VERSION and reject everything else, including the -1 a missing extra reads as.
    run {
        check(PttLinkProtocol.VERSION == 1) { "protocol version constant must be 1 per the link-v2 spec" }
        check(PttLinkProtocol.answersRequest(1)) { "protocol 1 must be answered" }
        for (bogus in listOf(0, 2, -1, 99)) {
            check(!PttLinkProtocol.answersRequest(bogus)) { "protocol $bogus must be ignored, not answered" }
        }
    }

    // Source scan: the old one-ticket "Push to talk" launch path is replaced, not kept beside the new one.
    val root = findRepoRoot()
    val bridge = File(root, "android/app/src/main/kotlin/dev/datafabric/atak/PttWorkspaceBridge.kt").readText()
    check("PttLinkProtocol.answersRequest(protocol)" in bridge) {
        "handleRequestOffers must gate on PttLinkProtocol.answersRequest, not re-implement the comparison inline"
    }
    check("PLUGIN_SESSION" !in bridge && "fun launchIntent" !in bridge) {
        "the old one-ticket PLUGIN_SESSION launch path must be deleted, not kept beside plugin-link-v2"
    }
    val offerFunction = bridge.substringAfter("private fun sendOffer(").substringBefore("\n    private fun ")
    check("tickets.clear()" !in offerFunction && "tickets.issue(" in offerFunction) {
        "issuing one workspace's ticket (sendOffer) must not clear every other workspace's ticket"
    }
    val plugin = File(root, "android/app/src/main/kotlin/dev/datafabric/atak/FabricPlugin.kt").readText()
    check("dev.arachne.ptt.PLUGIN_SESSION" !in plugin) { "no caller may reference the retired PLUGIN_SESSION action" }
    check("requestOffersReceiver" in plugin && "ACTION_REQUEST_OFFERS" in plugin) {
        "the plugin must register a receiver for PTT_REQUEST_OFFERS"
    }
    check("ACTION_OPEN_WORKSPACE" in plugin) { "Push to talk must launch PTT with the OPEN_WORKSPACE action" }

    // Source scan: OFFER/GONE share sender identity on API 34+ (BroadcastOptions), and every
    // broadcast still carries the `proof` PendingIntent regardless of API level.
    val broadcastFunction = bridge.substringAfter("private fun sendExplicitBroadcast(").substringBefore("\n    private fun ")
    check("UPSIDE_DOWN_CAKE" in broadcastFunction) {
        "sendExplicitBroadcast must branch on Build.VERSION_CODES.UPSIDE_DOWN_CAKE (API 34)"
    }
    check("setShareIdentityEnabled(true)" in broadcastFunction) {
        "the API 34+ branch must call BroadcastOptions.makeBasic().setShareIdentityEnabled(true)"
    }
    check(bridge.contains(".putExtra(PttLinkProtocol.EXTRA_PROOF, proof())")) {
        "GONE must still carry the proof PendingIntent extra on every API level"
    }
    check(Regex("proof\\s*=\\s*proof\\(\\)").containsMatchIn(bridge)) {
        "OFFER must still carry the proof PendingIntent extra (via the offer-extras builder) on every API level"
    }

    // GONE-vs-transient-drop decision: pure and behavioral, per the coordinator's truth table.
    run {
        check(!PttLinkGone.onDrop(stillSaved = true, ended = false)) {
            "paused, or a workspace merely absent from this snapshot but still saved and not ended, must not send GONE"
        }
        check(PttLinkGone.onDrop(stillSaved = true, ended = true)) { "left or ended must send GONE" }
        check(PttLinkGone.onDrop(stillSaved = false, ended = false)) { "removed or dismissed must send GONE" }
        check(PttLinkGone.onDrop(stillSaved = false, ended = true)) { "removed or dismissed must send GONE even if also ended" }
    }

    // Source scan: GONE fires only for an actual leave/remove/end, never for a merely transient
    // disconnect and never for ATAK/the plugin stopping (close() must not send it at all).
    val closeFunction = bridge.substringAfter("override fun close() {").substringBefore("\n    private fun ")
    check("sendGone" !in closeFunction) {
        "close() must not send GONE -- ATAK/the plugin stopping is not the user leaving, removing or ending a workspace"
    }
    val updateLockedFunction = bridge.substringAfter("private fun updateLocked(state: WorkspaceConnectionView): List<Pending> {")
        .substringBefore("\n    fun observePli(")
    check("sendGone(workspace)" in updateLockedFunction && "PttLinkGone.onDrop(" in updateLockedFunction) {
        "updateLocked() must gate GONE through PttLinkGone.onDrop on the saved record's own ended flag, " +
            "not on merely leaving the connected set or on a derived status string"
    }
    check("state.pauses" !in bridge) {
        "GONE must not be decided from a derived pauses status string -- a workspace can drop out of " +
            "scope for reasons unrelated to its pauses entry; use the saved record's ended flag instead"
    }
    check("state.revision <= lastRevision" in updateLockedFunction) {
        "updateLocked() must ignore a snapshot whose revision is not newer than the last one it applied"
    }

    // Source scan: update() must serialize against out-of-order/overlapping WorkspaceController
    // callbacks, AND must not invoke a foreign Pending.complete() while holding its own lock --
    // that callback came from a WorkspaceController's own receive path, which (like
    // WorkspaceController.publish) may take its own monitor around completion bookkeeping, and
    // two linked workspaces' controller threads could then deadlock on each other's locks.
    check(Regex("synchronized\\(this\\)\\s*\\{\\s*updateLocked\\(state\\)\\s*\\}").containsMatchIn(bridge)) {
        "update() must run updateLocked() inside synchronized(this) and fire its failed deliveries only after that returns"
    }
    check(".complete(" !in updateLockedFunction) {
        "updateLocked() must not call Pending.complete() itself -- return the failed deliveries " +
            "(as it does today) so update() can fire them after releasing the bridge's own lock"
    }
    check(Regex("private fun disconnectPeer\\(workspace: String\\): List<Pending>").containsMatchIn(bridge) &&
        Regex("private fun closePeer\\(target: Peer, workspace: String\\): List<Pending>").containsMatchIn(bridge)) {
        "disconnectPeer/closePeer must return the deliveries they failed instead of completing them inline"
    }

    println("PASS: PTT link v2 tickets are per-workspace (independent issue/consume/reissue/invalidate), " +
        "bounded, reject expired and reused tickets, the real token is 64 lowercase hex, offer extras carry all 8 " +
        "typed fields, protocol mismatch is rejected by answersRequest, OFFER/GONE share sender identity " +
        "on API 34+ while still carrying the proof PendingIntent on every API level, GONE fires only from " +
        "the saved record's ended flag (never a derived status string, transient disconnect, or plugin " +
        "stop), update() ignores out-of-order snapshots and defers Pending.complete() until its own lock " +
        "is released, and the retired PLUGIN_SESSION path is fully removed")
}

private fun findRepoRoot(): File {
    var dir = File(".").absoluteFile
    while (!File(dir, "android/app/src/main/kotlin/dev/datafabric/atak/WorkspaceController.kt").exists()) {
        dir = dir.parentFile ?: error("repo root not found from ${File(".").absolutePath}")
    }
    return dir
}
