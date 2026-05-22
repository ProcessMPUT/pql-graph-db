package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.catalog.AttributeKind
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.catalog.Type
import com.processm.processminterpreter.domain.pql.error.PQLSyntaxException
import com.processm.processminterpreter.domain.pql.error.Problem
import com.processm.processminterpreter.domain.pql.resolved.Aggregation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.domain.pql.resolved.ResolvedQuery
import com.processm.processminterpreter.domain.pql.resolved.ResolvedSelectColumn
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ValidatorTest {
    private val loc = SourceLocation(1, 0)
    private val validator = Validator()

    private fun attr(scope: Scope, canonical: String, kind: AttributeKind = AttributeKind.STANDARD) =
        ResolvedAttribute(
            name = canonical, baseScope = scope, effectiveScope = scope, kind = kind,
            xesStandardName = if (kind == AttributeKind.STANDARD) canonical else null,
            wasBracketed = false, type = Type.STRING, location = loc,
        )

    private fun select(
        columns: List<ResolvedSelectColumn>,
        groupBy: List<ResolvedAttribute> = emptyList(),
    ) = ResolvedQuery.Select(
        from = Scope.EVENT, columns = columns,
        where = null, groupBy = groupBy, orderBy = emptyList(),
        location = loc,
    )

    @Test
    fun `valid simple query passes`() {
        val q = select(listOf(ResolvedSelectColumn(expression = attr(Scope.EVENT, "concept:name"))))
        assertDoesNotThrow { validator.validate(q) }
    }

    @Test
    fun `non-aggregated projection not in group-by throws AttributeNotInGroupBy`() {
        val q = select(
            columns = listOf(
                ResolvedSelectColumn(expression = attr(Scope.EVENT, "concept:name")),
                ResolvedSelectColumn(expression = attr(Scope.EVENT, "cost:total")),
            ),
            groupBy = listOf(attr(Scope.EVENT, "concept:name")),
        )
        val ex = assertThrows<PQLSyntaxException> { validator.validate(q) }
        assertEquals(Problem.AttributeNotInGroupBy, ex.problem)
    }

    @Test
    fun `select-all event scope with event-level aggregation throws MixedScopes`() {
        val count = Aggregation(
            name = "count", argument = attr(Scope.EVENT, "concept:name"),
            type = Type.INTEGER, location = loc,
        )
        val q = select(
            listOf(
                ResolvedSelectColumn(expression = null, starAt = Scope.EVENT),
                ResolvedSelectColumn(expression = count),
            ),
        )
        val ex = assertThrows<PQLSyntaxException> { validator.validate(q) }
        assertEquals(Problem.MixedScopes, ex.problem)
    }

    @Test
    fun `aggregation on non-grouped column is allowed when group-by covers the rest`() {
        val name = attr(Scope.EVENT, "concept:name")
        val count = Aggregation(
            name = "count", argument = attr(Scope.EVENT, "cost:total"),
            type = Type.INTEGER, location = loc,
        )
        val q = select(
            columns = listOf(
                ResolvedSelectColumn(expression = name),
                ResolvedSelectColumn(expression = count),
            ),
            groupBy = listOf(name),
        )
        assertDoesNotThrow { validator.validate(q) }
    }

    @Test
    fun `ValidatedQuery wraps the same ResolvedQuery instance`() {
        val q = select(listOf(ResolvedSelectColumn(expression = attr(Scope.EVENT, "concept:name"))))
        val v = validator.validate(q)
        assertEquals(q, v.query)
    }

    @Test
    fun `select-all at scope without aggregation at that scope is allowed`() {
        val count = Aggregation(
            name = "count", argument = attr(Scope.EVENT, "concept:name"),
            type = Type.INTEGER, location = loc,
        )
        // l:* + event-scope aggregation => OK
        val q = select(
            listOf(
                ResolvedSelectColumn(expression = null, starAt = Scope.LOG),
                ResolvedSelectColumn(expression = count),
            ),
        )
        assertDoesNotThrow { validator.validate(q) }
    }
}
