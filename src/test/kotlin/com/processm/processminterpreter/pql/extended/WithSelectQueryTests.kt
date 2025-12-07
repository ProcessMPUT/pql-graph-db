package com.processm.processminterpreter.pql.extended

import com.processm.processminterpreter.pql.interpreter.BaseInterpreterTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("Integration")
class WithSelectQueryTests : BaseInterpreterTest() {

    @BeforeEach
    fun prepareData() {
        clearDatabase()
        // Seed simple process data
        // Log -> Trace -> Event
        executeCypher("""
            CREATE (l:Log {name: "Test Log"})
            CREATE (t1:Trace {name: "Case 1", caseId: "Case 1"})
            CREATE (t2:Trace {name: "Case 2", caseId: "Case 2"})
            CREATE (e1:Event {activity: "A", timestamp: datetime("2023-01-01T10:00:00Z")})
            CREATE (e2:Event {activity: "B", timestamp: datetime("2023-01-01T11:00:00Z")})
            CREATE (e3:Event {activity: "C", timestamp: datetime("2023-01-01T12:00:00Z")})
            
            CREATE (l)-[:CONTAINS]->(t1)
            CREATE (l)-[:CONTAINS]->(t2)
            CREATE (t1)-[:HAS_EVENT]->(e1)
            CREATE (t1)-[:HAS_EVENT]->(e2)
            CREATE (t2)-[:HAS_EVENT]->(e3)
        """.trimIndent())
    }

    @Test
    fun testSelectEventName() {
        val result = pqlQueryService.executePQLQuery("select e:name")
        
        assertTrue(result.success)
        assertEquals(3, result.resultCount)
        
        val names = result.results.map { it["e_name"] as String }.sorted()

        assertEquals(listOf("A", "B", "C"), names)
        
        // Verify generated Cypher contains expected clauses

        assertTrue(result.cypherQuery!!.contains("MATCH"))
        assertTrue(result.cypherQuery!!.contains("RETURN"))
        assertTrue(result.cypherQuery!!.contains("event.activity AS e_name"))
    }

    @Test
    fun testSelectTraceName() {
        val result = pqlQueryService.executePQLQuery("select t:name")
        
        assertTrue(result.success)
        assertEquals(2, result.resultCount)
        
        val names = result.results.map { it["t_name"] as String }.sorted()

        assertEquals(listOf("Case 1", "Case 2"), names)
        
        // Verify generated Cypher

        assertTrue(result.cypherQuery!!.contains("trace.caseId AS t_name"))
    }
}
