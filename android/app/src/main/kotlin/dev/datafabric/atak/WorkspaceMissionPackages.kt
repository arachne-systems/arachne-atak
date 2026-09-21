package dev.arachne.atak

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.atakmap.android.data.URIContentRecipient
import com.atakmap.android.data.URIContentSender
import com.atakmap.android.importexport.send.TAKContactSender
import com.atakmap.android.maps.MapView
import com.atakmap.android.missionpackage.MissionPackageMapComponent
import com.atakmap.android.missionpackage.MissionPackageReceiver
import com.atakmap.android.missionpackage.file.MissionPackageManifest
import com.atakmap.android.missionpackage.file.task.ExtractMissionPackageTask
import com.atakmap.android.missionpackage.file.task.MissionPackageBaseTask
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** ATAK's native URI sender and package importer around opaque workspace bytes. */
internal class WorkspaceMissionPackages(
    @Volatile private var record: LocalWorkspace,
    private val resources: WorkspaceResources,
    private val publish: WorkspacePublisher,
    private val resolveRecipients: (List<String>) -> List<ByteArray>,
) : AutoCloseable {
    private data class Pending(
        val uri: String,
        val offer: WorkspaceResources.DirectedOffer,
        val transfer: DirectedTransfer,
        val callback: URIContentSender.Callback?,
    )

    private val workspace = record.id.copyOf()
    private val member = checkNotNull(record.memberId).copyOf()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "arachne-mission-package") }
    private val pending = mutableMapOf<String, Pending>()
    private val inbound = linkedMapOf<String, Boolean?>()
    @Volatile private var contacts = emptyMap<String, WorkspaceMember>()
    @Volatile private var closed = false
    val sender = object : TAKContactSender(MapView.getMapView()) {
        override fun getName() = "Arachne: ${record.name}"
        override fun getIcon() = _context.getDrawable(android.R.drawable.ic_menu_share)

        override fun sendMissionPackage(manifest: MissionPackageManifest, mpCallback: MissionPackageBaseTask.Callback?, callback: URIContentSender.Callback?): Boolean {
            val uri = com.atakmap.android.data.URIHelper.getURI(manifest)
            selectRecipients(uri) { _, _, recipients ->
                if (!send(manifest, recipients, mpCallback, callback, uri)) finish(callback, uri, false)
            }
            return true
        }

        override fun sendMissionPackage(manifest: MissionPackageManifest, recipients: List<out URIContentRecipient>,
                                        mpCallback: MissionPackageBaseTask.Callback?, callback: URIContentSender.Callback?): Boolean =
            send(manifest, recipients, mpCallback, callback, com.atakmap.android.data.URIHelper.getURI(manifest))
    }

    fun update(record: LocalWorkspace) {
        require(record.id.contentEquals(workspace) && checkNotNull(record.memberId).contentEquals(member))
        this.record = record
    }

    fun members(values: List<WorkspaceMember>) {
        contacts = values.filter { !it.self && !it.service }.associateBy { hex(it.identity()) }
    }

    private fun send(manifest: MissionPackageManifest, recipients: List<out URIContentRecipient>?,
                     mpCallback: MissionPackageBaseTask.Callback?, callback: URIContentSender.Callback?, uri: String): Boolean {
        if (closed || recipients.isNullOrEmpty()) return false
        val selected = runCatching {
            val uids = recipients.map { requireNotNull(it.uid) }
            resolveRecipients(uids).map { requireNotNull(contacts[hex(it)]) }
        }.getOrElse { emptyList() }
        if (selected.size != recipients.size || selected.any { it.presence != "reachable" }) {
            toast("Choose reachable members from ${record.name} only.")
            return false
        }
        fun queue(file: File, temporary: Boolean) = worker.execute {
            try { publish(manifest.name, uri, file, selected, callback) }
            catch (error: Exception) {
                Log.w("Arachne", "MISSION_PACKAGE_SEND_FAILED", error)
                finish(callback, uri, false)
            } finally { if (temporary) file.delete() }
        }
        if (manifest.pathExists()) queue(File(manifest.path), false)
        else MissionPackageMapComponent.getInstance().fileIO.save(manifest, false) { task, success ->
            runCatching { mpCallback?.onMissionPackageTaskComplete(task, success) }
            if (success && manifest.pathExists()) queue(File(manifest.path), true) else finish(callback, uri, false)
        }
        return true
    }

    private fun publish(name: String, uri: String, file: File, recipients: List<WorkspaceMember>, callback: URIContentSender.Callback?) {
        check(!closed && file.isFile)
        DirectedTransfer.requireSize(file.length())
        val ids = recipients.map(WorkspaceMember::identity)
        val offer = resources.offer(file, ids)
        val id = UUID.randomUUID().toString().replace("-", "")
        try {
            DirectedTransfer.requireSize(offer.size)
            val state = Pending(uri, offer, DirectedTransfer(recipients.map { it.id }.toSet(), SystemClock.elapsedRealtime()), callback)
            synchronized(pending) { check(!closed && pending.size < 8); pending[id] = state }
            main.postDelayed({ checkPending(id) }, DirectedTransfer.REPORT_MS)
            val payload = JSONObject().put("v", 1).put("kind", "offer").put("id", id)
                .put("hash", offer.hash).put("grant", offer.grant).put("size", offer.size).put("name", name)
                .toString().toByteArray(Charsets.UTF_8)
            val accepted = publish(workspace, TOPIC, payload, ids, null) { result ->
                val complete = result.mapCatching(WorkspaceData::outcome).getOrNull()
                if (complete == null || complete.uncertain || complete.failed > 0 || complete.queued || complete.remoteAccepted != ids.size) fail(id)
            }
            if (!accepted) fail(id)
            else Log.i("Arachne", "MISSION_PACKAGE_OFFERED slot=${record.slot} id=$id recipients=${ids.size} bytes=${offer.size}")
        } catch (error: Exception) {
            synchronized(pending) { pending.remove(id) }
            runCatching { resources.withdraw(offer) }
            throw error
        }
    }

    fun receive(publication: JSONObject, complete: (Boolean) -> Unit) {
        var deferred = false
        try {
            require(!closed && publication.getString("topic") == TOPIC && publication.getJSONArray("workspace").bytes().contentEquals(workspace))
            val author = publication.getJSONArray("member").bytes()
            val authorId = hex(author)
            val roster = contacts.values
            require(authorId in roster.map { it.id })
            val audience = publication.getJSONArray("recipients").let { array -> (0 until array.length()).map { array.getJSONArray(it).bytes() } }
            require(audience.isNotEmpty() && audience.size <= 64 && audience.any { it.contentEquals(member) })
            val payload = publication.getJSONArray("payload")
            require(payload.length() in 1..4096)
            val value = JSONObject(String(payload.bytes(payload.length()), Charsets.UTF_8))
            require(value.get("v") == 1)
            val id = value.getString("id")
            require(ID.matches(id))
            when (value.getString("kind")) {
                "offer" -> {
                    deferred = true
                    receiveOffer(value, author, authorId, complete)
                }
                "result" -> receiveResult(value, authorId, audience)
                "active" -> {
                    require(value.keys().asSequence().toSet() == setOf("v", "kind", "id") && audience.size == 1)
                    val refreshed = synchronized(pending) { pending[id]?.transfer?.activity(authorId, SystemClock.elapsedRealtime()) == true }
                    if (refreshed) Log.i("Arachne", "MISSION_PACKAGE_ACTIVE id=$id recipient=$authorId")
                }
                else -> error("Unknown mission-package message")
            }
            if (!deferred) complete(true)
        } catch (error: Exception) {
            Log.w("Arachne", "MISSION_PACKAGE_MESSAGE_REJECTED", error)
            complete(false)
        }
    }

    private fun receiveOffer(value: JSONObject, author: ByteArray, authorId: String, complete: (Boolean) -> Unit) {
        require(value.keys().asSequence().toSet() == setOf("v", "kind", "id", "hash", "grant", "size", "name"))
        val id = value.getString("id")
        val hash = value.getString("hash")
        val grant = value.getString("grant")
        val size = value.get("size").let { require(it is Int || it is Long); (it as Number).toLong() }
        val name = value.getString("name").trim()
        require(HASH.matches(hash) && ID.matches(grant))
        DirectedTransfer.requireSize(size)
        require(name.codePointCount(0, name.length) in 1..80 && name.toByteArray(Charsets.UTF_8).size <= 320 &&
            name.codePoints().noneMatch(Character::isISOControl))
        val key = "$authorId:$id"
        var activeDuplicate = false
        val previous = synchronized(inbound) {
            if (!inbound.containsKey(key)) {
                if (inbound.size >= 32) {
                    val completed = inbound.entries.firstOrNull { it.value != null }
                    check(completed != null) { "Mission-package receive queue is full" }
                    inbound.remove(completed.key)
                }
                inbound[key] = null
                null
            } else {
                val existing = inbound[key]
                if (existing == null) activeDuplicate = true
                existing
            }
        }
        if (activeDuplicate) { complete(true); return }
        if (previous != null) { result(id, author, previous); complete(true); return }
        var nextActivity = 0L
        fun active() {
            val now = SystemClock.elapsedRealtime()
            val report = synchronized(inbound) {
                check(!closed && inbound.containsKey(key) && inbound[key] == null) { "Package transfer is no longer active" }
                if (now < nextActivity) false else { nextActivity = now + DirectedTransfer.REPORT_MS; true }
            }
            if (report) {
                val message = JSONObject().put("v", 1).put("kind", "active").put("id", id)
                    .toString().toByteArray(Charsets.UTF_8)
                publish(workspace, TOPIC, message, listOf(author), null) { }
            }
        }
        // A bounded queued job is still owned; another large package must not
        // exhaust its lease before it gets the serial receive worker.
        val queued = AtomicBoolean(true)
        fun pollQueued() {
            if (!queued.get() || !runCatching { active() }.isSuccess) return
            main.postDelayed({ pollQueued() }, DirectedTransfer.REPORT_MS)
        }
        main.post { pollQueued() }
        worker.execute {
            queued.set(false)
            val downloaded = try { active(); resources.fetch(WorkspaceResources.path(author, hash, grant), size, ::active) }
            catch (error: Exception) { Log.w("Arachne", "MISSION_PACKAGE_DOWNLOAD_FAILED id=$id", error); null }
            if (downloaded == null || downloaded.length() != size) { downloaded?.delete(); imported(key, id, author, false, complete); return@execute }
            // A directed grant is not permission to advertise or re-serve these
            // bytes to the workspace. Only public catalog downloads are replicas.
            val directory = File(MissionPackageMapComponent.getInstance().fileIO.missionPackagePath)
            if ((!directory.isDirectory && !directory.mkdirs())) { downloaded.delete(); imported(key, id, author, false, complete); return@execute }
            val destination = File(directory, "arachne-$id.zip")
            MissionPackageReceiver.addFileToSkip(destination)
            val copied = runCatching {
                check(!destination.exists())
                downloaded.inputStream().use { input -> ResourceFiles.copy(input, destination, size) { active(); !closed } }
                destination.length() == size
            }.getOrDefault(false)
            downloaded.delete()
            if (!copied) { destination.delete(); imported(key, id, author, false, complete); return@execute }
            main.post {
                if (closed) { destination.delete(); imported(key, id, author, false, complete); return@post }
                val task = ExtractMissionPackageTask(destination, MissionPackageMapComponent.getInstance().receiver) { task, success ->
                    val accepted = success && task.manifest?.isValid == true
                    if (!accepted) destination.delete()
                    imported(key, id, author, accepted, complete)
                    toast(if (accepted) "Received $name through ${record.name}." else "$name could not be imported.")
                }
                task.execute()
                // ATAK exposes task completion, not per-byte extraction progress.
                // No duration cap: native import may legitimately outlast transfer.
                fun pollImport() {
                    if (closed) { task.cancel(true); return }
                    if (synchronized(inbound) { inbound[key] != null }) return
                    if (task.status == android.os.AsyncTask.Status.FINISHED || task.isCancelled) {
                        imported(key, id, author, false, complete) // ATAK can finish without a manifest/callback.
                        return
                    }
                    if (!runCatching { active() }.isSuccess) { task.cancel(true); return }
                    main.postDelayed({ pollImport() }, DirectedTransfer.REPORT_MS)
                }
                pollImport()
            }
        }
        Log.i("Arachne", "MISSION_PACKAGE_ACCEPTED slot=${record.slot} id=$id author=$authorId bytes=$size")
    }

    private fun imported(key: String, id: String, author: ByteArray, success: Boolean,
                         complete: (Boolean) -> Unit) {
        val first = synchronized(inbound) {
            if (!inbound.containsKey(key) || inbound[key] != null) false else {
                inbound[key] = success
                if (!success) inbound.remove(key)
                while (inbound.size > 32) inbound.remove(inbound.keys.first())
                true
            }
        }
        if (first) {
            Log.i("Arachne", "MISSION_PACKAGE_IMPORTED slot=${record.slot} id=$id success=$success")
            result(id, author, success)
            complete(success)
        }
    }

    private fun result(id: String, author: ByteArray, success: Boolean) {
        if (closed) return
        val payload = JSONObject().put("v", 1).put("kind", "result").put("id", id).put("success", success)
            .toString().toByteArray(Charsets.UTF_8)
        if (!publish(workspace, TOPIC, payload, listOf(author), null) { outcome ->
            if (outcome.isFailure) Log.w("Arachne", "MISSION_PACKAGE_RESULT_UNCONFIRMED id=$id")
        }) Log.w("Arachne", "MISSION_PACKAGE_RESULT_NOT_QUEUED id=$id")
    }

    private fun receiveResult(value: JSONObject, author: String, audience: List<ByteArray>) {
        require(value.keys().asSequence().toSet() == setOf("v", "kind", "id", "success") && audience.size == 1 && audience.single().contentEquals(member))
        val id = value.getString("id")
        val success = value.getBoolean("success")
        val finished = synchronized(pending) {
            val state = pending[id] ?: return
            state.transfer.result(author, success)
            if (state.transfer.outcome != null) pending.remove(id) else null
        }
        if (finished != null) complete(finished, finished.transfer.outcome == true)
    }

    private fun checkPending(id: String) {
        val finished = synchronized(pending) {
            val state = pending[id] ?: return
            state.transfer.expire(SystemClock.elapsedRealtime())
            if (state.transfer.outcome != null) pending.remove(id) else null
        }
        if (finished != null) complete(finished, finished.transfer.outcome == true)
        else main.postDelayed({ checkPending(id) }, DirectedTransfer.REPORT_MS)
    }

    private fun fail(id: String) {
        val state = synchronized(pending) { pending.remove(id) } ?: return
        complete(state, false)
    }

    private fun complete(state: Pending, success: Boolean) {
        runCatching { resources.withdraw(state.offer) }
        finish(state.callback, state.uri, success)
        Log.i("Arachne", "MISSION_PACKAGE_COMPLETED slot=${record.slot} success=$success")
    }

    private fun finish(callback: URIContentSender.Callback?, uri: String, success: Boolean) {
        if (callback != null) main.post { callback.onSentContent(sender, uri, success) }
    }

    private fun toast(message: String) = main.post {
        if (!closed) Toast.makeText(MapView.getMapView().context, message, Toast.LENGTH_LONG).show()
    }

    override fun close() {
        closed = true
        val unfinished = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        unfinished.forEach { complete(it, false) }
        synchronized(inbound) { inbound.clear() }
        worker.shutdownNow()
    }

    companion object {
        const val TOPIC = "atak/mission-packages/v1"
        val topics = setOf(TOPIC)
        private val ID = Regex("[a-f0-9]{32}")
        private val HASH = Regex("[a-f0-9]{64}")
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun JSONArray.bytes(size: Int = length()): ByteArray {
            require(length() == size)
            return ByteArray(size) { getInt(it).also { n -> require(n in 0..255) }.toByte() }
        }
    }
}
