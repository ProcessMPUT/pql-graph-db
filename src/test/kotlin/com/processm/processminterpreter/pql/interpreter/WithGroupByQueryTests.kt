package com.processm.processminterpreter.pql.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("Integration")
class WithGroupByQueryTests : BaseInterpreterTest() {

    @BeforeEach
    fun prepareData() {
        clearDatabase()
        // Seed data for grouping tests
        // Log -> Trace -> Event
        // We need multiple events with same activity to test grouping
        executeCypher("""
            CREATE (l:Log {name: "Test Log"})
            
            // Trace 1: A, B, A
            CREATE (t1:Trace {name: "Case 1", caseId: "Case 1"})
            CREATE (e1_1:Event {activity: "A", timestamp: datetime("2023-01-01T10:00:00Z"), cost: 10, eventId: "e1_1"})
            CREATE (e1_2:Event {activity: "B", timestamp: datetime("2023-01-01T11:00:00Z"), cost: 20, eventId: "e1_2"})
            CREATE (e1_3:Event {activity: "A", timestamp: datetime("2023-01-01T12:00:00Z"), cost: 15, eventId: "e1_3"})
            
            // Trace 2: A, C
            CREATE (t2:Trace {name: "Case 2", caseId: "Case 2"})
            CREATE (e2_1:Event {activity: "A", timestamp: datetime("2023-01-02T10:00:00Z"), cost: 12, eventId: "e2_1"})
            CREATE (e2_2:Event {activity: "C", timestamp: datetime("2023-01-02T11:00:00Z"), cost: 30, eventId: "e2_2"})
            
            CREATE (l)-[:CONTAINS]->(t1)
            CREATE (l)-[:CONTAINS]->(t2)
            
            CREATE (t1)-[:HAS_EVENT]->(e1_1)
            CREATE (t1)-[:HAS_EVENT]->(e1_2)
            CREATE (t1)-[:HAS_EVENT]->(e1_3)
            
            CREATE (t2)-[:HAS_EVENT]->(e2_1)
            CREATE (t2)-[:HAS_EVENT]->(e2_2)
        """.trimIndent())
    }

    @Test
    fun `test group by activity with count`() {
        // Count events per activity
        // A: 3 events (10+15+12)
        // B: 1 event (20)
        // C: 1 event (30)
        val pql = "select e:name, count(e:id) group by e:name order by e:name asc"
        val result = pqlQueryService.executePQLQuery(pql)
        
        assertTrue(result.success)
        assertEquals(3, result.resultCount)
        
        val results = result.results
        println("Results: $results")
        
        // Check Activity A
        val rowA = results.find { it["e_name"] == "A" }
        // Key is likely "count(event.eventId)" based on previous run
        val countKey = results[0].keys.find { it.contains("count") } ?: "count"
        
        assertEquals(3L, (rowA?.get(countKey) as Number).toLong())
        
        // Check Activity B
        val rowB = results.find { it["e_name"] == "B" }
        assertEquals(1L, (rowB?.get(countKey) as Number).toLong())
        
        // Check Activity C
        val rowC = results.find { it["e_name"] == "C" }
        assertEquals(1L, (rowC?.get(countKey) as Number).toLong())
    }

    @Test
    fun `test group by activity with sum cost`() {
        // Sum cost per activity
        // A: 10 + 15 + 12 = 37
        // B: 20
        // C: 30
        val pql = "select e:name, sum(e:cost) group by e:name"
        val result = pqlQueryService.executePQLQuery(pql)
        
        assertTrue(result.success)
        assertEquals(3, result.resultCount)
        
        val results = result.results
        val sumKey = results[0].keys.find { it.contains("sum") } ?: "sum"
        
        val rowA = results.find { it["e_name"] == "A" }
        assertEquals(37.0, (rowA?.get(sumKey) as Number).toDouble(), 0.1)
        
        val rowB = results.find { it["e_name"] == "B" }
        assertEquals(20.0, (rowB?.get(sumKey) as Number).toDouble(), 0.1)
    }

    @Test
    fun `test group by multiple fields`() {
        // Group by Trace Name and Activity
        // Case 1, A: 2
        // Case 1, B: 1
        // Case 2, A: 1
        // Case 2, C: 1
        val pql = "select t:name, e:name, count(e:id) group by t:name, e:name"
        val result = pqlQueryService.executePQLQuery(pql)
        
        assertTrue(result.success)
        assertEquals(4, result.resultCount)
        
        val results = result.results
        println("Results: $results")
        val countKey = results[0].keys.find { it.contains("count") } ?: "count"
        
        val case1A = results.find { it["t_name"] == "Case 1" && it["e_name"] == "A" }
        assertEquals(2L, (case1A?.get(countKey) as Number).toLong())
        
        val case2A = results.find { it["t_name"] == "Case 2" && it["e_name"] == "A" }
        assertEquals(1L, (case2A?.get(countKey) as Number).toLong())
    }
}
