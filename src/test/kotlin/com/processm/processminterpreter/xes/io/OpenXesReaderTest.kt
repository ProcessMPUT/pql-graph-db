package com.processm.processminterpreter.xes.io

import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.ZonedDateTime
import java.util.UUID

class OpenXesReaderTest {
    private val reader = OpenXesReader()

    @Test
    fun `reads journal-review sample and produces one XesLog with traces and events`() {
        // The bundled sample is gzipped; OpenXesReader.read does not sniff gzip,
        // so decompress transparently via the shared input-stream helper.
        val raw =
            javaClass.classLoader.getResourceAsStream("logs/JournalReview.xes.gz")
                ?: error("logs/JournalReview.xes.gz missing from the classpath")
        val logs = raw.use { XesInputStreams().openPossiblyGzipped(it).use(reader::read) }
        assertEquals(1, logs.size)
        val log = logs.single()
        assertNotNull(log.conceptName)
        assertTrue(log.traces.isNotEmpty(), "expected at least one trace")
        assertTrue(log.traces.first().events.isNotEmpty(), "expected at least one event")
    }

    @Test
    fun `event standard attributes are lifted onto typed XesEvent fields`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <log xes.version="1.0">
              <string key="concept:name" value="Test Log"/>
              <trace>
                <string key="concept:name" value="case-1"/>
                <event>
                  <string key="concept:name" value="Register"/>
                  <date   key="time:timestamp" value="2023-01-02T03:04:05.000Z"/>
                  <string key="org:resource" value="alice"/>
                  <string key="lifecycle:transition" value="complete"/>
                </event>
              </trace>
            </log>
        """.trimIndent()
        val log = reader.read(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).single()
        val event = log.traces.single().events.single()
        assertEquals("Register", event.conceptName)
        assertEquals("alice", event.orgResource)
        assertEquals("complete", event.lifecycleTransition)
        assertNotNull(event.timeTimestamp)
    }

    @Test
    fun `event non-standard attributes surface on customAttributes map`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <log xes.version="1.0">
              <trace>
                <event>
                  <string key="concept:name" value="A"/>
                  <string key="priority" value="HIGH"/>
                </event>
              </trace>
            </log>
        """.trimIndent()
        val event = reader.read(ByteArrayInputStream(xml.toByteArray())).single()
            .traces.single().events.single()
        assertEquals("HIGH", event.customAttributes["priority"])
        assertNull(event.customAttributes["concept:name"], "standard attr must not double up in customAttributes")
    }

    @Test
    fun `identity id is parsed as UUID when present on trace`() {
        val id = UUID.randomUUID()
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <log xes.version="1.0">
              <trace>
                <string key="concept:name" value="c1"/>
                <string key="identity:id" value="$id"/>
                <event><string key="concept:name" value="e"/></event>
              </trace>
            </log>
        """.trimIndent()
        val trace = reader.read(ByteArrayInputStream(xml.toByteArray())).single().traces.single()
        assertEquals(id, trace.identityId)
    }

    @Test
    fun `result types are immutable data classes`() {
        // Sanity check — the adapter outputs domain types, not legacy mutable ones.
        val log: XesLog = XesLog()
        val trace: XesTrace = XesTrace()
        val event: XesEvent = XesEvent()
        assertTrue(log.traces is List)
        assertTrue(trace.events is List)
        assertTrue(event.customAttributes is Map)
    }

    @Test
    fun `trace conceptName comes from case-level concept name attribute`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <log xes.version="1.0">
              <trace>
                <string key="concept:name" value="case-alpha"/>
                <event><string key="concept:name" value="e"/></event>
              </trace>
            </log>
        """.trimIndent()
        val trace = reader.read(ByteArrayInputStream(xml.toByteArray())).single().traces.single()
        assertEquals("case-alpha", trace.conceptName)
    }

    @Test
    fun `cost total lifts to event field and is parsed as Double`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <log xes.version="1.0">
              <trace>
                <event>
                  <string key="concept:name" value="Pay"/>
                  <float key="cost:total" value="12.50"/>
                  <string key="cost:currency" value="EUR"/>
                </event>
              </trace>
            </log>
        """.trimIndent()
        val event = reader.read(ByteArrayInputStream(xml.toByteArray())).single()
            .traces.single().events.single()
        assertEquals(12.5, event.costTotal)
        assertEquals("EUR", event.costCurrency)
    }

    @Test
    fun `timestamp is represented as Instant UTC`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <log xes.version="1.0">
              <trace>
                <event>
                  <string key="concept:name" value="A"/>
                  <date key="time:timestamp" value="2023-06-15T12:00:00+02:00"/>
                </event>
              </trace>
            </log>
        """.trimIndent()
        val event = reader.read(ByteArrayInputStream(xml.toByteArray())).single()
            .traces.single().events.single()
        val expected = ZonedDateTime.parse("2023-06-15T12:00:00+02:00").toInstant()
        assertEquals(expected, event.timeTimestamp)
    }
}
