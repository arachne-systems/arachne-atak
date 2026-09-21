package dev.arachne.atak

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local transport credential only. The owning application's UID is the
 * security boundary; other code in ATAK's process is not isolated from this key.
 * Hold the file lock for the endpoint lifetime to prevent concurrent key reuse. */
internal class EndpointIdentity private constructor(
    private val file: RandomAccessFile,
    private val lock: FileLock,
    val secret: ByteArray,
    private val saved: AtomicFile,
    private val key: SecretKey
) : AutoCloseable {
    /** Caller holds this identity lease; clear the returned bytes after JNI. */
    fun storageSecret(): ByteArray {
        check(lock.isValid) { "Endpoint identity is closed" }
        return decrypt(saved, key)
    }

    override fun close() {
        secret.fill(0)
        try { lock.release() } finally { file.close() }
    }

    companion object {
        private const val SIZE = 1 + 12 + 32 + 16 // version, GCM IV, seed, tag
        private val AAD = "dev.datafabric.endpoint-storage/v1".toByteArray(Charsets.UTF_8)

        /** The debug fixture has no retained membership. An explicit caller may
         * replace its orphaned wrapping key after its ciphertext was removed. */
        fun repairMissingDebugIdentity(context: Context, name: String) {
            check(BuildConfig.DEBUG)
            require(name.matches(Regex("[a-z0-9-]{1,80}")))
            val base = File(File(context.noBackupFilesDir, "data-fabric"), "$name.bin")
            if (base.exists() || File(base.path + ".bak").exists()) return
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.deleteEntry("dev.datafabric.$name")
        }

        fun resetArachneKeys(preserve: String? = null) {
            preserve?.let { require(it.matches(Regex("[a-z0-9-]{1,80}"))) }
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val aliases = keys.aliases().toList()
            for (alias in aliases) if (alias.startsWith("dev.datafabric.") && alias != "dev.datafabric.$preserve") keys.deleteEntry(alias)
        }

        /** The local credential slot is explicit. Separate workspaces must use
         * different slots; the slot name is never a network identity. */
        fun open(context: Context, name: String): EndpointIdentity {
            require(name.matches(Regex("[a-z0-9-]{1,80}")))
            val directory = File(context.noBackupFilesDir, "data-fabric")
            check(directory.isDirectory || directory.mkdirs()) { "Identity storage unavailable" }
            val file = RandomAccessFile(File(directory, "$name.lock"), "rw")
            var lock: FileLock? = null
            var seed: ByteArray? = null
            try {
                lock = checkNotNull(file.channel.tryLock()) { "Endpoint identity already in use" }
                val base = File(directory, "$name.bin")
                val saved = AtomicFile(base)
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val alias = "dev.datafabric.$name"
                val exists = base.exists() || File(base.path + ".bak").exists()
                val hasKey = keys.containsAlias(alias)
                check(exists == hasKey) { "Saved endpoint identity is incomplete; recovery required" }
                val key = if (hasKey) {
                    keys.getKey(alias, null) as SecretKey
                } else {
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                        init(KeyGenParameterSpec.Builder(alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                    }.generateKey()
                }
                if (!exists) {
                    seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                    cipher.updateAAD(AAD)
                    check(cipher.iv.size == 12)
                    val record = byteArrayOf(1) + cipher.iv + cipher.doFinal(seed)
                    val output = saved.startWrite()
                    try {
                        output.write(record)
                        saved.finishWrite(output)
                    } catch (error: Exception) {
                        saved.failWrite(output)
                        throw error
                    }
                    check(read(saved).contentEquals(record)) { "Identity commit was not readable" }
                    seed.fill(0)
                }
                seed = decrypt(saved, key)
                return EndpointIdentity(file, lock, seed, saved, key)
            } catch (error: Exception) {
                seed?.fill(0)
                try { lock?.release() } finally { file.close() }
                throw error
            }
        }

        private fun decrypt(saved: AtomicFile, key: SecretKey): ByteArray {
            val record = read(saved)
            check(record[0] == 1.toByte()) { "Unsupported endpoint identity format" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record, 1, 12))
            cipher.updateAAD(AAD)
            return cipher.doFinal(record, 13, SIZE - 13).also { check(it.size == 32) }
        }

        private fun read(file: AtomicFile): ByteArray = DataInputStream(file.openRead()).use { input ->
            ByteArray(SIZE).also {
                input.readFully(it)
                check(input.read() == -1) { "Endpoint identity exceeds size limit" }
            }
        }
    }
}
