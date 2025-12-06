package com.processm.processminterpreter.pql.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("Integration")
class WithOrderByQueryTests : BaseInterpreterTest() {

    @BeforeEach
    fun prepareData() {
        clearDatabase()
        // Seed data for ordering tests
        executeCypher("""
            CREATE (l:Log {name: "Test Log"})
            CREATE (t1:Trace {name: "Case 1", caseId: "Case 1"})
            
            // Events with different timestamps and costs
            CREATE (e1:Event {activity: "A", timestamp: datetime("2023-01-01T10:00:00Z"), cost: 10, eventId: "e1"})
            CREATE (e2:Event {activity: "B", timestamp: datetime("2023-01-01T12:00:00Z"), cost: 30, eventId: "e2"})
            CREATE (e3:Event {activity: "C", timestamp: datetime("2023-01-01T11:00:00Z"), cost: 20, eventId: "e3"})
            
            CREATE (l)-[:CONTAINS]->(t1)
            CREATE (t1)-[:HAS_EVENT]->(e1)
            CREATE (t1)-[:HAS_EVENT]->(e2)
            CREATE (t1)-[:HAS_EVENT]->(e3)
        """.trimIndent())
    }

    @Test
    fun `test order by timestamp asc`() {
        // Expected order: A (10:00), C (11:00), B (12:00)
        val pql = "select e:name order by e:timestamp asc"
        val result = pqlQueryService.executePQLQuery(pql)
        
        assertTrue(result.success)
        assertEquals(3, result.resultCount)
        
        val names = result.results.map { it["e_name"] as String }
        assertEquals(listOf("A", "C", "B"), names)
    }

    @Test
    fun `test order by timestamp desc`() {
        // Expected order: B (12:00), C (11:00), A (10:00)
        val pql = "select e:name order by e:timestamp desc"
        val result = pqlQueryService.executePQLQuery(pql)
        
        assertTrue(result.success)
        assertEquals(3, result.resultCount)
        
        val names = result.results.map { it["e_name"] as String }
        assertEquals(listOf("B", "C", "A"), names)
    }

    @Test
    fun `test order by cost desc`() {
        // Expected order: B (30), C (20), A (10)
        val pql = "select e:name, e:cost order by e:cost desc"
        val result = pqlQueryService.executePQLQuery(pql)
        
        assertTrue(result.success)
        assertEquals(3, result.resultCount)
        
        val costs = result.results.map { (it["e_cost"] as Number).toInt() }
        assertEquals(listOf(30, 20, 10), costs)
    }

    @Test
    fun `test order by multiple fields`() {
        // Add duplicate cost event to test secondary sort
        executeCypher("""
            MATCH (t:Trace {caseId: "Case 1"})
            CREATE (e4:Event {activity: "D", timestamp: datetime("2023-01-01T13:00:00Z"), cost: 20, eventId: "e4"})
            CREATE (t)-[:HAS_EVENT]->(e4)
        """.trimIndent())
        
        // Order by cost DESC, then activity ASC
        // B (30), C (20), D (20), A (10) -> C comes before D alphabetically
        val pql = "select e:name, e:cost order by e:cost desc, e:name asc"
        val result = pqlQueryService.executePQLQuery(pql)
        
        assertTrue(result.success)
        assertEquals(4, result.resultCount)
        
        val names = result.results.map { it["e_name"] as String }
        assertEquals(listOf("B", "C", "D", "A"), names)
    }
}
