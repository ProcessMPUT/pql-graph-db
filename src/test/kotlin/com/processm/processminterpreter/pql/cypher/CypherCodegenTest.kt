package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.xes.DataStoreLogSummary
import com.processm.processminterpreter.xes.DataStoreRepository
import com.processm.processminterpreter.xes.LogRepository
import com.processm.processminterpreter.xes.LogStatistics
import com.processm.processminterpreter.pql.PqlCompiler
import com.processm.processminterpreter.pql.XesAttributeReadMode
import com.processm.processminterpreter.xes.datastore.DataStore
import com.processm.processminterpreter.xes.model.Log
import com.processm.processminterpreter.neo4j.property.NestedAttributePathCodec
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesCustomAttributeCodec
import com.processm.processminterpreter.pql.catalog.BinaryOperator
import com.processm.processminterpreter.pql.catalog.OrderDirection
import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.catalog.Type
import com.processm.processminterpreter.pql.plan.CandidateLogPlan
import com.processm.processminterpreter.pql.plan.GroupBySpec
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.common.HierarchicalOffsets
import com.processm.processminterpreter.pql.plan.LogicalPlan
import com.processm.processminterpreter.pql.plan.LogicalSource
import com.processm.processminterpreter.pql.ast.PqlQuery
import com.processm.processminterpreter.pql.plan.ProjectedColumn
import com.processm.processminterpreter.pql.plan.Projection
import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.pql.parser.AntlrPqlParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

class CypherCodegenTest {
    private val loc = SourceLocation(1, 0)
    private val codegen = CypherCodegen(PhysicalAttributeMapper())

    @Test
    fun `bracketed log id reads the custom property in projection filtering and ordering`() {
        // ProcessM Attribute.kt treats bracketed names as custom attributes.
        // LOCAL's unbracketed l:logId extension continues to address its storage id.
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        for ((reference, kind) in listOf("[l:logId]" to AttributeKind.CUSTOM, "l:logId" to AttributeKind.SYSTEM)) {
            val plan = compiler.compile(
                "select $reference where $reference = 'selected' order by $reference",
                logId = "physical-log-id",
            ) as LogicalPlan.Select
            val column = plan.projection.columns.single()
            assertEquals(kind, (column.expression as PqlExpression.Attribute).kind)
            assertEquals(kind, ((plan.filter as PqlExpression.Binary).left as PqlExpression.Attribute).kind)
            assertEquals(kind, (plan.orderBy.single().expression as PqlExpression.Attribute).kind)

            val property = if (kind == AttributeKind.CUSTOM) {
                Neo4jXesCustomAttributeCodec.physicalName(Scope.LOG, "logId")
            } else {
                "logId"
            }
            val query = codegen.generate(plan)
            assertTrue(query.cypher.contains("log.$property AS ${column.alias}"), query.cypher)
            assertEquals("physical-log-id", query.parameters["logId"])
            assertTrue("selected" in query.parameters.values)
        }
    }

    @Test
    fun `ProcessM JSON log hydration excludes nested and internal properties before transfer`() {
        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                limits = HierarchicalLimits(log = 1, trace = 1, event = 1),
                logId = "log-1",
            ),
            XesAttributeReadMode.PROCESSM_JSON,
        )

        assertFalse(q.cypher.contains("properties(log) AS log"), q.cypher)
        assertFalse(q.cypher.contains("properties(trace)"), q.cypher)
        assertFalse(q.cypher.contains("properties(event)"), q.cypher)
        assertTrue(q.cypher.contains("NOT key STARTS WITH '\u001f'"), q.cypher)
        assertTrue(q.hydrateLogProperties)
        assertEquals(XesAttributeReadMode.PROCESSM_JSON, q.attributeReadMode)
    }

    private fun stdAttr(scope: Scope, xesName: String, type: Type = Type.STRING) = PqlExpression.Attribute(
        name = xesName, baseScope = scope, effectiveScope = scope,
        kind = AttributeKind.STANDARD, xesStandardName = xesName,
        wasBracketed = false, type = type, location = loc,
    )

    private fun hoistedAttr(base: Scope, effective: Scope, xesName: String, type: Type = Type.STRING) =
        PqlExpression.Attribute(
            name = xesName, baseScope = base, effectiveScope = effective,
            kind = AttributeKind.STANDARD, xesStandardName = xesName,
            wasBracketed = false, type = type, location = loc,
        )

    private fun customAttr(scope: Scope, name: String, type: Type = Type.UNKNOWN) = PqlExpression.Attribute(
        name = name,
        baseScope = scope,
        effectiveScope = scope,
        kind = AttributeKind.CUSTOM,
        xesStandardName = null,
        wasBracketed = true,
        type = type,
        location = loc,
    )

    private fun selectPlanFull(
        columns: List<ProjectedColumn>,
        filter: com.processm.processminterpreter.pql.ast.PqlExpression? = null,
        groupBy: GroupBySpec? = null,
        orderBy: List<PqlQuery.OrderKey> = emptyList(),
        limits: HierarchicalLimits = HierarchicalLimits(),
        offsets: HierarchicalOffsets = HierarchicalOffsets(),
        materializedScopes: Set<Scope> = emptySet(),
        from: Scope = Scope.EVENT,
        used: Set<Scope> = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
        logId: String? = null,
        dataStoreId: String? = null,
    ) = LogicalPlan.Select(
        source = LogicalSource(fromScope = from, usedScopes = used, logId = logId, dataStoreId = dataStoreId),
        projection = Projection(columns = columns),
        filter = filter,
        groupBy = groupBy,
        orderBy = orderBy,
        limits = limits,
        offsets = offsets,
        materializedScopes = materializedScopes,
        location = loc,
    )

    private fun selectPlan(
        columns: List<ProjectedColumn>,
        filter: com.processm.processminterpreter.pql.ast.PqlExpression? = null,
        from: Scope = Scope.EVENT,
        used: Set<Scope> = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
        logId: String? = null,
        dataStoreId: String? = null,
    ) = LogicalPlan.Select(
        source = LogicalSource(fromScope = from, usedScopes = used, logId = logId, dataStoreId = dataStoreId),
        projection = Projection(columns = columns),
        filter = filter,
        location = loc,
    )

    @Test
    fun `selecting event name emits full MATCH chain and activity projection`() {
        val a = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            selectPlan(listOf(ProjectedColumn(a, alias = "concept_name", scope = Scope.EVENT))),
        )
        val c = q.cypher
        assertTrue(c.contains("MATCH (log:Log)"), c)
        assertTrue(c.contains("-[:CONTAINS]->(trace:Trace)"), c)
        assertTrue(c.contains("-[:HAS_EVENT]->(event:Event)"), c)
        assertTrue(c.contains("event.activity AS concept_name"), c)
    }

    @Test
    fun `WHERE equality binds RHS to a parameter and references left attribute`() {
        val a = stdAttr(Scope.EVENT, "concept:name")
        val filter = PqlExpression.Binary(
            op = BinaryOperator.EQ,
            left = a,
            right = PqlExpression.Literal(rawText = "Registration", kind = PqlExpression.LiteralKind.STRING, value = "Registration", type = Type.STRING, location = loc),
            type = Type.BOOLEAN, location = loc,
        )
        val q = codegen.generate(
            selectPlan(listOf(ProjectedColumn(a, alias = "concept_name", scope = Scope.EVENT)), filter = filter),
        )
        assertTrue(q.cypher.contains("WHERE event.activity = \$param0"), q.cypher)
        assertEquals("Registration", q.parameters["param0"])
    }

    @Test
    fun `simple LIKE literals use native Cypher string operators instead of regex`() {
        val name = stdAttr(Scope.EVENT, "concept:name")
        val cases = listOf(
            "%zzq%" to "event.activity CONTAINS \$param0",
            "prefix%" to "event.activity STARTS WITH \$param0",
            "%suffix" to "event.activity ENDS WITH \$param0",
            "exact" to "event.activity = \$param0",
        )

        cases.forEach { (pattern, expected) ->
            val filter = PqlExpression.Binary(
                BinaryOperator.LIKE,
                name,
                PqlExpression.Literal(
                    rawText = pattern,
                    kind = PqlExpression.LiteralKind.STRING,
                    value = pattern,
                    type = Type.STRING,
                    location = loc,
                ),
                Type.BOOLEAN,
                loc,
            )
            val query = codegen.generate(selectPlan(listOf(ProjectedColumn(name, "name", Scope.EVENT)), filter))

            assertTrue(query.cypher.contains(expected), query.cypher)
            assertTrue(!query.cypher.contains("=~"), query.cypher)
        }
    }

    @Test
    fun `datastore event name contains ranks and reads logs from indexed events`() {
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val timestamp = stdAttr(Scope.EVENT, "time:timestamp", Type.DATETIME)
        val filter = PqlExpression.Binary(
            op = BinaryOperator.LIKE,
            left = eventName,
            right = PqlExpression.Literal(
                rawText = "'%zzq%'",
                kind = PqlExpression.LiteralKind.STRING,
                value = "%zzq%",
                pqlText = "%zzq%",
                type = Type.STRING,
                location = loc,
            ),
            type = Type.BOOLEAN,
            location = loc,
        )

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                filter = filter,
                orderBy = listOf(
                    PqlQuery.OrderKey(eventName, OrderDirection.ASC),
                    PqlQuery.OrderKey(timestamp, OrderDirection.ASC),
                ),
                limits = HierarchicalLimits(log = 1, trace = 10, event = 20),
                dataStoreId = "store-42",
            ),
        )

        assertTrue(q.cypher.contains("MATCH (:DataStore {dataStoreId: \$dataStoreId})"), q.cypher)
        assertTrue(q.cypher.contains("MATCH (event:Event) WHERE event.activity CONTAINS"), q.cypher)
        assertTrue(q.cypher.contains("MATCH (trace:Trace)-[:HAS_EVENT]->(event)"), q.cypher)
        assertTrue(q.cypher.indexOf("MATCH (event:Event)") < q.cypher.indexOf("MATCH (trace:Trace)"), q.cypher)
        assertTrue(q.cypher.contains("event.activity AS _indexedLogOrder0"), q.cypher)
        assertTrue(q.cypher.contains("LIMIT \$logLimit CALL (log)"), q.cypher)
        assertTrue(
            q.cypher.contains(
                "ORDER BY trace.traceId, event.activity ASC, event.timestamp ASC, event.importOrder",
            ),
            q.cypher,
        )
        assertTrue(q.cypher.contains("collect(properties(event))[..\$eventLimit] AS events"), q.cypher)
        assertTrue(q.cypher.contains("LIMIT \$traceLimit"), q.cypher)
        assertTrue(q.hydrateLogProperties)
        assertEquals(2, q.parameters.values.count { it == "zzq" })
    }

    @Test
    fun `event name NOT LIKE keeps generic hierarchy plan`() {
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val filter = PqlExpression.Binary(
            op = BinaryOperator.NOT_LIKE,
            left = eventName,
            right = PqlExpression.Literal(
                rawText = "'%zzq%'",
                kind = PqlExpression.LiteralKind.STRING,
                value = "%zzq%",
                pqlText = "%zzq%",
                type = Type.STRING,
                location = loc,
            ),
            type = Type.BOOLEAN,
            location = loc,
        )

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                filter = filter,
                limits = HierarchicalLimits(log = 1, trace = 10, event = 20),
                logId = "Road",
            ),
        )

        assertFalse(q.cypher.contains("MATCH (event:Event) WHERE event.activity CONTAINS"), q.cypher)
        assertTrue(q.cypher.contains("MATCH (trace:Trace {parentLogId: log.logId})"), q.cypher)
    }

    @Test
    fun `complex LIKE patterns retain regex fallback and escaping semantics`() {
        val name = stdAttr(Scope.EVENT, "concept:name")
        val patterns = listOf("a%b%c", "under_score", "a\\\\%b%c")

        patterns.forEach { pattern ->
            val filter = PqlExpression.Binary(
                BinaryOperator.LIKE,
                name,
                PqlExpression.Literal(
                    rawText = pattern,
                    kind = PqlExpression.LiteralKind.STRING,
                    value = pattern,
                    type = Type.STRING,
                    location = loc,
                ),
                Type.BOOLEAN,
                loc,
            )
            val query = codegen.generate(selectPlan(listOf(ProjectedColumn(name, "name", Scope.EVENT)), filter))

            assertTrue(query.cypher.contains("event.activity =~ \$param0"), query.cypher)
        }
    }

    @Test
    fun `NOT LIKE preserves null-aware negation when lowered to CONTAINS`() {
        val name = stdAttr(Scope.EVENT, "concept:name")
        val pattern = "%blocked%"
        val filter = PqlExpression.Binary(
            BinaryOperator.NOT_LIKE,
            name,
            PqlExpression.Literal(
                rawText = pattern,
                kind = PqlExpression.LiteralKind.STRING,
                value = pattern,
                type = Type.STRING,
                location = loc,
            ),
            Type.BOOLEAN,
            loc,
        )

        val query = codegen.generate(selectPlan(listOf(ProjectedColumn(name, "name", Scope.EVENT)), filter))

        assertTrue(query.cypher.contains("NOT (event.activity CONTAINS \$param0)"), query.cypher)
        assertEquals("blocked", query.parameters["param0"])
    }

    @Test
    fun `nested XES attribute equality compares string representations`() {
        val nested = customAttr(
            Scope.LOG,
            NestedAttributePathCodec.encodedChildKey("meta_org:group_events_average", "Maternity ward"),
        )
        val filter = PqlExpression.Binary(
            op = BinaryOperator.EQ,
            left = nested,
            right = PqlExpression.Literal(rawText = "0.016", kind = PqlExpression.LiteralKind.STRING, value = "0.016", type = Type.STRING, location = loc),
            type = Type.BOOLEAN,
            location = loc,
        )

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                filter = filter,
                limits = HierarchicalLimits(event = 1, trace = 1, log = 1),
                logId = "Hospital-test",
            ),
        )

        assertTrue(q.cypher.contains("toString(log.`${nested.name}`) = toString(\$param0)"), q.cypher)
        assertEquals("0.016", q.parameters["param0"])
    }

    @Test
    fun `logId source binds logId parameter and narrows MATCH`() {
        val a = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            selectPlan(
                listOf(ProjectedColumn(a, alias = "concept_name", scope = Scope.EVENT)),
                logId = "log-42",
            ),
        )
        assertTrue(q.cypher.contains("(log:Log {logId: \$logId})"), q.cypher)
        assertEquals("log-42", q.parameters["logId"])
    }

    @Test
    fun `dataStoreId source narrows MATCH through data store relationship`() {
        val a = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            selectPlan(
                listOf(ProjectedColumn(a, alias = "concept_name", scope = Scope.EVENT)),
                dataStoreId = "store-42",
            ),
        )
        assertTrue(q.cypher.contains("MATCH (:DataStore {dataStoreId: \$dataStoreId})-[:CONTAINS_LOG]->(log:Log)"), q.cypher)
        assertEquals("store-42", q.parameters["dataStoreId"])
    }

    @Test
    fun `candidate log probe keeps datastore scope and returns only distinct log ids`() {
        val logName = stdAttr(Scope.LOG, "concept:name")
        val filter = PqlExpression.Binary(
            op = BinaryOperator.EQ,
            left = logName,
            right = PqlExpression.Literal(rawText = "JournalReview", kind = PqlExpression.LiteralKind.STRING, value = "JournalReview", type = Type.STRING, location = loc),
            type = Type.BOOLEAN,
            location = loc,
        )
        val q = codegen.generate(
            CandidateLogPlan(
                source = LogicalSource(
                    fromScope = Scope.EVENT,
                    usedScopes = setOf(Scope.LOG, Scope.EVENT),
                    dataStoreId = "store-42",
                ),
                filter = filter,
                location = loc,
            ),
        )

        assertTrue(
            q.cypher.startsWith("MATCH (:DataStore {dataStoreId: \$dataStoreId})-[:CONTAINS_LOG]->(log:Log)"),
            q.cypher,
        )
        // The probe yields log ids only, so events must not be expanded at all —
        // mandatorily it would drop logs whose traces have no events, optionally it
        // would just multiply rows ahead of the DISTINCT.
        assertFalse(q.cypher.contains("HAS_EVENT"), q.cypher)
        assertTrue(q.cypher.contains("WHERE log.name = \$param0"), q.cypher)
        assertTrue(q.cypher.endsWith("RETURN DISTINCT log.logId AS logId ORDER BY log.logId"), q.cypher)
        assertEquals("store-42", q.parameters["dataStoreId"])
        assertEquals("JournalReview", q.parameters["param0"])
    }

    @Test
    fun `candidate log probe preserves explicit log ordering and ignores deeper order keys`() {
        val logName = stdAttr(Scope.LOG, "concept:name")
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            CandidateLogPlan(
                source = LogicalSource(
                    fromScope = Scope.EVENT,
                    usedScopes = setOf(Scope.LOG, Scope.EVENT),
                    dataStoreId = "store-42",
                ),
                filter = null,
                orderBy = listOf(
                    PqlQuery.OrderKey(logName, OrderDirection.DESC),
                    PqlQuery.OrderKey(eventName, OrderDirection.ASC),
                ),
                location = loc,
            ),
        )

        assertTrue(q.cypher.endsWith("ORDER BY log.name DESC, log.logId"), q.cypher)
    }

    @Test
    fun `trace-only WHERE filters before event expansion`() {
        val diagnosis = customAttr(Scope.TRACE, "Diagnosis")
        val filter = PqlExpression.Binary(
            op = BinaryOperator.IS_NOT,
            left = diagnosis,
            right = PqlExpression.Literal(rawText = "null", kind = PqlExpression.LiteralKind.NULL, value = null, type = Type.UNKNOWN, location = loc),
            type = Type.BOOLEAN,
            location = loc,
        )

        val q = codegen.generate(
            LogicalPlan.Select(
                source = LogicalSource(
                    fromScope = Scope.EVENT,
                    usedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
                    logId = "Hospital-test",
                ),
                projection = Projection(
                    columns = emptyList(),
                    selectAll = mapOf(Scope.LOG to true, Scope.TRACE to true, Scope.EVENT to true),
                ),
                filter = filter,
                location = loc,
            ),
        )

        val traceFilter = "WHERE trace.Diagnosis IS NOT null"
        val eventMatch = "MATCH (trace)-[:HAS_EVENT]->(event:Event)"
        assertTrue(q.cypher.contains(traceFilter), q.cypher)
        assertTrue(q.cypher.contains(eventMatch), q.cypher)
        assertTrue(q.cypher.indexOf(traceFilter) < q.cypher.indexOf(eventMatch), q.cypher)
        assertTrue(q.cypher.contains("RETURN 0 AS _kind"), q.cypher)
        assertTrue(q.cypher.contains("RETURN 1 AS _kind"), q.cypher)
        assertTrue(q.cypher.contains("RETURN 2 AS _kind"), q.cypher)
        assertTrue(q.cypher.contains("properties(event) AS event"), q.cypher)
        assertTrue(!q.cypher.contains("ORDER BY _kind, _logKey, _traceOrder, _eventOrder"), q.cypher)
    }

    @Test
    fun `limited split hierarchy emits log row only for matching trace filters`() {
        val diagnosis = customAttr(Scope.TRACE, "Diagnosis")
        val filter = PqlExpression.Binary(
            op = BinaryOperator.IS_NOT,
            left = diagnosis,
            right = PqlExpression.Literal(rawText = "null", kind = PqlExpression.LiteralKind.NULL, value = null, type = Type.UNKNOWN, location = loc),
            type = Type.BOOLEAN,
            location = loc,
        )

        val q = codegen.generate(
            LogicalPlan.Select(
                source = LogicalSource(
                    fromScope = Scope.EVENT,
                    usedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
                    logId = "Hospital-test",
                ),
                projection = Projection(
                    columns = emptyList(),
                    selectAll = mapOf(Scope.LOG to true, Scope.TRACE to true, Scope.EVENT to true),
                ),
                filter = filter,
                defaultLimits = HierarchicalLimits(log = 10, trace = 30, event = 90),
                location = loc,
            ),
        )

        assertFalse(q.cypher.contains("UNION ALL"), q.cypher)
        assertEquals(1, "trace.Diagnosis IS NOT null".toRegex().findAll(q.cypher).count(), q.cypher)
        assertTrue(q.cypher.contains("AND (trace.Diagnosis IS NOT null)"), q.cypher)
        assertTrue(q.cypher.contains("collect(properties(event)) AS events"), q.cypher)
        assertTrue(q.hydrateLogProperties)
    }

    @Test
    fun `implicit full hierarchy trace-only WHERE does not expand events`() {
        val diagnosis = customAttr(Scope.TRACE, "Diagnosis")
        val filter = PqlExpression.Binary(
            op = BinaryOperator.IS_NOT,
            left = diagnosis,
            right = PqlExpression.Literal(rawText = "null", kind = PqlExpression.LiteralKind.NULL, value = null, type = Type.UNKNOWN, location = loc),
            type = Type.BOOLEAN,
            location = loc,
        )

        val q = codegen.generate(
            LogicalPlan.Select(
                source = LogicalSource(
                    fromScope = Scope.EVENT,
                    usedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
                    logId = "Hospital-test",
                ),
                projection = Projection(
                    columns = emptyList(),
                    selectAll = mapOf(Scope.LOG to true, Scope.TRACE to true, Scope.EVENT to true),
                    implicitAll = true,
                ),
                filter = filter,
                location = loc,
            ),
        )

        assertTrue(q.cypher.contains("UNION ALL"), q.cypher)
        assertTrue(q.cypher.contains("RETURN 0 AS _kind, log.logId AS _logKey"), q.cypher)
        assertTrue(q.cypher.contains("properties(log) AS log, null AS trace"), q.cypher)
        assertTrue(q.cypher.contains("WHERE trace.Diagnosis IS NOT null WITH DISTINCT log"), q.cypher)
        assertTrue(q.cypher.contains("RETURN 1 AS _kind, log.logId AS _logKey"), q.cypher)
        assertTrue(q.cypher.contains("null AS log, properties(trace) AS trace"), q.cypher)
        assertTrue(!q.cypher.contains("ORDER BY _kind, _logKey, _traceOrder"), q.cypher)
        assertTrue(!q.cypher.contains("RETURN log, trace, event ORDER BY"), q.cypher)
        assertTrue(!q.cypher.contains("HAS_EVENT"), q.cypher)
        assertTrue(!q.cypher.contains("properties(event)"), q.cypher)
        assertTrue(q.cypher.indexOf("WHERE trace.Diagnosis IS NOT null") < q.cypher.indexOf("RETURN 0 AS _kind"), q.cypher)
        assertTrue(!q.cypher.contains("collect("), q.cypher)
    }

    @Test
    fun `trace-only WHERE keeps event expansion when events are materialized`() {
        val diagnosis = customAttr(Scope.TRACE, "Diagnosis")
        val filter = PqlExpression.Binary(
            op = BinaryOperator.IS_NOT,
            left = diagnosis,
            right = PqlExpression.Literal(rawText = "null", kind = PqlExpression.LiteralKind.NULL, value = null, type = Type.UNKNOWN, location = loc),
            type = Type.BOOLEAN,
            location = loc,
        )

        val q = codegen.generate(
            LogicalPlan.Select(
                source = LogicalSource(
                    fromScope = Scope.EVENT,
                    usedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
                    logId = "Hospital-test",
                ),
                projection = Projection(columns = emptyList(), implicitAll = true),
                filter = filter,
                materializedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
                location = loc,
            ),
        )

        assertTrue(q.cypher.contains("UNION ALL"), q.cypher)
        assertTrue(q.cypher.contains("MATCH (trace)-[:HAS_EVENT]->(event:Event)"), q.cypher)
        assertTrue(q.cypher.contains("RETURN 0 AS _kind"), q.cypher)
        assertTrue(q.cypher.contains("RETURN 1 AS _kind"), q.cypher)
        assertTrue(q.cypher.contains("RETURN 2 AS _kind"), q.cypher)
        assertTrue(q.cypher.contains("properties(event) AS event"), q.cypher)
    }

    @Test
    fun `log-only query omits trace and event from MATCH`() {
        val a = stdAttr(Scope.LOG, "concept:name")
        val q = codegen.generate(
            selectPlan(
                listOf(ProjectedColumn(a, alias = "log_name", scope = Scope.LOG)),
                from = Scope.LOG, used = setOf(Scope.LOG),
            ),
        )
        val c = q.cypher
        assertTrue(c.contains("MATCH (log:Log)"), c)
        assertTrue(!c.contains("(trace:Trace)"), c)
        assertTrue(!c.contains("(event:Event)"), c)
        assertTrue(c.contains("log.name AS log_name"), c)
    }

    /**
     * `select count(t:name)` counts traces; an empty trace is still a trace, and
     * ProcessM counts it. Expanding events with a mandatory MATCH used to drop such
     * traces (wrong result) and made every trace-level aggregate walk the whole event
     * set of the log (slow). Events are still expanded — the output hierarchy needs
     * them — but optionally.
     */
    @Test
    fun `trace-scope aggregation reads trace members without requiring child events`() {
        val traceName = stdAttr(Scope.TRACE, "concept:name")
        val q = codegen.generate(
            selectPlan(
                listOf(
                    ProjectedColumn(
                        PqlExpression.Aggregation("count", traceName, type = Type.NUMBER, location = loc),
                        alias = "cnt",
                        scope = Scope.TRACE,
                    ),
                ),
                from = Scope.LOG,
                used = setOf(Scope.LOG, Scope.TRACE),
            ).copy(materializedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT)),
        )

        assertTrue(q.cypher.contains("WITH _member AS _component, _member.caseId AS _value"), q.cypher)
        assertTrue(q.cypher.contains("RETURN count(_value)"), q.cypher)
        assertFalse(q.cypher.contains("(trace:Trace)-[:HAS_EVENT]->"), q.cypher)
    }

    /** When a clause really uses the event scope, the expansion must stay mandatory. */
    @Test
    fun `event-scope query keeps the mandatory event expansion`() {
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            selectPlan(
                listOf(ProjectedColumn(eventName, alias = "e_name", scope = Scope.EVENT)),
                from = Scope.LOG,
                used = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
            ).copy(materializedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT)),
        )

        assertTrue(q.cypher.contains("-[:HAS_EVENT]->(event:Event)"), q.cypher)
        assertFalse(q.cypher.contains("OPTIONAL MATCH (trace)-[:HAS_EVENT]->"), q.cypher)
    }

    @Test
    fun `log-only projection can materialize descendant placeholders`() {
        val a = stdAttr(Scope.LOG, "concept:name")
        val q = codegen.generate(
            selectPlan(
                listOf(ProjectedColumn(a, alias = "log_name", scope = Scope.LOG)),
                from = Scope.LOG,
                used = setOf(Scope.LOG),
            ).copy(materializedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT)),
        )
        val c = q.cypher

        // Events are materialized for output only — no clause references the event
        // scope — so the expansion must be OPTIONAL. A mandatory `-[:HAS_EVENT]->`
        // silently drops traces that have no events, which ProcessM does return.
        assertTrue(c.contains("MATCH (log:Log)-[:CONTAINS]->(trace:Trace)"), c)
        assertTrue(c.contains("OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)"), c)
        assertFalse(c.contains("(trace:Trace)-[:HAS_EVENT]->"), c)
        assertTrue(c.contains("log.logId AS _log_id_"), c)
        assertTrue(c.contains("trace.traceId AS _trace_id_"), c)
        assertTrue(c.contains("{} AS event"), c)
        assertEquals(Scope.EVENT, q.columnAliases["event"]?.scope)
        assertTrue(q.columnAliases["event"]?.synthetic == true)
    }

    @Test
    fun `backtick-escaped properties round-trip into RETURN alias`() {
        val a = stdAttr(Scope.TRACE, "cost:total", Type.NUMBER)
        val q = codegen.generate(
            selectPlan(
                listOf(ProjectedColumn(a, alias = "t_cost_total", scope = Scope.TRACE)),
                from = Scope.TRACE, used = setOf(Scope.LOG, Scope.TRACE),
            ),
        )
        assertTrue(q.cypher.contains("trace.`cost:total` AS t_cost_total"), q.cypher)
    }

    @Test
    fun `column alias map records original PQL expression and scope`() {
        val a = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            selectPlan(listOf(ProjectedColumn(a, alias = "concept_name", scope = Scope.EVENT))),
        )
        val alias = q.columnAliases["concept_name"]!!
        assertEquals(Scope.EVENT, alias.scope)
        assertTrue(alias.pqlExpression.contains("concept:name"), alias.pqlExpression)
    }

    @Test
    fun `event projection carries hidden hierarchy keys`() {
        val a = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            selectPlan(listOf(ProjectedColumn(a, alias = "e_concept_name", scope = Scope.EVENT))),
        )

        assertTrue(q.cypher.contains("log.logId AS _log_id_"), q.cypher)
        assertTrue(q.cypher.contains("trace.traceId AS _trace_id_"), q.cypher)
        assertTrue(q.cypher.contains("_log_meta_"), q.cypher)
        assertTrue(q.cypher.contains("ORDER BY log.logId, trace.importOrder, event.importOrder"), q.cypher)
        assertTrue(q.columnAliases["_log_id_"]!!.synthetic)
        assertTrue(q.columnAliases["_trace_id_"]!!.synthetic)
        assertTrue("_log_meta_" !in q.columnAliases, q.columnAliases.toString())
    }

    @Test
    fun `trace projection carries hidden log key and deterministic trace order`() {
        val a = stdAttr(Scope.TRACE, "concept:name")
        val q = codegen.generate(
            selectPlan(
                listOf(ProjectedColumn(a, alias = "t_concept_name", scope = Scope.TRACE)),
                used = setOf(Scope.LOG, Scope.TRACE),
            ),
        )

        assertTrue(q.cypher.contains("log.logId AS _log_id_"), q.cypher)
        assertTrue(q.cypher.contains("_log_meta_"), q.cypher)
        assertTrue(!q.cypher.contains("trace.traceId AS _trace_id_"), q.cypher)
        assertTrue(q.cypher.contains("ORDER BY log.logId, trace.importOrder"), q.cypher)
    }

    @Test
    fun `AND OR compose predicates with parentheses`() {
        val a = stdAttr(Scope.EVENT, "concept:name")
        val left = PqlExpression.Binary(BinaryOperator.EQ, a, PqlExpression.Literal(rawText = "A", kind = PqlExpression.LiteralKind.STRING, value = "A", type = Type.STRING, location = loc), Type.BOOLEAN, loc)
        val right = PqlExpression.Binary(BinaryOperator.EQ, a, PqlExpression.Literal(rawText = "B", kind = PqlExpression.LiteralKind.STRING, value = "B", type = Type.STRING, location = loc), Type.BOOLEAN, loc)
        val or = PqlExpression.Binary(BinaryOperator.OR, left, right, Type.BOOLEAN, loc)
        val q = codegen.generate(
            selectPlan(listOf(ProjectedColumn(a, alias = "concept_name", scope = Scope.EVENT)), filter = or),
        )
        assertTrue(q.cypher.contains(" OR "), q.cypher)
        assertEquals(2, q.parameters.size)
    }

    // ---------- Task 26: GROUP BY + aggregation ----------

    @Test
    fun `simple GROUP BY emits WITH clause with grouping key`() {
        val name = stdAttr(Scope.EVENT, "concept:name")
        val count = PqlExpression.Aggregation("count", name, type = Type.NUMBER, location = loc)
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(name, alias = "e_concept_name", scope = Scope.EVENT),
                    ProjectedColumn(count, alias = "count_e_concept_name", scope = Scope.EVENT),
                ),
                groupBy = GroupBySpec(keys = listOf(name), hasAggregation = true),
            ),
        )
        val c = q.cypher
        assertTrue(c.contains("WITH "), "expected WITH clause: $c")
        assertTrue(c.contains("event.activity"), "expected grouping key event.activity: $c")
        assertTrue(c.contains("count(event.activity)"), "expected count(event.activity): $c")
    }

    @Test
    fun `event grouped aggregation order keeps trace window before event ordering`() {
        val name = stdAttr(Scope.EVENT, "concept:name")
        val count = PqlExpression.Aggregation("count", name, type = Type.NUMBER, location = loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(name, alias = "e_concept_name", scope = Scope.EVENT),
                    ProjectedColumn(count, alias = "count_e_concept_name", scope = Scope.EVENT),
                ),
                groupBy = GroupBySpec(keys = listOf(name), hasAggregation = true),
                orderBy = listOf(
                    PqlQuery.OrderKey(count, OrderDirection.DESC),
                    PqlQuery.OrderKey(name, OrderDirection.ASC),
                ),
            ),
        )

        assertTrue(
            q.cypher.contains(
                "ORDER BY _log_id_, _trace_order_, count_e_concept_name DESC, _gb_0 ASC, _event_group_order_",
            ),
            q.cypher,
        )
    }

    @Test
    fun `bare event classifier GROUP BY returns grouped event map without original event node`() {
        val resourceClassifier = PqlExpression.Attribute(
            name = "Resource",
            baseScope = Scope.EVENT,
            effectiveScope = Scope.EVENT,
            kind = AttributeKind.CLASSIFIER,
            xesStandardName = null,
            classifierName = "Resource",
            classifierKeys = listOf("org:resource"),
            wasBracketed = false,
            type = Type.STRING,
            location = loc,
        )

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                groupBy = GroupBySpec(keys = listOf(resourceClassifier), hasAggregation = false),
            ),
        )

        val c = q.cypher
        assertTrue(c.contains("WHERE event.resource IS NOT NULL"), c)
        assertTrue(
            c.contains(
                "WITH log, trace, event.resource AS _gb_0, " +
                    "min(event.importOrder) AS _group_first_event_order_, count(event) AS _event_count_",
            ),
            c,
        )
        assertTrue(c.contains("RETURN log.logId AS _logKey, trace, {resource: _gb_0} AS event"), c)
        assertTrue(c.contains("ORDER BY log.logId, trace.importOrder, _group_first_event_order_"), c)
        assertEquals(emptyMap<String, ColumnAlias>(), q.columnAliases)
    }

    @Test
    fun `bare event GROUP BY honors ordering by the grouped attribute`() {
        val eventName = stdAttr(Scope.EVENT, "concept:name")

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                groupBy = GroupBySpec(keys = listOf(eventName), hasAggregation = false),
                orderBy = listOf(PqlQuery.OrderKey(eventName, OrderDirection.ASC)),
            ),
        )

        assertTrue(
            q.cypher.contains("ORDER BY log.logId, trace.importOrder, _gb_0 ASC, _group_first_event_order_"),
            q.cypher,
        )
    }

    @Test
    fun `bare event GROUP BY with hierarchical limits pushes caps before grouping the whole log`() {
        val eventName = stdAttr(Scope.EVENT, "concept:name")

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                groupBy = GroupBySpec(keys = listOf(eventName), hasAggregation = false),
                orderBy = listOf(PqlQuery.OrderKey(eventName, OrderDirection.ASC)),
                limits = HierarchicalLimits(log = 1, trace = 2, event = 5),
                dataStoreId = "store-42",
            ),
        )

        assertTrue(q.cypher.contains("MATCH (:DataStore {dataStoreId: \$dataStoreId})-[:CONTAINS_LOG]->(log:Log)"), q.cypher)
        assertTrue(q.cypher.contains("WITH log ORDER BY log.logId LIMIT \$logLimit"), q.cypher)
        assertTrue(
            q.cypher.contains(
                "MATCH (trace:Trace {parentLogId: log.logId}) WHERE trace.importOrder IS NOT NULL" +
                    " WITH trace ORDER BY trace.parentLogId, trace.importOrder LIMIT \$traceLimit",
            ),
            q.cypher,
        )
        assertTrue(q.cypher.contains("ORDER BY _gb_0 ASC, _group_first_event_order_ LIMIT \$eventLimit"), q.cypher)
        assertTrue(
            q.cypher.contains("RETURN log.logId AS _logKey, trace, event, _event_order_0, _group_first_event_order_"),
            q.cypher,
        )
        assertTrue(q.cypher.contains("ORDER BY log.logId, trace.importOrder, _event_order_0 ASC, _group_first_event_order_"), q.cypher)
        assertEquals(1L, q.parameters["logLimit"])
        assertEquals(2L, q.parameters["traceLimit"])
        assertEquals(5L, q.parameters["eventLimit"])
    }

    @Test
    fun `count aggregation appears in RETURN`() {
        val name = stdAttr(Scope.EVENT, "concept:name")
        val count = PqlExpression.Aggregation("count", name, type = Type.NUMBER, location = loc)
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(count, alias = "cnt", scope = Scope.EVENT)),
                groupBy = GroupBySpec(keys = emptyList(), hasAggregation = true),
            ),
        )
        assertTrue(q.cypher.contains("count(event.activity)"), q.cypher)
        assertTrue(q.cypher.contains(" AS cnt"), q.cypher)
        assertTrue(!q.cypher.contains("ORDER BY event.importOrder"), q.cypher)
        assertTrue(q.cypher.contains("trace.importOrder AS _trace_order_"), q.cypher)
        assertTrue(q.cypher.contains("ORDER BY _log_id_, _trace_order_"), q.cypher)
    }

    @Test
    fun `log hierarchy cardinality uses count subqueries without flat event expansion`() {
        val logName = stdAttr(Scope.LOG, "concept:name")
        val traceName = hoistedAttr(Scope.TRACE, Scope.LOG, "concept:name")
        val eventName = hoistedAttr(Scope.EVENT, Scope.LOG, "concept:name")
        fun count(attribute: PqlExpression.Attribute) =
            PqlExpression.Aggregation("count", attribute, scope = Scope.LOG, type = Type.NUMBER, location = loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(count(logName), alias = "log_count", scope = Scope.LOG),
                    ProjectedColumn(count(traceName), alias = "trace_count", scope = Scope.LOG),
                    ProjectedColumn(count(eventName), alias = "event_count", scope = Scope.LOG),
                ),
                limits = HierarchicalLimits(log = 1, trace = 10, event = 20),
                logId = "Road",
            ),
        )

        assertTrue(q.cypher.contains("CASE WHEN log.name IS NULL THEN 0 ELSE 1 END AS log_count"), q.cypher)
        assertTrue(
            q.cypher.contains(
                "COUNT { (log)-[:CONTAINS]->(_count_trace:Trace)" +
                    " WHERE _count_trace.caseId IS NOT NULL } AS trace_count",
            ),
            q.cypher,
        )
        assertTrue(
            q.cypher.contains(
                "COUNT { (log)-[:CONTAINS]->(:Trace)-[:HAS_EVENT]->(_count_event:Event)" +
                    " WHERE _count_event.activity IS NOT NULL } AS event_count",
            ),
            q.cypher,
        )
        assertFalse(q.cypher.contains("count(DISTINCT CASE"), q.cypher)
        assertFalse(q.cypher.contains("MATCH (log)-[:CONTAINS]->(trace:Trace)-[:HAS_EVENT]->(event:Event)"), q.cypher)
        val placeholderCount = "COUNT { (_placeholder_trace)-[:HAS_EVENT]->(:Event) }"
        assertEquals(1, q.cypher.split(placeholderCount).size - 1, q.cypher)
    }

    @Test
    fun `compiled hierarchy cardinality uses count subqueries`() {
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        val plan = compiler.compile(
            "select count(l:name), count(^t:name), count(^^e:name) limit l:1",
            logId = "Road",
            defaultLimits = HierarchicalLimits(trace = 30, event = 100),
        ) as LogicalPlan.Select

        val q = codegen.generate(plan)

        assertTrue(q.cypher.contains("COUNT { (log)-[:CONTAINS]->(_count_trace:Trace)"), q.cypher)
        assertTrue(q.cypher.contains("COUNT { (log)-[:CONTAINS]->(:Trace)-[:HAS_EVENT]->(_count_event:Event)"), q.cypher)
        assertFalse(q.cypher.contains("count(DISTINCT CASE"), q.cypher)
    }

    @Test
    fun `scoped trace aggregation over log attribute preserves grouped event placeholder count`() {
        val logName = stdAttr(Scope.LOG, "concept:name")
        val traceMinLogName = PqlExpression.Aggregation("min", logName, scope = Scope.TRACE, type = Type.STRING, location = loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(traceMinLogName, alias = "min_0", scope = Scope.TRACE)),
            ),
        )

        assertTrue(q.cypher.contains("_log_id_"), q.cypher)
        assertTrue(q.cypher.contains("_log_meta_"), q.cypher)
        assertTrue(q.cypher.contains("collect(trace) AS _trace_members"), q.cypher)
        assertTrue(q.cypher.contains("coalesce(max(_event_count), 0) AS _null_event_count_"), q.cypher)
        assertTrue(q.cypher.contains("OPTIONAL MATCH (_member)<-[:CONTAINS]-(_argument_log_log:Log)"), q.cypher)
        assertTrue(q.cypher.contains("min(_value) AS _scope_agg_0"), q.cypher)
        assertEquals("trace:min(log:concept:name)", q.columnAliases["min_0"]?.pqlExpression)
        assertEquals(Scope.TRACE, q.columnAliases["min_0"]?.scope)
        assertEquals(Scope.TRACE, q.columnAliases["_null_event_count_"]?.scope)
    }

    @Test
    fun `empty projection aggregate ORDER BY returns placeholder event without exposing order aliases`() {
        val total = stdAttr(Scope.EVENT, "cost:total", Type.NUMBER)
        val avgTotal = PqlExpression.Aggregation("avg", total, type = Type.NUMBER, location = loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                orderBy = listOf(PqlQuery.OrderKey(avgTotal, OrderDirection.ASC)),
            ),
        )

        assertTrue(q.cypher.contains("avg(event.cost) AS _order_0"), q.cypher)
        assertTrue(q.cypher.contains("properties(log) AS _log_node_"), q.cypher)
        assertTrue(q.cypher.contains("properties(trace) AS _trace_node_, {} AS _event_node_"), q.cypher)
        assertTrue(q.cypher.contains("_log_node_ AS log, _trace_node_ AS trace, _event_node_ AS event"), q.cypher)
        assertTrue(q.cypher.contains("ORDER BY _order_0 ASC"), q.cypher)
        assertEquals(emptyMap<String, ColumnAlias>(), q.columnAliases)
    }

    @Test
    fun `log scoped hoisted aggregates preserve original trace event placeholder hierarchy`() {
        val total = hoistedAttr(Scope.EVENT, Scope.LOG, "cost:total", Type.NUMBER)
        val timestamp = hoistedAttr(Scope.EVENT, Scope.LOG, "time:timestamp", Type.DATETIME)
        val avgTotal = PqlExpression.Aggregation("avg", total, scope = Scope.LOG, type = Type.NUMBER, location = loc)
        val minTimestamp = PqlExpression.Aggregation("min", timestamp, scope = Scope.LOG, type = Type.DATETIME, location = loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(avgTotal, alias = "avg_total", scope = Scope.LOG),
                    ProjectedColumn(minTimestamp, alias = "min_ts", scope = Scope.LOG),
                ),
            ),
        )

        assertTrue(q.cypher.contains("collect(log) AS _log_members"), q.cypher)
        assertTrue(q.cypher.contains("collect(trace) AS _trace_members"), q.cypher)
        assertTrue(q.cypher.contains("RETURN count(event) AS _event_count"), q.cypher)
        assertTrue(q.cypher.contains("coalesce(max(_event_count), 0) AS _null_event_count_"), q.cypher)
        assertFalse(q.cypher.contains("{} AS event"), q.cypher)
        assertTrue(q.cypher.contains("_log_meta_"), q.cypher)
        assertTrue(q.columnAliases["_null_event_count_"]?.synthetic == true, q.columnAliases.toString())
        assertEquals(Scope.TRACE, q.columnAliases["_null_event_count_"]?.scope)
    }

    @Test
    fun `log scoped aggregates return only the placeholder prefix needed by trace windowing`() {
        val total = hoistedAttr(Scope.EVENT, Scope.LOG, "cost:total", Type.NUMBER)
        val avgTotal = PqlExpression.Aggregation("avg", total, scope = Scope.LOG, type = Type.NUMBER, location = loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(avgTotal, alias = "avg_total", scope = Scope.LOG)),
                limits = HierarchicalLimits(trace = 10),
                offsets = HierarchicalOffsets(trace = 3),
            ).copy(defaultLimits = HierarchicalLimits(trace = 5)),
        )

        val aggregate = q.cypher.indexOf("RETURN avg(_value)")
        val prefix = q.cypher.indexOf("LIMIT $" + "aggregateTracePrefix")
        assertTrue(aggregate >= 0 && prefix > aggregate, q.cypher)
        assertEquals(8L, q.parameters["aggregateTracePrefix"])
        assertFalse(q.cypher.contains(" SKIP "), q.cypher)

    }

    @Test
    fun `zero trace limit keeps aggregate log while Kotlin removes its placeholders`() {
        val total = hoistedAttr(Scope.EVENT, Scope.LOG, "cost:total", Type.NUMBER)
        val avgTotal = PqlExpression.Aggregation("avg", total, scope = Scope.LOG, type = Type.NUMBER, location = loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(avgTotal, alias = "avg_total", scope = Scope.LOG)),
                limits = HierarchicalLimits(trace = 0),
            ),
        )

        assertTrue(q.cypher.contains("OPTIONAL CALL (_log_members)"), q.cypher)
        assertEquals(1L, q.parameters["aggregateTracePrefix"])
        assertFalse(q.cypher.contains(" LIMIT 0"), q.cypher)

    }

    @Test
    fun `pure event aggregations pre-window traces by import order`() {
        val ts = stdAttr(Scope.EVENT, "time:timestamp", Type.DATETIME)
        val name = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(PqlExpression.Aggregation("min", ts, type = Type.DATETIME, location = loc), alias = "min_0", scope = Scope.EVENT),
                    ProjectedColumn(PqlExpression.Aggregation("count", name, type = Type.NUMBER, location = loc), alias = "count_1", scope = Scope.EVENT),
                ),
                groupBy = GroupBySpec(keys = emptyList(), hasAggregation = true),
                limits = HierarchicalLimits(trace = 30),
                logId = "journal",
            ),
        )
        // Per-trace aggregation rows get windowed to the trace limit anyway, so
        // the expansion must not aggregate every trace of a large log first.
        assertTrue(
            q.cypher.contains(
                "CALL (log) { MATCH (trace:Trace {parentLogId: log.logId})" +
                    " WHERE trace.importOrder IS NOT NULL" +
                    " WITH trace ORDER BY trace.parentLogId, trace.importOrder" +
                    " LIMIT \$traceLimit RETURN trace }",
            ),
            q.cypher,
        )
        assertEquals(30L, q.parameters["traceLimit"])
        assertTrue(q.cypher.contains("min(event.timestamp)"), q.cypher)
    }

    @Test
    fun `filtered event aggregations keep the full expansion`() {
        val ts = stdAttr(Scope.EVENT, "time:timestamp", Type.DATETIME)
        val filter = PqlExpression.Binary(
            op = BinaryOperator.EQ,
            left = stdAttr(Scope.EVENT, "concept:name"),
            right = PqlExpression.Literal(
                rawText = "A",
                kind = PqlExpression.LiteralKind.STRING,
                value = "A",
                type = Type.STRING,
                location = loc,
            ),
            type = Type.BOOLEAN,
            location = loc,
        )
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(PqlExpression.Aggregation("min", ts, type = Type.DATETIME, location = loc), alias = "min_0", scope = Scope.EVENT),
                ),
                filter = filter,
                groupBy = GroupBySpec(keys = emptyList(), hasAggregation = true),
                limits = HierarchicalLimits(trace = 30),
                logId = "journal",
            ),
        )
        // A filter changes which traces survive, so pre-windowing would be wrong.
        assertTrue(!q.cypher.contains("ORDER BY trace.importOrder LIMIT \$traceLimit RETURN trace }"), q.cypher)
    }

    @Test
    fun `max aggregation on timestamp`() {
        val ts = stdAttr(Scope.EVENT, "time:timestamp", Type.DATETIME)
        val maxTs = PqlExpression.Aggregation("max", ts, type = Type.DATETIME, location = loc)
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(maxTs, alias = "max_ts", scope = Scope.EVENT)),
                groupBy = GroupBySpec(keys = emptyList(), hasAggregation = true),
            ),
        )
        assertTrue(q.cypher.contains("max(event.timestamp)"), q.cypher)
        assertTrue(q.cypher.contains(" AS max_ts"), q.cypher)
    }

    @Test
    fun `hoisted GROUP BY pre-computes the key at the effective scope`() {
        // group by ^e:name at trace context:  baseScope=EVENT, effectiveScope=TRACE
        val hoistedName = hoistedAttr(base = Scope.EVENT, effective = Scope.TRACE, xesName = "concept:name")
        val count = PqlExpression.Aggregation(
            "count",
            stdAttr(Scope.EVENT, "concept:name"),
            type = Type.NUMBER, location = loc,
        )
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(hoistedName, alias = "t_e_name", scope = Scope.TRACE),
                    ProjectedColumn(count, alias = "cnt", scope = Scope.EVENT),
                ),
                groupBy = GroupBySpec(keys = listOf(hoistedName), hasAggregation = true),
            ),
        )
        val c = q.cypher
        // The hoisted attribute's data still lives on the event node (physical property event.activity).
        // The codegen must reference event.activity, not trace.activity.
        assertTrue(c.contains("event.activity"), "expected event.activity for hoisted group-by: $c")
        assertTrue(c.contains("WITH "), "expected WITH clause: $c")
    }

    @Test
    fun `hoisted event GROUP BY emits trace variant reconstruction query`() {
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val hoistedName = hoistedAttr(base = Scope.EVENT, effective = Scope.TRACE, xesName = "concept:name")
        val traceName = stdAttr(Scope.TRACE, "concept:name")
        val countTraceName = PqlExpression.Aggregation("count", traceName, type = Type.NUMBER, location = loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(eventName, alias = "e_concept_name", scope = Scope.EVENT)),
                groupBy = GroupBySpec(keys = listOf(hoistedName), hasAggregation = true),
                orderBy = listOf(PqlQuery.OrderKey(countTraceName, OrderDirection.DESC)),
            ),
        )

        val c = q.cypher

        assertTrue(c.contains("_group_event_event.activity AS _group_value"), c)
        assertTrue(c.contains("collect({value: _group_value})"), c)
        assertTrue(c.contains("collect(trace) AS _trace_members"), c)
        assertTrue(c.contains("collect(event) AS _event_members"), c)
        assertTrue(c.contains("_event_position AS _event_group_order_"), c)
        assertEquals(Scope.TRACE, q.columnAliases["_trace_id_"]!!.scope)
        assertTrue(q.columnAliases["_trace_id_"]!!.synthetic)

    }

    @Test
    fun `compiled count-only activity variants use trace cache without reading events`() {
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        val plan = compiler.compile(
            "select count(t:name), count(^e:name) group by ^e:name " +
                "order by count(t:name) desc limit l:1, t:3",
            logId = "Hospital-test",
        ) as LogicalPlan.Select

        val q = codegen.generate(plan)
        val c = q.cypher

        assertTrue(c.contains("MATCH (trace:Trace {parentLogId: log.logId})"), c)
        assertTrue(c.contains("trace.processmActivityVariantId AS _trace_variant_"), c)
        assertTrue(c.contains("trace.processmActivityNonNullCount AS _cached_variant_event_count_"), c)
        assertTrue(c.contains("trace.caseId AS _cached_trace_name_"), c)
        assertTrue(c.contains("count(_cached_trace_name_) AS _order_0"), c)
        assertTrue(c.contains("count(*) AS _trace_group_size_"), c)
        assertTrue(c.contains("_cached_variant_event_count_ * _trace_group_size_ AS count_1"), c)
        assertTrue(c.contains("_trace_group_size_ AS _trace_count_"), c)
        assertTrue(c.contains("ORDER BY _order_0 DESC, _trace_group_order_ LIMIT $"), c)
        assertFalse(c.contains("HAS_EVENT"), c)
        assertFalse(c.contains("event.activity"), c)
        assertEquals(3L, q.parameters["param0"])
    }

    @Test
    fun `grouping a cost sequence does not use the activity variant cache`() {
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        val plan = compiler.compile(
            "select count(t:name), count(^e:total) group by ^e:total " +
                "order by count(t:name) desc limit l:1, t:3",
            logId = "synthetic-test",
        ) as LogicalPlan.Select

        val c = codegen.generate(plan).cypher

        assertTrue(c.contains("HAS_EVENT"), c)
        assertTrue(c.contains("_group_event_event.cost AS _group_value"), c)
        assertTrue(c.contains("collect({value: _group_value})"), c)
        assertFalse(c.contains("processmActivityVariantId"), c)
        assertFalse(c.contains("processmActivityNonNullCount"), c)

    }

    @Test
    fun `activity variant cache falls back when grouped event values are selected`() {
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        val plan = compiler.compile(
            "select count(t:name), count(^e:name), e:name group by ^e:name " +
                "order by count(t:name) desc limit l:1, t:3",
            logId = "Hospital-test",
        ) as LogicalPlan.Select

        val c = codegen.generate(plan).cypher

        assertTrue(c.contains("HAS_EVENT"), c)
        assertTrue(c.contains("_group_event_event.activity AS _group_value"), c)
        assertTrue(c.contains("collect(event) AS _event_members"), c)
        assertFalse(c.contains("processmActivityVariantId"), c)

    }

    @Test
    fun `implicit hoisted event GROUP BY carries hidden log node attributes`() {
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val hoistedName = hoistedAttr(base = Scope.EVENT, effective = Scope.TRACE, xesName = "concept:name")

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                groupBy = GroupBySpec(keys = listOf(hoistedName), hasAggregation = true),
                orderBy = listOf(PqlQuery.OrderKey(eventName, OrderDirection.ASC)),
            ),
        )

        val c = q.cypher
        assertTrue(c.contains("CASE WHEN _event_idx_ = 0 THEN properties(log) ELSE null END AS _log_node_"), c)
        assertTrue(
            c.contains(
                "CASE WHEN _event_idx_ = 0 THEN log { .classifiers, .extensions, .traceGlobals, .eventGlobals } ELSE null END AS _log_meta_",
            ),
            c,
        )
        assertTrue("_log_node_" !in q.columnAliases, q.columnAliases.toString())
    }

    @Test
    fun `compiled implicit hoisted event GROUP BY follows ProcessM select all rewrite`() {
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        val plan = compiler.compile(
            "group by ^e:name order by name",
            logId = "Sepsis-test",
        ) as LogicalPlan.Select

        val q = codegen.generate(plan)
        val c = q.cypher

        assertTrue(plan.projection.implicitAll)
        assertEquals(setOf(Scope.LOG), plan.projection.selectAll.filterValues { it }.keys)
        assertTrue(plan.projection.columns.any { it.alias == "e_concept_name" }, plan.projection.columns.toString())
        assertTrue(c.contains("CASE WHEN _event_idx_ = 0 THEN properties(log) ELSE null END AS _log_node_"), c)
        assertTrue(c.contains("collect(event.activity) AS _selected_values_0"), c)
        assertTrue(c.contains("_selected_values_0[_event_idx_] AS e_concept_name"), c)
        assertTrue(
            c.contains(
                "CASE WHEN _event_idx_ = 0 THEN log { .classifiers, .extensions, .traceGlobals, .eventGlobals } ELSE null END AS _log_meta_",
            ),
            c,
        )
    }

    @Test
    fun `hoisted event GROUP BY applies default trace window before event unwind for single log`() {
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        val plan = compiler.compile(
            "group by ^e:name order by name",
            logId = "Sepsis-test",
            defaultLimits = HierarchicalLimits(trace = 30, event = 90),
        ) as LogicalPlan.Select

        val q = codegen.generate(plan)
        val c = q.cypher

        assertTrue(c.contains("ORDER BY _trace_group_order_ LIMIT $"), c)
        assertTrue(c.contains("AS _event_indexes_ UNWIND _event_indexes_ AS _event_idx_"), c)
        assertTrue(c.indexOf("ORDER BY _trace_group_order_ LIMIT") < c.indexOf("AS _event_indexes_ UNWIND"))
        assertEquals(30L, q.parameters["param0"])
        assertEquals(0L, q.parameters["param1"])
        assertEquals(90L, q.parameters["param2"])
    }

    @Test
    fun `mixed trace and hoisted event GROUP BY keeps trace variant reconstruction`() {
        val traceName = stdAttr(Scope.TRACE, "concept:name")
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val hoistedName = hoistedAttr(base = Scope.EVENT, effective = Scope.TRACE, xesName = "concept:name")
        val countTraceName = PqlExpression.Aggregation("count", traceName, type = Type.NUMBER, location = loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(countTraceName, alias = "count_trace", scope = Scope.TRACE),
                    ProjectedColumn(eventName, alias = "e_concept_name", scope = Scope.EVENT),
                ),
                groupBy = GroupBySpec(keys = listOf(traceName, hoistedName), hasAggregation = true),
                orderBy = listOf(PqlQuery.OrderKey(countTraceName, OrderDirection.DESC)),
            ),
        )

        val c = q.cypher

        assertTrue(c.contains("trace.caseId AS _trace_group_0"), c)
        assertTrue(c.contains("_group_sequence_1 AS _trace_group_1"), c)
        assertTrue(c.contains("collect(trace) AS _trace_members"), c)
        assertTrue(c.contains("_log_meta_"), c)
        assertTrue(c.contains("collect(event) AS _event_members"), c)
        assertEquals(Scope.TRACE, q.columnAliases["_trace_id_"]!!.scope)
        assertTrue(q.columnAliases["_trace_id_"]!!.synthetic)

    }

    @Test
    fun `mixed trace and hoisted event GROUP BY respects trace scoped order tiebreakers`() {
        val traceName = stdAttr(Scope.TRACE, "concept:name")
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val hoistedName = hoistedAttr(base = Scope.EVENT, effective = Scope.TRACE, xesName = "concept:name")
        val countTraceName = PqlExpression.Aggregation("count", traceName, type = Type.NUMBER, location = loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(countTraceName, alias = "count_trace", scope = Scope.TRACE),
                    ProjectedColumn(eventName, alias = "e_concept_name", scope = Scope.EVENT),
                ),
                groupBy = GroupBySpec(keys = listOf(traceName, hoistedName), hasAggregation = true),
                orderBy = listOf(
                    PqlQuery.OrderKey(countTraceName, OrderDirection.DESC),
                    PqlQuery.OrderKey(traceName, OrderDirection.ASC),
                ),
            ),
        )

        val c = q.cypher

        assertTrue(
            c.contains("_scope_order_1 DESC, trace.caseId ASC, _trace_order_"),
            "expected independent aggregate ordering followed by trace attribute tiebreaker: $c",
        )

    }

    @Test
    fun `bare hoisted event GROUP BY projects grouped values as variant events`() {
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val hoistedName = hoistedAttr(base = Scope.EVENT, effective = Scope.TRACE, xesName = "concept:name")

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                groupBy = GroupBySpec(keys = listOf(hoistedName), hasAggregation = true),
                orderBy = listOf(PqlQuery.OrderKey(eventName, OrderDirection.ASC)),
            ),
        )

        val c = q.cypher
        assertTrue(c.contains("UNWIND range(0, size(_trace_variant_) - 1) AS _event_idx_"), c)
        assertTrue(c.contains("_trace_variant_[_event_idx_] AS _grouped_event_value_"), c)
        assertTrue(!c.contains("count_trace_concept_name"), c)
        assertEquals("event:concept:name", q.columnAliases["_grouped_event_value_"]!!.pqlExpression)
        assertEquals(Scope.EVENT, q.columnAliases["_grouped_event_value_"]!!.scope)
        assertTrue("count_trace_concept_name" !in q.columnAliases, q.columnAliases.toString())
    }

    @Test
    fun `hoisted event GROUP BY ordered by grouped event attribute sorts events before variant collection`() {
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val hoistedName = hoistedAttr(base = Scope.EVENT, effective = Scope.TRACE, xesName = "concept:name")

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(eventName, alias = "e_concept_name", scope = Scope.EVENT)),
                groupBy = GroupBySpec(keys = listOf(hoistedName), hasAggregation = true),
                orderBy = listOf(PqlQuery.OrderKey(eventName, OrderDirection.ASC)),
            ),
        )

        val c = q.cypher
        assertTrue(
            c.contains("ORDER BY _trace_variant_log_id_, trace.importOrder, event.activity ASC, event.importOrder"),
            "expected event attribute ordering before trace variant collection: $c",
        )
        assertTrue(c.contains("MATCH (log:Log {logId: _trace_variant_log_id_})"), c)
        assertTrue(
            c.contains("ORDER BY _trace_group_order_, _event_idx_"),
            "expected final variant ordering by grouped trace order: $c",
        )
    }

    @Test
    fun `hoisted event GROUP BY supports variant aggregate projection and order`() {
        val eventName = hoistedAttr(base = Scope.EVENT, effective = Scope.TRACE, xesName = "concept:name")
        val eventTimestamp = stdAttr(Scope.EVENT, "time:timestamp", Type.DATETIME)
        val hoistedTimestamp = hoistedAttr(base = Scope.EVENT, effective = Scope.TRACE, xesName = "time:timestamp", Type.DATETIME)
        val minTimestamp = PqlExpression.Aggregation("min", eventTimestamp, type = Type.DATETIME, location = loc)
        val minHoistedTimestamp = PqlExpression.Aggregation("min", hoistedTimestamp, type = Type.DATETIME, location = loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(minTimestamp, alias = "min_timestamp", scope = Scope.EVENT)),
                groupBy = GroupBySpec(keys = listOf(eventName), hasAggregation = true),
                orderBy = listOf(PqlQuery.OrderKey(minHoistedTimestamp, OrderDirection.ASC)),
            ),
        )

        val c = q.cypher

        assertTrue(c.contains("_group_event_event.activity AS _group_value"), c)
        assertTrue(c.contains("collect(event) AS _event_members"), c)
        assertTrue(c.contains("min(_value) AS _scope_agg_"), c)
        assertTrue(c.contains("min(_value) AS _scope_order_"), c)
        assertTrue(c.contains("AS min_timestamp"), c)
        assertTrue(c.contains("_scope_order_1 ASC"), c)

    }

    @Test
    fun `hoisted classifier GROUP BY renders classifier keys as trace variant value`() {
        val classifier = PqlExpression.Attribute(
            name = "classifier:concept:name+lifecycle:transition",
            baseScope = Scope.EVENT,
            effectiveScope = Scope.TRACE,
            kind = AttributeKind.CLASSIFIER,
            xesStandardName = null,
            wasBracketed = true,
            classifierName = "concept:name+lifecycle:transition",
            classifierKeys = listOf("concept:name", "lifecycle:transition"),
            type = Type.STRING,
            location = loc,
        )
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val lifecycle = stdAttr(Scope.EVENT, "lifecycle:transition")

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(eventName, alias = "e_concept_name", scope = Scope.EVENT),
                    ProjectedColumn(lifecycle, alias = "e_lifecycle_transition", scope = Scope.EVENT),
                ),
                groupBy = GroupBySpec(keys = listOf(classifier), hasAggregation = false),
            ),
        )

        val c = q.cypher
        assertTrue(c.contains("coalesce(toString(event.activity), '') + coalesce(toString(event.lifecycle), '')"), c)
        assertTrue(c.contains("event.activity IS NOT NULL"), c)
        assertTrue(c.contains("event.lifecycle IS NOT NULL"), c)
        assertTrue(c.contains("collect(event.activity) AS _selected_values_0"), c)
        assertTrue(c.contains("collect(event.lifecycle) AS _selected_values_1"), c)
        assertTrue(!c.contains("count_trace_concept_name"), c)
    }

    @Test
    fun `temporal difference returns ProcessM-compatible days`() {
        val ts = stdAttr(Scope.EVENT, "time:timestamp", Type.DATETIME)
        val maxTs = PqlExpression.Aggregation("max", ts, type = Type.DATETIME, location = loc)
        val minTs = PqlExpression.Aggregation("min", ts, type = Type.DATETIME, location = loc)
        val diff = PqlExpression.Binary(BinaryOperator.MINUS, maxTs, minTs, Type.NUMBER, loc)
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(diff, alias = "duration", scope = Scope.EVENT)),
                groupBy = GroupBySpec(keys = emptyList(), hasAggregation = true),
            ),
        )
        assertTrue(q.cypher.contains("toFloat(duration.inSeconds("), q.cypher)
        assertTrue(q.cypher.contains(") / 86400.0"), q.cypher)
    }

    @Test
    fun `trace-scope GROUP BY uses group key as synthetic trace identity`() {
        val traceName = stdAttr(Scope.TRACE, "concept:name")
        val hoistedTs = hoistedAttr(Scope.EVENT, Scope.TRACE, "time:timestamp", Type.DATETIME)
        val maxTs = PqlExpression.Aggregation("max", hoistedTs, type = Type.DATETIME, location = loc)
        val minTs = PqlExpression.Aggregation("min", hoistedTs, type = Type.DATETIME, location = loc)
        val duration = PqlExpression.Binary(BinaryOperator.MINUS, maxTs, minTs, Type.NUMBER, loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(duration, alias = "duration", scope = Scope.TRACE)),
                groupBy = GroupBySpec(keys = listOf(traceName), hasAggregation = true),
                orderBy = listOf(PqlQuery.OrderKey(duration, OrderDirection.DESC)),
                materializedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
            ),
        )

        val c = q.cypher
        assertTrue(c.contains("trace.caseId AS _trace_group_0"), c)
        assertTrue(c.contains("collect(trace) AS _trace_members"), c)
        assertTrue(c.contains("toFloat(duration.inSeconds(_scope_agg_1, _scope_agg_0).seconds) / 86400.0 AS duration"), c)
        assertTrue(c.contains("[member IN coalesce(_trace_members, []) | member.traceId] AS _trace_id_"), c)
        assertEquals(Scope.TRACE, q.columnAliases["_trace_id_"]!!.scope)
        assertTrue(q.columnAliases["_trace_id_"]!!.synthetic)
        assertEquals(Scope.TRACE, q.columnAliases["_null_event_count_"]!!.scope)
        assertTrue(q.columnAliases["_null_event_count_"]!!.synthetic)

    }

    @Test
    fun `trace-scope GROUP BY without explicit order keeps trace source order`() {
        val traceName = stdAttr(Scope.TRACE, "concept:name")
        val hoistedTs = hoistedAttr(Scope.EVENT, Scope.TRACE, "time:timestamp", Type.DATETIME)
        val maxTs = PqlExpression.Aggregation("max", hoistedTs, type = Type.DATETIME, location = loc)
        val minTs = PqlExpression.Aggregation("min", hoistedTs, type = Type.DATETIME, location = loc)
        val duration = PqlExpression.Binary(BinaryOperator.MINUS, maxTs, minTs, Type.NUMBER, loc)

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(traceName, alias = "trace_name", scope = Scope.TRACE),
                    ProjectedColumn(duration, alias = "duration", scope = Scope.TRACE),
                ),
                groupBy = GroupBySpec(keys = listOf(traceName), hasAggregation = true),
                limits = HierarchicalLimits(log = 1, trace = 10),
                materializedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
            ),
        )

        val c = q.cypher
        assertTrue(c.contains("ORDER BY trace.importOrder, trace.traceId"), c)
        assertTrue(c.contains("min(_position) AS _trace_position"), c)
        assertTrue(c.contains("ORDER BY _log_order_, _log_id_, _trace_order_"), c)
    }

    @Test
    fun `compiled trace aggregate query keeps compact row shape under full materialization`() {
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        val plan = compiler.compile(
            "select max(^e:timestamp)-min(^e:timestamp) where l:logId='Hospital-test' group by t:name order by max(^e:timestamp)-min(^e:timestamp) desc",
            logId = "Hospital-test",
        ) as LogicalPlan.Select

        val q = codegen.generate(plan.copy(materializedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT)))
        val c = q.cypher

        assertTrue(c.contains("_log_id_"), c)
        assertTrue(c.contains("_log_meta_"), c)
        assertTrue(c.contains("trace.caseId AS _trace_group_0"), c)
        assertTrue(c.contains("max(_value) AS _scope_agg_0"), c)
        assertTrue(c.contains("min(_value) AS _scope_agg_1"), c)
        assertTrue(c.contains("toFloat(duration.inSeconds(_scope_agg_1, _scope_agg_0).seconds) / 86400.0 AS col_0"), c)
        assertTrue(c.contains("duration.inSeconds(_scope_order_3, _scope_order_2)"), c)
        assertFalse(c.contains("RETURN log, trace, event"), c)
        assertFalse(c.contains("aggregateTracePrefix"), c)

    }

    @Test
    fun `event group by keeps trace scoped hoisted aggregations independent from event groups`() {
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        val plan = compiler.compile(
            "select l:name, t:name, max(^e:timestamp)-min(^e:timestamp), e:name, count(e:name) " +
                "where l:logId='Journal-test' group by t:name, e:name",
            logId = "Journal-test",
        ) as LogicalPlan.Select

        val q = codegen.generate(plan.copy(materializedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT)))
        val c = q.cypher

        val traceAggregate = c.indexOf("max(_value) AS _scope_agg_0")
        val eventGrouping = c.indexOf("event.activity AS _event_group_0")
        assertTrue(traceAggregate >= 0 && eventGrouping > traceAggregate, c)
        assertTrue(c.contains("UNWIND coalesce(_trace_members, []) AS _member"), c)
        assertTrue(c.contains("UNWIND coalesce(_event_members, []) AS _member"), c)
        assertTrue(c.contains("toFloat(duration.inSeconds(_scope_agg_1, _scope_agg_0).seconds) / 86400.0 AS col_2"), c)
        assertTrue(c.contains("ORDER BY _log_order_, _log_id_, _trace_order_, _event_group_order_"), c)

    }

    @Test
    fun `sum null guard wraps sum in CASE WHEN count = 0 null ELSE sum`() {
        val cost = stdAttr(Scope.EVENT, "cost:total", Type.NUMBER)
        val sumCost = PqlExpression.Aggregation("sum", cost, type = Type.NUMBER, location = loc)
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(sumCost, alias = "total_cost", scope = Scope.EVENT)),
                groupBy = GroupBySpec(keys = emptyList(), hasAggregation = true),
            ),
        )
        val c = q.cypher
        assertTrue(c.contains("CASE WHEN count("), "expected CASE WHEN count guard: $c")
        assertTrue(c.contains("THEN null ELSE sum("), "expected THEN null ELSE sum: $c")
    }

    // ---------- Task 27: ORDER BY + LIMIT/OFFSET + classifier null filter ----------

    @Test
    fun `simple ORDER BY emits ORDER BY clause with ASC`() {
        val ts = stdAttr(Scope.EVENT, "time:timestamp", Type.DATETIME)
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(ts, alias = "ts", scope = Scope.EVENT)),
                orderBy = listOf(PqlQuery.OrderKey(ts, OrderDirection.ASC)),
            ),
        )
        assertTrue(q.cypher.contains("ORDER BY log.logId, trace.importOrder, event.timestamp ASC"), q.cypher)
    }

    @Test
    fun `ORDER BY DESC emits DESC direction`() {
        val name = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(name, alias = "n", scope = Scope.EVENT)),
                orderBy = listOf(PqlQuery.OrderKey(name, OrderDirection.DESC)),
            ),
        )
        assertTrue(q.cypher.contains("ORDER BY log.logId, trace.importOrder, event.activity DESC"), q.cypher)
    }

    @Test
    fun `event ORDER BY preserves hierarchy before sorting events`() {
        val name = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(name, alias = "n", scope = Scope.EVENT)),
                orderBy = listOf(PqlQuery.OrderKey(name, OrderDirection.ASC)),
            ),
        )

        assertTrue(q.cypher.contains("ORDER BY log.logId, trace.importOrder, event.activity ASC"), q.cypher)
    }

    @Test
    fun `mixed trace and event ORDER BY applies keys at their hierarchy scope`() {
        val traceTotal = stdAttr(Scope.TRACE, "cost:total", Type.NUMBER)
        val eventTimestamp = stdAttr(Scope.EVENT, "time:timestamp", Type.DATETIME)
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(eventTimestamp, alias = "ts", scope = Scope.EVENT)),
                orderBy =
                    listOf(
                        PqlQuery.OrderKey(traceTotal, OrderDirection.DESC),
                        PqlQuery.OrderKey(eventTimestamp, OrderDirection.ASC),
                    ),
            ),
        )

        assertTrue(q.cypher.contains("ORDER BY log.logId, trace.`cost:total` DESC, trace.importOrder, event.timestamp ASC"), q.cypher)
    }

    @Test
    fun `aggregate ORDER BY references the SELECT alias`() {
        val name = stdAttr(Scope.EVENT, "concept:name")
        val count = PqlExpression.Aggregation("count", name, type = Type.NUMBER, location = loc)
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(name, alias = "e_concept_name", scope = Scope.EVENT),
                    ProjectedColumn(count, alias = "cnt", scope = Scope.EVENT),
                ),
                groupBy = GroupBySpec(keys = listOf(name), hasAggregation = true),
                orderBy = listOf(PqlQuery.OrderKey(count, OrderDirection.DESC)),
            ),
        )
        val c = q.cypher
        // The aggregate must be ordered by its SELECT alias, not by the raw aggregate
        // expression — Cypher would otherwise re-evaluate the aggregate and complain.
        assertTrue(c.contains("ORDER BY _log_id_, _trace_order_, cnt DESC"), "expected ORDER BY cnt alias: $c")
    }

    @Test
    fun `classifier ORDER BY emits IS NOT NULL filter`() {
        // ORDER BY c:unknown — triggers IS NOT NULL so the query produces an empty
        // result rather than a Cypher error when the classifier doesn't exist.
        val classifier = PqlExpression.Attribute(
            name = "c:lifecycle", baseScope = Scope.EVENT, effectiveScope = Scope.EVENT,
            kind = AttributeKind.CLASSIFIER, xesStandardName = null,
            wasBracketed = false, type = Type.STRING, location = loc,
        )
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(classifier, alias = "cls", scope = Scope.EVENT)),
                orderBy = listOf(PqlQuery.OrderKey(classifier, OrderDirection.ASC)),
            ),
        )
        val c = q.cypher
        assertTrue(c.contains("IS NOT NULL"), "expected IS NOT NULL guard: $c")
        assertTrue(c.contains("c:lifecycle") || c.contains("`c:lifecycle`"), c)
    }

    @Test
    fun `hierarchical LIMIT is emitted as nested per-scope limits`() {
        // A single flat LIMIT would be wrong for hierarchical PQL, but the simple
        // read path can safely push the same windows into nested log/trace/event
        // subqueries before rows are materialized.
        val name = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(name, alias = "n", scope = Scope.EVENT)),
                limits = HierarchicalLimits(event = 10, trace = 5, log = 1),
            ),
        )
        assertTrue(q.cypher.contains("LIMIT \$logLimit"), q.cypher)
        assertTrue(q.cypher.contains("LIMIT \$traceLimit"), q.cypher)
        assertTrue(q.cypher.contains("LIMIT \$eventLimit"), q.cypher)
    }

    @Test
    fun `node-shaped logId query pushes trace and event limits into Cypher`() {
        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                limits = HierarchicalLimits(event = 1, trace = 1, log = 1),
                logId = "Hospital-test",
                used = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
            ),
        )

        assertTrue(q.cypher.contains("MATCH (log:Log {logId: \$logId})"), q.cypher)
        assertFalse(q.cypher.contains("UNION ALL"), q.cypher)
        assertTrue(q.cypher.contains("null AS log, properties(trace) AS trace, null AS event"), q.cypher)
        assertTrue(q.cypher.contains("collect(properties(event)) AS events"), q.cypher)
        assertTrue(q.hydrateLogProperties)
        assertTrue(!q.cypher.contains("RETURN log, trace, event"), q.cypher)
        assertTrue(q.cypher.contains("LIMIT \$traceLimit"), q.cypher)
        assertTrue(q.cypher.contains("LIMIT \$eventLimit"), q.cypher)
        assertTrue(
            q.cypher.contains(
                "OPTIONAL MATCH (event:Event {parentTraceId: trace.traceId})" +
                    " WHERE event.importOrder IS NOT NULL" +
                    " WITH event ORDER BY event.parentTraceId, event.importOrder LIMIT \$eventLimit",
            ),
            q.cypher,
        )
        assertEquals("Hospital-test", q.parameters["logId"])
        assertEquals(1L, q.parameters["traceLimit"])
        assertEquals(1L, q.parameters["eventLimit"])
        assertEquals(emptyMap<String, ColumnAlias>(), q.columnAliases)
    }

    @Test
    fun `node-shaped dataStore query keeps data store match when pushing limits into Cypher`() {
        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                limits = HierarchicalLimits(event = 1, trace = 1, log = 1),
                dataStoreId = "store-42",
                used = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
            ),
        )

        assertTrue(
            q.cypher.contains("MATCH (:DataStore {dataStoreId: \$dataStoreId})-[:CONTAINS_LOG]->(log:Log)"),
            q.cypher,
        )
        assertEquals("store-42", q.parameters["dataStoreId"])
        assertEquals(1L, q.parameters["traceLimit"])
        assertEquals(1L, q.parameters["eventLimit"])
        assertEquals(emptyMap<String, ColumnAlias>(), q.columnAliases)
    }

    @Test
    fun `node-shaped query pushes default trace limit into Cypher`() {
        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                limits = HierarchicalLimits(log = 1),
                logId = "Hospital-test",
                used = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
            ).copy(defaultLimits = HierarchicalLimits(trace = 30)),
        )

        assertTrue(q.cypher.contains("LIMIT \$traceLimit"), q.cypher)
        assertEquals(30L, q.parameters["traceLimit"])
    }

    @Test
    fun `node-shaped trace filter applies before pushed trace limit`() {
        val diagnosis = customAttr(Scope.TRACE, "Diagnosis")
        val filter = PqlExpression.Binary(
            op = BinaryOperator.IS_NOT,
            left = diagnosis,
            right = PqlExpression.Literal(rawText = "null", kind = PqlExpression.LiteralKind.NULL, value = null, type = Type.UNKNOWN, location = loc),
            type = Type.BOOLEAN,
            location = loc,
        )

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                filter = filter,
                limits = HierarchicalLimits(trace = 1),
                logId = "Hospital-test",
            ),
        )

        assertTrue(q.cypher.contains("WHERE trace.Diagnosis IS NOT null"), q.cypher)
        assertTrue(q.cypher.contains("LIMIT \$traceLimit"), q.cypher)
        assertTrue(q.cypher.indexOf("WHERE trace.Diagnosis IS NOT null") < q.cypher.indexOf("LIMIT \$traceLimit"), q.cypher)
    }

    @Test
    fun `simple projected hierarchy pushes per-scope limits before returning rows`() {
        val logName = stdAttr(Scope.LOG, "concept:name")
        val traceName = stdAttr(Scope.TRACE, "concept:name")
        val eventName = stdAttr(Scope.EVENT, "concept:name")

        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(
                    ProjectedColumn(logName, alias = "l_concept_name", scope = Scope.LOG),
                    ProjectedColumn(traceName, alias = "t_concept_name", scope = Scope.TRACE),
                    ProjectedColumn(eventName, alias = "e_concept_name", scope = Scope.EVENT),
                ),
                limits = HierarchicalLimits(log = 1, trace = 2, event = 3),
                dataStoreId = "store-42",
            ),
        )

        assertTrue(q.cypher.contains("LIMIT \$logLimit"), q.cypher)
        assertTrue(q.cypher.contains("LIMIT \$traceLimit"), q.cypher)
        assertTrue(q.cypher.contains("LIMIT \$eventLimit"), q.cypher)
        assertTrue(q.cypher.contains("RETURN log.logId AS _log_id_"), q.cypher)
        assertEquals(1L, q.parameters["logLimit"])
        assertEquals(2L, q.parameters["traceLimit"])
        assertEquals(3L, q.parameters["eventLimit"])
    }

    @Test
    fun `node-shaped event order pushes event limit with requested order`() {
        val timestamp = stdAttr(Scope.EVENT, "time:timestamp", Type.DATETIME)

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                orderBy = listOf(PqlQuery.OrderKey(timestamp, OrderDirection.ASC)),
                limits = HierarchicalLimits(log = 1, trace = 3, event = 5),
                dataStoreId = "store-42",
            ),
        )

        assertTrue(q.cypher.contains("WITH event ORDER BY event.timestamp ASC, event.importOrder LIMIT \$eventLimit"), q.cypher)
        assertTrue(
            q.cypher.contains(
                "RETURN log.logId AS _logKey, trace, event ORDER BY " +
                    "log.createdAt, log.logId, trace.importOrder, event.timestamp ASC, event.importOrder",
            ),
            q.cypher,
        )
        assertEquals(1L, q.parameters["logLimit"])
        assertEquals(3L, q.parameters["traceLimit"])
        assertEquals(5L, q.parameters["eventLimit"])
    }

    @Test
    fun `single-log anchor skips the child-order log ranking probe`() {
        val timestamp = stdAttr(Scope.EVENT, "time:timestamp", Type.DATETIME)

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                orderBy = listOf(PqlQuery.OrderKey(timestamp, OrderDirection.ASC)),
                limits = HierarchicalLimits(log = 1, trace = 3, event = 5),
                logId = "journal",
            ),
        )

        // Ranking a single bound log by child order keys would scan every event
        // for nothing — the anchor form must cap rows without the probe subquery.
        assertTrue(q.cypher.contains("MATCH (log:Log {logId: \$logId}) WITH log LIMIT \$logLimit"), q.cypher)
        assertTrue(!q.cypher.contains("_logOrder0"), q.cypher)
    }

    @Test
    fun `node-shaped event order keeps trace binding when only log limit is pushed`() {
        val timestamp = stdAttr(Scope.EVENT, "time:timestamp", Type.DATETIME)

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                orderBy = listOf(PqlQuery.OrderKey(timestamp, OrderDirection.ASC)),
                limits = HierarchicalLimits(log = 3),
                logId = "journal",
            ),
        )

        assertTrue(q.cypher.contains("WITH trace, event ORDER BY event.timestamp ASC, event.importOrder RETURN trace, event"), q.cypher)
        assertTrue(
            q.cypher.contains(
                "RETURN log.logId AS _logKey, trace, event ORDER BY " +
                    "log.logId, trace.importOrder, event.timestamp ASC, event.importOrder",
            ),
            q.cypher,
        )
        assertEquals(3L, q.parameters["logLimit"])
    }

    @Test
    fun `event filter is applied before pushed event and trace limits`() {
        val eventName = stdAttr(Scope.EVENT, "concept:name")
        val filter = PqlExpression.Binary(
            op = BinaryOperator.IS_NOT,
            left = eventName,
            right = PqlExpression.Literal(rawText = "null", kind = PqlExpression.LiteralKind.NULL, value = null, type = Type.UNKNOWN, location = loc),
            type = Type.BOOLEAN,
            location = loc,
        )

        val q = codegen.generate(
            selectPlanFull(
                columns = emptyList(),
                filter = filter,
                limits = HierarchicalLimits(trace = 2, event = 3),
                dataStoreId = "store-42",
            ),
        )

        assertTrue(q.cypher.contains("CALL (log, trace)"), q.cypher)
        assertTrue(q.cypher.contains("AND (event.activity IS NOT null)"), q.cypher)
        assertTrue(q.cypher.contains("WITH trace, events WHERE size(events) > 0"), q.cypher)
        assertTrue(q.cypher.indexOf("event.activity IS NOT null") < q.cypher.indexOf("LIMIT \$eventLimit"), q.cypher)
        assertTrue(
            q.cypher.indexOf("WITH event ORDER BY event.parentTraceId, event.importOrder LIMIT \$eventLimit") <
                q.cypher.lastIndexOf("LIMIT \$traceLimit"),
            q.cypher,
        )
    }

    @Test
    fun `hierarchical OFFSET is NOT emitted as Cypher SKIP`() {
        val name = stdAttr(Scope.EVENT, "concept:name")
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(name, alias = "n", scope = Scope.EVENT)),
                offsets = HierarchicalOffsets(event = 5, trace = 2, log = 1),
            ),
        )
        assertTrue(!q.cypher.contains(" SKIP "), "codegen must not emit SKIP: ${q.cypher}")
    }

    // ---------- Task 28: WHERE hoisting + DELETE ----------

    @Test
    fun `hoisted WHERE at trace context uses EXISTS subquery`() {
        // FROM trace ... WHERE ^e:name = 'Registration'
        // Trace-context query filtering by an event attribute lifted via ^ — needs
        // EXISTS { MATCH (trace)-[:HAS_EVENT]->(_hev:Event) WHERE _hev.activity = ... }.
        val hoistedName = hoistedAttr(base = Scope.EVENT, effective = Scope.TRACE, xesName = "concept:name")
        val caseId = stdAttr(Scope.TRACE, "concept:name")
        val filter = PqlExpression.Binary(
            op = BinaryOperator.EQ,
            left = hoistedName,
            right = PqlExpression.Literal(rawText = "Registration", kind = PqlExpression.LiteralKind.STRING, value = "Registration", type = Type.STRING, location = loc),
            type = Type.BOOLEAN, location = loc,
        )
        val q = codegen.generate(
            selectPlanFull(
                columns = listOf(ProjectedColumn(caseId, alias = "caseId", scope = Scope.TRACE)),
                filter = filter,
                from = Scope.TRACE,
                used = setOf(Scope.LOG, Scope.TRACE),
            ),
        )
        val c = q.cypher
        assertTrue(c.contains("EXISTS {"), "expected EXISTS subquery: $c")
        assertTrue(c.contains("(trace)-[:HAS_EVENT]->"), "expected (trace)-[:HAS_EVENT]-> subquery pattern: $c")
        assertTrue(c.contains(".activity = \$param0"), "expected _hev.activity binding: $c")
        assertEquals("Registration", q.parameters["param0"])
    }

    @Test
    fun `DELETE FROM event emits bounded target selection`() {
        val name = stdAttr(Scope.EVENT, "concept:name")
        val filter = PqlExpression.Binary(
            op = BinaryOperator.EQ,
            left = name,
            right = PqlExpression.Literal(rawText = "Trash", kind = PqlExpression.LiteralKind.STRING, value = "Trash", type = Type.STRING, location = loc),
            type = Type.BOOLEAN, location = loc,
        )
        val plan = LogicalPlan.Delete(
            source = LogicalSource(
                fromScope = Scope.EVENT,
                usedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
            ),
            filter = filter,
            target = Scope.EVENT,
            location = loc,
        )
        val q = codegen.generate(plan)
        val c = q.cypher
        assertTrue(c.contains("MATCH (log:Log)"), c)
        assertTrue(c.contains("WHERE event.activity = \$param0"), c)
        assertTrue(c.contains("WITH DISTINCT event WHERE event IS NOT NULL"), c)
        assertTrue(c.contains("RETURN event.eventId AS _deleteId"), c)
        assertTrue(c.contains("LIMIT \$_deleteBatchSize"), c)
        assertEquals(1000, q.parameters["_deleteBatchSize"])
        assertEquals("Trash", q.parameters["param0"])
    }

    @Test
    fun `DELETE without filter emits unfiltered bounded target selection`() {
        val plan = LogicalPlan.Delete(
            source = LogicalSource(
                fromScope = Scope.EVENT,
                usedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
            ),
            filter = null,
            target = Scope.EVENT,
            location = loc,
        )
        val q = codegen.generate(plan)
        val c = q.cypher
        assertTrue(c.contains("RETURN event.eventId AS _deleteId"), c)
        assertTrue(c.contains("LIMIT \$_deleteBatchSize"), c)
        assertTrue(c.contains("WHERE event IS NOT NULL"), c)
        assertTrue(!c.contains("WHERE event.activity"), c)
    }

    @Test
    fun `parameter names are unique per binding`() {
        val a = stdAttr(Scope.EVENT, "concept:name")
        val eq1 = PqlExpression.Binary(BinaryOperator.EQ, a, PqlExpression.Literal(rawText = "A", kind = PqlExpression.LiteralKind.STRING, value = "A", type = Type.STRING, location = loc), Type.BOOLEAN, loc)
        val eq2 = PqlExpression.Binary(BinaryOperator.EQ, a, PqlExpression.Literal(rawText = "B", kind = PqlExpression.LiteralKind.STRING, value = "B", type = Type.STRING, location = loc), Type.BOOLEAN, loc)
        val and = PqlExpression.Binary(BinaryOperator.AND, eq1, eq2, Type.BOOLEAN, loc)
        val q = codegen.generate(
            selectPlan(listOf(ProjectedColumn(a, alias = "concept_name", scope = Scope.EVENT)), filter = and),
        )
        assertEquals(2, q.parameters.size)
        assertTrue(q.parameters.keys.containsAll(setOf("param0", "param1")))
    }

    @Test
    fun `now function is bound once per generated query`() {
        val now = PqlExpression.Call("now", emptyList(), scope = Scope.LOG, type = Type.DATETIME, location = loc)

        val q = codegen.generate(
            selectPlan(
                columns = listOf(
                    ProjectedColumn(now, alias = "now_1", scope = Scope.LOG),
                    ProjectedColumn(now, alias = "now_2", scope = Scope.LOG),
                ),
                from = Scope.LOG,
                used = setOf(Scope.LOG),
            ),
        )

        assertTrue(q.cypher.contains("\$param0 AS now_1"), q.cypher)
        assertTrue(q.cypher.contains("\$param0 AS now_2"), q.cypher)
        assertEquals(1, q.parameters.size)
        assertTrue(q.parameters["param0"] is ZonedDateTime)
    }

    @Test
    fun `hoisted log aggregate chooses logs before reading all descendant values`() {
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        val plan = compiler.compile(
            "select count(^t:name), sum(^^e:total), avg(^^e:total) where e:name = 'hit'",
            logId = null,
        )
        val generated = codegen.generate(plan)
        val cypher = generated.cypher
        assertTrue(cypher.contains("collect(log) AS _log_members"), cypher)
        assertTrue(cypher.contains("WHERE EXISTS { WITH log"), cypher)
        assertTrue(cypher.contains("UNWIND coalesce(_log_members, []) AS _member"), cypher)
        assertTrue(cypher.contains("WITH _argument_event_log AS _component"), cypher)
        assertTrue(cypher.contains("avg(_value)"), cypher)
        assertFalse(cypher.contains("sum(DISTINCT"), cypher)
    }

    @Test
    fun `scope membership keeps normal and hoisted event paths separate`() {
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        val plan = compiler.compile(
            "select count(^e:name) where e:name = 'A' and ^e:name = 'B'",
            logId = "log-1",
        )
        val cypher = codegen.generate(plan).cypher
        assertTrue(cypher.contains("_filter_event_event"), cypher)
        assertTrue(cypher.contains("_filter_event_trace"), cypher)
        assertTrue(cypher.contains("OPTIONAL MATCH"), cypher)
        assertTrue(cypher.contains("WITH * WHERE"), cypher)
    }

    @Test
    fun `trace aggregate keeps complete membership before limiting placeholder rows`() {
        val compiler = PqlCompiler(AntlrPqlParser(), EmptyLogRepository, EmptyDataStoreRepository)
        val plan = compiler.compile(
            "select count(t:name), sum(^e:total) where ^e:name = 'A' limit t:1, e:2",
            logId = "log-1",
        )
        val generated = codegen.generate(plan)
        val cypher = generated.cypher
        val memberCollection = cypher.indexOf("collect(trace) AS _trace_members")
        val traceLimit = cypher.indexOf("LIMIT ${'$'}aggregateTracePrefix")
        assertTrue(memberCollection >= 0 && traceLimit > memberCollection, cypher)
        assertTrue(cypher.contains("coalesce(max(_event_count), 0) AS _null_event_count_"), cypher)
        assertEquals(1L, generated.parameters["aggregateTracePrefix"])
        assertEquals(2L, generated.parameters["aggregateEventPrefix"])
    }

    private object EmptyLogRepository : LogRepository {
        override fun save(log: Log): Log = log
        override fun findById(id: String): Log? = null
        override fun findAll(): List<Log> = emptyList()
        override fun search(namePart: String): List<Log> = emptyList()
        override fun findByAttribute(key: String, value: Any): List<Log> = emptyList()
        override fun findCreatedAfter(date: LocalDateTime): List<Log> = emptyList()
        override fun findCreatedBetween(start: LocalDateTime, end: LocalDateTime): List<Log> = emptyList()
        override fun getStatistics(id: String): LogStatistics? = null
        override fun getStatisticsAll(): List<Pair<Log, LogStatistics>> = emptyList()
        override fun update(log: Log): Log = log
        override fun delete(id: String): Boolean = false
        override fun deleteWithData(id: String): Boolean = false
        override fun exists(id: String): Boolean = false
    }

    private object EmptyDataStoreRepository : DataStoreRepository {
        override fun save(dataStore: DataStore): DataStore = dataStore
        override fun findById(id: String): DataStore? = null
        override fun findAll(): List<DataStore> = emptyList()
        override fun findLogSummaries(dataStoreId: String): List<DataStoreLogSummary> = emptyList()
        override fun update(dataStore: DataStore): DataStore = dataStore
        override fun deleteWithLogs(id: String): Boolean = false
        override fun exists(id: String): Boolean = false
        override fun attachLog(dataStoreId: String, logId: String) = Unit
    }
}
