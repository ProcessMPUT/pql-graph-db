package com.processm.processminterpreter.processm.log

import com.processm.processminterpreter.xes.XESParser
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * XES Import Certification tests adapted from ProcessM XESImportCertificationFirstLevelTest.kt
 *
 * Tests based on: XESImportCertificationFirstLevelTest.kt
 * Original: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/log/XESImportCertificationFirstLevelTest.kt
 *
 * Tests XES import conformance at various certification levels:
 * - A1: concept:name + identity:id
 * - B1: + lifecycle:transition + time:timestamp
 * - C1: + org:resource, org:role, org:group
 * - D1: + cost:total, cost:currency
 * - X1: non-standard extensions
 *
 * Adapted from ProcessM's streaming XMLXESInputStream to our DOM-based XESParser.
 */
class XESImportCertificationTest {

    private val parser = XESParser()

    // =====================
    // Level A1: concept:name + identity:id
    // =====================

    @Test
    fun `A1 - parser handles concept name and identity id`() {
        // ProcessM: Level A1 requires concept:name and identity:id at all levels
        val xes = """<?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Identity" prefix="identity" uri="http://www.xes-standard.org/identity.xesext"/>
                <string key="concept:name" value="A1 Test Log"/>
                <id key="identity:id" value="a1b2c3d4-e5f6-7890-abcd-ef1234567890"/>
                <trace>
                    <string key="concept:name" value="Case001"/>
                    <id key="identity:id" value="11111111-1111-1111-1111-111111111111"/>
                    <event>
                        <string key="concept:name" value="Activity A"/>
                        <id key="identity:id" value="22222222-2222-2222-2222-222222222222"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Activity B"/>
                        <id key="identity:id" value="33333333-3333-3333-3333-333333333333"/>
                    </event>
                </trace>
            </log>
        """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "cert-a1")

        // Log level
        assertEquals("A1 Test Log", result.logNode.name)
        assertEquals("a1b2c3d4-e5f6-7890-abcd-ef1234567890", result.logNode.attributes["identity:id"])

        // Trace level
        assertEquals(1, result.traces.size)
        assertEquals("Case001", result.traces[0].traceNode.caseId)
        assertEquals("11111111-1111-1111-1111-111111111111", result.traces[0].traceNode.attributes["identity:id"])

        // Event level
        assertEquals(2, result.traces[0].events.size)
        assertEquals("Activity A", result.traces[0].events[0].eventNode.activity)
        assertEquals("22222222-2222-2222-2222-222222222222", result.traces[0].events[0].eventNode.attributes["identity:id"])
        assertEquals("Activity B", result.traces[0].events[1].eventNode.activity)
        assertEquals("33333333-3333-3333-3333-333333333333", result.traces[0].events[1].eventNode.attributes["identity:id"])
    }

    // =====================
    // Level B1: + lifecycle:transition + time:timestamp
    // =====================

    @Test
    fun `B1 - parser handles lifecycle and timestamp`() {
        // ProcessM: Level B1 adds lifecycle:transition and time:timestamp
        val xes = """<?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Lifecycle" prefix="lifecycle" uri="http://www.xes-standard.org/lifecycle.xesext"/>
                <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>
                <string key="concept:name" value="B1 Test Log"/>
                <string key="lifecycle:model" value="standard"/>
                <trace>
                    <string key="concept:name" value="Case001"/>
                    <event>
                        <string key="concept:name" value="Register"/>
                        <string key="lifecycle:transition" value="start"/>
                        <date key="time:timestamp" value="2023-01-15T10:00:00.000+01:00"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Register"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2023-01-15T10:30:00.000+01:00"/>
                    </event>
                </trace>
            </log>
        """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "cert-b1")

        assertEquals("B1 Test Log", result.logNode.name)
        assertEquals("standard", result.logNode.attributes["lifecycle:model"])

        val event1 = result.traces[0].events[0].eventNode
        val event2 = result.traces[0].events[1].eventNode

        assertEquals("Register", event1.activity)
        assertEquals("start", event1.lifecycle)
        assertNotNull(event1.timestamp, "Event should have parsed timestamp")

        assertEquals("Register", event2.activity)
        assertEquals("complete", event2.lifecycle)
        assertNotNull(event2.timestamp, "Event should have parsed timestamp")

        // Verify timestamps are different (second is later)
        assertTrue(event2.timestamp!!.isAfter(event1.timestamp), "Complete should be after start")
    }

    // =====================
    // Level C1: + org:resource, org:role, org:group
    // =====================

    @Test
    fun `C1 - parser handles organizational attributes`() {
        // ProcessM: Level C1 adds organizational extension
        val xes = """<?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Organizational" prefix="org" uri="http://www.xes-standard.org/org.xesext"/>
                <extension name="Lifecycle" prefix="lifecycle" uri="http://www.xes-standard.org/lifecycle.xesext"/>
                <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>
                <string key="concept:name" value="C1 Test Log"/>
                <trace>
                    <string key="concept:name" value="Case001"/>
                    <event>
                        <string key="concept:name" value="Approve"/>
                        <string key="org:resource" value="John"/>
                        <string key="org:role" value="Manager"/>
                        <string key="org:group" value="Finance"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2023-01-15T11:00:00.000+01:00"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Review"/>
                        <string key="org:resource" value="Jane"/>
                        <string key="org:role" value="Analyst"/>
                        <string key="org:group" value="QA"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2023-01-15T12:00:00.000+01:00"/>
                    </event>
                </trace>
            </log>
        """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "cert-c1")

        val event1 = result.traces[0].events[0].eventNode
        assertEquals("John", event1.resource)
        assertEquals("Manager", event1.attributes["org:role"])
        assertEquals("Finance", event1.attributes["org:group"])

        val event2 = result.traces[0].events[1].eventNode
        assertEquals("Jane", event2.resource)
        assertEquals("Analyst", event2.attributes["org:role"])
        assertEquals("QA", event2.attributes["org:group"])
    }

    // =====================
    // Level D1: + cost:total, cost:currency
    // =====================

    @Test
    fun `D1 - parser handles cost attributes`() {
        // ProcessM: Level D1 adds cost extension (cost:total, cost:currency)
        val xes = """<?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Cost" prefix="cost" uri="http://www.xes-standard.org/cost.xesext"/>
                <string key="concept:name" value="D1 Test Log"/>
                <trace>
                    <string key="concept:name" value="Case001"/>
                    <float key="cost:total" value="500.00"/>
                    <string key="cost:currency" value="EUR"/>
                    <event>
                        <string key="concept:name" value="Process Payment"/>
                        <float key="cost:total" value="150.50"/>
                        <string key="cost:currency" value="EUR"/>
                        <date key="time:timestamp" value="2023-01-15T09:00:00.000+01:00"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Ship Order"/>
                        <float key="cost:total" value="349.50"/>
                        <string key="cost:currency" value="EUR"/>
                        <date key="time:timestamp" value="2023-01-15T10:00:00.000+01:00"/>
                    </event>
                </trace>
            </log>
        """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "cert-d1")

        // Trace cost
        assertEquals(500.00, result.traces[0].traceNode.attributes["cost:total"])
        assertEquals("EUR", result.traces[0].traceNode.attributes["cost:currency"])

        // Event costs
        val event1 = result.traces[0].events[0].eventNode
        assertEquals(150.50, event1.cost)
        assertEquals("EUR", event1.attributes["cost:currency"])

        val event2 = result.traces[0].events[1].eventNode
        assertEquals(349.50, event2.cost)
        assertEquals("EUR", event2.attributes["cost:currency"])
    }

    // =====================
    // Level X1: non-standard extensions (custom attributes)
    // =====================

    @Test
    fun `X1 - parser handles non-standard extensions and custom attributes`() {
        // ProcessM: Level X1 tests non-standard custom attributes
        val xes = """<?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <string key="concept:name" value="X1 Test Log"/>
                <string key="custom:source" value="ERP System"/>
                <int key="custom:version" value="42"/>
                <boolean key="custom:validated" value="true"/>
                <trace>
                    <string key="concept:name" value="Case001"/>
                    <string key="department" value="Engineering"/>
                    <int key="priority" value="3"/>
                    <float key="score" value="8.75"/>
                    <event>
                        <string key="concept:name" value="Custom Activity"/>
                        <string key="result" value="accept"/>
                        <int key="attempt" value="2"/>
                        <boolean key="automated" value="false"/>
                        <float key="confidence" value="0.95"/>
                        <date key="time:timestamp" value="2023-01-15T09:00:00.000+01:00"/>
                    </event>
                </trace>
            </log>
        """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "cert-x1")

        // Log custom attributes
        assertEquals("ERP System", result.logNode.attributes["custom:source"])
        assertEquals(42, result.logNode.attributes["custom:version"])
        assertEquals(true, result.logNode.attributes["custom:validated"])

        // Trace custom attributes
        val trace = result.traces[0]
        assertEquals("Engineering", trace.traceNode.attributes["department"])
        assertEquals(3, trace.traceNode.attributes["priority"])
        assertEquals(8.75, trace.traceNode.attributes["score"])

        // Event custom attributes
        val event = trace.events[0].eventNode
        assertEquals("accept", event.attributes["result"])
        assertEquals(2, event.attributes["attempt"])
        assertEquals(false, event.attributes["automated"])
        assertEquals(0.95, event.attributes["confidence"])
    }

    // =====================
    // Combined: Full certification (A1+B1+C1+D1+X1)
    // =====================

    @Test
    fun `full certification - parser handles all standard and custom attributes together`() {
        // Tests all certification levels combined in one XES file
        val xes = """<?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xes.features="nested-attributes" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Identity" prefix="identity" uri="http://www.xes-standard.org/identity.xesext"/>
                <extension name="Lifecycle" prefix="lifecycle" uri="http://www.xes-standard.org/lifecycle.xesext"/>
                <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>
                <extension name="Organizational" prefix="org" uri="http://www.xes-standard.org/org.xesext"/>
                <extension name="Cost" prefix="cost" uri="http://www.xes-standard.org/cost.xesext"/>
                <global scope="trace">
                    <string key="concept:name" value="__INVALID__"/>
                </global>
                <global scope="event">
                    <string key="concept:name" value="__INVALID__"/>
                    <string key="lifecycle:transition" value="complete"/>
                    <date key="time:timestamp" value="1970-01-01T00:00:00.000+00:00"/>
                </global>
                <classifier name="Event Name" keys="concept:name"/>
                <classifier name="Activity+Lifecycle" keys="concept:name lifecycle:transition"/>
                <string key="concept:name" value="Full Certification Log"/>
                <id key="identity:id" value="aaaa-bbbb-cccc-dddd"/>
                <string key="lifecycle:model" value="standard"/>
                <string key="source" value="Test"/>
                <int key="version" value="1"/>
                <trace>
                    <string key="concept:name" value="Case-Full-001"/>
                    <id key="identity:id" value="trace-id-001"/>
                    <float key="cost:total" value="1234.56"/>
                    <string key="cost:currency" value="USD"/>
                    <string key="department" value="HR"/>
                    <event>
                        <string key="concept:name" value="Submit Request"/>
                        <id key="identity:id" value="event-id-001"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2023-06-01T09:00:00.000+02:00"/>
                        <string key="org:resource" value="Alice"/>
                        <string key="org:role" value="Employee"/>
                        <string key="org:group" value="HR"/>
                        <float key="cost:total" value="10.00"/>
                        <string key="cost:currency" value="USD"/>
                        <string key="channel" value="web"/>
                        <int key="retries" value="0"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Approve"/>
                        <id key="identity:id" value="event-id-002"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2023-06-01T14:00:00.000+02:00"/>
                        <string key="org:resource" value="Bob"/>
                        <string key="org:role" value="Manager"/>
                        <string key="org:group" value="HR"/>
                        <float key="cost:total" value="0.00"/>
                        <string key="cost:currency" value="USD"/>
                        <boolean key="overridden" value="false"/>
                    </event>
                </trace>
            </log>
        """.trimIndent()

        val result = parser.parseXES(xes.byteInputStream(), "cert-full")

        // A1: concept:name + identity:id
        assertEquals("Full Certification Log", result.logNode.name)
        assertEquals("aaaa-bbbb-cccc-dddd", result.logNode.attributes["identity:id"])

        // B1: lifecycle + time
        assertEquals("standard", result.logNode.attributes["lifecycle:model"])

        // Trace
        val trace = result.traces[0]
        assertEquals("Case-Full-001", trace.traceNode.caseId)
        assertEquals("trace-id-001", trace.traceNode.attributes["identity:id"])

        // D1: cost
        assertEquals(1234.56, trace.traceNode.attributes["cost:total"])
        assertEquals("USD", trace.traceNode.attributes["cost:currency"])

        // X1: custom
        assertEquals("HR", trace.traceNode.attributes["department"])

        // Event 1: all levels
        val event1 = trace.events[0].eventNode
        assertEquals("Submit Request", event1.activity)                  // A1
        assertEquals("event-id-001", event1.attributes["identity:id"])   // A1
        assertEquals("complete", event1.lifecycle)                       // B1
        assertNotNull(event1.timestamp)                                  // B1
        assertEquals("Alice", event1.resource)                           // C1
        assertEquals("Employee", event1.attributes["org:role"])          // C1
        assertEquals("HR", event1.attributes["org:group"])               // C1
        assertEquals(10.00, event1.cost)                                 // D1
        assertEquals("web", event1.attributes["channel"])                // X1
        assertEquals(0, event1.attributes["retries"])                    // X1

        // Event 2
        val event2 = trace.events[1].eventNode
        assertEquals("Approve", event2.activity)
        assertEquals("Bob", event2.resource)
        assertEquals(0.00, event2.cost)
        assertEquals(false, event2.attributes["overridden"])
    }
}
