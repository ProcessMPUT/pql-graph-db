package com.processm.processminterpreter.pql.semantics

import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.catalog.Type
import com.processm.processminterpreter.pql.error.PQLSyntaxException
import com.processm.processminterpreter.pql.error.Problem
import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.pql.ast.PqlQuery
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ValidatorTest {
    private val loc = SourceLocation(1, 0)
    private val validator = Validator()

    private fun attr(scope: Scope, canonical: String, kind: AttributeKind = AttributeKind.STANDARD) =
        PqlExpression.Attribute(
            name = canonical, baseScope = scope, effectiveScope = scope, kind = kind,
            xesStandardName = if (kind == AttributeKind.STANDARD) canonical else null,
            wasBracketed = false, type = Type.STRING, location = loc,
        )

    private fun select(
        columns: List<PqlQuery.SelectColumn>,
        groupBy: List<PqlExpression.Attribute> = emptyList(),
    ) = PqlQuery.Select(
        from = Scope.EVENT, columns = columns,
        where = null, groupBy = groupBy, orderBy = emptyList(),
        location = loc,
    )

    @Test
    fun `valid simple query passes`() {
        val q = select(listOf(PqlQuery.SelectColumn(expression = attr(Scope.EVENT, "concept:name"))))
        assertDoesNotThrow { validator.validate(q) }
    }

    @Test
    fun `non-aggregated projection not in group-by throws AttributeNotInGroupBy`() {
        val q = select(
            columns = listOf(
                PqlQuery.SelectColumn(expression = attr(Scope.EVENT, "concept:name")),
                PqlQuery.SelectColumn(expression = attr(Scope.EVENT, "cost:total")),
            ),
            groupBy = listOf(attr(Scope.EVENT, "concept:name")),
        )
        val ex = assertThrows<PQLSyntaxException> { validator.validate(q) }
        assertEquals(Problem.AttributeNotInGroupBy, ex.problem)
    }

    @Test
    fun `select-all event scope with event-level aggregation throws ExplicitSelectAllWithImplicitGroupBy`() {
        // No explicit GROUP BY here, so the aggregation implicitly groups EVENT and
        // the reference classifies the explicit star as
        // ExplicitSelectAllWithImplicitGroupBy (Query.kt:394-401), not MixedScopes.
        val count = PqlExpression.Aggregation(
            name = "count", argument = attr(Scope.EVENT, "concept:name"),
            type = Type.INTEGER, location = loc,
        )
        val q = select(
            listOf(
                PqlQuery.SelectColumn(expression = null, starAt = Scope.EVENT),
                PqlQuery.SelectColumn(expression = count),
            ),
        )
        val ex = assertThrows<PQLSyntaxException> { validator.validate(q) }
        assertEquals(Problem.ExplicitSelectAllWithImplicitGroupBy, ex.problem)
    }

    @Test
    fun `aggregation on non-grouped column is allowed when group-by covers the rest`() {
        val name = attr(Scope.EVENT, "concept:name")
        val count = PqlExpression.Aggregation(
            name = "count", argument = attr(Scope.EVENT, "cost:total"),
            type = Type.INTEGER, location = loc,
        )
        val q = select(
            columns = listOf(
                PqlQuery.SelectColumn(expression = name),
                PqlQuery.SelectColumn(expression = count),
            ),
            groupBy = listOf(name),
        )
        assertDoesNotThrow { validator.validate(q) }
    }

    @Test
    fun `ValidatedQuery wraps the same PqlQuery instance`() {
        val q = select(listOf(PqlQuery.SelectColumn(expression = attr(Scope.EVENT, "concept:name"))))
        val v = validator.validate(q)
        assertEquals(q, v.query)
    }

    @Test
    fun `select-all at scope without aggregation at that scope is allowed`() {
        val count = PqlExpression.Aggregation(
            name = "count", argument = attr(Scope.EVENT, "concept:name"),
            type = Type.INTEGER, location = loc,
        )
        // l:* + event-scope aggregation => OK
        val q = select(
            listOf(
                PqlQuery.SelectColumn(expression = null, starAt = Scope.LOG),
                PqlQuery.SelectColumn(expression = count),
            ),
        )
        assertDoesNotThrow { validator.validate(q) }
    }
}
