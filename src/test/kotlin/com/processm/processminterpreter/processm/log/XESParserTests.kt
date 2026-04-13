package com.processm.processminterpreter.processm.log

import com.processm.processminterpreter.xes.XESParseException
import com.processm.processminterpreter.xes.XESParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * XES Parser tests adapted from ProcessM XMLXESInputStreamTest.kt
 *
 * Tests based on: XMLXESInputStreamTest.kt
 * Original: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/log/XMLXESInputStreamTest.kt
 *
 * Adapted from ProcessM's streaming parser (XMLXESInputStream) to our DOM-based XESParser.
 * Our parser returns XESLog with LogNode/TraceNode/EventNode instead of a flat sequence.
 */
class XESParserTests {
    private val parser = XESParser()

    // Rich XES content based on ProcessM's test fixture
    private val content =
        """
        <?xml version="1.0" encoding="UTF-8" ?>
        <log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7" xmlns="http://www.xes-standard.org/">
            <extension name="Lifecycle" prefix="lifecycle" uri="http://www.xes-standard.org/lifecycle.xesext"/>
            <extension name="Concept" prefix="conceptowy" uri="http://www.xes-standard.org/concept.xesext"/>
            <extension name="Organizational" prefix="org" uri="http://www.xes-standard.org/org.xesext"/>
            <extension name="Metadata_Organizational" prefix="meta_org" uri="http://www.xes-standard.org/meta_org.xesext"/>
            <global scope="trace">
                <string key="conceptowy:name" value="__INVALID__"/>
            </global>
            <global>
                <string key="lifecycle:transition" value="complete"/>
                <string key="conceptowy:name" value="__INVALID__"/>
                <string key="org:group" value="__INVALID__"/>
                <date key="time:timestamp" value="1970-01-01T01:00:00.000+01:00"/>
            </global>
            <classifier name="Event Name" keys="conceptowy:name"/>
            <classifier scope="trace" name="Department Classifier" keys="org:group"/>
            <float key="meta_org:resource_events_standard_deviation" value="202.617"/>
            <id key="identity:id" value="22a66e06-9371-4dbf-aee3-b58b44564a0c"/>
            <string key="meta_3TU:log_type" value="Real-life"/>
            <string key="concept:name" value="Some amazing log file"/>
            <int key="meta_org:role_events_total" value="150291"/>
            <trace>
                <date key="End date" value="2006-01-04T23:45:36.000+01:00"/>
                <int key="Age" value="33"/>
                <string key="concept:name" value="00000001"/>
                <event>
                    <string key="org:group" value="Radiotherapy"/>
                    <int key="Number of executions" value="1"/>
                    <int key="Specialism code" value="61"/>
                    <string key="concept:name" value="1e consult poliklinisch"/>
                    <string key="Producer code" value="SRTH"/>
                    <string key="Section" value="Section 5"/>
                    <int key="Activity code" value="410100"/>
                    <date key="time:timestamp" value="2005-01-03T00:00:00+01:00"/>
                    <string key="lifecycle:transition" value="complete"/>
                </event>
                <event>
                    <string key="org:group" value="Radiotherapy"/>
                    <int key="Number of executions" value="1"/>
                    <int key="Specialism code" value="61"/>
                    <string key="concept:name" value="administratief tarief - eerste pol"/>
                    <string key="Producer code" value="SRTH"/>
                    <string key="Section" value="Section 5"/>
                    <int key="Activity code" value="419100"/>
                    <date key="time:timestamp" value="2005-01-03T00:00:00+01:00"/>
                    <string key="lifecycle:transition" value="complete"/>
                </event>
            </trace>
        </log>
        """.trimIndent()

    // =====================
    // Log-level parsing (from ProcessM XMLXESInputStreamTest)
    // =====================

    @Test
    fun `parser extracts log concept name`() {
        // ProcessM: assertEquals(receivedLog.conceptName, "Some amazing log file")
        val result = parser.parseXES(content.byteInputStream(), "test-log")
        assertEquals("Some amazing log file", result.logNode.name)
    }

    @Test
    fun `parser stores log-level float attributes`() {
        // ProcessM: meta_org:resource_events_standard_deviation = 202.617
        val result = parser.parseXES(content.byteInputStream(), "test-log")
        assertEquals(202.617, result.logNode.attributes["meta_org:resource_events_standard_deviation"])
    }

    @Test
    fun `parser stores log-level int attributes`() {
        // ProcessM: meta_org:role_events_total = 150291
        val result = parser.parseXES(content.byteInputStream(), "test-log")
        assertEquals(150291, result.logNode.attributes["meta_org:role_events_total"])
    }

    @Test
    fun `parser stores log-level string attributes`() {
        // ProcessM: meta_3TU:log_type = "Real-life"
        val result = parser.parseXES(content.byteInputStream(), "test-log")
        assertEquals("Real-life", result.logNode.attributes["meta_3TU:log_type"])
    }

    @Test
    fun `parser stores log-level id attributes`() {
        // ProcessM: identity:id = "22a66e06-9371-4dbf-aee3-b58b44564a0c"
        val result = parser.parseXES(content.byteInputStream(), "test-log")
        assertEquals("22a66e06-9371-4dbf-aee3-b58b44564a0c", result.logNode.attributes["identity:id"])
    }

    // =====================
    // Trace-level parsing (from ProcessM XMLXESInputStreamTest)
    // =====================

    @Test
    fun `parser builds trace structure with attributes`() {
        // ProcessM: receivedTrace.attributes.size == 3, conceptowy:name == "00000001"
        val result = parser.parseXES(content.byteInputStream(), "test-log")
        assertEquals(1, result.traces.size)

        val trace = result.traces[0]
        assertEquals("00000001", trace.traceNode.caseId)

        // Trace should have attributes: End date, Age, concept:name
        val attrs = trace.traceNode.attributes
        assertTrue(attrs.containsKey("End date"), "Trace should have 'End date' attribute")
        assertEquals(33, attrs["Age"], "Age should be parsed as int")
        assertEquals("00000001", attrs["concept:name"])
    }

    @Test
    fun `parser stores trace date attributes as strings`() {
        // ProcessM: receivedTrace.attributes.getValue("End date") == parseISO8601("2006-01-04T23:45:36.000+01:00")
        // Our parser stores dates as strings (not Instant)
        val result = parser.parseXES(content.byteInputStream(), "test-log")
        val endDate = result.traces[0].traceNode.attributes["End date"]
        assertNotNull(endDate, "End date should be present")
        assertTrue(endDate.toString().contains("2006"), "End date should contain year 2006")
    }

    // =====================
    // Event-level parsing (from ProcessM XMLXESInputStreamTest)
    // =====================

    @Test
    fun `parser builds event structure with all attributes`() {
        // ProcessM: receivedEvent.attributes.size == 9
        val result = parser.parseXES(content.byteInputStream(), "test-log")
        val event = result.traces[0].events[0].eventNode

        // Standard attributes mapped to EventNode fields
        assertEquals("1e consult poliklinisch", event.activity)
        assertEquals("complete", event.lifecycle)

        // All attributes should also be in the attributes map
        val attrs = event.attributes
        assertEquals("Radiotherapy", attrs["org:group"])
        assertEquals(1, attrs["Number of executions"])
        assertEquals(61, attrs["Specialism code"])
        assertEquals("SRTH", attrs["Producer code"])
        assertEquals("Section 5", attrs["Section"])
        assertEquals(410100, attrs["Activity code"])
    }

    @Test
    fun `parser extracts standard event accessors`() {
        // ProcessM: assertEquals(receivedEvent.conceptName, "1e consult poliklinisch")
        // ProcessM: assertEquals(receivedEvent.lifecycleTransition, "complete")
        // ProcessM: assertEquals(receivedEvent.orgGroup, "Radiotherapy")
        val result = parser.parseXES(content.byteInputStream(), "test-log")
        val event = result.traces[0].events[0].eventNode

        assertEquals("1e consult poliklinisch", event.activity)
        assertEquals("complete", event.lifecycle)
        assertEquals("Radiotherapy", event.attributes["org:group"])
    }

    @Test
    fun `parser parses multiple events in a trace`() {
        // ProcessM test had 2 events in the trace
        val result = parser.parseXES(content.byteInputStream(), "test-log")
        assertEquals(2, result.traces[0].events.size)

        val event1 = result.traces[0].events[0].eventNode
        val event2 = result.traces[0].events[1].eventNode

        assertEquals("1e consult poliklinisch", event1.activity)
        assertEquals("administratief tarief - eerste pol", event2.activity)
    }

    // =====================
    // Error handling (from ProcessM XMLXESInputStreamTest)
    // =====================

    @Test
    fun `parser throws exception for invalid root element`() {
        // ProcessM: XES parser will throw exception when found invalid XML tag inside log structure
        val invalidXml =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <notaolog>
                <trace><event/></trace>
            </notaolog>
            """.trimIndent()

        assertFailsWith<XESParseException> {
            parser.parseXES(invalidXml.byteInputStream(), "test-log")
        }
    }

    @Test
    fun `parser handles empty log with no traces`() {
        val emptyLog =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <string key="concept:name" value="Empty Log"/>
            </log>
            """.trimIndent()

        val result = parser.parseXES(emptyLog.byteInputStream(), "test-log")
        assertEquals("Empty Log", result.logNode.name)
        assertEquals(0, result.traces.size)
    }

    @Test
    fun `parser handles event with missing concept name`() {
        // ProcessM defaults to handling missing attributes gracefully
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <string key="concept:name" value="Test Log"/>
                <trace>
                    <string key="concept:name" value="Case1"/>
                    <event>
                        <date key="time:timestamp" value="2005-01-03T00:00:00+01:00"/>
                        <string key="lifecycle:transition" value="complete"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "test-log")
        // Event without concept:name should get default activity
        assertNotNull(
            result.traces[0]
                .events[0]
                .eventNode.activity,
        )
    }

    // =====================
    // Boolean and mixed attribute types
    // =====================

    @Test
    fun `parser handles boolean attributes`() {
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <string key="concept:name" value="Test Log"/>
                <boolean key="is_test" value="true"/>
                <trace>
                    <string key="concept:name" value="Case1"/>
                    <boolean key="is_complete" value="false"/>
                    <event>
                        <string key="concept:name" value="Activity A"/>
                        <date key="time:timestamp" value="2005-01-03T00:00:00+01:00"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "test-log")
        assertEquals(true, result.logNode.attributes["is_test"])
        assertEquals(false, result.traces[0].traceNode.attributes["is_complete"])
    }

    @Test
    fun `parser handles multiple traces`() {
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <string key="concept:name" value="Multi-trace Log"/>
                <trace>
                    <string key="concept:name" value="Case_001"/>
                    <event>
                        <string key="concept:name" value="A"/>
                        <date key="time:timestamp" value="2005-01-01T00:00:00+01:00"/>
                    </event>
                </trace>
                <trace>
                    <string key="concept:name" value="Case_002"/>
                    <event>
                        <string key="concept:name" value="B"/>
                        <date key="time:timestamp" value="2005-01-02T00:00:00+01:00"/>
                    </event>
                </trace>
                <trace>
                    <string key="concept:name" value="Case_003"/>
                    <event>
                        <string key="concept:name" value="C"/>
                        <date key="time:timestamp" value="2005-01-03T00:00:00+01:00"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "test-log")
        assertEquals(3, result.traces.size)
        assertEquals("Case_001", result.traces[0].traceNode.caseId)
        assertEquals("Case_002", result.traces[1].traceNode.caseId)
        assertEquals("Case_003", result.traces[2].traceNode.caseId)
    }

    @Test
    fun `parser generates unique trace and event IDs`() {
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <string key="concept:name" value="ID Test"/>
                <trace>
                    <string key="concept:name" value="Case1"/>
                    <event>
                        <string key="concept:name" value="A"/>
                        <date key="time:timestamp" value="2005-01-01T00:00:00+01:00"/>
                    </event>
                    <event>
                        <string key="concept:name" value="B"/>
                        <date key="time:timestamp" value="2005-01-02T00:00:00+01:00"/>
                    </event>
                </trace>
                <trace>
                    <string key="concept:name" value="Case2"/>
                    <event>
                        <string key="concept:name" value="C"/>
                        <date key="time:timestamp" value="2005-01-03T00:00:00+01:00"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "test-log")

        val traceIds = result.traces.map { it.traceNode.traceId }
        assertEquals(traceIds.size, traceIds.toSet().size, "Trace IDs should be unique")

        val eventIds = result.traces.flatMap { t -> t.events.map { it.eventNode.eventId } }
        assertEquals(eventIds.size, eventIds.toSet().size, "Event IDs should be unique")
    }

    @Test
    fun `parser extracts cost attributes`() {
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <string key="concept:name" value="Cost Test"/>
                <trace>
                    <string key="concept:name" value="Case1"/>
                    <float key="cost:total" value="99.99"/>
                    <string key="cost:currency" value="EUR"/>
                    <event>
                        <string key="concept:name" value="Activity"/>
                        <date key="time:timestamp" value="2005-01-01T00:00:00+01:00"/>
                        <float key="cost:total" value="50.25"/>
                        <string key="cost:currency" value="EUR"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "test-log")

        // Trace cost attributes
        assertEquals(99.99, result.traces[0].traceNode.attributes["cost:total"])
        assertEquals("EUR", result.traces[0].traceNode.attributes["cost:currency"])

        // Event cost
        assertEquals(
            50.25,
            result.traces[0]
                .events[0]
                .eventNode.cost,
        )
    }

    @Test
    fun `parser extracts org resource attribute`() {
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <string key="concept:name" value="Org Test"/>
                <trace>
                    <string key="concept:name" value="Case1"/>
                    <event>
                        <string key="concept:name" value="Activity"/>
                        <date key="time:timestamp" value="2005-01-01T00:00:00+01:00"/>
                        <string key="org:resource" value="John"/>
                        <string key="org:group" value="Engineering"/>
                        <string key="org:role" value="Developer"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "test-log")
        val event = result.traces[0].events[0].eventNode

        assertEquals("John", event.resource)
        assertEquals("Engineering", event.attributes["org:group"])
        assertEquals("Developer", event.attributes["org:role"])
    }
}
