package dev.arachne.atak

import android.graphics.drawable.Drawable
import android.util.Log
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import android.view.Gravity
import dev.arachne.atak.ArachneComponents as Ui
import dev.arachne.atak.ArachneStyle.Type as UiType
import dev.arachne.atak.ArachneStyle.Tone as UiTone
import dev.arachne.atak.ArachneStyle.Action as UiAction
import com.atak.plugins.impl.PluginContextProvider
import com.atakmap.comms.CommsProviderFactory
import com.atakmap.android.maps.MapView
import gov.tak.api.commons.graphics.Bitmap
import gov.tak.api.plugin.IPlugin
import gov.tak.api.plugin.IServiceController
import gov.tak.api.ui.IHostUIService
import com.atakmap.android.dropdown.DropDown
import com.atakmap.android.dropdown.DropDownReceiver
import com.atakmap.android.ipc.AtakBroadcast
import com.atakmap.android.util.NotificationUtil
import gov.tak.api.ui.ToolbarItem
import gov.tak.api.ui.ToolbarItemAdapter
import gov.tak.platform.marshal.MarshalManager

/** ATAK host lifecycle, workspace navigation and member controls. */
class FabricPlugin(controller: IServiceController) : IPlugin {
    @Volatile private var workspaces: WorkspaceConnections? = null
    private var workspaceAdapters: WorkspaceAdapters? = null
    private var nearbyInvitations: NearbyInvitations? = null
    private val invitationReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(host: android.content.Context, intent: android.content.Intent) {
            if (intent.action != InvitationActivity.ACTION) return
            val link = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return
            if (openInvitation(link)) resultCode = android.app.Activity.RESULT_OK
        }
    }
    private fun openInvitation(link: String): Boolean {
        if (memberState?.busy == true || workspaceSubmission != null || reviewingInvitation || runCatching { WorkspaceInvitation.decode(link) }.isFailure) return false
        val returnToNearby = screen == Screen.NEARBY
        showPane()
        navigate(Screen.JOIN)
        workspaceName.text.clear()
        memberName.setText(MapView.getMapView().deviceCallsign.orEmpty())
        joinLink.setText(link)
        formMessage(Screen.JOIN, "")
        reviewInvitation(link, returnToNearby)
        return true
    }
    private val context = requireNotNull(controller.getService(PluginContextProvider::class.java)).pluginContext.also { ArachneStyle.initialize(it, MapView.getMapView().context) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation = 0
    private val diagnosticsStatus by lazy { Ui.notice(context, "Loading live Arachne state…").apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    } }
    private val workspaceStatus by lazy { Ui.notice(context, "").apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    } }
    private val workspaceName by lazy { EditText(context).apply {
        hint = "Workspace name"; contentDescription = "Workspace name"; isSingleLine = true
        ArachneStyle.text(this)
        setHintTextColor(ArachneStyle.secondaryColor)
        filters = arrayOf(android.text.InputFilter.LengthFilter(160))
    } }
    private val memberName by lazy { EditText(context).apply {
        hint = "Your name"; contentDescription = "Your name"; isSingleLine = true
        ArachneStyle.text(this)
        setHintTextColor(ArachneStyle.secondaryColor)
        filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        setText(MapView.getMapView()?.deviceCallsign.orEmpty())
    } }
    private fun labeledInput(label: String, input: EditText) = Ui.stack(input.context, 6).apply {
        orientation = LinearLayout.VERTICAL
        input.id = android.view.View.generateViewId()
        ArachneStyle.input(input)
        addView(TextView(input.context).apply {
            text = label
            labelFor = input.id
            ArachneStyle.text(this, UiType.FIELD, UiTone.SECONDARY)
        }, LinearLayout.LayoutParams(-1, -2))
        addView(input, LinearLayout.LayoutParams(-1, -2))
    }
    private val workspaceNameField by lazy { labeledInput("Workspace name", workspaceName) }
    private val memberNameField by lazy { labeledInput("Your name in this workspace", memberName) }
    private val invitationField by lazy { labeledInput("Private invitation link", joinLink) }
    private val initialSharing by lazy { android.widget.Switch(context).apply {
        contentDescription = "Share workspace-wide ATAK updates"
        ArachneStyle.choice(this)
        isChecked = true
    } }
    private val initialSharingRow by lazy { Ui.toggle(context, "Share ATAK updates",
        "Publish location, points, tracks and drawings to this workspace. You can change this in Sharing.", initialSharing) }
    private val createWorkspace by lazy { Button(context).apply {
        text = "Create workspace"
        setOnClickListener {
            val nameError = runCatching { checkedWorkspaceName(workspaceName.text.toString()) }.exceptionOrNull()?.message
            if (nameError != null) { workspaceName.error = nameError }
            else {
                val name = validMemberName() ?: return@setOnClickListener
                submitWorkspaceForm(Screen.CREATE) { workspaces?.create(workspaceName.text.toString(), name, initialSharing.isChecked) == true }
            }
        }
    } }
    private val joinLink by lazy { EditText(context).apply {
        hint = "Paste invitation link"; contentDescription = "Invitation link"; isSingleLine = true
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(65536))
        ArachneStyle.text(this); setHintTextColor(ArachneStyle.secondaryColor)
        isSaveEnabled = false
    } }
    private var reviewingInvitation = false
    private val joinWorkspace by lazy { Button(context).apply {
        text = "Review invitation"
        setOnClickListener { invitationInput()?.let { reviewInvitation(it) } }
    } }
    private data class InvitationReview(val link: String, val hydrated: JSONObject?, val verified: JSONObject?)
    private var invitationReview: InvitationReview? = null
    private val reviewSummary by lazy { Ui.stack(context, ArachneStyle.GROUP) }
    private val confirmJoin by lazy { Ui.action(context, "Join workspace", UiAction.PRIMARY) {
        val review = invitationReview ?: return@action
        val name = validMemberName() ?: return@action
        val sharing = initialSharing.isChecked
        submitWorkspaceForm(Screen.JOIN_REVIEW) {
            if (review.hydrated == null) workspaces?.join(review.link, name, sharing) == true
            else workspaces?.join(review.hydrated, name, sharing) == true
        }
    } }
    private fun validMemberName(): String? = runCatching { checkedMemberName(memberName.text.toString()) }
        .onSuccess { memberName.error = null }
        .onFailure { memberName.error = "Use a name of 1–80 characters without control or direction characters." }.getOrNull()
    private data class WorkspaceSubmission(val form: Screen, val previousSlots: Set<String>, var observedBusy: Boolean = false)
    private var workspaceSubmission: WorkspaceSubmission? = null
    private val formMessages = mutableMapOf<Screen, TextView>()
    private var formClose: Button? = null
    private fun formMessage(destination: Screen, message: String, error: Boolean = false) {
        formMessages[destination]?.apply {
            text = message
            ArachneStyle.notice(this, if (error) UiTone.DANGER else UiTone.SECONDARY)
            visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        }
        if (error && screen == destination) formScroll?.scrollTo(0, 0)
    }
    private fun submitWorkspaceForm(destination: Screen, submit: () -> Boolean) {
        if (workspaceSubmission != null) return
        workspaceSubmission = WorkspaceSubmission(destination, memberState?.saved.orEmpty().map { it.slot }.toSet())
        if (!submit()) {
            workspaceSubmission = null
            formMessage(destination, "Could not start. Wait for the current operation, or pause a workspace in Connection to free capacity, then try again.", true)
        } else formMessage(destination, if (destination == Screen.CREATE) "Creating workspace… You can close this form; the operation will continue."
            else "Saving your join request… You can close this form; the operation will continue.")
        updateFormControls()
    }
    private fun updateFormControls(state: WorkspaceView? = memberState) {
        val locked = state?.busy != false || workspaceSubmission != null || reviewingInvitation
        createWorkspace.isEnabled = !locked
        joinWorkspace.isEnabled = !locked
        confirmJoin.isEnabled = !locked && invitationReview != null
        createInvitation.isEnabled = !locked && invitationSubmission == null && memberConnectionActive &&
            state != null && state.active?.slot == invitationFormSlot && state.members.any { it.self && it.administrator }
        createInvitation.text = if (invitationSubmission != null) "Creating…" else "Create invitation"
        invitationLabelInput.isEnabled = invitationSubmission == null && !locked
        invitationAutomatic.isEnabled = invitationLabelInput.isEnabled
        invitationOptions.forEach { it.isEnabled = invitationLabelInput.isEnabled }
        workspaceName.isEnabled = !locked
        memberName.isEnabled = !locked
        joinLink.isEnabled = !locked
        initialSharing.isEnabled = !locked
        findNearbyWorkspace.isEnabled = !locked && !findingNearby
        scanInvitation.isEnabled = !locked
        createWorkspace.text = if (workspaceSubmission?.form == Screen.CREATE) "Creating…" else "Create workspace"
        joinWorkspace.text = if (reviewingInvitation) "Checking…" else "Review invitation"
        confirmJoin.text = when {
            workspaceSubmission?.form == Screen.JOIN_REVIEW -> "Joining…"
            invitationReview?.verified == null -> "Save join request"
            invitationReview?.verified?.optBoolean("personal_invitation") == true &&
                invitationReview?.verified?.optBoolean("automatic_approval") != true -> "Request access"
            else -> "Join workspace"
        }
        formClose?.text = when {
            workspaceSubmission != null || invitationSubmission != null || reviewingInvitation -> "Close"
            screen == Screen.RENAME && state?.members?.none { it.self && it.administrator } != false -> "Done"
            else -> "Cancel"
        }
    }
    private fun finishWorkspaceForm(state: WorkspaceView) {
        val request = workspaceSubmission ?: return
        if (screen != request.form) { workspaceSubmission = null; return }
        val record = state.active
        // Ignore queued snapshots of an existing workspace while the coordinator
        // selects the new owner. Opening an old workspace is never form success.
        if (record != null && !record.ended && record.slot in request.previousSlots) return
        if (state.busy) request.observedBusy = true
        val saved = record != null && record.slot !in request.previousSlots && !record.ended
        val waiting = saved && record?.joinPeer != null
        val joined = saved && !state.busy && state.members.any { it.self }
        if (waiting || joined) {
            workspaceSubmission = null
            formMessage(request.form, "")
            if (request.form == Screen.JOIN_REVIEW) { joinLink.text.clear(); invitationReview = null }
            history.clear(); history.add(PageLocation(Screen.WORKSPACES, 0))
            navigate(if (waiting) Screen.WAITING else Screen.MEMBERS, remember = false)
        } else if (!state.busy && request.observedBusy) {
            workspaceSubmission = null
            formMessage(request.form, usefulMessage(state) ?: "The workspace could not be opened. Your form is preserved; try again or open Workspaces to review saved state.", true)
        }
    }
    private val scanInvitation by lazy { Button(context).apply {
        text = "Scan invitation QR code"
        setOnClickListener {
            val intent = android.content.Intent().setClassName(BuildConfig.APPLICATION_ID,
                "${BuildConfig.APPLICATION_ID}.InvitationScannerActivity")
                .putExtra(ArachneStyle.EXTRA_APPEARANCE, ArachneStyle.appearance.name)
                .putExtra(ArachneStyle.EXTRA_LARGER_TEXT, ArachneStyle.largerText)
            try { MapView.getMapView().context.startActivity(intent) }
            catch (_: android.content.ActivityNotFoundException) {
                toast("The QR scanner is unavailable. Reinstall the current Arachne build.")
            }
        }
    } }
    private var findingNearby = false
    private var nearbyWorkspaces: List<NearbyWorkspace> = emptyList()
    private var nearbyFoundAt = 0L
    private var nearbyMessage = ""
    private val findNearbyWorkspace by lazy { Button(context).apply {
        text = "Find nearby"
        setOnClickListener { navigate(Screen.NEARBY); discoverNearbyWorkspaces() }
    } }
    private fun discoverNearbyWorkspaces() {
        if (findingNearby) return
        val service = nearbyInvitations ?: return
        val started = generation
        findingNearby = true
        nearbyMessage = "Searching for workspaces advertised on this network…"
        showNearbyWorkspaces()
        if (!service.discoverWorkspaces { result ->
            if (generation != started) return@discoverWorkspaces
            findingNearby = false
            result.onSuccess {
                nearbyWorkspaces = it.workspaces
                nearbyFoundAt = android.os.SystemClock.elapsedRealtime()
                nearbyMessage = if (it.limited) "Some nearby devices did not answer. These results may be incomplete." else ""
            }.onFailure { nearbyFoundAt = 0; nearbyMessage = it.message ?: "Nearby discovery failed. Try refreshing." }
            updateFormControls()
            showNearbyWorkspaces()
        }) {
            findingNearby = false
            nearbyFoundAt = 0
            nearbyMessage = "Nearby discovery could not start. Try refreshing."
            showNearbyWorkspaces()
        }
    }
    private fun showNearbyWorkspaces() {
        replacePage(Screen.NEARBY, listOf(findingNearby, nearbyMessage, nearbyWorkspaces)) {
            val rows = nearbyWorkspaces.map { advertised ->
                Ui.link(context, advertised.label,
                    "${advertised.mode.label} · Unverified · Workspace ${advertised.code}", Ui.Icon.WIFI) {
                    if (nearbyFoundAt == 0L || android.os.SystemClock.elapsedRealtime() - nearbyFoundAt > 30_000)
                        toast("Refresh nearby workspaces before selecting this result.")
                    else openInvitation(advertised.link)
                }.apply { isEnabled = !findingNearby && nearbyFoundAt != 0L }
            }
            listOf(Ui.intro(context, "Nearby labels are unverified. Open an invitation to verify its workspace before joining."),
                Ui.toolbar(context, Ui.label(context, "Nearby workspaces", UiType.LABEL),
                    Ui.action(context, if (findingNearby) "Searching…" else "Refresh", UiAction.QUIET) { discoverNearbyWorkspaces() }
                        .apply { isEnabled = !findingNearby }),
                if (nearbyMessage.isNotBlank()) Ui.notice(context, nearbyMessage) else Ui.note(context, "${rows.size} advertised"),
                if (rows.isEmpty()) Ui.empty(context, if (findingNearby) "Looking nearby" else "No workspaces found",
                    "An administrator must enable nearby discovery. You can also join using a QR code or a private link.")
                else Ui.stack(context, 0, *rows.toTypedArray()),
                pageLink("Use a private link", "Return to Join workspace", Screen.JOIN))
        }
    }
    private fun reviewInvitation(link: String, returnToNearby: Boolean = false) {
        if (reviewingInvitation) return
        val decoded = runCatching { WorkspaceInvitation.decode(link) }.getOrElse {
            joinLink.error = "Paste a complete Arachne invitation link."; return
        }
        val started = generation
        reviewingInvitation = true
        invitationReview = null
        formMessage(Screen.JOIN, "Verifying the invitation before you join…")
        updateFormControls()
        Thread({
            val result = runCatching {
                val hydrated = hydrateWorkspaceInvitation(MapView.getMapView().context, decoded)
                val request = JSONObject().put("invitation", hydrated.getJSONArray("invitation"))
                    .put("checkpoint", hydrated.getJSONArray("checkpoint"))
                hydrated to inspectWorkspaceInvitation(MapView.getMapView().context,
                    request.toString().toByteArray(Charsets.UTF_8))
            }
            mainHandler.post {
                if (generation != started) return@post
                reviewingInvitation = false
                updateFormControls()
                if (screen != Screen.JOIN || joinLink.text.toString().trim() != link) return@post
                formMessage(Screen.JOIN, "")
                result.onSuccess { (hydrated, verified) ->
                    if (verified.getJSONArray("workspace").toString() != hydrated.getJSONArray("workspace").toString()) {
                        joinLink.error = "This invitation does not match its workspace."; return@onSuccess
                    }
                    if (verified.optLong("expires_at") != 0L && verified.getLong("expires_at") <= System.currentTimeMillis() / 1000) {
                        joinLink.error = "This invitation has expired. Ask an administrator for a new link."; return@onSuccess
                    }
                    invitationReview = InvitationReview(link, hydrated, verified)
                    navigate(Screen.JOIN_REVIEW, remember = !returnToNearby)
                }.onFailure { error ->
                    if (error.message?.contains("No authorized workspace member") == true) {
                        invitationReview = InvitationReview(link, null, null)
                        navigate(Screen.JOIN_REVIEW, remember = !returnToNearby)
                    } else joinLink.error = "This invitation could not be verified. Ask an administrator for a new link."
                }
            }
        }, "arachne-invitation-review").start()
    }
    private fun showJoinReview() {
        reviewSummary.removeAllViews()
        val verified = invitationReview?.verified
        if (verified == null) {
            reviewSummary.addView(Ui.label(context, "Waiting for verification", UiType.SECTION))
            reviewSummary.addView(Ui.notice(context,
                "No authorized workspace member could be reached. Save your join request to retry automatically. The workspace name is not verified, and no ATAK updates are shared before joining.", UiTone.WARNING))
        } else {
            val name = if (verified.isNull("workspace_name")) "Unnamed workspace" else verified.getString("workspace_name")
            reviewSummary.addView(Ui.label(context, name, UiType.SECTION))
            reviewSummary.addView(ArachneSignal(context, "Invitation verified", ArachneSignal.State.ACTIVE))
            val personal = verified.optBoolean("personal_invitation")
            val automatic = verified.optBoolean("automatic_approval")
            reviewSummary.addView(Ui.keyValue(context, "Joining", when {
                automatic -> "One device · automatic approval"
                personal -> "Administrator review"
                else -> "Open invitation"
            }))
            reviewSummary.addView(Ui.note(context, if (personal && !automatic)
                "An administrator must approve your request before joining finishes."
                else "Joining finishes when an authorized workspace member is reachable."))
        }
    }
    private var workspaceNameSlot: String? = null
    private val settingsName by lazy { EditText(context).apply {
        hint = "Workspace name"; isSingleLine = true
        filters = arrayOf(android.text.InputFilter.LengthFilter(160))
        ArachneStyle.input(this)
    } }
    private val settingsNameField by lazy { labeledInput("Workspace name", settingsName) }
    private val renameWorkspace by lazy { Ui.action(context, "Save name", UiAction.PRIMARY) {
        val state = memberState ?: return@action
        val record = state.active ?: return@action
        if (state.busy || state.members.none { it.self && it.administrator }) return@action
        val name = runCatching { checkedWorkspaceName(settingsName.text.toString()) }
        name.onFailure { settingsName.error = it.message }
        name.onSuccess {
            if (it == record.sharedName) formMessage(Screen.RENAME, "The workspace name is already saved.")
            else if (workspaces?.rename(record.id, record.name, it) == true) formMessage(Screen.RENAME, "Saving workspace name…")
            else formMessage(Screen.RENAME, "Could not start the rename. Try again when this workspace is ready.", true)
        }
    } }
    private val leaveWorkspace by lazy { Button(context).apply {
        text = "Leave workspace"
        setOnClickListener { memberState?.let { confirmLeave(it) } }
    } }
    private fun confirmLeave(state: WorkspaceView) {
        val record = state.active ?: return
        if (record.ended || record.joinPeer != null) return
        val self = state.members.singleOrNull { it.self }
        val handover = self?.administrator == true && state.members.size > 1 && state.members.count { it.administrator } == 1
        val ending = self?.administrator == true && state.members.size == 1
        val dialog = ArachneStyle.dialog(MapView.getMapView().context, pagePane)
            .setTitle(if (handover) "Choose another administrator" else if (ending) "End ${record.name}?" else "Leave ${record.name}?")
            .setNegativeButton("Cancel", null)
        if (handover) {
            val choices = state.members.filter { !it.self && it.presence == "reachable" }
            if (choices.isEmpty()) dialog.setMessage("Another member must be reachable before you can hand over administration and leave.")
            else {
                var selected = 0
                dialog.setSingleChoiceItems(choices.map { "${it.name ?: "Name not received"} · ${it.id.take(8)}" }.toTypedArray(), selected) { _, which -> selected = which }
                    .setPositiveButton("Make admin and leave") { _, _ -> performOperation("Hand over and leave") { workspaces?.leave(record.id, choices[selected].identity()) } }
            }
        } else {
            dialog.setMessage(if (ending) "You are the only member. Ending this workspace stops sharing and removes it from your list."
                else "Leave workspace updates the team roster now. Remove from this device stops access here immediately; others may show you offline until an administrator removes that roster entry.")
                .setPositiveButton(if (ending) "End workspace" else "Leave workspace") { _, _ -> performOperation("Leave workspace") { workspaces?.leave(record.id) } }
        }
        if (!ending) dialog.setNeutralButton("Remove from this device") { _, _ -> performOperation("Remove from this device") { workspaces?.leaveDevice(record) } }
        dialog.show()
    }
    private fun confirmRemoveDevice(record: LocalWorkspace) {
        ArachneStyle.dialog(MapView.getMapView().context, pagePane)
            .setTitle("Remove ${record.name} from this device?")
            .setMessage("This stops Arachne access on this tablet immediately. Other members may still show this device offline until an administrator removes its roster entry.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove from device") { _, _ -> performOperation("Remove from this device") { workspaces?.leaveDevice(record) } }
            .show()
    }
    private fun confirmDismiss(record: LocalWorkspace) {
        ArachneStyle.dialog(MapView.getMapView().context, pagePane)
            .setTitle("Remove ${record.name} from this device?")
            .setMessage("Arachne will remove this workspace from your list. Your membership will remain ended.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove from list") { _, _ -> performOperation("Remove from list") { workspaces?.dismiss(record) } }
            .show()
    }
    private val reconnectWorkspace by lazy { Button(context).apply {
        text = "Reconnect"
        setOnClickListener {
            val link = invitationInput() ?: return@setOnClickListener
            if (workspaces?.reconnect(link) == true) { joinLink.text.clear(); goBack() }
            else joinLink.error = "Could not start reconnecting. Wait for the current operation and try again."
        }
    } }
    private fun invitationInput(): String? {
        val link = joinLink.text.toString().trim()
        if (runCatching { WorkspaceInvitation.decode(link) }.isFailure) {
            joinLink.error = if (link.isEmpty()) "Paste the invitation link you received."
                else "This invitation link is incomplete or invalid. Paste the full link you received."
            joinLink.requestFocus()
            return null
        }
        joinLink.error = null
        return link
    }
    private var selectedInvitationNumber: Int? = null
    private val issuedLinks = mutableMapOf<Pair<String, Int>, String>()
    private val invitationLabels by lazy { MapView.getMapView().context.getSharedPreferences("arachne-invitation-labels", android.content.Context.MODE_PRIVATE) }
    private val invitationLink: String? get() {
        val state = memberState ?: return null
        val record = state.active ?: return null
        val link = state.invitations.find { it.number == selectedInvitationNumber } ?: return null
        if (!link.enabled || (link.expiresAt != 0L && link.expiresAt <= System.currentTimeMillis() / 1000) ||
            state.members.none { it.self && it.administrator }) return null
        return issuedLinks[record.slot to link.number]
    }
    private fun invitationLabel(record: LocalWorkspace, link: WorkspaceInvite) =
        invitationLabels.getString("${record.slot}:${link.number}", null)
            ?: "${if (link.requestAccess) "Request access" else if (link.personal) "Personal" else "Open"} invitation ${link.number}"
    private data class InvitationSubmission(val slot: String, val previousLink: String?, val label: String, var observedBusy: Boolean = false)
    private var invitationSubmission: InvitationSubmission? = null
    private var invitationFormSlot: String? = null
    private var invitationPersonal = true
    private var invitationLifetime = 0L
    private val invitationOptions = mutableListOf<Button>()
    private val invitationLabelInput by lazy { EditText(context).apply {
        hint = "For example: Shift B"; isSingleLine = true
        filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        ArachneStyle.input(this)
    } }
    private val invitationAutomatic by lazy { android.widget.CheckBox(context).apply {
        text = "Approve the first device automatically"; isChecked = true; ArachneStyle.choice(this)
    } }
    private val invitationExplanation by lazy { Ui.note(context, "A personal invitation can be used by one device. An administrator must be reachable to finish joining.") }
    private val createInvitation by lazy { Ui.action(context, "Create invitation", UiAction.PRIMARY) {
        val state = memberState ?: return@action
        val record = state.active ?: return@action
        if (state.busy || invitationSubmission != null || record.slot != invitationFormSlot ||
            state.members.none { it.self && it.administrator }) return@action
        val label = invitationLabelInput.text.toString().trim()
        if (label.isNotEmpty() && runCatching { checkedMemberName(label) }.isFailure) {
            invitationLabelInput.error = "Use a short label without control or direction characters."; return@action
        }
        invitationSubmission = InvitationSubmission(record.slot, state.invitation, label)
        if (workspaces?.invite(if (invitationLifetime == 0L) 0 else System.currentTimeMillis() / 1000 + invitationLifetime,
                invitationPersonal, invitationPersonal && invitationAutomatic.isChecked) != true) {
            invitationSubmission = null
            formMessage(Screen.INVITE, "Could not start. Wait for the current operation and try again.", true)
        } else formMessage(Screen.INVITE, "Creating your private invitation… You can close this form; the operation will continue.")
        updateFormControls()
    } }
    private val pendingNearbyModes = mutableMapOf<String, NearbyJoinMode>()
    private val advertisedNearbyAuthorities = mutableMapOf<String, Pair<ByteArray, ByteArray>>()
    private val advertisedNearbyModes = mutableMapOf<String, NearbyJoinMode>()
    private val nearbyAdvertisementAttempts = mutableMapOf<String, Int>()
    private val inviteMember by lazy { Button(context).apply {
        text = "New"
        setOnClickListener { openInvitationForm() }
    } }
    private fun openInvitationForm() {
        val state = memberState ?: return
        val record = state.active ?: return
        if (state.members.none { it.self && it.administrator } || state.busy || invitationSubmission != null) return
        if (invitationFormSlot != record.slot) invitationLabelInput.text.clear()
        invitationFormSlot = record.slot
        formMessage(Screen.INVITE, "")
        navigate(Screen.INVITE)
    }
    private fun finishInvitationCreation(connections: WorkspaceConnectionView) {
        val request = invitationSubmission ?: return
        val state = (connections.connected + connections.selected).find { it.active?.slot == request.slot } ?: return
        if (state.busy) { request.observedBusy = true; return }
        val created = state.invitationKey?.let { key -> state.invitations.singleOrNull { it.key.contentEquals(key) } }
        if (created != null && state.invitation != null && state.invitation != request.previousLink) {
            issuedLinks[request.slot to created.number] = state.invitation
            if (request.label.isNotBlank()) invitationLabels.edit().putString("${request.slot}:${created.number}", request.label).apply()
            invitationSubmission = null
            formMessage(Screen.INVITE, "")
            if (screen == Screen.INVITE && memberState?.active?.slot == request.slot) {
                selectedInvitationNumber = created.number
                // The completed form should not reappear when returning from its result.
                navigate(Screen.INVITATION, remember = false)
            }
        } else if (request.observedBusy) {
            invitationSubmission = null
            formMessage(Screen.INVITE, usefulMessage(state) ?: "Invitation creation did not finish. Your choices are preserved.", true)
        }
    }

    private fun singleChoice(viewContext: android.content.Context, title: String, labels: Array<String>, initial: Int = 0, changed: (Int) -> Unit) = Button(viewContext).apply {
        var selected = initial.coerceIn(labels.indices)
        text = labels[selected]
        contentDescription = "$title: ${labels[selected]}"
        ArachneStyle.button(this)
        setOnClickListener {
            ArachneStyle.dialog(MapView.getMapView().context, pagePane).setTitle(title).setSingleChoiceItems(labels, selected) { dialog, index ->
                selected = index; text = labels[index]; contentDescription = "$title: ${labels[index]}"
                changed(index); dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun invitationStatus(link: WorkspaceInvite): String = when {
        !link.enabled -> "Disabled"
        link.expiresAt != 0L && link.expiresAt <= System.currentTimeMillis() / 1000 -> "Expired"
        link.requestAccess -> "Each request reviewed separately"
        link.personal && link.approved -> "Approved for one person"
        link.automatic -> "Automatic approval"
        link.personal -> "Administrator review"
        else -> "Open joining"
    }
    private fun invitationExpiry(link: WorkspaceInvite) = if (link.expiresAt == 0L) "No expiry" else
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(link.expiresAt * 1000))
    private fun disableInvitation(record: LocalWorkspace, link: WorkspaceInvite?) {
        ArachneStyle.dialog(MapView.getMapView().context, pagePane)
            .setTitle("Disable ${if (link == null) "older links" else "invitation ${link.number}"}?")
            .setMessage("Existing members stay in the workspace. Other members must receive this change before they can stop accepting the link.")
            .setNegativeButton("Cancel", null).setPositiveButton("Disable") { _, _ ->
                val latest = memberState?.takeIf { it.active?.slot == record.slot } ?: return@setPositiveButton
                if (latest.busy || latest.members.none { it.self && it.administrator }) return@setPositiveButton
                stopNearbyAdvertisement(false, record.slot)
                performOperation("Disable invitation") { workspaces?.disableInvitation(record.id, link?.key ?: ByteArray(32)) }
            }.show()
    }
    private fun showInvitationDetails() {
        val state = memberState ?: return
        val record = state.active ?: return
        val link = state.invitations.find { it.number == selectedInvitationNumber }
        val privateLink = invitationLink
        val admin = state.members.any { it.self && it.administrator }
        replacePage(Screen.INVITATION, listOf(record.slot, selectedInvitationNumber, link?.let(::invitationStatus), link?.expiresAt,
                privateLink, admin, state.busy, link?.let { invitationLabel(record, it) })) {
            if (link == null) listOf(Ui.empty(context, "Invitation unavailable", "Return to Invitations for the current list."))
            else {
                val views = mutableListOf<View>(Ui.label(context, invitationLabel(record, link), UiType.SECTION),
                    Ui.stack(context, 0, Ui.keyValue(context, "Status", invitationStatus(link)),
                        Ui.keyValue(context, "Type", if (link.requestAccess) "Request access · each person reviewed" else if (link.personal) "Personal · one device" else "Open · multiple devices"),
                        Ui.keyValue(context, "Expires", invitationExpiry(link))))
                if (privateLink != null) {
                    listOf(copyInvitation, showInvitationQr, shareInvitation).forEach {
                        (it.parent as? android.view.ViewGroup)?.removeView(it)
                        it.visibility = View.VISIBLE; it.isEnabled = !state.busy
                    }
                    copyInvitation.text = "Copy"; Ui.actionIcon(copyInvitation, Ui.Icon.COPY)
                    showInvitationQr.text = "QR"; Ui.actionIcon(showInvitationQr, Ui.Icon.SCAN)
                    shareInvitation.text = "Share"
                    views += Ui.notice(context, "This is a private joining capability. Share it only with the intended people.")
                    views += Ui.actions(context, copyInvitation, showInvitationQr, shareInvitation)
                    views += Ui.link(context, "Send nearby", "Choose and confirm a nearby device", Ui.Icon.WIFI) {
                        if (!state.busy) { navigate(Screen.NEARBY_DEVICES); discoverNearbyDevices() }
                    }
                    views += Ui.details(context, "private invitation link", Ui.identity(context, "Private invitation link", privateLink))
                } else if (admin && link.enabled) {
                    views += Ui.notice(context, "This private link is unavailable on this device. Issued links are kept only during the current plugin session.")
                    views += Ui.action(context, "Create a new invitation") { openInvitationForm() }.apply { isEnabled = !state.busy && memberConnectionActive }
                }
                if (admin && link.enabled) views += Ui.action(context, "Disable invitation", UiAction.DESTRUCTIVE) { disableInvitation(record, link) }
                    .apply { isEnabled = !state.busy && memberConnectionActive }
                views
            }
        }
    }
    private val invitationList by lazy { Ui.stack(context, 0) }
    private val invitationCount by lazy { Ui.note(context, "") }
    private val nearbyDiscoverySection by lazy { Ui.section(context, "Nearby discovery", nearbyAdvertisementStatus, advertiseWorkspaceNearby) }
    private var invitationKey: List<Any?>? = null
    private fun showInvitations(state: WorkspaceView) {
        val admin = state.members.any { it.self && it.administrator }
        val available = state.invitations.count { invitationStatus(it) !in setOf("Disabled", "Expired", "Approved for one person") }
        invitationCount.text = "$available active · ${state.invitations.size} created"
        nearbyDiscoverySection.visibility = if (admin) View.VISIBLE else View.GONE
        val key = listOf(state.active?.slot, admin, state.invitations.map { listOf(it.number, invitationStatus(it), it.expiresAt) }, state.legacyInvitations, state.busy)
        if (invitationKey == key) return
        invitationKey = key
        invitationList.removeAllViews()
        val record = state.active ?: return
        if (!admin) invitationList.addView(Ui.empty(context, "Administrator access", "Ask a workspace administrator to create an invitation or review join requests."))
        else {
            if (state.invitations.isEmpty()) invitationList.addView(Ui.empty(context, "No invitations yet", "Create a personal invitation for one person, or an open invitation for a wider team."))
            for (link in state.invitations.asReversed()) invitationList.addView(Ui.link(context,
                invitationLabel(record, link), invitationStatus(link), Ui.Icon.LINK) {
                selectedInvitationNumber = link.number
                navigate(Screen.INVITATION)
            }, LinearLayout.LayoutParams(-1, -2))
            if (state.legacyInvitations) invitationList.addView(Ui.action(context, "Disable older links", UiAction.DESTRUCTIVE) {
                disableInvitation(record, null)
            }.apply { isEnabled = !state.busy }, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private val nearbyAdvertisementStatus by lazy { TextView(context).apply {
        ArachneStyle.text(this, tone = UiTone.SECONDARY)
        accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        text = "Nearby workspace discovery is off. Nearby devices cannot see this workspace."
    } }
    private val advertiseWorkspaceNearby by lazy { Button(context).apply {
        text = "Advertise workspace nearby"
        setOnClickListener {
            val workspace = memberState?.active ?: return@setOnClickListener
            if (advertisedNearbyAuthorities.containsKey(workspace.slot) || pendingNearbyModes.containsKey(workspace.slot)) {
                ArachneStyle.dialog(MapView.getMapView().context, pagePane)
                    .setTitle("Stop nearby advertising?")
                    .setMessage("This nearby link will be disabled and the workspace will disappear from discovery. Waiting requests will need a new invitation. Existing members stay joined.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Stop advertising") { _, _ -> stopNearbyAdvertisement(true, workspace.slot) }
                    .show()
                return@setOnClickListener
            }
            val host = MapView.getMapView().context
            val body = Ui.stack(host, ArachneStyle.GROUP).apply {
                val inset = ArachneStyle.dp(this, ArachneStyle.INSET); setPadding(inset, 0, inset, inset)
            }
            val modes = android.widget.RadioGroup(host)
            val controlled = android.widget.RadioButton(host).apply {
                id = android.view.View.generateViewId(); text = "Request access"; minHeight = ArachneStyle.dp(this, 48)
            }
            val open = android.widget.RadioButton(host).apply {
                id = android.view.View.generateViewId(); text = "Open joining"; minHeight = ArachneStyle.dp(this, 48)
            }
            modes.addView(controlled); modes.addView(open); modes.check(controlled.id)
            body.addView(modes)
            body.addView(Ui.note(host, "Nearby devices will see the workspace name “${workspace.name}” and the join mode. The name is unverified until Join review. Your roster and administrator identity stay private. Other workspaces advertised by this device remain available."))
            var expiryIndex = 0
            body.addView(Ui.section(host, "Expires", singleChoice(host, "Discovery expiry", arrayOf("In 1 hour", "In 1 day", "In 7 days")) { expiryIndex = it }))
            ArachneStyle.dialog(host, pagePane).setTitle("Advertise ${workspace.name} nearby")
                .setView(ScrollView(host).apply { addView(body) }).setNegativeButton("Cancel", null).setPositiveButton("Start advertising") { _, _ ->
                    if (memberState?.active?.slot != workspace.slot || memberState?.busy == true) return@setPositiveButton
                    val mode = if (modes.checkedRadioButtonId == controlled.id) NearbyJoinMode.REQUEST_ACCESS else NearbyJoinMode.OPEN_JOINING
                    val lifetime = longArrayOf(3600, 86400, 604800)[expiryIndex]
                    pendingNearbyModes[workspace.slot] = mode
                    if (workspaces?.invite(System.currentTimeMillis() / 1000 + lifetime,
                            personal = mode == NearbyJoinMode.REQUEST_ACCESS, automatic = false,
                            requestAccess = mode == NearbyJoinMode.REQUEST_ACCESS) != true) {
                        pendingNearbyModes.remove(workspace.slot)
                        toast("Could not create the nearby invitation. Wait for the current operation and try again.")
                    }
                }.show()
        }
    } }

    private fun startNearbyAdvertisement(record: LocalWorkspace, mode: NearbyJoinMode, link: String, key: ByteArray) {
        val service = nearbyInvitations ?: return
        val slot = record.slot
        val attempt = (nearbyAdvertisementAttempts[slot] ?: 0) + 1
        nearbyAdvertisementAttempts[slot] = attempt
        if (!service.advertise(mode, link, record.name, record.id) { result ->
            if (attempt != nearbyAdvertisementAttempts[slot]) return@advertise
            result.onSuccess {
                advertisedNearbyAuthorities[slot] = record.id.copyOf() to key.copyOf()
                advertisedNearbyModes[slot] = mode
                if (memberState?.active?.slot == slot) {
                    nearbyAdvertisementStatus.text = "“${record.name}” · ${mode.label} is advertised on this LAN. The name is unverified until Join review. Roster and administrator identity stay private."
                    advertiseWorkspaceNearby.text = "Stop advertising nearby"
                }
            }.onFailure {
                nearbyAdvertisementStatus.text = "Nearby advertising could not start. This workspace remains private."
            }
        }) nearbyAdvertisementStatus.text = "Nearby advertising could not start. This workspace remains private."
    }

    private fun stopNearbyAdvertisement(showResult: Boolean, slot: String? = memberState?.active?.slot) {
        val target = slot ?: return
        val previousAuthority = advertisedNearbyAuthorities[target]
        val previousMode = advertisedNearbyModes[target]
        if (showResult && previousAuthority != null && workspaces?.disableInvitation(previousAuthority.first, previousAuthority.second) != true) {
            toast("Wait for the current operation, then stop advertising again.")
            return
        }
        pendingNearbyModes.remove(target)
        val attempt = (nearbyAdvertisementAttempts[target] ?: 0) + 1
        nearbyAdvertisementAttempts[target] = attempt
        advertisedNearbyAuthorities.remove(target)
        advertisedNearbyModes.remove(target)
        if (memberState?.active?.slot == target) {
            nearbyAdvertisementStatus.text = "Nearby workspace discovery is off. Nearby devices cannot see this workspace."
            advertiseWorkspaceNearby.text = "Advertise workspace nearby"
        }
        val service = nearbyInvitations ?: return
        fun failed() {
            if (attempt != nearbyAdvertisementAttempts[target]) return
            if (previousAuthority != null) advertisedNearbyAuthorities[target] = previousAuthority
            if (previousMode != null) advertisedNearbyModes[target] = previousMode
            if (memberState?.active?.slot == target) {
                nearbyAdvertisementStatus.text = "Could not stop nearby advertising. Try again."
                advertiseWorkspaceNearby.text = "Stop advertising nearby"
            }
        }
        if (previousAuthority == null || !service.advertise(null, null, workspace = previousAuthority.first) { result ->
            if (result.isFailure) failed()
            if (showResult) toast(if (result.isSuccess) "Nearby advertising stopped." else "Could not stop nearby advertising.")
        }) failed()
    }

    private val copyInvitation by lazy { Button(context).apply {
        text = "Copy link"
        visibility = android.view.View.GONE
        setOnClickListener {
            invitationLink?.let { link ->
                val clip = android.content.ClipData.newPlainText("Workspace invitation", link)
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                val clipboard = MapView.getMapView().context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(clip)
                toast("Invitation copied. Share it privately.")
            }
        }
    } }
    private val shareInvitation by lazy { Button(context).apply {
        text = "Share invitation"
        visibility = android.view.View.GONE
        setOnClickListener { shareCurrentInvitation() }
    } }
    private var nearbyDevices: List<NearbyEndpoint> = emptyList()
    private var nearbyDeviceMessage = ""
    private var discoveringDevices = false
    private var sendingNearby = false
    private var deviceDiscoveryGeneration = 0
    private var nearbyDeviceScope: Pair<String, Int>? = null
    private fun discoverNearbyDevices() {
        val slot = memberState?.active?.slot ?: return
        val number = selectedInvitationNumber ?: return
        val scope = slot to number
        if (discoveringDevices && nearbyDeviceScope == scope) return
        val service = nearbyInvitations ?: return
        if (nearbyDeviceScope != scope) nearbyDevices = emptyList()
        nearbyDeviceScope = scope
        val attempt = ++deviceDiscoveryGeneration
        val started = generation
        discoveringDevices = true
        nearbyDeviceMessage = "Looking for nearby Arachne devices…"
        showNearbyDevices()
        if (!service.discover { result ->
            if (generation != started || attempt != deviceDiscoveryGeneration) return@discover
            discoveringDevices = false
            result.onSuccess { nearbyDevices = it; nearbyDeviceMessage = "" }
                .onFailure { nearbyDeviceMessage = it.message ?: "Nearby discovery failed. Try refreshing." }
            showNearbyDevices()
        }) {
            discoveringDevices = false
            nearbyDeviceMessage = "Discovery could not start. Try refreshing."
            showNearbyDevices()
        }
    }
    private fun confirmNearbySend(endpoint: NearbyEndpoint) {
        val link = invitationLink ?: return
        val scope = nearbyDeviceScope ?: return
        val service = nearbyInvitations ?: return
        if (sendingNearby) return
        ArachneStyle.dialog(MapView.getMapView().context, pagePane).setTitle("Send private invitation?")
            .setMessage("${endpoint.label} is an unverified nearby endpoint. Confirm it with the intended recipient before sending this joining capability.")
            .setNegativeButton("Cancel", null).setPositiveButton("Send invitation") { _, _ ->
                if (memberState?.active?.slot != scope.first || selectedInvitationNumber != scope.second || invitationLink != link) {
                    toast("The invitation changed. Open its current details before sending."); return@setPositiveButton
                }
                val started = generation
                sendingNearby = true
                nearbyDeviceMessage = "Sending invitation…"
                showNearbyDevices()
                if (!service.send(endpoint, link) { result ->
                    if (generation != started) return@send
                    sendingNearby = false
                    if (nearbyDeviceScope == scope) {
                        nearbyDeviceMessage = if (result.isSuccess) "Invitation sent. The recipient still needs to review and join."
                            else "Could not send the invitation. Confirm the device is nearby and try again."
                        showNearbyDevices()
                    }
                }) {
                    sendingNearby = false
                    nearbyDeviceMessage = "The send operation could not start. Try again."
                    showNearbyDevices()
                }
            }.show()
    }
    private fun showNearbyDevices() {
        val ready = invitationLink != null
        replacePage(Screen.NEARBY_DEVICES, listOf(nearbyDeviceScope, discoveringDevices, sendingNearby, nearbyDeviceMessage, nearbyDevices, ready)) {
            listOf(Ui.intro(context, "Choose a device to receive this private invitation. Nearby labels do not prove who owns a device."),
                Ui.toolbar(context, Ui.label(context, "Nearby devices", UiType.LABEL),
                    Ui.action(context, if (discoveringDevices) "Searching…" else "Refresh", UiAction.QUIET) { discoverNearbyDevices() }
                        .apply { isEnabled = ready && !discoveringDevices && !sendingNearby }),
                Ui.notice(context, if (!ready) "This invitation is no longer available. Return to its details." else nearbyDeviceMessage.ifBlank { "Confirm the intended recipient before sending." }),
                if (nearbyDevices.isEmpty()) Ui.empty(context, if (discoveringDevices) "Looking nearby" else "No devices found",
                    "Keep Arachne open on the receiving device and confirm both devices can communicate on this network.")
                else Ui.stack(context, 0, *nearbyDevices.map { endpoint ->
                    Ui.link(context, endpoint.label, "Unverified nearby endpoint", Ui.Icon.WIFI) { if (ready && !sendingNearby) confirmNearbySend(endpoint) }
                        .apply { body.isEnabled = ready && !sendingNearby; more.isEnabled = body.isEnabled }
                }.toTypedArray()))
        }
    }
    private val showInvitationQr by lazy { Button(context).apply {
        text = "Show QR code"
        visibility = android.view.View.GONE
        setOnClickListener {
            val link = invitationLink ?: return@setOnClickListener
            val workspace = memberState?.active ?: return@setOnClickListener
            val host = MapView.getMapView().context
            val size = minOf(pageScroll.width, pageScroll.height).coerceIn(320, 720)
            val image = android.widget.ImageView(host).apply {
                setImageBitmap(invitationQr(link, size))
                contentDescription = "QR code invitation to ${workspace.name}"
                adjustViewBounds = true
                setPadding(ArachneStyle.dp(this, 16), ArachneStyle.dp(this, 16),
                    ArachneStyle.dp(this, 16), ArachneStyle.dp(this, 16))
            }
            ArachneStyle.dialog(host, pagePane).setTitle("Scan to join ${workspace.name}")
                .setView(image).setPositiveButton("Close", null).show()
        }
    } }
    private fun shareCurrentInvitation() {
        val link = invitationLink ?: return
        val workspace = memberState?.active ?: return
        val host = MapView.getMapView().context
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Join ${workspace.name}")
            putExtra(android.content.Intent.EXTRA_TITLE, "Invitation to ${workspace.name}")
            putExtra(android.content.Intent.EXTRA_TEXT, link)
        }
        try { host.startActivity(android.content.Intent.createChooser(send, "Invite to ${workspace.name}")) }
        catch (_: android.content.ActivityNotFoundException) {
            toast("No sharing app is available. Copy the invitation instead.")
        }
    }
    private val help by lazy { PluginHelp(context, MapView.getMapView().context, pagePane) }
    private enum class Screen { WORKSPACES, CREATE, JOIN, JOIN_REVIEW, NEARBY, WAITING, RECONNECT, WORKSPACE, RENAME, MEMBERS, MEMBER, APPROVALS,
        INVITATIONS, INVITE, INVITATION, NEARBY_DEVICES, FEEDS, FEED, DATA, CONNECTION, SETTINGS, APPEARANCE, NOTIFICATIONS, HELP, MANUAL, DIAGNOSTICS, PEERS, PEER, EVENTS, METRICS, SUPPORT, REPORT, ABOUT }
    private val formScreens = setOf(Screen.CREATE, Screen.JOIN, Screen.JOIN_REVIEW, Screen.INVITE, Screen.RENAME, Screen.RECONNECT, Screen.SUPPORT)
    private var screen = Screen.WORKSPACES
    private var formDialog: android.app.Dialog? = null
    private var formScroll: ScrollView? = null
    private var formScrollY = 0
    private var restoreForm: (() -> Unit)? = null

    private fun closeForm() {
        formClose = null
        formScroll?.let { formScrollY = it.scrollY }
        formScroll = null
        val dialog = formDialog
        formDialog = null
        restoreForm?.invoke()
        restoreForm = null
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
    }
    private val screens = mutableMapOf<Screen, LinearLayout>()
    private val formFields = mutableMapOf<Screen, LinearLayout>()
    private data class PageLocation(val screen: Screen, val scrollY: Int,
        val member: String? = null, val feed: String? = null, val invitation: Int? = null)
    private val history = mutableListOf<PageLocation>()
    private var selectedMemberId: String? = null
    private var selectedFeedTopic: String? = null
    private val workspaceTabs = linkedMapOf(Screen.MEMBERS to "Members", Screen.DATA to "Sharing",
        Screen.FEEDS to "Feeds", Screen.CONNECTION to "Connection")
    private val workspacePages = workspaceTabs.keys + setOf(Screen.WORKSPACE, Screen.RENAME, Screen.MEMBER,
        Screen.APPROVALS, Screen.INVITATIONS, Screen.INVITE, Screen.INVITATION, Screen.NEARBY_DEVICES, Screen.FEED, Screen.RECONNECT, Screen.WAITING, Screen.PEER)
    private val tabButtons = mutableMapOf<Screen, Button>()
    private val tabs by lazy { LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = ArachneStyle.background(this, UiTone.SURFACE)
        setPadding(ArachneStyle.dp(this, 8), 0, ArachneStyle.dp(this, 8), 0)
        workspaceTabs.forEach { (destination, label) ->
            val button = navigation(label, destination)
            tabButtons[destination] = button
            addView(button, LinearLayout.LayoutParams(-2, -2, 1f))
        }
    } }
    private val tabStrip by lazy { android.widget.HorizontalScrollView(context).apply {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        addView(tabs)
    } }
    private val appHeader by lazy { ArachneHeader(context, {}, ::showAppMenu) }
    private val pageTitle get() = appHeader.title
    private val pageBack get() = appHeader.back
    private val pageScroll by lazy { ScrollView(context) }
    private val pagePane: View get() = pageScroll.parent as? View ?: pageScroll
    private var broadcastsSelected = false
    private val cancelJoin by lazy { Button(context).apply {
        text = "Cancel join request"
        visibility = android.view.View.GONE
        setOnClickListener {
            val record = memberState?.active?.takeIf { it.joinPeer != null } ?: return@setOnClickListener
            ArachneStyle.dialog(MapView.getMapView().context, pagePane)
                .setTitle("Cancel joining ${record.name}?")
                .setMessage("This stops retrying and ends this saved join attempt on this device. You can join later with a new invitation.")
                .setNegativeButton("Keep waiting", null)
                .setPositiveButton("Cancel join") { _, _ -> performOperation("Cancel join") { workspaces?.cancelJoin(record.id) } }
                .show()
        }
    } }
    private val workspaceReady by lazy { Ui.stack(context, ArachneStyle.SECTION_GAP) }
    private fun showWaiting() {
        val state = memberState ?: return
        val record = state.active ?: return
        replacePage(Screen.WAITING, listOf(record.slot, record.name, record.memberName, record.preJoin, record.joinPeer != null, state.busy, state.message)) {
            if (record.joinPeer == null) listOf(Ui.empty(context, "Join attempt finished", "Open Workspaces to see the current state."),
                pageLink("Workspaces", "Return to your teams", Screen.WORKSPACES))
            else {
                (cancelJoin.parent as? android.view.ViewGroup)?.removeView(cancelJoin)
                listOf(Ui.stack(context, 6, ArachneSignal(context, "Waiting to join", ArachneSignal.State.WAITING),
                    Ui.label(context, if (record.preJoin) "Looking for a workspace member" else record.name, UiType.SECTION)),
                    Ui.notice(context, usefulMessage(state) ?: "Your join request is saved. Arachne will keep trying while you use ATAK."),
                    Ui.keyValue(context, "Your name", record.memberName ?: "Saved with your request"),
                    Ui.notice(context, "No workspace ATAK updates are shared until joining finishes. You can use other workspaces while you wait."),
                    Ui.actions(context, Ui.action(context, "Other workspaces") { navigate(Screen.WORKSPACES) },
                        Ui.action(context, if (state.busy) "Trying…" else "Retry now", UiAction.PRIMARY) {
                            if (workspaces?.retryJoin() != true) toast("Arachne is already working. Your saved request will retry automatically.")
                        }.apply { isEnabled = !state.busy && memberConnectionActive }), cancelJoin)
            }
        }
    }
    private fun showPageStatus(state: WorkspaceView? = memberState) {
        val message = usefulMessage(state).orEmpty()
        workspaceStatus.text = message
        if (screen == Screen.RENAME && (state?.busy == true || state?.attention == true || message == "Workspace name saved."))
            formMessage(Screen.RENAME, message, state?.attention == true)
        ArachneStyle.notice(workspaceStatus, if (state?.attention == true) UiTone.DANGER else UiTone.SECONDARY)
        workspaceStatus.visibility = if ((screen in workspaceTabs || screen in setOf(Screen.WORKSPACE, Screen.INVITATIONS, Screen.APPROVALS) || (screen == Screen.WORKSPACES && state?.attention == true))
            && message.isNotBlank()) android.view.View.VISIBLE else android.view.View.GONE
    }
    private fun usefulMessage(state: WorkspaceView?): String? {
        if (state == null) return null
        return state.message.lineSequence().filterNot {
            !state.busy && it in setOf("Workspace presence updated.", "Workspace members updated.",
                "Membership synchronization checked.", "Membership synchronized. Workspace ready.", "Workspace ready.", "Saved workspace opened.")
        }.joinToString("\n").takeIf { it.isNotBlank() }
    }
    private fun navigate(destination: Screen, remember: Boolean = true, scrollY: Int = 0) {
        pendingWorkspaceOpen = null
        workspaceSubmission?.takeIf { destination != it.form }?.let {
            formMessage(it.form, "")
            workspaceSubmission = null
        }
        if (destination == Screen.WORKSPACES) history.clear()
        else if (remember && destination != screen && !(destination in workspaceTabs && screen in workspaceTabs))
            history.add(PageLocation(screen, formScroll?.scrollY ?: pageScroll.scrollY, selectedMemberId, selectedFeedTopic, selectedInvitationNumber))
        screen = destination
        closeForm()
        (pageScroll.parent as? android.view.View)?.let { it.background = ArachneStyle.background(it) }
        screens.forEach { (key, view) -> view.visibility = if (key == destination) android.view.View.VISIBLE else android.view.View.GONE }
        pageTitle.text = when (destination) {
            Screen.WORKSPACES -> "Workspaces"
            Screen.CREATE -> "Create workspace"
            Screen.JOIN -> "Join workspace"
            Screen.JOIN_REVIEW -> "Review invitation"
            Screen.NEARBY -> "Nearby workspaces"
            Screen.WAITING -> "Waiting to join"
            Screen.RECONNECT -> "Reconnect"
            Screen.WORKSPACE -> "Workspace settings"
            Screen.RENAME -> "Rename workspace"
            Screen.MEMBERS, Screen.FEEDS, Screen.DATA, Screen.CONNECTION -> memberState?.active?.name ?: "Workspace"
            Screen.MEMBER -> "Member details"
            Screen.APPROVALS -> "Join requests"
            Screen.INVITATIONS -> "Invitations"
            Screen.INVITE -> "Create invitation"
            Screen.INVITATION -> "Invitation details"
            Screen.NEARBY_DEVICES -> "Nearby devices"
            Screen.FEED -> "Feed details"
            Screen.SETTINGS -> "Settings"
            Screen.APPEARANCE -> "Appearance"
            Screen.NOTIFICATIONS -> "Notifications"
            Screen.HELP -> "Help"
            Screen.MANUAL -> "Field manual"
            Screen.PEER -> "Peer details"
            Screen.DIAGNOSTICS -> "Diagnostics"
            Screen.PEERS -> "Peers"
            Screen.EVENTS -> "Event log"
            Screen.METRICS -> "Metrics"
            Screen.SUPPORT -> "Report a problem"
            Screen.REPORT -> "Report preview"
            Screen.ABOUT -> "About Arachne"
        }
        appHeader.subtitle.text = memberState?.active?.name.orEmpty()
        appHeader.subtitle.visibility = if (destination in setOf(Screen.MEMBER, Screen.APPROVALS, Screen.INVITATIONS,
            Screen.FEED, Screen.PEERS, Screen.PEER, Screen.INVITATION, Screen.INVITE, Screen.NEARBY_DEVICES, Screen.WORKSPACE, Screen.RENAME)) View.VISIBLE else View.GONE
        updatePageBack()
        refreshTabs()
        refreshContactTimer()
        // Builds member rows on entry to Members and releases them on exit.
        memberState?.let { showMembers(it) }
        if (destination == Screen.MEMBER) showMemberDetails()
        refreshDetailPage()
        appHeader.workspaceMenu.visibility = if (destination in workspaceTabs || destination in setOf(Screen.MEMBER, Screen.INVITATIONS, Screen.WORKSPACE, Screen.FEED, Screen.APPROVALS))
            android.view.View.VISIBLE else android.view.View.GONE
        appHeader.workspaceMenu.setOnClickListener { memberState?.active?.let(::showWorkspaceMenu) }
        showPageStatus()
        updateFormControls()
        // Forms share input views; move them only during explicit navigation.
        if (destination in setOf(Screen.CREATE, Screen.JOIN, Screen.JOIN_REVIEW)) {
            val fields = formFields.getValue(destination)
            fields.removeAllViews()
            val ordered = if (destination == Screen.CREATE) listOf(workspaceNameField, memberNameField, initialSharingRow)
                else if (destination == Screen.JOIN_REVIEW) listOf(memberNameField, initialSharingRow)
                else listOf(invitationField)
            ordered.forEach { field ->
                (field.parent as? android.view.ViewGroup)?.removeView(field)
                fields.addView(field, LinearLayout.LayoutParams(-1, -2))
            }
        }
        if (destination == Screen.RECONNECT) {
            (invitationField.parent as? android.view.ViewGroup)?.removeView(invitationField)
            formFields.getValue(destination).addView(invitationField, LinearLayout.LayoutParams(-1, -2))
        }
        pageScroll.post {
            if (screen == destination) {
                pageScroll.scrollTo(0, scrollY)
                if (destination in formScreens) showForm(destination, scrollY)
            }
        }
    }
    private fun refreshTabs() {
        tabStrip.visibility = if (screen in workspaceTabs) android.view.View.VISIBLE else android.view.View.GONE
        tabButtons.forEach { (destination, button) -> ArachneStyle.tab(button, destination == screen) }
    }
    private fun goBack(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        selectedMemberId = previous.member
        selectedFeedTopic = previous.feed
        selectedInvitationNumber = previous.invitation
        navigate(previous.screen, remember = false, scrollY = previous.scrollY)
        return true
    }
    private fun updatePageBack() {
        appHeader.showBack(history.isNotEmpty() && screen !in formScreens)
        val previous = history.lastOrNull()?.screen
        pageBack.contentDescription = "Back to " + when (previous) {
            Screen.WORKSPACES -> "Workspaces"
            Screen.MEMBERS -> "Members"
            Screen.MEMBER -> "Member details"
            Screen.DATA -> "Sharing"
            Screen.FEEDS -> "Feeds"
            Screen.CONNECTION -> "Connection"
            Screen.HELP -> "Help"
            Screen.SETTINGS -> "Settings"
            Screen.WORKSPACE -> "Workspace settings"
            Screen.RENAME -> "Rename workspace"
            Screen.APPEARANCE -> "Appearance"
            Screen.NOTIFICATIONS -> "Notifications"
            Screen.INVITATIONS -> "Invitations"
            Screen.APPROVALS -> "Join requests"
            Screen.PEERS -> "Peers"
            Screen.DIAGNOSTICS -> "Diagnostics"
            Screen.METRICS -> "Metrics"
            Screen.SUPPORT -> "Report a problem"
            else -> "previous page"
        }
        pageBack.setOnClickListener { goBack() }
    }
    private fun showForm(destination: Screen, scrollY: Int = formScrollY) {
        if (screen != destination || formDialog != null || pageScroll.width == 0 || pageScroll.height == 0) return
        // ATAK pans its activity for the keyboard. A separate resizable window
        // keeps this form's focused field and submit action in the visible area.
        val host = MapView.getMapView().context
        val body = screens.getValue(destination)
        val oldParent = body.parent as android.view.ViewGroup
        val oldIndex = oldParent.indexOfChild(body)
        oldParent.removeView(body)
        val action = when (destination) {
            Screen.CREATE -> createWorkspace
            Screen.JOIN -> joinWorkspace
            Screen.JOIN_REVIEW -> confirmJoin
            Screen.INVITE -> createInvitation
            Screen.RENAME -> renameWorkspace
            Screen.SUPPORT -> prepareReport
            else -> reconnectWorkspace
        }
        (action.parent as? android.view.ViewGroup)?.removeView(action)
        ArachneStyle.button(action, UiAction.PRIMARY)
        action.isSingleLine = false
        val back = Ui.icon(host, Ui.Icon.BACK, "Back") { goBack() }
        val header = Ui.label(host, pageTitle.text.toString(), UiType.TITLE)
        val scroll = object : ScrollView(host) {
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int {
                // Android normally reveals only the cursor. Include its persistent
                // field label in every focus/resize scroll calculation.
                val field = (body.findFocus() as? EditText)?.parent as? android.view.View
                if (field != null) {
                    val labeled = android.graphics.Rect(0, 0, field.width, field.height - field.paddingBottom)
                    offsetDescendantRectToMyCoords(field, labeled)
                    rect.union(labeled)
                }
                return super.computeScrollDeltaToGetChildRectOnScreen(rect)
            }
        }.apply {
            isFillViewport = true
            val inset = ArachneStyle.dp(this, ArachneStyle.pageInset((pageScroll.width / resources.displayMetrics.density).toInt()))
            body.setPadding(inset, ArachneStyle.dp(this, 14), inset, ArachneStyle.dp(this, 20))
        }
        formScroll = scroll
        val toolbar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(ArachneStyle.dp(this, 48), -2))
            addView(header, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = ArachneStyle.dp(header, 8)
                rightMargin = ArachneStyle.dp(header, 8)
            })
            background = ArachneStyle.engravedSurface(this)
            setPadding(ArachneStyle.dp(this, 8), ArachneStyle.dp(this, 2), ArachneStyle.dp(this, 16), ArachneStyle.dp(this, 2))
        }
        val scrolling = Ui.stack(host, 0, body)
        scroll.addView(scrolling)
        val footer = Ui.actions(host, Ui.action(host, "Cancel") { goBack() }.also { formClose = it }, action).apply {
            val inset = ArachneStyle.dp(this, ArachneStyle.pageInset((pageScroll.width / resources.displayMetrics.density).toInt()))
            setPadding(inset, ArachneStyle.dp(this, 8), inset, ArachneStyle.dp(this, 8))
            background = ArachneStyle.background(this, UiTone.SURFACE)
        }
        val form = Ui.page(host, toolbar, scroll, footer)
        val dialog = android.app.Dialog(host, ArachneStyle.dialogTheme())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(form)
        dialog.setOnCancelListener { goBack() }
        restoreForm = {
            (body.parent as? android.view.ViewGroup)?.removeView(body)
            (action.parent as? android.view.ViewGroup)?.removeView(action)
            body.setPadding(0, 0, 0, 0)
            body.addView(action, LinearLayout.LayoutParams(-1, -2))
            oldParent.addView(body, oldIndex)
        }
        dialog.setOnDismissListener { if (formDialog === dialog) closeForm() }
        formDialog = dialog
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ArachneStyle.pageColor))
            ArachneStyle.fitWindow(this, pagePane)
        }
        dialog.show()
        updateFormControls()
        scroll.post { if (formDialog === dialog) scroll.scrollTo(0, scrollY) }
    }
    private fun navigation(label: String, destination: Screen) = Button(context).apply {
        ArachneStyle.button(this)
        text = label; setOnClickListener { navigate(destination) }
    }

    private fun showAppMenu() {
        lateinit var dialog: android.app.AlertDialog
        fun item(label: String, description: String, icon: Ui.Icon, destination: Screen) =
            Ui.link(context, label, description, icon) {
                dialog.dismiss()
                if (screen != destination) navigate(destination)
            }
        val menu = Ui.stack(context, ArachneStyle.SECTION_GAP,
            Ui.section(context, "Workspace & app", Ui.stack(context, 0,
                item("Workspaces", "Open, join or create a workspace", Ui.Icon.PEOPLE, Screen.WORKSPACES),
                item("Settings", "Connections, storage and device preferences", Ui.Icon.SETTINGS, Screen.SETTINGS))),
            Ui.section(context, "Help & support", Ui.stack(context, 0,
                item("Help", "Field manual and reporting a problem", Ui.Icon.HELP, Screen.HELP),
                item("Diagnostics", "Connection state and event log", Ui.Icon.ACTIVITY, Screen.DIAGNOSTICS),
                item("Metrics", "Traffic and delivery counters", Ui.Icon.CHART, Screen.METRICS))))
        val inset = ArachneStyle.dp(menu, ArachneStyle.GROUP)
        menu.setPadding(inset, inset, inset, inset)
        if (android.os.Build.VERSION.SDK_INT >= 28) for (index in 0 until menu.childCount)
            (menu.getChildAt(index) as android.view.ViewGroup).getChildAt(0).isAccessibilityHeading = true
        val scroll = ScrollView(context).apply {
            contentDescription = "Arachne navigation"
            background = ArachneStyle.background(this)
            addView(menu)
        }
        dialog = ArachneStyle.dialog(MapView.getMapView().context, pagePane)
            .setView(scroll).setNegativeButton("Close", null).create()
        dialog.show()
    }

    private fun settingsPage() = Ui.stack(context, ArachneStyle.SECTION_GAP,
        Ui.section(context, "Connections & data", Ui.stack(context, 0,
            Ui.link(context, "Packages & storage", "TAK Server, caching and quota · choose a workspace", Ui.Icon.DOWNLOAD) {
                val records = memberState?.saved.orEmpty().filter { it.memberId != null && !it.ended }
                if (records.isEmpty()) toast("Join or create a workspace to configure packages and storage.")
                else ArachneStyle.dialog(MapView.getMapView().context, pagePane).setTitle("Choose workspace")
                    .setItems(records.map { it.name }.toTypedArray()) { _, index -> showResourceSettings(records[index]) }
                    .setNegativeButton("Cancel", null).show()
            },
            Ui.link(context, "ATAK connection ports", "Local stream and package ports · this device", Ui.Icon.LINK) {
                LocalTakPortSettings.show(context, pagePane)
            })),
        Ui.section(context, "Device preferences", Ui.stack(context, 0,
            pageLink("Appearance", "Theme, text size and reduced motion", Screen.APPEARANCE),
            pageLink("Notifications", "Action-needed alerts and Android permissions", Screen.NOTIFICATIONS))),
        Ui.section(context, "App", Ui.stack(context, 0,
            pageLink("About Arachne", "Version ${BuildConfig.VERSION_NAME}", Screen.ABOUT)),
            Button(context).apply {
                text = "Reset Arachne"
                contentDescription = "Reset all Arachne data on this device"
                ArachneStyle.button(this, UiAction.DESTRUCTIVE)
                setOnClickListener { confirmResetArachne(this) }
            })).apply {
                if (android.os.Build.VERSION.SDK_INT >= 28) for (index in 0 until childCount)
                    ((getChildAt(index) as android.view.ViewGroup).getChildAt(0)).isAccessibilityHeading = true
            }

    private fun appearanceSettings() = Ui.stack(context, ArachneStyle.SECTION_GAP,
        Ui.note(context, "Arachne on this device. ATAK's appearance is unchanged."),
        Ui.stack(context, 14,
            Ui.section(context, "Theme", singleChoice(context, "Theme", arrayOf("Dark · default", "Light · gray"),
                if (ArachneStyle.appearance == ArachneStyle.Appearance.GRAY) 1 else 0) {
                ArachneStyle.chooseAppearance(if (it == 1) ArachneStyle.Appearance.GRAY else ArachneStyle.Appearance.DARK)
                refreshAppearance()
            }),
            Ui.section(context, "Text size", singleChoice(context, "Text size", arrayOf("Standard", "Larger"),
                if (ArachneStyle.largerText) 1 else 0) { ArachneStyle.chooseLargerText(it == 1); refreshAppearance() }),
            Ui.note(context, "Both sizes also follow Android’s font scaling. Light mode uses gray backgrounds.")),
        Ui.toggle(context, "Reduce motion", "Keep state changes immediate. Also follows Android’s animation setting.", android.widget.Switch(context).apply {
            contentDescription = "Reduce motion"; isChecked = ArachneStyle.reducedMotion
            setOnCheckedChangeListener { _, checked -> ArachneStyle.chooseReducedMotion(checked); refreshAppearance() }
        }))

    private fun notificationSettings() = Ui.stack(context, ArachneStyle.SECTION_GAP,
        Ui.note(context, "Arachne alerts on this device. Changes save immediately."),
        Ui.toggle(context, "Action needed", "Quiet ATAK notifications for join requests and workspace failures. No names or locations appear in the notification.",
            android.widget.Switch(context).apply {
                contentDescription = "Action-needed notifications"
                isChecked = notificationPreferences.getBoolean("action-needed", false)
                var resetting = false
                setOnCheckedChangeListener { _, enabled ->
                    if (!resetting) {
                        if (!notificationPreferences.edit().putBoolean("action-needed", enabled).commit()) {
                            resetting = true; isChecked = !enabled; resetting = false
                            toast("Notification preference could not be saved.")
                        }
                        updateAttentionNotification(); refreshNotificationStatus()
                    }
                }
            }), notificationStatus, Ui.action(context, "ATAK notification settings", UiAction.QUIET) {
                val host = MapView.getMapView().context
                try { host.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, host.packageName)) }
                catch (_: android.content.ActivityNotFoundException) { toast("Open Android Settings, then ATAK notifications.") }
            })

    private fun refreshAppearance() {
        (pageScroll.parent as? android.view.View)?.let(ArachneStyle::applyTree)
        refreshTabs()
        refreshMemberToolbar()
        showPageStatus()
    }

    private data class PendingWorkspaceOpen(val slot: String, val returnScroll: Int)
    private var pendingWorkspaceOpen: PendingWorkspaceOpen? = null
    private fun openWorkspace(record: LocalWorkspace) {
        if (pendingWorkspaceOpen != null) return
        pendingWorkspaceOpen = PendingWorkspaceOpen(record.slot, pageScroll.scrollY)
        if (workspaces?.inspect(record) != true) {
            pendingWorkspaceOpen = null
            toast("Could not open this workspace. Try again.")
        }
    }

    private fun showWorkspaceMenu(record: LocalWorkspace) {
        val latest = connectionState ?: return
        val current = latest.connected.singleOrNull { it.active?.slot == record.slot }
        val activity = latest.pauses[record.slot]
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (screen == Screen.WORKSPACES) actions.add("Open workspace" to { openWorkspace(record) })
        else {
            actions.add("Workspace settings" to { navigate(Screen.WORKSPACE) })
            if (record.joinPeer == null && !record.ended) actions.add("Invitations" to { navigate(Screen.INVITATIONS) })
        }
        if (!record.ended && current != null) {
            actions.add("Pause workspace" to { performOperation("Pause workspace") { workspaces?.pause(record.id) }; Unit })
            val enabled = record.id.toList() in latest.publishing
            actions.add((if (enabled) "Turn workspace sharing off" else "Turn workspace sharing on") to {
                if (workspaces?.shareBroadcasts(record.id, !enabled) != true)
                    toast("Sharing preference could not be saved")
            })
        } else if (!record.ended && activity == "Paused") {
            actions.add("Resume workspace" to { if (performOperation("Resume workspace") { workspaces?.open(record) }) navigate(Screen.MEMBERS) })
        }
        when {
            record.ended -> actions.add("Remove from list" to { confirmDismiss(record) })
            current?.members?.any { it.self } == true -> {
                val ending = current.members.size == 1 && current.members.singleOrNull { it.self }?.administrator == true
                actions.add((if (ending) "End workspace" else "Leave workspace") to { confirmLeave(current) })
            }
            activity != "Pausing" -> actions.add("Remove from this device" to { confirmRemoveDevice(record) })
        }
        ArachneStyle.dialog(MapView.getMapView().context, pagePane).setTitle(record.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton("Cancel", null).show()
    }
    private val memberList by lazy { LinearLayout(context).apply { orientation = LinearLayout.VERTICAL } }
    private val workspaceList by lazy { LinearLayout(context).apply { orientation = LinearLayout.VERTICAL } }
    private val topicList by lazy { Ui.stack(context, ArachneStyle.SECTION_GAP) }
    private var lastTopicControls: List<Any?>? = null
    private fun showTopics(state: WorkspaceView, connected: Boolean) {
        val key = listOf(state.active?.slot, state.active?.name, state.topics, connected, state.busy, broadcastsSelected)
        if (key == lastTopicControls) return
        lastTopicControls = key
        topicList.removeAllViews()
        val record = state.active ?: return
        val publishing = android.widget.Switch(context).apply {
            contentDescription = "Share workspace-wide ATAK updates in ${record.name}"
            isChecked = broadcastsSelected
            isEnabled = connected && !state.busy
            setOnCheckedChangeListener { _, enabled ->
                if (workspaces?.shareBroadcasts(record.id, enabled) != true) {
                    setOnCheckedChangeListener(null); isChecked = broadcastsSelected
                    lastTopicControls = null
                    memberState?.let { showTopics(it, memberConnectionActive) }
                    toast("Sharing preference could not be saved.")
                }
            }
        }
        topicList.addView(Ui.toggle(context, "Share ATAK updates", "Publish workspace-wide data to ${record.name}.", publishing))
        if (!connected || !broadcastsSelected) topicList.addView(Ui.notice(context,
            if (!connected) "This workspace is paused. Resume it in Connection to change sharing."
            else "Workspace-wide sharing is off. Your choices below are saved; receiving and addressed chat are unchanged.", UiTone.WARNING))
        val table = Ui.stack(context, 0)
        val column = ArachneStyle.dp(table, 62)
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = ArachneStyle.dp(this, 34) }
        header.addView(Ui.label(context, "ATAK data", UiType.FIELD, UiTone.SECONDARY), LinearLayout.LayoutParams(0, -2, 1f))
        for (name in listOf("Share", "Receive")) header.addView(Ui.label(context, name, UiType.FIELD, UiTone.SECONDARY).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(column, -2))
        table.addView(header); table.addView(Ui.rule(context))
        for ((topic, name) in CotTopics.nativeLabels) {
            val choice = state.topics.singleOrNull { it.topic == topic } ?: continue
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ArachneStyle.dp(this, 58)
            }
            row.addView(Ui.label(context, name, UiType.LABEL), LinearLayout.LayoutParams(0, -2, 1f))
            for (publish in listOf(true, false)) {
                val checked = if (publish) choice.publish else choice.receive
                val control = android.widget.Switch(context).apply {
                    contentDescription = (if (publish) "Share " else "Receive ") + name + " in " + record.name
                    isChecked = checked; isEnabled = connected && !state.busy
                    ArachneStyle.choice(this)
                    // A centered 48 dp control keeps both columns aligned without shrinking the hit area.
                    setPadding(ArachneStyle.dp(this, 10), 0, 0, 0)
                    var resetting = false
                    setOnCheckedChangeListener { _, enabled ->
                        if (resetting) return@setOnCheckedChangeListener
                        if (workspaces?.selectTopics(record.id, setOf(topic), if (publish) enabled else null, if (publish) null else enabled) != true) {
                            resetting = true; isChecked = checked; resetting = false
                            toast("Could not change data sharing. Try again.")
                        }
                    }
                }
                row.addView(android.widget.FrameLayout(context).apply {
                    addView(control, android.widget.FrameLayout.LayoutParams(ArachneStyle.dp(this, 48), ArachneStyle.dp(this, 48), Gravity.CENTER))
                }, LinearLayout.LayoutParams(column, ArachneStyle.dp(row, 58)))
            }
            table.addView(row); table.addView(Ui.rule(context))
        }
        topicList.addView(table, LinearLayout.LayoutParams(-1, -2))
        topicList.addView(Ui.note(context, "Receiving applies to this workspace on this device. Direct and group chat follows ATAK recipients; chat history follows the Chat receive choice."))
    }

    private val feedList by lazy { Ui.stack(context, 0) }
    private var lastFeedControls: List<Any?>? = null
    private fun feedControl(record: LocalWorkspace, feed: FeedView, busy: Boolean) = android.widget.Switch(context).apply {
        contentDescription = "Receive ${feed.name} in ${record.name}"
        isChecked = feed.enabled
        isEnabled = !busy && memberConnectionActive && feed.format == "adsb.lol.aircraft.v1"
        ArachneStyle.choice(this)
        var resetting = false
        setOnCheckedChangeListener { _, enabled ->
            if (resetting) return@setOnCheckedChangeListener
            if (workspaces?.selectFeed(record.id, feed.topic, enabled) != true) {
                resetting = true; isChecked = feed.enabled; resetting = false
                toast("Could not change feed selection. Try again.")
            }
        }
    }
    private fun showFeeds(state: WorkspaceView) {
        val key = listOf(state.active?.slot, state.active?.name, state.feeds, state.busy, memberConnectionActive)
        if (lastFeedControls == key) return
        lastFeedControls = key
        feedList.removeAllViews()
        val record = state.active ?: return
        if (state.feeds.isEmpty()) feedList.addView(Ui.empty(context,
            if (memberConnectionActive) "No feeds advertised" else "Workspace paused",
            if (memberConnectionActive) "Feeds appear here when a publisher advertises them to this workspace."
            else "Resume in Connection to discover feeds. Saved subscriptions are retained."))
        for (feed in state.feeds) {
            val status = if (feed.available) "Available" else "Source unavailable"
            val row = ArachneRow(context, feed.name, "${feed.publisher} · ${feed.format}", status,
                if (feed.available) ArachneSignal.State.ACTIVE else ArachneSignal.State.WAITING,
                "Open ${feed.name}", { selectedFeedTopic = feed.topic; navigate(Screen.FEED) },
                leadingIcon = Ui.Icon.MAP, trailingControl = feedControl(record, feed, state.busy), separateStatus = true)
            feedList.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun pageLink(title: String, description: String, destination: Screen) = Ui.link(context, title, description, when (destination) {
        Screen.SETTINGS, Screen.WORKSPACE, Screen.APPEARANCE -> Ui.Icon.SETTINGS
        Screen.NOTIFICATIONS -> Ui.Icon.MAIL
        Screen.ABOUT -> Ui.Icon.HELP
        Screen.DIAGNOSTICS, Screen.EVENTS, Screen.CONNECTION -> Ui.Icon.ACTIVITY
        Screen.METRICS -> Ui.Icon.CHART
        Screen.PEERS, Screen.WORKSPACES, Screen.MEMBERS -> Ui.Icon.PEOPLE
        Screen.HELP -> Ui.Icon.HELP
        Screen.MANUAL -> Ui.Icon.BOOK
        Screen.SUPPORT -> Ui.Icon.MAIL
        else -> Ui.Icon.LINK
    }) { navigate(destination) }
    private val detailKeys = mutableMapOf<Screen, List<Any?>>()
    private fun replacePage(destination: Screen, key: List<Any?>, content: () -> List<View>) {
        val page = screens[destination] ?: return
        if (detailKeys[destination] == key) return
        detailKeys[destination] = key
        page.removeAllViews()
        content().forEach { view -> page.addView(view, LinearLayout.LayoutParams(-1, view.layoutParams?.height ?: -2)) }
    }
    private val latestPeerContact = mutableMapOf<String, Long>()
    private var connectionContact: TextView? = null
    private var peerContact: ArachneSignal? = null
    private var peerPathText: TextView? = null
    private var peerRttText: TextView? = null
    private fun contactAge(slot: String): String = latestPeerContact[slot]?.let {
        "${Ui.elapsed(android.os.SystemClock.elapsedRealtime() - it)} ago"
    } ?: "Not observed"
    private val trafficValues = mutableMapOf<Screen, List<TextView>>()
    private fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KiB", value / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MiB", value / (1024.0 * 1024.0))
    }
    private fun updateTraffic(destination: Screen, metrics: WorkspaceMetrics?) {
        trafficValues[destination]?.zip(listOf(metrics?.latest?.received, metrics?.latest?.sent))?.forEach { (view, value) ->
            view.text = value?.let(::bytes) ?: "—"
        }
    }
    private fun trafficCards(destination: Screen, metrics: WorkspaceMetrics?): View {
        val cards = arrayOf("Received", "Sent").mapIndexed { index, title -> ArachneStat(context, title).apply {
            ArachneStyle.text(value, UiType.STAT, if (index == 0) UiTone.RECEIVE else UiTone.SEND)
        } }
        trafficValues[destination] = cards.map { it.value }
        updateTraffic(destination, metrics)
        return Ui.stats(context, *cards.toTypedArray())
    }
    private val connectionBody by lazy { Ui.stack(context, ArachneStyle.SECTION_GAP) }
    private var connectionKey: List<Any?>? = null
    private fun showConnectionDetails(state: WorkspaceView, active: Boolean) {
        val record = state.active ?: return
        val activity = connectionState?.pauses?.get(record.slot)
        val key = listOf(record.slot, record.name, active, activity, state.busy, state.continuity, state.continuityChangedAt, broadcastsSelected, state.metricsError)
        if (connectionKey == key) return
        connectionKey = key
        connectionBody.removeAllViews()
        val status = if (active) "Active" else activity ?: "Opening"
        val signal = ArachneSignal(context, status, when {
            active -> ArachneSignal.State.ACTIVE
            activity == "Pause failed" -> ArachneSignal.State.FAILED
            activity == "Pausing" -> ArachneSignal.State.WAITING
            else -> ArachneSignal.State.NEUTRAL
        })
        connectionBody.addView(Ui.toolbar(context, Ui.stack(context, 6, signal,
            Ui.note(context, if (active) "Workspace connection is running." else "Membership and sharing choices are retained.")),
            Ui.action(context, if (active) "Pause" else "Resume") {
                if (active) performOperation("Pause workspace") { workspaces?.pause(record.id) }
                else if (workspaces?.open(record) != true) toast("Could not resume this workspace. Try again.")
            }.apply { isEnabled = !state.busy && !record.ended && activity !in setOf("Pausing", "Pause failed") }))
        connectionContact = Ui.label(context, contactAge(record.slot), UiType.SUPPORTING).apply { gravity = Gravity.END }
        connectionBody.addView(Ui.stack(context, 6,
            Ui.toolbar(context, Ui.note(context, "Latest peer contact"), checkNotNull(connectionContact)),
            Ui.note(context, "Elapsed since this device heard from another member. An active connection alone does not confirm delivery.")))
        val continuity = state.continuity ?: if (active) "No recovery is currently reported." else "Resume to check for missed data."
        val continuityViews = mutableListOf<View>(Ui.notice(context, continuity))
        state.continuityChangedAt?.let { continuityViews += Ui.note(context, "Updated ${android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it))}") }
        connectionBody.addView(Ui.section(context, "Recovery", *continuityViews.toTypedArray()))
        connectionBody.addView(Ui.section(context, "Inspect connection", Ui.stack(context, 0,
            pageLink("Peers", "Last contact and network paths to other members", Screen.PEERS),
            pageLink("Metrics", "Traffic history, pending work and transport measurements", Screen.METRICS),
            pageLink("Sharing", "Workspace-wide sharing is ${if (!active) "paused" else if (broadcastsSelected) "on" else "off"}", Screen.DATA))))
        state.metricsError?.let { connectionBody.addView(Ui.notice(context, it, UiTone.WARNING)) }
        connectionBody.addView(Ui.section(context, "Troubleshooting", Ui.stack(context, 0,
            pageLink("Diagnostics", "Device state, event log and support report", Screen.DIAGNOSTICS),
            pageLink("Reconnect via invitation", "Refresh the saved connection using this workspace’s invitation", Screen.RECONNECT))))
    }

    private val workspaceCount by lazy { Ui.note(context, "Loading…") }
    private val diagnosticCounts by lazy { Ui.stack(context, 0) }
    private fun showDiagnostics(connections: WorkspaceConnectionView) {
        workspaceCount.text = "${connections.connected.size} active · ${connections.selected.saved.size} saved"
        val key = listOf(connections.connected.size, connections.selected.saved.size, connections.pauses.values.toList())
        if (diagnosticKey == key) return
        diagnosticKey = key
        diagnosticCounts.removeAllViews()
        diagnosticCounts.addView(Ui.keyValue(context, "Active workspaces", connections.connected.size.toString()))
        diagnosticCounts.addView(Ui.keyValue(context, "Paused workspaces", connections.pauses.values.count { it == "Paused" }.toString()))
        diagnosticCounts.addView(Ui.keyValue(context, "Saved workspaces", connections.selected.saved.size.toString()))
        diagnosticsStatus.text = if (connections.pauses.values.any { it == "Pause failed" })
            "A workspace could not pause. Restart ATAK before resuming it." else "Showing current state from this device."
    }
    private var diagnosticKey: List<Any?>? = null

    private fun nativeCheck(type: String, method: String, marker: String, background: Boolean) {
        check(BuildConfig.DEBUG)
        // Implementations live in the debug source set, outside the release APK.
        val run = Runnable {
            runCatching { Class.forName("dev.arachne.atak.$type").getMethod(method).invoke(null) }
                .onSuccess { android.util.Log.i("Arachne", "${marker}_RESULT $it") }
                .onFailure { android.util.Log.e("Arachne", "${marker}_CHECK_FAILED", it) }
        }
        if (background) Thread(run, "arachne-native-check").start() else run.run()
    }

    /** Debug control lives in the debug source set, outside the release APK. */
    private fun debugControl(method: String) {
        check(BuildConfig.DEBUG)
        runCatching { Class.forName("dev.arachne.atak.DebugControl").getMethod(method, FabricPlugin::class.java).invoke(null, this) }
            .onFailure { Log.e("Arachne", "DEBUG_CONTROL_${method.uppercase()}_FAILED", it) }
    }

    /** Debug control only. WorkspaceConnections synchronizes its own snapshot. */
    internal fun debugWorkspaces(): WorkspaceConnections? { check(BuildConfig.DEBUG); return workspaces }

    private fun confirmResetArachne(button: Button) {
        val confirmation = EditText(context).apply {
            hint = "Type RESET"
            contentDescription = "Reset confirmation"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            ArachneStyle.text(this)
            setHintTextColor(ArachneStyle.secondaryColor)
        }
        val dialog = ArachneStyle.dialog(MapView.getMapView().context, pagePane)
            .setTitle("Reset Arachne?")
            .setMessage("This removes every Arachne workspace, identity, invitation, and setting from this device. ATAK data is not removed. Type RESET to continue. Restart ATAK afterward.")
            .setView(labeledInput("Type RESET to confirm", confirmation))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset Arachne", null)
            .create()
        dialog.setOnShowListener {
            val reset = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            reset.isEnabled = false
            confirmation.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                    reset.isEnabled = resetConfirmationAccepted(value)
                }
                override fun afterTextChanged(value: android.text.Editable?) = Unit
            })
            reset.setOnClickListener {
                dialog.dismiss()
                navigate(Screen.DIAGNOSTICS)
                resetArachne(button)
            }
        }
        dialog.show()
    }

    private fun resetArachne(button: Button) {
        button.isEnabled = false
        diagnosticsStatus.text = "Resetting Arachne…"
        resetState { result ->
            result.onSuccess {
                diagnosticsStatus.text = "Arachne reset complete. Create or join a workspace."
                toast("Arachne reset complete.")
            }.onFailure { error ->
                diagnosticsStatus.text = "Reset stopped safely: ${error.message}. Restart ATAK before trying again."
                button.isEnabled = true
            }
        }
    }

    /** Main thread. Closes and recreates Arachne's owners without restarting ATAK.
     * Debug control may retain one separate transport identity across the wipe. */
    internal fun resetState(preserveIdentity: String? = null, done: (Result<Unit>) -> Unit) {
        val host = MapView.getMapView().context
        val adapters = workspaceAdapters.also { workspaceAdapters = null }
        val owners = workspaces.also { workspaces = null }
        generation++
        adapters?.close()
        owners?.resetAndClose()
        Thread({
            val result = runCatching {
                check(adapters?.awaitClosed(20_000) != false) { "Native ATAK connections did not close" }
                check(owners?.awaitClosed(20, java.util.concurrent.TimeUnit.SECONDS) != false) { "Workspace connections did not close" }
                check(NativeHttpCredentials.recover(host) == 0) { "Native ATAK credentials could not be cleaned up" }
                EndpointIdentity.resetArachneKeys(preserveIdentity)
                val preferenceNames = mutableSetOf("arachne-display", "arachne-notifications", "arachne-invitation-labels", "fabric-broadcast-sharing", "fabric-workspace-connections",
                    "fabric-topic-choices", "fabric-feed-interests", "arachne-native-bindings-v1", "arachne-listener-ports-v1")
                java.io.File(host.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { file ->
                    val name = file.name.removeSuffix(".xml")
                    if (name.startsWith("arachne-native-chat-") || name.startsWith("arachne-native-receipts-") ||
                        name.startsWith("arachne-native-identities-") || name.startsWith("arachne-resource-cache-")) preferenceNames += name
                }
                for (name in preferenceNames)
                    check(host.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().clear().commit()) { "Could not clear $name" }
                val dataDirectory = java.io.File(host.noBackupFilesDir, "data-fabric")
                if (preserveIdentity == null) check(!dataDirectory.exists() || dataDirectory.deleteRecursively()) { "Could not remove data-fabric" }
                else dataDirectory.listFiles()?.forEach { target ->
                    if (target.name !in setOf("$preserveIdentity.bin", "$preserveIdentity.bin.bak", "$preserveIdentity.lock"))
                        check(target.deleteRecursively()) { "Could not remove ${target.name}" }
                }
                val targets = listOf(
                    java.io.File(host.noBackupFilesDir, "arachne-relay-only"),
                    java.io.File(host.noBackupFilesDir, "arachne-wan-only"),
                    java.io.File(host.filesDir, "fabric-fixture.json"),
                    java.io.File(host.filesDir, "fabric-tls-probe.json")
                )
                for (target in targets) check(!target.exists() || target.deleteRecursively()) { "Could not remove ${target.name}" }
            }
            result.onFailure { Log.e("Arachne", "RESET_FAILED", it) }
            mainHandler.post {
                val restarted = result.mapCatching { startWorkspaceState() }
                done(restarted)
            }
        }, "arachne-reset").start()
    }

    private fun showWorkspaces(connections: WorkspaceConnectionView) {
        val previousSavedSlots = memberState?.saved.orEmpty().map { it.slot }.toSet()
        connectionState = connections
        showDiagnostics(connections)
        val state = connections.selected
        for (view in connections.connected) {
            val slot = view.active?.slot ?: continue
            val seen = view.members.filterNot { it.self }.mapNotNull { it.lastContactElapsedMs }.maxOrNull() ?: continue
            latestPeerContact[slot] = maxOf(latestPeerContact[slot] ?: seen, seen)
        }
        latestPeerContact.keys.retainAll(state.saved.map { it.slot }.toSet())
        val removedFromList = memberState?.active?.slot?.let { previous -> state.saved.none { it.slot == previous } } == true
        val stopped = memberState?.active != null && state.active == null && !state.busy && state.attention
        val justJoined = memberState?.active?.slot == state.active?.slot && memberState?.active?.joinPeer != null &&
            state.active?.joinPeer == null && !state.busy && state.members.any { it.self }
        val activeConnection = state.active?.slot?.let { slot -> connections.connected.any { it.active?.slot == slot } } == true
        val validSlots = state.saved.map { it.slot }.toSet()
        (advertisedNearbyAuthorities.keys - validSlots + connections.pauses.keys).toList()
            .forEach { stopNearbyAdvertisement(false, it) }
        broadcastsSelected = state.active?.id?.toList() in connections.publishing
        if (screen in workspaceTabs) pageTitle.text = state.active?.name ?: "Workspace"
        appHeader.subtitle.text = state.active?.name.orEmpty()
        updatePageBack()
        showPageStatus(state)
        showTopics(state, activeConnection)
        showConnectionDetails(state, activeConnection)
        val previous = memberState
        val newInvitation = state.invitation != null && (previous?.active?.slot != state.active?.slot || previous?.invitation != state.invitation)
        if (newInvitation && previous?.active?.slot == state.active?.slot && !state.busy) {
            val newNumber = state.invitationKey?.let { key -> state.invitations.singleOrNull { it.key.contentEquals(key) }?.number }
            if (newNumber != null) issuedLinks[checkNotNull(state.active).slot to newNumber] = checkNotNull(state.invitation)
        }
        if (newInvitation) pageScroll.post {
            if (screen == Screen.INVITATIONS) {
                if (ArachneMotion.enabled()) pageScroll.smoothScrollTo(0, 0) else pageScroll.scrollTo(0, 0)
            }
        }
        val slot = state.active?.slot
        val mode = if (newInvitation) slot?.let { pendingNearbyModes.remove(it) } else null
        if (slot != null && mode != null) {
            startNearbyAdvertisement(checkNotNull(state.active), mode,
                checkNotNull(state.invitation), checkNotNull(state.invitationKey))
        }
        if (!state.busy && state.invitation == null) {
            state.active?.slot?.let { pendingNearbyModes.remove(it) }
        }
        inviteMember.isEnabled = activeConnection && !state.busy && state.active?.joinPeer == null && state.members.any { it.self && it.administrator }
        inviteMember.visibility = if (state.members.any { it.self && it.administrator }) android.view.View.VISIBLE else android.view.View.GONE
        nearbyAdvertisementStatus.visibility = inviteMember.visibility
        advertiseWorkspaceNearby.visibility = inviteMember.visibility
        val activeNearbyMode = state.active?.slot?.let { advertisedNearbyModes[it] }
        advertiseWorkspaceNearby.isEnabled = inviteMember.isEnabled || activeNearbyMode != null
        if (state.active?.slot?.let { pendingNearbyModes.containsKey(it) } == true) {
            nearbyAdvertisementStatus.text = "Creating the authorized invitation before advertising…"
            advertiseWorkspaceNearby.text = "Stop advertising nearby"
        } else if (activeNearbyMode != null) {
            nearbyAdvertisementStatus.text = "“${state.active?.name}” · ${activeNearbyMode.label} is advertised on this LAN. Other workspaces can remain advertised."
            advertiseWorkspaceNearby.text = "Stop advertising nearby"
        } else {
            nearbyAdvertisementStatus.text = "Nearby workspace discovery is off. Nearby devices cannot see this workspace."
            advertiseWorkspaceNearby.text = "Advertise workspace nearby"
        }
        renameWorkspace.visibility = if (state.members.any { it.self && it.administrator }) android.view.View.VISIBLE else android.view.View.GONE
        renameWorkspace.isEnabled = activeConnection && !state.busy && state.active?.joinPeer == null && state.members.any { it.self && it.administrator }
        settingsName.isEnabled = renameWorkspace.isEnabled
        val joining = state.active?.joinPeer != null
        cancelJoin.visibility = if (joining) View.VISIBLE else View.GONE
        cancelJoin.isEnabled = joining && !state.busy
        workspaceReady.visibility = if (joining) android.view.View.GONE else android.view.View.VISIBLE
        leaveWorkspace.visibility = if (state.active != null && state.active.joinPeer == null && !state.active.ended) android.view.View.VISIBLE else android.view.View.GONE
        leaveWorkspace.text = if (state.members.size == 1 && state.members.singleOrNull { it.self }?.administrator == true) "End workspace" else "Leave workspace"
        leaveWorkspace.isEnabled = activeConnection && !state.busy && state.members.any { it.self }
        reconnectWorkspace.isEnabled = activeConnection && !state.busy && state.active?.joinPeer == null
        memberConnectionActive = activeConnection
        showMembers(state)
        pendingWorkspaceOpen?.let { pending ->
            val record = state.active
            if (state.saved.none { it.slot == pending.slot }) {
                pendingWorkspaceOpen = null
                toast("This workspace is no longer in your saved list.")
            } else if (record?.slot == pending.slot) {
                history.clear(); history.add(PageLocation(Screen.WORKSPACES, pending.returnScroll))
                navigate(when { record.joinPeer != null -> Screen.WAITING; record.ended -> Screen.WORKSPACE; else -> Screen.MEMBERS }, remember = false)
            }
        }
        if ((removedFromList || stopped) && screen in workspacePages) {
            selectedMemberId = null; selectedFeedTopic = null
            navigate(Screen.WORKSPACES)
        }
        finishWorkspaceForm(state)
        updateFormControls(state)
        if (screen == Screen.MEMBER) showMemberDetails()
        // A saved join displayed in the waiting page becomes the Members home.
        if (screen == Screen.WAITING && justJoined) {
            navigate(Screen.MEMBERS, remember = false)
        }
        showFeeds(state)
        finishInvitationCreation(connections)
        showInvitations(state)
        recordEvents(connections)
        refreshDetailPage()
        val savedSlots = state.saved.map { it.slot }.toSet()
        issuedLinks.keys.removeAll { it.first !in savedSlots }
        val removedSlots = previousSavedSlots - savedSlots
        val removedLabels = if (removedSlots.isEmpty()) emptyList() else
            invitationLabels.all.keys.filter { it.substringBefore(':') in removedSlots }
        if (removedLabels.isNotEmpty()) invitationLabels.edit().apply {
            removedLabels.forEach { remove(it) }
        }.apply()
        workspaceRows.keys.filter { it !in savedSlots }.forEach { slot -> workspaceList.removeView(workspaceRows.remove(slot)) }
        if (state.saved.isEmpty()) {
            if (workspaceList.childCount == 0) workspaceList.addView(Ui.empty(context,
                "Bring your team together", "Create a workspace, or join using a private invitation from an administrator."))
        } else {
            for (index in workspaceList.childCount - 1 downTo 0) {
                if (workspaceList.getChildAt(index) !is ArachneRow) workspaceList.removeViewAt(index)
            }
        }
        state.saved.forEachIndexed { index, record ->
            val connected = connections.connected.singleOrNull { it.active?.slot == record.slot }
            val activity = connections.pauses[record.slot]
            val status = when {
                record.ended -> "Ended" to ArachneSignal.State.NEUTRAL
                record.joinPeer != null -> "Waiting" to ArachneSignal.State.WAITING
                activity == "Pause failed" -> "Pause failed" to ArachneSignal.State.FAILED
                activity == "Stopped" -> "Stopped" to ArachneSignal.State.FAILED
                connected != null -> "Active" to ArachneSignal.State.ACTIVE
                activity == "Paused" -> "Paused" to ArachneSignal.State.NEUTRAL
                else -> (activity ?: "Opening…") to ArachneSignal.State.NEUTRAL
            }
            val detail = when {
                record.ended -> "Saved on this device"
                record.joinPeer != null -> "Join request saved on this device"
                activity == "Pause failed" -> "Restart ATAK before resuming"
                activity == "Stopped" -> "Open Connection to resume saved state"
                connected == null -> "Membership and sharing settings retained"
                else -> {
                    val members = connected.members.size
                    (if (members > 0) "$members ${if (members == 1) "member" else "members"}" else "Loading members…") +
                        if (record.id.toList() in connections.publishing) " · Sharing on" else " · Sharing off"
                }
            }
            val openLabel = "${if (connected != null) "Open" else "View details"} ${record.name}"
            val row = workspaceRows.getOrPut(record.slot) {
                fun latest() = connectionState?.selected?.saved?.find { it.slot == record.slot }
                ArachneRow(context, record.name, detail, status.first, status.second, openLabel,
                    { latest()?.let(::openWorkspace) }, { latest()?.let(::showWorkspaceMenu) })
            }
            row.update(record.name, detail, status.first, status.second, openLabel)
            if (workspaceList.indexOfChild(row) != index) {
                (row.parent as? android.view.ViewGroup)?.removeView(row)
                workspaceList.addView(row, index, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }
    private val workspaceRows = mutableMapOf<String, ArachneRow>()
    private var connectionState: WorkspaceConnectionView? = null
    private var memberConnectionActive = false
    private var memberState: WorkspaceView? = null
    private val memberSearch by lazy { EditText(context).apply {
        hint = "Search members"
        contentDescription = "Search members"
        isSingleLine = true
        ArachneStyle.text(this)
        setHintTextColor(ArachneStyle.secondaryColor)
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                memberState?.let { showMembers(it) }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    } }
    private val memberRows = mutableMapOf<String, ArachneRow>()
    private val MEMBER_ROWS = 100
    private val requestsButton by lazy { navigation("Requests", Screen.APPROVALS).apply {
        visibility = View.GONE; Ui.actionIcon(this, Ui.Icon.PEOPLE)
    } }
    private val memberInvite by lazy { navigation("Invite", Screen.INVITATIONS).apply { Ui.actionIcon(this, Ui.Icon.PLUS) } }
    private fun refreshMemberToolbar() {
        val widthDp = pageScroll.width / pageScroll.resources.displayMetrics.density
        val compact = widthDp in 1f..400f || ArachneStyle.largerText
        val count = memberState?.approvals?.size ?: 0
        val label = if (compact) count.toString() else "Requests $count"
        requestsButton.text = android.text.SpannableString(label).apply {
            if (count > 0) setSpan(android.text.style.ForegroundColorSpan(ArachneStyle.color(UiTone.WARNING)),
                label.length - count.toString().length, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        requestsButton.contentDescription = "Review $count join requests"
        memberInvite.text = if (compact) "" else "Invite"
        memberInvite.contentDescription = "Invite people"
        for (button in listOf(requestsButton, memberInvite)) {
            button.minimumWidth = ArachneStyle.dp(button, 48)
            Ui.actionIcon(button, if (button === requestsButton) Ui.Icon.PEOPLE else Ui.Icon.PLUS)
        }
    }
    private fun memberRole(member: WorkspaceMember) = if (member.service) "Service" else if (member.administrator) "Administrator" else "Member"
    private fun memberPresence(member: WorkspaceMember): Pair<String, ArachneSignal.State> {
        if (member.self) return "This device" to ArachneSignal.State.NEUTRAL
        val now = android.os.SystemClock.elapsedRealtime()
        val seen = member.lastContactElapsedMs ?: return "Not seen" to ArachneSignal.State.NEUTRAL
        val fresh = member.presence == "reachable" && now < (member.reachableUntilElapsedMs ?: 0)
        return "${Ui.elapsed(now - seen)} ago" to
            if (fresh) ArachneSignal.State.ACTIVE else ArachneSignal.State.WAITING
    }
    private val contactTick = object : Runnable {
        override fun run() {
            if (dropdown?.isVisible != true || screen !in setOf(Screen.MEMBERS, Screen.MEMBER, Screen.PEERS, Screen.PEER, Screen.CONNECTION)) return
            memberState?.members?.forEach { member ->
                if (member.id !in memberRows && member.id !in peerRows && member.id != selectedMemberId) return@forEach
                val presence = memberPresence(member)
                memberRows[member.id]?.let { row ->
                    row.updateStatus(presence.first, presence.second)
                    row.body.contentDescription = "Open member ${member.name ?: "Name not received"}, ${memberRole(member)}, ID ${member.id.take(8)}, last contact ${presence.first}"
                }
                peerRows[member.id]?.updateStatus(presence.first, presence.second)
                if (screen == Screen.MEMBER && member.id == selectedMemberId) memberSignal.update(presence.first, presence.second)
                if (screen == Screen.PEER && member.id == selectedMemberId) peerContact?.update(presence.first, presence.second)
            }
            if (screen == Screen.CONNECTION) memberState?.active?.slot?.let { connectionContact?.text = contactAge(it) }
            mainHandler.postDelayed(this, 1000)
        }
    }
    private fun refreshContactTimer() {
        mainHandler.removeCallbacks(contactTick)
        val watched = screen in setOf(Screen.MEMBERS, Screen.MEMBER, Screen.PEERS, Screen.PEER, Screen.CONNECTION) && dropdown?.isVisible == true
        WorkspaceController.presenceWatched = watched
        if (watched) mainHandler.post(contactTick)
    }
    private fun showMembers(state: WorkspaceView) {
        val changedWorkspace = memberState?.active?.slot != state.active?.slot
        memberState = state
        if (changedWorkspace) { memberRows.clear(); memberList.removeAllViews(); memberSearch.text.clear() }
        for (index in memberList.childCount - 1 downTo 0) {
            if (memberList.getChildAt(index) !is ArachneRow) memberList.removeViewAt(index)
        }
        val scope = state.active?.id
        val query = memberSearch.text.toString().trim()
        // Rows exist only while the Members page shows, and at most
        // MEMBER_ROWS of them: each row is a small view tree, and a 500-member
        // roster otherwise keeps 500 of them for the whole session.
        val showing = screen == Screen.MEMBERS
        val matching = if (!showing) emptyList() else state.members.filter { it.name.orEmpty().contains(query, true) || it.id.contains(query, true) }
        val shown = matching.take(MEMBER_ROWS)
        val shownIds = shown.mapTo(HashSet()) { it.id }
        memberRows.keys.filter { it !in shownIds }.forEach { id -> memberList.removeView(memberRows.remove(id)) }
        if (scope == null) return
        var index = 0
        refreshMemberToolbar()
        requestsButton.visibility = if (state.members.any { it.self && it.administrator } && state.approvals.isNotEmpty()) View.VISIBLE else View.GONE
        memberInvite.visibility = if (state.members.any { it.self && it.administrator }) View.VISIBLE else View.GONE
        memberInvite.isEnabled = memberConnectionActive && !state.busy
        if (!showing) return
        if (matching.isEmpty()) memberList.addView(Ui.label(context,
            if (state.members.isNotEmpty()) "No matching members" else if (memberConnectionActive) "Loading members…"
            else "Resume this workspace to load its members.", tone = UiTone.SECONDARY))
        val nameCounts = HashMap<String?, Int>()
        for (member in state.members) nameCounts[member.name] = (nameCounts[member.name] ?: 0) + 1
        shown.forEach { member ->
            val label = (member.name ?: "Name not received") + if (member.self) " (you)" else ""
            val presence = memberPresence(member)
            val description = "Open member $label, ${memberRole(member)}, ID ${member.id.take(8)}, ${presence.first}"
            val duplicate = (nameCounts[member.name] ?: 0) > 1
            val detail = memberRole(member) + if (duplicate) " · ${member.id.take(8)}" else ""
            val row = memberRows.getOrPut(member.id) {
                ArachneRow(context, label, detail, presence.first, presence.second, description, {
                    selectedMemberId = member.id
                    navigate(Screen.MEMBER)
                })
            }
            row.update(label, detail, presence.first, presence.second, description)
            if (memberList.indexOfChild(row) != index) {
                (row.parent as? android.view.ViewGroup)?.removeView(row)
                memberList.addView(row, index, LinearLayout.LayoutParams(-1, -2))
            }
            index++
        }
        if (matching.size > shown.size) memberList.addView(Ui.label(context,
            "Showing ${shown.size} of ${matching.size} members. Search by name or ID to find others.", tone = UiTone.SECONDARY))
    }
    private val memberHeading by lazy { Ui.label(context, "", UiType.SECTION) }
    private val memberRoleLabel by lazy { Ui.note(context, "") }
    private val memberAvatar by lazy { ArachneAvatar(context, "?") }
    private val memberInformation by lazy { Ui.stack(context, ArachneStyle.GROUP) }
    private val memberSignal by lazy { ArachneSignal(context, "Not seen", ArachneSignal.State.NEUTRAL) }
    private val memberActions by lazy { Ui.stack(context, ArachneStyle.GROUP) }
    private var memberActionsKey: List<Any?>? = null
    private fun showMemberDetails() {
        val state = memberState ?: return
        val record = state.active ?: return
        val member = state.members.singleOrNull { it.id == selectedMemberId }
        if (member == null) {
            memberHeading.text = "Member unavailable"
            memberRoleLabel.text = ""
            memberInformation.removeAllViews()
            memberInformation.addView(Ui.empty(context, "Roster changed", "This member is no longer in the current roster. Return to Members for the latest list."))
            memberSignal.visibility = android.view.View.GONE
            memberActions.removeAllViews(); memberActionsKey = null
            return
        }
        memberSignal.visibility = android.view.View.VISIBLE
        memberHeading.text = (member.name ?: "Name not received") + if (member.self) " (you)" else ""
        memberAvatar.update(member.name ?: "?")
        memberRoleLabel.text = memberRole(member)
        memberPresence(member).let { memberSignal.update(it.first, it.second) }
        val admin = state.members.any { it.self && it.administrator }
        val admins = state.members.count { it.administrator }
        val key = listOf(record.slot, record.name, member.id, member.name, member.administrator, member.self, admin, admins, state.members.size, state.busy, memberConnectionActive)
        if (memberActionsKey == key) return
        memberActionsKey = key
        memberInformation.removeAllViews()
        memberInformation.addView(Ui.keyValue(context, "Workspace", record.name))
        memberInformation.addView(Ui.details(context, "member identity",
            Ui.identity(context, "Member identity", member.id),
            Ui.note(context, "Names are self-chosen. Compare this identity when distinguishing people with the same name.")))
        memberActions.removeAllViews()
        state.attentionReason?.takeIf { it.contains("missing local join state") }?.let {
            memberActions.addView(Ui.notice(context, it, UiTone.WARNING))
        }
        val actions = mutableListOf<Pair<String, String>>()
        if (admin && memberConnectionActive) {
            if (!member.administrator) actions.add("Make administrator" to "promote")
            else if (admins > 1) actions.add("Remove administrator role" to "demote")
            if (!member.self && (!member.administrator || admins > 1)) actions.add("Remove member" to "remove")
        }
        if (member.self && memberConnectionActive) actions.add((if (state.members.size == 1 && member.administrator) "End workspace" else "Leave workspace") to "leave")
        if (actions.isNotEmpty()) memberActions.addView(Ui.label(context, "Membership", UiType.LABEL))
        actions.forEach { (label, operation) ->
            memberActions.addView(Ui.action(context, label,
                if (operation in setOf("remove", "leave")) UiAction.DESTRUCTIVE else UiAction.SECONDARY) {
                // Resolve the current roster again before asking for confirmation.
                val latest = memberState?.takeIf { it.active?.slot == record.slot } ?: return@action
                val current = latest.members.singleOrNull { it.id == member.id } ?: return@action
                if (operation == "leave") confirmLeave(latest)
                else ArachneStyle.dialog(MapView.getMapView().context, pagePane).setTitle(label)
                    .setMessage("$label for ${current.name ?: "this member"} (${current.id.take(8)}) in ${record.name}?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Confirm") { _, _ -> performOperation(label) { workspaces?.manage(record.id, current.identity(), operation) } }.show()
            }.apply { isEnabled = !state.busy }, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun showWorkspaceSettings() {
        val state = memberState ?: return
        val record = state.active ?: return
        val self = state.members.singleOrNull { it.self }
        if (workspaceNameSlot != record.slot || !settingsName.hasFocus()) {
            workspaceNameSlot = record.slot
            if (settingsName.text.toString() != record.name) settingsName.setText(record.name)
        }
        val key = listOf(record.slot, record.name, record.ended, self?.id, self?.administrator, memberConnectionActive, state.busy)
        if (workspaceSettingsKey == key) return
        workspaceSettingsKey = key
        workspaceReady.removeAllViews()
        workspaceReady.addView(Ui.section(context, "Team",
            Ui.keyValue(context, "Name", record.name),
            Ui.keyValue(context, "Your role", self?.let(::memberRole) ?: "Not loaded")))
        if (self?.administrator == true && !record.ended) workspaceReady.addView(
            pageLink("Rename workspace", "Change the shared name for everyone", Screen.RENAME))
        workspaceReady.addView(Ui.section(context, "On this device", Ui.stack(context, 0,
            Ui.link(context, "Packages & storage", "TAK Server integration, caching and device storage", Ui.Icon.DOWNLOAD) { showResourceSettings(record) },
            pageLink("Sharing", "Choose which ATAK data this device shares and receives", Screen.DATA),
            pageLink("Connection", "Connection state, recovery, pause and resume", Screen.CONNECTION))))
        workspaceReady.addView(Ui.details(context, "workspace identity",
            Ui.identity(context, "Workspace identity", record.id.joinToString("") { "%02x".format(it.toInt() and 255) }),
            Ui.note(context, "This identity stays the same when the workspace name changes.")))
        (leaveWorkspace.parent as? android.view.ViewGroup)?.removeView(leaveWorkspace)
        if (record.ended) workspaceReady.addView(Ui.action(context, "Remove from list", UiAction.DESTRUCTIVE) { confirmDismiss(record) })
        else {
            workspaceReady.addView(Ui.section(context, "Leave or remove", Ui.note(context, "Leave updates the team roster. Removing from this device deletes its local workspace state. Both ask for confirmation."),
                leaveWorkspace,
                Ui.action(context, "Remove from this device", UiAction.QUIET) { confirmRemoveDevice(record) }))
        }
    }
    private var workspaceSettingsKey: List<Any?>? = null

    private fun showResourceSettings(record: LocalWorkspace) = WorkspaceResourceSettings.show(context, pagePane, record,
        { workspaceAdapters?.resources(record.id) }, { workspaceAdapters?.resourcePolicyChanged(record.id) })

    private fun showApprovals() {
        val state = memberState ?: return
        val record = state.active ?: return
        val admin = state.members.any { it.self && it.administrator }
        replacePage(Screen.APPROVALS, listOf(record.slot, admin, state.approvals.map { listOf(it.attemptId.toList(), it.name) }, state.busy, memberConnectionActive)) {
            if (!admin) listOf(Ui.empty(context, "Administrator access", "Only workspace administrators can review join requests."))
            else if (state.approvals.isEmpty()) listOf(Ui.empty(context, "No pending requests", "New requests appear here when someone uses a personal invitation that requires review."))
            else listOf(Ui.note(context, "Review the person and device before admitting them to ${record.name}."),
                Ui.stack(context, 0, *state.approvals.map { pending ->
                    Ui.link(context, pending.name, "Request ${pending.endpoint.take(4).joinToString("") { "%02x".format(it.toInt() and 255) }} · Review access") {
                        val latest = memberState?.takeIf { it.active?.slot == record.slot && !it.busy } ?: return@link
                        val current = latest.approvals.find { it.attemptId.contentEquals(pending.attemptId) } ?: return@link
                        if (latest.members.none { it.self && it.administrator }) return@link
                        ArachneStyle.dialog(MapView.getMapView().context, pagePane)
                            .setTitle("Review ${current.name}")
                            .setMessage("Approve this device to join ${record.name}, or decline this request.")
                            .setNeutralButton("Not now", null)
                            .setNegativeButton("Decline") { _, _ -> performOperation("Decline request") { workspaces?.declineInvitation(record.id, current) } }
                            .setPositiveButton("Approve") { _, _ -> performOperation("Approve request") { workspaces?.approveInvitation(record.id, current) } }.show()
                    }.apply {
                        body.isEnabled = !state.busy && memberConnectionActive
                        more.isEnabled = body.isEnabled
                    }
                }.toTypedArray()))
        }
    }

    private fun showFeedDetails() {
        val state = memberState ?: return
        val record = state.active ?: return
        val feed = state.feeds.find { it.topic == selectedFeedTopic }
        replacePage(Screen.FEED, listOf(record.slot, record.name, feed, state.busy, memberConnectionActive)) {
            if (feed == null) listOf(Ui.empty(context, "Feed unavailable", "This feed is no longer in the current catalog. Return to Feeds for the latest list."))
            else listOf(
                Ui.stack(context, 6, Ui.label(context, feed.name, UiType.SECTION),
                    ArachneSignal(context, if (feed.available) "Available" else "Source unavailable",
                        if (feed.available) ArachneSignal.State.ACTIVE else ArachneSignal.State.WAITING)),
                Ui.toggle(context, "Receive this feed", "Save this device’s subscription in ${record.name}.", feedControl(record, feed, state.busy)),
                if (feed.format == "adsb.lol.aircraft.v1") Ui.action(context, "Show on ATAK map", UiAction.PRIMARY) {
                    val latest = memberState?.takeIf { it.active?.slot == record.slot }?.feeds?.find { it.topic == feed.topic }
                    val adapters = workspaceAdapters
                    if (latest?.enabled == true && adapters != null) adapters.showFeed(record.id, feed.topic) { shown ->
                        if (!shown) toast("No current observations have arrived for this feed. Keep receiving and try again.")
                    } else toast("This feed is no longer active. Check its receive setting.")
                }.apply { isEnabled = feed.enabled && memberConnectionActive }
                else Ui.notice(context, "This feed’s map format is not supported by this version of Arachne.", UiTone.WARNING),
                Ui.stack(context, 0, Ui.keyValue(context, "Publisher", feed.publisher), Ui.keyValue(context, "Coverage", feed.coverage)),
                Ui.section(context, "Source & attribution", Ui.note(context, feed.source), Ui.note(context, feed.attribution)),
                Ui.details(context, "format details", Ui.keyValue(context, "Format", feed.format)))
        }
    }

    private val peerRows = mutableMapOf<String, ArachneRow>()
    private fun showPeers() {
        val state = memberState ?: return replacePage(Screen.PEERS, listOf("loading")) {
            listOf(Ui.empty(context, "Loading workspace state", "Peer details will appear when Arachne finishes opening its saved workspaces."))
        }
        val record = state.active
        val peers = state.members.filterNot { it.self }
        replacePage(Screen.PEERS, listOf(record?.slot, record?.name, peers.map { listOf(it.id, it.name, it.administrator, it.service) }, memberConnectionActive)) {
            peerRows.clear()
            if (record == null) listOf(Ui.empty(context, "Select a workspace", "Peer details belong to a workspace. Open one from Workspaces."), pageLink("Workspaces", "Choose a team", Screen.WORKSPACES))
            else if (peers.isEmpty()) listOf(Ui.empty(context, if (memberConnectionActive) "No other members" else "Workspace paused",
                if (memberConnectionActive) "Peer details appear when other members join ${record.name}." else "Resume this workspace to load its members."))
            else listOf(Ui.note(context, "${record.name} · Last contact is elapsed time since this device heard from the peer."),
                // Bounded like Members: the most recently heard peers first when capped.
                Ui.stack(context, 0, *(if (peers.size <= MEMBER_ROWS) peers else
                    peers.sortedByDescending { it.lastContactElapsedMs ?: Long.MIN_VALUE }.take(MEMBER_ROWS)).map { peer ->
                    val presence = memberPresence(peer)
                    ArachneRow(context, peer.name ?: "Name not received", memberRole(peer), presence.first, presence.second,
                        "Open peer ${peer.name ?: peer.id.take(8)}", { selectedMemberId = peer.id; navigate(Screen.PEER) })
                        .also { peerRows[peer.id] = it }
                }.toTypedArray()), Ui.notice(context, (if (peers.size > MEMBER_ROWS) "Showing the $MEMBER_ROWS most recently heard of ${peers.size} peers. " else "") +
                    "Open a peer for its current path and round-trip estimate. Contact timers are separate from network latency."))
        }
    }

    private fun showPeerDetails() {
        val state = memberState ?: return
        val record = state.active ?: return
        val peer = state.members.find { it.id == selectedMemberId && !it.self }
        val paths = state.metrics?.paths.orEmpty().filter { it.member == peer?.id }
        replacePage(Screen.PEER, listOf(record.slot, record.name, peer?.id, peer?.name, peer?.administrator, memberConnectionActive, state.metricsError)) {
            peerContact = null
            peerPathText = null; peerRttText = null
            if (peer == null) listOf(Ui.empty(context, "Peer unavailable", "Return to Peers for the current workspace roster."))
            else {
                val presence = memberPresence(peer)
                peerContact = ArachneSignal(context, presence.first, presence.second)
                peerPathText = Ui.label(context, "", UiType.SUPPORTING)
                peerRttText = Ui.label(context, "", UiType.SUPPORTING)
                listOf(Ui.stack(context, 6, Ui.label(context, peer.name ?: "Name not received", UiType.SECTION),
                    Ui.note(context, memberRole(peer)), checkNotNull(peerContact)),
                    Ui.stack(context, 0, Ui.keyValue(context, "Workspace", record.name),
                        Ui.keyValue(context, "Network path", checkNotNull(peerPathText)),
                        Ui.keyValue(context, "Transport RTT", checkNotNull(peerRttText))),
                    Ui.action(context, "Member details", UiAction.SECONDARY) { navigate(Screen.MEMBER) },
                    Ui.details(context, "connection details", Ui.identity(context, "Peer identity", peer.id),
                        Ui.note(context, "RTT is the fastest current transport round trip. Idle connections have no active path. Contact age does not measure latency."))) +
                    listOfNotNull(state.metricsError?.let { Ui.notice(context, it, UiTone.WARNING) })
            }
        }
        peerPathText?.text = pathLabel(paths, state.metrics)
        peerRttText?.text = paths.minOfOrNull { it.rttMs }?.let { "$it ms" }
            ?: if (state.metrics == null) "Awaiting measurements" else "No active path"
    }

    private data class UiEvent(val time: Long, val workspace: String, val message: String, val attention: Boolean = false)
    private var attentionOnly = false
    private val observedAttention = mutableSetOf<String>()
    private val events = java.util.ArrayDeque<UiEvent>()
    private val lastEventMessages = mutableMapOf<String, String>()
    private val notificationPreferences by lazy { MapView.getMapView().context.getSharedPreferences("arachne-notifications", android.content.Context.MODE_PRIVATE) }
    private val notificationStatus by lazy { Ui.note(context, "") }
    private var attentionNotificationId: Int? = null
    private var currentAttentionKeys = emptySet<String>()
    private var notifiedAttentionKeys = emptySet<String>()
    private val attentionAction = "dev.arachne.atak.SHOW_ATTENTION"
    private val attentionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action != attentionAction) return
            showPane(); attentionOnly = true; navigate(Screen.EVENTS)
            attentionNotificationId?.let { NotificationUtil.getInstance().clearNotification(it) }
        }
    }
    private fun refreshNotificationStatus() {
        val manager = MapView.getMapView().context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationStatus.text = if (manager.areNotificationsEnabled()) "Delivery also follows ATAK’s Android notification settings."
            else "Android has blocked ATAK notifications. Enable them in ATAK notification settings below."
    }
    private fun updateAttentionNotification() {
        val enabled = notificationPreferences.getBoolean("action-needed", false)
        val notifications = NotificationUtil.getInstance()
        if (!enabled || currentAttentionKeys.isEmpty()) {
            attentionNotificationId?.let { notifications.clearNotification(it) }
            notifiedAttentionKeys = emptySet()
        } else if (notifiedAttentionKeys != currentAttentionKeys) {
            val id = attentionNotificationId ?: notifications.reserveNotifyId().also { attentionNotificationId = it }
            notifications.postNotification(id, NotificationUtil.GeneralIcon.STATUS_YELLOW.getID(), NotificationUtil.YELLOW,
                "Arachne · action needed", "${currentAttentionKeys.size} workspace items need attention. Open Arachne to review.",
                android.content.Intent(attentionAction), true)
            notifiedAttentionKeys = currentAttentionKeys.toSet()
        }
    }
    private fun recordEvents(connections: WorkspaceConnectionView) {
        // This bounded session log records observed status messages, not transport telemetry.
        for (state in (connections.connected + connections.selected + connections.attention).distinctBy { it.active?.slot ?: it.statusWorkspace?.slot }) {
            val record = state.active ?: state.statusWorkspace ?: continue
            val message = usefulMessage(state) ?: continue
            if (lastEventMessages.put(record.slot, message) == message) continue
            events.addFirst(UiEvent(System.currentTimeMillis(), record.name, message, state.attention))
            while (events.size > 100) events.removeLast()
        }
        val currentAttention = mutableSetOf<String>()
        for (state in (connections.connected + connections.selected + connections.attention).distinctBy { it.active?.slot ?: it.statusWorkspace?.slot }) {
            val record = state.active ?: state.statusWorkspace ?: continue
            if (state.attention) {
                val key = "${record.slot}:failure:${state.attentionReason ?: state.message}"
                currentAttention += key
                if (observedAttention.add(key)) toast(state.attentionReason ?: state.message)
            }
            for (pending in state.approvals) {
                val key = "${record.slot}:request:${pending.attemptId.toList()}"
                currentAttention += key
                if (observedAttention.add(key)) events.addFirst(UiEvent(System.currentTimeMillis(), record.name, "Join request awaiting review: ${pending.name}", true))
            }
        }
        for ((slot, status) in connections.pauses) if (status == "Pause failed") {
            val key = "$slot:pause-failed"
            currentAttention += key
            if (observedAttention.add(key)) events.addFirst(UiEvent(System.currentTimeMillis(),
                connections.selected.saved.find { it.slot == slot }?.name ?: "Workspace", "Pause failed. Restart ATAK before resuming.", true))
        }
        observedAttention.retainAll(currentAttention)
        currentAttentionKeys = currentAttention
        updateAttentionNotification()
        while (events.size > 100) events.removeLast()
        lastEventMessages.keys.retainAll(connections.selected.saved.map { it.slot }.toSet())
    }
    private fun showEvents() {
        replacePage(Screen.EVENTS, listOf(attentionOnly, events.toList())) {
            val visible = events.filter { !attentionOnly || it.attention }
            listOf(Ui.toolbar(context, Ui.label(context, "This session", UiType.LABEL),
                singleChoice(context, "Event filter", arrayOf("All events", "Needs attention"), if (attentionOnly) 1 else 0) {
                    attentionOnly = it == 1; showEvents()
                }),
                Ui.note(context, "Up to 100 observed workspace status changes. This log clears when the plugin closes; it is not a complete audit history."),
                if (visible.isEmpty()) Ui.empty(context, if (attentionOnly) "No attention events" else "No events recorded", "New matching status changes will appear here.")
                else Ui.stack(context, 0, *visible.map { event ->
                    LinearLayout(context).apply {
                        gravity = Gravity.TOP
                        val gap = ArachneStyle.dp(this, 12)
                        setPadding(0, gap, 0, gap)
                        addView(Ui.note(context, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(event.time))),
                            LinearLayout.LayoutParams(ArachneStyle.dp(this, 48), -2).apply { marginEnd = gap })
                        addView(Ui.stack(context, 4, Ui.label(context, event.workspace, UiType.FIELD),
                            Ui.label(context, event.message, UiType.SUPPORTING, if (event.attention) UiTone.WARNING else UiTone.SECONDARY)),
                            LinearLayout.LayoutParams(0, -2, 1f))
                    }.let { Ui.stack(context, 0, it, Ui.rule(context)) }
                }.toTypedArray()))
        }
    }
    private val metricValues = mutableMapOf<String, TextView>()
    private var metricChart: ArachneTrafficChart? = null
    private fun metricRow(label: String, tone: UiTone? = null): View {
        val value = Ui.label(context, "—", UiType.SUPPORTING, tone ?: UiTone.TEXT).apply { gravity = Gravity.END }
        metricValues[label] = value
        return Ui.keyValue(context, label, value, tone ?: UiTone.SECONDARY)
    }
    private fun pathLabel(paths: List<PeerPath>, metrics: WorkspaceMetrics?): String = when {
        metrics == null -> "Awaiting measurements"
        paths.isEmpty() -> if (metrics.pathsLimited) "Observation limit reached" else "No active path"
        else -> paths.map { when (it.route) { "direct" -> "Direct"; "relay" -> "Relay"; else -> "Custom" } }.distinct().sorted().joinToString(" + ")
    }
    private fun showMetrics() {
        val connections = connectionState ?: return replacePage(Screen.METRICS, listOf("loading")) {
            listOf(Ui.empty(context, "Loading state", "Metrics will appear when Arachne finishes opening."))
        }
        val state = connections.selected
        val record = state.active
        val metrics = state.metrics
        replacePage(Screen.METRICS, listOf(record?.slot, record?.name, metrics?.session, state.metricsError, memberConnectionActive)) {
            metricValues.clear(); metricChart = null
            if (record == null) return@replacePage listOf(Ui.empty(context, "Select a workspace", "Metrics belong to one workspace on this device."),
                pageLink("Workspaces", "Choose a team", Screen.WORKSPACES))
            listOf(Ui.toolbar(context, Ui.stack(context, 4, Ui.label(context, "Workspace metrics", UiType.LABEL),
                Ui.note(context, record?.name ?: "No workspace selected")), exportAction("metrics") { metricsSnapshot() }),
                exportMessage(Screen.METRICS),
                trafficCards(Screen.METRICS, metrics),
                Ui.note(context, state.metricsError ?: if (memberConnectionActive)
                    "Transport bytes, including protocol traffic and retransmission. Counters reset when this workspace opens or resumes."
                    else "Workspace paused. Resume in Connection to collect measurements."),
                Ui.section(context, "Traffic · last 10 minutes",
                    ArachneTrafficChart(context).also { metricChart = it },
                    Ui.toolbar(context, Ui.note(context, "10 min before sample"), Ui.note(context, "Latest sample")),
                    metricRow("Receive · solid", UiTone.RECEIVE), metricRow("Send · dashed", UiTone.SEND)),
                Ui.section(context, "Pending on this device", metricRow("Total pending"),
                    metricRow("Transport receive queue"), metricRow("Awaiting ATAK acceptance"), metricRow("Local sends"), metricRow("Active data repairs"),
                    Ui.note(context, "Pending work is local. An empty queue does not confirm that a peer received or read an item.")),
                Ui.section(context, "Current workspace", metricRow("Members"), metricRow("Selected feeds"),
                    metricRow("Active paths"), metricRow("Fastest transport RTT")),
                Ui.section(context, "ATAK process", metricRow("Java heap in use"), metricRow("Native heap in use"),
                    Ui.note(context, "Memory includes ATAK and its plugins, not just Arachne.")),
                pageLink("Peers", "Inspect individual transport paths", Screen.PEERS),
                pageLink("Diagnostics", "Inspect this device and its workspaces", Screen.DIAGNOSTICS))
        }
        updateTraffic(Screen.METRICS, metrics)
        metricChart?.update(metrics?.samples.orEmpty())
        val samples = metrics?.samples.orEmpty().takeLast(2)
        val seconds = if (samples.size == 2) (samples.last().elapsedMs - samples.first().elapsedMs) / 1000.0 else 0.0
        val values = mapOf(
            "Receive · solid" to if (seconds > 0) bytes(((samples.last().received - samples.first().received) / seconds).toLong()) + "/s" else "Collecting…",
            "Send · dashed" to if (seconds > 0) bytes(((samples.last().sent - samples.first().sent) / seconds).toLong()) + "/s" else "Collecting…",
            "Total pending" to metrics?.pending?.toString(), "Transport receive queue" to metrics?.receiveQueue?.toString(),
            "Admission queue" to metrics?.admissionQueue?.toString(), "Admission queue bytes" to metrics?.admissionQueueBytes?.toString(),
            "Admissions in flight" to metrics?.admissionInFlight?.toString(), "Approval-pending requests" to metrics?.approvalPending?.toString(),
            "Awaiting ATAK acceptance" to metrics?.pendingObjects?.toString(), "Local sends" to metrics?.pendingSends?.toString(),
            "Active data repairs" to metrics?.repairJobs?.toString(),
            "Members" to if (memberConnectionActive) state.members.size.toString() else null,
            "Selected feeds" to if (memberConnectionActive) state.feeds.count { it.enabled }.toString() else null,
            "Active paths" to pathLabel(metrics?.paths.orEmpty(), metrics),
            "Fastest transport RTT" to (metrics?.paths?.minOfOrNull { it.rttMs }?.let { "$it ms" }
                ?: if (metrics == null) "—" else "No active path"),
            "Java heap in use" to bytes(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()),
            "Native heap in use" to bytes(android.os.Debug.getNativeHeapAllocatedSize()))
        for ((label, value) in values) metricValues[label]?.text = value ?: "—"
    }
    private fun metricsSnapshot() = buildString {
        appendLine("Arachne metrics")
        appendLine("Created: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}")
        appendLine("Scope: selected workspace on this device")
        val metrics = memberState?.metrics
        appendLine("Transport counters reset when this workspace opens or resumes; include protocol overhead and retransmission.")
        appendLine("Received bytes: ${metrics?.latest?.received ?: "Unavailable"}")
        appendLine("Sent bytes: ${metrics?.latest?.sent ?: "Unavailable"}")
        appendLine("Transport receive queue: ${metrics?.receiveQueue ?: "Unavailable"}")
        appendLine("Admission queue: ${metrics?.admissionQueue ?: "Unavailable"}")
        appendLine("Admission queue bytes: ${metrics?.admissionQueueBytes ?: "Unavailable"}")
        appendLine("Admissions in flight: ${metrics?.admissionInFlight ?: "Unavailable"}")
        appendLine("Approval-pending requests: ${metrics?.approvalPending ?: "Unavailable"}")
        appendLine("Awaiting ATAK acceptance: ${metrics?.pendingObjects ?: "Unavailable"}")
        appendLine("Local sends: ${metrics?.pendingSends ?: "Unavailable"}")
        appendLine("Active data repairs: ${metrics?.repairJobs ?: "Unavailable"}")
        appendLine("Active paths: ${pathLabel(metrics?.paths.orEmpty(), metrics)}")
        appendLine("Fastest transport RTT ms: ${metrics?.paths?.minOfOrNull { it.rttMs } ?: if (metrics == null) "Unavailable" else "No active path"}")
        appendLine("Path observations limited: ${metrics?.pathsLimited ?: "Unavailable"}")
        appendLine("Members: ${if (memberConnectionActive) memberState?.members?.size else "Not loaded"}")
        appendLine("Selected feeds: ${if (memberConnectionActive) memberState?.feeds?.count { it.enabled } else "Not loaded"}")
        appendLine("Traffic history: age_ms,received_bytes,sent_bytes")
        metrics?.samples?.forEach { appendLine("${metrics.latest.elapsedMs - it.elapsedMs},${it.received},${it.sent}") }
    }
    private val reportDescription by lazy { EditText(context).apply {
        hint = "What happened? What did you expect?"
        contentDescription = "Problem description"
        gravity = Gravity.TOP
        minLines = 3
        maxLines = 8
        filters = arrayOf(android.text.InputFilter.LengthFilter(4000))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        ArachneStyle.input(this)
        isSaveEnabled = false
    } }
    private val reportPreview by lazy { Ui.label(context, "", UiType.SUPPORTING).apply { setTextIsSelectable(true) } }
    private val includeDiagnostics by lazy { android.widget.CheckBox(context).apply {
        text = "Include device diagnostics"; isChecked = true; ArachneStyle.choice(this)
    } }
    private fun diagnosticSnapshot() = buildString {
        val connections = connectionState
        val density = pageScroll.resources.displayMetrics.density
        appendLine("Arachne: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
        appendLine("ATAK: ${atakVersion()}")
        appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Pane: ${(pageScroll.width / density).toInt()} × ${(pageScroll.height / density).toInt()} dp")
        appendLine("Appearance: ${ArachneStyle.appearance}; larger text: ${ArachneStyle.largerText}; reduced motion: ${ArachneStyle.reducedMotion}")
        appendLine("Workspaces: ${connections?.connected?.size ?: "Not loaded"} active / ${connections?.selected?.saved?.size ?: "Not loaded"} saved")
        appendLine("Workspace measurements: ${connections?.connected?.count { it.metrics != null } ?: 0} available")
        appendLine("Workspace attention states: ${connections?.attention?.size ?: 0}")
        appendLine("Pending local work: ${connections?.connected?.sumOf { it.metrics?.pending ?: 0 } ?: 0}")
        appendLine("Active data repairs: ${connections?.connected?.sumOf { it.metrics?.repairJobs ?: 0 } ?: 0}")
    }
    private val prepareReport by lazy { Ui.action(context, "Preview report", UiAction.PRIMARY) {
        val description = reportDescription.text.toString().trim()
        if (description.isBlank()) { reportDescription.error = "Describe the problem before preparing a report."; return@action }
        reportPreview.text = buildString {
            appendLine("Arachne problem report")
            appendLine("Created: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}")
            appendLine(); appendLine("Description"); appendLine(description)
            if (includeDiagnostics.isChecked) { appendLine(); append(diagnosticSnapshot()) }
        }
        exportMessages[Screen.REPORT]?.apply { text = ""; visibility = View.GONE }
        navigate(Screen.REPORT)
    } }
    private val exportMessages = mutableMapOf<Screen, TextView>()
    private fun exportMessage(destination: Screen): TextView = exportMessages.getOrPut(destination) {
        Ui.notice(context, "").apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    }.also { (it.parent as? android.view.ViewGroup)?.removeView(it) }
    private fun exportAction(kind: String, snapshot: () -> String): Button {
        val destination = when (kind) { "metrics" -> Screen.METRICS; "diagnostics" -> Screen.DIAGNOSTICS; else -> Screen.REPORT }
        lateinit var action: Button
        action = Ui.action(context, if (destination == Screen.REPORT) "Save report" else "Export",
            if (destination == Screen.REPORT) UiAction.PRIMARY else UiAction.SECONDARY) {
            val text = snapshot()
            if (text.isBlank()) return@action
            val message = exportMessages.getValue(destination)
            val started = generation
            message.text = "Saving locally…"; message.visibility = View.VISIBLE
            action.isEnabled = false
            Thread({
                // The 5.8 Hello World sample uses getItem + IOProviderFactory for
                // host-owned files. Exporting uses that seam, without another Activity.
                var partial: java.io.File? = null
                val result = runCatching {
                    val directory = com.atakmap.coremap.filesystem.FileSystemUtils.getItem(
                        com.atakmap.coremap.filesystem.FileSystemUtils.EXPORT_DIRECTORY + "/arachne")
                    val file = java.io.File(directory, "$kind-${java.util.UUID.randomUUID()}.txt")
                    partial = file
                    check(com.atakmap.coremap.io.IOProviderFactory.exists(directory) || com.atakmap.coremap.io.IOProviderFactory.mkdirs(directory))
                    com.atakmap.coremap.io.IOProviderFactory.getOutputStream(file).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    file.absolutePath
                }
                if (result.isFailure) partial?.let { runCatching { com.atakmap.coremap.io.IOProviderFactory.delete(it) } }
                mainHandler.post {
                    if (generation == started) {
                        action.isEnabled = true
                        message.text = result.fold({ "Saved locally:\n$it" }, { "Could not save the file. Check available storage and try again." })
                        ArachneStyle.notice(message, if (result.isSuccess) UiTone.SECONDARY else UiTone.DANGER)
                        if (result.isSuccess) toast("Saved locally in ATAK/export/arachne.")
                    }
                }
            }, "arachne-local-export").start()
        }
        Ui.actionIcon(action, Ui.Icon.DOWNLOAD)
        return action
    }
    private fun atakVersion(): String = runCatching {
        val host = MapView.getMapView().context
        host.packageManager.getPackageInfo(host.packageName, 0).versionName ?: "Unavailable"
    }.getOrDefault("Unavailable")
    private fun refreshDetailPage() {
        when (screen) {
            Screen.NOTIFICATIONS -> refreshNotificationStatus()
            Screen.JOIN_REVIEW -> showJoinReview()
            Screen.INVITATION -> showInvitationDetails()
            Screen.NEARBY_DEVICES -> showNearbyDevices()
            Screen.NEARBY -> showNearbyWorkspaces()
            Screen.WAITING -> showWaiting()
            Screen.WORKSPACE, Screen.RENAME -> showWorkspaceSettings()
            Screen.APPROVALS -> showApprovals()
            Screen.FEED -> showFeedDetails()
            Screen.PEERS -> showPeers()
            Screen.PEER -> showPeerDetails()
            Screen.EVENTS -> showEvents()
            Screen.METRICS -> showMetrics()
            else -> Unit
        }
    }
    private val ui = requireNotNull(controller.getService(IHostUIService::class.java))
    private fun styleViews(view: android.view.View) {
        ArachneStyle.applyTree(view)
    }
    private val paneView by lazy {
        val frame = Ui.page(context, Ui.stack(context, 0, appHeader, tabStrip), pageScroll)
        val content = Ui.stack(context, ArachneStyle.GROUP).apply {
            val inset = ArachneStyle.dp(this, ArachneStyle.INSET)
            setPadding(inset, ArachneStyle.dp(this, 14), inset, ArachneStyle.dp(this, 20))
        }
        content.addView(workspaceStatus, LinearLayout.LayoutParams(-1, -2))
        for (destination in Screen.entries) {
            screens[destination] = Ui.stack(context, ArachneStyle.SECTION_GAP)
            content.addView(screens.getValue(destination), LinearLayout.LayoutParams(-1, -2))
        }
        fun section(destination: Screen, vararg views: View) {
            views.forEach { screens.getValue(destination).addView(it, LinearLayout.LayoutParams(-1, it.layoutParams?.height ?: -2)) }
        }
        for (destination in listOf(Screen.CREATE, Screen.JOIN, Screen.JOIN_REVIEW, Screen.RECONNECT)) formFields[destination] = Ui.stack(context, 16)
        for (destination in listOf(Screen.CREATE, Screen.JOIN, Screen.JOIN_REVIEW, Screen.INVITE, Screen.RENAME)) formMessages[destination] = Ui.notice(context, "").apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        section(Screen.WORKSPACES,
            Ui.toolbar(context, Ui.stack(context, 4, Ui.label(context, "Your teams", UiType.LABEL), workspaceCount),
                navigation("Join", Screen.JOIN), navigation("Create", Screen.CREATE).apply { ArachneStyle.button(this, UiAction.PRIMARY) }),
            workspaceList, Ui.note(context, "Opening a workspace keeps its sharing choices. Use Connection to pause or resume."))
        section(Screen.SETTINGS, settingsPage())
        section(Screen.APPEARANCE, appearanceSettings())
        section(Screen.NOTIFICATIONS, notificationSettings())
        section(Screen.HELP, Ui.note(context, "Guidance is available offline. Project support opens your browser."),
            Ui.stack(context, 0,
                pageLink("Field manual", "Joining, sharing and everyday use", Screen.MANUAL),
                pageLink("Troubleshooting", "Check device and workspace diagnostics", Screen.DIAGNOSTICS),
                pageLink("Report a problem", "Prepare and review a local support report", Screen.SUPPORT),
                pageLink("About Arachne", "Version, changelog and project information", Screen.ABOUT)))
        section(Screen.MANUAL, help.manualPage())
        section(Screen.CREATE, Ui.note(context, "Choose the workspace name your team will see."), formMessages.getValue(Screen.CREATE), formFields.getValue(Screen.CREATE), createWorkspace)
        section(Screen.JOIN, Ui.intro(context, "Use an invitation from a workspace administrator."),
            Ui.stack(context, 0,
                Ui.link(context, "Scan invitation", "Use your camera to read a QR code", Ui.Icon.SCAN) { if (scanInvitation.isEnabled) scanInvitation.performClick() },
                Ui.link(context, "Find nearby", "Discover workspaces advertised on this network", Ui.Icon.WIFI) { if (findNearbyWorkspace.isEnabled) findNearbyWorkspace.performClick() }),
            formMessages.getValue(Screen.JOIN), formFields.getValue(Screen.JOIN), joinWorkspace)
        section(Screen.JOIN_REVIEW, reviewSummary, formMessages.getValue(Screen.JOIN_REVIEW), formFields.getValue(Screen.JOIN_REVIEW), confirmJoin)
        section(Screen.INVITE, Ui.intro(context, "Create a private link for this workspace."), formMessages.getValue(Screen.INVITE),
            labeledInput("Label on this device · optional", invitationLabelInput),
            Ui.section(context, "Invitation type", singleChoice(context, "Invitation type", arrayOf("Personal · one device", "Open · multiple devices")) {
                invitationPersonal = it == 0
                invitationAutomatic.visibility = if (invitationPersonal) View.VISIBLE else View.GONE
                invitationExplanation.text = if (invitationPersonal) "One device can use this invitation. An administrator must be reachable to finish joining."
                    else "Anyone with the link can join until it expires or is disabled."
            }.also { invitationOptions += it }), invitationExplanation, invitationAutomatic,
            Ui.section(context, "Expires", singleChoice(context, "Invitation expiry", arrayOf("Never", "In 1 hour", "In 1 day", "In 7 days")) {
                invitationLifetime = longArrayOf(0, 3600, 86400, 604800)[it]
            }.also { invitationOptions += it }), createInvitation)
        section(Screen.RECONNECT, Ui.note(context, "Use a private invitation from this same workspace to refresh its saved connection."),
            formFields.getValue(Screen.RECONNECT), reconnectWorkspace)
        section(Screen.WORKSPACE, workspaceReady)
        section(Screen.RENAME, Ui.note(context, "Administrators can change the name for everyone. The workspace identity stays the same."),
            formMessages.getValue(Screen.RENAME), settingsNameField, renameWorkspace)
        section(Screen.MEMBERS, Ui.stack(context, ArachneStyle.GAP,
            Ui.toolbar(context, memberSearch, requestsButton, memberInvite), memberList))
        val memberHero = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(memberAvatar, LinearLayout.LayoutParams(ArachneStyle.dp(this, 44), ArachneStyle.dp(this, 44)).apply {
                marginEnd = ArachneStyle.dp(memberAvatar, 12)
            })
            addView(Ui.stack(context, 4, memberHeading, memberRoleLabel, memberSignal), LinearLayout.LayoutParams(0, -2, 1f))
        }
        section(Screen.MEMBER, memberHero, memberInformation, memberActions)
        section(Screen.INVITATIONS,
            Ui.toolbar(context, Ui.stack(context, 4, Ui.label(context, "Invite people", UiType.LABEL), invitationCount), inviteMember),
            invitationList, nearbyDiscoverySection)
        section(Screen.FEEDS, Ui.note(context, "Select the feeds this device receives. Open a feed for its source, coverage and map controls."), feedList)
        section(Screen.DATA, topicList)
        section(Screen.CONNECTION, connectionBody)
        section(Screen.DIAGNOSTICS,
            Ui.toolbar(context, Ui.stack(context, 4, Ui.label(context, MapView.getMapView().deviceCallsign.orEmpty().ifBlank { "This ATAK device" }, UiType.SECTION),
                Ui.note(context, "Arachne ${BuildConfig.VERSION_NAME} · ATAK ${atakVersion()}")), exportAction("diagnostics") { diagnosticSnapshot() }),
            exportMessage(Screen.DIAGNOSTICS), diagnosticsStatus, Ui.section(context, "Workspace state", diagnosticCounts),
            Ui.stack(context, 0,
                pageLink("Peers", "Last contact in the selected workspace", Screen.PEERS),
                pageLink("Event log", "Status changes observed during this session", Screen.EVENTS),
                pageLink("Metrics", "Current counts and available measurements", Screen.METRICS),
                pageLink("Report a problem", "Review and save a local report", Screen.SUPPORT)))
        if (BuildConfig.DEBUG) section(Screen.DIAGNOSTICS,
            Ui.section(context, "Development tools",
                Ui.action(context, "Inspect native workspace bindings") {
                    nativeCheck("NativeBindingCheck", "snapshot", "NATIVE_BINDINGS", false)
                },
                Ui.action(context, "Check local stream authentication") {
                    nativeCheck("LocalTakStreamCheck", "run", "LOCAL_NATIVE_AUTH", true)
                },
                Ui.action(context, "Check native resource protocol") {
                    nativeCheck("NativeResourceCheck", "protocol", "NATIVE_RESOURCE_PROTOCOL", true)
                },
                Ui.action(context, "Check native TAK service") {
                    nativeCheck("NativeServiceCheck", "run", "NATIVE_TAK_SERVICE", true)
                },
                Ui.action(context, "Check native listener ports") {
                    nativeCheck("NativePortCheck", "run", "NATIVE_PORTS", true)
                }))
        section(Screen.SUPPORT, Ui.note(context, "Describe the problem and the steps that led to it."),
            labeledInput("What happened?", reportDescription), includeDiagnostics,
            Ui.note(context, "Nothing is sent automatically. Review the preview and your description before sharing."),
            Ui.details(context, "diagnostic contents", Ui.note(context, "Optional diagnostics include app versions, pane size, appearance, workspace counts and local pending/repair counts. Names, identities, invitations, locations and event messages are excluded.")), prepareReport)
        section(Screen.REPORT, Ui.notice(context, "Review this local preview. Nothing has been sent. Saving puts a text file in ATAK’s export folder."), reportPreview,
            exportMessage(Screen.REPORT),
            Ui.actions(context, Ui.action(context, "Edit description") { goBack() }, exportAction("report") { reportPreview.text.toString() }),
            Ui.action(context, "Copy report", UiAction.QUIET) {
                val clipboard = MapView.getMapView().context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Arachne problem report", reportPreview.text)
                clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                clipboard.setPrimaryClip(clip)
                toast("Report copied. Review it before sharing.")
            }, Ui.link(context, "Project support", "Open the Arachne GitHub project in your browser") { help.project() })
        val aboutMark = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.fabric_icon)
            ArachneStyle.brandIcon(this)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = android.widget.ImageView.ScaleType.FIT_START
            layoutParams = LinearLayout.LayoutParams(-1, ArachneStyle.dp(this, 48))
        }
        section(Screen.ABOUT, Ui.stack(context, 8, aboutMark, Ui.label(context, "Arachne", UiType.SECTION),
            Ui.note(context, "Workspace sharing for ATAK.")), Ui.stack(context, 0,
            Ui.keyValue(context, "Version", BuildConfig.VERSION_NAME),
            Ui.keyValue(context, "Build", "${BuildConfig.VERSION_CODE} · ${BuildConfig.BUILD_TYPE}"),
            Ui.keyValue(context, "ATAK", atakVersion())), Ui.stack(context, 0,
            Ui.link(context, "Changelog", "What changed in this release") { help.changelog() },
            Ui.link(context, "Project & support", "Source code, issues and project information") { help.project() }))
        pageScroll.addView(content)
        for (action in listOf(createWorkspace, joinWorkspace, reconnectWorkspace, inviteMember)) ArachneStyle.button(action, UiAction.PRIMARY)
        ArachneStyle.button(leaveWorkspace, UiAction.DESTRUCTIVE)
        styleViews(content)
        memberSearch.setTag(R.id.arachne_action_icon, Ui.Icon.SEARCH.resource)
        ArachneStyle.input(memberSearch)
        pageScroll.addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                val widthDp = ((right - left) / view.resources.displayMetrics.density).toInt()
                val inset = ArachneStyle.dp(content, ArachneStyle.pageInset(widthDp))
                content.setPadding(inset, ArachneStyle.dp(content, if (widthDp <= 360) 12 else if (widthDp >= 560) 18 else 14),
                    inset, ArachneStyle.dp(content, if (widthDp <= 360) 12 else 20))
                val tabInset = ArachneStyle.dp(tabs, if (widthDp <= 360) 4 else 8)
                tabs.setPadding(tabInset, 0, tabInset, 0)
                refreshMemberToolbar()
            }
        }
        navigate(screen)
        frame
    }
    private inner class ArachneDropDown : DropDownReceiver(MapView.getMapView()), DropDown.OnStateListener {
        init { setRetain(true) }
        fun open() {
            if (isClosed) showDropDown(paneView, .5, 1.0, 1.0, .5, true, this)
            else unhideDropDown()
        }
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {}
        override fun disposeImpl() {}
        override fun onBackButtonPressed(): Boolean {
            // ATAK 5.8 suppresses its fallback close when ignoreBackButton is true.
            if (!goBack()) closeDropDown()
            return true
        }
        override fun onDropDownSelectionRemoved() {}
        override fun onDropDownSizeChanged(width: Double, height: Double) {
            closeForm()
            pageScroll.post { if (isVisible && screen in formScreens) showForm(screen) }
        }
        override fun onDropDownClose() {
            closeForm(); mainHandler.removeCallbacks(contactTick); WorkspaceController.presenceWatched = false; ArachneMotion.finishTree(paneView)
        }
        override fun onDropDownVisible(visible: Boolean) {
            if (!visible) { closeForm(); mainHandler.removeCallbacks(contactTick); WorkspaceController.presenceWatched = false; ArachneMotion.finishTree(paneView) }
            else {
                refreshContactTimer()
                pageScroll.post { if (screen in formScreens) showForm(screen) }
            }
        }
    }
    private var dropdown: ArachneDropDown? = null
    private fun showPane() { (dropdown ?: ArachneDropDown().also { dropdown = it }).open() }
    private fun toast(message: String) {
        val started = generation
        ui.queueEvent { if (generation == started) ui.showToast(message) }
    }
    private fun performOperation(label: String, action: () -> Boolean?): Boolean {
        val accepted = try { action() == true } catch (error: Exception) {
            Log.e("Arachne", "UI_OPERATION_FAILED action=$label", error)
            false
        }
        if (!accepted) {
            val message = "$label could not start. Wait for the current operation or reopen the workspace and try again."
            toast(message)
            events.addFirst(UiEvent(System.currentTimeMillis(), memberState?.active?.name ?: "Arachne", message, true))
            while (events.size > 100) events.removeLast()
            if (screen == Screen.EVENTS) showEvents()
        }
        return accepted
    }
    private val button = ToolbarItem.Builder(
        "Arachne",
        MarshalManager.marshal(context.getDrawable(R.drawable.fabric_icon), Drawable::class.java, Bitmap::class.java)
    ).setIdentifier("dev.arachne.atak").setListener(object : ToolbarItemAdapter() {
        override fun onClick(item: ToolbarItem) { showPane() }
    }).build()

    private fun startWorkspaceState() {
        check(workspaces == null && workspaceAdapters == null) { "Arachne workspace state already started" }
        val startedGeneration = ++generation
        Thread({
            try {
                val failures = NativeHttpCredentials.recover(MapView.getMapView().context)
                Log.i("Arachne", "NATIVE_HTTP_RECOVERY_COMPLETE failures=$failures")
            } catch (error: Exception) { Log.w("Arachne", "NATIVE_HTTP_RECOVERY_FAILED", error) }
        }, "arachne-native-http-recovery").start()
        val adapters = WorkspaceAdapters({ workspace, request, complete ->
            workspaces?.resource(workspace, request, complete) == true
        }) { workspace, topic, bytes, recipients, current, complete ->
            workspaces?.publish(workspace, topic, bytes, recipients, current, complete) == true
        }
        workspaceAdapters = adapters
        var displayedRevision = 0L // Accessed only on the main thread.
        workspaces = WorkspaceConnections(MapView.getMapView().context,
            CotTopics.nativeDefaults + WorkspaceResources.topics + WorkspaceMissionPackages.topics, adapters::receive) { state ->
            adapters.bind(state)
            mainHandler.post {
                if (generation == startedGeneration && state.revision > displayedRevision) {
                    displayedRevision = state.revision
                    showWorkspaces(state)
                }
            }
        }
    }

    override fun onStart() {
        check(workspaces == null) { "Plugin already started" }
        LocalTakPorts.start(MapView.getMapView().context)
        startWorkspaceState()
        val startedGeneration = generation
        nearbyInvitations = NearbyInvitations(MapView.getMapView().context,
            MapView.getMapView().deviceCallsign.orEmpty().trim().ifBlank { "Unnamed Arachne device" }) { link ->
            if (generation == startedGeneration) {
                val opened = openInvitation(link)
                toast(if (opened) "Nearby workspace invitation received." else "Invitation received. Finish the current workspace action and ask the sender to try again.")
            }
        }
        val provider = CommsProviderFactory.getProvider()
        Log.i("Arachne", "HOST_COMMS default=${CommsProviderFactory.isDefault()} initialized=${provider.isInitialized}")
        val receiverContext = MapView.getMapView().context
        val filter = android.content.IntentFilter(InvitationActivity.ACTION)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            receiverContext.registerReceiver(invitationReceiver, filter, InvitationActivity.PERMISSION, mainHandler, android.content.Context.RECEIVER_EXPORTED)
        } else {
            receiverContext.registerReceiver(invitationReceiver, filter, InvitationActivity.PERMISSION, mainHandler)
        }
        AtakBroadcast.getInstance().registerReceiver(attentionReceiver, AtakBroadcast.DocumentedIntentFilter(attentionAction))
        if (BuildConfig.DEBUG) debugControl("install")
        ui.addToolbarItem(button)
        Log.i("Arachne", "PLUGIN_STARTED version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}")
    }
    override fun onStop() {
        closeForm()
        help.close()
        ArachneStyle.closeDialogs()
        dropdown?.let { it.closeDropDown(); it.dispose() }
        dropdown = null
        generation++
        mainHandler.removeCallbacks(contactTick); WorkspaceController.presenceWatched = false
        reviewingInvitation = false
        workspaceSubmission = null
        pendingWorkspaceOpen = null
        invitationSubmission = null
        invitationReview = null
        issuedLinks.clear()
        discoveringDevices = false; sendingNearby = false; nearbyDevices = emptyList(); nearbyDeviceScope = null
        findingNearby = false
        nearbyInvitations?.close()
        nearbyInvitations = null
        MapView.getMapView().context.unregisterReceiver(invitationReceiver)
        if (BuildConfig.DEBUG) debugControl("uninstall")
        AtakBroadcast.getInstance().unregisterReceiver(attentionReceiver)
        attentionNotificationId?.let { NotificationUtil.getInstance().clearNotification(it) }
        currentAttentionKeys = emptySet(); notifiedAttentionKeys = emptySet()
        workspaceAdapters?.close()
        workspaceAdapters = null
        workspaces?.let { owners ->
            owners.close()
            Thread({
                Log.i("Arachne", "WORKSPACES_CLOSED success=${owners.awaitClosed(20, java.util.concurrent.TimeUnit.SECONDS)}")
            }, "arachne-workspaces-close").start()
        }
        workspaces = null
        events.clear(); lastEventMessages.clear(); observedAttention.clear(); latestPeerContact.clear(); detailKeys.remove(Screen.EVENTS)
        ui.removeToolbarItem(button)
        Log.i("Arachne", "PLUGIN_STOPPED")
    }
}
