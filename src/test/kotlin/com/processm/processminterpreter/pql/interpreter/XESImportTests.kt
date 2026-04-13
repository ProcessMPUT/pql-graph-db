package com.processm.processminterpreter.pql.interpreter

import com.processm.processminterpreter.xes.XESLoader
import com.processm.processminterpreter.xes.XESParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

@Tag("Integration")
class XESImportTests : BaseInterpreterTest() {
    private lateinit var xesLoader: XESLoader

    @BeforeEach
    fun initLoader() {
        xesLoader = XESLoader(XESParser(), driver)
    }

    @Test
    fun `test import XES`() {
        clearDatabase()

        val xesContent =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xes.features="nested-attributes" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>
                <string key="concept:name" value="Test Log"/>
                <trace>
                    <string key="concept:name" value="Case 1"/>
                    <event>
                        <string key="concept:name" value="A"/>
                        <date key="time:timestamp" value="2023-01-01T10:00:00.000+00:00"/>
                        <string key="org:resource" value="User1"/>
                        <float key="cost:total" value="10.0"/>
                    </event>
                    <event>
                        <string key="concept:name" value="B"/>
                        <date key="time:timestamp" value="2023-01-01T11:00:00.000+00:00"/>
                        <string key="org:resource" value="User2"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val inputStream = ByteArrayInputStream(xesContent.toByteArray())
        val result = xesLoader.loadXESFile(inputStream, "test-log")

        assertTrue(result.success, "Import should be successful")
        assertEquals(1, result.tracesCount)
        assertEquals(2, result.eventsCount)

        // Verify Neo4j content
        driver.session().use { session ->
            // Check Log
            val logResult = session.run("MATCH (l:Log {logId: 'test-log'}) RETURN l.name as name").single()
            assertEquals("Test Log", logResult.get("name").asString())

            // Check Trace
            val traceResult = session.run("MATCH (l:Log)-[:CONTAINS]->(t:Trace) RETURN t.caseId as caseId").single()
            assertEquals("Case 1", traceResult.get("caseId").asString())

            // Check Events
            val eventsResult =
                session
                    .run(
                        """
                        MATCH (t:Trace)-[:HAS_EVENT]->(e:Event) 
                        RETURN e.activity as activity, e.resource as resource, e.cost as cost 
                        ORDER BY e.timestamp
                        """.trimIndent(),
                    ).list()

            assertEquals(2, eventsResult.size)

            val event1 = eventsResult[0]
            assertEquals("A", event1.get("activity").asString())
            assertEquals("User1", event1.get("resource").asString())
            assertEquals(10.0, event1.get("cost").asDouble())

            val event2 = eventsResult[1]
            assertEquals("B", event2.get("activity").asString())
            assertEquals("User2", event2.get("resource").asString())
        }
    }
}
