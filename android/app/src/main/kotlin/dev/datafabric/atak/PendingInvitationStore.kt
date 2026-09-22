package dev.arachne.atak

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts an unverified bearer until an authorized member can supply the
 * checkpoint. The catalog contains only route hints and an untrusted ID. */
internal class PendingInvitationStore(private val context: Context, private val slot: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir,
        "data-fabric/pending-invitations/$slot.bin"))
    private val aad = "dev.datafabric.pending-invitation/v1/$slot".toByteArray(Charsets.UTF_8)

    fun save(link: String, memberName: String, sharing: Boolean) {
        val value = JSONObject().put("version", 1).put("link", link)
            .put("member_name", checkedMemberName(memberName)).put("sharing", sharing)
        val plain = value.toString().toByteArray(Charsets.UTF_8)
        require(plain.size in 1..4096)
        val record = crypt(Cipher.ENCRYPT_MODE) { cipher -> byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) }
        check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val output = file.startWrite()
        try { output.write(record); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        check(load().let { it.first == link && it.second == checkedMemberName(memberName) && it.third == sharing })
    }

    fun load(): Triple<String, String, Boolean> {
        val record = DataInputStream(file.openRead()).use { input ->
            val bytes = ByteArray(8193)
            var size = 0
            while (size < bytes.size) { val read = input.read(bytes, size, bytes.size - size); if (read < 0) break; size += read }
            check(size in 30..8192)
            bytes.copyOf(size)
        }
        check(record[0] == 1.toByte())
        val plain = crypt(Cipher.DECRYPT_MODE, record.copyOfRange(1, 13)) { cipher ->
            cipher.doFinal(record, 13, record.size - 13)
        }
        val value = JSONObject(String(plain, Charsets.UTF_8))
        check(value.length() == 4 && value.getInt("version") == 1)
        val link = value.getString("link")
        WorkspaceInvitation.decode(link)
        return Triple(link, checkedMemberName(value.getString("member_name")), value.getBoolean("sharing"))
    }

    fun delete() { file.delete() }

    private fun <T> crypt(mode: Int, iv: ByteArray? = null, action: (Cipher) -> T): T =
        EndpointIdentity.open(context, slot).use { identity ->
            val secret = identity.storageSecret()
            try {
                val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(aad + secret), "AES")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                if (iv == null) cipher.init(mode, key) else cipher.init(mode, key, GCMParameterSpec(128, iv))
                cipher.updateAAD(aad)
                action(cipher)
            } finally { secret.fill(0) }
        }
}
