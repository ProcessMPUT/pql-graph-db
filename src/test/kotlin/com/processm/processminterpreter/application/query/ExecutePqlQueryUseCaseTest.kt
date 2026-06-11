package com.processm.processminterpreter.application.query

import com.processm.processminterpreter.domain.datastore.DataStore
import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.log.Log
import com.processm.processminterpreter.domain.log.xes.XesEvent
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.log.xes.XesTrace
import com.processm.processminterpreter.domain.pql.syntax.RawAttributeRef
import com.processm.processminterpreter.domain.pql.syntax.RawBinaryOp
import com.processm.processminterpreter.domain.pql.syntax.RawLiteral
import com.processm.processminterpreter.domain.pql.syntax.RawLiteralKind
import com.processm.processminterpreter.domain.pql.syntax.RawOrderKey
import com.processm.processminterpreter.domain.pql.syntax.RawQuery
import com.processm.processminterpreter.domain.pql.syntax.RawSelectColumn
import com.processm.processminterpreter.domain.pql.catalog.OrderDirection
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.domain.pql.common.HierarchicalLimits
import com.processm.processminterpreter.domain.pql.plan.LogicalPlan
import com.processm.processminterpreter.application.ports.DataStoreLogSummary
import com.processm.processminterpreter.application.ports.DataStoreRepository
import com.processm.processminterpreter.application.ports.DeleteExecutionResult
import com.processm.processminterpreter.application.ports.ExecutionOptions
import com.processm.processminterpreter.application.ports.LogRepository
import com.processm.processminterpreter.application.ports.LogStatistics
import com.processm.processminterpreter.application.ports.PqlParser
import com.processm.processminterpreter.application.ports.QueryExecutionResult
import com.processm.processminterpreter.application.ports.QueryPlanExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Fakes instead of Mockito — Kotlin non-null parameter types plus Mockito's
 * `any(Class)` matcher fight each other ugly. Hand-rolled recorders keep these
 * tests direct: the mock parser returns canned RawQueries, the executor records
 * invocations and returns canned results, the log repo returns canned classifier
 * metadata.
 */
class ExecutePqlQueryUseCaseTest {

    // ----- fakes -----

    private class FakeParser(private val byQuery: Map<String, RawQuery>) : PqlParser {
        val parsed = mutableListOf<String>()
        override fun parse(source: String): RawQuery {
            parsed += source
            return byQuery[source] ?: error("FakeParser has no canned RawQuery for '$source'")
        }
    }

    private class FakeExecutor(
        var selectResult: QueryExecutionResult = QueryExecutionResult(emptyList(), emptyList(), 0, ""),
        var deleteResult: DeleteExecutionResult = DeleteExecutionResult(0, ""),
        var matchingLogIds: List<String> = emptyList(),
    ) : QueryPlanExecutor {
        val selectCalls = mutableListOf<Pair<LogicalPlan.Select, ExecutionOptions>>()
        val candidateLogCalls = mutableListOf<com.processm.processminterpreter.domain.pql.plan.CandidateLogPlan>()
        val deleteCalls = mutableListOf<LogicalPlan.Delete>()
        override fun execute(plan: LogicalPlan.Select, options: ExecutionOptions): QueryExecutionResult {
            selectCalls += plan to options
            return selectResult
        }
        override fun findMatchingLogIds(
            plan: com.processm.processminterpreter.domain.pql.plan.CandidateLogPlan,
        ): List<String> {
            candidateLogCalls += plan
            return matchingLogIds
        }
        override fun executeDelete(plan: LogicalPlan.Delete): DeleteExecutionResult {
            deleteCalls += plan
            return deleteResult
        }
    }

    private class FakeLogRepository(
        private val byId: Map<String, Log> = emptyMap(),
    ) : LogRepository {
        val findByIdCalls = mutableListOf<String>()
        override fun save(log: Log): Log = log
        override fun findById(id: String): Log? {
            findByIdCalls += id
            return byId[id]
        }
        override fun findAll(): List<Log> = byId.values.toList()
        override fun search(namePart: String): List<Log> = emptyList()
        override fun findByAttribute(key: String, value: Any): List<Log> = emptyList()
        override fun findCreatedAfter(date: LocalDateTime): List<Log> = emptyList()
        override fun findCreatedBetween(start: LocalDateTime, end: LocalDateTime): List<Log> = emptyList()
        override fun getStatistics(id: String): LogStatistics? = null
        override fun getStatisticsAll(): List<Pair<Log, LogStatistics>> = emptyList()
        override fun getClassifiers(id: String): Map<String, List<String>> = emptyMap()
        override fun update(log: Log): Log = log
        override fun delete(id: String): Boolean = false
        override fun deleteWithData(id: String): Boolean = false
        override fun exists(id: String): Boolean = byId.containsKey(id)
    }

    private class FakeDataStoreRepository(
        private val logsByDataStoreId: Map<String, List<DataStoreLogSummary>> = emptyMap(),
    ) : DataStoreRepository {
        override fun save(dataStore: DataStore): DataStore = dataStore
        override fun findById(id: String): DataStore? = null
        override fun findAll(): List<DataStore> = emptyList()
        override fun findLogSummaries(dataStoreId: String): List<DataStoreLogSummary> =
            logsByDataStoreId[dataStoreId].orEmpty()
        override fun update(dataStore: DataStore): DataStore = dataStore
        override fun deleteWithLogs(id: String): Boolean = false
        override fun exists(id: String): Boolean = logsByDataStoreId.containsKey(id)
        override fun attachLog(dataStoreId: String, logId: String) = Unit
    }

    // ----- fixtures -----

    /** Minimal SELECT raw query: `select e:name`. */
    private fun rawSelect(): RawQuery.Select = RawQuery.Select(
        from = Scope.EVENT,
        columns = listOf(
            RawSelectColumn(
                expression = RawAttributeRef(
                    rawText = "e:name", hoisting = 0, scopeHint = "e", name = "name",
                    wasBracketed = false, location = SourceLocation.UNKNOWN,
                ),
            ),
        ),
        location = SourceLocation.UNKNOWN,
    )

    private fun rawDelete(): RawQuery.Delete = RawQuery.Delete(
        from = Scope.EVENT,
        location = SourceLocation.UNKNOWN,
    )

    private fun rawTraceProjection(): RawQuery.Select = RawQuery.Select(
        from = Scope.EVENT,
        columns = listOf(
            RawSelectColumn(
                expression = RawAttributeRef(
                    rawText = "t:total",
                    hoisting = 0,
                    scopeHint = "t",
                    name = "total",
                    wasBracketed = false,
                    location = SourceLocation.UNKNOWN,
                ),
            ),
            RawSelectColumn(expression = null, starAt = Scope.TRACE),
        ),
        location = SourceLocation.UNKNOWN,
    )

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
        val parser = FakeParser(mapOf("select e:name" to rawSelect()))
        val executor = FakeExecutor(
            selectResult = QueryExecutionResult(
                logs = listOf(sampleXesLog("hit")),
                rows = emptyList(),
                rowCount = 7,
                executedQueryDescription = "MATCH (e:Event) RETURN e.name",
            ),
        )
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()), executor)

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
        val parser = FakeParser(mapOf("select e:name" to rawSelect()))
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
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, logs, dataStores), executor)

        useCase.execute(ExecutePqlQueryRequest(query = "select e:name", dataStoreId = "store-1"))

        val plan = executor.selectCalls.single().first
        assertEquals("log-1", plan.source.logId)
        assertNull(plan.source.dataStoreId)
    }

    @Test
    fun `execute routes DELETE plans through executeDelete and returns empty logs`() {
        val parser = FakeParser(mapOf("delete from event" to rawDelete()))
        val executor = FakeExecutor(
            deleteResult = DeleteExecutionResult(
                nodesDeleted = 42,
                executedQueryDescription = "MATCH (e:Event) DETACH DELETE e",
            ),
        )
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()), executor)

        val result = useCase.execute(ExecutePqlQueryRequest(query = "delete from event"))

        assertTrue(result.logs.isEmpty())
        assertEquals(42, result.rowCount)
        assertTrue(result.executedQueryDescription.contains("DETACH DELETE"))
        assertTrue(executor.selectCalls.isEmpty(), "execute should not be called for DELETE")
        assertEquals(1, executor.deleteCalls.size)
    }

    @Test
    fun `execute exposes projection metadata needed by ProcessM formatting`() {
        val parser = FakeParser(mapOf("select t:total, t:*" to rawTraceProjection()))
        val executor = FakeExecutor()
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()), executor)

        val result = useCase.execute(ExecutePqlQueryRequest(query = "select t:total, t:*"))

        assertEquals(setOf(Scope.TRACE), result.selectAllScopes)
        assertEquals(setOf(StandardAttributeCatalog.COST_TOTAL), result.projectedTraceStandardAttributes)
    }

    @Test
    fun `executeDelete on a DELETE plan returns structured DeleteExecutionResult`() {
        val parser = FakeParser(mapOf("delete from event" to rawDelete()))
        val executor = FakeExecutor(
            deleteResult = DeleteExecutionResult(nodesDeleted = 3, executedQueryDescription = "desc"),
        )
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()), executor)

        val result = useCase.executeDelete(ExecutePqlQueryRequest(query = "delete from event"))

        assertEquals(3, result.nodesDeleted)
        assertEquals("desc", result.executedQueryDescription)
    }

    @Test
    fun `executeDelete rejects SELECT plans with IllegalArgumentException`() {
        val parser = FakeParser(mapOf("select e:name" to rawSelect()))
        val executor = FakeExecutor()
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()), executor)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            useCase.executeDelete(ExecutePqlQueryRequest(query = "select e:name"))
        }
        assertTrue(
            ex.message!!.contains("non-DELETE", ignoreCase = true),
            "expected rejection message, got: ${ex.message}",
        )
        assertTrue(executor.deleteCalls.isEmpty())
    }

    @Test
    fun `non-classifier query carries logId without loading log metadata`() {
        val classifiers = listOf(Classifier("Event Name", listOf("concept:name")))
        val parser = FakeParser(mapOf("select e:name" to rawSelect()))
        val executor = FakeExecutor()
        val repo = FakeLogRepository(byId = mapOf("log-1" to sampleLog("log-1", classifiers)))
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, repo, FakeDataStoreRepository()), executor)

        useCase.execute(ExecutePqlQueryRequest(query = "select e:name", logId = "log-1"))

        assertTrue(repo.findByIdCalls.isEmpty(), "plain queries should not load classifier metadata")
        // Plan must carry the logId down into the LogicalSource.
        assertEquals("log-1", executor.selectCalls.single().first.source.logId)
    }

    @Test
    fun `buildContext skips the repository when logId is null`() {
        val parser = FakeParser(mapOf("select e:name" to rawSelect()))
        val executor = FakeExecutor()
        val repo = FakeLogRepository()
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, repo, FakeDataStoreRepository()), executor)

        useCase.execute(ExecutePqlQueryRequest(query = "select e:name", logId = null))

        assertTrue(repo.findByIdCalls.isEmpty(), "findById should not be hit when logId is null")
        assertNull(executor.selectCalls.single().first.source.logId)
    }

    @Test
    fun `plain query does not require the log to exist during compilation`() {
        val parser = FakeParser(mapOf("select e:name" to rawSelect()))
        val executor = FakeExecutor(
            selectResult = QueryExecutionResult(listOf(sampleXesLog()), emptyList(), 0, ""),
        )
        val repo = FakeLogRepository(byId = emptyMap())
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, repo, FakeDataStoreRepository()), executor)

        val result = useCase.execute(ExecutePqlQueryRequest(query = "select e:name", logId = "nope"))
        assertEquals(1, result.logs.size)
        assertTrue(repo.findByIdCalls.isEmpty(), "plain queries should not load classifier metadata")
        assertEquals("nope", executor.selectCalls.single().first.source.logId)
    }
    @Test
    fun `buildContext loads classifier metadata for logs attached to a data store`() {
        val classifierQuery = RawQuery.Select(
            from = Scope.EVENT,
            columns = listOf(
                RawSelectColumn(
                    expression = RawAttributeRef(
                        rawText = "c:Resource",
                        hoisting = 0,
                        scopeHint = null,
                        name = "c:Resource",
                        wasBracketed = false,
                        location = SourceLocation.UNKNOWN,
                    ),
                ),
            ),
            location = SourceLocation.UNKNOWN,
        )
        val parser = FakeParser(mapOf("select c:Resource" to classifierQuery))
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
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, repo, dataStores), executor)

        useCase.execute(ExecutePqlQueryRequest(query = "select c:Resource", dataStoreId = "store-1"))

        assertEquals(listOf("journal"), repo.findByIdCalls)
        val attr = executor.selectCalls.single().first.projection.columns.single().expression
            as com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
        assertEquals(listOf("org:resource"), attr.classifierKeys)
    }

    @Test
    fun `classifier queries spanning multiple logs compile one plan per log with local classifier metadata`() {
        val classifierQuery = RawQuery.Select(
            from = Scope.EVENT,
            columns = listOf(
                RawSelectColumn(
                    expression = RawAttributeRef(
                        rawText = "c:Activity",
                        hoisting = 0,
                        scopeHint = null,
                        name = "c:Activity",
                        wasBracketed = false,
                        location = SourceLocation.UNKNOWN,
                    ),
                ),
            ),
            limit = com.processm.processminterpreter.domain.pql.common.HierarchicalLimits(log = 1),
            offset = com.processm.processminterpreter.domain.pql.common.HierarchicalOffsets(log = 1),
            location = SourceLocation.UNKNOWN,
        )
        val parser = FakeParser(mapOf("select c:Activity" to classifierQuery))
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
        val executor = object : QueryPlanExecutor {
            val selectCalls = mutableListOf<LogicalPlan.Select>()

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

            override fun findMatchingLogIds(
                plan: com.processm.processminterpreter.domain.pql.plan.CandidateLogPlan,
            ): List<String> = listOf("a", "b")

            override fun executeDelete(plan: LogicalPlan.Delete): DeleteExecutionResult =
                DeleteExecutionResult(0, "")
        }
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, repo, dataStores), executor)

        val result = useCase.execute(ExecutePqlQueryRequest(query = "select c:Activity", dataStoreId = "store-1"))

        assertEquals(listOf("a", "b"), executor.selectCalls.map { it.source.logId })
        assertEquals(
            listOf(
                listOf("concept:name"),
                listOf("concept:name", "lifecycle:transition"),
            ),
            executor.selectCalls.map { call ->
                call.projection.columns.map { column ->
                    val attr = column.expression
                        as com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
                    attr.xesStandardName ?: attr.name
                }
            },
        )
        assertEquals(listOf("b"), result.logs.mapNotNull { it.conceptName })
        assertEquals(2, result.rowCount)
        assertEquals("a\n-- per-log --\nb", result.executedQueryDescription)
        assertEquals(
            listOf(null, null),
            executor.selectCalls.map { it.limits.log },
            "log limit must be applied once after merging, not once per per-log plan",
        )
        assertEquals(
            listOf(null, null),
            executor.selectCalls.map { it.offsets.log },
            "log offset must be applied once after merging, not once per per-log plan",
        )
    }

    @Test
    fun `classifier fan-out only specializes logs that pass the shared where filter`() {
        val classifierQuery = RawQuery.Select(
            from = Scope.EVENT,
            columns = listOf(
                RawSelectColumn(
                    expression = RawAttributeRef(
                        rawText = "c:Activity",
                        hoisting = 0,
                        scopeHint = null,
                        name = "c:Activity",
                        wasBracketed = false,
                        location = SourceLocation.UNKNOWN,
                    ),
                ),
            ),
            where = RawBinaryOp(
                op = com.processm.processminterpreter.domain.pql.catalog.BinaryOperator.EQ,
                left = RawAttributeRef(
                    rawText = "l:name",
                    hoisting = 0,
                    scopeHint = "l",
                    name = "name",
                    wasBracketed = false,
                    location = SourceLocation.UNKNOWN,
                ),
                right = RawLiteral(
                    rawText = "'B'",
                    kind = RawLiteralKind.STRING,
                    location = SourceLocation.UNKNOWN,
                ),
                location = SourceLocation.UNKNOWN,
            ),
            location = SourceLocation.UNKNOWN,
        )
        val parser = FakeParser(mapOf("select c:Activity where l:name='B'" to classifierQuery))
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
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, repo, dataStores), executor)

        val result = useCase.execute(
            ExecutePqlQueryRequest(
                query = "select c:Activity where l:name='B'",
                dataStoreId = "store-1",
            ),
        )

        assertEquals(1, executor.candidateLogCalls.size)
        assertEquals(listOf("b"), executor.selectCalls.map { it.first.source.logId })
        assertEquals(listOf("b"), result.logs.mapNotNull { it.conceptName })
    }

    @Test
    fun `classifier candidate probe keeps only non-classifier order keys`() {
        val classifierQuery = RawQuery.Select(
            from = Scope.EVENT,
            columns = listOf(
                RawSelectColumn(
                    expression = RawAttributeRef(
                        rawText = "c:Activity",
                        hoisting = 0,
                        scopeHint = null,
                        name = "c:Activity",
                        wasBracketed = false,
                        location = SourceLocation.UNKNOWN,
                    ),
                ),
            ),
            orderBy = listOf(
                RawOrderKey(
                    expression = RawAttributeRef(
                        rawText = "l:name",
                        hoisting = 0,
                        scopeHint = "l",
                        name = "name",
                        wasBracketed = false,
                        location = SourceLocation.UNKNOWN,
                    ),
                    direction = OrderDirection.DESC,
                ),
                RawOrderKey(
                    expression = RawAttributeRef(
                        rawText = "c:Activity",
                        hoisting = 0,
                        scopeHint = null,
                        name = "c:Activity",
                        wasBracketed = false,
                        location = SourceLocation.UNKNOWN,
                    ),
                    direction = OrderDirection.ASC,
                ),
            ),
            location = SourceLocation.UNKNOWN,
        )
        val parser = FakeParser(mapOf("select c:Activity order by l:name desc, c:Activity" to classifierQuery))
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
        val useCase = ExecutePqlQueryUseCase(PqlCompiler(parser, repo, dataStores), executor)

        useCase.execute(
            ExecutePqlQueryRequest(
                query = "select c:Activity order by l:name desc, c:Activity",
                dataStoreId = "store-1",
            ),
        )

        val candidateOrder = executor.candidateLogCalls.single().orderBy
        assertEquals(1, candidateOrder.size)
        val candidateAttribute = candidateOrder.single().expression
            as com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
        assertEquals(Scope.LOG, candidateAttribute.effectiveScope)
        assertEquals("concept:name", candidateAttribute.xesStandardName)
    }
}
