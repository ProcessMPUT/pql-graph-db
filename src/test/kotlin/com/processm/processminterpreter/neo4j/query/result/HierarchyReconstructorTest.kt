package com.processm.processminterpreter.neo4j.query.result

import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesCustomAttributeCodec
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.common.HierarchicalOffsets
import com.processm.processminterpreter.pql.cypher.ColumnAlias
import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.xes.model.XesAttributeValue
import com.processm.processminterpreter.neo4j.xes.metadata.XesLogMetadataCodec
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class HierarchyReconstructorTest {
    private val reconstructor = HierarchyReconstructor()

    @Test
    fun `single row produces one XesLog with one trace and one event`() {
        val rows = listOf(
            mapOf(
                "l_concept_name" to "log A",
                "t_concept_name" to "case 1",
                "e_concept_name" to "Register",
                "e_time_timestamp" to ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
            ),
        )
        val logs = reconstructor.reconstruct(
            rows = rows,
            columnAliases = mapOf(
                "l_concept_name" to ColumnAlias("l:concept:name", Scope.LOG),
                "t_concept_name" to ColumnAlias("t:concept:name", Scope.TRACE),
                "e_concept_name" to ColumnAlias("e:concept:name", Scope.EVENT),
                "e_time_timestamp" to ColumnAlias("e:time:timestamp", Scope.EVENT),
            ),
        )
        assertEquals(1, logs.size)
        assertEquals("log A", logs[0].conceptName)
        assertEquals(1, logs[0].traces.size)
        assertEquals("case 1", logs[0].traces[0].conceptName)
        assertEquals(1, logs[0].traces[0].events.size)
        assertEquals("Register", logs[0].traces[0].events[0].conceptName)
        assertEquals(
            Instant.parse("2023-01-01T00:00:00Z"),
            logs[0].traces[0].events[0].timeTimestamp,
        )
    }

    @Test
    fun `two rows with same log and trace merge into one trace with two events`() {
        val rows = listOf(
            mapOf("l_concept_name" to "L", "t_concept_name" to "T", "e_concept_name" to "A"),
            mapOf("l_concept_name" to "L", "t_concept_name" to "T", "e_concept_name" to "B"),
        )
        val logs = reconstructor.reconstruct(
            rows = rows,
            columnAliases = mapOf(
                "l_concept_name" to ColumnAlias("l:concept:name", Scope.LOG),
                "t_concept_name" to ColumnAlias("t:concept:name", Scope.TRACE),
                "e_concept_name" to ColumnAlias("e:concept:name", Scope.EVENT),
            ),
        )
        assertEquals(1, logs.size)
        assertEquals(1, logs[0].traces.size)
        assertEquals(2, logs[0].traces[0].events.size)
        assertEquals(listOf("A", "B"), logs[0].traces[0].events.map { it.conceptName })
    }

    @Test
    fun `node-shaped row may carry compact collected events`() {
        val rows = listOf(
            mapOf(
                "log" to mapOf("logId" to "L", "name" to "Log"),
                "trace" to mapOf("traceId" to "T", "caseId" to "Case"),
                "events" to listOf(
                    mapOf("eventId" to "E1", "activity" to "A"),
                    mapOf("eventId" to "E2", "activity" to "B"),
                ),
            ),
        )

        val logs = reconstructor.reconstruct(rows = rows, columnAliases = emptyMap())

        assertEquals(1, logs.size)
        assertEquals("Log", logs[0].conceptName)
        assertEquals(1, logs[0].traces.size)
        assertEquals("Case", logs[0].traces[0].conceptName)
        assertEquals(listOf("A", "B"), logs[0].traces[0].events.map { it.conceptName })
    }

    @Test
    fun `full hierarchy merges cold nested payload while ProcessM pairs keep scalar parents`() {
        val logNested = XesAttributeValue(value = 7, children = mapOf("child" to "log-secret"))
        val traceNested = XesAttributeValue(value = 8, children = mapOf("child" to "trace-secret"))
        val eventNested = XesAttributeValue(value = 9, children = mapOf("child" to "event-secret"))
        val fullLogProperties = mapOf(
            "logId" to "L",
            "name" to "Log",
            "outer" to 7,
            Neo4jXesSchema.NESTED_ATTRIBUTE_PAYLOAD_PROPERTY to
                XesLogMetadataCodec.serializeArbitrary(mapOf("outer" to logNested)),
        )

        val full = reconstructor.reconstruct(
            rows = listOf(
                mapOf(
                    "log" to fullLogProperties,
                    "trace" to mapOf(
                        "traceId" to "T",
                        "trace-outer" to 8,
                        Neo4jXesSchema.NESTED_ATTRIBUTE_PAYLOAD_PROPERTY to
                            XesLogMetadataCodec.serializeArbitrary(mapOf("trace-outer" to traceNested)),
                    ),
                    "event" to mapOf(
                        "eventId" to "E",
                        "event-outer" to 9,
                        Neo4jXesSchema.NESTED_ATTRIBUTE_PAYLOAD_PROPERTY to
                            XesLogMetadataCodec.serializeArbitrary(mapOf("event-outer" to eventNested)),
                    ),
                ),
            ),
            columnAliases = emptyMap(),
        ).single()
        assertEquals(logNested, full.customAttributes["outer"])
        assertEquals(traceNested, full.traces.single().customAttributes["trace-outer"])
        assertEquals(eventNested, full.traces.single().events.single().customAttributes["event-outer"])

        val compact = reconstructor.reconstruct(
            rows = listOf(
                mapOf(
                    "log" to listOf(
                        mapOf("key" to "logId", "value" to "L"),
                        mapOf("key" to "name", "value" to "Log"),
                        mapOf("key" to "outer", "value" to 7),
                    ),
                    "trace" to listOf(
                        mapOf("key" to "traceId", "value" to "T"),
                        mapOf("key" to "trace-outer", "value" to 8),
                    ),
                    "event" to listOf(
                        mapOf("key" to "eventId", "value" to "E"),
                        mapOf("key" to "event-outer", "value" to 9),
                    ),
                ),
            ),
            columnAliases = emptyMap(),
        ).single()
        assertEquals(7, compact.customAttributes["outer"])
        assertEquals(8, compact.traces.single().customAttributes["trace-outer"])
        assertEquals(9, compact.traces.single().events.single().customAttributes["event-outer"])
    }

    @Test
    fun `node-shaped rows decode colliding custom attributes at every XES scope`() {
        val rows = listOf(
            mapOf(
                "log" to mapOf(
                    "logId" to "L",
                    "name" to "Log",
                    StandardAttributeCatalog.LIFECYCLE_MODEL to "standard lifecycle",
                    Neo4jXesCustomAttributeCodec.physicalName(Scope.LOG, "name") to "custom log name",
                    Neo4jXesCustomAttributeCodec.physicalName(
                        Scope.LOG,
                        StandardAttributeCatalog.LIFECYCLE_MODEL,
                    ) to "custom lifecycle",
                ),
                "trace" to mapOf(
                    "traceId" to "T",
                    "caseId" to "Case",
                    Neo4jXesCustomAttributeCodec.physicalName(Scope.TRACE, "traceId") to "custom trace id",
                ),
                "events" to listOf(
                    mapOf(
                        "eventId" to "E",
                        "activity" to "A",
                        Neo4jXesCustomAttributeCodec.physicalName(Scope.EVENT, "activity") to "custom activity",
                    ),
                ),
            ),
        )

        val log = reconstructor.reconstruct(rows = rows, columnAliases = emptyMap()).single()

        assertEquals("Log", log.conceptName)
        assertEquals("standard lifecycle", log.lifecycleModel)
        assertEquals("custom log name", log.customAttributes["name"])
        assertEquals("custom lifecycle", log.customAttributes[StandardAttributeCatalog.LIFECYCLE_MODEL])
        assertEquals("Case", log.traces.single().conceptName)
        assertEquals("custom trace id", log.traces.single().customAttributes["traceId"])
        assertEquals("A", log.traces.single().events.single().conceptName)
        assertEquals("custom activity", log.traces.single().events.single().customAttributes["activity"])
    }

    @Test
    fun `node-shaped split log and trace rows attach traces without synthetic placeholder`() {
        val rows = listOf(
            mapOf(
                "_logKey" to "L",
                "log" to mapOf("logId" to "L", "name" to "Log"),
                "trace" to null,
            ),
            mapOf(
                "_logKey" to "L",
                "log" to null,
                "trace" to mapOf("traceId" to "T1", "caseId" to "Case 1"),
            ),
            mapOf(
                "_logKey" to "L",
                "log" to null,
                "trace" to mapOf("traceId" to "T2", "caseId" to "Case 2"),
            ),
        )

        val logs = reconstructor.reconstruct(rows = rows, columnAliases = emptyMap())

        assertEquals(1, logs.size)
        assertEquals("Log", logs[0].conceptName)
        assertEquals(listOf("Case 1", "Case 2"), logs[0].traces.map { it.conceptName })
        assertTrue(logs[0].traces.all { it.events.isEmpty() })
    }

    @Test
    fun `node-shaped split event rows attach to trace key without repeating trace payload`() {
        val rows = listOf(
            mapOf(
                "_logKey" to "L",
                "log" to mapOf("logId" to "L", "name" to "Log"),
                "trace" to null,
                "event" to null,
            ),
            mapOf(
                "_logKey" to "L",
                "_traceKey" to "T",
                "log" to null,
                "trace" to mapOf("traceId" to "T", "caseId" to "Case"),
                "event" to null,
            ),
            mapOf(
                "_logKey" to "L",
                "_traceKey" to "T",
                "log" to null,
                "trace" to null,
                "event" to mapOf("eventId" to "E1", "activity" to "A"),
            ),
            mapOf(
                "_logKey" to "L",
                "_traceKey" to "T",
                "log" to null,
                "trace" to null,
                "event" to mapOf("eventId" to "E2", "activity" to "B"),
            ),
        )

        val logs = reconstructor.reconstruct(rows = rows, columnAliases = emptyMap())

        assertEquals(1, logs.size)
        assertEquals("Log", logs.single().conceptName)
        assertEquals("Case", logs.single().traces.single().conceptName)
        assertEquals(listOf("A", "B"), logs.single().traces.single().events.map { it.conceptName })
    }

    @Test
    fun `node-shaped split rows restore source order after unordered streaming`() {
        val rows = listOf(
            mapOf(
                "_kind" to 2,
                "_logKey" to "L",
                "_traceKey" to "T2",
                "_traceOrder" to 2,
                "_eventOrder" to 1,
                "event" to mapOf("activity" to "D"),
            ),
            mapOf(
                "_kind" to 2,
                "_logKey" to "L",
                "_traceKey" to "T1",
                "_traceOrder" to 1,
                "_eventOrder" to 1,
                "event" to mapOf("activity" to "B"),
            ),
            mapOf(
                "_kind" to 1,
                "_logKey" to "L",
                "_traceKey" to "T2",
                "_traceOrder" to 2,
                "trace" to mapOf("traceId" to "T2", "caseId" to "Case 2"),
            ),
            mapOf(
                "_kind" to 0,
                "_logKey" to "L",
                "log" to mapOf("logId" to "L", "name" to "Log"),
            ),
            mapOf(
                "_kind" to 2,
                "_logKey" to "L",
                "_traceKey" to "T1",
                "_traceOrder" to 1,
                "_eventOrder" to 0,
                "event" to mapOf("activity" to "A"),
            ),
            mapOf(
                "_kind" to 1,
                "_logKey" to "L",
                "_traceKey" to "T1",
                "_traceOrder" to 1,
                "trace" to mapOf("traceId" to "T1", "caseId" to "Case 1"),
            ),
            mapOf(
                "_kind" to 2,
                "_logKey" to "L",
                "_traceKey" to "T2",
                "_traceOrder" to 2,
                "_eventOrder" to 0,
                "event" to mapOf("activity" to "C"),
            ),
        )

        val log = reconstructor.reconstruct(rows = rows, columnAliases = emptyMap()).single()

        assertEquals(listOf("Case 1", "Case 2"), log.traces.map { it.conceptName })
        assertEquals(listOf("A", "B"), log.traces[0].events.map { it.conceptName })
        assertEquals(listOf("C", "D"), log.traces[1].events.map { it.conceptName })
    }

    @Test
    fun `projected event rows ignore null placeholder count`() {
        val rows = listOf(
            mapOf(
                "_log_id_" to "L",
                "_trace_variant_" to "A|B",
                "_null_event_count_" to 2,
                "_grouped_event_value_" to "A",
            ),
            mapOf(
                "_log_id_" to "L",
                "_trace_variant_" to "A|B",
                "_null_event_count_" to 2,
                "_grouped_event_value_" to "B",
            ),
        )

        val logs = reconstructor.reconstruct(
            rows,
            mapOf(
                "_log_id_" to ColumnAlias("_log_id_", Scope.LOG, synthetic = true),
                "_trace_variant_" to ColumnAlias("_trace_variant_", Scope.TRACE, synthetic = true),
                "_null_event_count_" to ColumnAlias("_null_event_count_", Scope.TRACE, synthetic = true),
                "_grouped_event_value_" to ColumnAlias("event:concept:name", Scope.EVENT),
            ),
        )

        val trace = logs.single().traces.single()
        assertEquals(listOf("A", "B"), trace.events.map { it.conceptName })
        assertEquals(0, trace.nullEventCount)
    }

    @Test
    fun `synthetic event column materializes empty event placeholders without leaking row attributes`() {
        val rows = listOf(
            mapOf(
                "_log_id_" to "L",
                "_trace_id_" to "T",
                "_trace_order_" to 0,
                "avg_total" to 1.05,
                "event" to emptyMap<String, Any?>(),
            ),
            mapOf(
                "_log_id_" to "L",
                "_trace_id_" to "T",
                "_trace_order_" to 0,
                "avg_total" to 1.05,
                "event" to emptyMap<String, Any?>(),
            ),
        )

        val logs = reconstructor.reconstruct(
            rows,
            mapOf(
                "_log_id_" to ColumnAlias("_log_id_", Scope.LOG, synthetic = true),
                "_trace_id_" to ColumnAlias("_trace_id_", Scope.TRACE, synthetic = true),
                "_trace_order_" to ColumnAlias("_trace_order_", Scope.TRACE, synthetic = true),
                "avg_total" to ColumnAlias("avg(^^event:cost:total)", Scope.LOG),
                "event" to ColumnAlias("event", Scope.EVENT, synthetic = true),
            ),
        )

        val trace = logs.single().traces.single()
        assertEquals(2, trace.events.size)
        assertTrue(trace.events.all { it.customAttributes.isEmpty() })
        assertEquals(0, trace.nullEventCount)
    }

    @Test
    fun `different trace values produce separate traces under the same log`() {
        val rows = listOf(
            mapOf("l_concept_name" to "L", "t_concept_name" to "T1", "e_concept_name" to "A"),
            mapOf("l_concept_name" to "L", "t_concept_name" to "T2", "e_concept_name" to "B"),
        )
        val logs = reconstructor.reconstruct(
            rows,
            mapOf(
                "l_concept_name" to ColumnAlias("l:concept:name", Scope.LOG),
                "t_concept_name" to ColumnAlias("t:concept:name", Scope.TRACE),
                "e_concept_name" to ColumnAlias("e:concept:name", Scope.EVENT),
            ),
        )
        assertEquals(1, logs.size)
        assertEquals(2, logs[0].traces.size)
        assertEquals(listOf("T1", "T2"), logs[0].traces.map { it.conceptName })
    }

    @Test
    fun `custom event attributes go into customAttributes map`() {
        val rows = listOf(
            mapOf(
                "l_concept_name" to "L",
                "t_concept_name" to "T",
                "e_concept_name" to "A",
                "e_priority" to "HIGH",
            ),
        )
        val logs = reconstructor.reconstruct(
            rows,
            mapOf(
                "l_concept_name" to ColumnAlias("l:concept:name", Scope.LOG),
                "t_concept_name" to ColumnAlias("t:concept:name", Scope.TRACE),
                "e_concept_name" to ColumnAlias("e:concept:name", Scope.EVENT),
                "e_priority" to ColumnAlias("e:priority", Scope.EVENT),
            ),
        )
        val event = logs[0].traces[0].events[0]
        assertEquals("HIGH", event.customAttributes["priority"])
    }

    @Test
    fun `projected custom attributes keep original key without scope prefix`() {
        val rows = listOf(
            mapOf(
                "_log_id_" to "L",
                "_trace_id_" to "T",
                "t_Specialism_code" to "CARD",
                "e_call_centre" to "Brisbane",
            ),
        )

        val logs = reconstructor.reconstruct(
            rows,
            mapOf(
                "_log_id_" to ColumnAlias("_log_id_", Scope.LOG, synthetic = true),
                "_trace_id_" to ColumnAlias("_trace_id_", Scope.TRACE, synthetic = true),
                "t_Specialism_code" to ColumnAlias("trace:Specialism code", Scope.TRACE),
                "e_call_centre" to ColumnAlias("event:call centre", Scope.EVENT),
            ),
        )

        val trace = logs.single().traces.single()
        assertEquals("CARD", trace.customAttributes["Specialism code"])
        assertNull(trace.customAttributes["trace:Specialism code"])
        val event = trace.events.single()
        assertEquals("Brisbane", event.customAttributes["call centre"])
        assertNull(event.customAttributes["event:call centre"])
    }

    @Test
    fun `projected expression keys keep ProcessM scope prefixes`() {
        val rows = listOf(
            mapOf(
                "_log_id_" to "L",
                "_trace_id_" to "T",
                "min_0" to "Hospital log",
                "sum_0" to null,
                "literal_0" to "2020-03-12T00:00:00Z",
            ),
        )

        val logs = reconstructor.reconstruct(
            rows,
            mapOf(
                "_log_id_" to ColumnAlias("_log_id_", Scope.LOG, synthetic = true),
                "_trace_id_" to ColumnAlias("_trace_id_", Scope.TRACE, synthetic = true),
                "min_0" to ColumnAlias("trace:min(log:concept:name)", Scope.TRACE),
                "sum_0" to ColumnAlias(
                    pqlExpression = "event:cost:total + trace:cost:total",
                    scope = Scope.EVENT,
                    materializeNull = true,
                ),
                "literal_0" to ColumnAlias("log:D2020-03-12T00:00:00Z", Scope.LOG),
            ),
        )

        val log = logs.single()
        assertEquals("2020-03-12T00:00:00Z", log.customAttributes["log:D2020-03-12T00:00:00Z"])
        val trace = log.traces.single()
        assertEquals("Hospital log", trace.customAttributes["trace:min(log:concept:name)"])
        val event = trace.events.single()
        assertTrue(event.customAttributes.containsKey("event:cost:total + trace:cost:total"))
    }

    @Test
    fun `aggregation-only query produces synthetic single-log single-trace wrapper`() {
        // No LOG/TRACE aliases, just an aggregate column. The reconstructor keys the
        // aggregation value by its PQL surface text (`count(event:concept:name)`) —
        // matches ProcessM's attribute-key convention.
        val rows = listOf(mapOf("cnt" to 42L))
        val logs = reconstructor.reconstruct(
            rows,
            mapOf("cnt" to ColumnAlias("count(event:concept:name)", Scope.EVENT)),
        )
        assertEquals(1, logs.size)
        assertEquals(1, logs[0].traces.size)
        assertEquals(1, logs[0].traces[0].events.size)
        assertEquals(
            42L,
            logs[0].traces[0].events[0].customAttributes["count(event:concept:name)"],
        )
    }

    @Test
    fun `node-shaped event wildcard uses log and trace only as hierarchy containers`() {
        val rows =
            listOf(
                mapOf(
                    "log" to mapOf("logId" to "log-1", "concept:name" to "L"),
                    "trace" to mapOf("traceId" to "trace-1", "concept:name" to "T"),
                    "event" to mapOf("eventId" to "event-1", "activity" to "A"),
                ),
            )

        val logs =
            reconstructor.reconstruct(
                rows = rows,
                columnAliases = emptyMap(),
                selectAllScopes = setOf(Scope.EVENT),
            )

        assertNull(logs.single().conceptName)
        assertNull(logs.single().traces.single().conceptName)
        assertEquals("A", logs.single().traces.single().events.single().conceptName)
    }

    @Test
    fun `projected rows retain hidden log metadata without selecting log attributes`() {
        val rows =
            listOf(
                mapOf(
                    "_log_meta_" to mapOf("extensions" to """[{"name":"Concept","prefix":"concept","uri":"uri"}]"""),
                    "_log_id_" to "log-1",
                    "_trace_id_" to "trace-1",
                    "event" to mapOf("eventId" to "event-1", "activity" to "A"),
                ),
            )

        val logs =
            reconstructor.reconstruct(
                rows = rows,
                columnAliases =
                    mapOf(
                        "_log_id_" to ColumnAlias("_log_id_", Scope.LOG, synthetic = true),
                        "_trace_id_" to ColumnAlias("_trace_id_", Scope.TRACE, synthetic = true),
                    ),
                selectAllScopes = setOf(Scope.EVENT),
            )

        val log = logs.single()
        assertNull(log.conceptName)
        assertEquals("Concept", log.extensions.single().name)
    }

    @Test
    fun `projected rows can retain hidden full log node for implicit no-select shapes`() {
        val rows =
            listOf(
                mapOf(
                    "_log_id_" to "log-1",
                    "_log_node_" to mapOf("logId" to "log-1", "meta_3TU:language" to "eng"),
                    "_trace_variant_" to "|A",
                    "_grouped_event_value_" to "A",
                ),
            )

        val logs =
            reconstructor.reconstruct(
                rows = rows,
                columnAliases =
                    mapOf(
                        "_log_id_" to ColumnAlias("_log_id_", Scope.LOG, synthetic = true),
                        "_trace_variant_" to ColumnAlias("_trace_variant_", Scope.TRACE, synthetic = true),
                        "_grouped_event_value_" to ColumnAlias("event:concept:name", Scope.EVENT),
                    ),
            )

        val log = logs.single()
        assertEquals("eng", log.customAttributes["meta_3TU:language"])
        assertNull(log.customAttributes["logId"], "technical log id must not leak as a custom XES attribute")
    }

    @Test
    fun `projected event wildcard does not materialize technical row columns as event attributes`() {
        val rows =
            listOf(
                mapOf(
                    "_log_id_" to "log-1",
                    "_trace_id_" to "trace-1",
                    "t_concept_name" to "T",
                    "event" to mapOf(
                        "eventId" to "event-1",
                        "activity" to "A",
                        "importOrder" to 0,
                        "processmEventOrder" to 3,
                    ),
                ),
            )

        val logs =
            reconstructor.reconstruct(
                rows = rows,
                columnAliases = mapOf(
                    "_log_id_" to ColumnAlias("_log_id_", Scope.LOG, synthetic = true),
                    "_trace_id_" to ColumnAlias("_trace_id_", Scope.TRACE, synthetic = true),
                    "t_concept_name" to ColumnAlias("t:concept:name", Scope.TRACE),
                ),
                selectAllScopes = setOf(Scope.EVENT),
            )

        val event = logs.single().traces.single().events.single()
        assertEquals("A", event.conceptName)
        assertTrue(event.customAttributes.isEmpty(), "technical columns leaked into event attributes: ${event.customAttributes}")
    }

    @Test
    fun `null values do not overwrite previously-seen non-null log attributes`() {
        val rows = listOf(
            mapOf("l_concept_name" to "L", "t_concept_name" to "T", "e_concept_name" to "A"),
            mapOf("l_concept_name" to null, "t_concept_name" to "T", "e_concept_name" to "B"),
        )
        val logs = reconstructor.reconstruct(
            rows,
            mapOf(
                "l_concept_name" to ColumnAlias("l:concept:name", Scope.LOG),
                "t_concept_name" to ColumnAlias("t:concept:name", Scope.TRACE),
                "e_concept_name" to ColumnAlias("e:concept:name", Scope.EVENT),
            ),
        )
        // The null value matches the existing log key (via "missing" semantics) rather
        // than forking a new log. For now we simply assert the earlier log still has
        // its concept name — whether the second row merges or not is a product of the
        // key logic and that's what the test pins down.
        assertTrue(logs.any { it.conceptName == "L" }, "expected log with conceptName L: $logs")
        val firstLog = logs.first { it.conceptName == "L" }
        assertEquals("L", firstLog.conceptName)
        assertNull(
            firstLog.traces.firstOrNull()?.events?.firstOrNull()?.orgResource,
            "no resource aliased, so event.orgResource should remain null",
        )
    }

    // ---------- Hierarchical LIMIT / OFFSET ----------

    /** Helper: build `logCount * traceCount * eventCount` rows of a complete hierarchy. */
    private fun buildHierarchyRows(
        logCount: Int,
        traceCount: Int,
        eventCount: Int,
    ): List<Map<String, Any?>> = buildList {
        for (l in 0 until logCount) {
            for (t in 0 until traceCount) {
                for (e in 0 until eventCount) {
                    add(
                        mapOf(
                            "l_concept_name" to "L$l",
                            "t_concept_name" to "T$l-$t",
                            "e_concept_name" to "E$l-$t-$e",
                        ),
                    )
                }
            }
        }
    }

    private val hierarchyAliases = mapOf(
        "l_concept_name" to ColumnAlias("l:concept:name", Scope.LOG),
        "t_concept_name" to ColumnAlias("t:concept:name", Scope.TRACE),
        "e_concept_name" to ColumnAlias("e:concept:name", Scope.EVENT),
    )

    @Test
    fun `hierarchical limit l=1 keeps only the first log`() {
        val rows = buildHierarchyRows(logCount = 3, traceCount = 2, eventCount = 2)
        val logs = reconstructor.reconstruct(
            rows = rows,
            columnAliases = hierarchyAliases,
            limits = HierarchicalLimits(log = 1),
        )
        assertEquals(1, logs.size)
        assertEquals("L0", logs[0].conceptName)
    }

    @Test
    fun `hierarchical limit t=2 keeps 2 traces per log, all events`() {
        val rows = buildHierarchyRows(logCount = 2, traceCount = 4, eventCount = 3)
        val logs = reconstructor.reconstruct(
            rows = rows,
            columnAliases = hierarchyAliases,
            limits = HierarchicalLimits(trace = 2),
        )
        assertEquals(2, logs.size)
        for (log in logs) {
            assertEquals(2, log.traces.size)
            for (trace in log.traces) assertEquals(3, trace.events.size)
        }
    }

    @Test
    fun `hierarchical limit e=1 keeps first event only in each trace`() {
        val rows = buildHierarchyRows(logCount = 1, traceCount = 3, eventCount = 4)
        val logs = reconstructor.reconstruct(
            rows = rows,
            columnAliases = hierarchyAliases,
            limits = HierarchicalLimits(event = 1),
        )
        assertEquals(1, logs.size)
        assertEquals(3, logs[0].traces.size)
        for ((tIdx, trace) in logs[0].traces.withIndex()) {
            assertEquals(1, trace.events.size)
            assertEquals("E0-$tIdx-0", trace.events[0].conceptName)
        }
    }

    @Test
    fun `hierarchical offset skips logs traces events at each scope`() {
        val rows = buildHierarchyRows(logCount = 3, traceCount = 3, eventCount = 3)
        val logs = reconstructor.reconstruct(
            rows = rows,
            columnAliases = hierarchyAliases,
            offsets = HierarchicalOffsets(log = 1, trace = 1, event = 1),
        )
        // log offset 1 → keep L1, L2
        assertEquals(listOf("L1", "L2"), logs.map { it.conceptName })
        for (log in logs) {
            // trace offset 1 of 3 → 2 remain
            assertEquals(2, log.traces.size)
            for (trace in log.traces) {
                // event offset 1 of 3 → 2 remain
                assertEquals(2, trace.events.size)
            }
        }
    }

    @Test
    fun `default limits apply when plan has no explicit trace limit`() {
        val rows = buildHierarchyRows(logCount = 1, traceCount = 10, eventCount = 1)
        val logs = reconstructor.reconstruct(
            rows = rows,
            columnAliases = hierarchyAliases,
            defaultLimits = HierarchicalLimits(trace = 3),
        )
        assertEquals(1, logs.size)
        assertEquals(3, logs[0].traces.size)
    }

    @Test
    fun `explicit trace limit below default limit narrows traces`() {
        val rows = buildHierarchyRows(logCount = 1, traceCount = 10, eventCount = 1)
        val logs = reconstructor.reconstruct(
            rows = rows,
            columnAliases = hierarchyAliases,
            limits = HierarchicalLimits(trace = 2),
            defaultLimits = HierarchicalLimits(trace = 7),
        )
        assertEquals(2, logs[0].traces.size)
    }

    @Test
    fun `default trace limit caps explicit trace limit above ProcessM REST cap`() {
        val rows = buildHierarchyRows(logCount = 1, traceCount = 10, eventCount = 1)
        val logs = reconstructor.reconstruct(
            rows = rows,
            columnAliases = hierarchyAliases,
            limits = HierarchicalLimits(trace = 7),
            defaultLimits = HierarchicalLimits(trace = 3),
        )
        assertEquals(3, logs[0].traces.size)
    }

    @Test
    fun `default event limit caps null event placeholders`() {
        val logs = reconstructor.reconstruct(
            rows = listOf(
                mapOf(
                    "_log_id_" to "L1",
                    "_null_event_count_" to 185,
                    "min_0" to "Sepsis Cases - Event Log",
                ),
            ),
            columnAliases = mapOf(
                "_log_id_" to ColumnAlias("_log_id_", Scope.LOG, synthetic = true),
                "_null_event_count_" to ColumnAlias("_null_event_count_", Scope.TRACE, synthetic = true),
                "min_0" to ColumnAlias("trace:min(log:concept:name)", Scope.TRACE),
            ),
            defaultLimits = HierarchicalLimits(event = 90),
        )

        assertEquals(90, logs.single().traces.single().nullEventCount)
    }

    @Test
    fun `offset past end yields empty slice at that scope`() {
        val rows = buildHierarchyRows(logCount = 2, traceCount = 2, eventCount = 2)
        val logs = reconstructor.reconstruct(
            rows = rows,
            columnAliases = hierarchyAliases,
            offsets = HierarchicalOffsets(log = 99),
        )
        assertTrue(logs.isEmpty(), "log offset past end should drop all logs: $logs")
    }

    @Test
    fun `windowing reuses an already bounded hierarchy without copying it`() {
        val events = listOf(XesEvent(conceptName = "A"))
        val trace = XesTrace(conceptName = "T", events = events)
        val traces = listOf(trace)
        val log = XesLog(conceptName = "L", traces = traces)
        val logs = listOf(log)

        val result = HierarchicalWindowing.apply(
            logs = logs,
            limits = HierarchicalLimits(),
            offsets = HierarchicalOffsets(),
            defaultLimits = HierarchicalLimits(log = 10, trace = 30, event = 90),
        )

        assertSame(logs, result)
        assertSame(log, result.single())
        assertSame(traces, result.single().traces)
        assertSame(trace, result.single().traces.single())
        assertSame(events, result.single().traces.single().events)
    }

    @Test
    fun `huge offset is clamped instead of wrapping to zero`() {
        val logs = listOf(XesLog(conceptName = "L"))

        val result = HierarchicalWindowing.apply(
            logs = logs,
            limits = HierarchicalLimits(),
            offsets = HierarchicalOffsets(log = Long.MAX_VALUE),
            defaultLimits = HierarchicalLimits(),
        )

        assertTrue(result.isEmpty())
    }
}
