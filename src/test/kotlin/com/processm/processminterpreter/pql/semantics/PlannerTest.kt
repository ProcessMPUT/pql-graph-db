package com.processm.processminterpreter.pql.semantics

import com.processm.processminterpreter.pql.catalog.OrderDirection
import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.catalog.Type
import com.processm.processminterpreter.pql.plan.LogicalPlan
import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.ast.PqlQuery
import com.processm.processminterpreter.pql.ast.ValidatedQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlannerTest {
    private val loc = SourceLocation(1, 0)
    private val planner = Planner()

    private fun attr(scope: Scope, canonical: String) = PqlExpression.Attribute(
        name = canonical, baseScope = scope, effectiveScope = scope,
        kind = AttributeKind.STANDARD, xesStandardName = canonical,
        wasBracketed = false, type = Type.STRING, location = loc,
    )

    private fun customAttr(scope: Scope, name: String) = PqlExpression.Attribute(
        name = name, baseScope = scope, effectiveScope = scope,
        kind = AttributeKind.CUSTOM, xesStandardName = null,
        wasBracketed = true, type = Type.STRING, location = loc,
    )

    private fun hoistedAttr(base: Scope, effective: Scope, canonical: String) = PqlExpression.Attribute(
        name = canonical, baseScope = base, effectiveScope = effective,
        kind = AttributeKind.STANDARD, xesStandardName = canonical,
        wasBracketed = false, type = Type.STRING, location = loc,
    )

    private fun validated(
        from: Scope = Scope.EVENT,
        columns: List<PqlQuery.SelectColumn>,
        groupBy: List<com.processm.processminterpreter.pql.ast.PqlExpression> = emptyList(),
        orderBy: List<PqlQuery.OrderKey> = emptyList(),
        limit: HierarchicalLimits = HierarchicalLimits(),
        implicitAll: Boolean = false,
    ) = ValidatedQuery(
        PqlQuery.Select(
            from = from, columns = columns,
            implicitAll = implicitAll,
            where = null, groupBy = groupBy, orderBy = orderBy,
            limit = limit,
            location = loc,
        ),
    )

    @Test
    fun `plan produces one ProjectedColumn per non-star select item`() {
        val q = validated(columns = listOf(
            PqlQuery.SelectColumn(expression = attr(Scope.EVENT, "concept:name")),
            PqlQuery.SelectColumn(expression = attr(Scope.EVENT, "time:timestamp")),
        ))
        val plan = planner.plan(q) as LogicalPlan.Select
        assertEquals(2, plan.projection.columns.size)
        // Aliases are scope-prefixed (l_/t_/e_) so multi-scope queries like
        // `select l:name, t:name, e:name` don't collide on a single Cypher column.
        assertEquals("e_concept_name", plan.projection.columns[0].alias)
        assertEquals(Scope.EVENT, plan.projection.columns[0].scope)
    }

    @Test
    fun `custom attribute aliases are safe Cypher identifiers`() {
        val q = validated(columns = listOf(
            PqlQuery.SelectColumn(expression = customAttr(Scope.TRACE, "Specialism code")),
            PqlQuery.SelectColumn(expression = customAttr(Scope.EVENT, "call centre")),
        ))

        val plan = planner.plan(q) as LogicalPlan.Select

        assertEquals("t_Specialism_code", plan.projection.columns[0].alias)
        assertEquals("e_call_centre", plan.projection.columns[1].alias)
    }

    @Test
    fun `star columns collapse into selectAll map, not projections`() {
        val q = validated(columns = listOf(
            PqlQuery.SelectColumn(expression = null, starAt = Scope.EVENT),
            PqlQuery.SelectColumn(expression = attr(Scope.EVENT, "concept:name")),
        ))
        val plan = planner.plan(q) as LogicalPlan.Select
        assertEquals(1, plan.projection.columns.size)
        assertEquals(true, plan.projection.selectAll[Scope.EVENT])
    }

    @Test
    fun `implicit all marker is preserved separately from explicit stars`() {
        val q = validated(
            columns = Scope.entries.map { PqlQuery.SelectColumn(expression = null, starAt = it) },
            implicitAll = true,
        )

        val plan = planner.plan(q) as LogicalPlan.Select

        assertTrue(plan.projection.implicitAll)
        assertEquals(Scope.entries.toSet(), plan.projection.selectAll.filterValues { it }.keys)
    }

    @Test
    fun `implicit select all with hoisted group by keeps only upper wildcard scopes`() {
        val hoistedEventName = hoistedAttr(Scope.EVENT, Scope.TRACE, "concept:name")
        val q = validated(
            columns = Scope.entries.map { PqlQuery.SelectColumn(expression = null, starAt = it) },
            groupBy = listOf(hoistedEventName),
            implicitAll = true,
        )

        val plan = planner.plan(q) as LogicalPlan.Select

        assertTrue(plan.projection.implicitAll)
        assertEquals(setOf(Scope.LOG), plan.projection.selectAll.filterValues { it }.keys)
        assertEquals(1, plan.projection.columns.size)
        val projected = plan.projection.columns.single()
        assertEquals(Scope.EVENT, projected.scope)
        assertEquals(Scope.EVENT, (projected.expression as PqlExpression.Attribute).effectiveScope)
    }

    @Test
    fun `group by is preserved and hasAggregation is false without aggregates`() {
        val name = attr(Scope.EVENT, "concept:name")
        val q = validated(
            columns = listOf(PqlQuery.SelectColumn(expression = name)),
            groupBy = listOf(name),
        )
        val plan = planner.plan(q) as LogicalPlan.Select
        val gb = plan.groupBy
        assertNotNull(gb)
        assertEquals(1, gb!!.keys.size)
        assertEquals(false, gb.hasAggregation)
    }

    @Test
    fun `aggregation in select triggers implicit group-by spec`() {
        val count = PqlExpression.Aggregation(
            name = "count", argument = attr(Scope.EVENT, "concept:name"),
            type = Type.INTEGER, location = loc,
        )
        val q = validated(columns = listOf(PqlQuery.SelectColumn(expression = count, alias = "c")))
        val plan = planner.plan(q) as LogicalPlan.Select
        val gb = plan.groupBy
        assertNotNull(gb)
        assertTrue(gb!!.hasAggregation)
        assertTrue(gb.keys.isEmpty())
    }

    @Test
    fun `no group-by and no aggregation yields null groupBy spec`() {
        val q = validated(columns = listOf(PqlQuery.SelectColumn(expression = attr(Scope.EVENT, "concept:name"))))
        val plan = planner.plan(q) as LogicalPlan.Select
        assertNull(plan.groupBy)
    }

    @Test
    fun `order by translates to plan OrderKeys preserving direction`() {
        val name = attr(Scope.EVENT, "concept:name")
        val q = validated(
            columns = listOf(PqlQuery.SelectColumn(expression = name)),
            orderBy = listOf(PqlQuery.OrderKey(name, OrderDirection.DESC)),
        )
        val plan = planner.plan(q) as LogicalPlan.Select
        assertEquals(OrderDirection.DESC, plan.orderBy.single().direction)
    }

    @Test
    fun `usedScopes includes from-scope and all attribute scopes`() {
        val q = validated(
            from = Scope.TRACE,
            columns = listOf(PqlQuery.SelectColumn(expression = attr(Scope.EVENT, "concept:name"))),
        )
        val plan = planner.plan(q) as LogicalPlan.Select
        assertTrue(Scope.TRACE in plan.source.usedScopes)
        assertTrue(Scope.EVENT in plan.source.usedScopes)
    }

    @Test
    fun `logId is propagated into LogicalSource`() {
        val q = validated(columns = listOf(PqlQuery.SelectColumn(expression = attr(Scope.EVENT, "concept:name"))))
        val plan = planner.plan(q, logId = "log-42") as LogicalPlan.Select
        assertEquals("log-42", plan.source.logId)
    }

    @Test
    fun `delete query becomes LogicalPlan Delete with target equal to from`() {
        val q = ValidatedQuery(PqlQuery.Delete(from = Scope.EVENT, where = null, location = loc))
        val plan = planner.plan(q) as LogicalPlan.Delete
        assertEquals(Scope.EVENT, plan.target)
    }
}
