package com.processm.processminterpreter.processm.log

import com.processm.processminterpreter.xes.XESParser
import com.processm.processminterpreter.xes.XESWriter
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * XES Writer tests adapted from ProcessM XMLXESOutputStreamTest.kt
 *
 * Tests based on: XMLXESOutputStreamTest.kt
 * Original: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/log/XMLXESOutputStreamTest.kt
 *
 * ProcessM tests serialization of XES components back to XML.
 * Our XESWriter takes List<Map<String, Any?>> (query result rows), not typed objects.
 * These tests verify that XESWriter produces valid, parseable XES XML.
 */
class XESWriterTests {
    private val writer = XESWriter()
    private val parser = XESParser()

    // =====================
    // Basic output structure (from ProcessM XMLXESOutputStreamTest)
    // =====================

    @Test
    fun `writer produces valid XES XML header`() {
        // ProcessM: verify output starts with XML declaration and log element
        val results =
            listOf(
                mapOf<String, Any?>(
                    "traceId" to "trace-1",
                    "caseId" to "Case001",
                    "event" to
                        mapOf(
                            "activity" to "Activity A",
                            "timestamp" to java.time.LocalDateTime.of(2023, 1, 15, 10, 0),
                            "lifecycle" to "complete",
                        ),
                ),
            )

        val output = ByteArrayOutputStream()
        writer.writeXES(results, output, logName = "Test Log")
        val xml = output.toString("UTF-8")

        assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>"), "Should have XML declaration")
        assertTrue(xml.contains("<log xes.version=\"1.0\""), "Should have log element with version")
        assertTrue(xml.contains("xmlns=\"http://www.xes-standard.org/\""), "Should have XES namespace")
        assertTrue(xml.contains("</log>"), "Should close log element")
    }

    @Test
    fun `writer includes standard extensions`() {
        // ProcessM: XMLXESOutputStream writes standard XES extensions
        val results =
            listOf(
                mapOf<String, Any?>(
                    "traceId" to "trace-1",
                    "event" to mapOf("activity" to "A", "lifecycle" to "complete"),
                ),
            )

        val output = ByteArrayOutputStream()
        writer.writeXES(results, output)
        val xml = output.toString("UTF-8")

        assertTrue(xml.contains("extension name=\"Concept\""), "Should include Concept extension")
        assertTrue(xml.contains("extension name=\"Time\""), "Should include Time extension")
        assertTrue(xml.contains("extension name=\"Organizational\""), "Should include Organizational extension")
        assertTrue(xml.contains("extension name=\"Lifecycle\""), "Should include Lifecycle extension")
    }

    @Test
    fun `writer produces trace and event structure`() {
        // ProcessM: verify trace/event hierarchy in output
        val results =
            listOf(
                mapOf<String, Any?>(
                    "traceId" to "trace-001",
                    "caseId" to "Case001",
                    "event" to
                        mapOf(
                            "activity" to "Register",
                            "timestamp" to java.time.LocalDateTime.of(2023, 6, 1, 9, 0),
                            "resource" to "John",
                            "lifecycle" to "complete",
                        ),
                ),
                mapOf<String, Any?>(
                    "traceId" to "trace-001",
                    "caseId" to "Case001",
                    "event" to
                        mapOf(
                            "activity" to "Approve",
                            "timestamp" to java.time.LocalDateTime.of(2023, 6, 1, 10, 0),
                            "resource" to "Jane",
                            "lifecycle" to "complete",
                        ),
                ),
            )

        val output = ByteArrayOutputStream()
        writer.writeXES(results, output, logName = "Trace Test")
        val xml = output.toString("UTF-8")

        // Should have exactly one trace (both events share traceId)
        val traceCount = Regex("<trace>").findAll(xml).count()
        assertEquals(1, traceCount, "Should have exactly 1 trace")

        // Should have 2 events
        val eventCount = Regex("<event>").findAll(xml).count()
        assertEquals(2, eventCount, "Should have 2 events")

        // Verify event content
        assertTrue(xml.contains("value=\"Register\""), "Should contain Register activity")
        assertTrue(xml.contains("value=\"Approve\""), "Should contain Approve activity")
        assertTrue(xml.contains("value=\"John\""), "Should contain John resource")
        assertTrue(xml.contains("value=\"Jane\""), "Should contain Jane resource")
    }

    @Test
    fun `writer round-trip produces parseable XES`() {
        // ProcessM: verify that output can be parsed back
        // This is the key "round-trip" test from ProcessM
        val results =
            listOf(
                mapOf<String, Any?>(
                    "traceId" to "trace-rt-1",
                    "caseId" to "RoundTrip-001",
                    "event" to
                        mapOf(
                            "activity" to "Start Process",
                            "timestamp" to java.time.LocalDateTime.of(2023, 3, 15, 8, 0),
                            "resource" to "Alice",
                            "lifecycle" to "start",
                        ),
                ),
                mapOf<String, Any?>(
                    "traceId" to "trace-rt-1",
                    "caseId" to "RoundTrip-001",
                    "event" to
                        mapOf(
                            "activity" to "Start Process",
                            "timestamp" to java.time.LocalDateTime.of(2023, 3, 15, 8, 30),
                            "resource" to "Alice",
                            "lifecycle" to "complete",
                        ),
                ),
                mapOf<String, Any?>(
                    "traceId" to "trace-rt-2",
                    "caseId" to "RoundTrip-002",
                    "event" to
                        mapOf(
                            "activity" to "Review",
                            "timestamp" to java.time.LocalDateTime.of(2023, 3, 15, 9, 0),
                            "resource" to "Bob",
                            "lifecycle" to "complete",
                            "cost" to 25.50,
                        ),
                ),
            )

        // Write
        val output = ByteArrayOutputStream()
        writer.writeXES(results, output, logName = "Round Trip Test")
        val xml = output.toString("UTF-8")

        // Parse back - should not throw
        val parsed = parser.parseXES(xml.byteInputStream(), "round-trip-test")

        // Verify structure
        assertEquals("Round Trip Test", parsed.logNode.name)
        assertEquals(2, parsed.traces.size, "Should have 2 traces")

        // Trace 1 should have 2 events, Trace 2 should have 1
        val traceSizes = parsed.traces.map { it.events.size }.sorted()
        assertEquals(listOf(1, 2), traceSizes, "Should have traces with 1 and 2 events")
    }

    // =====================
    // XML escaping and special characters
    // =====================

    @Test
    fun `writer escapes XML special characters`() {
        // ProcessM: XML output should properly escape special characters
        val results =
            listOf(
                mapOf<String, Any?>(
                    "traceId" to "trace-esc",
                    "event" to
                        mapOf(
                            "activity" to "Check <status> & verify \"result\"",
                            "lifecycle" to "complete",
                        ),
                ),
            )

        val output = ByteArrayOutputStream()
        writer.writeXES(results, output)
        val xml = output.toString("UTF-8")

        // Should contain escaped versions
        assertTrue(xml.contains("&lt;status&gt;"), "Should escape < and >")
        assertTrue(xml.contains("&amp;"), "Should escape &")
        assertTrue(xml.contains("&quot;result&quot;"), "Should escape quotes")

        // Should still be valid XML (parseable)
        val parsed = parser.parseXES(xml.byteInputStream(), "escape-test")
        assertNotNull(parsed, "Escaped XML should be parseable")
    }

    @Test
    fun `writer handles multiple traces from flat results`() {
        // ProcessM: XMLXESOutputStream groups flat records into traces
        val results =
            listOf(
                mapOf<String, Any?>(
                    "traceId" to "t1",
                    "event" to mapOf("activity" to "A1", "lifecycle" to "complete"),
                ),
                mapOf<String, Any?>(
                    "traceId" to "t2",
                    "event" to mapOf("activity" to "B1", "lifecycle" to "complete"),
                ),
                mapOf<String, Any?>(
                    "traceId" to "t1",
                    "event" to mapOf("activity" to "A2", "lifecycle" to "complete"),
                ),
                mapOf<String, Any?>(
                    "traceId" to "t3",
                    "event" to mapOf("activity" to "C1", "lifecycle" to "complete"),
                ),
                mapOf<String, Any?>(
                    "traceId" to "t2",
                    "event" to mapOf("activity" to "B2", "lifecycle" to "complete"),
                ),
            )

        val output = ByteArrayOutputStream()
        writer.writeXES(results, output, logName = "Multi-Trace")
        val xml = output.toString("UTF-8")

        // Should group into 3 traces
        val traceCount = Regex("<trace>").findAll(xml).count()
        assertEquals(3, traceCount, "Should have 3 traces")

        // Total 5 events
        val eventCount = Regex("<event>").findAll(xml).count()
        assertEquals(5, eventCount, "Should have 5 events total")
    }

    @Test
    fun `writer handles empty results`() {
        // ProcessM: empty log should still produce valid XES structure
        val results = emptyList<Map<String, Any?>>()

        val output = ByteArrayOutputStream()
        writer.writeXES(results, output, logName = "Empty Log")
        val xml = output.toString("UTF-8")

        assertTrue(xml.contains("<log"), "Should have log element")
        assertTrue(xml.contains("</log>"), "Should close log element")

        // Should be parseable
        val parsed = parser.parseXES(xml.byteInputStream(), "empty-test")
        assertEquals("Empty Log", parsed.logNode.name)
        assertEquals(0, parsed.traces.size, "Should have no traces")
    }
}
