package com.processm.processminterpreter.pql.interpreter

import com.processm.processminterpreter.xes.XESWriter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayOutputStream

@Tag("Integration")
class XESExportTests : BaseInterpreterTest() {

    @BeforeEach
    fun prepareData() {
        clearDatabase()
        // Seed data for export tests
        executeCypher("""
            CREATE (l:Log {name: "Export Log"})
            CREATE (t1:Trace {name: "Case 1", caseId: "Case 1"})
            CREATE (e1:Event {activity: "A", timestamp: datetime("2023-01-01T10:00:00Z"), cost: 10, resource: "User1", lifecycle: "complete", eventId: "e1"})
            
            CREATE (l)-[:CONTAINS]->(t1)
            CREATE (t1)-[:HAS_EVENT]->(e1)
        """.trimIndent())
    }

    @Test
    fun `test export to XES`() {
        // Select all events
        val pql = "select *"
        val result = pqlQueryService.executePQLQuery(pql)
        
        assertTrue(result.success)
        
        // Export to XES
        val outputStream = ByteArrayOutputStream()
        xesWriter.writeXES(result.results, outputStream)
        
        val xesContent = outputStream.toString()
        
        // Verify XES structure
        assertTrue(xesContent.contains("<log xes.version=\"1.0\""), "Should contain log element")
        assertTrue(xesContent.contains("<trace>"), "Should contain trace element")
        assertTrue(xesContent.contains("<string key=\"concept:name\" value=\"Case 1\"/>"), "Should contain trace name")
        assertTrue(xesContent.contains("<event>"), "Should contain event element")
        assertTrue(xesContent.contains("<string key=\"concept:name\" value=\"A\"/>"), "Should contain activity name")
        assertTrue(xesContent.contains("<date key=\"time:timestamp\" value=\"2023-01-01T10:00:00.000Z\"/>") || 
                   xesContent.contains("<date key=\"time:timestamp\" value=\"2023-01-01T11:00:00.000+01:00\"/>"), // Timezone might vary
                   "Should contain timestamp")
        assertTrue(xesContent.contains("<string key=\"org:resource\" value=\"User1\"/>"), "Should contain resource")
        assertTrue(xesContent.contains("<float key=\"cost:total\" value=\"10.0\"/>"), "Should contain cost")
    }
}
