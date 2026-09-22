package dev.arachne.atak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import com.atakmap.android.maps.MapView
import dev.arachne.atak.ArachneComponents as Ui
import dev.arachne.atak.ArachneStyle.Action as UiAction

/** Device-local resource policy. Opening or saving this form never changes
 * workspace activity, membership, native sharing or feed subscriptions. */
internal object WorkspaceResourceSettings {
    private fun editPolicy(context: Context, pane: View, record: LocalWorkspace, changed: () -> Unit) {
        val host = MapView.getMapView().context
        val saved = WorkspaceResources.policy(host, record.id)
        val server = Switch(context).apply { contentDescription = "Workspace TAK Server"; isChecked = saved.server }
        val serve = Switch(context).apply { contentDescription = "Serve cached resources"; isChecked = saved.serveReplicas }
        val quota = EditText(context).apply {
            contentDescription = "Resource cache quota in MiB"; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            setText((saved.quota / (1024 * 1024)).toString()); ArachneStyle.input(this)
        }
        val modes = WorkspaceResources.Retention.values()
        val labels = arrayOf("Automatic · default", "Selected resources only", "Off · keep existing copies")
        var mode = saved.retention
        val retention = android.widget.Button(context).apply {
            fun refresh() { text = labels[mode.ordinal]; contentDescription = "Caching: ${text}" }
            refresh(); ArachneStyle.button(this)
            setOnClickListener {
                ArachneStyle.dialog(host, pane).setTitle("Caching in ${record.name}")
                    .setSingleChoiceItems(labels, mode.ordinal) { dialog, index -> mode = modes[index]; refresh(); dialog.dismiss() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        val content = Ui.stack(context, ArachneStyle.SECTION_GAP,
            Ui.note(context, "${record.name} · this device only. Changes apply when you save."),
            Ui.toggle(context, "Workspace TAK Server", "Show Arachne: ${record.name} in ATAK’s package server picker.", server),
            Ui.section(context, "Caching", retention,
                Ui.note(context, "Cache verified bytes without installing. Selected mode fetches only your choices; Off keeps existing copies.")),
            Ui.section(context, "Cache quota (MiB)", quota,
                Ui.note(context, "0–1024 MiB per workspace. Reducing the quota may evict oldest cached copies. Published packages are outside this quota.")),
            Ui.toggle(context, "Serve cached resources", "Let current workspace members fetch cached copies. Off keeps them available locally.", serve),
            Ui.details(context, "defaults & connection behavior",
                Ui.note(context, "Defaults: server on, automatic caching, 256 MiB, replica serving on. Free space still limits downloads."),
                Ui.note(context, "Saving briefly reconnects the local ATAK bridge, not the workspace. Replica serving does not change packages you explicitly publish or directed sends.")))
        val dialog = ArachneStyle.dialog(host, pane).setTitle("Server & caching").setView(scrolling(context, content))
            .setNegativeButton("Cancel", null).setPositiveButton("Save settings", null).show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val mib = quota.text.toString().toLongOrNull()
            if (mib == null || mib !in 0..1024) quota.error = "Enter 0–1024 MiB"
            else if (WorkspaceResources.savePolicy(host, record.id, WorkspaceResources.Policy(server.isChecked, mode, mib * 1024 * 1024, serve.isChecked))) {
                changed(); Toast.makeText(host, "Package settings saved for ${record.name}.", Toast.LENGTH_SHORT).show(); dialog.dismiss()
            } else quota.error = "Settings could not be saved. Try again."
        }
    }

    fun show(context: Context, pane: View, record: LocalWorkspace, resources: () -> WorkspaceResources?, changed: () -> Unit) {
        val host = MapView.getMapView().context
        val main = Handler(Looper.getMainLooper())
        val usage = Ui.stack(context, 0)
        fun refreshUsage() {
            val value = WorkspaceResources.usage(host, record.id)
            fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(host, bytes)
            usage.removeAllViews()
            usage.addView(Ui.keyValue(context, "Cached copies", size(value.cached)))
            usage.addView(Ui.keyValue(context, "Published by this device", size(value.published)))
            usage.addView(Ui.keyValue(context, "Device free space", size(value.free)))
        }
        fun completed(result: Result<Unit>) = main.post {
            refreshUsage()
            Toast.makeText(host, if (result.isSuccess) "Cache updated. Installed and published packages were preserved."
                else "Cache operation failed. Refresh usage and try again.", Toast.LENGTH_LONG).show()
        }
        fun engine(): WorkspaceResources? = resources().also {
            if (it == null) Toast.makeText(host, "Resume this workspace to manage cached resources. Settings can still be saved.", Toast.LENGTH_LONG).show()
        }
        refreshUsage()
        val content = Ui.stack(context, ArachneStyle.SECTION_GAP,
            Ui.note(context, "${record.name} · this device only. Cached packages are not installed automatically."),
            Ui.link(context, "Server & caching", "TAK Server picker, retention, quota and replica serving", Ui.Icon.SETTINGS) {
                editPolicy(context, pane, record) { changed(); refreshUsage() }
            },
            Ui.section(context, "Storage on this device", usage,
                Ui.action(context, "Refresh usage", UiAction.QUIET) { refreshUsage() }),
            Ui.section(context, "Manage cached copies", Ui.note(context, "These actions apply immediately; removal asks for confirmation."),
                Ui.action(context, "Select resources to retain", UiAction.SECONDARY) {
                    val resource = engine() ?: return@action
                    val entries = resource.cacheEntries().filterNot { it.published }
                    if (entries.isEmpty()) Toast.makeText(host, "No shared resources discovered yet.", Toast.LENGTH_SHORT).show()
                    else ArachneStyle.dialog(host, pane).setTitle("Select resources · ${record.name}")
                        .setMultiChoiceItems(entries.map { "${it.name} · ${if (it.cached) "cached" else "not cached"}" }.toTypedArray(),
                            entries.map { it.selected }.toBooleanArray()) { _, index, selected ->
                            runCatching { resource.select(entries[index].hash, selected) }.onFailure {
                                Toast.makeText(host, "Selection could not be saved.", Toast.LENGTH_LONG).show()
                            }
                        }.setPositiveButton("Done", null).show()
                },
                Ui.action(context, "Remove a cached copy", UiAction.SECONDARY) {
                    val resource = engine() ?: return@action
                    val entries = resource.cacheEntries().filter { it.cached && !it.published }
                    if (entries.isEmpty()) Toast.makeText(host, "No cached copies to remove.", Toast.LENGTH_SHORT).show()
                    else ArachneStyle.dialog(host, pane).setTitle("Cached copies · ${record.name}")
                        .setItems(entries.map { it.name }.toTypedArray()) { _, index ->
                            val entry = entries[index]
                            ArachneStyle.dialog(host, pane).setTitle("Remove cached copy?")
                                .setMessage("Remove ${entry.name} from this device's cache? It will not cache again until you select it. Installed packages and other members' copies are unchanged.")
                                .setNegativeButton("Cancel", null).setPositiveButton("Remove copy") { _, _ ->
                                    resource.removeCached(entry.hash) { completed(it) }
                                }.show()
                        }.setNegativeButton("Cancel", null).show()
                },
                Ui.action(context, "Clear cache & turn caching off", UiAction.DESTRUCTIVE) {
                    val resource = engine() ?: return@action
                    ArachneStyle.dialog(host, pane).setTitle("Clear cache in ${record.name}?")
                        .setMessage("Remove this workspace's cached replicas from this device and turn caching off to prevent refilling. Published packages, installed packages and membership are preserved.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Clear cache") { _, _ ->
                            resource.clearCache { result -> main.post {
                                if (result.isSuccess) changed()
                                completed(result)
                            } }
                        }.show()
                }),
            Ui.details(context, "install instructions & limits",
                Ui.note(context, "To install a cached data package, use ATAK Data Packages → Download from TAK Server → Arachne: ${record.name}. Keep this workspace active and the server enabled; a verified local copy needs no reachable peer."),
                Ui.note(context, "Packages stream within available device storage, with no fixed file-size limit. The cache quota controls retained replicas, not transfer size. Private server uploads are not supported; use selected contacts for directed sends.")))
        ArachneStyle.dialog(host, pane).setTitle("Packages & storage · ${record.name}").setView(scrolling(context, content))
            .setPositiveButton("Done", null).show()
    }

    private fun scrolling(context: Context, content: View) = ScrollView(context).apply {
        addView(content)
        val inset = ArachneStyle.dp(this, ArachneStyle.INSET)
        setPadding(inset, 0, inset, ArachneStyle.dp(this, ArachneStyle.GROUP))
    }
}
