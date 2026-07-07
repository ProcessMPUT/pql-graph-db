package com.processm.processminterpreter.pql

import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.Log
import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.pql.ast.PqlQuery
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.error.PQLSyntaxException
import com.processm.processminterpreter.xes.DataStoreLogSummary
import com.processm.processminterpreter.xes.LogRepository
import com.processm.processminterpreter.pql.parser.AntlrPqlParser
import com.processm.processminterpreter.xes.io.OpenXesWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class PqlQueryServiceValidateTest {

    /**
     * Builds the service around the given compiler. The executor and writer are
     * never touched by validate-only flows — the executor wraps a mocked driver
     * purely to satisfy the constructor.
     */
    private fun service(compiler: PqlCompiler): PqlQueryService =
        PqlQueryService(compiler, stubExecutor(), OpenXesWriter())

    private class FakeParser(
        private val result: PqlQuery? = null,
        private val throwing: PQLSyntaxException? = null,
        private val throwOther: RuntimeException? = null,
    ) : AntlrPqlParser() {
        override fun parse(source: String): PqlQuery {
            throwing?.let { throw it }
            throwOther?.let { throw it }
            return result ?: error("FakeParser has no canned result")
        }
    }

    private fun attr(name: String, scope: String? = "e", hoisting: Int = 0): PqlExpression.AttributeRef = PqlExpression.AttributeRef(
        rawText = "^".repeat(hoisting) + "${scope?.plus(":").orEmpty()}$name",
        hoisting = hoisting, scopeHint = scope, name = name,
        wasBracketed = false, location = SourceLocation.UNKNOWN,
    )

    private fun rawSelect(columns: List<PqlExpression>): PqlQuery.Select = PqlQuery.Select(
        from = Scope.EVENT,
        columns = columns.map { PqlQuery.SelectColumn(expression = it) },
        location = SourceLocation.UNKNOWN,
    )

    // ----- tests -----

    @Test
    fun `valid query returns valid=true and no errors`() {
        val parser = FakeParser(result = rawSelect(listOf(attr("name"))))
        val useCase = service(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()))

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
        val useCase = service(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()))

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
        // Build a PqlQuery.Select with `select e:name, count(e:id)` + `group by e:id`
        // then hand the raw to the use case; the real Validator should reject it.
        val select = PqlQuery.Select(
            from = Scope.EVENT,
            columns = listOf(
                PqlQuery.SelectColumn(expression = attr("name")),
                PqlQuery.SelectColumn(
                    expression = com.processm.processminterpreter.pql.ast.PqlExpression.Call(
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
        val useCase = service(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()))

        val r = useCase.validate(ValidatePqlQueryRequest(query = "bad group by"))
        assertFalse(r.valid)
        assertTrue(r.errors.single().contains("GROUP BY", ignoreCase = true), r.errors.single())
    }

    @Test
    fun `group by hoisted attribute covers the selected base attribute`() {
        val select = PqlQuery.Select(
            from = Scope.EVENT,
            columns = listOf(PqlQuery.SelectColumn(expression = attr("name"))),
            groupBy = listOf(attr("name", hoisting = 1)),
            location = SourceLocation.UNKNOWN,
        )
        val parser = FakeParser(result = select)
        val useCase = service(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()))

        val r = useCase.validate(ValidatePqlQueryRequest(query = "select e:name group by ^e:name"))

        assertTrue(r.valid, r.errors.joinToString())
    }

    @Test
    fun `non-compile exceptions propagate`() {
        val parser = FakeParser(throwOther = IllegalStateException("something broke"))
        val useCase = service(PqlCompiler(parser, FakeLogRepository(), FakeDataStoreRepository()))

        // Runtime exceptions that aren't compile errors are bugs, not validation failures.
        val ex = assertThrows(IllegalStateException::class.java) {
            useCase.validate(ValidatePqlQueryRequest(query = "x"))
        }
        assertEquals("something broke", ex.message)
    }

    @Test
    fun `logId is forwarded to the repository for classifier lookup`() {
        val parser = FakeParser(result = rawSelect(listOf(attr("c:Event Name", scope = null))))
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
        val useCase = service(PqlCompiler(parser, repo, FakeDataStoreRepository()))

        val r = useCase.validate(ValidatePqlQueryRequest(query = "select [e:c:Event Name]", logId = "log-1"))

        assertTrue(r.valid)
        assertEquals("log-1", repo.lastLookup)
    }

    @Test
    fun `validation accepts classifier queries spanning logs with different local definitions`() {
        val classifierQuery = PqlQuery.Select(
            from = Scope.EVENT,
            columns = listOf(PqlQuery.SelectColumn(expression = attr("c:Activity", scope = null))),
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
        val useCase = service(PqlCompiler(parser, repo, dataStores))

        val result = useCase.validate(
            ValidatePqlQueryRequest(
                query = "select c:Activity",
                dataStoreId = "store-1",
            ),
        )

        assertTrue(result.valid, result.errors.joinToString())
    }

    /**
     * Reference-parity table: queries the original ProcessM rejects must be
     * rejected here too, and queries it accepts must stay accepted. Each case
     * was verified against processm.core querylanguage/Query.kt during the
     * 2026-07 semantic-parity review.
     */
    @Test
    fun `validation matches reference ProcessM on group-by and hoisting rules`() {
        val useCase = service(PqlCompiler(AntlrPqlParser(), FakeLogRepository(), FakeDataStoreRepository()))
        fun validate(q: String) = useCase.validate(ValidatePqlQueryRequest(query = q))

        val mustReject = mapOf(
            // implicit group-by: non-aggregated attribute at aggregated scope
            "select e:name, avg(e:total)" to "MissingAttributesInAggregation",
            // explicit star at scope below the implicitly aggregated scope
            "select e:*, avg(t:total)" to "ExplicitSelectAllWithImplicitGroupBy",
            // explicit star with explicit group-by at the same scope
            "select e:* group by e:name" to "MixedScopes",
            // ORDER BY attribute not covered by group keys
            "select t:name group by t:name order by t:total" to "AttributeNotInGroupBy",
            // non-aggregated attribute next to an aggregation, not in group keys
            "select sum(e:total) + t:total group by t:name" to "AttributeNotInGroupBy",
            // hoisted group key does not legitimize the parent-scope attribute
            "select t:name group by ^e:name" to "AttributeNotInGroupBy",
            // hoisting in SELECT outside an aggregation argument
            "select ^e:name" to "ScopeHoistingInSelectOrOrderBy",
        )
        for ((query, problem) in mustReject) {
            val r = validate(query)
            assertTrue(!r.valid, "expected invalid: $query")
            assertTrue(
                r.errors.any { it.contains(problem) },
                "expected $problem for '$query', got: ${r.errors.joinToString()}",
            )
        }

        val mustAccept = listOf(
            // hoisted group key covers its base attribute
            "select e:name group by ^e:name",
            // aggregation argument may be hoisted
            "select count(t:name) group by ^e:name order by count(t:name) desc",
            // ORDER BY at an implicitly aggregated scope is dropped, not rejected
            "select avg(e:total) order by e:timestamp",
            // upper-scope attribute is unconstrained by deeper grouping
            "select t:name, count(e:name) group by t:name",
        )
        for (query in mustAccept) {
            val r = validate(query)
            assertTrue(r.valid, "expected valid: $query -> ${r.errors.joinToString()}")
        }
    }
}
