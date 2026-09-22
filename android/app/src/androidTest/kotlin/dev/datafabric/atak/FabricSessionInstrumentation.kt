package dev.arachne.atak

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Android/JNI queue check; no test framework or substitute native engine. */
class FabricSessionInstrumentation : Instrumentation() {
    private var options = Bundle()
    override fun onCreate(arguments: Bundle?) { options = arguments ?: Bundle(); super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var session: FabricSession? = null
        val release = CountDownLatch(1)
        val savedWorkspace = AtomicReference<ByteArray>()
        try {
            if (options.getString("camera_preview_check") == "true") {
                check(fitCameraPreview(1920, 900, 640, 480) == (1200 to 900))
                check(fitCameraPreview(900, 1600, 480, 640) == (900 to 1200))
                check(fitCameraPreview(1600, 900, 1920, 1080) == (1600 to 900))
                check(fitCameraPreview(600, 900, 640, 480) == (600 to 450))
                check(fitCameraPreview(0, 0, 640, 480) == (0 to 0))
                check(fitCameraPreview(900, 600, 0, 0) == (900 to 600))
                result.putString("result", "PASS: camera preview preserves aspect ratio in landscape, portrait and narrow windows")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("nearby_advertiser_check") == "true") {
                result.putString("nearby_advertiser_result", NearbyAdvertisementCheck.run(targetContext).toString())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("join_cancel_check") == "true") {
                result.putString("join_cancel_result", JoinCancellationCheck.run(targetContext).toString())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("toolbar_icon_check") == "true") {
                result.putString("toolbar_icon_result", DesignSystemCheck.checkToolbarIcon(targetContext))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("design_system_check") == "true") {
                result.putString("design_system_result", DesignSystemCheck.run(targetContext, this).toString())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("identity_storage_check") == "true") {
                checkIdentityStorage()
                result.putString("result", "PASS: endpoint identity storage and debug fixture repair")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("invitation_scheme_check") == "true") {
                val value = org.json.JSONObject().put("address", "192.0.2.1:4242")
                    .put("workspace", org.json.JSONArray(List(32) { 1 }))
                    .put("peer", org.json.JSONArray(List(32) { 2 }))
                    .put("bootstrap_peers", org.json.JSONArray()
                        .put(org.json.JSONArray(List(32) { 2 })).put(org.json.JSONArray(List(32) { 5 })))
                    .put("invitation", org.json.JSONArray(List(293) { 3 }))
                    .put("checkpoint", org.json.JSONArray().put(4))
                    .put("routes", org.json.JSONArray())
                val link = WorkspaceInvitation.encode(value)
                check(link.startsWith("arachne://join#") && link.length == 535)
                val decoded = WorkspaceInvitation.decode(link)
                check(decoded.getJSONArray("bootstrap_peers").length() == 2 && !decoded.has("checkpoint"))
                check(com.google.zxing.qrcode.encoder.Encoder.encode(link,
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M).version.versionNumber == 18)
                for (size in listOf(320, 720)) {
                    val qr = invitationQr(link, size)
                    val pixels = IntArray(qr.width * qr.height)
                    qr.getPixels(pixels, 0, qr.width, 0, 0, qr.width, qr.height)
                    check(pixels.none { it == android.graphics.Color.WHITE }) { "QR backgrounds must remain gray" }
                    val scanned = com.google.zxing.MultiFormatReader().decode(
                        com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(
                            com.google.zxing.RGBLuminanceSource(qr.width, qr.height, pixels))))
                    check(scanned.text == link)
                    check(decodeInvitationQr(ByteArray(pixels.size) { index ->
                        val color = pixels[index]
                        ((android.graphics.Color.red(color) + android.graphics.Color.green(color) +
                            android.graphics.Color.blue(color)) / 3).toByte()
                    }, qr.width, qr.height) == link)
                }
                check(WorkspaceInvitation.decode(link.replaceFirst("arachne://", "datafabric://")).toString() == decoded.toString())
                val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                val full = org.json.JSONObject().put("version", 2).put("address", value.getString("address"))
                for (field in listOf("workspace", "peer", "invitation", "checkpoint")) {
                    val array = value.getJSONArray(field)
                    full.put(field, android.util.Base64.encodeToString(ByteArray(array.length()) { array.getInt(it).toByte() }, flags))
                }
                full.put("routes", org.json.JSONArray())
                val legacy = "datafabric://join#" + android.util.Base64.encodeToString(full.toString().toByteArray(), flags)
                check(WorkspaceInvitation.decode(legacy).getJSONArray("workspace").length() == 32)
                check(runCatching { WorkspaceInvitation.decode(link.replaceFirst("arachne://", "other://")) }.isFailure)
                result.putString("result", "PASS: arachne invitation generated; legacy invitation accepted")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("native_frames_check") == "true") {
                result.putString("native_frames_result", NativeCotFramesCheck.run(targetContext).toString())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("recovery_check") == "true") {
                result.putString("recovery_result", WorkspaceDataRecoveryCheck.run(targetContext).toString())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("resource_recovery_check") == "true") {
                result.putString("resource_recovery_result", WorkspaceResourcesCheck.run(targetContext).toString())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("native_resource_protocol_check") == "true") {
                result.putString("native_resource_protocol_result", NativeResourceCheck.protocol())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("native_service_check") == "true") {
                result.putString("native_service_result", NativeServiceCheck.run())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("connections_check") == "true") {
                result.putString("connections_result", WorkspaceConnectionsCheck.run(targetContext).toString())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("workspace_exit_check") == "true") {
                result.putString("workspace_exit_result", WorkspaceConnectionsCheck.run(targetContext, true).toString())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("controller_check") == "true") {
                result.putString("controller_result", WorkspaceControllerCheck.run(targetContext).toString())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.getString("members_check") == "true") {
                result.putString("members_result", WorkspaceMembersCheck.run(targetContext).toString())
                finish(Activity.RESULT_OK, result)
                return
            }
            if (options.containsKey("network_role")) {
                result.putString("peer_result", PeerAdmissionCheck.run(targetContext, options).toString())
                finish(Activity.RESULT_OK, result)
                return
            }
            checkIdentityStorage()
            check(CotTopics.outgoing("a-f-G-U-C", true, null) == "atak/pli")
            check(CotTopics.outgoing("a-f-G-E-S", false, null) == "atak/features")
            check(CotTopics.outgoing("u-d-f", false, null) == "atak/drawings")
            check(CotTopics.outgoing("b-t-f", false, "All Chat Rooms") == "atak/chat")
            check(CotTopics.outgoing("b-t-f", false, "private-peer") == null)
            check(!CotTopics.accepts("atak/pli", "b-t-f", "All Chat Rooms"))
            val workspaceA = ByteArray(32) { 1 }
            val workspaceB = ByteArray(32) { 2 }
            val authorA = ByteArray(32) { 3 }
            val authorB = ByteArray(32) { 4 }
            val actor = CotIdentity.member(workspaceA, authorA)
            check(actor == "dfm-4ed6cccadb2827a8242e5722604bef0dc948f162728ddab0f935d5a2a718fa04") // Stable v1 wire identifier.
            check(actor != CotIdentity.member(workspaceB, authorA))
            check(actor != CotIdentity.member(workspaceA, authorB))
            check(CotIdentity.receivedPli(workspaceA, authorA, actor) == actor)
            check(runCatching { CotIdentity.receivedPli(workspaceA, authorB, actor) }.isFailure)
            val wireObject = CotIdentity.publishedObject(workspaceA, authorA, "host-global-object")
            check(wireObject != CotIdentity.publishedObject(workspaceB, authorA, "host-global-object"))
            check(!wireObject.contains("host-global-object"))
            check(CotIdentity.receivedObject(workspaceA, authorA, wireObject) != CotIdentity.receivedObject(workspaceA, authorB, wireObject))
            check(CotIdentity.broadcastChat(workspaceA, authorA, "message-1") ==
                "GeoChat.$actor.All Chat Rooms.${CotIdentity.publishedObject(workspaceA, authorA, "message-1")}")
            check(runCatching { CotIdentity.member(ByteArray(31), authorA) }.isFailure)
            check(runCatching { CotIdentity.publishedObject(workspaceA, authorA, " ") }.isFailure)
            check(runCatching { CotIdentity.publishedObject(workspaceA, authorA, "x".repeat(1025)) }.isFailure)
            check(runCatching { CotIdentity.publishedObject(workspaceA, authorA, "\uD800") }.isFailure)
            val pliXml = """<event version="2.0" access="Undefined" caveat="test" uid="host-device" type="a-f-G-U-C" how="m-g" time="2026-09-08T12:00:00Z" start="2026-09-08T12:00:00Z" stale="2026-09-08T12:05:00Z"><point lat="41.0" lon="-87.0" hae="100" ce="10" le="10"/><detail><contact callsign="Alpha" endpoint="10.0.0.1:4242:tcp" phone="5551234"/><uid Droid="Alpha"/><link uid="host-device" type="a-f-G-U-C" relation="p-p" production_time="2026-09-08T12:00:00Z" parent_callsign="Native-Parent"/><creator uid="host-device" type="a-f-G-U-C" callsign="Native-Creator" time="2026-09-08T12:00:00Z"/><__serverdestination destinations="10.0.0.1:4242:tcp:host-device"/></detail></event>"""
            val projected = CotProjection.outgoing(pliXml.toByteArray(), workspaceA, authorA, "host-device", "atak/pli")
            val projectedXml = CotXml.decode(projected)
            check(!projectedXml.contains("host-device") && !projectedXml.contains("10.0.0.1") && !projectedXml.contains("5551234"))
            check(projectedXml.contains(actor) && projectedXml.contains("Alpha") && projectedXml.contains("Undefined"))
            check(!projectedXml.contains("Native-Parent") && !projectedXml.contains("parent_callsign") && !projectedXml.contains("Native-Creator"))
            check(projectedXml.contains("production_time=\"2026-09-08T12:00:00Z\""))
            val namedPli = CotXml.decode(CotProjection.outgoing(pliXml.toByteArray(), workspaceA, authorA, "host-device", "atak/pli", "Jordan Lee"))
            check(namedPli.contains("Jordan Lee") && !namedPli.contains("Alpha"))
            check(CotXml.decode(CotProjection.incoming(projected, workspaceA, authorA, "atak/pli")).contains(actor))
            check(runCatching { CotProjection.incoming(projected, workspaceA, authorB, "atak/pli") }.isFailure)
            check(!CotXml.decode(CotProjection.outgoing(pliXml.toByteArray(), workspaceB, authorA, "host-device", "atak/pli")).contains(actor))
            val pointXml = pliXml.replaceFirst("uid=\"host-device\" type=", "uid=\"point-native\" type=")
            val pointWire = CotProjection.outgoing(pointXml.toByteArray(), workspaceA, authorA, "host-device", "atak/features")
            val localPoint = CotXml.decode(CotProjection.incoming(pointWire, workspaceA, authorA, "atak/features"))
            check(localPoint.contains(CotIdentity.receivedObject(workspaceA, authorA, CotIdentity.publishedObject(workspaceA, authorA, "point-native"))))
            check(runCatching { CotProjection.outgoing(pliXml.toByteArray(), workspaceA, authorA, "host-device", "atak/features") }.isFailure)
            for (bad in listOf(pliXml.replace("lat=\"41.0\"", "lat=\"NaN\""),
                pliXml.replace("<uid Droid=\"Alpha\"/>", "<uid Droid=\"Alpha\" serial=\"secret\"/>"),
                pliXml.replace("</detail>", "<unknown uid=\"secret\"/></detail>"),
                pliXml.replace("type=\"a-f-G-U-C\"", "type=\"b-t-f\""))) {
                check(runCatching { CotProjection.outgoing(bad.toByteArray(), workspaceA, authorA, "host-device", "atak/pli") }.isFailure)
            }
            val xml = "<event><detail><![CDATA[</event>]]>&amp;&#65;</detail></event>"
            check(CotXml.decode(xml.toByteArray()) == xml)
            val invalid = listOf(
                "<!DOCTYPE event [<!ENTITY secret SYSTEM 'file:///data/local/tmp/secret'>]><event>&secret;</event>".toByteArray(),
                "<event>&unknown;</event>".toByteArray(),
                "<other/>".toByteArray(), "<event/><event/>".toByteArray(),
                "<event>".toByteArray(), "<event/>trailing".toByteArray(),
                ("<event>" + "<x>".repeat(33) + "</x>".repeat(33) + "</event>").toByteArray(),
                ("<event>" + "x".repeat(16384) + "</event>").toByteArray(),
                byteArrayOf(0xc3.toByte(), 0x28)
            )
            invalid.forEachIndexed { index, bytes ->
                check(runCatching { CotXml.decode(bytes) }.isFailure) { "Invalid XML case $index accepted" }
            }
            val ready = CountDownLatch(1)
            session = FabricSession(targetContext, "endpoint-v1") { if (it.startsWith("Fabric engine running.")) ready.countDown() }
            check(ready.await(15, TimeUnit.SECONDS)) { "Native engine startup timed out" }
            val savedMember = AtomicReference<ByteArray>()
            val created = CountDownLatch(1)
            val workspaceFailure = AtomicReference<Throwable?>()
            check(session.request("{\"op\":\"create_workspace\",\"display_name\":\"Alex Morgan\"}".toByteArray()) { response ->
                try {
                    val state = org.json.JSONObject(String(response.getOrThrow()))
                    check(state.getJSONArray("workspace").length() == 32)
                    check(state.getLong("epoch") == 0L && state.getInt("members") == 1)
                    check(!state.getBoolean("durable"))
                    val profile = state.getJSONObject("member")
                    check(profile.getString("display_name") == "Alex Morgan")
                    val memberId = profile.getJSONArray("id").bytes()
                    check(memberId.size == 32)
                    check(!memberId.contentEquals(state.getJSONArray("workspace").bytes()))
                    savedMember.set(memberId)
                } catch (error: Throwable) { workspaceFailure.set(error) }
                finally { created.countDown() }
            })
            check(created.await(15, TimeUnit.SECONDS)) { "Workspace creation timed out" }
            workspaceFailure.get()?.let { throw it }
            val rejected = CountDownLatch(1)
            check(session.request("{\"op\":\"create_workspace\",\"display_name\":\"Alex Morgan\"}".toByteArray()) { response ->
                if (response.isSuccess) workspaceFailure.set(IllegalStateException("Workspace replaced"))
                rejected.countDown()
            })
            check(rejected.await(15, TimeUnit.SECONDS))
            workspaceFailure.get()?.let { throw it }
            val snapshotDone = CountDownLatch(1)
            val store = WorkspaceStore(targetContext)
            check(session.request("{\"op\":\"seal_workspace\"}".toByteArray()) { response ->
                try {
                    val sealed = org.json.JSONObject(String(response.getOrThrow()))
                    val id = sealed.getJSONArray("workspace").bytes()
                    val ciphertext = sealed.getJSONArray("snapshot").bytes()
                    store.save(id, ciphertext)
                    check(store.load(id).contentEquals(ciphertext))
                    savedWorkspace.set(id)
                } catch (error: Throwable) { workspaceFailure.set(error) }
                finally { snapshotDone.countDown() }
            })
            check(snapshotDone.await(15, TimeUnit.SECONDS))
            workspaceFailure.get()?.let { throw it }
            val poll = "{\"op\":\"poll_protected\"}".toByteArray()
            val entered = CountDownLatch(1)
            val done = CountDownLatch(64)
            val failure = AtomicReference<Throwable?>()
            check(!session.request(ByteArray(128 * 1024 + 1)) {}) { "Oversized request admitted" }
            check(session.request(poll) { response ->
                try {
                    check(String(response.getOrThrow()) == "null")
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Queue test release timed out" }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { done.countDown() }
            })
            check(entered.await(10, TimeUnit.SECONDS)) { "Worker callback did not run" }
            repeat(63) {
                val request = poll.copyOf()
                check(session.request(request) { response ->
                    try { check(String(response.getOrThrow()) == "null") }
                    catch (error: Throwable) { failure.compareAndSet(null, error) }
                    finally { done.countDown() }
                }) { "Request $it rejected before capacity" }
                request.fill(0) // An admitted request must own its bytes.
            }
            check(!session.request(poll) {}) { "Queue capacity exceeded" }
            check(!session.receive {}) { "Consumer update bypassed queue capacity" }
            session.close() // Must admit cleanup even while the request queue is full.
            check(!session.request(poll) {}) { "Closed session admitted request" }
            check(!session.receive {}) { "Closed session admitted consumer" }
            release.countDown()
            check(done.await(15, TimeUnit.SECONDS)) { "Close lost admitted requests" }
            failure.get()?.let { throw it }
            session.close() // Idempotent.
            check(session.awaitClosed(15, TimeUnit.SECONDS)) { "Closed session retained its credential lock" }
            val resumed = CountDownLatch(1)
            session = FabricSession(targetContext, "endpoint-v1") { if (it.startsWith("Fabric engine running.")) resumed.countDown() }
            check(resumed.await(15, TimeUnit.SECONDS))
            val workspaceId = checkNotNull(savedWorkspace.get())
            val ciphertext = WorkspaceStore(targetContext).load(workspaceId)
            val restored = CountDownLatch(1)
            val restore = org.json.JSONObject().put("op", "restore_workspace")
                .put("workspace", org.json.JSONArray(workspaceId.map { it.toInt() and 255 }))
                .put("snapshot", org.json.JSONArray(ciphertext.map { it.toInt() and 255 }))
            check(session.request(restore.toString().toByteArray()) { response ->
                try {
                    val state = org.json.JSONObject(String(response.getOrThrow()))
                    check(state.getJSONArray("workspace").bytes().contentEquals(workspaceId))
                    val profile = state.getJSONObject("member")
                    check(profile.getString("display_name") == "Alex Morgan")
                    check(profile.getJSONArray("id").bytes().contentEquals(savedMember.get()))
                    check(state.getLong("epoch") == 0L && state.getInt("members") == 1)
                } catch (error: Throwable) { workspaceFailure.set(error) }
                finally { restored.countDown() }
            })
            check(restored.await(15, TimeUnit.SECONDS))
            workspaceFailure.get()?.let { throw it }
            val invitation = pendingCall(session, org.json.JSONObject().put("op", "issue_invitation"))
            val (retryRequest, expectedReply) = checkPendingJoin(invitation, session)
            session.close()
            check(session.awaitClosed(15, TimeUnit.SECONDS))
            val filename = workspaceId.joinToString("") { "%02x".format(it.toInt() and 255) } + ".bin"
            session = FabricSession(targetContext, "endpoint-v1") {}
            val recovered = pendingCall(session, org.json.JSONObject().put("op", "restore_workspace")
                .put("workspace", invitation.getJSONArray("workspace"))
                .put("snapshot", org.json.JSONArray(WorkspaceStore(targetContext).load(workspaceId).map { it.toInt() and 255 })))
            check(recovered.getInt("members") == 2 && recovered.getInt("epoch") == 1)
            val replayed = pendingCall(session, retryRequest)
            check(replayed.getJSONArray("welcome").toString() == expectedReply.getJSONArray("welcome").toString())
            check(replayed.getJSONArray("commit").toString() == expectedReply.getJSONArray("commit").toString())
            session.close(); check(session.awaitClosed(15, TimeUnit.SECONDS))
            check(File(targetContext.noBackupFilesDir, "data-fabric/workspaces/$filename").delete())
            result.putString("result", "PASS: protected Iroh application delivery with AtomicFile save/adopt; protected message save/adopt and persisted replay rejection; Iroh-authenticated admission request and post-save reply; verified Welcome joined with retained history, saved before pending retirement and restored member identity; staged admission blocks mutation and reply exposure; AtomicFile save before adoption and exact reply after native-session restart; signed invitation validation and exact authorized pending request persisted across native sessions with phase isolation; Rust MLS workspace creation with chosen name and stable member identity, encrypted AtomicFile snapshot and new-session restore, replacement rejection; Keystore identity recovery, exclusive ownership, tamper/truncation/missing-state rejection; CoT category and adversarial XML checks, actual JNI, 64 request bound, owned bytes, oversize rejection, close admission and drain")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("failure", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            release.countDown()
            session?.close()
            session?.awaitClosed(15, TimeUnit.SECONDS)
            savedWorkspace.get()?.let { id ->
                val filename = id.joinToString("") { "%02x".format(it.toInt() and 255) } + ".bin"
                for (suffix in listOf("", ".bak", ".new"))
                    File(targetContext.noBackupFilesDir, "data-fabric/workspaces/$filename$suffix").delete()
            }
        }
    }

    private fun checkPendingJoin(invitation: org.json.JSONObject, issuer: FabricSession): Pair<org.json.JSONObject, org.json.JSONObject> {
        val slot = "join-test-" + UUID.randomUUID().toString()
        val workspace = invitation.getJSONArray("workspace").bytes()
        val jsonId = org.json.JSONArray(workspace.map { it.toInt() and 255 })
        val store = WorkspaceStore(targetContext, pendingJoin = true)
        val filename = workspace.joinToString("") { "%02x".format(it.toInt() and 255) } + ".bin"
        var current: FabricSession? = null
        try {
            current = FabricSession(targetContext, slot) {}
            val begin = org.json.JSONObject().put("op", "begin_join")
                .put("invitation", invitation.getJSONArray("invitation"))
                .put("checkpoint", invitation.getJSONArray("checkpoint")).put("display_name", "Morgan Lee")
            val created = pendingCall(current, begin)
            check(created.getString("state") == "pending" && !created.getBoolean("durable"))
            val profile = created.getJSONObject("member")
            check(profile.getString("display_name") == "Morgan Lee")
            val memberId = profile.getJSONArray("id").bytes()
            val packageBytes = created.getJSONArray("key_package").bytes()
            val requestBytes = created.getJSONArray("admission_request").bytes()
            check(requestBytes.size > packageBytes.size)
            check(memberId.size == 32 && packageBytes.isNotEmpty())
            val replace = org.json.JSONObject().put("op", "create_workspace").put("display_name", "Replacement")
            check(runCatching { pendingCall(current, replace) }.exceptionOrNull()?.message?.contains("owns") == true)
            val sealed = pendingCall(current, org.json.JSONObject().put("op", "seal_pending_join"))
                .getJSONArray("snapshot").bytes()
            check(runCatching { WorkspaceStore(targetContext).save(workspace, sealed) }.isFailure)
            check(WorkspaceStore(targetContext).load(workspace).isNotEmpty())
            store.save(workspace, sealed)
            check(store.load(workspace).contentEquals(sealed))
            current.close(); check(current.awaitClosed(15, TimeUnit.SECONDS))
            current = FabricSession(targetContext, slot) {}
            val restore = org.json.JSONObject().put("op", "restore_pending_join").put("workspace", jsonId)
                .put("snapshot", org.json.JSONArray(store.load(workspace).map { it.toInt() and 255 }))
            val resumed = pendingCall(current, restore)
            check(resumed.getString("state") == "pending")
            check(resumed.getJSONObject("member").getString("display_name") == "Morgan Lee")
            check(resumed.getJSONObject("member").getJSONArray("id").bytes().contentEquals(memberId))
            check(resumed.getJSONArray("key_package").bytes().contentEquals(packageBytes))
            check(resumed.getJSONArray("admission_request").bytes().contentEquals(requestBytes))
            check(runCatching { pendingCall(current, begin) }.exceptionOrNull()?.message?.contains("owns") == true)
            pendingCall(current, org.json.JSONObject().put("op", "add_address_hint")
                .put("peer", invitation.getJSONArray("peer"))
                .put("address", invitation.getString("address").replace("0.0.0.0:", "127.0.0.1:")))
            val networkDone = CountDownLatch(1)
            val networkReply = AtomicReference<Result<ByteArray>>()
            val networkRequest = org.json.JSONObject().put("op", "request_admission").put("peer", invitation.getJSONArray("peer"))
            check(current.request(networkRequest.toString().toByteArray(Charsets.UTF_8)) { networkReply.set(it); networkDone.countDown() })
            var staged: org.json.JSONObject? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (staged == null && System.nanoTime() < deadline) {
                // Poll returns JSON null until the authenticated request arrives.
                val done = CountDownLatch(1)
                val response = AtomicReference<Result<ByteArray>>()
                check(issuer.request("{\"op\":\"poll_admission\"}".toByteArray()) { response.set(it); done.countDown() })
                check(done.await(1, TimeUnit.SECONDS))
                val text = String(checkNotNull(response.get()).getOrThrow(), Charsets.UTF_8)
                if (text != "null") {
                    val candidate = org.json.JSONObject(text)
                    if (candidate.has("snapshot")) staged = candidate
                } else Thread.sleep(10)
            }
            val receivedStage = checkNotNull(staged) { "No Iroh admission received" }
            check(!receivedStage.has("welcome") && !receivedStage.has("commit"))
            check(runCatching { pendingCall(issuer, org.json.JSONObject().put("op", "seal_workspace")) }.isFailure)
            val incorrect = receivedStage.getJSONArray("snapshot").bytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            check(runCatching { pendingCall(issuer, org.json.JSONObject().put("op", "adopt_admission")
                .put("snapshot", org.json.JSONArray(incorrect.map { it.toInt() and 255 }))) }.isFailure)
            var adoptionCalled = false
            check(runCatching { store.commitAdmission(receivedStage) { adoptionCalled = true; pendingCall(issuer, it) } }.isFailure)
            check(!adoptionCalled)
            check(runCatching { pendingCall(issuer, org.json.JSONObject().put("op", "send_admission_reply")) }.isFailure)
            check(networkDone.await(5, TimeUnit.SECONDS))
            val queuedReply = org.json.JSONObject(String(checkNotNull(networkReply.get()).getOrThrow(), Charsets.UTF_8))
            check(queuedReply.getString("state") == "admission_queued")
            val adopted = WorkspaceStore(targetContext).commitAdmission(receivedStage) { pendingCall(issuer, it) }
            check(adopted.getInt("members") == 2 && adopted.getInt("epoch") == 1)
            val retry = org.json.JSONObject().put("op", "retained_admission")
                .put("authenticated_endpoint", resumed.getJSONArray("endpoint"))
                .put("request", resumed.getJSONArray("admission_request"))
            val finalNetworkDone = CountDownLatch(1)
            val finalNetworkReply = AtomicReference<Result<ByteArray>>()
            check(current.request(networkRequest.toString().toByteArray(Charsets.UTF_8)) {
                finalNetworkReply.set(it); finalNetworkDone.countDown()
            })
            var finalPoll = org.json.JSONObject()
            val finalPollDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!finalPoll.has("state") && System.nanoTime() < finalPollDeadline) {
                finalPoll = pendingCall(issuer, org.json.JSONObject().put("op", "poll_admission"))
                if (!finalPoll.has("state")) Thread.sleep(10)
            }
            check(finalPoll.getString("state") == "admission_replied")
            check(finalNetworkDone.await(5, TimeUnit.SECONDS))
            val reply = org.json.JSONObject(String(checkNotNull(finalNetworkReply.get()).getOrThrow(), Charsets.UTF_8))
            check(reply.getJSONArray("welcome").toString() == pendingCall(issuer, retry).getJSONArray("welcome").toString())
            val join = org.json.JSONObject().put("op", "stage_join").put("welcome", reply.getJSONArray("welcome"))
                .put("commits", org.json.JSONArray().put(org.json.JSONObject()
                    .put("commit", reply.getJSONArray("commit")).put("authorization", reply.getJSONObject("authorization"))))
            val badJoin = org.json.JSONObject(join.toString()).put("welcome", org.json.JSONArray().put(0))
            check(runCatching { pendingCall(current, badJoin) }.isFailure)
            check(pendingCall(current, org.json.JSONObject().put("op", "seal_pending_join")).has("snapshot"))
            val stagedJoin = pendingCall(current, join)
            // Separate test-owned root represents the joiner's device without overwriting the issuer's same-workspace file.
            val joinedContext = object : android.content.ContextWrapper(targetContext) {
                override fun getNoBackupFilesDir() = File(targetContext.noBackupFilesDir, "join-store-$slot")
            }
            val joinedStore = WorkspaceStore(joinedContext)
            val joiningSession = checkNotNull(current)
            val joined = joinedStore.commitJoin(stagedJoin, store) { pendingCall(joiningSession, it) }
            check(joined.getInt("members") == 2 && joined.getInt("epoch") == 1)
            check(runCatching { store.load(workspace) }.isFailure)
            check(runCatching { pendingCall(current, org.json.JSONObject().put("op", "seal_pending_join")) }.isFailure)
            current.close(); check(current.awaitClosed(15, TimeUnit.SECONDS))
            current = FabricSession(targetContext, slot) {}
            val restoredJoin = pendingCall(current, org.json.JSONObject().put("op", "restore_workspace").put("workspace", jsonId)
                .put("snapshot", org.json.JSONArray(joinedStore.load(workspace).map { it.toInt() and 255 })))
            check(restoredJoin.getJSONObject("member").getJSONArray("id").bytes().contentEquals(memberId))
            check(restoredJoin.getInt("members") == 2)
            for (owner in listOf(issuer, checkNotNull(current))) {
                val policy = org.json.JSONObject().put("op", "install_member_policy").put("revision", 17)
                    .put("topics", org.json.JSONArray().put("streams/sample"))
                val installed = pendingCall(owner, policy)
                check(installed.getInt("members") == 2 && installed.getInt("revision") == 17)
                check(runCatching { pendingCall(owner, policy) }.isFailure)
                check(runCatching { pendingCall(owner, org.json.JSONObject().put("op", "install_verified_policy")
                    .put("workspace", jsonId).put("revision", 18).put("endpoints", org.json.JSONArray())) }.isFailure)
            }
            val networkReceiver = checkNotNull(current)
            pendingCall(networkReceiver, org.json.JSONObject().put("op", "add_address_hint")
                .put("peer", invitation.getJSONArray("peer"))
                .put("address", invitation.getString("address").replace("0.0.0.0:", "127.0.0.1:")))
            val subscription = pendingCall(networkReceiver, org.json.JSONObject().put("op", "subscribe")
                .put("workspace", jsonId).put("revision", 17).put("topic", "streams/sample"))
            check(subscription.getJSONArray("failed").length() == 0)
            val networkStage = pendingCall(issuer, org.json.JSONObject().put("op", "stage_network_publication")
                .put("revision", 17).put("topic", "streams/sample")
                .put("id", org.json.JSONArray(List(16) { 10 }))
                .put("payload", org.json.JSONArray().put(0).put(255).put(77)))
            check(!networkStage.has("ciphertext"))
            val sent = WorkspaceStore(targetContext).commitPublication(networkStage) { pendingCall(issuer, it) }
            check(sent.getJSONObject("admission").getJSONArray("failed").length() == 0)
            var incomingNetwork = org.json.JSONObject()
            val networkDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!incomingNetwork.has("state") && System.nanoTime() < networkDeadline) {
                incomingNetwork = pendingCall(networkReceiver, org.json.JSONObject().put("op", "poll_protected"))
                if (!incomingNetwork.has("state")) Thread.sleep(10)
            }
            check(incomingNetwork.has("state")) { "Queued publication did not reach the receiver" }
            check(!incomingNetwork.has("payload"))
            val networkDelivered = joinedStore.commitReception(incomingNetwork) { pendingCall(networkReceiver, it) }
            check(networkDelivered.getJSONArray("payload").bytes().contentEquals(byteArrayOf(0, -1, 77)))
            check(networkDelivered.getString("topic") == "streams/sample")
            check(networkDelivered.getJSONArray("id").bytes().contentEquals(ByteArray(16) { 10 }))
            val context = org.json.JSONArray().put(1).put(2).put(3)
            val publication = org.json.JSONObject().put("op", "stage_publication")
                .put("context", context).put("payload", org.json.JSONArray().put(0).put(255).put(42))
            val stagedPublication = pendingCall(issuer, publication)
            check(!stagedPublication.has("ciphertext") && !stagedPublication.has("payload"))
            check(runCatching { pendingCall(issuer, publication) }.isFailure)
            var releasedBeforeSave = false
            check(runCatching { store.commitPublication(stagedPublication) {
                releasedBeforeSave = true; pendingCall(issuer, it)
            } }.isFailure)
            check(!releasedBeforeSave)
            val released = WorkspaceStore(targetContext).commitPublication(stagedPublication) { pendingCall(issuer, it) }
            val receive = org.json.JSONObject().put("op", "stage_reception")
                .put("context", context).put("ciphertext", released.getJSONArray("ciphertext"))
            val wrongContext = org.json.JSONObject(receive.toString()).put("context", org.json.JSONArray().put(9))
            check(runCatching { pendingCall(current, wrongContext) }.isFailure)
            val stagedReception = pendingCall(current, receive)
            check(!stagedReception.has("payload") && !stagedReception.has("member"))
            check(runCatching { pendingCall(current, org.json.JSONObject().put("op", "poll")) }.isFailure)
            val receivingSession = checkNotNull(current)
            val delivered = joinedStore.commitReception(stagedReception) { pendingCall(receivingSession, it) }
            check(delivered.getJSONArray("payload").bytes().contentEquals(byteArrayOf(0, -1, 42)))
            check(delivered.getJSONArray("endpoint").toString() == invitation.getJSONArray("peer").toString())
            current.close(); check(current.awaitClosed(15, TimeUnit.SECONDS))
            current = FabricSession(targetContext, slot) {}
            pendingCall(current, org.json.JSONObject().put("op", "restore_workspace").put("workspace", jsonId)
                .put("snapshot", org.json.JSONArray(joinedStore.load(workspace).map { it.toInt() and 255 })))
            check(runCatching { pendingCall(current, receive) }.isFailure)
            check(pendingCall(current, org.json.JSONObject().put("op", "seal_workspace")).has("snapshot"))
            return Pair(retry, reply)
        } finally {
            current?.close()
            current?.let {
                if (!it.awaitClosed(15, TimeUnit.SECONDS)) {
                    android.util.Log.e("Arachne", "TEST_SESSION_CLOSE_TIMEOUT")
                }
            }
            File(targetContext.noBackupFilesDir, "join-store-$slot").deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.deleteEntry("dev.datafabric.$slot")
            for (suffix in listOf(".bin", ".bin.bak", ".bin.new", ".lock")) {
                File(targetContext.noBackupFilesDir, "data-fabric/$slot$suffix").delete()
            }
            for (suffix in listOf("", ".bak", ".new")) {
                File(targetContext.noBackupFilesDir, "data-fabric/pending-joins/$filename$suffix").delete()

            }
        }
    }

    private fun pendingCall(session: FabricSession, request: org.json.JSONObject): org.json.JSONObject {
        val done = CountDownLatch(1)
        val response = AtomicReference<Result<ByteArray>>()
        check(session.request(request.toString().toByteArray(Charsets.UTF_8)) { response.set(it); done.countDown() })
        check(done.await(20, TimeUnit.SECONDS)) { "Pending join request timed out" }
        val text = String(checkNotNull(response.get()).getOrThrow(), Charsets.UTF_8)
        return if (text == "null") org.json.JSONObject() else org.json.JSONObject(text)
    }

    private fun org.json.JSONArray.bytes(): ByteArray = ByteArray(length()) { getInt(it).also { value -> check(value in 0..255) }.toByte() }

    private fun checkIdentityStorage() {
        // Dedicated random namespace: never modify the endpoint used by ATAK or
        // the session queue check. Delete only this test's key and files.
        val name = "identity-test-" + UUID.randomUUID().toString()
        val file = File(targetContext.noBackupFilesDir, "data-fabric/$name.bin")
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val repairName = "$name-debug-repair"
        var expected = ByteArray(0)
        try {
            EndpointIdentity.open(targetContext, repairName).use { check(it.secret.size == 32) }
            val repairFile = File(file.parentFile, "$repairName.bin")
            check(repairFile.delete())
            EndpointIdentity.repairMissingDebugIdentity(targetContext, repairName)
            EndpointIdentity.open(targetContext, repairName).close()
            EndpointIdentity.open(targetContext, name).use { identity ->
                expected = identity.secret.copyOf()
                check(expected.size == 32)
                check(runCatching { EndpointIdentity.open(targetContext, name).close() }.isFailure) {
                    "Concurrent credential ownership allowed"
                }
                EndpointIdentity.open(targetContext, "$name-b").use { other ->
                    check(!other.secret.contentEquals(expected)) { "Separate credential slots share a seed" }
                }
            }
            EndpointIdentity.open(targetContext, name).use {
                check(it.secret.contentEquals(expected)) { "Restart changed identity" }
            }
            val saved = file.readBytes()
            check(saved.size == 61)
            // Simulate an interrupted replacement before AtomicFile.finishWrite.
            AtomicFile(file).startWrite().use { it.write(byteArrayOf(1, 2)) }
            EndpointIdentity.open(targetContext, name).use {
                check(it.secret.contentEquals(expected)) { "Uncommitted bytes replaced identity" }
            }
            val tampered = saved.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            for (invalid in listOf(tampered, saved.copyOf(60), saved + byteArrayOf(0),
                saved.copyOf().also { it[0] = 2 })) {
                file.writeBytes(invalid)
                check(runCatching { EndpointIdentity.open(targetContext, name).close() }.isFailure) {
                    "Damaged identity accepted"
                }
                check(file.readBytes().contentEquals(invalid)) { "Damaged identity silently replaced" }
            }
            check(file.delete())
            check(runCatching { EndpointIdentity.open(targetContext, name).close() }.isFailure) {
                "Missing ciphertext silently replaced"
            }
            check(!file.exists())
            file.writeBytes(saved)
            keys.deleteEntry("dev.datafabric.$name")
            check(runCatching { EndpointIdentity.open(targetContext, name).close() }.isFailure) {
                "Missing wrapping key silently replaced"
            }
            check(file.readBytes().contentEquals(saved))
            check(!keys.containsAlias("dev.datafabric.$name"))
        } finally {
            expected.fill(0)
            for (slot in listOf(name, "$name-b", repairName)) {
                keys.deleteEntry("dev.datafabric.$slot")
                for (suffix in listOf(".bin", ".bin.bak", ".bin.new", ".lock")) {
                    File(file.parentFile, slot + suffix).delete()
                }
            }
        }
    }
}
