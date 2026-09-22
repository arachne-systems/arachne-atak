package dev.arachne.atak

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID

internal data class PublicationOutcome(val remoteAccepted: Int, val failed: Int, val uncertain: Boolean, val queued: Boolean)

/** Confined to WorkspaceController's worker; never called on the JNI callback
 * worker. Recipient audiences are verified by the reusable runtime roster. */
internal class WorkspaceData(
    private val store: WorkspaceStore,
    metadata: JSONObject,
    topics: Set<String>,
    private val call: (JSONObject) -> JSONObject?,
    private val received: ((JSONObject, (Boolean) -> Unit) -> Unit)?,
    private val deliveryClock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val reachablePeers: () -> List<ByteArray> = { emptyList() },
    private val continuity: (String?) -> Unit = {}
) {
    companion object {
        private const val CONTINUITY_INTERVAL_MS = 30_000L
        private val topicPattern = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
        private val retainedEventTopics = setOf("chat/messages/v1", "atak/native/v1/chat")
        fun validTopic(topic: String) = topic.length in 1..128 && topicPattern.matches(topic)
        fun outcome(report: JSONObject): PublicationOutcome {
            if (report.has("network_error")) return PublicationOutcome(0, 0, true, false)
            fun endpoint(value: JSONArray): List<Int> {
                require(value.length() == 32)
                return (0 until 32).map { value.getInt(it).also { n -> require(n in 0..255) } }
            }
            val self = endpoint(report.getJSONArray("publisher_endpoint"))
            val admission = report.getJSONObject("admission")
            val admitted = admission.getJSONArray("admitted")
            val remote = (0 until admitted.length()).map { endpoint(admitted.getJSONArray(it)) }.filter { it != self }
            return PublicationOutcome(
                remote.distinct().size,
                admission.getJSONArray("failed").length(),
                false,
                admission.optBoolean("queued", false)
            )
        }
    }
    val workspace = metadata.getJSONArray("workspace").bytes()
    private val publisherEndpoint = checkNotNull(call(JSONObject().put("op", "endpoint_info"))).getJSONArray("endpoint_key").bytes()
    // Fixed all-member profile v1: membership is its only changing policy input.
    // A new permission/profile scheme needs its own revision negotiation.
    private val revision = Math.addExact(metadata.getLong("epoch"), 1)
    private var topics = topics.sorted().also { require(it.size <= 64 && it.all(::validTopic)) }
    private var nextTopic = 0
    private var nextAnnouncement = 0L
    private class Delivery(val publication: JSONObject, val scope: JSONObject) {
        val completion = java.util.concurrent.atomic.AtomicInteger(0)
    }
    private val delivering = linkedMapOf<String, Delivery>()
    private val deferred = linkedMapOf<String, Pair<JSONObject, Long>>()
    private var nextContinuityAttempt = 0L
    private var rangeRecoveryPending = false
    private var nextReachableSweep = 0L
    private var reachableSweepCursor = 0
    // Only the explicitly supplied publisher route may seed holder recovery.
    // There is no roster walk for an arbitrary offline member.
    private var lastRecoveryPeer: JSONArray? = null
    private var preferCurrentRecovery = false
    private val lastCurrentTopic = mutableMapOf<String, String>()
    private var currentRecoveryTopic: String? = null
    private var directRecoveryTopic: String? = null
    private val installedInterests = mutableSetOf<String>()
    private val pendingCurrentTopics = ArrayDeque<Pair<String, ByteArray>>()
    private val pendingReachableHistory = ArrayDeque<ByteArray>()
    private val pendingReachableCurrent = ArrayDeque<Pair<String, ByteArray>>()
    private fun retainedInterests() = topics.filter { it in retainedEventTopics }
    private fun currentSelection(authority: ByteArray?, preferredTopic: String? = null): Pair<String, ByteArray>? {
        authority ?: return null
        val authorityKey = authority.joinToString("") { "%02x".format(it.toInt() and 255) }
        val selections = buildList {
            installedInterests.filter { it in CotTopics.nativeCurrent }
                .forEach { add(it to WorkspaceCurrent.selector(it)) }
            installedInterests.filter { WorkspaceFeeds.authority(it)?.contentEquals(authority) == true }
                .forEach { add(it to WorkspaceFeeds.currentSelector(it)) }
            if (WorkspaceFeeds.CATALOG in installedInterests) add(WorkspaceFeeds.CATALOG to WorkspaceCurrent.selector(WorkspaceFeeds.CATALOG))
            if (WorkspaceResources.CATALOG in installedInterests) add(WorkspaceResources.CATALOG to WorkspaceCurrent.selector(WorkspaceResources.CATALOG))
            if (WorkspaceResources.CLAIMS in installedInterests) add(WorkspaceResources.CLAIMS to WorkspaceCurrent.selector(WorkspaceResources.CLAIMS))
        }.sortedBy { it.first }
        preferredTopic?.let { topic -> return selections.firstOrNull { it.first == topic } }
        val previous = lastCurrentTopic[authorityKey]
        return selections.firstOrNull { previous == null || it.first > previous } ?: selections.firstOrNull()
    }

    init {
        val candidate = checkNotNull(call(JSONObject().put("op", "enable_object_delivery")))
        if (candidate.getString("state") != "object_delivery_enabled")
            store.commitReception(candidate) { checkNotNull(call(it)) }
        call(JSONObject().put("op", "install_workspace_policy").put("revision", revision))
    }

    fun interests(next: Set<String>) {
        require(next.size <= 64 && next.all(::validTopic))
        // Withdraw an in-flight retained-event request without touching already
        // accepted inbox work when one of its selected topics is disabled.
        if (retainedInterests().any { it !in next }) {
            call(JSONObject().put("op", "cancel_recovery_range"))
            rangeRecoveryPending = false
            continuity(null)
        }
        if (directRecoveryTopic?.let { it !in next } == true) {
            call(JSONObject().put("op", "cancel_direct_recovery"))
            directRecoveryTopic = null
            continuity(null)
        }
        for (topic in topics.filter { it !in next }) {
            checkNotNull(call(JSONObject().put("op", "set_interest").put("workspace", workspace.json())
                .put("revision", revision).put("topic", topic).put("subscribed", false)))
            installedInterests.remove(topic)
            pendingCurrentTopics.removeAll { it.first == topic }
            pendingReachableCurrent.removeAll { it.first == topic }
            if (topic == currentRecoveryTopic) {
                call(JSONObject().put("op", "cancel_current_view"))
                currentRecoveryTopic = null
                continuity(null)
            }
        }
        topics = next.sorted()
        if (retainedInterests().isEmpty()) pendingReachableHistory.clear()
        reconnect()
    }

    fun publish(topic: String, bytes: ByteArray, recipients: List<ByteArray> = emptyList(), current: WorkspaceCurrent? = null): JSONObject {
        require(validTopic(topic) && bytes.size <= 12 * 1024)
        require(current == null || (recipients.isEmpty() && current.selector.size == 32 &&
            current.replacementKey.size == 32 && current.expiresAt > 0))
        val id = UUID.randomUUID().let { ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array() }
        val request = JSONObject().put("op", "stage_network_publication")
            .put("revision", revision).put("topic", topic).put("id", id.json()).put("payload", bytes.json())
            .put("recipients", JSONArray(recipients.map { it.json() }))
            .put("bulk", topic == WorkspaceResources.RESPONSE)
        current?.let { request.put("current", JSONObject().put("selector", it.selector.json())
            .put("replacement_key", it.replacementKey.json()).put("expires_at", it.expiresAt)
            .put("tombstone", it.tombstone)) }
        val staged = checkNotNull(call(request))
        return store.commitPublication(staged) { checkNotNull(call(it)) }.put("publisher_endpoint", publisherEndpoint.json())
    }

    fun reconnect() { nextTopic = 0; nextAnnouncement = 0 }

    fun discoverCutoff(peer: JSONArray?, authority: JSONArray? = null, preferredTopic: String? = null): Boolean {
        val now = deliveryClock()
        if (now < nextContinuityAttempt || currentRecoveryTopic != null || rangeRecoveryPending || directRecoveryTopic != null) return false
        peer?.let { lastRecoveryPeer = JSONArray(it.toString()) }
        try {
            val retained = retainedInterests().isNotEmpty()
            val selection = currentSelection(authority?.let { values ->
                ByteArray(values.length()) { values.getInt(it).also { value -> require(value in 0..255) }.toByte() }
            }, preferredTopic)
            when {
                selection != null && (preferredTopic != null || preferCurrentRecovery || !retained) -> {
                    preferCurrentRecovery = false
                    val authorityKey = checkNotNull(authority).let { values ->
                        (0 until values.length()).joinToString("") { "%02x".format(values.getInt(it)) }
                    }
                    val pending = checkNotNull(call(JSONObject().put("op", "fetch_current_view")
                        .put("authority", authority).put("revision", revision)
                        .put("topic", selection.first).put("selector", selection.second.json())))
                    lastCurrentTopic[authorityKey] = selection.first
                    currentRecoveryTopic = selection.first
                    when (pending.getString("state")) {
                        "current_view_pending" -> {
                            continuity(when (selection.first) {
                                "atak/native/v1/pli" -> "Checking for current position updates…"
                                in CotTopics.nativeCurrent -> "Checking for current map data…"
                                else -> "Checking for current feed updates…"
                            })
                            Log.i("Arachne", "WORKSPACE_CURRENT_VIEW_STARTED topic=${selection.first}")
                        }
                        "current_view_source_waiting" -> {
                            currentRecoveryTopic = null
                            // The source is absent, not busy. Keep the queued discovery
                            // for a bounded later retry instead of draining every offline
                            // member on this worker turn.
                            nextContinuityAttempt = now + CONTINUITY_INTERVAL_MS
                            continuity(when (selection.first) {
                                "atak/native/v1/pli" -> "Waiting for a connected member with current positions."
                                in CotTopics.nativeCurrent -> "Waiting for a connected member with current map data."
                                else -> "Waiting for a connected member with current feed updates."
                            })
                            Log.i("Arachne", "WORKSPACE_CURRENT_VIEW_WAITING topic=${selection.first}")
                            return false
                        }
                        else -> error("Unexpected current-view start")
                    }
                }
                retained && peer != null -> {
                    preferCurrentRecovery = selection != null
                    val selected = retainedInterests()
                    continuity("Checking for missed Chat updates…")
                    val pending = checkNotNull(call(JSONObject().put("op", "discover_recovery_cutoff")
                        .put("peer", peer).put("revision", revision).put("topics", JSONArray(selected))))
                    check(pending.getString("state") == "recovery_cutoff_pending")
                    rangeRecoveryPending = true
                    Log.i("Arachne", "WORKSPACE_CUTOFF_STARTED")
                }
            }
            nextContinuityAttempt = 0L
            return true
        } catch (error: Exception) {
            currentRecoveryTopic = null
            nextContinuityAttempt = deliveryClock() + 5_000L
            continuity("Couldn’t check for missing data. Live sharing continues.")
            Log.w("Arachne", "WORKSPACE_CONTINUITY_START_REJECTED reason=${error.message ?: error.javaClass.simpleName}")
            return false
        }
    }

    private fun pollCutoff() {
        try {
            val result = call(JSONObject().put("op", "poll_recovery_cutoff")) ?: return
            rangeRecoveryPending = false
            check(!result.getBoolean("accepted_progress"))
            when (result.getString("state")) {
                "recovery_cutoff_observed" -> {
                    check(result.getJSONArray("workspace").bytes().contentEquals(workspace))
                    check(result.getLong("revision") == revision)
                    val head = result.getLong("head")
                    Log.i("Arachne", "WORKSPACE_CUTOFF_OBSERVED head=$head revision=$revision")
                    val retainedAfter = result.getLong("retained_after")
                    val acceptedThrough = result.getLong("accepted_through")
                    val selected = result.getJSONArray("topics").let { values ->
                        (0 until values.length()).map { values.getString(it) }
                    }
                    if (selected.any { it !in topics || it !in retainedEventTopics }) {
                        continuity(null)
                        return
                    }
                    when {
                        acceptedThrough > head -> {
                            continuity("Couldn’t verify earlier updates. Keeping saved data.")
                            Log.w("Arachne", "WORKSPACE_RECOVERY_UNAVAILABLE reason=publisher_rollback")
                        }
                        acceptedThrough < retainedAfter -> {
                            continuity("Some earlier Chat updates have expired and are no longer available.")
                            Log.w("Arachne", "WORKSPACE_RECOVERY_UNAVAILABLE reason=history_expired after=$retainedAfter")
                        }
                        head > acceptedThrough -> {
                            continuity("Missed Chat updates found. Recovering now…")
                            val request = JSONObject().put("op", "fetch_recovery_range")
                                .put("author", result.getJSONArray("author")).put("revision", revision)
                                .put("topics", JSONArray(selected))
                                .put("after", acceptedThrough).put("through", head)
                            // Keep the holder that supplied the cutoff. It may be
                            // reachable by endpoint ID without being a gossip neighbor.
                            lastRecoveryPeer?.let { request.put("peer", it) }
                            val started = call(request)
                            rangeRecoveryPending = started?.optString("state") in setOf("recovery_range_pending", "recovery_source_waiting")
                            if (started?.getString("state") == "recovery_source_waiting") {
                                continuity("Waiting for a connected member with the missing Chat updates.")
                                Log.i("Arachne", "WORKSPACE_RECOVERY_WAITING")
                            }
                        }
                        else -> continuity(null)
                    }
                }
                "recovery_cutoff_denied" -> {
                    continuity(null)
                    Log.i("Arachne", "WORKSPACE_CUTOFF_DENIED")
                }
                else -> error("Unexpected cutoff result")
            }
        } catch (error: Exception) {
            // The publisher may be offline while another authorized member holds
            // an exact publisher-signed range beginning at our durable cursor.
            val selected = retainedInterests()
            val peer = lastRecoveryPeer
            val pending = try {
                if (peer == null || selected.isEmpty()) null else call(JSONObject()
                    .put("op", "fetch_recovery_range").put("peer", peer)
                    .put("revision", revision).put("topics", JSONArray(selected)))
            } catch (fallback: Exception) { null }
            if (pending?.getString("state") == "recovery_range_pending") {
                rangeRecoveryPending = true
                continuity("Checking connected members for missed Chat updates…")
                Log.i("Arachne", "WORKSPACE_RECOVERY_HOLDER_STARTED")
            } else {
                rangeRecoveryPending = false
                continuity("Couldn’t confirm missed updates. Live sharing continues.")
                Log.w("Arachne", "WORKSPACE_RECOVERY_HOLDER_WAITING")
            }
        }
    }

    private fun recover() {
        pollCutoff()
        val current = try { call(JSONObject().put("op", "poll_current_view")) }
            catch (error: Exception) {
                currentRecoveryTopic = null
                continuity("Couldn’t check for missing data. Live sharing continues.")
                Log.w("Arachne", "WORKSPACE_CURRENT_VIEW_FAILED"); null
            }
        if (current != null) when (current.getString("state")) {
            "current_view_ready" -> {
                val feed = currentRecoveryTopic?.startsWith("feeds/") == true
                val map = currentRecoveryTopic?.let { it != "atak/native/v1/pli" && it in CotTopics.nativeCurrent } == true
                continuity(if (feed) "Updating current feed data…" else if (map) "Updating current map data…" else "Updating current positions…")
                val staged = try { checkNotNull(call(JSONObject().put("op", "stage_current_view"))) }
                catch (error: Exception) {
                    call(JSONObject().put("op", "cancel_current_view"))
                    continuity(if (feed) "Current feed data could not be verified. Keeping saved data." else if (map) "Current map data could not be verified. Keeping saved data." else "Current positions could not be verified. Keeping saved positions.")
                    currentRecoveryTopic = null
                    Log.w("Arachne", "WORKSPACE_CURRENT_VIEW_UNAVAILABLE reason=staging_rejected")
                    return
                }
                val saved = store.commitCurrent(staged) { checkNotNull(call(it)) }
                val topic = currentRecoveryTopic
                currentRecoveryTopic = null
                continuity(null)
                Log.i("Arachne", "WORKSPACE_CURRENT_VIEW_SAVED topic=$topic cut=${saved.getLong("cut")} pending=${saved.getInt("pending")} stale=${saved.getInt("stale")}")
            }
            "current_view_unavailable" -> {
                val feed = currentRecoveryTopic?.startsWith("feeds/") == true
                val map = currentRecoveryTopic?.let { it != "atak/native/v1/pli" && it in CotTopics.nativeCurrent } == true
                currentRecoveryTopic = null
                continuity(if (feed) "Current feed data is unavailable from connected members." else if (map) "Current map data is unavailable from connected members." else "Current positions are unavailable from connected members.")
                Log.i("Arachne", "WORKSPACE_CURRENT_VIEW_UNAVAILABLE reason=holder_has_no_view")
            }
            else -> error("Unexpected current-view result")
        }
        val reply = try { call(JSONObject().put("op", "poll_recovery_range")) }
            catch (error: Exception) {
                continuity("Couldn’t check for missing data. Live sharing continues.")
                Log.w("Arachne", "WORKSPACE_RECOVERY_FAILED"); null
            }
        if (reply != null) {
            if (reply.getString("state") == "recovery_range_ready") {
                continuity("Recovering missed Chat updates…")
                // Saving/adoption errors propagate: close and restore authoritative disk state.
                val staged = try { checkNotNull(call(JSONObject().put("op", "stage_recovery_range")
                    .put("retain_until", System.currentTimeMillis() / 1000 + 3600))) }
                catch (error: Exception) {
                    call(JSONObject().put("op", "cancel_recovery_range"))
                    rangeRecoveryPending = false
                    continuity("Missed Chat updates could not be verified. Keeping saved data.")
                    Log.w("Arachne", "WORKSPACE_RECOVERY_UNAVAILABLE reason=staging_rejected")
                    return
                }
                if (staged.getString("state") == "recovery_no_new_objects") {
                    rangeRecoveryPending = false
                    continuity(null)
                    return
                }
                val saved = store.commitRecovery(staged) { checkNotNull(call(it)) }
                rangeRecoveryPending = false
                continuity(null)
                Log.i("Arachne", "WORKSPACE_RECOVERY_SAVED count=${saved.getInt("publication_count")}")
            } else if (reply.optBoolean("automatic_source")) {
                // A holder lookup without a publisher cutoff found no retained
                // range; that does not prove this reader missed anything.
                rangeRecoveryPending = false
                continuity(null)
                Log.i("Arachne", "WORKSPACE_RECOVERY_UNCONFIRMED")
            } else {
                rangeRecoveryPending = false
                continuity("Missed Chat updates are unavailable from connected members.")
                Log.w("Arachne", "WORKSPACE_RECOVERY_UNAVAILABLE reason=${reply.optString("reason")}")
            }
        }
        val direct = try { call(JSONObject().put("op", "poll_direct_recovery")) }
            catch (error: Exception) {
                call(JSONObject().put("op", "cancel_direct_recovery"))
                directRecoveryTopic = null
                continuity("Couldn’t recover a missed private update. Live sharing continues.")
                Log.w("Arachne", "WORKSPACE_DIRECT_RECOVERY_FAILED"); null
            }
        if (direct != null) {
            if (direct.getString("state") == "direct_recovery_ready") {
                val staged = try { checkNotNull(call(JSONObject().put("op", "stage_direct_recovery"))) }
                catch (error: Exception) {
                    call(JSONObject().put("op", "cancel_direct_recovery"))
                    directRecoveryTopic = null
                    continuity("A missed private update could not be verified. Keeping saved data.")
                    Log.w("Arachne", "WORKSPACE_DIRECT_RECOVERY_UNAVAILABLE reason=staging_rejected")
                    return
                }
                if (staged.getString("state") != "direct_recovery_already_covered") {
                    val saved = store.commitRecovery(staged) { checkNotNull(call(it)) }
                    Log.i("Arachne", "WORKSPACE_DIRECT_RECOVERY_SAVED count=${saved.getInt("publication_count")}")
                }
                directRecoveryTopic = null
                continuity(null)
            } else {
                val missed = try { checkNotNull(call(JSONObject().put("op", "stage_direct_miss"))) }
                catch (_: Exception) {
                    call(JSONObject().put("op", "cancel_direct_recovery"))
                    directRecoveryTopic = null
                    continuity("A missed private update could not be recovered. Later updates remain saved.")
                    return
                }
                val saved = store.commitRecovery(missed) { checkNotNull(call(it)) }
                directRecoveryTopic = null
                continuity("${saved.getLong("missing_count")} private update(s) could not be recovered. Later updates are available.")
                Log.w("Arachne", "WORKSPACE_DIRECT_RECOVERY_MISSED count=${saved.getLong("missing_count")} reason=${direct.optString("reason")}")
            }
        }
        if (directRecoveryTopic == null) try {
            val gap = call(JSONObject().put("op", "next_direct_gap")) ?: return
            val topic = gap.getString("topic")
            if (topic !in topics) return
            val started = checkNotNull(call(JSONObject().put("op", "fetch_direct_recovery")
                .put("author", gap.getJSONArray("author")).put("revision", gap.getLong("revision"))
                .put("topic", topic).put("recipients", gap.getJSONArray("recipients"))
                .put("after", gap.getLong("after")).put("through", gap.getLong("through"))))
            if (started.getString("state") == "direct_recovery_pending") {
                directRecoveryTopic = topic
                continuity("Recovering a missed private update…")
            }
        } catch (_: Exception) { Unit }
    }

    /** Attempt accepted application work. False means delivery is pending, not
     * that replication, recovery or read-only control work must stop. */
    fun drainPending(): Boolean {
        val consumer = received ?: return true
        val now = deliveryClock()
        deferred.entries.removeAll { it.value.second <= now }
        // Independent application streams may progress while another importer
        // waits. The durable inbox still owns every payload and acknowledgement.
        repeat(16) {
            for ((key, delivery) in delivering.toMap()) {
                val current = delivery.publication
                when (delivery.completion.get()) {
                    1 -> {
                        val staged = checkNotNull(call(JSONObject().put("op", "stage_object_acknowledgement")
                            .put("member", current.getJSONArray("member")).put("topic", current.getString("topic"))
                            .put("counter", current.getLong("counter")).put("id", current.getJSONArray("id"))))
                        store.commitReception(staged) { checkNotNull(call(it)) }
                        Log.i("Arachne", "WORKSPACE_APPLICATION_ACKNOWLEDGED topic=${current.getString("topic")} counter=${current.getLong("counter")}")
                        delivering.remove(key)
                    }
                    2 -> {
                        deferred[key] = delivery.scope to (now + 5000)
                        delivering.remove(key)
                        Log.w("Arachne", "WORKSPACE_APPLICATION_PENDING topic=${current.getString("topic")} counter=${current.getLong("counter")} id=${current.getJSONArray("id")} retry=true")
                    }
                }
            }
            if (delivering.size >= 16 || delivering.size + deferred.size >= 64) return false
            val excluded = deferred.values.map { it.first } + delivering.values.map { it.scope }
            val request = JSONObject().put("op", "poll_pending_object").put("deferred", JSONArray(excluded))
            // Large recipient sets must backpressure locally, not exceed the
            // native command bound and close the workspace.
            if (request.toString().toByteArray(Charsets.UTF_8).size > 96 * 1024) return false
            val pending = call(request)
                ?: return delivering.isEmpty() && deferred.isEmpty()
            check(pending.getJSONArray("workspace").bytes().contentEquals(workspace))
            val scope = JSONObject().put("member", pending.getJSONArray("member"))
                .put("revision", pending.getLong("revision")).put("topic", pending.getString("topic"))
                .put("recipients", pending.getJSONArray("recipients"))
            val key = scope.toString()
            check(key !in delivering && key !in deferred) { "Runtime selected a deferred delivery stream" }
            val delivery = Delivery(pending, scope)
            delivering[key] = delivery
            val replied = java.util.concurrent.atomic.AtomicBoolean(false)
            try { consumer(pending) { accepted ->
                if (replied.compareAndSet(false, true)) delivery.completion.set(if (accepted) 1 else 2)
            } }
            catch (error: Exception) {
                // A thrown importer/storage error is not proof the publication
                // is invalid. Keep it retryable, just like a negative callback.
                if (replied.compareAndSet(false, true)) delivery.completion.set(2)
                Log.e("Arachne", "WORKSPACE_CONSUMER_FAILED", error)
            }
        }
        return false
    }

    fun tick() {
        drainPending()
        if (received != null) {
            for (ignored in 0 until 16) {
                val candidate = try { call(JSONObject().put("op", "poll_protected")) }
                catch (error: Exception) { Log.w("Arachne", "WORKSPACE_PUBLICATION_REJECTED"); null }
                if (candidate == null) break
                store.commitReception(candidate) { checkNotNull(call(it)) }
            }
            // Application retries cannot starve the durable receive/repair path.
            drainPending()
        }
        // Poll only completed network work. Offline subscription peers must not
        // occupy this worker while it needs to consume publications/responses.
        val interest = call(JSONObject().put("op", "poll_interest"))
        if (interest?.optString("state") == "interest_observed")
            Log.i("Arachne", "WORKSPACE_SUBSCRIPTION topic=${interest.getString("topic")} failed=${interest.getJSONObject("admission").getJSONArray("failed").length()}")
        else if (interest?.optString("state") == "interest_failed") Log.w("Arachne", "WORKSPACE_SUBSCRIPTION_RETRY")
        recover()
        val now = android.os.SystemClock.elapsedRealtime()
        startNextRecovery()
        scheduleReachableRecovery(deliveryClock())
        if (topics.isEmpty() || now < nextAnnouncement) return
        val topic = topics[nextTopic]
        try {
            val report = checkNotNull(call(JSONObject().put("op", "set_interest").put("workspace", workspace.json())
                .put("revision", revision).put("topic", topic).put("subscribed", true)))
            check(report.getString("state") == "interest_queued")
            if (installedInterests.add(topic)) {
                val reachable = reachablePeers()
                WorkspaceFeeds.authority(topic)?.let { authority ->
                    if (reachable.any { it.contentEquals(authority) }) pendingCurrentTopics.addLast(topic to authority)
                }
                if (topic in setOf(WorkspaceFeeds.CATALOG, WorkspaceResources.CATALOG, WorkspaceResources.CLAIMS)) {
                    reachable.forEach { pendingCurrentTopics.addLast(topic to it.copyOf()) }
                }
            }
        } catch (error: Exception) {
            Log.w("Arachne", "WORKSPACE_SUBSCRIPTION_RETRY topic=$topic")
            nextAnnouncement = now + 1000
            return
        }
        nextTopic++
        if (nextTopic == topics.size) {
            nextTopic = 0
            nextAnnouncement = Long.MAX_VALUE
        } else nextAnnouncement = now + 250
    }

    private fun startNextRecovery() {
        if (deliveryClock() < nextContinuityAttempt || currentRecoveryTopic != null || rangeRecoveryPending || directRecoveryTopic != null) return
        while (pendingCurrentTopics.isNotEmpty()) {
            val pending = pendingCurrentTopics.removeFirst()
            if (pending.first !in installedInterests) continue
            if (!discoverCutoff(null, pending.second.json(), pending.first)) {
                // Keep a busy or rate-limited request for its next bounded turn.
                pendingCurrentTopics.addLast(pending)
            }
            return
        }
        while (pendingReachableHistory.isNotEmpty()) {
            val peer = pendingReachableHistory.removeFirst()
            if (!discoverCutoff(peer.json())) pendingReachableHistory.addLast(peer)
            return
        }
        while (pendingReachableCurrent.isNotEmpty()) {
            val pending = pendingReachableCurrent.removeFirst()
            if (pending.first !in installedInterests) continue
            if (!discoverCutoff(null, pending.second.json(), pending.first)) pendingReachableCurrent.addLast(pending)
            return
        }
    }

    private fun scheduleReachableRecovery(now: Long) {
        if (now < nextReachableSweep || nextAnnouncement != Long.MAX_VALUE) return
        if (pendingCurrentTopics.isNotEmpty() || pendingReachableHistory.isNotEmpty() || pendingReachableCurrent.isNotEmpty() ||
            currentRecoveryTopic != null || rangeRecoveryPending || directRecoveryTopic != null) {
            nextReachableSweep = now + CONTINUITY_INTERVAL_MS
            return
        }
        val peers = reachablePeers().filter { it.size == 32 }
            .distinctBy { Hex.encode(it) }.sortedBy { Hex.encode(it) }
        if (peers.isEmpty()) {
            nextReachableSweep = now + 5_000L
            return
        }
        // ponytail: cap each sweep at 16 reachable members; tune from measured recovery latency at larger mesh sizes.
        val count = minOf(16, peers.size)
        val start = reachableSweepCursor % peers.size
        val selected = List(count) { peers[(start + it) % peers.size] }
        reachableSweepCursor = (start + count) % peers.size
        nextReachableSweep = now + CONTINUITY_INTERVAL_MS
        for (peer in selected) {
            if (retainedInterests().isNotEmpty()) pendingReachableHistory.addLast(peer.copyOf())
            for (topic in installedInterests) {
                if (topic in CotTopics.nativeCurrent ||
                    WorkspaceFeeds.authority(topic)?.contentEquals(peer) == true ||
                    topic in setOf(WorkspaceFeeds.CATALOG, WorkspaceResources.CATALOG, WorkspaceResources.CLAIMS))
                    pendingReachableCurrent.addLast(topic to peer.copyOf())
            }
        }
    }

    private fun JSONArray.bytes() = ByteArray(length()) { getInt(it).also { value -> require(value in 0..255) }.toByte() }
    private fun ByteArray.json() = JSONArray(map { it.toInt() and 255 })
}
