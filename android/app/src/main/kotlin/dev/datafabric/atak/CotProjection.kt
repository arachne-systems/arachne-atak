package dev.arachne.atak

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.io.StringWriter

/** Explicit initial PLI/point profile. Unknown details reject rather than
 * escaping identity translation. Expand with source and native rendering checks. */
internal object CotProjection {
    private data class Element(val name: String, val attributes: MutableMap<String, String>, val children: MutableList<Element> = mutableListOf(), var text: String = "")
    private val fields = mapOf(
        "event" to setOf("version", "uid", "type", "how", "time", "start", "stale", "access", "qos", "opex", "caveat", "releasableTo"),
        "point" to setOf("lat", "lon", "hae", "ce", "le"),
        "detail" to emptySet(),
        "contact" to setOf("callsign"), "uid" to setOf("Droid"),
        "takv" to setOf("device", "platform", "os", "version"),
        "__group" to setOf("name", "role"), "track" to setOf("speed", "course"),
        "status" to setOf("battery", "readiness"),
        "precisionlocation" to setOf("geopointsrc", "altsrc"),
        "link" to setOf("uid", "type", "relation", "production_time"),
        "creator" to setOf("uid", "type", "time"),
        "remarks" to setOf("source", "time"), "color" to setOf("argb", "value"),
        "usericon" to setOf("iconsetpath"), "archive" to emptySet()
    )

    fun outgoing(payload: ByteArray, workspace: ByteArray, member: ByteArray, selfUid: String, topic: String, memberName: String? = null): ByteArray =
        translate(payload, workspace, member, selfUid, topic, memberName)

    fun incoming(payload: ByteArray, workspace: ByteArray, authenticatedMember: ByteArray, topic: String): ByteArray =
        translate(payload, workspace, authenticatedMember, null, topic, null)

    private fun translate(payload: ByteArray, workspace: ByteArray, member: ByteArray, selfUid: String?, topic: String, memberName: String?): ByteArray {
        require(payload.size <= 12 * 1024 && topic in setOf("atak/pli", "atak/features"))
        val root = parse(CotXml.decode(payload))
        require(root.name == "event" && root.attributes["type"]?.startsWith("a-") == true)
        val original = checkNotNull(root.attributes["uid"])
        val actor = CotIdentity.member(workspace, member)
        fun mapped(value: String): String = if (selfUid != null) {
            if (value == selfUid) actor else CotIdentity.publishedObject(workspace, member, value)
        } else {
            if (value == actor) actor else CotIdentity.receivedObject(workspace, member, value)
        }
        root.attributes["uid"] = if (topic == "atak/pli") {
            if (selfUid != null) { require(original == selfUid); actor }
            else CotIdentity.receivedPli(workspace, member, original)
        } else {
            require(original != (selfUid ?: actor)) { "Member PLI cannot be classified as an object" }
            mapped(original)
        }
        require(root.children.count { it.name == "point" } == 1 && root.children.count { it.name == "detail" } <= 1)
        require(root.children.all { it.name in setOf("point", "detail") })
        val point = root.children.single { it.name == "point" }
        for (name in listOf("lat", "lon", "hae", "ce", "le")) {
            val value = checkNotNull(point.attributes[name]).toDouble()
            require(value.isFinite())
            if (name == "lat") require(value in -90.0..90.0)
            if (name == "lon") require(value in -180.0..180.0)
            if (name in setOf("ce", "le")) require(value >= 0)
        }
        root.children.singleOrNull { it.name == "detail" }?.children?.let { details ->
            details.removeAll { it.name == "__serverdestination" }
            details.forEach { detail ->
                require(detail.name !in setOf("event", "point", "detail") && detail.children.isEmpty())
                if (detail.name == "contact") {
                    for (name in listOf("endpoint", "phone", "altphone", "sipAddress", "emailAddress", "xmppUsername")) detail.attributes.remove(name)
                }
                if (detail.name in setOf("link", "creator")) {
                    // Native parent callsign can reveal a host identity. Attribution
                    // uses the scoped parent UID; a display-name claim is not authority.
                    detail.attributes.remove(if (detail.name == "creator") "callsign" else "parent_callsign")
                    detail.attributes["uid"]?.let { detail.attributes["uid"] = mapped(it) }
                }
                if (detail.name == "remarks") detail.attributes["source"]?.let { source ->
                    require(source.startsWith("BAO.F.ATAK.")) { "Unsupported remarks source" }
                    detail.attributes["source"] = "BAO.F.ATAK." + mapped(source.removePrefix("BAO.F.ATAK."))
                }
            }
        }
        if (topic == "atak/pli" && selfUid != null && memberName != null) {
            require(memberName.isNotBlank() && memberName.length <= 80)
            val detail = root.children.singleOrNull { it.name == "detail" }
                ?: Element("detail", linkedMapOf()).also { root.children.add(it) }
            val contacts = detail.children.filter { it.name == "contact" }
            require(contacts.size <= 1)
            val contact = contacts.singleOrNull() ?: Element("contact", linkedMapOf()).also { detail.children.add(it) }
            contact.attributes["callsign"] = memberName
            detail.children.filter { it.name == "uid" }.forEach { it.attributes["Droid"] = memberName }
        }
        val out = StringWriter()
        val serializer = Xml.newSerializer().apply { setOutput(out) }
        fun write(element: Element) {
            val allowed = fields[element.name] ?: error("Unsupported CoT detail: ${element.name}")
            require(element.attributes.keys.all { it in allowed }) { "Unsupported CoT attributes: ${element.name} ${element.attributes.keys - allowed}" }
            require(element.name == "remarks" || element.text.isBlank())
            if (element.name == "point") require(element.children.isEmpty())
            serializer.startTag(null, element.name)
            element.attributes.forEach { (name, value) -> serializer.attribute(null, name, value) }
            if (element.text.isNotEmpty()) serializer.text(element.text)
            element.children.forEach(::write)
            serializer.endTag(null, element.name)
        }
        write(root)
        serializer.flush()
        return out.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= 12 * 1024) }
    }

    private fun parse(xml: String): Element {
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        val stack = mutableListOf<Element>()
        var root: Element? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    require(!parser.name.contains(':'))
                    val attributes = linkedMapOf<String, String>()
                    for (i in 0 until parser.attributeCount) {
                        val name = parser.getAttributeName(i)
                        require(!name.contains(':') && name != "xmlns")
                        attributes[name] = parser.getAttributeValue(i)
                    }
                    val element = Element(parser.name, attributes)
                    if (stack.isEmpty()) root = element else stack.last().children.add(element)
                    stack.add(element)
                }
                XmlPullParser.TEXT -> if (stack.isNotEmpty()) stack.last().text += parser.text
                XmlPullParser.END_TAG -> stack.removeAt(stack.lastIndex)
                XmlPullParser.END_DOCUMENT -> return checkNotNull(root)
            }
        }
    }
}
