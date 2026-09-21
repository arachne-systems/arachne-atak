package dev.arachne.atak

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bound and validate the XML before invoking ATAK's native CoT parser. */
internal object CotXml {
    fun decode(payload: ByteArray): String {
        require(payload.size <= 16384) { "CoT exceeds 16 KiB" }
        val xml = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload)).toString()
        require(!xml.contains("<!DOCTYPE") && !xml.contains("<!ENTITY")) { "DTD/entities forbidden" }
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        var depth = 0
        var roots = 0
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.START_TAG -> {
                    if (depth == 0) require(parser.name == "event" && roots++ == 0)
                    require(++depth <= 32) { "XML depth limit" }
                }
                XmlPullParser.END_TAG -> depth--
                // nextToken exposes unresolved entities instead of rejecting
                // them. Only references resolved without a DTD are acceptable.
                XmlPullParser.ENTITY_REF -> require(depth > 0 && parser.text != null) { "Unresolved entity" }
                XmlPullParser.TEXT -> require(depth > 0 || parser.text.isBlank()) { "Text outside event" }
                XmlPullParser.CDSECT -> require(depth > 0) { "CDATA outside event" }
                XmlPullParser.DOCDECL -> error("DTD forbidden")
                XmlPullParser.END_DOCUMENT -> { require(roots == 1 && depth == 0); return xml }
            }
        }
    }
}
