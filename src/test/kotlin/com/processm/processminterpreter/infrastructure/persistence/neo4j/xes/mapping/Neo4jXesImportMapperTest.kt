package com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.mapping

import com.processm.processminterpreter.domain.log.xes.XesAttributeValue
import com.processm.processminterpreter.infrastructure.persistence.neo4j.property.NestedAttributePathCodec
import com.processm.processminterpreter.domain.log.xes.XesEvent
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.log.xes.XesTrace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class Neo4jXesImportMapperTest {
    private val mapper = Neo4jXesImportMapper()

    @Test
    fun `maps XesLog to Neo4j import batch rows`() {
        val importedAt = LocalDateTime.parse("2026-04-24T09:00:00")
        val log = XesLog(
            conceptName = "Audit",
            traces = listOf(
                XesTrace(
                    conceptName = "Case 1",
                    events = listOf(
                        XesEvent(
                            conceptName = "A",
                            timeTimestamp = Instant.parse("2024-01-01T10:00:00Z"),
                            orgResource = "alice",
                        ),
                        XesEvent(
                            conceptName = "B",
                            timeTimestamp = Instant.parse("2024-01-01T11:00:00Z"),
                            orgResource = "bob",
                        ),
                    ),
                ),
            ),
        )

        val batch = mapper.toImportBatch(log, requestedLogId = "log-1", importedAt = importedAt)

        assertEquals("log-1", batch.logId)
        assertEquals("Audit", batch.logName)
        assertEquals(importedAt, batch.importedAt)
        assertEquals(1, batch.traceCount)
        assertEquals(2, batch.eventCount)
        assertEquals(1, batch.traceBatches.size)

        val traceBatch = batch.traceBatches.single()
        assertEquals(1, traceBatch.traces.size)
        assertEquals(2, traceBatch.events.size)
        assertEquals(1, traceBatch.follows.size)

        val trace = traceBatch.traces.single()
        assertEquals("log-1-trace-1-Case_1", trace["traceId"])
        assertEquals("Case 1", trace["caseId"])
        assertEquals(importedAt, trace["createdAt"])
        assertNotNull(trace["attributes"])

        val firstEvent = traceBatch.events.first()
        assertEquals("log-1-trace-1-Case_1-event-1", firstEvent["eventId"])
        assertEquals("A", firstEvent["activity"])
        assertEquals("alice", firstEvent["resource"])
        assertEquals(LocalDateTime.parse("2024-01-01T10:00:00"), firstEvent["timestamp"])

        assertEquals(
            mapOf(
                "fromEventId" to "log-1-trace-1-Case_1-event-1",
                "toEventId" to "log-1-trace-1-Case_1-event-2",
            ),
            traceBatch.follows.single(),
        )
    }

    @Test
    fun `does not fabricate missing standard XES attributes`() {
        val importedAt = LocalDateTime.parse("2026-04-24T09:00:00")
        val log = XesLog(
            conceptName = "Sparse",
            traces = listOf(
                XesTrace(
                    events = listOf(XesEvent(customAttributes = mapOf("payload" to "x"))),
                ),
            ),
        )

        val batch = mapper.toImportBatch(log, requestedLogId = "log-1", importedAt = importedAt)
        val trace = batch.traceBatches.single().traces.single()
        val event = batch.traceBatches.single().events.single()

        assertEquals(null, trace["caseId"])
        assertEquals(null, event["activity"])
        assertEquals(null, event["timestamp"])
    }

    @Test
    fun `stores log identity id separately from storage logId`() {
        val identityId = UUID.fromString("bbf3f64f-2507-4f0b-a6f8-0113377d69e4")
        val importedAt = LocalDateTime.parse("2026-04-24T09:00:00")
        val log = XesLog(identityId = identityId)

        val batch = mapper.toImportBatch(log, requestedLogId = "log-1", importedAt = importedAt)

        assertEquals("log-1", batch.logId)
        assertEquals(identityId.toString(), batch.logAttributes["identity:id"])
        assertEquals(false, batch.logAttributes.containsKey("logId"))
    }

    @Test
    fun `flattens nested XES attributes for query filtering while preserving parent value`() {
        val importedAt = LocalDateTime.parse("2026-04-24T09:00:00")
        val nestedAttribute = XesAttributeValue(
            value = 150291,
            children = mapOf("haptoglobine" to 23),
        )
        val log = XesLog(
            conceptName = "Hospital",
            customAttributes = mapOf("meta_concept:named_events_total" to nestedAttribute),
        )

        val batch = mapper.toImportBatch(log, requestedLogId = "log-1", importedAt = importedAt)
        val nestedKey = NestedAttributePathCodec.encodedChildKey("meta_concept:named_events_total", "haptoglobine")

        assertEquals(23, batch.logAttributes[nestedKey])
        assertNotNull(batch.logAttributes["meta_concept:named_events_total"])
    }

    @Test
    fun `follow rows preserve trace event order when timestamps are missing`() {
        val importedAt = LocalDateTime.parse("2026-04-24T09:00:00")
        val log = XesLog(
            conceptName = "Untimed",
            traces = listOf(
                XesTrace(
                    conceptName = "Case 1",
                    events = listOf(
                        XesEvent(conceptName = "A"),
                        XesEvent(conceptName = "B"),
                        XesEvent(conceptName = "C"),
                    ),
                ),
            ),
        )

        val follows = mapper.toImportBatch(log, requestedLogId = "log-1", importedAt = importedAt)
            .traceBatches.single()
            .follows

        assertEquals(
            listOf(
                mapOf("fromEventId" to "log-1-trace-1-Case_1-event-1", "toEventId" to "log-1-trace-1-Case_1-event-2"),
                mapOf("fromEventId" to "log-1-trace-1-Case_1-event-2", "toEventId" to "log-1-trace-1-Case_1-event-3"),
            ),
            follows,
        )
    }

    @Test
    fun `follow rows use trace event order as timestamp tie breaker`() {
        val importedAt = LocalDateTime.parse("2026-04-24T09:00:00")
        val sameTimestamp = Instant.parse("2024-01-01T10:00:00Z")
        val log = XesLog(
            conceptName = "Ties",
            traces = listOf(
                XesTrace(
                    conceptName = "Case 1",
                    events = listOf(
                        XesEvent(conceptName = "A", timeTimestamp = sameTimestamp),
                        XesEvent(conceptName = "B", timeTimestamp = sameTimestamp),
                        XesEvent(conceptName = "C", timeTimestamp = sameTimestamp),
                    ),
                ),
            ),
        )

        val follows = mapper.toImportBatch(log, requestedLogId = "log-1", importedAt = importedAt)
            .traceBatches.single()
            .follows

        assertEquals(
            listOf(
                mapOf("fromEventId" to "log-1-trace-1-Case_1-event-1", "toEventId" to "log-1-trace-1-Case_1-event-2"),
                mapOf("fromEventId" to "log-1-trace-1-Case_1-event-2", "toEventId" to "log-1-trace-1-Case_1-event-3"),
            ),
            follows,
        )
    }
}
