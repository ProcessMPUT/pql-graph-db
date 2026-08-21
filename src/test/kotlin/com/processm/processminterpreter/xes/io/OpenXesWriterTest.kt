package com.processm.processminterpreter.xes.io

import com.processm.processminterpreter.xes.model.AttributeScope
import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.xes.io.XesWriteOptions
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream

class OpenXesWriterTest {
    private val writer = OpenXesWriter()

    @Test
    fun `round trip produces readable xml containing log trace and event names`() {
        val log = XesLog(
            conceptName = "roundtrip",
            traces = listOf(
                XesTrace(
                    conceptName = "case-1",
                    events = listOf(
                        XesEvent(
                            conceptName = "Register",
                            timeTimestamp = Instant.parse("2023-01-01T00:00:00Z"),
                        ),
                    ),
                ),
            ),
        )
        val out = ByteArrayOutputStream()
        writer.write(listOf(log), out)
        val xml = out.toString(Charsets.UTF_8)

        assertTrue(xml.startsWith("<?xml"), "missing xml prolog: $xml")
        assertTrue(xml.contains("<log"), "missing log element: $xml")
        assertTrue(xml.contains("Register"), "missing event name: $xml")
        assertTrue(xml.contains("case-1"), "missing case id: $xml")
        assertTrue(xml.contains("concept:name"), "missing concept:name attr: $xml")
    }

    @Test
    fun `writer emits standard extensions header by default`() {
        val out = ByteArrayOutputStream()
        writer.write(listOf(XesLog(conceptName = "x")), out)
        val xml = out.toString(Charsets.UTF_8)
        assertTrue(xml.contains("extension"), "expected at least one extension declaration: $xml")
    }

    @Test
    fun `empty log list still emits a well-formed XML document`() {
        val out = ByteArrayOutputStream()
        writer.write(emptyList(), out)
        val xml = out.toString(Charsets.UTF_8)
        assertTrue(xml.startsWith("<?xml"))
    }

    @Test
    fun `gzip compression produces a gzipped stream readable via GZIPInputStream`() {
        val log = XesLog(conceptName = "gz", traces = listOf(XesTrace(conceptName = "c")))
        val out = ByteArrayOutputStream()
        writer.write(listOf(log), out, XesWriteOptions(compress = true))
        // Inflating the stream should produce valid XML that mentions the log.
        val inflated = GZIPInputStream(ByteArrayInputStream(out.toByteArray()))
            .readBytes().toString(Charsets.UTF_8)
        assertTrue(inflated.contains("<log"))
        assertTrue(inflated.contains("gz"))
    }

    @Test
    fun `custom attribute values with XML special characters are escaped`() {
        val log = XesLog(
            traces = listOf(
                XesTrace(
                    events = listOf(
                        XesEvent(
                            conceptName = "A",
                            customAttributes = mapOf("note" to "a<b & c>\"d"),
                        ),
                    ),
                ),
            ),
        )
        val out = ByteArrayOutputStream()
        writer.write(listOf(log), out)
        val xml = out.toString(Charsets.UTF_8)
        assertTrue(xml.contains("&lt;"), "< not escaped: $xml")
        assertTrue(xml.contains("&amp;"), "& not escaped: $xml")
        assertTrue(xml.contains("&gt;"), "> not escaped: $xml")
        assertTrue(xml.contains("&quot;"), "quote not escaped: $xml")
        // Raw unescaped forms should not appear inside value=""
        assertFalse(
            xml.contains("value=\"a<b"),
            "expected escaped value but found raw <: $xml",
        )
    }

    @Test
    fun `custom attribute keys with XML special characters are escaped`() {
        // Hospital_log has real keys like "Obstetrics & Gynaecology clinic";
        // an unescaped key makes the exported document unparseable.
        val log = XesLog(
            traces = listOf(
                XesTrace(
                    events = listOf(
                        XesEvent(
                            conceptName = "A",
                            customAttributes = mapOf(
                                "Obstetrics & Gynaecology clinic" to 6.181,
                                "key<with>\"specials\"" to "v",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val out = ByteArrayOutputStream()
        writer.write(listOf(log), out)
        val xml = out.toString(Charsets.UTF_8)
        assertTrue(xml.contains("Obstetrics &amp; Gynaecology clinic"), "& in key not escaped: $xml")
        assertFalse(xml.contains("key=\"Obstetrics & "), "raw & left in key attribute: $xml")
        assertFalse(xml.contains("key=\"key<with>"), "raw <> left in key attribute: $xml")
        // The document must parse and round-trip the key intact.
        val reread = OpenXesReader().read(ByteArrayInputStream(out.toByteArray())).single()
        val eventAttrs = reread.traces.single().events.single().customAttributes
        assertTrue("Obstetrics & Gynaecology clinic" in eventAttrs, "key lost in roundtrip: $eventAttrs")
    }

    @Test
    fun `identity id is emitted as an id element`() {
        val id = UUID.randomUUID()
        val log = XesLog(
            traces = listOf(XesTrace(conceptName = "c", identityId = id, events = emptyList())),
        )
        val out = ByteArrayOutputStream()
        writer.write(listOf(log), out)
        val xml = out.toString(Charsets.UTF_8)
        assertTrue(xml.contains(id.toString()), "expected uuid in output: $xml")
        assertTrue(xml.contains("identity:id"))
    }

    @Test
    fun `classifiers declared on the log are emitted`() {
        val log = XesLog(
            conceptName = "x",
            classifiers = listOf(
                Classifier("Event Name", listOf("concept:name")),
                Classifier("Department", listOf("org:group"), AttributeScope.TRACE),
            ),
        )
        val out = ByteArrayOutputStream()
        writer.write(listOf(log), out)
        val xml = out.toString(Charsets.UTF_8)
        assertTrue(xml.contains("classifier"))
        assertTrue(xml.contains("Event Name"))
        assertTrue(xml.contains("concept:name"))
        assertTrue(xml.contains("scope=\"trace\" name=\"Department\""))

        val reread = OpenXesReader().read(ByteArrayInputStream(out.toByteArray())).single()
        assertTrue(reread.classifiers.contains(Classifier("Event Name", listOf("concept:name"))))
        assertTrue(
            reread.classifiers.contains(
                Classifier("Department", listOf("org:group"), AttributeScope.TRACE),
            ),
        )
    }

    @Test
    fun `writer output round-trips through the reader preserving standard fields`() {
        val original = XesLog(
            conceptName = "rt-log",
            traces = listOf(
                XesTrace(
                    conceptName = "rt-case",
                    events = listOf(
                        XesEvent(
                            conceptName = "rt-event",
                            orgResource = "bob",
                            timeTimestamp = Instant.parse("2024-03-10T12:00:00Z"),
                        ),
                    ),
                ),
            ),
        )
        val out = ByteArrayOutputStream()
        writer.write(listOf(original), out)
        val reread = OpenXesReader().read(ByteArrayInputStream(out.toByteArray())).single()

        assertTrue(reread.conceptName == "rt-log")
        val trace = reread.traces.single()
        assertTrue(trace.conceptName == "rt-case")
        val event = trace.events.single()
        assertTrue(event.conceptName == "rt-event")
        assertTrue(event.orgResource == "bob")
        assertTrue(event.timeTimestamp == Instant.parse("2024-03-10T12:00:00Z"))
    }

    @Test
    fun `timestamps are emitted in ISO-8601 UTC`() {
        val ts = Instant.parse("2023-06-15T10:00:00Z")
        val log = XesLog(
            traces = listOf(
                XesTrace(events = listOf(XesEvent(conceptName = "A", timeTimestamp = ts))),
            ),
        )
        val out = ByteArrayOutputStream()
        writer.write(listOf(log), out)
        val xml = out.toString(Charsets.UTF_8)
        // Must appear as a date element with ISO-8601 timestamp
        assertTrue(xml.contains("time:timestamp"), "missing time:timestamp: $xml")
        assertTrue(xml.contains("2023-06-15T10:00:00"), "wrong ISO timestamp: $xml")
    }
}
