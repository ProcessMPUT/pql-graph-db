package com.processm.processminterpreter.domain.pql.interpreter

import com.processm.processminterpreter.application.ports.XesWriteOptions
import com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.Neo4jXesLogWriter
import com.processm.processminterpreter.infrastructure.xes.OpenXesReader
import com.processm.processminterpreter.infrastructure.xes.XESLoader
import com.processm.processminterpreter.infrastructure.xes.XESParser
import com.processm.processminterpreter.infrastructure.xes.OpenXesWriter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@Tag("Integration")
class XESExportTests : BaseInterpreterTest() {
    @BeforeEach
    fun prepareData() {
        clearDatabase()
        val importResult = XESLoader(OpenXesReader(XESParser()), Neo4jXesLogWriter(driver)).loadXESFile(
            ByteArrayInputStream(exportFixture.toByteArray()),
            "export-log",
        )
        attachLogToInterpreterDataStore(importResult.logId ?: "export-log")
    }

    @Test
    fun `test export to XES`() {
        // Select all events
        val pql = "select *"
        val result = executeDataStoreQuery(pql)

        assertTrue(result.success)

        // Export to XES
        val outputStream = ByteArrayOutputStream()
        OpenXesWriter().write(
            logs = result.logs,
            output = outputStream,
            options = XesWriteOptions(logName = "Query Result Log"),
        )

        val xesContent = outputStream.toString()

        // Verify XES structure
        assertTrue(xesContent.contains("<log xes.version=\"1.0\""), "Should contain log element")
        assertTrue(xesContent.contains("<trace>"), "Should contain trace element")
        assertTrue(xesContent.contains("<string key=\"concept:name\" value=\"Case 1\"/>"), "Should contain trace name")
        assertTrue(xesContent.contains("<event>"), "Should contain event element")
        assertTrue(xesContent.contains("<string key=\"concept:name\" value=\"A\"/>"), "Should contain activity name")
        assertTrue(
            xesContent.contains("<date key=\"time:timestamp\" value=\"2023-01-01T10:00:00.000Z\"/>") ||
                xesContent.contains("<date key=\"time:timestamp\" value=\"2023-01-01T11:00:00.000+01:00\"/>"),
            // Timezone might vary
            "Should contain timestamp",
        )
        assertTrue(xesContent.contains("<string key=\"org:resource\" value=\"User1\"/>"), "Should contain resource")
        assertTrue(xesContent.contains("<float key=\"cost:total\" value=\"10.0\"/>"), "Should contain cost")
    }

    private companion object {
        val exportFixture =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xes.features="nested-attributes" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>
                <extension name="Organizational" prefix="org" uri="http://www.xes-standard.org/org.xesext"/>
                <extension name="Cost" prefix="cost" uri="http://www.xes-standard.org/cost.xesext"/>
                <string key="concept:name" value="Export Log"/>
                <trace>
                    <string key="concept:name" value="Case 1"/>
                    <event>
                        <string key="concept:name" value="A"/>
                        <date key="time:timestamp" value="2023-01-01T10:00:00.000Z"/>
                        <string key="org:resource" value="User1"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <float key="cost:total" value="10.0"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()
    }
}


