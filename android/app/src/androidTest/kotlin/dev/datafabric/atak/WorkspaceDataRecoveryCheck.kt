package dev.arachne.atak

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Controlled replies at WorkspaceData's existing runtime seam. This tests
 * cancellation sequencing, not Rust cryptography, storage or native ATAK UI. */
internal object WorkspaceDataRecoveryCheck {
    private const val CHAT = "chat/messages/v1"
    private const val NATIVE_CHAT = "atak/native/v1/chat"
    private const val PLI = "atak/native/v1/pli"
    private const val FEATURES = "atak/native/v1/features"
    private class Replies {
        val workspace = JSONArray(List(32) { 17 })
        var cutoffReady = false
        var cutoffFails = false
        var rangeActive = false
        var rangeReady = false
        var rangeUnavailable = false
        var fetched = 0
        var staged = 0
        var cancelled = 0
        var pending: JSONObject? = null
        val later = mutableListOf<JSONObject>()
        var now = 0L
        var retainedAfter = 0L
        var acceptedThrough = 0L
        var head = 1L
        var selectedTopics = listOf(CHAT)
        var fetchedTopics = emptyList<String>()
        var currentActive = false
        var currentBlocked = false
        var currentSourceWaiting = false
        var currentReady = false
        var currentFetched = 0
        val currentTopics = mutableListOf<String>()
        var currentStaged = 0
        var currentCancelled = 0
        var protectedPolls = 0
        var membershipPeerPolls = 0

        fun call(request: JSONObject): JSONObject? = when (request.getString("op")) {
            "endpoint_info" -> JSONObject().put("endpoint_key", JSONArray(List(32) { 19 }))
            "enable_object_delivery" -> JSONObject().put("state", "object_delivery_enabled")
            "install_workspace_policy" -> JSONObject().put("failed", JSONArray())
            "set_interest" -> JSONObject().put("state", "interest_queued").put("queued", 0)
            "poll_interest" -> null
            "member_roster" -> JSONObject().put("members", JSONArray().put(
                JSONObject().put("self", false).put("id", JSONArray(List(32) { 20 }))))
            "discover_recovery_cutoff" -> {
                selectedTopics = request.getJSONArray("topics").let { values ->
                    (0 until values.length()).map { values.getString(it) }
                }
                JSONObject().put("state", "recovery_cutoff_pending")
            }
            "poll_recovery_cutoff" -> if (cutoffFails) error("publisher offline") else if (!cutoffReady) null else {
                cutoffReady = false
                JSONObject().put("state", "recovery_cutoff_observed").put("accepted_progress", false)
                    .put("workspace", workspace).put("revision", 1).put("head", head)
                    .put("topics", JSONArray(selectedTopics))
                    .put("retained_after", retainedAfter).put("accepted_through", acceptedThrough)
                    .put("peer", JSONArray(List(32) { 18 })).put("author", JSONArray(List(32) { 20 }))
            }
            "fetch_recovery_range" -> {
                if (request.has("peer")) {
                    check(!request.has("author") && !request.has("after") && !request.has("through"))
                } else {
                    check((0 until 32).all { request.getJSONArray("author").getInt(it) == 20 })
                    check(request.getLong("after") == acceptedThrough)
                }
                fetchedTopics = request.getJSONArray("topics").let { values ->
                    (0 until values.length()).map { values.getString(it) }
                }
                fetched++; rangeActive = true
                JSONObject().put("state", "recovery_range_pending")
            }
            "cancel_recovery_range" -> {
                cancelled++; rangeActive = false; rangeReady = false
                JSONObject().put("state", "recovery_range_cancelled").put("accepted_progress", false)
            }
            "poll_recovery_range" -> when {
                rangeActive && rangeUnavailable -> {
                    rangeActive = false
                    JSONObject().put("state", "recovery_source_unavailable")
                        .put("reason", "no holder").put("automatic_source", true)
                }
                rangeActive && rangeReady -> JSONObject().put("state", "recovery_range_ready")
                    .put("after", 0).put("through", 1)
                else -> null
            }
            "stage_recovery_range" -> {
                staged++; rangeActive = false; rangeReady = false
                // No fake ciphertext or successful durable adoption is supplied.
                JSONObject().put("state", "recovery_no_new_objects")
            }
            "fetch_current_view" -> {
                check(!currentBlocked) { "continuity operation already pending" }
                check(!request.has("peer"))
                check((0 until 32).all { request.getJSONArray("authority").getInt(it) == 20 })
                currentTopics.add(request.getString("topic"))
                check(request.getJSONArray("selector").length() == 32)
                currentFetched++
                if (currentSourceWaiting) JSONObject().put("state", "current_view_source_waiting")
                else {
                    currentActive = true
                    JSONObject().put("state", "current_view_pending")
                }
            }
            "poll_current_view" -> if (currentActive && currentReady) {
                currentActive = false; currentReady = false
                JSONObject().put("state", "current_view_unavailable").put("accepted_progress", false)
            } else null
            "stage_current_view" -> {
                currentStaged++
                error("Unavailable current view must not be staged")
            }
            "cancel_current_view" -> {
                currentCancelled++; currentActive = false; currentReady = false
                JSONObject().put("state", "current_view_cancelled")
            }
            "poll_pending_object" -> {
                check(request.toString().toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "Pending query exceeded native command bound" }
                val deferred = request.getJSONArray("deferred")
                (listOfNotNull(pending) + later).firstOrNull { item ->
                    (0 until deferred.length()).none { index ->
                        val scope = deferred.getJSONObject(index)
                        listOf("member", "revision", "topic", "recipients").all {
                            scope.get(it).toString() == item.get(it).toString()
                        }
                    }
                }
            }
            "poll_protected" -> { protectedPolls++; null }
            "next_membership_peer" -> { membershipPeerPolls++; null }
            "poll_direct_recovery", "next_direct_gap" -> null
            else -> error("Unexpected runtime command: ${request.getString("op")}")
        }

        fun data(context: Context, topics: Set<String> = setOf(CHAT), consumer: (JSONObject, (Boolean) -> Unit) -> Unit = { _, _ -> }, continuity: (String?) -> Unit = {}) =
            WorkspaceData(WorkspaceStore(context), JSONObject().put("workspace", workspace).put("epoch", 0),
                topics, ::call, consumer, deliveryClock = { now }, continuity = continuity)

        fun publication(topic: String = CHAT, counter: Long = 7, member: Int = 20) =
            JSONObject().put("workspace", workspace).put("topic", topic).put("revision", 1)
                .put("member", JSONArray(List(32) { member })).put("counter", counter)
                .put("recipients", JSONArray()).put("id", JSONArray(List(16) { counter }))
                .put("payload", JSONArray(listOf(7)))
    }

    fun run(context: Context): JSONObject {
        val cases = JSONArray()
        fun scenario(name: String, checkCase: () -> Unit) {
            val result = runCatching(checkCase)
            cases.put(JSONObject().put("case", name).put("passed", result.isSuccess)
                .put("error", result.exceptionOrNull()?.message ?: JSONObject.NULL))
        }
        scenario("late_cutoff_does_not_fetch_after_unsubscribe") {
            val replies = Replies()
            val data = replies.data(context)
            data.discoverCutoff(JSONArray(List(32) { 18 }))
            data.interests(emptySet())
            replies.cutoffReady = true
            data.tick()
            check(replies.fetched == 0) { "Late cutoff fetched unsubscribed history" }
        }
        scenario("expired_history_is_not_silently_skipped") {
            val replies = Replies()
            val states = mutableListOf<String?>()
            replies.retainedAfter = 1
            replies.head = 2
            val data = replies.data(context, continuity = { states.add(it) })
            data.discoverCutoff(JSONArray(List(32) { 18 }))
            replies.cutoffReady = true
            data.tick()
            check(replies.fetched == 0) { "Recovery skipped an expired prefix" }
            check(states.last() == "Some earlier Chat updates have expired and are no longer available.")
        }
        scenario("gap_status_moves_from_checking_to_recovery") {
            val replies = Replies()
            val states = mutableListOf<String?>()
            val data = replies.data(context, continuity = { states.add(it) })
            data.discoverCutoff(JSONArray(List(32) { 18 }))
            replies.cutoffReady = true
            data.tick()
            check(states.take(2) == listOf("Checking for missed Chat updates…", "Missed Chat updates found. Recovering now…"))
        }
        scenario("offline_publisher_falls_back_to_connected_holders") {
            val replies = Replies()
            val states = mutableListOf<String?>()
            val data = replies.data(context, continuity = { states.add(it) })
            data.discoverCutoff(JSONArray(List(32) { 18 }))
            replies.cutoffFails = true
            data.tick()
            check(replies.fetched == 1 && replies.rangeActive)
            check(states.last() == "Checking connected members for missed Chat updates…")
        }
        scenario("unconfirmed_holder_miss_does_not_report_a_gap") {
            val replies = Replies()
            val states = mutableListOf<String?>()
            val data = replies.data(context, continuity = { states.add(it) })
            data.discoverCutoff(JSONArray(List(32) { 18 }))
            replies.cutoffFails = true
            data.tick()
            replies.rangeUnavailable = true
            data.tick()
            check(states.last() == null) { "Unconfirmed holder miss was shown as missing data" }
        }
        scenario("native_chat_uses_retained_recovery") {
            val replies = Replies()
            val data = replies.data(context, setOf(NATIVE_CHAT))
            data.discoverCutoff(JSONArray(List(32) { 18 }))
            replies.cutoffReady = true
            data.tick()
            check(replies.fetched == 1 && replies.fetchedTopics == listOf(NATIVE_CHAT))
        }
        scenario("native_chat_and_pli_both_get_recovery_turns") {
            val replies = Replies()
            val data = replies.data(context, setOf(NATIVE_CHAT, PLI))
            data.tick()
            android.os.SystemClock.sleep(300)
            data.tick()
            data.discoverCutoff(JSONArray(List(32) { 18 }), JSONArray(List(32) { 20 }))
            replies.cutoffReady = true
            data.tick()
            replies.rangeReady = true
            data.tick()
            data.discoverCutoff(JSONArray(List(32) { 18 }), JSONArray(List(32) { 20 }))
            check(replies.fetched == 1 && replies.currentFetched == 1 && replies.currentActive)
        }
        scenario("offline_current_source_yields_without_draining_the_roster") {
            val replies = Replies()
            replies.currentSourceWaiting = true
            val data = replies.data(context, setOf(PLI))
            check(!data.discoverCutoff(null, JSONArray(List(32) { 20 }), PLI))
            check(!data.discoverCutoff(null, JSONArray(List(32) { 20 }), PLI))
            check(replies.currentFetched == 1) { "Offline current source retried in the same interval" }
        }
        scenario("current_view_recovery_does_not_walk_offline_members") {
            val replies = Replies()
            replies.data(context, setOf(PLI)).tick()
            check(replies.membershipPeerPolls == 0) { "Recovery rotated through membership peers" }
        }
        for (ready in listOf(false, true)) scenario(if (ready) "ready_range_cancelled" else "inflight_range_cancelled") {
            val replies = Replies()
            val data = replies.data(context)
            data.discoverCutoff(JSONArray(List(32) { 18 }))
            replies.cutoffReady = true
            data.tick()
            check(replies.fetched == 1 && replies.rangeActive)
            replies.rangeReady = ready
            data.interests(emptySet())
            check(!replies.rangeActive && replies.cancelled == 1) { "Unsubscribe did not cancel automatic range" }
            replies.rangeReady = true // A late transport completion has no owned query.
            data.tick()
            check(replies.staged == 0) { "Cancelled range entered adoption staging" }
            replies.rangeReady = false
            data.interests(setOf(CHAT))
            data.discoverCutoff(JSONArray(List(32) { 18 }))
            replies.cutoffReady = true
            data.tick()
            check(replies.fetched == 2)
            replies.rangeReady = true
            data.tick()
            check(replies.staged == 1) { "Resubscribe did not restore automatic recovery" }
        }
        scenario("accepted_handoff_survives_unsubscribe") {
            val replies = Replies()
            replies.pending = replies.publication()
            var handedOff = 0
            var completion: ((Boolean) -> Unit)? = null
            val data = replies.data(context, consumer = { item, complete ->
                check(item === replies.pending)
                handedOff++; completion = complete
            })
            check(!data.drainPending())
            val accepted = completion
            data.interests(emptySet())
            check(!data.drainPending() && handedOff == 1 && completion === accepted && accepted != null)
            // The actual durable acknowledgement path is covered by store/JNI tests.
        }
        for (retry in listOf(false, true)) scenario(if (retry) "retrying_delivery_does_not_block_recovery" else "pending_delivery_does_not_block_recovery") {
            val replies = Replies()
            val publication = replies.publication()
            replies.pending = publication
            var deliveries = 0
            val data = replies.data(context, consumer = { _, complete ->
                deliveries++
                if (retry) complete(false)
            })
            data.discoverCutoff(JSONArray(List(32) { 18 }))
            replies.cutoffReady = true
            data.tick()
            data.tick()
            check(replies.protectedPolls >= 2) { "Application delivery blocked inbound polling" }
            check(replies.fetched == 1 && replies.rangeActive) { "Application delivery blocked retained recovery" }
            check(replies.pending === publication && deliveries == 1) { "Pending delivery was discarded or retried without backoff" }
        }
        for (failure in listOf("waiting", "retry", "exception")) scenario("${failure}_delivery_preserves_stream_order_and_allows_other_streams") {
            val replies = Replies()
            val blocked = replies.publication()
            val sameStream = replies.publication(counter = 8)
            val otherTopic = replies.publication(topic = NATIVE_CHAT)
            val otherAuthor = replies.publication(member = 21)
            val otherAudience = replies.publication().put("recipients", JSONArray().put(JSONArray(List(32) { 22 })))
            replies.pending = blocked
            replies.later.addAll(listOf(sameStream, otherTopic, otherAuthor, otherAudience))
            val delivered = mutableListOf<JSONObject>()
            val data = replies.data(context, consumer = { item, complete ->
                delivered.add(item)
                if (item === blocked) when (failure) {
                    "retry" -> complete(false)
                    "exception" -> error("Transient importer failure")
                }
            })
            check(!data.drainPending())
            check(delivered == listOf(blocked, otherTopic, otherAuthor, otherAudience)) { "Independent stream stalled or blocked stream overtaken" }
            check(!data.drainPending() && delivered.size == 4)
            replies.now = 5000
            check(!data.drainPending())
            if (failure == "waiting") check(delivered.size == 4) { "An in-flight import was duplicated" }
            else check(delivered == listOf(blocked, otherTopic, otherAuthor, otherAudience, blocked)) { "Retry did not preserve original identity and order" }
            check(replies.pending === blocked && sameStream !in delivered) { "Unaccepted publication was lost or overtaken" }
        }
        scenario("large_audiences_backpressure_without_oversize_command") {
            val replies = Replies()
            val audience = JSONArray((128 until 192).map { JSONArray(List(32) { _ -> it }) })
            replies.pending = replies.publication().put("recipients", audience)
            replies.later.addAll((21..60).map { replies.publication(member = it).put("recipients", audience) })
            var deliveries = 0
            val data = replies.data(context, consumer = { _, _ -> deliveries++ })
            check(!data.drainPending())
            val before = deliveries
            check(before in 2..15 && !data.drainPending() && deliveries == before)
        }
        scenario("unavailable_current_view_is_not_treated_as_empty") {
            val replies = Replies()
            val states = mutableListOf<String?>()
            val data = replies.data(context, setOf(PLI), continuity = { states.add(it) })
            data.tick() // Install local interest before requesting a snapshot.
            data.discoverCutoff(JSONArray(List(32) { 18 }), JSONArray(List(32) { 20 }))
            check(replies.currentFetched == 1 && replies.currentActive)
            replies.currentReady = true
            data.tick()
            check(!replies.currentActive && replies.currentStaged == 0)
            check(states.last() == "Current positions are unavailable from connected members.")
        }
        scenario("native_map_state_uses_current_view") {
            val replies = Replies()
            val states = mutableListOf<String?>()
            val data = replies.data(context, setOf(FEATURES), continuity = { states.add(it) })
            data.tick()
            data.discoverCutoff(JSONArray(List(32) { 18 }), JSONArray(List(32) { 20 }))
            replies.currentReady = true
            data.tick()
            check(replies.currentTopics == listOf(FEATURES))
            check(states.last() == "Current map data is unavailable from connected members.")
        }
        scenario("selected_feed_uses_payload_neutral_current_view") {
            val replies = Replies()
            val states = mutableListOf<String?>()
            val authority = "14".repeat(32)
            val feed = "feeds/$authority/${"34".repeat(16)}"
            val data = replies.data(context, setOf(PLI, feed), continuity = { states.add(it) })
            data.tick()
            android.os.SystemClock.sleep(300)
            data.tick()
            // Subscribing schedules the feed's own current view immediately.
            data.tick()
            check(replies.currentTopics == listOf(feed))
            replies.currentReady = true
            data.tick()
            check(WorkspaceFeeds.currentSelector(feed).size == 32)
            check(states.last() == "Current feed data is unavailable from connected members.")
            data.discoverCutoff(JSONArray(List(32) { 18 }), JSONArray(List(32) { 20 }))
            replies.currentReady = true
            data.tick()
            check(replies.currentTopics == listOf(feed, PLI))
            check(states.last() == "Current positions are unavailable from connected members.")
        }
        scenario("catalog_discovery_retries_a_busy_runtime") {
            val replies = Replies()
            val data = replies.data(context, setOf(WorkspaceResources.CATALOG))
            data.tick()
            replies.currentBlocked = true
            data.tick()
            check(replies.currentFetched == 0)
            replies.currentBlocked = false
            data.tick()
            check(replies.currentFetched == 0) { "Busy continuity failure retried immediately" }
            replies.now = 5000
            data.tick()
            check(replies.currentTopics == listOf(WorkspaceResources.CATALOG) && replies.currentActive)
        }
        scenario("feed_current_view_is_cancelled_on_unsubscribe") {
            val replies = Replies()
            val feed = "feeds/${"14".repeat(32)}/${"34".repeat(16)}"
            val data = replies.data(context, setOf(feed))
            data.tick()
            data.discoverCutoff(JSONArray(List(32) { 18 }), JSONArray(List(32) { 20 }))
            check(replies.currentActive)
            data.interests(emptySet())
            check(!replies.currentActive && replies.currentCancelled == 1)
        }
        return JSONObject().put("passed", (0 until cases.length()).all { cases.getJSONObject(it).getBoolean("passed") })
            .put("scope", "Controlled Kotlin recovery sequencing; no native or cryptographic acceptance")
            .put("cases", cases)
    }
}
