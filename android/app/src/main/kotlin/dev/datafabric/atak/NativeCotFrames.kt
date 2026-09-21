package dev.arachne.atak

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/** Reads concatenated native XML events without interpreting their CoT types.
 * The caller owns the stream. Each synchronous callback receives exactly one
 * complete original UTF-8 document; failure terminates this stream, never skips
 * ahead into potentially misframed data. No callback queue or fabric dependency. */
internal object NativeCotFrames {
    const val MAX_BYTES = 12 * 1024 // Current workspace publication payload budget.

    fun read(input: InputStream, received: (ByteArray) -> Unit) {
        val source = input.buffered(8192)
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        val handler = object : DefaultHandler2() {
            private var depth = 0
            override fun startElement(uri: String, local: String, qualified: String, attributes: Attributes) {
                if (depth == 0 && (local != "event" || uri.isNotEmpty())) throw SAXException("Expected native CoT event")
                if (++depth > 32) throw SAXException("Native CoT nesting exceeds 32")
            }
            override fun endElement(uri: String, local: String, qualified: String) {
                depth--
            }
            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                throw SAXException("Native CoT DTD is forbidden")
            }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
                throw SAXException("Native CoT external entity is forbidden")
            }
            override fun error(error: SAXParseException) { throw error }
            override fun fatalError(error: SAXParseException) { throw error }
        }
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = handler
        // Required, not best-effort: an unsupported parser fails before reading.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        while (true) {
            source.mark(1)
            val first = source.read()
            if (first < 0) return
            if (first == 9 || first == 10 || first == 13 || first == 32) continue
            source.reset()
            val raw = ByteArrayOutputStream()
            val frame = object : InputStream() {
                override fun read(): Int {
                    val next = source.read()
                    if (next >= 0) {
                        if (raw.size() == MAX_BYTES) throw SAXException("Native CoT exceeds payload budget")
                        raw.write(next)
                    }
                    return next
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    val next = read()
                    if (next < 0) return -1
                    bytes[offset] = next.toByte()
                    return 1
                }
                // The connection owner retains the concatenated source stream.
                override fun close() {}
            }
            // ponytail: one byte per parser read prevents read-ahead past the
            // closing root. Measure this seam before replacing it with a faster
            // parser exposing consumed-byte offsets; never scan for </event>.
            val tokens = android.util.Xml.newPullParser()
            tokens.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            tokens.setInput(frame, "UTF-8")
            while (true) {
                when (tokens.nextToken()) {
                    XmlPullParser.DOCDECL -> throw SAXException("Native CoT DTD is forbidden")
                    XmlPullParser.START_TAG -> if (tokens.depth > 32) throw SAXException("Native CoT nesting exceeds 32")
                    XmlPullParser.END_TAG -> if (tokens.depth == 1) break
                    XmlPullParser.END_DOCUMENT -> throw SAXException("Native CoT ended without a complete event")
                }
            }
            val bytes = raw.toByteArray()
            // Validate the bounded original bytes before handing them off. SAX
            // callbacks can be deferred into the next native message; a finite
            // document is safe to validate, but those callbacks cannot frame IO.
            // This also rejects malformed UTF-8 that a pull reader may replace.
            reader.parse(InputSource(bytes.inputStream()).apply { encoding = "UTF-8" })
            received(bytes)
        }
    }
}
