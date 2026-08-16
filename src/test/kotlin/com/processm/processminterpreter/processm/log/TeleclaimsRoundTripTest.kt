package com.processm.processminterpreter.processm.log

import com.processm.processminterpreter.TestcontainersConfiguration
import com.processm.processminterpreter.pql.ExecutePqlQueryRequest
import com.processm.processminterpreter.pql.PqlQueryService
import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.xes.io.XesWriteOptions
import com.processm.processminterpreter.xes.io.OpenXesWriter
import com.processm.processminterpreter.xes.io.XESLoader
import com.processm.processminterpreter.xes.io.OpenXesReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for supervisor feedback:
 * import teleclaims.xes.gz, retrieve it with `where l:name='teleclaims.mxml'`,
 * export as XES, and verify the exported log preserves the original XES model.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TeleclaimsRoundTripTest {
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
    fun clearTeleclaims() {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    MATCH (log:Log {logId: ${'$'}logId})
                    OPTIONAL MATCH (log)-[:CONTAINS]->(trace:Trace)
                    OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)
                    DETACH DELETE event, trace, log
                    """.trimIndent(),
                    mapOf("logId" to TELECLAIMS_LOG_ID),
                ).consume()
            }
        }
    }

    @Test
    fun `where by log name exported as XES preserves teleclaims log semantics`() {
        val original = readOriginalTeleclaims()
        assertEquals("teleclaims.mxml", original.conceptName)

        val importResult = loader.loadXESFromResource(TELECLAIMS_RESOURCE, TELECLAIMS_LOG_ID)
        assertTrue(importResult.success, importResult.error ?: importResult.message)

        val result = executeQuery.execute(
            ExecutePqlQueryRequest(
                query = "where l:name='teleclaims.mxml'",
                logId = null,
            ),
        )
        assertEquals(1, result.logs.size, "Query should find exactly the imported teleclaims log")

        val exported = ByteArrayOutputStream()
        writer.write(result.logs, exported, XesWriteOptions(compress = false, logName = "Query Result Log"))
        val reread = reader.read(ByteArrayInputStream(exported.toByteArray())).single()

        assertSemanticallyEqual(original, reread)
    }

    @Test
    fun `grouped event name query keeps PQL attributes and hides storage columns`() {
        val original = readOriginalTeleclaims()
        val originalEventNames = original.traces
            .flatMap { it.events }
            .mapNotNull { it.conceptName }
            .toSet()

        val importResult = loader.loadXESFromResource(TELECLAIMS_RESOURCE, TELECLAIMS_LOG_ID)
        assertTrue(importResult.success, importResult.error ?: importResult.message)

        val result = executeQuery.execute(
            ExecutePqlQueryRequest(
                query = "select e:name group by ^e:name order by count(t:name) desc",
                logId = TELECLAIMS_LOG_ID,
            ),
        )

        assertEquals(1, result.logs.size, "Grouped query should stay within the selected teleclaims log")

        val log = result.logs.single()
        assertNoTechnicalAttributes(log.customAttributes, "log")
        assertTrue(log.traces.isNotEmpty(), "Grouped query should produce trace groups")

        val returnedEvents = log.traces.flatMap { trace ->
            assertNoTechnicalAttributes(trace.customAttributes, "trace")
            trace.events
        }
        assertTrue(returnedEvents.isNotEmpty(), "Grouped query should return projected event names")

        returnedEvents.forEachIndexed { index, event ->
            val prefix = "event[$index]"
            assertTrue(!event.conceptName.isNullOrBlank(), "$prefix concept:name should carry e:name")
            assertTrue(event.conceptName != "Unknown Activity", "$prefix should not use fabricated activity name")
            assertTrue(event.conceptName in originalEventNames, "$prefix concept:name should come from original log")
            assertNoTechnicalAttributes(event.customAttributes, prefix)
            assertTrue("e_name" !in event.customAttributes, "$prefix should not expose e:name as e_name custom attribute")
        }

        val exported = ByteArrayOutputStream()
        writer.write(result.logs, exported, XesWriteOptions(compress = false, logName = "Query Result Log"))
        val xml = exported.toString(Charsets.UTF_8)
        assertTrue("Unknown Activity" !in xml, "XES export should not contain fabricated activity names")
        assertTrue("t_traceId" !in xml, "XES export should not contain storage trace ids")
        assertTrue("l_logId" !in xml, "XES export should not contain storage log ids")
        assertTrue("count_t_concept_name_" !in xml, "XES export should not contain ORDER BY helper aliases")
        assertTrue("e_name" !in xml, "XES export should not contain physical result aliases")
    }

    @Test
    fun `hierarchical limit query returns prefix of teleclaims hierarchy without changing attributes`() {
        val original = readOriginalTeleclaims()

        val importResult = loader.loadXESFromResource(TELECLAIMS_RESOURCE, TELECLAIMS_LOG_ID)
        assertTrue(importResult.success, importResult.error ?: importResult.message)

        val result = executeQuery.execute(
            ExecutePqlQueryRequest(
                query = "limit e:3, t:2, l:1",
                logId = TELECLAIMS_LOG_ID,
            ),
        )

        assertEquals(1, result.logs.size, "Limit query should return exactly one log")
        val expected = original.copy(
            traces = original.traces.take(2).map { trace ->
                trace.copy(events = trace.events.take(3))
            },
        )
        assertSemanticallyEqual(expected, result.logs.single())
    }

    private fun readOriginalTeleclaims(): XesLog {
        val resource = javaClass.classLoader.getResourceAsStream(TELECLAIMS_RESOURCE)
            ?: error("Missing test resource $TELECLAIMS_RESOURCE")
        return resource.use { input ->
            GZIPInputStream(input).use { gzipped ->
                reader.read(gzipped).single()
            }
        }
    }

    private fun assertSemanticallyEqual(expected: XesLog, actual: XesLog) {
        assertEquals(expected.conceptName, actual.conceptName, "log concept:name")
        assertEquals(expected.identityId, actual.identityId, "log identity:id")
        assertEquals(expected.lifecycleModel, actual.lifecycleModel, "log lifecycle:model")
        assertEquals(expected.classifiers, actual.classifiers, "classifiers")
        assertEquals(expected.extensions, actual.extensions, "extensions")
        assertEquals(expected.traceGlobals, actual.traceGlobals, "trace globals")
        assertEquals(expected.eventGlobals, actual.eventGlobals, "event globals")
        assertEquals(expected.customAttributes, actual.customAttributes, "log custom attributes")
        assertEquals(expected.traces.size, actual.traces.size, "trace count")

        expected.traces.zip(actual.traces).forEachIndexed { traceIndex, (expectedTrace, actualTrace) ->
            assertTraceEqual(traceIndex, expectedTrace, actualTrace)
        }
    }

    private fun assertTraceEqual(index: Int, expected: XesTrace, actual: XesTrace) {
        val prefix = "trace[$index]"
        assertEquals(expected.conceptName, actual.conceptName, "$prefix concept:name")
        assertEquals(expected.identityId, actual.identityId, "$prefix identity:id")
        assertEquals(expected.costCurrency, actual.costCurrency, "$prefix cost:currency")
        assertEquals(expected.costTotal, actual.costTotal, "$prefix cost:total")
        assertEquals(expected.customAttributes, actual.customAttributes, "$prefix custom attributes")
        assertEquals(expected.events.size, actual.events.size, "$prefix event count")

        expected.events.zip(actual.events).forEachIndexed { eventIndex, (expectedEvent, actualEvent) ->
            assertEventEqual("$prefix.event[$eventIndex]", expectedEvent, actualEvent)
        }
    }

    private fun assertEventEqual(prefix: String, expected: XesEvent, actual: XesEvent) {
        assertEquals(expected.conceptName, actual.conceptName, "$prefix concept:name")
        assertEquals(expected.conceptInstance, actual.conceptInstance, "$prefix concept:instance")
        assertEquals(expected.identityId, actual.identityId, "$prefix identity:id")
        assertEquals(expected.timeTimestamp, actual.timeTimestamp, "$prefix time:timestamp")
        assertEquals(expected.lifecycleTransition, actual.lifecycleTransition, "$prefix lifecycle:transition")
        assertEquals(expected.lifecycleState, actual.lifecycleState, "$prefix lifecycle:state")
        assertEquals(expected.orgResource, actual.orgResource, "$prefix org:resource")
        assertEquals(expected.orgRole, actual.orgRole, "$prefix org:role")
        assertEquals(expected.orgGroup, actual.orgGroup, "$prefix org:group")
        assertEquals(expected.costCurrency, actual.costCurrency, "$prefix cost:currency")
        assertEquals(expected.costTotal, actual.costTotal, "$prefix cost:total")
        assertEquals(expected.customAttributes, actual.customAttributes, "$prefix custom attributes")
    }

    private fun assertNoTechnicalAttributes(attributes: Map<String, Any?>, owner: String) {
        val forbiddenPrefixes = listOf("_", "l_", "t_", "e_")
        val forbiddenNames = setOf("logId", "traceId", "parentLogId", "eventId", "parentTraceId", "importOrder")
        val leaked = attributes.keys.filter { key ->
            key in forbiddenNames || forbiddenPrefixes.any { prefix -> key.startsWith(prefix) }
        }
        assertTrue(leaked.isEmpty(), "$owner should not expose technical attributes: $leaked")
    }

    private companion object {
        const val TELECLAIMS_RESOURCE = "logs/teleclaims.xes.gz"
        const val TELECLAIMS_LOG_ID = "teleclaims-roundtrip-test"
    }
}
