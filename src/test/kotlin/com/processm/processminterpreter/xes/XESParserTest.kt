package com.processm.processminterpreter.xes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(properties = ["spring.neo4j.uri=bolt://localhost:7687"])
class XESParserTest {
    private val xesParser = XESParser()

    @Test
    fun `should parse sample XES file successfully`() {
        // Given
        val resourcePath = "logs/sample_process.xes"
        val inputStream = this::class.java.classLoader.getResourceAsStream(resourcePath)
        assertNotNull(inputStream, "Sample XES file should exist in resources")

        // When
        val result =
            inputStream!!.use { stream ->
                xesParser.parseXES(stream, "test-log-001")
            }

        // Then
        assertNotNull(result)
        assertEquals("test-log-001", result.logNode.logId)
        assertEquals("Sample Process Log", result.logNode.name)
        assertEquals(3, result.traces.size)

        // Verify first trace
        val firstTrace = result.traces[0]
        assertEquals("Case_001", firstTrace.traceNode.caseId)
        assertEquals("test-log-001-trace-Case_001", firstTrace.traceNode.traceId)
        assertEquals(4, firstTrace.events.size)

        // Verify first event
        val firstEvent = firstTrace.events[0]
        assertEquals("Register Request", firstEvent.eventNode.activity)
        assertEquals("John Doe", firstEvent.eventNode.resource)
        assertEquals("complete", firstEvent.eventNode.lifecycle)
        assertEquals(50.0, firstEvent.eventNode.cost)




    }

    @Test
    fun `should handle missing log ID by generating one`() {
        // Given
        val resourcePath = "logs/sample_process.xes"
        val inputStream = this::class.java.classLoader.getResourceAsStream(resourcePath)
        assertNotNull(inputStream, "Sample XES file should exist in resources")

        // When
        val result =
            inputStream!!.use { stream ->
                xesParser.parseXES(stream, null) // No logId provided
            }

        // Then
        assertNotNull(result)
        assertTrue(result.logNode.logId.startsWith("log-"))
        assertEquals("Sample Process Log", result.logNode.name)


    }

    @Test
    fun `should parse all trace attributes correctly`() {
        // Given
        val resourcePath = "logs/sample_process.xes"
        val inputStream = this::class.java.classLoader.getResourceAsStream(resourcePath)
        assertNotNull(inputStream, "Sample XES file should exist in resources")

        // When
        val result =
            inputStream!!.use { stream ->
                xesParser.parseXES(stream, "test-log-002")
            }

        // Then
        val secondTrace = result.traces[1] // Case_002
        assertEquals("Case_002", secondTrace.traceNode.caseId)
        assertEquals("Variant_B", secondTrace.traceNode.attributes["case:variant"])
        assertEquals(2, secondTrace.traceNode.attributes["case:priority"])


    }

    @Test
    fun `should parse all event attributes correctly`() {
        // Given
        val resourcePath = "logs/sample_process.xes"
        val inputStream = this::class.java.classLoader.getResourceAsStream(resourcePath)
        assertNotNull(inputStream, "Sample XES file should exist in resources")

        // When
        val result =
            inputStream!!.use { stream ->
                xesParser.parseXES(stream, "test-log-003")
            }

        // Then
        val firstEvent = result.traces[0].events[0]
        assertEquals("Register Request", firstEvent.eventNode.activity)
        assertEquals("John Doe", firstEvent.eventNode.resource)
        assertEquals("Reception", firstEvent.eventNode.attributes["org:group"])
        assertEquals(50.0, firstEvent.eventNode.cost)
        assertNotNull(firstEvent.eventNode.timestamp)


    }

    @Test
    fun `should generate unique IDs for traces and events`() {
        // Given
        val resourcePath = "logs/sample_process.xes"
        val inputStream = this::class.java.classLoader.getResourceAsStream(resourcePath)
        assertNotNull(inputStream, "Sample XES file should exist in resources")

        // When
        val result =
            inputStream!!.use { stream ->
                xesParser.parseXES(stream, "test-log-004")
            }

        // Then
        val allTraceIds = result.traces.map { it.traceNode.traceId }
        val allEventIds =
            result.traces.flatMap { trace ->
                trace.events.map { it.eventNode.eventId }
            }

        // Check uniqueness
        assertEquals(allTraceIds.size, allTraceIds.toSet().size, "All trace IDs should be unique")
        assertEquals(allEventIds.size, allEventIds.toSet().size, "All event IDs should be unique")


    }
}
