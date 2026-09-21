package dev.arachne.atak

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A bounded third advertiser for the real three-tablet selection check. */
internal object NearbyAdvertisementCheck {
    fun run(context: Context): JSONObject {
        check(context.packageName == "dev.arachne.atak") // Never run in the ATAK host's storage/keystore.
        val root = File(context.noBackupFilesDir, "nearby-check/${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = root.apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("${root.name}-$name", mode)
        }
        val views = LinkedBlockingQueue<WorkspaceView>()
        val owner = WorkspaceController(isolated) { views.offer(it) }
        EndpointIdentity.repairMissingDebugIdentity(isolated, "nearby-invitations-v1")
        val nearby = NearbyInvitations(isolated, "Test advertiser") {}
        fun waitFor(predicate: (WorkspaceView) -> Boolean): WorkspaceView {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (System.nanoTime() < end) {
                val view = views.poll(1, TimeUnit.SECONDS) ?: continue
                check(!view.attention) { view.message }
                if (!view.busy && predicate(view)) return view
            }
            error("Third advertiser deadline")
        }
        try {
            waitFor { it.saved.isEmpty() }
            check(owner.create("Medical Check", "Test organizer", false))
            waitFor { it.active != null }
            check(owner.invite())
            val invitation = checkNotNull(waitFor { it.invitation != null }.invitation)
            val results = LinkedBlockingQueue<Result<Unit>>()
            check(nearby.advertise(NearbyJoinMode.OPEN_JOINING, invitation, "Medical Check") { results.offer(it) })
            checkNotNull(results.poll(15, TimeUnit.SECONDS)).getOrThrow()
            android.util.Log.i("Arachne", "THIRD_ADVERTISER_READY Medical Check")
            // The operator selects and verifies this invitation from another tablet.
            Thread.sleep(300_000)
            return JSONObject().put("advertised", true).put("duration_seconds", 300)
                .put("scope", "Third-advertiser fixture; receiver UI proof is recorded separately")
        } finally {
            nearby.close(); owner.close()
            check(owner.awaitClosed(20, TimeUnit.SECONDS))
            root.deleteRecursively()
        }
    }
}
