package com.processm.processminterpreter.pql.extended

import com.processm.processminterpreter.pql.interpreter.BaseInterpreterTest
import com.processm.processminterpreter.pql.model.Scope
import com.processm.processminterpreter.service.LogService
import com.processm.processminterpreter.xes.XESLoader
import com.processm.processminterpreter.xes.XESParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayInputStream

class GroupByHoistingTests : BaseInterpreterTest() {

    private lateinit var xesParser: XESParser
    private lateinit var xesLoader: XESLoader
    private lateinit var logService: LogService

    private lateinit var logId: String

    @BeforeEach
    fun init() {
        // BaseInterpreterTest.setup() is @BeforeAll, so driver is already initialized
        xesParser = XESParser()
        logService = Mockito.mock(LogService::class.java)
        xesLoader = XESLoader(xesParser, logService, driver)

        // Generate unique log ID for this test run
        logId = "groupby-test-log-${java.util.UUID.randomUUID()}"

        // Load sample data
        val xesContent = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7">
                <string key="concept:name" value="Test Log"/>
                <trace>
                    <string key="concept:name" value="Trace1"/>
                    <event>
                        <string key="concept:name" value="A"/>
                        <string key="org:resource" value="User1"/>
                        <float key="cost:total" value="10.0"/>
                    </event>
                    <event>
                        <string key="concept:name" value="B"/>
                        <string key="org:resource" value="User1"/>
                        <float key="cost:total" value="20.0"/>
                    </event>
                </trace>
                <trace>
                    <string key="concept:name" value="Trace2"/>
                    <event>
                        <string key="concept:name" value="A"/>
                        <string key="org:resource" value="User2"/>
                        <float key="cost:total" value="15.0"/>
                    </event>
                </trace>
            </log>
        """.trimIndent()

        xesLoader.loadXESFile(ByteArrayInputStream(xesContent.toByteArray()), logId)
    }

    private fun executeQuery(query: String, logId: String): List<Map<String, Any?>> {
        val result = pqlQueryService.executePQLQuery(query, logId)
        if (!result.success) {
            throw IllegalArgumentException(result.error)
        }
        println("Query: $query")
        println("Result size: ${result.results.size}")
        println("Result keys: ${result.results.firstOrNull()?.keys}")
        result.results.forEach { println("Row: $it") }
        return result.results
    }

    @Test
    fun `test GROUP BY trace attribute`() {
        // Group by trace attribute (t:concept:name)
        // Count events per trace
        val query = "select t:concept:name, count(e:concept:name) group by t:concept:name"
        val result = executeQuery(query, logId)

        assertEquals(2, result.size)
        // Trace1 has 2 events, Trace2 has 1 event
        val trace1 = result.find { it["t_concept_name"] == "Trace1" }
        val trace2 = result.find { it["t_concept_name"] == "Trace2" }

        assertNotNull(trace1)
        assertEquals(2L, trace1!!["count_e_concept_name_"])

        assertNotNull(trace2)
        assertEquals(1L, trace2!!["count_e_concept_name_"])
    }

    @Test
    fun `test GROUP BY hoisted event attribute`() {
        // Group by hoisted event attribute (^e:concept:name) groups traces by event sequence.
        // Trace1 has events [A, B], Trace2 has event [A] → 2 distinct groups.
        // After UNWIND, 3 rows: group "A,B" → 2 rows, group "A" → 1 row.
        val query = "select ^e:concept:name, count(e:concept:name) group by ^e:concept:name"
        val result = executeQuery(query, logId)

        // 2 groups unwound to 3 rows (group with 2 events → 2 rows + group with 1 event → 1 row)
        assertEquals(3, result.size)
        // Each row has e_concept_name (unwound event activity)
        assertTrue(result.all { it.containsKey("e_concept_name") })
        // The group with 2 events has count=2, the group with 1 event has count=1
        val counts = result.map { (it["count_e_concept_name_"] as Number).toLong() }.distinct().sorted()
        assertEquals(listOf(1L, 2L), counts, "Should have groups with count 1 and 2")
    }

    @Test
    fun `test GROUP BY multi-scope`() {
        // Group by trace name AND event resource
        // Trace1: User1 (2 events)
        // Trace2: User2 (1 event)
        val query = "select t:concept:name, e:org:resource, count(e:concept:name) group by t:concept:name, e:org:resource"
        val result = executeQuery(query, logId)

        assertEquals(2, result.size)
        
        val row1 = result.find { it["t_concept_name"] == "Trace1" && it["e_org_resource"] == "User1" }
        assertNotNull(row1)
        assertEquals(2L, row1!!["count_e_concept_name_"])
    }

    @Test
    fun `test validation failure missing group by`() {
        // Select event-level non-aggregated attribute without it being in GROUP BY
        // e:org:resource (Event scope) is NOT allowed without GROUP BY when grouping by t:concept:name (Trace scope)
        // because Event is a LOWER scope than Trace — lower-scope attributes must be in GROUP BY
        val query = "select e:org:resource, count(e:concept:name) group by t:concept:name"

        val exception = assertThrows(IllegalArgumentException::class.java) {
            executeQuery(query, logId)
        }

        assertTrue(exception.message!!.contains("must be present in GROUP BY clause"))
        assertTrue(exception.message!!.contains("e:org:resource"))
    }
}
