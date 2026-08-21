package com.processm.processminterpreter.pql.semantics

import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.AttributeScope
import com.processm.processminterpreter.pql.catalog.BinaryOperator
import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.pql.ast.PqlQuery
import com.processm.processminterpreter.pql.catalog.OrderDirection
import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.catalog.Type
import com.processm.processminterpreter.pql.error.InvalidScopeHoistingException
import com.processm.processminterpreter.pql.error.PQLSyntaxException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class ResolverTest {
    private val loc = SourceLocation(1, 0)
    private val resolver = Resolver()

    private fun ref(
        name: String,
        scopeHint: String? = null,
        hoisting: Int = 0,
        wasBracketed: Boolean = false,
    ) = PqlExpression.AttributeRef(
        rawText = name, hoisting = hoisting, scopeHint = scopeHint,
        name = name, wasBracketed = wasBracketed, location = loc,
    )

    private fun select(
        from: Scope,
        columns: List<PqlQuery.SelectColumn>,
        where: PqlExpression? = null,
        groupBy: List<PqlExpression> = emptyList(),
        orderBy: List<PqlQuery.OrderKey> = emptyList(),
    ) = PqlQuery.Select(
        from = from, columns = columns,
        where = where, groupBy = groupBy, orderBy = orderBy,
        location = loc,
    )

    @Test
    fun `resolves simple select of event name`() {
        val q = select(Scope.EVENT, listOf(PqlQuery.SelectColumn(expression = ref("name"))))
        val r = resolver.resolve(q) as PqlQuery.Select
        val attr = r.columns.single().expression as PqlExpression.Attribute
        assertEquals("concept:name", attr.xesStandardName)
        assertEquals(Scope.EVENT, attr.effectiveScope)
        assertEquals(AttributeKind.STANDARD, attr.kind)
    }

    @Test
    fun `from trace with hoisted event name yields TRACE-scoped resolution`() {
        val q = select(Scope.TRACE, listOf(PqlQuery.SelectColumn(expression = ref("name", scopeHint = "e", hoisting = 1))))
        val r = resolver.resolve(q) as PqlQuery.Select
        val attr = r.columns.single().expression as PqlExpression.Attribute
        assertEquals(Scope.TRACE, attr.effectiveScope)
    }

    @Test
    fun `literals are typed`() {
        val q = select(
            Scope.EVENT,
            listOf(PqlQuery.SelectColumn(expression = PqlExpression.Literal("42", PqlExpression.LiteralKind.NUMBER, location = loc), alias = "n")),
        )
        val r = resolver.resolve(q) as PqlQuery.Select
        val lit = r.columns.single().expression as PqlExpression.Literal
        assertEquals(Type.NUMBER, lit.type)
        assertEquals(42.0, lit.value)
    }

    @Test
    fun `binary comparison yields BOOLEAN`() {
        val expr = PqlExpression.Binary(
            BinaryOperator.EQ,
            ref("name"),
            PqlExpression.Literal("'Task A'", PqlExpression.LiteralKind.STRING, location = loc),
            location = loc,
        )
        val q = select(Scope.EVENT, emptyList(), where = expr)
        val r = resolver.resolve(q) as PqlQuery.Select
        val b = r.where as PqlExpression.Binary
        assertEquals(Type.BOOLEAN, b.type)
        assertTrue(b.left is PqlExpression.Attribute)
        val lit = b.right as PqlExpression.Literal
        assertEquals("Task A", lit.value)
    }

    @Test
    fun `count() becomes an Aggregation node`() {
        val expr = PqlExpression.Call("count", listOf(ref("name")), location = loc)
        val q = select(Scope.EVENT, listOf(PqlQuery.SelectColumn(expression = expr, alias = "c")))
        val r = resolver.resolve(q) as PqlQuery.Select
        val agg = r.columns.single().expression as PqlExpression.Aggregation
        assertEquals("count", agg.name)
        assertEquals(Type.INTEGER, agg.type)
    }

    @Test
    fun `upper() becomes a Call with STRING return type`() {
        val expr = PqlExpression.Call("upper", listOf(ref("name")), location = loc)
        val q = select(Scope.EVENT, listOf(PqlQuery.SelectColumn(expression = expr)))
        val r = resolver.resolve(q) as PqlQuery.Select
        val fn = r.columns.single().expression as PqlExpression.Call
        assertEquals("upper", fn.name)
        assertEquals(Type.STRING, fn.type)
    }

    @Test
    fun `order by propagates direction and resolves expression`() {
        val q = select(
            Scope.EVENT,
            listOf(PqlQuery.SelectColumn(expression = ref("name"))),
            orderBy = listOf(PqlQuery.OrderKey(ref("timestamp"), OrderDirection.DESC)),
        )
        val r = resolver.resolve(q) as PqlQuery.Select
        val key = r.orderBy.single()
        assertEquals(OrderDirection.DESC, key.direction)
        val attr = key.expression as PqlExpression.Attribute
        assertEquals("time:timestamp", attr.xesStandardName)
    }

    @Test
    fun `select star column is preserved`() {
        val q = select(Scope.EVENT, listOf(PqlQuery.SelectColumn(expression = null, starAt = Scope.EVENT)))
        val r = resolver.resolve(q) as PqlQuery.Select
        val col = r.columns.single()
        assertEquals(Scope.EVENT, col.starAt)
        assertNotNull(r.from)
    }

    @Test
    fun `delete query is resolved to PqlQuery Delete`() {
        val q = PqlQuery.Delete(
            from = Scope.EVENT,
            where = PqlExpression.Binary(
                BinaryOperator.EQ, ref("name"),
                PqlExpression.Literal("'X'", PqlExpression.LiteralKind.STRING, location = loc), location = loc,
            ),
            location = loc,
        )
        val r = resolver.resolve(q) as PqlQuery.Delete
        assertEquals(Scope.EVENT, r.from)
        assertNotNull(r.where)
    }

    // --- Attribute resolution (folded from AttributeResolverTest) ---

    private fun resolveAttr(
        raw: PqlExpression.AttributeRef,
        from: Scope,
        context: ResolutionContext = ResolutionContext(),
    ): PqlExpression.Attribute {
        val q = select(from, listOf(PqlQuery.SelectColumn(expression = raw)))
        val r = resolver.resolve(q, context) as PqlQuery.Select
        return r.columns.single().expression as PqlExpression.Attribute
    }

    @Test
    fun `resolves standard event name to concept colon name`() {
        val r = resolveAttr(ref("name"), from = Scope.EVENT)
        assertEquals(Scope.EVENT, r.effectiveScope)
        assertEquals("concept:name", r.xesStandardName)
        assertEquals(AttributeKind.STANDARD, r.kind)
        assertEquals(Type.STRING, r.type)
    }

    @Test
    fun `explicit scope hint overrides default`() {
        val r = resolveAttr(ref("name", scopeHint = "t"), from = Scope.EVENT)
        assertEquals(Scope.TRACE, r.baseScope)
        assertEquals(Scope.TRACE, r.effectiveScope)
        assertEquals("concept:name", r.xesStandardName)
    }

    @Test
    fun `hoisted event name resolves to trace-scoped concept colon name`() {
        val r = resolveAttr(ref("name", scopeHint = "e", hoisting = 1), from = Scope.TRACE)
        assertEquals(Scope.EVENT, r.baseScope)
        assertEquals(Scope.TRACE, r.effectiveScope)
        assertEquals("concept:name", r.xesStandardName)
        assertEquals(AttributeKind.STANDARD, r.kind)
    }

    @Test
    fun `hoisted event timestamp keeps event lookup and trace effective scope`() {
        val r = resolveAttr(ref("timestamp", scopeHint = "e", hoisting = 1), from = Scope.TRACE)
        assertEquals(Scope.EVENT, r.baseScope)
        assertEquals(Scope.TRACE, r.effectiveScope)
        assertEquals("time:timestamp", r.xesStandardName)
        assertEquals(AttributeKind.STANDARD, r.kind)
        assertEquals(Type.DATETIME, r.type)
    }

    @Test
    fun `hoisting beyond LOG throws`() {
        assertThrows<InvalidScopeHoistingException> {
            resolveAttr(ref("name", scopeHint = "l", hoisting = 1), from = Scope.LOG)
        }
    }

    @Test
    fun `bracketed non-standard attribute is accepted as custom`() {
        val r = resolveAttr(ref("customFoo", wasBracketed = true), from = Scope.EVENT)
        assertEquals(AttributeKind.CUSTOM, r.kind)
        assertEquals("customFoo", r.name)
        assertNull(r.xesStandardName)
    }

    @Test
    fun `unbracketed non-standard attribute throws NoSuchAttribute`() {
        assertThrows<PQLSyntaxException> {
            resolveAttr(ref("notAStandardName"), from = Scope.EVENT)
        }
    }

    @Test
    fun `logId resolves as system attribute at log scope`() {
        val r = resolveAttr(ref("logId", scopeHint = "l"), from = Scope.EVENT)

        assertEquals(AttributeKind.SYSTEM, r.kind)
        assertEquals(Scope.LOG, r.baseScope)
        assertEquals(Scope.LOG, r.effectiveScope)
        assertEquals("logId", r.name)
        assertNull(r.xesStandardName)
        assertEquals(Type.ID, r.type)
    }

    @Test
    fun `classifier reference preserves CLASSIFIER kind`() {
        val r = resolveAttr(
            ref("c:Activity"),
            from = Scope.EVENT,
            context = ResolutionContext(
                classifiers = listOf(Classifier("Activity", listOf("concept:name", "lifecycle:transition"))),
            ),
        )
        assertEquals(AttributeKind.CLASSIFIER, r.kind)
        assertEquals("c:Activity", r.name)
        assertEquals("Activity", r.classifierName)
        assertEquals(listOf("concept:name", "lifecycle:transition"), r.classifierKeys)
    }

    @Test
    fun `classifier prefix with full classifier keyword`() {
        val r = resolveAttr(
            ref("classifier:Resource"),
            from = Scope.EVENT,
            context = ResolutionContext(
                classifiers = listOf(Classifier("Resource", listOf("org:resource", "lifecycle:transition"))),
            ),
        )
        assertEquals(AttributeKind.CLASSIFIER, r.kind)
        assertEquals("Resource", r.classifierName)
    }

    @Test
    fun `classifier resolution respects trace and event scope`() {
        val context = ResolutionContext(
            classifiers = listOf(
                Classifier("Shared", listOf("concept:name")),
                Classifier("Shared", listOf("org:group"), AttributeScope.TRACE),
            ),
        )

        val event = resolveAttr(ref("c:Shared", scopeHint = "e"), from = Scope.EVENT, context = context)
        val trace = resolveAttr(ref("c:Shared", scopeHint = "t"), from = Scope.TRACE, context = context)

        assertEquals(listOf("concept:name"), event.classifierKeys)
        assertEquals(listOf("org:group"), trace.classifierKeys)
    }

    // --- Hoisting (folded from HoistingResolverTest) ---

    @Test
    fun `zero hoisting returns base scope`() {
        assertEquals(Scope.EVENT, resolveAttr(ref("name", scopeHint = "e"), from = Scope.EVENT).effectiveScope)
        assertEquals(Scope.TRACE, resolveAttr(ref("name", scopeHint = "t"), from = Scope.EVENT).effectiveScope)
        assertEquals(Scope.LOG, resolveAttr(ref("name", scopeHint = "l"), from = Scope.EVENT).effectiveScope)
    }

    @Test
    fun `event hoisted once becomes trace`() {
        assertEquals(Scope.TRACE, resolveAttr(ref("name", scopeHint = "e", hoisting = 1), from = Scope.TRACE).effectiveScope)
    }

    @Test
    fun `event hoisted twice becomes log`() {
        assertEquals(Scope.LOG, resolveAttr(ref("name", scopeHint = "e", hoisting = 2), from = Scope.EVENT).effectiveScope)
    }

    @Test
    fun `trace hoisted once becomes log`() {
        assertEquals(Scope.LOG, resolveAttr(ref("name", scopeHint = "t", hoisting = 1), from = Scope.TRACE).effectiveScope)
    }

    @Test
    fun `trace hoisted twice throws`() {
        assertThrows<InvalidScopeHoistingException> {
            resolveAttr(ref("name", scopeHint = "t", hoisting = 2), from = Scope.TRACE)
        }
    }

    @Test
    fun `negative hoisting throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            resolveAttr(ref("name", scopeHint = "e", hoisting = -1), from = Scope.EVENT)
        }
    }

    // --- Literal typing (folded from LiteralTyperTest) ---

    private fun typeLiteral(rawText: String, kind: PqlExpression.LiteralKind): PqlExpression.Literal {
        val q = select(
            Scope.EVENT,
            listOf(PqlQuery.SelectColumn(expression = PqlExpression.Literal(rawText, kind, location = loc))),
        )
        val r = resolver.resolve(q) as PqlQuery.Select
        return r.columns.single().expression as PqlExpression.Literal
    }

    @Test
    fun `date-only D literal is typed as start of day datetime`() {
        val typed = typeLiteral("D2007-01-01", PqlExpression.LiteralKind.DATETIME)

        assertEquals(Type.DATETIME, typed.type)
        assertEquals(LocalDateTime.of(2007, 1, 1, 0, 0), typed.value)
    }

    @Test
    fun `month-only D literal is typed as first day of month`() {
        val typed = typeLiteral("D2007-01", PqlExpression.LiteralKind.DATETIME)

        assertEquals(Type.DATETIME, typed.type)
        assertEquals(LocalDateTime.of(2007, 1, 1, 0, 0), typed.value)
    }

    @Test
    fun `compact date D literal is typed as start of day datetime`() {
        val typed = typeLiteral("D20070101", PqlExpression.LiteralKind.DATETIME)

        assertEquals(Type.DATETIME, typed.type)
        assertEquals(LocalDateTime.of(2007, 1, 1, 0, 0), typed.value)
    }
}
