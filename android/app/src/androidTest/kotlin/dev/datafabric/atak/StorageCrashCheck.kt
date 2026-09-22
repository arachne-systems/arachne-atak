package dev.arachne.atak

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.system.exitProcess

/** Loaded with the plugin package as parent so Kotlin runtime types are shared. */
class StorageCrashCheck(private val context: Context) {
    private fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun ByteArray.json() = JSONArray(map { it.toInt() and 255 })
    private fun JSONArray.bytes() = ByteArray(length()) { getInt(it).also { n -> require(n in 0..255) }.toByte() }
    private fun ByteArray.hash() = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun runCheck(directory: File, phase: String, boundary: String): JSONObject {
        val packageContext = context.createPackageContext("dev.arachne.atak", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val loader = packageContext.classLoader
        val native = loader.loadClass("dev.arachne.atak.FabricNative")
        val instance = native.getField("INSTANCE").get(null)
        fun invoke(name: String, types: Array<Class<*>>, vararg values: Any): Any? = try {
            native.getMethod(name, *types).invoke(instance, *values)
        } catch (error: InvocationTargetException) { throw error.targetException }
        val info = context.packageManager.getApplicationInfo("dev.arachne.atak", 0)
        invoke("load", arrayOf(String::class.java), File(info.nativeLibraryDir, "libfabric_android.so").absolutePath)
        val rootFile = File(directory, "test-root.bin")
        if (phase == "setup") {
            check(!rootFile.exists())
            write(rootFile, ByteArray(32).also { SecureRandom().nextBytes(it) })
        }
        val root = rootFile.readBytes().also { check(it.size == 32) }
        val handle = try {
            invoke("create", arrayOf(ByteArray::class.java, Boolean::class.javaPrimitiveType!!), root, false) as Long
        }
            finally { root.fill(0) }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
        }
        val storeType = loader.loadClass("dev.arachne.atak.WorkspaceStore")
        val store = storeType.getConstructor(Context::class.java, Boolean::class.javaPrimitiveType!!).newInstance(isolated, false)
        fun load(id: ByteArray) = storeType.getMethod("load", ByteArray::class.java).invoke(store, id) as ByteArray
        fun save(id: ByteArray, bytes: ByteArray) { storeType.getMethod("save", ByteArray::class.java, ByteArray::class.java).invoke(store, id, bytes) }
        fun call(op: JSONObject, snapshot: ByteArray = ByteArray(0)): JSONObject {
            @Suppress("UNCHECKED_CAST")
            val response = invoke("executeStored", arrayOf(Long::class.javaPrimitiveType!!, ByteArray::class.java, ByteArray::class.java),
                handle, op.toString().toByteArray(), snapshot) as Array<ByteArray>
            check(response.size == 2)
            return JSONObject(String(response[0], Charsets.UTF_8)).also {
                if (response[1].isNotEmpty()) it.put("snapshot", response[1])
            }
        }
        fun stage(number: Int) = call(JSONObject().put("op", "stage_network_publication").put("revision", 1)
            .put("topic", "streams/crash-test").put("id", ByteArray(16) { number.toByte() }.json())
            .put("payload", ByteArray(12 * 1024) { 7 }.json()))
        fun killAtBoundary(bytes: Int): Nothing {
            write(File(directory, "crash.json"), JSONObject().put("passed", true)
                .put("boundary", boundary).put("pid", Process.myPid()).put("candidate_bytes", bytes)
                .put("state", "about_to_kill_process").toString().toByteArray())
            Process.killProcess(Process.myPid())
            exitProcess(77)
        }
        fun commit(staged: JSONObject, kill: Boolean = false): JSONObject {
            // Adapt the callback to the target package's Kotlin interface classloader.
            val callback: (JSONObject) -> JSONObject = { request ->
                if (kill) killAtBoundary((staged.get("snapshot") as ByteArray).size)
                val binary = request.getJSONArray("snapshot").bytes()
                request.remove("snapshot")
                call(request, binary)
            }
            return try {
                val functionType = loader.loadClass("kotlin.jvm.functions.Function1")
                val bridge = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(functionType)) { _, method, values ->
                    check(method.name == "invoke")
                    callback(checkNotNull(values)[0] as JSONObject)
                }
                storeType.getMethod("commitPublication", JSONObject::class.java, functionType)
                    .invoke(store, staged, bridge) as JSONObject
            } catch (error: InvocationTargetException) { throw error.targetException }
        }
        try {
            val idFile = File(directory, "workspace.bin")
            val id = if (phase == "setup") {
                val created = call(JSONObject().put("op", "create_workspace").put("display_name", "Storage test"))
                created.getJSONArray("workspace").bytes().also { write(idFile, it) }
            } else idFile.readBytes().also {
                call(JSONObject().put("op", "restore_workspace").put("workspace", it.json()), load(it))
            }
            call(JSONObject().put("op", "install_member_policy").put("revision", 1)
                .put("topics", JSONArray().put("streams/crash-test")))
            if (phase == "setup") {
                val sealed = call(JSONObject().put("op", "seal_workspace")).get("snapshot") as ByteArray
                save(id, sealed)
                repeat(12) { commit(stage(it + 1)) }
                val baseline = load(id)
                check(baseline.size > 128 * 1024 && baseline.take(5) == listOf<Byte>(68, 70, 87, 66, 1))
                write(File(directory, "expected-base.bin"), baseline)
                return JSONObject().put("pid", Process.myPid()).put("baseline_bytes", baseline.size)
                    .put("baseline_sha256", baseline.hash())
            }
            if (phase == "crash") {
                val staged = stage(13)
                val candidate = staged.get("snapshot") as ByteArray
                write(File(directory, "expected-candidate.bin"), candidate)
                when (boundary) {
                    "before_save" -> killAtBoundary(candidate.size)
                    "after_save" -> { commit(staged, kill = true); error("Crash callback returned") }
                    else -> {
                        // Same Android primitive/path as WorkspaceStore; no production
                        // fault hook. Only this mid-write case bypasses save's body.
                        val base = File(directory, "data-fabric/workspaces/" + id.joinToString("") { "%02x".format(it.toInt() and 255) } + ".bin")
                        val output = AtomicFile(base).startWrite()
                        output.write(candidate, 0, candidate.size / 2)
                        output.fd.sync()
                        killAtBoundary(candidate.size)
                    }
                }
            }
            val actual = load(id)
            val committed = boundary == "after_save"
            val expected = File(directory, if (committed) "expected-candidate.bin" else "expected-base.bin").readBytes()
            check(actual.contentEquals(expected)) { "Unexpected committed record after crash" }
            val duplicate = runCatching { stage(13) }
            if (committed) {
                check(duplicate.isFailure && duplicate.exceptionOrNull().toString().contains("publication already retained"))
            } else commit(duplicate.getOrThrow())
            commit(stage(14))
            return JSONObject().put("pid", Process.myPid()).put("boundary", boundary)
                .put("candidate_survived", committed).put("readback_sha256", actual.hash())
                .put("restored_bytes", actual.size).put("duplicate_rejected", duplicate.isFailure)
                .put("next_publication_committed", true)
        } finally { invoke("close", arrayOf(Long::class.javaPrimitiveType!!), handle) }
    }
}
