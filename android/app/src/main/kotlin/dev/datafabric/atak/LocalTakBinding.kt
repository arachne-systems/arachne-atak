package dev.arachne.atak

import android.content.Context
import java.security.SecureRandom

/** Saved, device-local native address. Contains no transport or group credentials. */
internal class LocalTakBinding private constructor(val host: String, val description: String,
    val port: Int, val httpPort: Int) {
    fun listeningOn(port: Int) = LocalTakBinding(host, description, port, httpPort)

    companion object {
        internal fun validHost(value: String): Boolean {
            val parts = value.split('.').map { it.toIntOrNull() }
            return parts.size == 4 && parts.all { it != null } && parts.joinToString(".") == value &&
                parts[0] == 127 && parts[1] in 64..191 && parts[2] in 0..255 && parts[3] in 1..254
        }

        /** Called on the local stream worker. A bad or conflicting saved binding
         * is an error; never silently repoint native references to another scope. */
        @Synchronized
        fun forWorkspace(context: Context, workspace: ByteArray, name: String): LocalTakBinding {
            require(workspace.size == 32 && name.isNotBlank() && name.codePointCount(0, name.length) <= 80)
            val key = workspace.joinToString("") { "%02x".format(it.toInt() and 255) }
            val ports = LocalTakPorts.activeChoices(context)
            val preferences = context.getSharedPreferences("arachne-native-bindings-v1", Context.MODE_PRIVATE)
            val saved = preferences.all
            check(saved.keys.all { it.matches(Regex("[a-f0-9]{64}")) } &&
                saved.values.all { it is String && validHost(it) } && saved.values.toSet().size == saved.size) {
                "Invalid saved native bindings"
            }
            (saved[key] as String?)?.let { return LocalTakBinding(it, "Arachne: $name", ports.stream, ports.https) }
            val random = SecureRandom()
            repeat(64) {
                val host = "127.${64 + random.nextInt(128)}.${random.nextInt(256)}.${1 + random.nextInt(254)}"
                if (host !in saved.values) {
                    try {
                        check(preferences.edit().putString(key, host).commit()) { "Could not save native binding" }
                    } catch (error: Exception) {
                        // A failed commit can still update SharedPreferences'
                        // memory cache. Do not reuse that unconfirmed address.
                        preferences.edit().remove(key).apply()
                        throw error
                    }
                    return LocalTakBinding(host, "Arachne: $name", ports.stream, ports.https)
                }
            }
            error("Could not allocate a distinct native binding")
        }
    }
}
