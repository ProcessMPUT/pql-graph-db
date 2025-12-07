package com.processm.processminterpreter.pql.extended

import com.processm.processminterpreter.pql.interpreter.BaseInterpreterTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("Integration")
class WithWhereQueryTests : BaseInterpreterTest() {

    @BeforeEach
    fun prepareData() {
        clearDatabase()
        // Seed simple process data
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
    fun testWhereEquals() {
        val result = pqlQueryService.executePQLQuery("select e:name where e:name = 'A'")
        
        assertTrue(result.success)
        assertEquals(1, result.resultCount)
        assertEquals("A", result.results[0]["e_name"])
        
        // Verify generated Cypher

        assertTrue(result.cypherQuery!!.contains("WHERE"))
        assertTrue(result.cypherQuery!!.contains("event.activity = "))
    }

    @Test
    fun testWhereIn() {
        val result = pqlQueryService.executePQLQuery("select e:name where e:name in ('A', 'B')")
        
        assertTrue(result.success)
        assertEquals(2, result.resultCount)
        

        assertTrue(result.cypherQuery!!.contains("IN"))
    }

    @Test
    fun testWhereLike() {
        val result = pqlQueryService.executePQLQuery("select e:name where e:name like 'A'")
        
        assertTrue(result.success)
        assertEquals(1, result.resultCount)
        assertEquals("A", result.results[0]["e_name"])
        

        assertTrue(result.cypherQuery!!.contains("=~"))
    }
    
    @Test
    fun testWhereAnd() {
        val result = pqlQueryService.executePQLQuery("select e:name where e:name = 'A' and e:timestamp > D2022-01-01")
        

        assertTrue(result.success)
        assertEquals(1, result.resultCount)
        
        assertTrue(result.cypherQuery!!.contains("AND"))
        assertTrue(result.cypherQuery!!.contains("event.timestamp >"))
    }
}
