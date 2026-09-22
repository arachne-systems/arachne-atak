package dev.arachne.atak

import android.content.Context
import android.os.Looper
import android.util.AtomicFile
import com.atakmap.net.AtakAuthenticationCredentials as Credentials
import com.atakmap.net.AtakAuthenticationDatabase as Passwords
import com.atakmap.net.AtakCertificateDatabase as Certificates
import com.atakmap.net.AtakCertificateDatabaseIFace as Types
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest

/** Owns only the native HTTP credential rows for one local binding.
 * Hold for the listener lifetime. Close the listener before closing this lease.
 * The private journal contains fingerprints, never passwords or private keys.
 * Other code in ATAK's UID is trusted; native APIs provide no cross-plugin CAS. */
internal class NativeHttpCredentials private constructor(
    private val host: String, private val file: RandomAccessFile,
    private val lock: FileLock, private val journal: AtomicFile
) : AutoCloseable {
    private var closed = false

    private fun read(): JSONObject? {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return null
        val record = journal.openRead().use { input ->
            check(input.channel.size() in 1..4096) { "Invalid native credential journal size" }
            JSONObject(input.readBytes().toString(Charsets.UTF_8))
        }
        check(record.keys().asSequence().toSet() == setOf("version", "host", "port", "client", "ca", "password"))
        check(record.getInt("version") == 1 && record.getString("host") == host && record.getInt("port") in 1..65535)
        check(listOf("client", "ca", "password").all { record.getString(it).matches(Regex("[a-f0-9]{64}")) })
        return record
    }

    private fun save(record: JSONObject) {
        val output = journal.startWrite()
        try {
            output.write(record.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
            journal.finishWrite(output)
        } catch (error: Exception) { journal.failWrite(output); throw error }
        check(read().toString() == record.toString()) { "Native credential ownership was not saved" }
    }

    private fun certificate(type: String, port: Int): ByteArray? =
        // The three-argument getter falls back to an arbitrary host row. The
        // explicit overload reads this port only; false also exposes bad-hash
        // records so they cannot be mistaken for an unoccupied slot.
        Certificates.getAdapter().getCertificateForTypeAndServerAndPort(type, host, port, false)

    private fun count(type: String): Int = checkNotNull(Certificates.getAdapter().getServers(type)) {
        "Native certificate database unavailable"
    }.count { it == host }

    private fun password(type: String): String? = Passwords.getCredentials(type, host)?.let {
        fingerprint((it.username + "\u0000" + it.password).toByteArray(Charsets.UTF_8))
    }

    private fun clean() {
        val record = read() ?: return
        val port = record.getInt("port")
        checkNotNull(Passwords.getDistinctSitesAndTypes()) { "Native password database unavailable" }
        // Validate the whole host before changing anything. A host-scoped
        // password may also be used by an unexpected certificate on another
        // port, so retain all rows and the journal on any ownership conflict.
        for ((type, key) in certTypes) {
            val value = certificate(type, port)
            check(count(type) == if (value == null) 0 else 1) { "Native certificate ownership conflict" }
            check(value == null || fingerprint(value) == record.getString(key)) { "Native certificate changed" }
        }
        for (type in passwordTypes) check(password(type).let { it == null || it == record.getString("password") }) {
            "Native password ownership conflict"
        }
        for ((type, _) in certTypes) {
            if (certificate(type, port) != null) Certificates.deleteCertificateForServerAndPort(type, host, port)
            check(certificate(type, port) == null) { "Native certificate cleanup failed" }
        }
        for (type in passwordTypes) {
            if (password(type) != null) Passwords.delete(type, host)
            check(password(type) == null) { "Native password cleanup failed" }
        }
        check(certTypes.all { count(it.first) == 0 }) { "Native certificate cleanup was not confirmed" }
        checkNotNull(Passwords.getDistinctSitesAndTypes()) { "Native password database unavailable" }
        com.atakmap.net.CertificateManager.invalidate(host)
        journal.delete()
        check(read() == null) { "Native credential journal cleanup failed" }
    }

    @Synchronized override fun close() {
        if (closed) return
        check(Looper.myLooper() != Looper.getMainLooper())
        closed = true
        synchronized(Companion) {
            try { clean() } finally { try { lock.release() } finally { file.close() } }
        }
    }

    companion object {
        private val certTypes = listOf(Types.TYPE_CLIENT_CERTIFICATE to "client", Types.TYPE_TRUST_STORE_CA to "ca")
        private val passwordTypes = listOf(Credentials.TYPE_clientPassword, Credentials.TYPE_caPassword)
        private fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun directory(context: Context): File = File(context.noBackupFilesDir, "data-fabric/native-http").also {
            check(it.isDirectory || it.mkdirs()) { "Native credential storage unavailable" }
        }

        private fun lease(context: Context, host: String): NativeHttpCredentials? {
            check(Looper.myLooper() != Looper.getMainLooper())
            require(LocalTakBinding.validHost(host))
            val directory = directory(context)
            val file = RandomAccessFile(File(directory, "$host.lock"), "rw")
            try {
                val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) { file.close(); return null }
                return NativeHttpCredentials(host, file, lock, AtomicFile(File(directory, "$host.json")))
            } catch (error: Exception) { file.close(); throw error }
        }

        // Serialize native database ownership transitions, including startup
        // recovery and teardown. A live listener still fails the file lease.
        @Synchronized fun open(context: Context, host: String, port: Int, client: ByteArray, ca: ByteArray, password: String): NativeHttpCredentials {
            require(port in 1..65535 && client.size in 1..16384 && ca.size in 1..16384 && password.length in 32..256)
            val owner = checkNotNull(lease(context, host)) { "Native HTTP binding already in use" }
            try {
                owner.clean()
                checkNotNull(Passwords.getDistinctSitesAndTypes()) { "Native password database unavailable" }
                check(certTypes.all { owner.count(it.first) == 0 } && passwordTypes.all { owner.password(it) == null }) {
                    "Native HTTP host already has credentials"
                }
                owner.save(JSONObject().put("version", 1).put("host", host).put("port", port)
                    .put("client", fingerprint(client)).put("ca", fingerprint(ca))
                    .put("password", fingerprint(("\u0000" + password).toByteArray(Charsets.UTF_8))))
                for (type in passwordTypes) {
                    check(owner.password(type) == null)
                    Passwords.saveCredentials(type, host, "", password, false)
                    check(owner.password(type) == owner.read()!!.getString("password")) { "Native password write failed" }
                }
                for ((type, bytes) in listOf(Types.TYPE_CLIENT_CERTIFICATE to client, Types.TYPE_TRUST_STORE_CA to ca)) {
                    check(owner.count(type) == 0)
                    // Host-only reads select this sole row. Using an exact port
                    // lets cleanup avoid the native host-wide delete operation.
                    Certificates.saveCertificateForServerAndPort(type, host, port, bytes)
                    check(owner.certificate(type, port).contentEquals(bytes) &&
                        Certificates.getCertificateForServerAndPort(type, host, port).contentEquals(bytes)) {
                        "Native certificate write failed"
                    }
                }
                return owner
            } catch (error: Exception) {
                try { owner.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                throw error
            }
        }

        /** Called on plugin startup and again when opening a binding. Live
         * leases are skipped; one damaged journal never halts other recovery. */
        @Synchronized fun recover(context: Context): Int {
            var failures = 0
            val hosts = checkNotNull(directory(context).listFiles()).filter {
                it.name.endsWith(".json") || it.name.endsWith(".json.bak")
            }.map { it.name.substringBefore(".json") }.toSet()
            for (host in hosts) try { lease(context, host)?.close() }
            catch (error: Exception) {
                failures++
                android.util.Log.w("Arachne", "NATIVE_HTTP_RECOVERY_FAILED host=$host", error)
            }
            return failures
        }
    }
}
