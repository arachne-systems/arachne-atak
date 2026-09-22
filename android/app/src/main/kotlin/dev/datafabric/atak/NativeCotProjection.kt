package dev.arachne.atak

import android.util.Xml
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Native CoT stays XML. Wire identifiers remain workspace-scoped while an
 * authenticated projection restores ATAK's stable entity identities locally. */
internal object NativeCotProjection {
    data class Publication(val topic: String, val bytes: ByteArray, val recipients: List<ByteArray> = emptyList(),
                           val current: WorkspaceCurrent? = null)
    private val standardGroups = setOf("All Chat Rooms", "RootContactGroup", "UserGroups")
    private val uidAttributes = setOf("uid", "bullseyeUID", "centerMarkerUID", "edgeMarkerUID")
    private const val DELIVERY_DATA = "dev.arachne.atak.broadcast-delivery"
    private const val DELIVERY_ELEMENT = "__arachne"

    fun parse(bytes: ByteArray): Document {
        require(bytes.size <= NativeCotFrames.MAX_BYTES)
        var events = 0
        NativeCotFrames.read(bytes.inputStream()) { events++ }
        require(events == 1) { "Expected one native event" }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(bytes.inputStream(), "UTF-8")
        }
        val stack = ArrayDeque<Element>()
        while (true) when (parser.nextToken()) {
            XmlPullParser.START_TAG -> {
                val namespace = parser.namespace.orEmpty().takeIf { it.isNotEmpty() }
                val qualified = parser.prefix.orEmpty().takeIf { it.isNotEmpty() }?.let { "$it:${parser.name}" } ?: parser.name
                val element = document.createElementNS(namespace, qualified)
                for (index in 0 until parser.attributeCount) {
                    val attributeNamespace = parser.getAttributeNamespace(index).orEmpty().takeIf { it.isNotEmpty() }
                    val prefix = parser.getAttributePrefix(index).orEmpty()
                    val name = parser.getAttributeName(index)
                    val attributeName = prefix.takeIf { it.isNotEmpty() }?.let { "$it:$name" } ?: name
                    element.setAttributeNS(attributeNamespace, attributeName, parser.getAttributeValue(index))
                }
                stack.lastOrNull()?.appendChild(element) ?: document.appendChild(element)
                stack.addLast(element)
            }
            XmlPullParser.TEXT -> stack.lastOrNull()?.appendChild(document.createTextNode(parser.text))
            XmlPullParser.CDSECT -> stack.lastOrNull()?.appendChild(document.createCDATASection(parser.text))
            XmlPullParser.COMMENT -> stack.lastOrNull()?.appendChild(document.createComment(parser.text))
            XmlPullParser.PROCESSING_INSTRUCTION -> Unit
            XmlPullParser.ENTITY_REF -> stack.lastOrNull()?.appendChild(
                document.createTextNode(parser.text ?: error("Native CoT entity is unresolved"))
            ) ?: error("Native CoT entity is outside the document")
            XmlPullParser.DOCDECL -> error("Native CoT DTDs are forbidden")
            XmlPullParser.END_TAG -> stack.removeLast()
            XmlPullParser.END_DOCUMENT -> return document
        }
    }

    fun encode(document: Document): ByteArray {
        val output = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }.transform(DOMSource(document), StreamResult(output))
        return output.toByteArray().also { require(it.size <= NativeCotFrames.MAX_BYTES) { "Projected CoT exceeds payload budget" } }
    }

    fun positionReport(bytes: ByteArray, selfUid: String, now: Long): Document {
        val document = parse(bytes)
        val event = document.documentElement
        require(event.getAttribute("uid") == selfUid && event.getAttribute("type").startsWith("a-")) { "Expected ATAK's own location report." }
        val expires = java.time.Instant.parse(event.getAttribute("stale")).toEpochMilli()
        require(expires > now) { "ATAK's last location report expired. Wait for its next report." }
        require(elements(event, "point").size == 1) { "ATAK's report has no single location." }
        return document
    }

    fun outgoing(document: Document, workspace: ByteArray, member: ByteArray, selfUid: String, routing: NativeChatRouting? = null): Publication? {
        val root = document.documentElement
        require(elements(root, DELIVERY_ELEMENT).isEmpty()) { "Reserved native delivery detail" }
        val nativeUid = root.getAttribute("uid")
        if (isReceipt(root)) {
            val route = requireNotNull(routing) { "Native receipt routing is not connected yet" }.outgoingReceipt(root)
            val actor = CotIdentity.member(workspace, member)
            checkChatAuthor(root, selfUid)
            fun map(value: String): String = when {
                value == selfUid -> actor
                value in standardGroups -> value
                value in route.identities -> route.identities.getValue(value)
                else -> error("Unknown native receipt reference")
            }
            root.setAttribute("uid", map(nativeUid))
            rewrite(root, ::map)
            return Publication("atak/native/v1/chat", encode(document), route.recipients)
        }
        // Native imports may later be automatically re-dispatched by ATAK.
        if (nativeUid.startsWith("dfm-") || nativeUid.startsWith("dfl-") || nativeUid.startsWith("GeoChat.dfm-")) return null
        val topic = nativeTopic(root, nativeUid == selfUid) ?: return null
        val sourceChat = chat(root)
        val delivery = sourceChat?.let {
            val message = it.getAttribute("messageId")
            require(message.isNotBlank()) { "Missing native message identity" }
            MessageDigest.getInstance("SHA-256").digest("$selfUid\u0000$message".toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        } ?: MessageDigest.getInstance("SHA-256").digest(encode(document)).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 255)
        }
        val addressed = chat(root)?.getAttribute("id")?.let { it != "All Chat Rooms" } == true
        val targets = if (sourceChat == null) directedTargets(root) else emptyList()
        val route = when {
            addressed && routing != null -> routing.outgoing(root)
            targets.isNotEmpty() && routing != null -> NativeChatRouting.Route(routing.selectedMembers(targets), emptyMap())
            else -> null
        }
        validateIntent(root, route != null)
        val actor = CotIdentity.member(workspace, member)
        val nativeIdentities = linkedMapOf<String, String>()
        fun map(value: String): String = when {
            value in standardGroups -> value
            value == selfUid -> actor.also { nativeIdentities[it] = value }
            value in route?.identities.orEmpty() -> route!!.identities.getValue(value).also {
                if (it != value) nativeIdentities[it] = value
            }
            value.startsWith("dfm-") || value.startsWith("dfl-") -> error("Cross-workspace native reference is not routable")
            else -> CotIdentity.publishedObject(workspace, member, value).also { nativeIdentities[it] = value }
        }
        val currentIdentity = currentUid(root)
        root.setAttribute("uid", map(nativeUid))
        rewrite(root, ::map)
        chat(root)?.let { detail ->
            checkChatAuthor(root, actor)
            // Receipt correlation remains a saved local alias; entity/contact
            // UIDs retain their native ATAK identity across workspaces.
            nativeIdentities.remove(detail.getAttribute("messageId"))
            val wire = "GeoChat.$actor.${detail.getAttribute("id")}.${detail.getAttribute("messageId")}"
            nativeIdentities[wire] = nativeUid
            root.setAttribute("uid", wire)
        }
        val detail = elements(root, "detail").singleOrNull() ?: document.createElement("detail").also(root::appendChild)
        detail.appendChild(document.createElement(DELIVERY_ELEMENT).apply {
            setAttribute("id", delivery)
            for ((wire, native) in nativeIdentities) appendChild(document.createElement("identity").apply {
                setAttribute("wire", wire)
                setAttribute("native", Base64.getUrlEncoder().withoutPadding().encodeToString(native.toByteArray(Charsets.UTF_8)))
            })
        })
        val current = if (route?.recipients?.isNotEmpty() == true) null else when (topic) {
            "atak/native/v1/pli" -> WorkspaceCurrent.nativePli(member, root.getAttribute("stale"))
            "atak/native/v1/chat" -> null
            else -> WorkspaceCurrent.nativeObject(
                topic,
                currentIdentity,
                root.getAttribute("stale"),
                root.getAttribute("type") == "t-x-d-d",
            )
        }
        return Publication(topic, encode(document), route?.recipients.orEmpty(), current)
    }

    fun incoming(bytes: ByteArray, workspace: ByteArray, member: ByteArray, topic: String,
                 routing: NativeChatRouting? = null, recipients: List<ByteArray> = emptyList()): Document {
        val document = parse(bytes)
        val root = document.documentElement
        val markers = elements(root, DELIVERY_ELEMENT)
        require(markers.size <= 1) { "Duplicate native delivery detail" }
        val nativeIdentities = linkedMapOf<String, String>()
        val delivery = markers.singleOrNull()?.let { marker ->
            require(marker.parentNode?.nodeName == "detail" && marker.attributes.length == 1 && marker.hasAttribute("id") &&
                marker.getAttribute("id").matches(Regex("[0-9a-f]{64}"))) { "Invalid native delivery detail" }
            val mappings = elements(marker, "identity")
            val children = (0 until marker.childNodes.length).map { marker.childNodes.item(it) }
            require(children.all { it.nodeType == Node.ELEMENT_NODE && it.nodeName == "identity" } &&
                mappings.size <= 128 && mappings.all { it.parentNode === marker && it.attributes.length == 2 &&
                it.hasAttribute("wire") && it.hasAttribute("native") }) { "Invalid native identity map" }
            for (mapping in mappings) {
                val wire = mapping.getAttribute("wire")
                val nativeBytes = Base64.getUrlDecoder().decode(mapping.getAttribute("native"))
                val native = String(nativeBytes, Charsets.UTF_8)
                require(wire.matches(Regex("(?:df[mo]-[0-9a-f]{64}|GeoChat\\..+)")) && native.isNotBlank() &&
                    nativeBytes.size <= 1024 && native.toByteArray(Charsets.UTF_8).contentEquals(nativeBytes) &&
                    !native.startsWith("dfm-") && !native.startsWith("dfl-") && !native.startsWith("dfo-") &&
                    nativeIdentities.put(wire, native) == null) {
                    "Invalid native identity mapping"
                }
            }
            marker.parentNode.removeChild(marker)
            marker.getAttribute("id")
        }
        val actor = CotIdentity.member(workspace, member)
        require(nativeTopic(root, root.getAttribute("uid") == actor) == topic) { "Native topic/type mismatch" }
        nativeIdentities[actor]?.let { routing?.observeNative(member, it) }
        if (isReceipt(root)) {
            checkChatAuthor(root, actor)
            val identities = requireNotNull(routing) { "Native receipt routing is not connected yet" }.incomingReceipt(root, member, recipients)
            fun map(value: String): String = when {
                value in standardGroups -> value
                value in identities -> identities.getValue(value)
                else -> error("Unknown native receipt reference")
            }
            root.setAttribute("uid", map(root.getAttribute("uid")))
            rewrite(root, ::map)
            return document
        }
        val detail = chat(root)
        if (detail != null) {
            require(root.getAttribute("uid") == "GeoChat.$actor.${detail.getAttribute("id")}.${detail.getAttribute("messageId")}") { "Native chat author mismatch" }
            checkChatAuthor(root, actor)
        }
        val addressed = chat(root)?.getAttribute("id")?.let { it != "All Chat Rooms" } == true
        val identities = if (addressed && routing != null) routing.incoming(root, member, recipients) else emptyMap()
        validateIntent(root, addressed && routing != null)
        if (detail == null && recipients.isNotEmpty()) {
            requireNotNull(routing) { "Native recipient routing is unavailable" }.checkMapAudience(member, recipients)
        } else require(addressed == recipients.isNotEmpty()) { "Native publication audience mismatch" }
        fun map(value: String): String = when (value) {
            in standardGroups -> value
            in nativeIdentities -> nativeIdentities.getValue(value)
            actor -> actor
            in identities -> identities.getValue(value)
            else -> CotIdentity.receivedObject(workspace, member, value)
        }
        root.setAttribute("uid", map(root.getAttribute("uid")))
        rewrite(root, ::map)
        if (delivery != null) document.setUserData(DELIVERY_DATA, delivery, null)
        return document
    }

    fun deliveryId(document: Document): String? = document.getUserData(DELIVERY_DATA) as String?

    fun elements(root: Element, name: String): List<Element> {
        val nodes = root.getElementsByTagName(name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    fun chat(root: Element): Element? = elements(root, "__chat").also { require(it.size <= 1) }.singleOrNull()

    fun isReceipt(root: Element): Boolean = root.getAttribute("type") in setOf("b-t-f-d", "b-t-f-r") || elements(root, "__chatreceipt").isNotEmpty()

    /** ATAK delete tasks name the deleted object in their link. Keep the
     * tombstone in the same recoverable category and replacement slot. */
    private fun nativeTopic(root: Element, isSelf: Boolean): String? = CotTopics.native(
        if (root.getAttribute("type") == "t-x-d-d") elements(root, "link").singleOrNull()?.getAttribute("type").orEmpty()
        else root.getAttribute("type"), isSelf)

    private fun currentUid(root: Element): String =
        if (root.getAttribute("type") == "t-x-d-d") elements(root, "link").singleOrNull()?.getAttribute("uid").orEmpty()
        else root.getAttribute("uid")

    private fun checkChatAuthor(root: Element, actor: String) {
        require(elements(root, "link").filter { it.getAttribute("relation") == "p-p" }.singleOrNull()?.getAttribute("uid") == actor) { "Native chat sender mismatch" }
        require(elements(root, "chatgrp").singleOrNull()?.getAttribute("uid0") == actor) { "Native chat group sender mismatch" }
    }

    private fun validateIntent(root: Element, addressed: Boolean = false) {
        require(addressed || elements(root, "marti").none { elements(it, "dest").isNotEmpty() }) { "Native directed routing is not connected yet" }
        require(elements(root, "__chatreceipt").isEmpty() && root.getAttribute("type") !in setOf("b-t-f-d", "b-t-f-r")) { "Native receipt routing is not connected yet" }
        chat(root)?.let {
            require(addressed || it.getAttribute("id") == "All Chat Rooms") { "Native selected-group routing is not connected yet" }
            require(it.getAttribute("messageId").isNotBlank()) { "Missing native message identity" }
        }
        if (root.getAttribute("type").startsWith("b-t-f")) require(chat(root) != null) { "Missing native chat detail" }
    }

    private fun directedTargets(root: Element): List<String> {
        val marti = elements(root, "marti")
        require(marti.size <= 1) { "Invalid native recipient list" }
        val destinations = marti.singleOrNull()?.let { elements(it, "dest") }.orEmpty()
        require(destinations.all { it.parentNode === marti.single() && it.attributes.length == 1 &&
            it.hasAttribute("callsign") && it.getAttribute("callsign").isNotBlank() }) { "Unsupported native destination" }
        return destinations.map { it.getAttribute("callsign") }
    }

    private fun rewrite(root: Element, map: (String) -> String) {
        fun visit(element: Element) {
            if (element !== root) {
                val attributes = element.attributes
                for (i in 0 until attributes.length) {
                    val attr = attributes.item(i)
                    val name = attr.nodeName
                    val tag = element.localName ?: element.tagName
                    if (name in uidAttributes || (tag == "chatgrp" && name.matches(Regex("uid[0-9]+"))) ||
                        (tag in setOf("__chat", "__chatreceipt", "chatgrp", "Style") && name == "id") ||
                        (tag in setOf("__chat", "__chatreceipt") && name in setOf("messageId", "parent", "deleteChild"))) {
                        if (attr.nodeValue.isNotEmpty()) attr.nodeValue = map(attr.nodeValue)
                    } else if (tag == "remarks" && name == "source" && attr.nodeValue.startsWith("BAO.F.ATAK.")) {
                        attr.nodeValue = "BAO.F.ATAK." + map(attr.nodeValue.removePrefix("BAO.F.ATAK."))
                    } else if (tag == "remarks" && name == "to" && attr.nodeValue.isNotEmpty()) attr.nodeValue = map(attr.nodeValue)
                }
                if (element.tagName == "contact" && element.hasAttribute("endpoint")) element.setAttribute("endpoint", "*:-1:stcp")
            }
            val children = (0 until element.childNodes.length).map { element.childNodes.item(it) }
            for (child in children) if (child.nodeType == Node.ELEMENT_NODE) {
                if (child.nodeName == "__serverdestination" || (element.nodeName == "marti" && child.nodeName == "dest")) element.removeChild(child)
                else visit(child as Element)
            }
        }
        visit(root)
    }
}
