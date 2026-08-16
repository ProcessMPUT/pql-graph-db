package com.processm.processminterpreter.pql

import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.Log
import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.plan.CandidateLogPlan
import com.processm.processminterpreter.pql.plan.LogicalPlan
import com.processm.processminterpreter.xes.DataStoreLogSummary
import com.processm.processminterpreter.neo4j.query.DeleteExecutionResult
import com.processm.processminterpreter.neo4j.query.ExecutionOptions
import com.processm.processminterpreter.neo4j.query.QueryExecutionResult
import com.processm.processminterpreter.pql.parser.AntlrPqlParser
import com.processm.processminterpreter.pql.parser.AstBuilder
import com.processm.processminterpreter.xes.io.OpenXesWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Uses the real [AntlrPqlParser] (pure, no I/O) plus hand-rolled recording fakes
 * for the executor and repositories — Kotlin non-null parameter types plus
 * Mockito's `any(Class)` matcher fight each other ugly. The executor fake
 * subclasses [StubQueryPlanExecutor] (shared in QueryTestFakes.kt) with a
 * never-touched mocked driver: it records invocations and returns canned results;
 * the shared [FakeLogRepository] returns canned classifier metadata.
 */
class PqlQueryServiceExecuteTest {

    private val parser = AntlrPqlParser(AstBuilder())

    // ----- fakes -----

    private class FakeExecutor(
        var selectResult: QueryExecutionResult = QueryExecutionResult(emptyList(), emptyList(), 0, ""),
        var deleteResult: DeleteExecutionResult = DeleteExecutionResult(0, ""),
        var matchingLogIds: List<String> = emptyList(),
    ) : StubQueryPlanExecutor() {
        // Recorders are synchronized: multi-log fan-out invokes execute() concurrently.
        val selectCalls: MutableList<Pair<LogicalPlan.Select, ExecutionOptions>> =
            java.util.Collections.synchronizedList(mutableListOf())
        val candidateLogCalls: MutableList<CandidateLogPlan> =
            java.util.Collections.synchronizedList(mutableListOf())
        val deleteCalls: MutableList<LogicalPlan.Delete> =
            java.util.Collections.synchronizedList(mutableListOf())
        override fun execute(plan: LogicalPlan.Select, options: ExecutionOptions): QueryExecutionResult {
            selectCalls += plan to options
            return selectResult
        }
        override fun findMatchingLogIds(plan: CandidateLogPlan): List<String> {
            candidateLogCalls += plan
            return matchingLogIds
        }
        override fun executeDelete(plan: LogicalPlan.Delete): DeleteExecutionResult {
            deleteCalls += plan
            return deleteResult
        }
    }

    // ----- fixtures -----

    private fun sampleLog(id: String, classifiers: List<Classifier>) = Log(
        id = id,
        name = "sample",
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now(),
        classifiers = classifiers,
    )

    private fun sampleXesLog(name: String = "x") = XesLog(
        conceptName = name,
        traces = listOf(XesTrace(conceptName = "t", events = listOf(XesEvent(conceptName = "e")))),
    )

    // ----- tests -----

    @Test
    fun `execute runs the pipeline and wraps SELECT executor result in QueryResult`() {
        val executor = FakeExecutor(
            selectResult = QueryExecutionResult(
                logs = listOf(sampleXesLog("hit")),
                rows = emptyList(),
                rowCount = 7,
                executedQueryDescription = "MATCH (e:Event) RETURN e.name",
            ),
        )
        val useCase = PqlQueryService(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()), executor, OpenXesWriter())

        val result = useCase.execute(
            ExecutePqlQueryRequest(
                query = "select e:name",
                logId = null,
                defaultLimits = HierarchicalLimits(trace = 30),
            ),
        )

        assertEquals(7, result.rowCount)
        assertEquals(1, result.logs.size)
        assertEquals("hit", result.logs[0].conceptName)
        assertTrue(result.executedQueryDescription.contains("MATCH"))

        // Planner produced a SELECT plan with the right FROM scope, and the use case
        // forwarded the ProcessM-style default limits through ExecutionOptions.
        assertEquals(1, executor.selectCalls.size)
        val (plan, options) = executor.selectCalls.single()
        assertEquals(Scope.EVENT, plan.source.fromScope)
        assertEquals(HierarchicalLimits(trace = 30), options.defaultLimits)
        assertTrue(executor.deleteCalls.isEmpty(), "executeDelete should not be called for SELECT")
    }

    @Test
    fun `single-log datastore is compiled as direct log source`() {
        val logs = FakeLogRepository(
            byId = mapOf("log-1" to sampleLog("log-1", classifiers = emptyList())),
        )
        val dataStores = FakeDataStoreRepository(
            logsByDataStoreId = mapOf(
                "store-1" to listOf(
                    DataStoreLogSummary(
                        logId = "log-1",
                        name = "sample",
                        createdAt = null,
                        updatedAt = null,
                    ),
                ),
            ),
        )
        val executor = FakeExecutor()
        val useCase = PqlQueryService(PqlCompiler(parser, logs, dataStores), executor, OpenXesWriter())

        useCase.execute(ExecutePqlQueryRequest(query = "select e:name", dataStoreId = "store-1"))

        val plan = executor.selectCalls.single().first
        assertEquals("log-1", plan.source.logId)
        assertNull(plan.source.dataStoreId)
        assertTrue(dataStores.existsCalls.isEmpty(), "non-empty datastore should be resolved in one repository call")
    }

    @Test
    fun `execute routes DELETE plans through executeDelete and returns empty logs`() {
        val executor = FakeExecutor(
            deleteResult = DeleteExecutionResult(
                nodesDeleted = 42,
                executedQueryDescription = "MATCH (e:Event) DETACH DELETE e",
            ),
        )
        val useCase = PqlQueryService(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()), executor, OpenXesWriter())

        val result = useCase.execute(ExecutePqlQueryRequest(query = "delete event"))

        assertTrue(result.logs.isEmpty())
        assertEquals(42, result.rowCount)
        assertTrue(result.executedQueryDescription.contains("DETACH DELETE"))
        assertTrue(executor.selectCalls.isEmpty(), "execute should not be called for DELETE")
        assertEquals(1, executor.deleteCalls.size)
    }

    @Test
    fun `execute exposes projection metadata needed by ProcessM formatting`() {
        val executor = FakeExecutor()
        val useCase = PqlQueryService(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()), executor, OpenXesWriter())

        val result = useCase.execute(ExecutePqlQueryRequest(query = "select t:total, t:*"))

        assertEquals(setOf(Scope.TRACE), result.selectAllScopes)
        assertEquals(setOf(StandardAttributeCatalog.COST_TOTAL), result.projectedTraceStandardAttributes)
    }

    @Test
    fun `non-classifier query carries logId without loading log metadata`() {
        val classifiers = listOf(Classifier("Event Name", listOf("concept:name")))
        val executor = FakeExecutor()
        val repo = FakeLogRepository(byId = mapOf("log-1" to sampleLog("log-1", classifiers)))
        val useCase = PqlQueryService(PqlCompiler(parser, repo, FakeDataStoreRepository()), executor, OpenXesWriter())

        useCase.execute(ExecutePqlQueryRequest(query = "select e:name", logId = "log-1"))

        assertTrue(repo.findByIdCalls.isEmpty(), "plain queries should not load classifier metadata")
        // Plan must carry the logId down into the LogicalSource.
        assertEquals("log-1", executor.selectCalls.single().first.source.logId)
    }

    @Test
    fun `buildContext skips the repository when logId is null`() {
        val executor = FakeExecutor()
        val repo = FakeLogRepository()
        val useCase = PqlQueryService(PqlCompiler(parser, repo, FakeDataStoreRepository()), executor, OpenXesWriter())

        useCase.execute(ExecutePqlQueryRequest(query = "select e:name", logId = null))

        assertTrue(repo.findByIdCalls.isEmpty(), "findById should not be hit when logId is null")
        assertNull(executor.selectCalls.single().first.source.logId)
    }

    @Test
    fun `plain query does not require the log to exist during compilation`() {
        val executor = FakeExecutor(
            selectResult = QueryExecutionResult(listOf(sampleXesLog()), emptyList(), 0, ""),
        )
        val repo = FakeLogRepository(byId = emptyMap())
        val useCase = PqlQueryService(PqlCompiler(parser, repo, FakeDataStoreRepository()), executor, OpenXesWriter())

        val result = useCase.execute(ExecutePqlQueryRequest(query = "select e:name", logId = "nope"))
        assertEquals(1, result.logs.size)
        assertTrue(repo.findByIdCalls.isEmpty(), "plain queries should not load classifier metadata")
        assertEquals("nope", executor.selectCalls.single().first.source.logId)
    }
    @Test
    fun `buildContext loads classifier metadata for logs attached to a data store`() {
        val executor = FakeExecutor()
        val repo = FakeLogRepository(
            byId = mapOf(
                "journal" to sampleLog("journal", listOf(Classifier("Resource", listOf("org:resource")))),
            ),
        )
        val dataStores = FakeDataStoreRepository(
            logsByDataStoreId = mapOf(
                "store-1" to listOf(DataStoreLogSummary("journal", "JournalReview", null, null)),
            ),
        )
        val useCase = PqlQueryService(PqlCompiler(parser, repo, dataStores), executor, OpenXesWriter())

        useCase.execute(ExecutePqlQueryRequest(query = "select c:Resource", dataStoreId = "store-1"))

        assertEquals(listOf("journal"), repo.findByIdCalls)
        val attr = executor.selectCalls.single().first.projection.columns.single().expression
            as com.processm.processminterpreter.pql.ast.PqlExpression.Attribute
        assertEquals(listOf("org:resource"), attr.classifierKeys)
    }

    @Test
    fun `classifier queries spanning multiple logs compile one plan per log with local classifier metadata`() {
        val repo = FakeLogRepository(
            byId = mapOf(
                "a" to sampleLog("a", listOf(Classifier("Activity", listOf("concept:name")))),
                "b" to sampleLog("b", listOf(Classifier("Activity", listOf("concept:name", "lifecycle:transition")))),
            ),
        )
        val dataStores = FakeDataStoreRepository(
            logsByDataStoreId = mapOf(
                "store-1" to listOf(
                    DataStoreLogSummary("a", "A", null, null),
                    DataStoreLogSummary("b", "B", null, null),
                ),
            ),
        )
        val executor = object : StubQueryPlanExecutor() {
            val selectCalls: MutableList<LogicalPlan.Select> =
                java.util.Collections.synchronizedList(mutableListOf())

            override fun execute(plan: LogicalPlan.Select, options: ExecutionOptions): QueryExecutionResult {
                selectCalls += plan
                val planLogId = requireNotNull(plan.source.logId)
                return QueryExecutionResult(
                    logs = listOf(sampleXesLog(planLogId)),
                    rows = emptyList(),
                    rowCount = 1,
                    executedQueryDescription = planLogId,
                )
            }

            override fun findMatchingLogIds(plan: CandidateLogPlan): List<String> = listOf("a", "b")

            override fun executeDelete(plan: LogicalPlan.Delete): DeleteExecutionResult =
                DeleteExecutionResult(0, "")
        }
        val useCase = PqlQueryService(PqlCompiler(parser, repo, dataStores), executor, OpenXesWriter())

        val result = useCase.execute(
            ExecutePqlQueryRequest(
                query = "select c:Activity limit l:1 offset l:1",
                dataStoreId = "store-1",
            ),
        )

        // Fan-out runs concurrently: execution (recording) order is nondeterministic,
        // so recorder assertions sort by logId. RESULT order is guaranteed to follow
        // plan order — those assertions stay strict.
        val callsByLog = executor.selectCalls.sortedBy { it.source.logId }
        assertEquals(listOf("a", "b"), callsByLog.map { it.source.logId })
        assertEquals(
            listOf(
                listOf("concept:name"),
                listOf("concept:name", "lifecycle:transition"),
            ),
            callsByLog.map { call ->
                call.projection.columns.map { column ->
                    val attr = column.expression
                        as com.processm.processminterpreter.pql.ast.PqlExpression.Attribute
                    attr.xesStandardName ?: attr.name
                }
            },
        )
        assertEquals(listOf("b"), result.logs.mapNotNull { it.conceptName })
        assertEquals(2, result.rowCount)
        assertEquals("a\n-- per-log --\nb", result.executedQueryDescription)
        assertEquals(
            listOf("a", "b"),
            repo.findByIdCalls.sorted(),
            "classifier metadata loaded during preparation must be reused for per-log compilation",
        )
        assertEquals(
            listOf(null, null),
            callsByLog.map { it.limits.log },
            "log limit must be applied once after merging, not once per per-log plan",
        )
        assertEquals(
            listOf(null, null),
            callsByLog.map { it.offsets.log },
            "log offset must be applied once after merging, not once per per-log plan",
        )
    }

    @Test
    fun `classifier fan-out only specializes logs that pass the shared where filter`() {
        val repo = FakeLogRepository(
            byId = mapOf(
                "a" to sampleLog("a", classifiers = emptyList()),
                "b" to sampleLog("b", listOf(Classifier("Activity", listOf("concept:name")))),
            ),
        )
        val dataStores = FakeDataStoreRepository(
            logsByDataStoreId = mapOf(
                "store-1" to listOf(
                    DataStoreLogSummary("a", "A", null, null),
                    DataStoreLogSummary("b", "B", null, null),
                ),
            ),
        )
        val executor = FakeExecutor(
            selectResult = QueryExecutionResult(listOf(sampleXesLog("b")), emptyList(), 1, "b"),
            matchingLogIds = listOf("b"),
        )
        val useCase = PqlQueryService(PqlCompiler(parser, repo, dataStores), executor, OpenXesWriter())

        val result = useCase.execute(
            ExecutePqlQueryRequest(
                query = "select c:Activity where l:name = 'B'",
                dataStoreId = "store-1",
            ),
        )

        assertEquals(1, executor.candidateLogCalls.size)
        assertEquals(listOf("b"), executor.selectCalls.map { it.first.source.logId })
        assertEquals(listOf("b"), result.logs.mapNotNull { it.conceptName })
        assertEquals(listOf("a", "b"), repo.findByIdCalls.sorted())
    }

    @Test
    fun `classifier candidate probe keeps only non-classifier order keys`() {
        val repo = FakeLogRepository(
            byId = mapOf(
                "a" to sampleLog("a", listOf(Classifier("Activity", listOf("concept:name")))),
                "b" to sampleLog("b", listOf(Classifier("Activity", listOf("concept:name")))),
            ),
        )
        val dataStores = FakeDataStoreRepository(
            logsByDataStoreId = mapOf(
                "store-1" to listOf(
                    DataStoreLogSummary("a", "A", null, null),
                    DataStoreLogSummary("b", "B", null, null),
                ),
            ),
        )
        val executor = FakeExecutor(matchingLogIds = listOf("a", "b"))
        val useCase = PqlQueryService(PqlCompiler(parser, repo, dataStores), executor, OpenXesWriter())

        useCase.execute(
            ExecutePqlQueryRequest(
                query = "select c:Activity order by l:name desc, c:Activity asc",
                dataStoreId = "store-1",
            ),
        )

        val candidateOrder = executor.candidateLogCalls.single().orderBy
        assertEquals(1, candidateOrder.size)
        val candidateAttribute = candidateOrder.single().expression
            as com.processm.processminterpreter.pql.ast.PqlExpression.Attribute
        assertEquals(Scope.LOG, candidateAttribute.effectiveScope)
        assertEquals("concept:name", candidateAttribute.xesStandardName)
    }
}
