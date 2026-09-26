package dev.arachne.atak

import java.security.SecureRandom

/** Wire constants for the ATAK-plugin <-> Arachne-PTT-app "plugin link v2"
 * broadcast protocol. Deliberately free of android.* so this file, and the
 * pure logic below it, can be exercised by the host-JVM check programs
 * (scripts/check-host.sh) without an Android classpath. */
internal object PttLinkProtocol {
    const val VERSION = 1

    const val ATAK_PACKAGE = "com.atakmap.app.civ"
    const val PTT_PACKAGE = "dev.arachne.ptt"
    const val PTT_MAIN_ACTIVITY = "dev.arachne.ptt.MainActivity"

    /** PTT -> plugin: "does any workspace want to link?" */
    const val ACTION_REQUEST_OFFERS = "dev.arachne.atak.PTT_REQUEST_OFFERS"
    /** Plugin -> PTT: one workspace's live link offer. */
    const val ACTION_OFFER = "dev.arachne.ptt.ATAK_WORKSPACE_OFFER"
    /** Plugin -> PTT: a previously offered workspace is no longer connected. */
    const val ACTION_GONE = "dev.arachne.ptt.ATAK_WORKSPACE_GONE"
    /** Inert action wrapped by the `proof` PendingIntent; never broadcast. */
    const val ACTION_PROOF = "dev.arachne.atak.PTT_PROOF"
    /** Plugin -> PTT: open (or focus) a specific workspace. */
    const val ACTION_OPEN_WORKSPACE = "dev.arachne.ptt.OPEN_WORKSPACE"

    const val EXTRA_PROTOCOL = "protocol"
    const val EXTRA_WORKSPACE = "workspace"
    const val EXTRA_WORKSPACE_NAME = "workspaceName"
    const val EXTRA_MEMBER_NAME = "memberName"
    const val EXTRA_PORT = "port"
    const val EXTRA_TICKET = "ticket"
    const val EXTRA_EXPIRES_AT = "expiresAt"
    const val EXTRA_PROOF = "proof"

    /** Both the ticket's own lifetime and the `expiresAt` value reported in the offer:
     * one wall-clock concept, not two (there is no separate elapsedRealtime lifetime). */
    const val OFFER_TICKET_LIFETIME_MS = 60_000L
}

/** Per-workspace PTT link tickets. One live ticket per workspace: issuing a new
 * ticket for a workspace replaces only that workspace's previous ticket, never
 * another workspace's. A ticket is single-use and rejected once expired.
 * No Android dependency, so this is checkable on the host JVM; `now` is always
 * supplied by the caller so a check can drive the clock deterministically. */
internal class PttTicketStore(private val nextToken: () -> String = ::randomLinkToken) {
    private data class Ticket(val workspace: String, val expiresAt: Long)

    private val byToken = LinkedHashMap<String, Ticket>()
    private val tokenByWorkspace = HashMap<String, String>()

    @Synchronized
    fun issue(workspace: String, now: Long, lifetimeMs: Long = PttLinkProtocol.OFFER_TICKET_LIFETIME_MS): String {
        tokenByWorkspace.remove(workspace)?.let(byToken::remove)
        val token = nextToken()
        byToken[token] = Ticket(workspace, now + lifetimeMs)
        tokenByWorkspace[workspace] = token
        return token
    }

    /** True only for an unexpired ticket issued for exactly this workspace; consumes it (single use). */
    @Synchronized
    fun consume(token: String, workspace: String, now: Long): Boolean {
        val ticket = byToken[token] ?: return false
        if (ticket.workspace != workspace || ticket.expiresAt < now) return false
        byToken.remove(token)
        tokenByWorkspace.remove(workspace, token)
        return true
    }

    /** Drops a workspace's outstanding ticket (e.g. it stopped being connected) without touching others. */
    @Synchronized
    fun invalidate(workspace: String) {
        tokenByWorkspace.remove(workspace)?.let(byToken::remove)
    }

    @Synchronized
    fun clear() {
        byToken.clear()
        tokenByWorkspace.clear()
    }
}

private val linkTokenRandom = SecureRandom()

/** 64 lowercase hex characters (32 random bytes), matching the workspace/ticket id shape used across Arachne. */
private fun randomLinkToken(): String {
    val bytes = ByteArray(32)
    linkTokenRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** Builds the 8 OFFER extras. Host-checkable: `proof` is generic so a check can pass an opaque
 * marker instead of a real android.app.PendingIntent, while production code passes the real one. */
internal object PttOfferExtras {
    fun <P : Any> build(
        workspace: String,
        workspaceName: String,
        memberName: String,
        port: Int,
        ticket: String,
        expiresAt: Long,
        proof: P,
    ): Map<String, Any> = linkedMapOf(
        PttLinkProtocol.EXTRA_PROTOCOL to PttLinkProtocol.VERSION,
        PttLinkProtocol.EXTRA_WORKSPACE to workspace,
        PttLinkProtocol.EXTRA_WORKSPACE_NAME to workspaceName,
        PttLinkProtocol.EXTRA_MEMBER_NAME to memberName,
        PttLinkProtocol.EXTRA_PORT to port,
        PttLinkProtocol.EXTRA_TICKET to ticket,
        PttLinkProtocol.EXTRA_EXPIRES_AT to expiresAt,
        PttLinkProtocol.EXTRA_PROOF to proof,
    )
}

/** When a workspace drops out of scope, decides whether that is an actual leave/remove/end
 * (send GONE, which removes the workspace's "net" on the PTT side) or a merely transient
 * disconnect such as a pause (say nothing; PTT infers that from the loopback link closing).
 * Pure and total so it is exhaustively checkable: the caller supplies ground truth from the
 * saved-record list (`stillSaved`) and that record's own `ended` flag, never a derived status
 * string, since a workspace can drop out of scope for reasons unrelated to why it is or is not
 * still saved. */
internal object PttLinkGone {
    fun onDrop(stillSaved: Boolean, ended: Boolean): Boolean = !stillSaved || ended
}
