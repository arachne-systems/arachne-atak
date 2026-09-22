package dev.arachne.atak

import android.content.Context

/** Debug-only reverse-rig node. It has its own persistent app-owned identity
 * and carries no workspace data. This class is absent from release APKs. */
internal class DebugNative(context: Context) : AutoCloseable {
    private val bridge = NativeAccess(context)
    private val identity: EndpointIdentity
    private val handle: Long

    init {
        identity = EndpointIdentity.open(context, "debug-rig")
        try { handle = bridge.create(identity.secret, false, true) }
        catch (error: Throwable) {
            identity.close()
            throw error
        } finally { identity.secret.fill(0) }
    }

    fun execute(request: ByteArray): ByteArray = bridge.execute(handle, request)

    override fun close() {
        try { bridge.close(handle) } finally { identity.close() }
    }
}
