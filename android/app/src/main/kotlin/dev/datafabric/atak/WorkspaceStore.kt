package dev.arachne.atak

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Stores ciphertext only. Caller holds the session's exclusive credential lock.
 * Filename selects expected workspace scope; Rust authenticates the contents.
 * Atomic replacement does not provide rollback protection or backup recovery. */
internal class WorkspaceStore(context: Context, private val pendingJoin: Boolean = false) {
    private val directory = File(context.noBackupFilesDir, if (pendingJoin) "data-fabric/pending-joins" else "data-fabric/workspaces")

    private fun file(workspace: ByteArray): AtomicFile {
        require(workspace.size == 32)
        check(directory.isDirectory || directory.mkdirs()) { "Workspace storage unavailable" }
        val name = workspace.joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(directory, "$name.bin"))
    }

    private fun database(workspace: ByteArray): File = file(workspace).baseFile.let { File(it.parentFile, it.nameWithoutExtension + ".db") }
    private fun backend(workspace: ByteArray): AtomicFile = AtomicFile(database(workspace).let { File(it.parentFile, it.nameWithoutExtension + ".backend") })
    private fun markerExists(marker: AtomicFile): Boolean = marker.baseFile.exists() || File(marker.baseFile.path + ".bak").exists()

    fun usesRecords(workspace: ByteArray): Boolean {
        check(!pendingJoin)
        return database(workspace).exists() || markerExists(backend(workspace))
    }

    private fun markRecords(workspace: ByteArray) {
        val marker = backend(workspace)
        val output = marker.startWrite()
        try { output.write(RECORD_BACKEND); marker.finishWrite(output) }
        catch (error: Exception) { marker.failWrite(output); throw error }
        checkBackend(workspace)
    }

    private fun checkBackend(workspace: ByteArray) {
        val marker = backend(workspace)
        if (markerExists(marker)) java.io.DataInputStream(marker.openRead()).use { input ->
            val value = ByteArray(RECORD_BACKEND.size)
            input.readFully(value)
            check(value.contentEquals(RECORD_BACKEND) && input.read() == -1) { "Unknown storage backend" }
        }
    }

    /** DB presence covers a crash before marker save. A surviving marker must
     * never permit fallback if the authoritative DB later disappears. */
    fun enableRecords(workspace: ByteArray, call: (org.json.JSONObject) -> org.json.JSONObject) {
        check(!pendingJoin && !usesRecords(workspace)) { "Restore the existing native store" }
        check(call(org.json.JSONObject().put("op", "enable_record_storage").put("path", database(workspace).absolutePath)).getBoolean("durable"))
        markRecords(workspace)
    }

    fun restoreRecords(workspace: ByteArray, call: (org.json.JSONObject) -> org.json.JSONObject): org.json.JSONObject {
        check(!pendingJoin && usesRecords(workspace))
        checkBackend(workspace)
        val restored = call(org.json.JSONObject().put("op", "restore_record_storage")
            .put("path", database(workspace).absolutePath)
            .put("workspace", org.json.JSONArray(workspace.map { it.toInt() and 255 })))
        markRecords(workspace)
        return restored
    }

    fun save(workspace: ByteArray, sealed: ByteArray) {
        require(sealed.size in 65..if (pendingJoin) LEGACY_MAX_BYTES else MAX_BYTES)
        val marker = sealed.copyOfRange(0, 5)
        val validPhase = if (pendingJoin) (marker.contentEquals(byteArrayOf(68, 70, 80, 74, 1)) || marker.contentEquals(byteArrayOf(68, 70, 80, 74, 2)))
            else marker.contentEquals(byteArrayOf(68, 70, 87, 83, 1)) || marker.contentEquals(byteArrayOf(68, 70, 87, 83, 2)) || marker.contentEquals(byteArrayOf(68, 70, 87, 83, 3)) || marker.contentEquals(byteArrayOf(68, 70, 87, 83, 4))
        val bundle = marker.contentEquals(byteArrayOf(68, 70, 87, 66, 1))
        val removed = marker.contentEquals(byteArrayOf(68, 70, 82, 77, 1))
        require(validPhase || (!pendingJoin && (bundle || removed))) { "Snapshot belongs to another lifecycle phase" }
        require(sealed.copyOfRange(5, 37).contentEquals(workspace)) { "Snapshot belongs to another workspace" }
        val target = file(workspace)
        val output = target.startWrite()
        try {
            output.write(sealed)
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
        check(load(workspace).contentEquals(sealed)) { "Workspace commit was not readable" }
    }

    /** Invoke on a coordinator worker holding exclusive session ownership.
     * On any error close the session and restore disk state before further work. */
    fun commitAdmission(staged: org.json.JSONObject, call: (org.json.JSONObject) -> org.json.JSONObject): org.json.JSONObject {
        return commitCandidate(staged, "awaiting_save", "adopt_admission", call)
    }

    fun commitPublication(staged: org.json.JSONObject, call: (org.json.JSONObject) -> org.json.JSONObject): org.json.JSONObject =
        commitCandidate(staged, "awaiting_publication_save", "adopt_publication", call)

    fun commitReception(staged: org.json.JSONObject, call: (org.json.JSONObject) -> org.json.JSONObject): org.json.JSONObject =
        commitCandidate(staged, "awaiting_reception_save", "adopt_reception", call)

    fun commitRecovery(staged: org.json.JSONObject, call: (org.json.JSONObject) -> org.json.JSONObject): org.json.JSONObject =
        commitCandidate(staged, "awaiting_recovery_save", "adopt_recovery", call)

    fun commitCurrent(staged: org.json.JSONObject, call: (org.json.JSONObject) -> org.json.JSONObject): org.json.JSONObject =
        commitCandidate(staged, "awaiting_current_view_save", "adopt_current_view", call)

    fun commitJoin(staged: org.json.JSONObject, pending: WorkspaceStore, call: (org.json.JSONObject) -> org.json.JSONObject): org.json.JSONObject {
        check(!pendingJoin && pending.pendingJoin)
        val adopted = commitCandidate(staged, "awaiting_join_save", "adopt_join", call)
        val id = staged.getJSONArray("workspace")
        pending.file(ByteArray(id.length()) { id.getInt(it).toByte() }).delete()
        return adopted
    }

    private fun commitCandidate(staged: org.json.JSONObject, expected: String, operation: String, call: (org.json.JSONObject) -> org.json.JSONObject): org.json.JSONObject {
        check(!pendingJoin && staged.getString("state") == expected)
        fun org.json.JSONArray.bytes() = ByteArray(length()) { getInt(it).also { n -> require(n in 0..255) }.toByte() }
        val workspace = staged.getJSONArray("workspace").bytes()
        val snapshot = when (val value = staged.get("snapshot")) {
            is ByteArray -> value
            is org.json.JSONArray -> value.bytes() // Existing instrumentation/legacy callers.
            else -> error("Invalid snapshot value")
        }
        if (snapshot.take(5) == listOf<Byte>(68, 70, 82, 67, 1)) {
            check(snapshot.size == 37 && usesRecords(workspace)) { "Native candidate requires native storage" }
            check(call(org.json.JSONObject().put("op", "save_candidate").put("snapshot", snapshot)).getBoolean("durable"))
            return call(org.json.JSONObject().put("op", operation).put("snapshot", snapshot))
        }
        check(!usesRecords(workspace)) { "Cannot overwrite native storage with a legacy snapshot" }
        val bundle = snapshot.take(5) == listOf<Byte>(68, 70, 87, 66, 1)
        val removed = snapshot.take(5) == listOf<Byte>(68, 70, 82, 77, 1)
        require(bundle || removed || snapshot.take(4) == listOf<Byte>(68, 70, 87, 83))
        require(if (removed) operation == "adopt_admission" else if (bundle) operation in listOf("adopt_publication", "adopt_reception", "adopt_recovery", "adopt_current_view", "adopt_admission") else when (operation) {
            "adopt_recovery" -> false
            "adopt_join" -> snapshot.getOrNull(4) == 4.toByte()
            "adopt_admission" -> snapshot.getOrNull(4) in listOf<Byte>(3, 4)
            else -> snapshot.getOrNull(4) in listOf<Byte>(2, 3, 4)
        })
        save(workspace, snapshot)
        // Pass the disk readback, not merely the candidate originally returned.
        val persisted = load(workspace)
        check(persisted.contentEquals(snapshot))
        return call(org.json.JSONObject().put("op", operation)
            .put("snapshot", org.json.JSONArray(persisted.map { it.toInt() and 255 })))
    }

    fun exists(workspace: ByteArray): Boolean {
        val base = file(workspace).baseFile
        return base.exists() || File(base.path + ".bak").exists()
    }

    fun retire(workspace: ByteArray) {
        check(pendingJoin)
        file(workspace).delete()
    }

    fun discardPendingJoin(workspace: ByteArray) {
        check(!pendingJoin)
        file(workspace).delete()
        backend(workspace).delete()
        val database = database(workspace)
        listOf(database, File(database.path + "-wal"), File(database.path + "-shm"), File(database.path + "-journal")).forEach {
            check(!it.exists() || it.delete()) { "Pending join storage could not be removed" }
        }
        check(!exists(workspace) && !usesRecords(workspace))
    }

    fun load(workspace: ByteArray): ByteArray = file(workspace).openRead().use { input ->
        val buffer = ByteArray(MAX_BYTES + 1)
        var length = 0
        while (length < buffer.size) {
            val count = input.read(buffer, length, buffer.size - length)
            if (count == -1) break
            length += count
        }
        check(length in 65..MAX_BYTES) { "Workspace snapshot exceeds bounds or is truncated" }
        buffer.copyOf(length)
    }

    companion object {
        private val RECORD_BACKEND = "records-v1\n".toByteArray(Charsets.UTF_8)
        const val LEGACY_MAX_BYTES = 5 + 32 + 12 + 128 * 1024 + 16
        const val MAX_BYTES = 49 + 16 + 8 + LEGACY_MAX_BYTES + 528 * 1024
    }
}
