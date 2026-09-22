package dev.arachne.atak

import com.atakmap.android.contact.Contacts
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Executes the production framer on Android's parser, without ATAK substitutes. */
internal object NativeCotFramesCheck {
    fun run(host: android.content.Context): JSONObject {
        val cases = JSONArray()
        fun scenario(name: String, run: () -> Unit) {
            run()
            cases.put(JSONObject().put("case", name).put("passed", true))
        }
        val events = listOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><event uid=\"sample\" type=\"a-f-A\"><point lat=\"1\" lon=\"2\"/><detail><contact callsign=\"Sky &amp; Sea\"/></detail></event>",
            "<event uid='chat' type='b-t-f-r'><detail><!-- </event> --><remarks><![CDATA[文字 </event> & text]]></remarks><x:extension xmlns:x='urn:sample' x:v='&quot;'>é</x:extension></detail></event>",
            "<event uid=\"unknown-type\" type=\"new-application-type\"/>"
        ).map { it.toByteArray(Charsets.UTF_8) }
        scenario("fragmented_concatenated_exact_bytes") {
            val all = events.reduce { a, b -> a + " \r\n\t".toByteArray() + b }
            for (chunk in listOf(1, 7, 8192)) {
                var closed = false
                val source = object : ByteArrayInputStream(all) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, chunk))
                    override fun close() { closed = true }
                }
                val actual = mutableListOf<ByteArray>()
                NativeCotFrames.read(source) { actual.add(it) }
                check(actual.size == events.size && actual.zip(events).all { (a, b) -> a.contentEquals(b) })
                check(!closed) { "Framer closed the caller's stream" }
            }
        }
        scenario("malformed_and_unsafe_input_stops_stream") {
            val malformed = listOf(
                "<other/>", "<event xmlns='urn:not-cot'/>", "<event><detail></event>",
                "<event>", "<event", "<event><!-- unfinished", "<event a='unfinished",
                "<event>&undefined;</event>", "<!DOCTYPE event><event/>",
                "<!DOCTYPE event [<!ENTITY x 'expanded'>]><event>&x;</event>",
                "<!DOCTYPE event SYSTEM 'file:///data/local/tmp/forbidden'><event/>",
                "<!DOCTYPE event [<!ENTITY % x SYSTEM 'https://invalid.invalid/no'>%x;]><event/>",
                "<event>" + "<x>".repeat(32) + "</x>".repeat(32) + "</event>",
                "<event a='" + "x".repeat(NativeCotFrames.MAX_BYTES) + "'/>"
            ).map { it.toByteArray() } + listOf("<event>".toByteArray() + byteArrayOf(0xff.toByte()) + "</event>".toByteArray())
            for (bad in malformed) {
                var emitted = 0
                check(runCatching { NativeCotFrames.read(bad.inputStream()) { emitted++ } }.isFailure)
                check(emitted == 0)
            }
            var emitted = 0
            check(runCatching { NativeCotFrames.read((events[0] + "<broken/>".toByteArray() + events[2]).inputStream()) { emitted++ } }.isFailure)
            check(emitted == 1) { "Parser recovered past invalid input" }
        }
        scenario("external_xml_resources_never_accessed") {
            val secret = java.io.File.createTempFile("xml-secret-", ".txt", host.cacheDir)
            secret.writeText("forbidden-xml-content")
            val connections = AtomicInteger()
            java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 100
                val stopped = java.util.concurrent.atomic.AtomicBoolean()
                val observer = Thread {
                    while (!stopped.get()) try {
                        server.accept().use { socket ->
                            connections.incrementAndGet()
                            socket.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        }
                    } catch (_: java.net.SocketTimeoutException) { }
                      catch (_: java.net.SocketException) { if (!stopped.get()) throw IllegalStateException("Observer failed") }
                }.apply { start() }
                try {
                    val url = "http://127.0.0.1:${server.localPort}/forbidden"
                    val unsafe = listOf(
                        "<!DOCTYPE event [<!ENTITY x SYSTEM '${secret.toURI()}'>]><event>&x;</event>",
                        "<!DOCTYPE event [<!ENTITY x SYSTEM '$url'>]><event>&x;</event>",
                        "<!DOCTYPE event SYSTEM '$url'><event/>",
                        "<!DOCTYPE event [<!ENTITY % x SYSTEM '$url'>%x;]><event/>",
                        "<!DOCTYPE event [<!ENTITY a 'expanded'><!ENTITY b '&a;&a;&a;'>]><event>&b;</event>"
                    )
                    for (xml in unsafe) check(runCatching { NativeCotProjection.parse(xml.toByteArray()) }.isFailure)
                    val valid = "<?xml-stylesheet type='text/xsl' href='$url'?><event><detail>safe</detail></event>"
                    val encoded = NativeCotProjection.encode(NativeCotProjection.parse(valid.toByteArray()))
                    check(!String(encoded).contains("forbidden-xml-content"))
                    check(NativeCotProjection.parse(encoded).documentElement.textContent == "safe")
                    check(connections.get() == 0) { "XML seam fetched an external resource" }
                } finally { stopped.set(true); server.close(); observer.join(2000); check(!observer.isAlive); check(secret.delete()) }
            }
        }
        scenario("event_completes_without_next_event_bytes") {
            for (size in listOf(155, 512, 715, 716, 1024, 4096, 8192)) {
                val prefix = "<?xml version='1.0'?><event><detail>"
                val suffix = "</detail></event>"
                val event = (prefix + "x".repeat(size - prefix.length - suffix.length) + suffix).toByteArray()
                var completed = 0
                val source = object : InputStream() {
                    var offset = 0
                    override fun read(): Int {
                        check(offset < (completed + 1) * event.size) { "Framer needs next event before completing $size-byte event" }
                        if (offset == event.size * 2) return -1
                        return event[offset++ % event.size].toInt() and 255
                    }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        val value = read()
                        if (value < 0) return -1
                        bytes[offset] = value.toByte()
                        return 1
                    }
                }
                NativeCotFrames.read(source) { check(it.contentEquals(event)); completed++ }
                check(completed == 2)
            }
        }
        scenario("payload_and_depth_boundaries") {
            val prefix = "<event><detail><!--"
            val suffix = "--></detail></event>"
            val exact = prefix + "x".repeat(NativeCotFrames.MAX_BYTES - prefix.length - suffix.length) + suffix
            var size = 0
            NativeCotFrames.read(exact.byteInputStream()) { size = it.size }
            check(size == NativeCotFrames.MAX_BYTES)
            check(runCatching { NativeCotFrames.read((prefix + "x" + exact.removePrefix(prefix)).byteInputStream()) { error("Oversize callback") } }.exceptionOrNull() is org.xml.sax.SAXException)
            var depthAccepted = false
            NativeCotFrames.read(("<event>" + "<x>".repeat(31) + "</x>".repeat(31) + "</event>").byteInputStream()) { depthAccepted = true }
            check(depthAccepted)
        }
        scenario("synchronous_backpressure_and_callback_failure") {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val count = AtomicInteger()
            val error = AtomicReference<Throwable>()
            val thread = Thread {
                try {
                    NativeCotFrames.read((events[0] + events[1]).inputStream()) {
                        if (count.incrementAndGet() == 1) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    }
                } catch (failure: Throwable) { error.set(failure) }
            }
            thread.start()
            try { check(entered.await(10, TimeUnit.SECONDS)); check(count.get() == 1) }
            finally { release.countDown(); thread.join(10000) }
            check(!thread.isAlive && error.get() == null && count.get() == 2)
            val consumerError = IllegalStateException("Consumer failure")
            var callbacks = 0
            val thrown = runCatching { NativeCotFrames.read((events[0] + events[1]).inputStream()) { callbacks++; throw consumerError } }.exceptionOrNull()
            check(thrown === consumerError && callbacks == 1)
        }
        val workspace = ByteArray(32) { 7 }
        val otherWorkspace = ByteArray(32) { 8 }
        val member = ByteArray(32) { 9 }
        val otherMember = ByteArray(32) { 10 }
        val selfUid = "Android-native-identity"
        val actor = CotIdentity.member(workspace, member)
        fun event(uid: String, type: String, detail: String) = "<event version='2.0' uid='$uid' type='$type' how='m-g' time='2026-09-09T22:00:00Z' start='2026-09-09T22:00:00Z' stale='2026-09-10T22:00:00Z'><point lat='38' lon='-77' hae='0' ce='10' le='10'/><detail>$detail</detail></event>".toByteArray()
        fun send(bytes: ByteArray, scope: ByteArray = workspace) = checkNotNull(NativeCotProjection.outgoing(NativeCotProjection.parse(bytes), scope, member, selfUid))
        scenario("native_projection_preserves_atak_ids_and_scopes_wire_ids") {
            val pli = event(selfUid, "a-f-G-U-C", "<contact callsign='Field One' endpoint='10.0.2.15:4242:tcp'/><uid Droid='Field One'/><link uid='$selfUid' type='a-f-G-U-C' relation='p-p'/><ext:data xmlns:ext='urn:example' value='42'><ext:child><![CDATA[Text < & >]]></ext:child></ext:data>")
            val sent = send(pli)
            check(sent.topic == "atak/native/v1/pli" && !String(sent.bytes).contains(selfUid))
            val current = checkNotNull(sent.current)
            check(current.selector.contentEquals(java.security.MessageDigest.getInstance("SHA-256")
                .digest(sent.topic.toByteArray(Charsets.UTF_8))))
            check(current.replacementKey.contentEquals(member))
            check(current.expiresAt == java.time.Instant.parse("2026-09-10T22:00:00Z").epochSecond)
            val wire = NativeCotProjection.parse(sent.bytes)
            check(wire.documentElement.getAttribute("uid") == actor)
            check(NativeCotProjection.elements(wire.documentElement, "contact").single().getAttribute("callsign") == "Field One")
            check(NativeCotProjection.elements(wire.documentElement, "contact").single().getAttribute("endpoint") == "*:-1:stcp")
            check(wire.getElementsByTagNameNS("urn:example", "child").item(0).textContent == "Text < & >")
            check(!sent.bytes.contentEquals(send(pli, otherWorkspace).bytes))
            check(NativeCotProjection.incoming(sent.bytes, workspace, member, sent.topic).documentElement.getAttribute("uid") == selfUid)
            check(runCatching { NativeCotProjection.incoming(sent.bytes, workspace, otherMember, sent.topic) }.isFailure)
            val feature = send(event("plugin-object", "future-native-type", "<unknown preserved='yes'/>"))
            check(feature.topic == "atak/native/v1/events")
            check(feature.current?.selector?.contentEquals(WorkspaceCurrent.selector(feature.topic)) == true)
            val a = NativeCotProjection.incoming(feature.bytes, workspace, member, feature.topic).documentElement.getAttribute("uid")
            val b = NativeCotProjection.incoming(feature.bytes, workspace, otherMember, feature.topic).documentElement.getAttribute("uid")
            check(a == "plugin-object" && b == a)
        }
        scenario("native_point_has_stable_current_state_identity") {
            val first = send(event("field-point", "a-f-G-U-C", "<contact callsign='Checkpoint'/>"))
            val second = send(event("field-point", "a-f-G-U-C", "<contact callsign='Updated checkpoint'/>"))
            check(first.topic == "atak/native/v1/features" && second.topic == first.topic)
            val firstCurrent = checkNotNull(first.current)
            val secondCurrent = checkNotNull(second.current)
            check(firstCurrent.selector.contentEquals(WorkspaceCurrent.selector(first.topic)))
            check(firstCurrent.replacementKey.contentEquals(secondCurrent.replacementKey))
            check(firstCurrent.expiresAt == java.time.Instant.parse("2026-09-10T22:00:00Z").epochSecond)
        }
        scenario("native_map_tools_retain_latest_lifecycle_event") {
            for ((uid, type) in listOf("route-1" to "b-m-r", "drawing-1" to "u-d-f", "emergency-1" to "b-a-o-tbl")) {
                val created = send(event(uid, type, "<link uid='$uid' relation='p-p' type='$type'/><tool extension='created'/>"))
                val updated = send(event(uid, type, "<link uid='$uid' relation='p-p' type='$type'/><tool extension='updated'/>"))
                val deleted = send(event("delete-$uid", "t-x-d-d", "<link uid='$uid' relation='p-p' type='$type'/><__forcedelete/>"))
                check(created.topic == CotTopics.native(type, false) && updated.topic == created.topic && deleted.topic == created.topic)
                check(created.current?.replacementKey?.contentEquals(updated.current?.replacementKey) == true)
                check(created.current?.replacementKey?.contentEquals(deleted.current?.replacementKey) == true)
                val received = NativeCotProjection.incoming(deleted.bytes, workspace, member, deleted.topic)
                check(NativeCotProjection.elements(received.documentElement, "link").single().getAttribute("uid") != received.documentElement.getAttribute("uid"))
            }
        }
        val chatDetail = "<__chat id='All Chat Rooms' messageId='native-message' chatroom='All Chat Rooms' senderCallsign='Field One' parent='RootContactGroup'><chatgrp id='All Chat Rooms' uid0='$selfUid' uid1='All Chat Rooms'/></__chat><link uid='$selfUid' type='a-f-G-U-C' relation='p-p'/><__serverdestination destinations='10.0.2.15:4242:tcp:$selfUid'/><remarks source='BAO.F.ATAK.$selfUid' to='All Chat Rooms'>Native &amp; text</remarks>"
        val nativeChat = event("GeoChat.$selfUid.All Chat Rooms.native-message", "b-t-f", chatDetail)
        scenario("native_chat_preserves_semantics_and_author") {
            val sent = send(nativeChat)
            check(sent.topic == "atak/native/v1/chat" && !String(sent.bytes).contains(selfUid) && !String(sent.bytes).contains("10.0.2.15"))
            val received = NativeCotProjection.incoming(sent.bytes, workspace, member, sent.topic)
            val chat = checkNotNull(NativeCotProjection.chat(received.documentElement))
            check(chat.getAttribute("id") == "All Chat Rooms" && chat.getAttribute("senderCallsign") == "Field One")
            check(chat.getAttribute("messageId").startsWith("dfl-"))
            check(NativeCotProjection.elements(received.documentElement, "remarks").single().textContent == "Native & text")
            check(runCatching { NativeCotProjection.incoming(sent.bytes, workspace, otherMember, sent.topic) }.isFailure)
        }
        scenario("overlapping_workspaces_coalesce_native_broadcast_chat") {
            val first = send(nativeChat)
            val second = send(nativeChat, otherWorkspace)
            val firstDocument = NativeCotProjection.incoming(first.bytes, workspace, member, first.topic)
            val secondDocument = NativeCotProjection.incoming(second.bytes, otherWorkspace, member, second.topic)
            val firstId = checkNotNull(NativeCotProjection.deliveryId(firstDocument))
            check(firstId == NativeCotProjection.deliveryId(secondDocument) && !first.bytes.contentEquals(second.bytes))
            check(NativeCotProjection.elements(firstDocument.documentElement, "__arachne").isEmpty())
            val deduplicator = NativeDeliveryDeduplicator()
            val results = mutableListOf<Boolean>()
            var injections = 0
            lateinit var finish: (Boolean) -> Unit
            deduplicator.deliver(firstId, results::add) { injections++; finish = it }
            deduplicator.deliver(firstId, results::add) { injections++ }
            check(injections == 1 && results.isEmpty())
            finish(true)
            check(results == listOf(true, true))
            deduplicator.deliver(firstId, results::add) { injections++ }
            check(injections == 1 && results == listOf(true, true, true))
            val retry = "0".repeat(64)
            deduplicator.deliver(retry, results::add) { injections++; it(false) }
            deduplicator.deliver(retry, results::add) { injections++; it(true) }
            check(injections == 3 && results.takeLast(2) == listOf(false, true))

            val route = event("shared-route", "b-m-r", "<link uid='shared-route' relation='p-p' type='b-m-r'/>")
            val firstRoute = send(route)
            val secondRoute = send(route, otherWorkspace)
            val firstRouteDocument = NativeCotProjection.incoming(firstRoute.bytes, workspace, member, firstRoute.topic)
            val secondRouteDocument = NativeCotProjection.incoming(secondRoute.bytes, otherWorkspace, member, secondRoute.topic)
            check(checkNotNull(NativeCotProjection.deliveryId(firstRouteDocument)) ==
                NativeCotProjection.deliveryId(secondRouteDocument))
        }
        scenario("native_recipient_intent_and_echo_fail_closed") {
            for (detail in listOf(chatDetail + "<marti><dest callsign='Peer'/></marti>", chatDetail.replace("id='All Chat Rooms'", "id='Private group'")))
                check(runCatching { send(event("chat", "b-t-f", detail)) }.isFailure)
            check(runCatching { send(event("receipt", "b-t-f-r", "<__chatreceipt messageId='native-message'/>")) }.isFailure)
            check(NativeCotProjection.outgoing(NativeCotProjection.parse(event(actor, "a-f-G-U-C", "")), workspace, member, selfUid) == null)
            check(NativeCotProjection.outgoing(NativeCotProjection.parse(event("dfl-imported", "u-d-f", "")), workspace, member, selfUid) == null)
            check(CotTopics.native("t-x-c-t", false) == null && CotTopics.native("b-t-f-r", false) in CotTopics.nativeDefaults)
        }
        val preferences = (0..5).map { host.getSharedPreferences("native-routing-check-${System.nanoTime()}-$it", 0) }
        val thirdMember = ByteArray(32) { 11 }
        fun roster(self: ByteArray) = listOf(member, otherMember, thirdMember).map { identity ->
            WorkspaceMember(identity.joinToString("") { "%02x".format(it) }, "Test member", false, identity.contentEquals(self))
        }
        fun router(identity: ByteArray, uid: String, index: Int) = NativeChatRouting(workspace, identity, uid,
            preferences[index], preferences[index + 2], preferences[index + 4]).also { it.members = roster(identity) }
        val target = CotIdentity.member(workspace, otherMember)
        val receiverUid = "Android-second-native-identity"
        val senderRouting = router(member, selfUid, 0)
        val receiverRouting = router(otherMember, receiverUid, 1)
        fun addressed(room: String, recipient: String, uid: String = selfUid, message: String = "message") =
            event("GeoChat.$uid.$room.$message", "b-t-f",
                "<__chat id='$room' messageId='$message' chatroom='Native team' senderCallsign='Test' parent='RootContactGroup'><chatgrp id='$room' uid0='$uid' uid1='$recipient'/></__chat><link uid='$uid' relation='p-p'/><remarks to='$room'>Native text</remarks><marti><dest callsign='Test'/><extension value='preserved'/></marti>")
        try {
            scenario("native_recipient_unique_callsign_fallback_and_ambiguity") {
                val named = router(member, selfUid, 0).also { routing ->
                    routing.members = listOf(
                        WorkspaceMember(member.joinToString("") { "%02x".format(it.toInt() and 255) }, "HEWN", true, true),
                        WorkspaceMember(otherMember.joinToString("") { "%02x".format(it.toInt() and 255) }, "JOSA", false, false),
                        WorkspaceMember(thirdMember.joinToString("") { "%02x".format(it.toInt() and 255) }, "BIG RED", false, false),
                    )
                }
                check(named.selectedMembers(listOf("JOSA")).single().contentEquals(otherMember))
                check(runCatching { senderRouting.selectedMembers(listOf("Test member")) }.isFailure)
            }
            scenario("native_pli_selection_preserves_report_and_excludes_other_members") {
                val bytes = event(selfUid, "a-f-G-U-C", "<contact callsign='Test'/>")
                val native = NativeCotProjection.positionReport(bytes, selfUid, 0)
                check(runCatching { NativeCotProjection.positionReport(bytes, selfUid, java.time.Instant.parse(native.documentElement.getAttribute("stale")).toEpochMilli()) }.isFailure)
                check(runCatching { NativeCotProjection.positionReport(bytes, receiverUid, 0) }.isFailure)
                val selected = senderRouting.selectedMembers(listOf(target))
                check(selected.single().contentEquals(otherMember))
                check(runCatching { senderRouting.selectedMembers(listOf("dfl-" + "0".repeat(64))) }.isFailure)
                check(runCatching { senderRouting.selectedMembers(listOf(target, target)) }.isFailure)
                check(runCatching { senderRouting.selectedMembers(listOf(CotIdentity.member(otherWorkspace, otherMember))) }.isFailure)
                val projected = checkNotNull(NativeCotProjection.outgoing(NativeCotProjection.parse(bytes), workspace, member, selfUid, senderRouting))
                val received = NativeCotProjection.incoming(projected.bytes, workspace, member, projected.topic, receiverRouting, selected)
                val before = NativeCotProjection.parse(bytes).documentElement
                for (field in listOf("time", "start", "stale")) check(received.documentElement.getAttribute(field) == before.getAttribute(field))
                check(NativeCotProjection.elements(received.documentElement, "point").single().getAttribute("lat") == NativeCotProjection.elements(before, "point").single().getAttribute("lat"))
                check(runCatching { NativeCotProjection.incoming(projected.bytes, workspace, member, projected.topic, router(thirdMember, "Third", 1), selected) }.isFailure)
                check(runCatching { NativeCotProjection.incoming(projected.bytes, workspace, member, projected.topic, receiverRouting, listOf(member)) }.isFailure)
            }
            scenario("native_direct_recipient_roundtrip_and_audience_rejection") {
                senderRouting.observeNative(otherMember, receiverUid)
                val sent = checkNotNull(NativeCotProjection.outgoing(NativeCotProjection.parse(
                    addressed(receiverUid, receiverUid, message = "stable-message")), workspace, member, selfUid, senderRouting))
                check(sent.recipients.single().contentEquals(otherMember))
                check(!String(sent.bytes).contains(selfUid))
                val wire = NativeCotProjection.parse(sent.bytes).documentElement
                check(NativeCotProjection.elements(wire, "dest").isEmpty())
                check(NativeCotProjection.elements(wire, "extension").single().getAttribute("value") == "preserved")
                val received = NativeCotProjection.incoming(sent.bytes, workspace, member, sent.topic, receiverRouting, sent.recipients)
                check(NativeCotProjection.chat(received.documentElement)!!.getAttribute("id") == receiverUid)
                check(runCatching { NativeCotProjection.incoming(sent.bytes, workspace, member, sent.topic, receiverRouting, listOf(member)) }.isFailure)
                val outside = CotIdentity.member(otherWorkspace, otherMember)
                check(runCatching { NativeCotProjection.outgoing(NativeCotProjection.parse(addressed(outside, outside)), workspace, member, selfUid, senderRouting) }.isFailure)
            }
            scenario("native_selected_map_object_preserves_audience") {
                val directed = event("selected-route", "b-m-r",
                    "<link uid='selected-route' relation='p-p' type='b-m-r'/><marti><dest callsign='$target'/></marti><extension value='preserved'/>")
                val sent = checkNotNull(NativeCotProjection.outgoing(NativeCotProjection.parse(directed), workspace, member, selfUid, senderRouting))
                check(sent.recipients.single().contentEquals(otherMember) && sent.current == null)
                val wire = NativeCotProjection.parse(sent.bytes).documentElement
                check(NativeCotProjection.elements(wire, "dest").isEmpty())
                check(NativeCotProjection.elements(wire, "extension").single().getAttribute("value") == "preserved")
                NativeCotProjection.incoming(sent.bytes, workspace, member, sent.topic, receiverRouting, sent.recipients)
                check(runCatching { NativeCotProjection.incoming(sent.bytes, workspace, member, sent.topic,
                    router(thirdMember, "Third", 1), sent.recipients) }.isFailure)
                val outside = CotIdentity.member(otherWorkspace, otherMember)
                val invalid = String(directed).replace(target, outside).toByteArray()
                check(runCatching { NativeCotProjection.outgoing(NativeCotProjection.parse(invalid), workspace, member, selfUid, senderRouting) }.isFailure)
            }
            scenario("native_direct_receipts_return_to_original_row_after_router_restart") {
                senderRouting.observeNative(otherMember, receiverUid)
                val sent = checkNotNull(NativeCotProjection.outgoing(NativeCotProjection.parse(addressed(receiverUid, receiverUid)), workspace, member, selfUid, senderRouting))
                val received = NativeCotProjection.incoming(sent.bytes, workspace, member, sent.topic, receiverRouting, sent.recipients)
                val localId = NativeCotProjection.chat(received.documentElement)!!.getAttribute("messageId")
                fun receipt(type: String) = event(localId, type,
                    "<__chatreceipt id='$selfUid' messageId='$localId'><chatgrp id='$selfUid' uid0='$receiverUid' uid1='$selfUid'/></__chatreceipt><link uid='$receiverUid' relation='p-p'/>")
                for (type in listOf("b-t-f-d", "b-t-f-r")) {
                    val returned = checkNotNull(NativeCotProjection.outgoing(NativeCotProjection.parse(receipt(type)), workspace, otherMember, receiverUid, router(otherMember, receiverUid, 1)))
                    check(returned.recipients.single().contentEquals(member))
                    check(!String(returned.bytes).contains(receiverUid) && !String(returned.bytes).contains(localId))
                    val original = NativeCotProjection.incoming(returned.bytes, workspace, otherMember, returned.topic, router(member, selfUid, 0), returned.recipients)
                    check(original.documentElement.getAttribute("uid") == "message")
                    check(original.documentElement.getAttribute("type") == type)
                    check(NativeCotProjection.elements(original.documentElement, "link").single().getAttribute("uid") == receiverUid)
                    check(NativeCotProjection.elements(original.documentElement, "__chatreceipt").single().getAttribute("messageId") == "message")
                    check(runCatching { NativeCotProjection.incoming(returned.bytes, workspace, member, returned.topic, senderRouting, returned.recipients) }.isFailure)
                    check(runCatching { NativeCotProjection.incoming(returned.bytes, workspace, otherMember, returned.topic, senderRouting, emptyList()) }.isFailure)
                    val forged = NativeCotProjection.parse(returned.bytes).apply {
                        val thirdActor = CotIdentity.member(workspace, thirdMember)
                        NativeCotProjection.elements(documentElement, "link").single().setAttribute("uid", thirdActor)
                        NativeCotProjection.elements(documentElement, "chatgrp").single().setAttribute("uid0", thirdActor)
                    }
                    check(runCatching { NativeCotProjection.incoming(NativeCotProjection.encode(forged), workspace, thirdMember, returned.topic, senderRouting, returned.recipients) }.isFailure)
                    val unknown = NativeCotProjection.parse(returned.bytes).apply {
                        documentElement.setAttribute("uid", "unknown")
                        NativeCotProjection.elements(documentElement, "__chatreceipt").single().setAttribute("messageId", "unknown")
                    }
                    check(runCatching { NativeCotProjection.incoming(NativeCotProjection.encode(unknown), workspace, otherMember, returned.topic, senderRouting, returned.recipients) }.isFailure)
                    val mismatch = NativeCotProjection.parse(returned.bytes).apply { NativeCotProjection.elements(documentElement, "__chatreceipt").single().setAttribute("messageId", "different") }
                    check(runCatching { NativeCotProjection.incoming(NativeCotProjection.encode(mismatch), workspace, otherMember, returned.topic, senderRouting, returned.recipients) }.isFailure)
                    val foreign = NativeChatRouting(otherWorkspace, member, selfUid, preferences[0], preferences[2]).also { it.members = roster(member) }
                    check(runCatching { NativeCotProjection.incoming(returned.bytes, otherWorkspace, otherMember, returned.topic, foreign, returned.recipients) }.isFailure)
                }
            }
            scenario("custom_workspace_conversation_is_not_routable") {
                val room = CotIdentity.publishedObject(workspace, ByteArray(32), "retired-workspace-chat")
                check(runCatching { NativeCotProjection.outgoing(NativeCotProjection.parse(addressed(room, room)), workspace, member, selfUid, senderRouting) }.isFailure)
                val foreign = CotIdentity.publishedObject(otherWorkspace, ByteArray(32), "retired-workspace-chat")
                check(runCatching { NativeCotProjection.outgoing(NativeCotProjection.parse(addressed(foreign, foreign)), workspace, member, selfUid, senderRouting) }.isFailure)
            }
            scenario("native_group_reply_preserves_conversation_after_router_restart") {
                val original = "native-group-uuid"
                val sent = checkNotNull(NativeCotProjection.outgoing(NativeCotProjection.parse(addressed(original, target)), workspace, member, selfUid, senderRouting))
                val received = NativeCotProjection.incoming(sent.bytes, workspace, member, sent.topic, receiverRouting, sent.recipients)
                val room = NativeCotProjection.chat(received.documentElement)!!.getAttribute("id")
                check(room == original)
                val reply = checkNotNull(NativeCotProjection.outgoing(NativeCotProjection.parse(addressed(room, actor, receiverUid)), workspace, otherMember, receiverUid, router(otherMember, receiverUid, 1)))
                val returned = NativeCotProjection.incoming(reply.bytes, workspace, otherMember, reply.topic, router(member, selfUid, 0), reply.recipients)
                check(NativeCotProjection.chat(returned.documentElement)!!.getAttribute("id") == original)
            }
            scenario("native_user_group_updates_retain_atak_root") {
                val sent = checkNotNull(NativeCotProjection.outgoing(NativeCotProjection.parse(addressed(Contacts.USER_GROUPS, target)), workspace, member, selfUid, senderRouting))
                check(NativeCotProjection.chat(NativeCotProjection.parse(sent.bytes).documentElement)!!.getAttribute("id") == Contacts.USER_GROUPS)
                val received = NativeCotProjection.incoming(sent.bytes, workspace, member, sent.topic, receiverRouting, sent.recipients)
                check(NativeCotProjection.chat(received.documentElement)!!.getAttribute("id") == Contacts.USER_GROUPS)
            }
        } finally { preferences.forEach { check(it.edit().clear().commit()) } }
        var parsed = 0
        val sample = events[0]
        val repetitions = 1000
        // Repeating stream avoids allocating the workload in one large array.
        val workload = object : InputStream() {
            var offset = 0
            override fun read(): Int = if (offset == sample.size * repetitions) -1 else sample[offset++ % sample.size].toInt() and 255
        }
        val start = System.nanoTime()
        scenario("repeated_native_event_workload") {
            NativeCotFrames.read(workload) { check(it.contentEquals(sample)); parsed++ }
            check(parsed == repetitions)
        }
        return JSONObject().put("passed", true).put("cases", cases)
            .put("parser_events", parsed).put("parser_bytes", sample.size * repetitions)
            .put("parser_elapsed_ms", (System.nanoTime() - start) / 1_000_000.0)
    }
}
