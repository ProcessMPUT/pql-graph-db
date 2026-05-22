package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.syntax.BinaryOperator
import com.processm.processminterpreter.domain.pql.syntax.RawAttributeRef
import com.processm.processminterpreter.domain.pql.syntax.RawBinaryOp
import com.processm.processminterpreter.domain.pql.syntax.RawFunctionCall
import com.processm.processminterpreter.domain.pql.syntax.RawLiteral
import com.processm.processminterpreter.domain.pql.syntax.RawLiteralKind
import com.processm.processminterpreter.domain.pql.syntax.RawOrderKey
import com.processm.processminterpreter.domain.pql.syntax.OrderDirection
import com.processm.processminterpreter.domain.pql.syntax.RawQuery
import com.processm.processminterpreter.domain.pql.syntax.RawSelectColumn
import com.processm.processminterpreter.domain.pql.catalog.AttributeKind
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.catalog.Type
import com.processm.processminterpreter.domain.pql.resolved.Aggregation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.domain.pql.resolved.ResolvedQuery
import com.processm.processminterpreter.domain.pql.resolved.ScalarFunction
import com.processm.processminterpreter.domain.pql.resolved.TypedBinaryOp
import com.processm.processminterpreter.domain.pql.resolved.TypedLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResolverTest {
    private val loc = SourceLocation(1, 0)
    private val resolver = Resolver()

    private fun ref(
        name: String,
        scopeHint: String? = null,
        hoisting: Int = 0,
        wasBracketed: Boolean = false,
    ) = RawAttributeRef(
        rawText = name, hoisting = hoisting, scopeHint = scopeHint,
        name = name, wasBracketed = wasBracketed, location = loc,
    )

    private fun select(
        from: Scope,
        columns: List<RawSelectColumn>,
        where: com.processm.processminterpreter.domain.pql.syntax.RawExpression? = null,
        groupBy: List<com.processm.processminterpreter.domain.pql.syntax.RawExpression> = emptyList(),
        orderBy: List<RawOrderKey> = emptyList(),
    ) = RawQuery.Select(
        from = from, columns = columns,
        where = where, groupBy = groupBy, orderBy = orderBy,
        location = loc,
    )

    @Test
    fun `resolves simple select of event name`() {
        val q = select(Scope.EVENT, listOf(RawSelectColumn(expression = ref("name"))))
        val r = resolver.resolve(q) as ResolvedQuery.Select
        val attr = r.columns.single().expression as ResolvedAttribute
        assertEquals("concept:name", attr.xesStandardName)
        assertEquals(Scope.EVENT, attr.effectiveScope)
        assertEquals(AttributeKind.STANDARD, attr.kind)
    }

    @Test
    fun `from trace with hoisted event name yields TRACE-scoped resolution`() {
        val q = select(Scope.TRACE, listOf(RawSelectColumn(expression = ref("name", scopeHint = "e", hoisting = 1))))
        val r = resolver.resolve(q) as ResolvedQuery.Select
        val attr = r.columns.single().expression as ResolvedAttribute
        assertEquals(Scope.TRACE, attr.effectiveScope)
    }

    @Test
    fun `literals are typed`() {
        val q = select(
            Scope.EVENT,
            listOf(RawSelectColumn(expression = RawLiteral("42", RawLiteralKind.NUMBER, loc), alias = "n")),
        )
        val r = resolver.resolve(q) as ResolvedQuery.Select
        val lit = r.columns.single().expression as TypedLiteral
        assertEquals(Type.NUMBER, lit.type)
        assertEquals(42.0, lit.value)
    }

    @Test
    fun `binary comparison yields BOOLEAN`() {
        val expr = RawBinaryOp(
            BinaryOperator.EQ,
            ref("name"),
            RawLiteral("'Task A'", RawLiteralKind.STRING, loc),
            loc,
        )
        val q = select(Scope.EVENT, emptyList(), where = expr)
        val r = resolver.resolve(q) as ResolvedQuery.Select
        val b = r.where as TypedBinaryOp
        assertEquals(Type.BOOLEAN, b.type)
        assertTrue(b.left is ResolvedAttribute)
        val lit = b.right as TypedLiteral
        assertEquals("Task A", lit.value)
    }

    @Test
    fun `count() becomes an Aggregation node`() {
        val expr = RawFunctionCall("count", listOf(ref("name")), loc)
        val q = select(Scope.EVENT, listOf(RawSelectColumn(expression = expr, alias = "c")))
        val r = resolver.resolve(q) as ResolvedQuery.Select
        val agg = r.columns.single().expression as Aggregation
        assertEquals("count", agg.name)
        assertEquals(Type.INTEGER, agg.type)
    }

    @Test
    fun `upper() becomes a ScalarFunction with STRING return type`() {
        val expr = RawFunctionCall("upper", listOf(ref("name")), loc)
        val q = select(Scope.EVENT, listOf(RawSelectColumn(expression = expr)))
        val r = resolver.resolve(q) as ResolvedQuery.Select
        val fn = r.columns.single().expression as ScalarFunction
        assertEquals("upper", fn.name)
        assertEquals(Type.STRING, fn.type)
    }

    @Test
    fun `order by propagates direction and resolves expression`() {
        val q = select(
            Scope.EVENT,
            listOf(RawSelectColumn(expression = ref("name"))),
            orderBy = listOf(RawOrderKey(ref("timestamp"), OrderDirection.DESC)),
        )
        val r = resolver.resolve(q) as ResolvedQuery.Select
        val key = r.orderBy.single()
        assertEquals(OrderDirection.DESC, key.direction)
        val attr = key.expression as ResolvedAttribute
        assertEquals("time:timestamp", attr.xesStandardName)
    }

    @Test
    fun `select star column is preserved`() {
        val q = select(Scope.EVENT, listOf(RawSelectColumn(expression = null, starAt = Scope.EVENT)))
        val r = resolver.resolve(q) as ResolvedQuery.Select
        val col = r.columns.single()
        assertEquals(Scope.EVENT, col.starAt)
        assertNotNull(r.from)
    }

    @Test
    fun `delete query is resolved to ResolvedQuery Delete`() {
        val q = RawQuery.Delete(
            from = Scope.EVENT,
            where = RawBinaryOp(
                BinaryOperator.EQ, ref("name"),
                RawLiteral("'X'", RawLiteralKind.STRING, loc), loc,
            ),
            location = loc,
        )
        val r = resolver.resolve(q) as ResolvedQuery.Delete
        assertEquals(Scope.EVENT, r.from)
        assertNotNull(r.where)
    }
}
