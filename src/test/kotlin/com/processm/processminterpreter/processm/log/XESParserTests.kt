package com.processm.processminterpreter.processm.log

import com.processm.processminterpreter.xes.model.AttributeScope
import com.processm.processminterpreter.xes.model.XesAttributeValue
import com.processm.processminterpreter.xes.io.XESParseException
import com.processm.processminterpreter.xes.io.XESParser
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * XES parser compatibility tests adapted from ProcessM XMLXESInputStreamTest.kt.
 *
 * Tests based on: XMLXESInputStreamTest.kt
 * Original: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/log/XMLXESInputStreamTest.kt
 */
class XESParserTests {
    private val parser = XESParser()

    private val content =
        """
        <?xml version="1.0" encoding="UTF-8" ?>
        <!-- OpenXES library version: 1.0RC7 -->
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
                <list key="globalListKey">
                    <int key="intInsideListKey" value="25" />
                    <values>
                        <float key="__UNKNOWN__" value="123.617"/>
                        <int key="__NEW__" value="456"/>
                    </values>
                </list>
            </global>
            <classifier name="Event Name" keys="conceptowy:name"/>
            <classifier scope="trace" name="Department Classifier" keys="org:group"/>
            <float key="meta_org:resource_events_standard_deviation" value="202.617">
                <float key="UNKNOWN" value="202.617"/>
            </float>
            <list key="listKey">
                <int key="intInsideListKey" value="22" />
                <values>
                    <float key="__UNKNOWN__" value="202.617"/>
                    <int key="__NEW__" value="111"/>
                </values>
            </list>
            <id key="id" value="22a66e06-9371-4dbf-aee3-b58b44564a0c"/>
            <string key="meta_3TU:log_type" value="Real-life"/>
            <string key="conceptowy:name" value="Some amazing log file"/>
            <int key="meta_org:role_events_total" value="150291">
                <int key="UNKNOWN" value="150291"/>
            </int>
            <trace>
                <date key="End date" value="2006-01-04T23:45:36.000+01:00"/>
                <int key="Age" value="33"/>
                <string key="conceptowy:name" value="00000001"/>
                <event>
                    <string key="org:group" value="Radiotherapy"/>
                    <int key="Number of executions" value="1"/>
                    <int key="Specialism code" value="61"/>
                    <string key="conceptowy:name" value="1e consult poliklinisch"/>
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
                    <string key="conceptowy:name" value="administratief tarief - eerste pol"/>
                    <string key="Producer code" value="SRTH"/>
                    <string key="Section" value="Section 5"/>
                    <int key="Activity code" value="419100"/>
                    <date key="time:timestamp" value="2005-01-03T00:00:00+01:00"/>
                    <string key="lifecycle:transition" value="complete"/>
                </event>
            </trace>
        </log>
        """.trimIndent()

    @Test
    fun `XES parser can recognize extensions and prepare it inside Log`() {
        val result = parse(content)

        assertEquals(4, result.extensions.size)
        assertEquals("Lifecycle", result.extensions.first { it.prefix == "lifecycle" }.name)
        assertEquals("http://www.xes-standard.org/lifecycle.xesext", result.extensions.first { it.prefix == "lifecycle" }.uri)
        assertEquals("Concept", result.extensions.first { it.prefix == "conceptowy" }.name)
        assertEquals("http://www.xes-standard.org/concept.xesext", result.extensions.first { it.prefix == "conceptowy" }.uri)
        assertEquals("Organizational", result.extensions.first { it.prefix == "org" }.name)
        assertEquals("http://www.xes-standard.org/org.xesext", result.extensions.first { it.prefix == "org" }.uri)
        assertEquals("Metadata_Organizational", result.extensions.first { it.prefix == "meta_org" }.name)
        assertEquals("http://www.xes-standard.org/meta_org.xesext", result.extensions.first { it.prefix == "meta_org" }.uri)
    }


    @Test
    fun `XES parser is able to load trace globals`() {
        val result = parse(content)

        assertEquals(1, result.traceGlobals.size)
        assertEquals(AttributeScope.TRACE, result.traceGlobals[0].scope)
        assertEquals("conceptowy:name", result.traceGlobals[0].key)
        assertEquals("__INVALID__", result.traceGlobals[0].value)
    }

    @Test
    fun `XES parser is able to load event globals even when scope key missing`() {
        val result = parse(content)
        val globals = result.eventGlobals.associateBy { it.key }

        assertEquals(5, result.eventGlobals.size)
        assertEquals("__INVALID__", globals.getValue("conceptowy:name").value)
        assertEquals("complete", globals.getValue("lifecycle:transition").value)
        assertEquals("__INVALID__", globals.getValue("org:group").value)
        assertEquals(Instant.parse("1970-01-01T00:00:00Z"), globals.getValue("time:timestamp").value)

        val list = globals.getValue("globalListKey").value as Map<*, *>
        val attributes = list["attributes"] as Map<*, *>
        val values = list["values"] as List<*>
        assertEquals(25, attributes["intInsideListKey"])
        assertEquals(mapOf("__UNKNOWN__" to 123.617), values[0])
        assertEquals(mapOf("__NEW__" to 456), values[1])
    }

    @Test
    fun `XES parser is able to load classifiers into log structure`() {
        val result = parse(content)
        val classifiers = result.classifiers.associateBy { it.name }

        assertEquals(2, result.classifiers.size)
        assertEquals(listOf("conceptowy:name"), classifiers.getValue("Event Name").keys)
        assertEquals(AttributeScope.EVENT, classifiers.getValue("Event Name").scope)
        assertEquals(listOf("org:group"), classifiers.getValue("Department Classifier").keys)
        assertEquals(AttributeScope.TRACE, classifiers.getValue("Department Classifier").scope)
    }

    @Test
    fun `XES parser preserves nested child attributes on scalar attributes`() {
        val result = parse(content)

        val standardDeviation = result.customAttributes.getValue("meta_org:resource_events_standard_deviation")
        val roleTotal = result.customAttributes.getValue("meta_org:role_events_total")

        require(standardDeviation is XesAttributeValue)
        require(roleTotal is XesAttributeValue)

        assertEquals(202.617, standardDeviation.value)
        assertEquals(202.617, standardDeviation.children["UNKNOWN"])
        assertEquals(150291, roleTotal.value)
        assertEquals(150291, roleTotal.children["UNKNOWN"])
    }

    @Test
    fun `XES parser will throw exception when found invalid XML tag inside log structure`() {
        val thrown = assertFailsWith<XESParseException> {
            parse(
                """
                <?xml version="1.0" encoding="UTF-8" ?>
                <log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7" xmlns="http://www.xes-standard.org/">
                    <invalid-tag name="Lifecycle" prefix="lifecycle" uri="http://www.xes-standard.org/lifecycle.xesext"/>
                </log>
                """.trimIndent(),
            )
        }
        assertEquals(
            "Failed to parse XES file: Found unexpected XML tag: invalid-tag in line 3 column 106",
            thrown.message,
        )
    }

    @Test
    fun `XES parser will throw exception when found invalid XML tag inside trace structure`() {
        val thrown = assertFailsWith<XESParseException> {
            parse(
                """
                <?xml version="1.0" encoding="UTF-8" ?>
                <log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7" xmlns="http://www.xes-standard.org/">
                    <trace>
                        <foo key="bar" value="123.22"/>
                    </trace>
                </log>
                """.trimIndent(),
            )
        }
        assertEquals(
            "Failed to parse XES file: Found unexpected XML tag: foo in line 4 column 40",
            thrown.message,
        )
    }

    @Test
    fun `XES parser will throw exception when found invalid global scope`() {
        val thrown =
            assertFailsWith<XESParseException> {
                parse(
                    """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7" xmlns="http://www.xes-standard.org/">
                        <global scope="invalid-scope">
                            <string key="conceptowy:name" value="__INVALID__"/>
                        </global>
                    </log>
                    """.trimIndent(),
                )
            }

        assertEquals(
            "Failed to parse XES file: Illegal <global> scope. Expected 'trace' or 'event', found invalid-scope",
            thrown.message,
        )
    }

    @Test
    fun `XES parser will throw exception when found invalid classifier's scope`() {
        val thrown =
            assertFailsWith<XESParseException> {
                parse(
                    """
                    <?xml version="1.0" encoding="UTF-8" ?>
                    <log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7" xmlns="http://www.xes-standard.org/">
                        <classifier scope="invalid" name="invalid" keys="concept:name"/>
                    </log>
                    """.trimIndent(),
                )
            }

        assertEquals(
            "Failed to parse XES file: Illegal <classifier> scope. Expected 'trace' or 'event', found invalid",
            thrown.message,
        )
    }

    @Test
    fun `XES parser is able to recognize list attribute`() {
        val result = parse(content)
        val list = result.customAttributes.getValue("listKey") as Map<*, *>
        val attributes = list["attributes"] as Map<*, *>
        val values = list["values"] as List<*>

        assertEquals(22, attributes["intInsideListKey"])
        assertEquals(mapOf("__UNKNOWN__" to 202.617), values[0])
        assertEquals(mapOf("__NEW__" to 111), values[1])
    }

    @Test
    fun `XES parser is able to build trace structure`() {
        val trace = parse(content).traces[0]

        assertEquals("00000001", trace.conceptName)
        assertEquals(Instant.parse("2006-01-04T22:45:36Z"), trace.customAttributes["End date"])
        assertEquals(33, trace.customAttributes["Age"])
        assertNull(trace.identityId)
        assertNull(trace.costCurrency)
        assertNull(trace.costTotal)
    }

    @Test
    fun `XES parser is able to build event structure`() {
        val event = parse(content).traces[0].events[0]

        assertEquals("1e consult poliklinisch", event.conceptName)
        assertEquals(Instant.parse("2005-01-02T23:00:00Z"), event.timeTimestamp)
        assertEquals("complete", event.lifecycleTransition)
        assertEquals("Radiotherapy", event.orgGroup)
        assertEquals(410100, event.customAttributes["Activity code"])
        assertEquals("Section 5", event.customAttributes["Section"])
        assertEquals("SRTH", event.customAttributes["Producer code"])
        assertEquals(1, event.customAttributes["Number of executions"])
        assertEquals(61, event.customAttributes["Specialism code"])
    }

    @Test
    fun `XES parser is able to add meaning assigned to most popular extensions' fields inside log structure`() {
        val result = parse(content)

        assertEquals("Some amazing log file", result.conceptName)
        assertNull(result.identityId)
        assertNull(result.lifecycleModel)
    }

    @Test
    fun `XES parser is able to add meaning assigned to most popular extensions' fields inside trace structure`() {
        val trace = parse(content).traces[0]

        assertEquals("00000001", trace.conceptName)
        assertNull(trace.costCurrency)
        assertNull(trace.costTotal)
        assertNull(trace.identityId)
    }

    @Test
    fun `XES parser is able to add meaning assigned to most popular extensions' fields inside event structure`() {
        val event = parse(content).traces[0].events[0]

        assertEquals("1e consult poliklinisch", event.conceptName)
        assertEquals("complete", event.lifecycleTransition)
        assertEquals("Radiotherapy", event.orgGroup)
    }

    private fun parse(xml: String) = parser.parseXesLog(xml.byteInputStream())
}
