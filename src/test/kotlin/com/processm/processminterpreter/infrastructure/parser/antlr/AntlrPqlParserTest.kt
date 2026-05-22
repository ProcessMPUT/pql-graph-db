package com.processm.processminterpreter.infrastructure.parser.antlr

import com.processm.processminterpreter.domain.pql.syntax.BinaryOperator
import com.processm.processminterpreter.domain.pql.syntax.OrderDirection
import com.processm.processminterpreter.domain.pql.syntax.RawAttributeRef
import com.processm.processminterpreter.domain.pql.syntax.RawBinaryOp
import com.processm.processminterpreter.domain.pql.syntax.RawFunctionCall
import com.processm.processminterpreter.domain.pql.syntax.RawInList
import com.processm.processminterpreter.domain.pql.syntax.RawLiteral
import com.processm.processminterpreter.domain.pql.syntax.RawLiteralKind
import com.processm.processminterpreter.domain.pql.syntax.RawQuery
import com.processm.processminterpreter.domain.pql.syntax.RawUnaryOp
import com.processm.processminterpreter.domain.pql.syntax.UnaryOperator
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.error.PQLSyntaxException
import com.processm.processminterpreter.domain.pql.error.Problem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * End-to-end test of the ANTLR adapter: PQL text → RawQuery.
 * Exercises AstBuilder and AntlrPqlParser in one shot — they're useless apart.
 */
class AntlrPqlParserTest {

    private val parser = AntlrPqlParser()

    @Test
    fun `empty SELECT expands to three star columns`() {
        val q = parser.parse("where e:name = 'A'") as RawQuery.Select
        assertEquals(3, q.columns.size)
        assertEquals(setOf(Scope.LOG, Scope.TRACE, Scope.EVENT), q.columns.mapNotNull { it.starAt }.toSet())
    }

    @Test
    fun `explicit SELECT star also produces three star columns`() {
        val q = parser.parse("select *") as RawQuery.Select
        assertEquals(3, q.columns.count { it.starAt != null })
    }

    @Test
    fun `scoped select all SELECT e star`() {
        val q = parser.parse("select e:*") as RawQuery.Select
        assertEquals(1, q.columns.size)
        assertEquals(Scope.EVENT, q.columns.single().starAt)
    }

    @Test
    fun `SELECT with mixed scoped star and attribute`() {
        val q = parser.parse("select t:*, e:name") as RawQuery.Select
        assertEquals(2, q.columns.size)
        assertEquals(Scope.TRACE, q.columns[0].starAt)
        val attr = q.columns[1].expression as RawAttributeRef
        assertEquals("name", attr.name)
        assertEquals("e", attr.scopeHint)
    }

    @Test
    fun `attribute reference preserves scope hint and name`() {
        val q = parser.parse("select e:timestamp") as RawQuery.Select
        val a = q.columns[0].expression as RawAttributeRef
        assertEquals("timestamp", a.name)
        assertEquals("e", a.scopeHint)
        assertEquals(0, a.hoisting)
    }

    @Test
    fun `hoisted attribute records hoisting level`() {
        val q = parser.parse("select ^e:name") as RawQuery.Select
        val a = q.columns[0].expression as RawAttributeRef
        assertEquals(1, a.hoisting)
        assertEquals("name", a.name)
        assertEquals("e", a.scopeHint)
    }

    @Test
    fun `WHERE with equality produces BinaryOp EQ`() {
        val q = parser.parse("where e:name = 'Task A'") as RawQuery.Select
        val bin = q.where as RawBinaryOp
        assertEquals(BinaryOperator.EQ, bin.op)
        assertEquals("name", (bin.left as RawAttributeRef).name)
        assertEquals(RawLiteralKind.STRING, (bin.right as RawLiteral).kind)
    }

    @Test
    fun `WHERE AND and OR compose correctly`() {
        val q = parser.parse("where e:name = 'A' and e:name = 'B' or e:name = 'C'") as RawQuery.Select
        // AND has higher precedence than OR → top-level is OR
        val or = q.where as RawBinaryOp
        assertEquals(BinaryOperator.OR, or.op)
        val leftAnd = or.left as RawBinaryOp
        assertEquals(BinaryOperator.AND, leftAnd.op)
    }

    @Test
    fun `NOT wraps logic expression in unary`() {
        val q = parser.parse("where not e:name = 'A'") as RawQuery.Select
        val not = q.where as RawUnaryOp
        assertEquals(UnaryOperator.NOT, not.op)
    }

    @Test
    fun `IS NULL becomes binary IS with null literal`() {
        val q = parser.parse("where [e:customX] is null") as RawQuery.Select
        val bin = q.where as RawBinaryOp
        assertEquals(BinaryOperator.IS, bin.op)
        val lit = bin.right as RawLiteral
        assertEquals(RawLiteralKind.NULL, lit.kind)
    }

    @Test
    fun `IS NOT NULL becomes binary IS_NOT with null literal`() {
        val q = parser.parse("where [e:customX] is not null") as RawQuery.Select
        val bin = q.where as RawBinaryOp
        assertEquals(BinaryOperator.IS_NOT, bin.op)
    }

    @Test
    fun `IN list is lifted into RawInList on right`() {
        val q = parser.parse("where e:name in ('A', 'B', 'C')") as RawQuery.Select
        val bin = q.where as RawBinaryOp
        assertEquals(BinaryOperator.IN, bin.op)
        val list = bin.right as RawInList
        assertEquals(3, list.values.size)
        assertTrue(list.values.all { it is RawLiteral })
    }

    @Test
    fun `LIKE becomes binary LIKE with string RHS`() {
        val q = parser.parse("where e:name like 'Task%'") as RawQuery.Select
        val bin = q.where as RawBinaryOp
        assertEquals(BinaryOperator.LIKE, bin.op)
        assertEquals(RawLiteralKind.STRING, (bin.right as RawLiteral).kind)
    }

    @Test
    fun `arithmetic preserves operator precedence`() {
        val q = parser.parse("select 1 + 2 * 3") as RawQuery.Select
        // + is top-level since * binds tighter
        val plus = q.columns[0].expression as RawBinaryOp
        assertEquals(BinaryOperator.PLUS, plus.op)
        val mul = plus.right as RawBinaryOp
        assertEquals(BinaryOperator.MUL, mul.op)
    }

    @Test
    fun `parentheses do not appear as AST nodes`() {
        val q = parser.parse("select (1 + 2) * 3") as RawQuery.Select
        val top = q.columns[0].expression as RawBinaryOp
        assertEquals(BinaryOperator.MUL, top.op)
        val left = top.left as RawBinaryOp
        assertEquals(BinaryOperator.PLUS, left.op)
    }

    @Test
    fun `aggregation function wraps ID into attribute ref argument`() {
        val q = parser.parse("select count(e:name)") as RawQuery.Select
        val fn = q.columns[0].expression as RawFunctionCall
        assertEquals("count", fn.name)
        assertEquals(1, fn.arguments.size)
        assertTrue(fn.arguments[0] is RawAttributeRef)
    }

    @Test
    fun `scalar functions are captured with arguments`() {
        val q = parser.parse("select year(e:timestamp)") as RawQuery.Select
        val fn = q.columns[0].expression as RawFunctionCall
        assertEquals("year", fn.name)
        assertTrue(fn.arguments[0] is RawAttributeRef)
    }

    @Test
    fun `now has zero arguments`() {
        val q = parser.parse("select now()") as RawQuery.Select
        val fn = q.columns[0].expression as RawFunctionCall
        assertEquals("now", fn.name)
        assertTrue(fn.arguments.isEmpty())
    }

    @Test
    fun `GROUP BY produces list of attribute refs`() {
        val q = parser.parse("select e:name group by e:name") as RawQuery.Select
        assertEquals(1, q.groupBy.size)
        assertEquals("name", (q.groupBy[0] as RawAttributeRef).name)
    }

    @Test
    fun `ORDER BY preserves direction`() {
        val q = parser.parse("select e:name order by e:timestamp desc") as RawQuery.Select
        assertEquals(1, q.orderBy.size)
        assertEquals(OrderDirection.DESC, q.orderBy[0].direction)
    }

    @Test
    fun `LIMIT with scoped numbers populates correct slots`() {
        val q = parser.parse("select e:name limit l:5, t:10, e:20") as RawQuery.Select
        assertEquals(5L, q.limit.log)
        assertEquals(10L, q.limit.trace)
        assertEquals(20L, q.limit.event)
    }

    @Test
    fun `single LIMIT zero is rejected`() {
        val ex = assertThrows<PQLSyntaxException> { parser.parse("select e:name limit l:0") }
        assertEquals(Problem.PositiveIntegerRequired, ex.problem)
    }

    @Test
    fun `any LIMIT zero is rejected`() {
        val ex = assertThrows<PQLSyntaxException> { parser.parse("select e:name limit l:1, e:0") }
        assertEquals(Problem.PositiveIntegerRequired, ex.problem)
    }

    @Test
    fun `OFFSET zero is rejected`() {
        val ex = assertThrows<PQLSyntaxException> { parser.parse("select e:name offset l:0") }
        assertEquals(Problem.PositiveIntegerRequired, ex.problem)
    }

    @Test
    fun `DELETE defaults to EVENT when no scope given`() {
        val q = parser.parse("delete") as RawQuery.Delete
        assertEquals(Scope.EVENT, q.from)
        assertNull(q.where)
    }

    @Test
    fun `DELETE with explicit scope and WHERE`() {
        val q = parser.parse("delete t where t:name = 'C1'") as RawQuery.Delete
        assertEquals(Scope.TRACE, q.from)
        assertNotNull(q.where)
    }

    @Test
    fun `syntax error surfaces as PQLSyntaxException`() {
        assertThrows<PQLSyntaxException> { parser.parse("select from where") }
    }
}
