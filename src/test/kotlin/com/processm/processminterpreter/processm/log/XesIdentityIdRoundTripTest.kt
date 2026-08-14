package com.processm.processminterpreter.processm.log

import com.processm.processminterpreter.TestcontainersConfiguration
import com.processm.processminterpreter.pql.ExecutePqlQueryRequest
import com.processm.processminterpreter.pql.PqlQueryService
import com.processm.processminterpreter.xes.io.OpenXesReader
import com.processm.processminterpreter.xes.io.OpenXesWriter
import com.processm.processminterpreter.xes.io.XESLoader
import com.processm.processminterpreter.xes.io.XesWriteOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: the source `identity:id` of traces and events used to be mapped
 * onto the generated `traceId`/`eventId` storage keys, so the importer dropped
 * it to protect those keys and no export could reconstruct the UUID. The log
 * scope was unaffected.
 *
 * Also guards custom XES attributes whose names collide with generated or
 * standard Neo4j properties. Covers XES -> parser -> Neo4j -> read -> XES
 * export, plus PQL projection and filtering.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class XesIdentityIdRoundTripTest {
    @Autowired
    private lateinit var loader: XESLoader

    @Autowired
    private lateinit var executeQuery: PqlQueryService

    @Autowired
    private lateinit var writer: OpenXesWriter

    @Autowired
    private lateinit var reader: OpenXesReader

    @Autowired
    private lateinit var driver: Driver

    @BeforeEach
    @AfterEach
    fun clearLog() {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    MATCH (log:Log {logId: ${'$'}logId})
                    OPTIONAL MATCH (log)-[:CONTAINS]->(trace:Trace)
                    OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)
                    DETACH DELETE event, trace, log
                    """.trimIndent(),
                    mapOf("logId" to LOG_ID),
                ).consume()
            }
        }
    }

    @Test
    fun `XES export preserves the source identity id of log, trace and event`() {
        importFixture()

        val result = executeQuery.execute(
            ExecutePqlQueryRequest(query = "limit l:1, t:10, e:20", logId = LOG_ID),
        )
        val log = result.logs.single()

        assertEquals(LOG_UUID, log.identityId, "log identity:id")
        assertEquals("custom log id", log.customAttributes["logId"])
        assertEquals("custom log name", log.customAttributes["name"])
        assertEquals("custom created at", log.customAttributes["createdAt"])

        val trace = log.traces.single()
        assertEquals(TRACE_UUID, trace.identityId, "trace identity:id")
        assertEquals("Case 1", trace.conceptName, "trace concept:name")
        assertEquals("custom trace id", trace.customAttributes["traceId"])
        assertEquals("custom case id", trace.customAttributes["caseId"])
        assertEquals(EVENT_UUIDS, trace.events.map { it.identityId }, "event identity:id")
        assertEquals(
            listOf("custom event id A", "custom event id B"),
            trace.events.map { it.customAttributes["eventId"] },
        )
        assertEquals(
            listOf("custom activity A", "custom activity B"),
            trace.events.map { it.customAttributes["activity"] },
        )

        // The exact custom values above prove that generated storage ids did not
        // replace them. Identity must be carried by the typed field only.
        assertFalse("identity:id" in trace.customAttributes, "trace identity:id should not be duplicated")
        trace.events.forEach { event ->
            assertFalse("identity:id" in event.customAttributes, "event identity:id should not be duplicated")
        }

        val exported = ByteArrayOutputStream()
        writer.write(result.logs, exported, XesWriteOptions(compress = false, logName = "Query Result Log"))
        val xml = exported.toString(Charsets.UTF_8)
        // XES types identity:id as a UUID, so it must be written as <id>, once.
        assertEquals(
            1,
            xml.occurrencesOf("""<id key="identity:id" value="$TRACE_UUID"/>"""),
            "exported XES should carry the trace identity:id exactly once, as an id attribute",
        )
        EVENT_UUIDS.forEach { uuid ->
            assertEquals(
                1,
                xml.occurrencesOf("""<id key="identity:id" value="$uuid"/>"""),
                "exported XES should carry the event identity:id $uuid exactly once, as an id attribute",
            )
        }
        assertFalse("$LOG_ID-trace" in xml, "exported XES should not leak generated storage ids")

        val reread = reader.read(ByteArrayInputStream(exported.toByteArray())).single()
        assertEquals(LOG_UUID, reread.identityId, "re-read log identity:id")
        assertEquals(TRACE_UUID, reread.traces.single().identityId, "re-read trace identity:id")
        assertEquals(
            EVENT_UUIDS,
            reread.traces.single().events.map { it.identityId },
            "re-read event identity:id",
        )
        assertEquals("custom log id", reread.customAttributes["logId"])
        assertEquals("custom log name", reread.customAttributes["name"])
        assertEquals("custom created at", reread.customAttributes["createdAt"])
        assertEquals("custom trace id", reread.traces.single().customAttributes["traceId"])
        assertEquals("custom case id", reread.traces.single().customAttributes["caseId"])
        assertEquals(
            listOf("custom event id A", "custom event id B"),
            reread.traces.single().events.map { it.customAttributes["eventId"] },
        )
        assertEquals(
            listOf("custom activity A", "custom activity B"),
            reread.traces.single().events.map { it.customAttributes["activity"] },
        )
    }

    @Test
    fun `select t id and e id project the source UUIDs`() {
        importFixture()

        val result = executeQuery.execute(
            ExecutePqlQueryRequest(query = "select t:id, e:id", logId = LOG_ID),
        )

        val trace = result.logs.single().traces.single()
        assertEquals(TRACE_UUID, trace.identityId, "projected t:id")
        assertEquals(EVENT_UUIDS, trace.events.map { it.identityId }, "projected e:id")
    }

    @Test
    fun `where on t id matches the source UUID`() {
        importFixture()

        val matching = executeQuery.execute(
            ExecutePqlQueryRequest(query = "where t:id = '$TRACE_UUID'", logId = LOG_ID),
        )
        assertEquals(1, matching.logs.single().traces.size, "trace filtered by its source identity:id")

        val nonMatching = executeQuery.execute(
            ExecutePqlQueryRequest(
                query = "where t:id = '00000000-0000-0000-0000-000000000000'",
                logId = LOG_ID,
            ),
        )
        assertTrue(
            nonMatching.logs.isEmpty() || nonMatching.logs.single().traces.isEmpty(),
            "unknown identity:id should match no trace",
        )
    }

    @Test
    fun `PQL can project and filter custom attributes colliding with physical columns`() {
        importFixture()

        val projected = executeQuery.execute(
            ExecutePqlQueryRequest(
                query = "select [l:createdAt], [t:caseId], [e:activity], [e:eventId]",
                logId = LOG_ID,
            ),
        )
        val log = projected.logs.single()
        val trace = log.traces.single()
        assertEquals("custom created at", log.customAttributes["createdAt"])
        assertEquals("custom case id", trace.customAttributes["caseId"])
        assertEquals(
            listOf("custom activity A", "custom activity B"),
            trace.events.map { it.customAttributes["activity"] },
        )
        assertEquals(
            listOf("custom event id A", "custom event id B"),
            trace.events.map { it.customAttributes["eventId"] },
        )

        val filtered = executeQuery.execute(
            ExecutePqlQueryRequest(query = "where [e:activity] = 'custom activity B'", logId = LOG_ID),
        )
        assertEquals(
            listOf("B"),
            filtered.logs.single().traces.single().events.map { it.conceptName },
        )
    }

    private fun String.occurrencesOf(needle: String): Int =
        windowed(needle.length).count { it == needle }

    private fun importFixture() {
        val importResult = loader.loadXESFile(FIXTURE.byteInputStream(), LOG_ID)
        assertTrue(importResult.success, importResult.error ?: importResult.message)
    }

    private companion object {
        const val LOG_ID = "identity-roundtrip-log"
        val LOG_UUID: UUID = UUID.fromString("bbf3f64f-2507-4f0b-a6f8-0113377d69e4")
        val TRACE_UUID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val EVENT_UUIDS: List<UUID> = listOf(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
        )

        val FIXTURE = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>
                <extension name="Identity" prefix="identity" uri="http://www.xes-standard.org/identity.xesext"/>
                <string key="concept:name" value="IdentityRoundTrip"/>
                <id key="identity:id" value="bbf3f64f-2507-4f0b-a6f8-0113377d69e4"/>
                <string key="logId" value="custom log id"/>
                <string key="name" value="custom log name"/>
                <string key="createdAt" value="custom created at"/>
                <trace>
                    <string key="concept:name" value="Case 1"/>
                    <id key="identity:id" value="11111111-1111-1111-1111-111111111111"/>
                    <string key="traceId" value="custom trace id"/>
                    <string key="caseId" value="custom case id"/>
                    <event>
                        <string key="concept:name" value="A"/>
                        <id key="identity:id" value="22222222-2222-2222-2222-222222222222"/>
                        <date key="time:timestamp" value="2020-01-01T00:00:00Z"/>
                        <string key="eventId" value="custom event id A"/>
                        <string key="activity" value="custom activity A"/>
                    </event>
                    <event>
                        <string key="concept:name" value="B"/>
                        <id key="identity:id" value="33333333-3333-3333-3333-333333333333"/>
                        <date key="time:timestamp" value="2020-01-01T01:00:00Z"/>
                        <string key="eventId" value="custom event id B"/>
                        <string key="activity" value="custom activity B"/>
                    </event>
                </trace>
            </log>
        """.trimIndent()
    }
}
