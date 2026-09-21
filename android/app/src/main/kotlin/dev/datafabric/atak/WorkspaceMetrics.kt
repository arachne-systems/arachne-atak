package dev.arachne.atak

import org.json.JSONObject

internal data class TrafficSample(val elapsedMs: Long, val received: Long, val sent: Long)
internal data class PeerPath(val member: String, val route: String, val rttMs: Long)

/** Rust-owned workspace lifecycle projection; Kotlin does not advance it. */
internal data class WorkspaceActivityView(val state: String, val reason: String?) {
    companion object {
        fun read(value: JSONObject?, previous: WorkspaceActivityView = WorkspaceActivityView("empty", null)): WorkspaceActivityView {
            val activity = value?.optJSONObject("activity") ?: return previous
            val state = activity.getString("state")
            require(state in setOf("empty", "creating", "joining", "synchronizing", "active", "recovering", "leaving", "resetting", "removed", "failed"))
            val reason = if (activity.isNull("reason")) null else activity.getString("reason")
            return WorkspaceActivityView(state, reason)
        }
    }
}

/** Local transport observations; no delivery or reachability claim. */
internal data class WorkspaceMetrics(
    val session: String, val samples: List<TrafficSample>, val receiveQueue: Int,
    val admissionQueue: Int, val admissionQueueBytes: Int, val admissionInFlight: Int, val approvalPending: Int,
    val pendingObjects: Int, val pendingSends: Int, val repairJobs: Int,
    val paths: List<PeerPath>, val pathsLimited: Boolean
) {
    val latest get() = samples.last()
    val pending get() = receiveQueue + pendingObjects + pendingSends
    companion object {
        fun read(value: JSONObject, previous: WorkspaceMetrics?, elapsedMs: Long, pendingSends: Int): WorkspaceMetrics {
            val session = value.getJSONArray("session").toString()
            val next = TrafficSample(elapsedMs, value.getLong("received_bytes"), value.getLong("sent_bytes"))
            require(next.received >= 0 && next.sent >= 0 && pendingSends >= 0)
            val history = previous?.takeIf { it.session == session && it.latest.elapsedMs < elapsedMs &&
                it.latest.received <= next.received && it.latest.sent <= next.sent }?.samples.orEmpty()
                .filter { it.elapsedMs >= elapsedMs - 600_000 }.takeLast(300) + next
            fun count(key: String) = value.getInt(key).also { require(it >= 0) }
            val paths = value.getJSONArray("paths")
            require(paths.length() <= 256)
            return WorkspaceMetrics(session, history, count("receive_queue"), count("admission_queue"), count("admission_queue_bytes"), count("admission_in_flight"), count("approval_pending"), count("pending_objects"), pendingSends,
                count("repair_jobs"), (0 until paths.length()).map { index ->
                    val path = paths.getJSONObject(index)
                    val member = path.getJSONArray("member").also { require(it.length() == 32) }
                    PeerPath((0 until 32).joinToString("") { "%02x".format(member.getInt(it).also { n -> require(n in 0..255) }) },
                        path.getString("route").also { require(it in setOf("direct", "relay", "custom")) },
                        path.getLong("rtt_ms").also { require(it >= 0) })
                }, value.getBoolean("paths_limited"))
        }
    }
}
