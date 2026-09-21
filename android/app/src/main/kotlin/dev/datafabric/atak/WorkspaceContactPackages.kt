package dev.arachne.atak

import android.widget.Toast
import com.atakmap.android.data.URIContentManager
import com.atakmap.android.data.URIContentRecipient
import com.atakmap.android.data.URIContentSender
import com.atakmap.android.data.URIHelper
import com.atakmap.android.importexport.send.TAKContactSender
import com.atakmap.android.maps.MapView
import com.atakmap.android.missionpackage.file.MissionPackageManifest
import com.atakmap.android.missionpackage.file.task.MissionPackageBaseTask

/** Keeps native Contacts selection, then uses the selected workspace's package
 * transport for its contacts. Ordinary ATAK recipients retain their native route. */
internal class WorkspaceContactPackages(
    private val candidates: (List<String>) -> List<WorkspaceMissionPackages>
) : AutoCloseable {
    private val manager = URIContentManager.getInstance()
    private val original = manager.senders.singleOrNull { it.javaClass == TAKContactSender::class.java }
    private val map = MapView.getMapView()
    private var closed = false
    private val sender = object : TAKContactSender(map) {
        override fun sendMissionPackage(manifest: MissionPackageManifest, mp: MissionPackageBaseTask.Callback?,
                                        callback: URIContentSender.Callback?): Boolean {
            val uri = URIHelper.getURI(manifest)
            selectRecipients(uri) { _, _, recipients ->
                if (!sendMissionPackage(manifest, recipients, mp, callback)) callback?.onSentContent(this, uri, false)
            }
            return true
        }

        override fun sendMissionPackage(manifest: MissionPackageManifest, recipients: List<out URIContentRecipient>,
                                        mp: MissionPackageBaseTask.Callback?, callback: URIContentSender.Callback?): Boolean {
            if (closed || recipients.isEmpty()) return false
            val uids = recipients.map { it.uid }
            val routes = candidates(uids)
            if (routes.size == 1) return routes.single().sender.sendMissionPackage(manifest, recipients, mp, callback)
            if (routes.isEmpty()) {
                if (uids.none { candidates(listOf(it)).isNotEmpty() })
                    return super.sendMissionPackage(manifest, recipients, mp, callback)
                Toast.makeText(map.context, "Choose recipients from one Arachne workspace for this package.", Toast.LENGTH_LONG).show()
                return false
            }
            // Several shared workspaces need an explicit audience decision.
            android.app.AlertDialog.Builder(map.context).setTitle("Send package through workspace")
                .setItems(routes.map { it.sender.name }.toTypedArray()) { _, index ->
                    if (!routes[index].sender.sendMissionPackage(manifest, recipients, mp, callback))
                        callback?.onSentContent(this, URIHelper.getURI(manifest), false)
                }.setNegativeButton("Cancel") { _, _ -> callback?.onSentContent(this, URIHelper.getURI(manifest), false) }
                .setOnCancelListener { callback?.onSentContent(this, URIHelper.getURI(manifest), false) }.show()
            return true
        }
    }

    init {
        if (original != null) { manager.unregisterSender(original); manager.registerSender(sender) }
    }

    override fun close() {
        closed = true
        if (original != null) { manager.unregisterSender(sender); manager.registerSender(original) }
    }
}
