package com.processm.processminterpreter.processm.log

import com.processm.processminterpreter.xes.io.XesWriteOptions
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.io.OpenXesWriter
import com.processm.processminterpreter.xes.io.XESParser
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XES writer compatibility tests adapted from ProcessM XMLXESOutputStreamTest.kt.
 *
 * Tests based on: XMLXESOutputStreamTest.kt
 * Original: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/log/XMLXESOutputStreamTest.kt
 */
class XESWriterTests {
    private val writer = OpenXesWriter()
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
    @Disabled("Not applicable to OpenXesWriter: stream abort lifecycle belongs to ProcessM XMLXESOutputStream API.")
    fun `Abort will close the stream and user can close it again without any exception`() {
    }

    @Test
    @Disabled("Not applicable to OpenXesWriter: this adapter writes a full document per call instead of exposing close-only stream lifecycle.")
    fun `Close XML without passing any XML Element`() {
    }

    @Test
    fun `Write log without any events and attributes`() {
        val xml = write(XesLog())
        val output = parser.parseXesLog(xml.byteInputStream())

        assertTrue(xml.contains("<log xes.version=\"1.0\" xes.features=\"nested-attributes\""))
        assertEquals("Query Result Log", output.conceptName)
        assertEquals(0, output.traces.size)
    }

    @Test
    @Disabled("Not applicable to XesLog tree writer: event stream traces are a ProcessM streaming writer feature.")
    fun `Event stream as input - ignore trace element`() {
    }

    @Test
    fun `Compare imported and exported XML files`() {
        val imported = parser.parseXesLog(content.byteInputStream())
        val xml = write(imported)
        val exported = parser.parseXesLog(xml.byteInputStream())

        assertTrue(xml.contains("key=\"conceptowy:name\""))
        assertFalse(xml.contains("key=\"concept:name\""))
        assertTrue(xml.contains("<id key=\"id\" value=\"22a66e06-9371-4dbf-aee3-b58b44564a0c\"/>"))
        assertTrue(xml.contains("<list key=\"listKey\">"))
        assertEquals(imported, exported)
    }

    private fun write(log: XesLog): String {
        val output = ByteArrayOutputStream()
        writer.write(listOf(log), output, XesWriteOptions())
        return output.toString(StandardCharsets.UTF_8)
    }
}
