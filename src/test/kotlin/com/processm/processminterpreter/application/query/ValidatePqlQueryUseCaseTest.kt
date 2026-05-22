package com.processm.processminterpreter.application.query

import com.processm.processminterpreter.domain.datastore.DataStore
import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.log.Log
import com.processm.processminterpreter.domain.pql.syntax.RawAttributeRef
import com.processm.processminterpreter.domain.pql.syntax.RawQuery
import com.processm.processminterpreter.domain.pql.syntax.RawSelectColumn
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.error.PQLSyntaxException
import com.processm.processminterpreter.application.ports.DataStoreLogSummary
import com.processm.processminterpreter.application.ports.DataStoreRepository
import com.processm.processminterpreter.application.ports.LogRepository
import com.processm.processminterpreter.application.ports.LogStatistics
import com.processm.processminterpreter.application.query.PqlParser
import com.processm.processminterpreter.domain.pql.syntax.RawExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ValidatePqlQueryUseCaseTest {

    private class FakeParser(
        private val result: RawQuery? = null,
        private val throwing: PQLSyntaxException? = null,
        private val throwOther: RuntimeException? = null,
    ) : PqlParser {
        override fun parse(source: String): RawQuery {
            throwing?.let { throw it }
            throwOther?.let { throw it }
            return result ?: error("FakeParser has no canned result")
        }
    }

    private class FakeLogRepository(private val byId: Map<String, Log> = emptyMap()) : LogRepository {
        override fun save(log: Log): Log = log
        override fun findById(id: String): Log? = byId[id]
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

    private fun attr(name: String, scope: String? = "e", hoisting: Int = 0): RawAttributeRef = RawAttributeRef(
        rawText = "^".repeat(hoisting) + "${scope?.plus(":").orEmpty()}$name",
        hoisting = hoisting, scopeHint = scope, name = name,
        wasBracketed = false, location = SourceLocation.UNKNOWN,
    )

    private fun rawSelect(columns: List<RawExpression>): RawQuery.Select = RawQuery.Select(
        from = Scope.EVENT,
        columns = columns.map { RawSelectColumn(expression = it) },
        location = SourceLocation.UNKNOWN,
    )

    // ----- tests -----

    @Test
    fun `valid query returns valid=true and no errors`() {
        val parser = FakeParser(result = rawSelect(listOf(attr("name"))))
        val useCase = ValidatePqlQueryUseCase(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()))

        val r = useCase.validate(ValidatePqlQueryRequest(query = "select e:name"))

        assertTrue(r.valid)
        assertEquals("select e:name", r.query)
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun `parser syntax errors surface as valid=false with a human message`() {
        val parser = FakeParser(
            throwing = PQLSyntaxException(SourceLocation(1, 7), "unexpected token"),
        )
        val useCase = ValidatePqlQueryUseCase(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()))

        val r = useCase.validate(ValidatePqlQueryRequest(query = "select ???"))

        assertFalse(r.valid)
        assertEquals("select ???", r.query)
        assertEquals(1, r.errors.size)
        assertTrue(
            r.errors.single().contains("unexpected token"),
            "expected 'unexpected token' in error: ${r.errors.single()}",
        )
    }

    @Test
    fun `validator errors are captured`() {
        // GROUP BY rule: non-aggregated attribute not in GROUP BY → AttributeNotInGroupBy.
        // Build a RawQuery.Select with `select e:name, count(e:id)` + `group by e:id`
        // then hand the raw to the use case; the real Validator should reject it.
        val select = RawQuery.Select(
            from = Scope.EVENT,
            columns = listOf(
                RawSelectColumn(expression = attr("name")),
                RawSelectColumn(
                    expression = com.processm.processminterpreter.domain.pql.syntax.RawFunctionCall(
                        name = "count",
                        arguments = listOf(attr("id")),
                        location = SourceLocation.UNKNOWN,
                    ),
                ),
            ),
            groupBy = listOf(attr("id")),
            location = SourceLocation.UNKNOWN,
        )
        val parser = FakeParser(result = select)
        val useCase = ValidatePqlQueryUseCase(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()))

        val r = useCase.validate(ValidatePqlQueryRequest(query = "bad group by"))
        assertFalse(r.valid)
        assertTrue(r.errors.single().contains("GROUP BY", ignoreCase = true), r.errors.single())
    }

    @Test
    fun `group by hoisted attribute covers the selected base attribute`() {
        val select = RawQuery.Select(
            from = Scope.EVENT,
            columns = listOf(RawSelectColumn(expression = attr("name"))),
            groupBy = listOf(attr("name", hoisting = 1)),
            location = SourceLocation.UNKNOWN,
        )
        val parser = FakeParser(result = select)
        val useCase = ValidatePqlQueryUseCase(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()))

        val r = useCase.validate(ValidatePqlQueryRequest(query = "select e:name group by ^e:name"))

        assertTrue(r.valid, r.errors.joinToString())
    }

    @Test
    fun `non-compile exceptions propagate`() {
        val parser = FakeParser(throwOther = IllegalStateException("something broke"))
        val useCase = ValidatePqlQueryUseCase(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()))

        // Runtime exceptions that aren't compile errors are bugs, not validation failures.
        val ex = assertThrows(IllegalStateException::class.java) {
            useCase.validate(ValidatePqlQueryRequest(query = "x"))
        }
        assertEquals("something broke", ex.message)
    }

    @Test
    fun `logId is forwarded to the repository for classifier lookup`() {
        // Smoke test — the validator itself doesn't consult classifiers for a
        // plain query, but we want to prove the resolution context is built.
        val parser = FakeParser(result = rawSelect(listOf(attr("name"))))
        val repo = object : LogRepository by FakeLogRepository() {
            var lastLookup: String? = null
            override fun findById(id: String): Log? {
                lastLookup = id
                return Log(
                    id = id, name = "n",
                    createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now(),
                    classifiers = listOf(Classifier("Event Name", listOf("concept:name"))),
                )
            }
        }
        val useCase = ValidatePqlQueryUseCase(PqlCompiler(parser, repo, FakeDataStoreRepository()))

        val r = useCase.validate(ValidatePqlQueryRequest(query = "select e:name", logId = "log-1"))

        assertTrue(r.valid)
        assertEquals("log-1", repo.lastLookup)
    }

    @Test
    fun `validation accepts classifier queries spanning logs with different local definitions`() {
        val classifierQuery = RawQuery.Select(
            from = Scope.EVENT,
            columns = listOf(RawSelectColumn(expression = attr("c:Activity", scope = null))),
            location = SourceLocation.UNKNOWN,
        )
        val parser = FakeParser(result = classifierQuery)
        val repo = FakeLogRepository(
            byId = mapOf(
                "a" to Log(
                    id = "a",
                    name = "A",
                    createdAt = LocalDateTime.now(),
                    updatedAt = LocalDateTime.now(),
                    classifiers = listOf(Classifier("Activity", listOf("concept:name"))),
                ),
                "b" to Log(
                    id = "b",
                    name = "B",
                    createdAt = LocalDateTime.now(),
                    updatedAt = LocalDateTime.now(),
                    classifiers = listOf(Classifier("Activity", listOf("concept:name", "lifecycle:transition"))),
                ),
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
        val useCase = ValidatePqlQueryUseCase(PqlCompiler(parser, repo, dataStores))

        val result = useCase.validate(
            ValidatePqlQueryRequest(
                query = "select c:Activity",
                dataStoreId = "store-1",
            ),
        )

        assertTrue(result.valid, result.errors.joinToString())
    }
}
