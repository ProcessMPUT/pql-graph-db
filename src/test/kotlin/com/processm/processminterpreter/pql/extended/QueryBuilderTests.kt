package com.processm.processminterpreter.pql.extended

import QLLexer
import QLParser
import com.processm.processminterpreter.pql.model.*
import com.processm.processminterpreter.pql.visitor.*
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for QueryBuilder.
 *
 * Tests parsing of PQL queries into Query objects.
 */
class QueryBuilderTests {

    /**
     * Helper function to parse a PQL string into a Query object.
     */
    private fun parseQuery(pql: String): Query {
        val charStream = CharStreams.fromString(pql)
        val lexer = QLLexer(charStream)
        val tokens = CommonTokenStream(lexer)
        val parser = QLParser(tokens)

        // Remove default error listeners
        parser.removeErrorListeners()

        // Add custom error listener to throw exceptions on syntax errors
        parser.addErrorListener(object : org.antlr.v4.runtime.BaseErrorListener() {
            override fun syntaxError(
                recognizer: org.antlr.v4.runtime.Recognizer<*, *>?,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String?,
                e: org.antlr.v4.runtime.RecognitionException?,
            ) {
                throw PQLSyntaxException(line, charPositionInLine, "Parser error: $msg")
            }
        })

        val parseTree = parser.query()
        val builder = QueryBuilder()

        return builder.build(parseTree, pql)
    }

    // ========================================
    // select CLAUSE TESTS
    // ========================================

    @Test
    fun selectImplicitAllTest() {
        val query = parseQuery("")

        // Implicit select * for all scopes
        // Implicit select * is TRUE for empty query (defaults to select *)
        assertTrue(query.isImplicitSelectAll[Scope.Event] == true)
        assertTrue(query.isImplicitSelectAll[Scope.Trace] == true)
        assertTrue(query.isImplicitSelectAll[Scope.Log] == true)
    }

    @Test
    fun selectExplicitAllTest() {
        val query = parseQuery("select *")

        // Explicit select *
        assertEquals(true, query.selectAll[Scope.Event])
    }

    @Test
    fun selectSingleAttributeTest() {
        val query = parseQuery("select e:name")

        val eventAttrs = query.selectStandardAttributes[Scope.Event]
        assertEquals(1, eventAttrs?.size)

        val attr = eventAttrs?.first()
        assertEquals("name", attr?.name)
        assertEquals(Scope.Event, attr?.scope)
        assertTrue(attr?.isStandard == true)
    }

    @Test
    fun selectMultipleAttributesTest() {
        val query = parseQuery("select e:name, e:timestamp, t:name")

        // EVENT scope: 2 attributes
        val eventAttrs = query.selectStandardAttributes[Scope.Event]
        assertEquals(2, eventAttrs?.size)

        // TRACE scope: 1 attribute
        val traceAttrs = query.selectStandardAttributes[Scope.Trace]
        assertEquals(1, traceAttrs?.size)
    }

    @Test
    fun selectCustomAttributeTest() {
        val query = parseQuery("select e:customAttr")

        val eventAttrs = query.selectOtherAttributes[Scope.Event]
        assertEquals(1, eventAttrs?.size)

        val attr = eventAttrs?.first()
        assertEquals("customAttr", attr?.name)
        assertFalse(attr?.isStandard == true)
    }

    @Test
    fun selectFunctionTest() {
        val query = parseQuery("select count(e:id)")

        val eventExprs = query.selectExpressions[Scope.Event]
        assertEquals(1, eventExprs?.size)

        val expr = eventExprs?.first()
        assertTrue(expr is com.processm.processminterpreter.pql.model.Function)
        val func = expr as com.processm.processminterpreter.pql.model.Function
        assertEquals("count", func.name)
        assertEquals(FunctionType.Aggregation, func.functionType)
    }

    @Test
    fun selectScopedAllTest() {
        val query = parseQuery("select e:*")

        assertEquals(true, query.selectAll[Scope.Event])
        assertEquals(false, query.selectAll[Scope.Trace])
    }

    // ========================================
    // delete CLAUSE TESTS
    // ========================================

    @Test
    fun deleteDefaultScopeTest() {
        val query = parseQuery("delete")

        assertEquals(Scope.Event, query.deleteScope)
    }

    @Test
    fun deleteExplicitScopeTest() {
        val query = parseQuery("delete trace")

        assertEquals(Scope.Trace, query.deleteScope)
    }

    // ========================================
    // where CLAUSE TESTS
    // ========================================

    @Test
    fun whereSimpleComparisonTest() {
        val query = parseQuery("select * where e:name = \"A\"")

        assertNotNull(query.whereExpression)
        assertTrue(query.whereExpression is BinaryOperator)

        val op = query.whereExpression as BinaryOperator
        assertEquals("=", op.operator)
        assertTrue(op.left is Attribute)
        assertTrue(op.right is StringLiteral)
    }

    @Test
    fun whereAndOrTest() {
        val query = parseQuery("select * where e:name = \"A\" and e:timestamp > D2020-01-01")

        assertTrue(query.whereExpression is BinaryOperator)

        val andOp = query.whereExpression as BinaryOperator
        assertEquals("AND", andOp.operator)
        assertTrue(andOp.left is BinaryOperator)
        assertTrue(andOp.right is BinaryOperator)
    }

    @Test
    fun whereNotTest() {
        val query = parseQuery("select * where not e:name = \"A\"")

        assertTrue(query.whereExpression is UnaryOperator)

        val notOp = query.whereExpression as UnaryOperator
        assertEquals("NOT", notOp.operator)
        assertTrue(notOp.operand is BinaryOperator)
    }

    @Test
    fun whereIsNullTest() {
        val query = parseQuery("select * where e:name is null")

        assertTrue(query.whereExpression is UnaryOperator)

        val op = query.whereExpression as UnaryOperator
        assertEquals("IS NULL", op.operator)
        assertTrue(op.operand is Attribute)
    }

    @Test
    fun whereInTest() {
        val query = parseQuery("select * where e:name in (\"A\", \"B\", \"C\")")

        assertTrue(query.whereExpression is BinaryOperator)

        val inOp = query.whereExpression as BinaryOperator
        assertEquals("IN", inOp.operator)
        assertTrue(inOp.left is Attribute)
        assertTrue(inOp.right is InListExpression)

        val inList = inOp.right as InListExpression
        assertEquals(3, inList.values.size)
    }

    @Test
    fun whereLikeTest() {
        val query = parseQuery("select * where e:name like \"%pattern%\"")

        assertTrue(query.whereExpression is BinaryOperator)

        val likeOp = query.whereExpression as BinaryOperator
        assertEquals("LIKE", likeOp.operator)
        assertTrue(likeOp.left is Attribute)
        assertTrue(likeOp.right is StringLiteral)
    }

    // ========================================
    // group by CLAUSE TESTS
    // ========================================

    @Test
    fun groupBySingleAttributeTest() {
        val query = parseQuery("select e:name, count(e:id) group by e:name")

        val eventGroupBy = query.groupByStandardAttributes[Scope.Event]
        assertEquals(1, eventGroupBy?.size)

        val attr = eventGroupBy?.first()
        assertEquals("name", attr?.name)
        assertEquals(Scope.Event, attr?.scope)
    }

    @Test
    fun groupByMultipleAttributesTest() {
        val query = parseQuery("select e:name, t:name, count(e:id) group by e:name, t:name")

        // EVENT scope
        val eventGroupBy = query.groupByStandardAttributes[Scope.Event]
        assertEquals(1, eventGroupBy?.size)

        // TRACE scope
        val traceGroupBy = query.groupByStandardAttributes[Scope.Trace]
        assertEquals(1, traceGroupBy?.size)

        // isGroupBy should be true
        assertTrue(query.isGroupBy[Scope.Event] == true)
        assertTrue(query.isGroupBy[Scope.Trace] == true)
    }

    // ========================================
    // order by CLAUSE TESTS
    // ========================================

    @Test
    fun orderBySingleAttributeTest() {
        val query = parseQuery("select * order by e:timestamp")

        val eventOrderBy = query.orderByExpressions[Scope.Event]
        assertEquals(1, eventOrderBy?.size)

        val ordered = eventOrderBy?.first()
        assertTrue(ordered?.base is Attribute)
        assertEquals(OrderDirection.Ascending, ordered?.direction) // Default
    }

    @Test
    fun orderByWithDirectionTest() {
        val query = parseQuery("select * order by e:timestamp desc")

        val eventOrderBy = query.orderByExpressions[Scope.Event]
        assertEquals(1, eventOrderBy?.size)

        val ordered = eventOrderBy?.first()
        assertEquals(OrderDirection.Descending, ordered?.direction)
    }

    @Test
    fun orderByMultipleExpressionsTest() {
        val query = parseQuery("select * order by e:timestamp asc, e:name desc")

        val eventOrderBy = query.orderByExpressions[Scope.Event]
        assertEquals(2, eventOrderBy?.size)

        assertEquals(OrderDirection.Ascending, eventOrderBy?.get(0)?.direction)
        assertEquals(OrderDirection.Descending, eventOrderBy?.get(1)?.direction)
    }

    // ========================================
    // limit & offset CLAUSE TESTS
    // ========================================

    @Test
    fun limitSingleScopeTest() {
        val query = parseQuery("select * limit 100")

        assertEquals(100L, query.limit[Scope.Event])
        assertNull(query.limit[Scope.Trace])
        assertNull(query.limit[Scope.Log])
    }

    @Test
    fun limitMultipleScopesTest() {
        val query = parseQuery("select * limit 100, 50, 10")

        assertEquals(100L, query.limit[Scope.Event])
        assertEquals(50L, query.limit[Scope.Trace])
        assertEquals(10L, query.limit[Scope.Log])
    }

    @Test
    fun limitScopedSyntaxTest() {
        val query = parseQuery("select * limit l:5, t:10, e:20")

        assertEquals(20L, query.limit[Scope.Event])
        assertEquals(10L, query.limit[Scope.Trace])
        assertEquals(5L, query.limit[Scope.Log])
    }

    @Test
    fun offsetSingleScopeTest() {
        val query = parseQuery("select * offset 10")

        assertEquals(10L, query.offset[Scope.Event])
        assertNull(query.offset[Scope.Trace])
    }

    @Test
    fun offsetScopedSyntaxTest() {
        val query = parseQuery("select * offset l:1, t:2")

        assertEquals(1L, query.offset[Scope.Log])
        assertEquals(2L, query.offset[Scope.Trace])
        assertNull(query.offset[Scope.Event])
    }

    @Test
    fun offsetMultipleScopesTest() {
        val query = parseQuery("select * offset 10, 5, 1")

        assertEquals(10L, query.offset[Scope.Event])
        assertEquals(5L, query.offset[Scope.Trace])
        assertEquals(1L, query.offset[Scope.Log])
    }

    // ========================================
    // COMPLEX QUERY TESTS
    // ========================================

    @Test
    fun complexReadQueryTest() {
        val pql = """
            select e:name, e:timestamp, count(e:id)
            where e:name = "A" and e:timestamp > D2020-01-01
            group by e:name, e:timestamp
            order by e:timestamp desc
            limit 100
            offset 10
        """.trimIndent()

        val query = parseQuery(pql)

        // select
        assertEquals(2, query.selectStandardAttributes[Scope.Event]?.size)
        assertEquals(1, query.selectExpressions[Scope.Event]?.size)

        // where
        assertNotNull(query.whereExpression)
        assertTrue(query.whereExpression is BinaryOperator)

        // group by
        assertEquals(2, query.groupByStandardAttributes[Scope.Event]?.size)

        // order by
        assertEquals(1, query.orderByExpressions[Scope.Event]?.size)

        // limit/offset
        assertEquals(100L, query.limit[Scope.Event])
        assertEquals(10L, query.offset[Scope.Event])
    }

    @Test
    fun complexDeleteQueryTest() {
        val pql = """
            delete trace
            where t:name = "BadTrace"
            order by t:timestamp desc
            limit 10
        """.trimIndent()

        val query = parseQuery(pql)

        // delete
        assertEquals(Scope.Trace, query.deleteScope)

        // where
        assertNotNull(query.whereExpression)

        // order by
        assertNotNull(query.orderByExpressions[Scope.Trace])

        // limit
        assertEquals(10L, query.limit[Scope.Event]) // First limit goes to EVENT
    }

    // ========================================
    // ARITHMETIC EXPRESSION TESTS
    // ========================================

    @Test
    fun arithExprAdditionTest() {
        val query = parseQuery("select e:cost + e:tax where e:cost + e:tax > 100")

        // select has one expression (addition)
        val selectExpr = query.selectExpressions[Scope.Event]?.first()
        assertTrue(selectExpr is BinaryOperator)
        assertEquals("+", (selectExpr as BinaryOperator).operator)

        // where also has addition
        val whereOp = query.whereExpression as BinaryOperator
        assertTrue(whereOp.left is BinaryOperator)
        assertEquals("+", (whereOp.left as BinaryOperator).operator)
    }

    @Test
    fun arithExprParenthesesTest() {
        val query = parseQuery("select (e:cost + e:tax) * e:quantity")

        val expr = query.selectExpressions[Scope.Event]?.first()
        assertTrue(expr is BinaryOperator)
        assertEquals("*", (expr as BinaryOperator).operator)

        // Left side should be addition
        assertTrue(expr.left is BinaryOperator)
        assertEquals("+", (expr.left as BinaryOperator).operator)
    }

    // ========================================
    // VALIDATION TESTS
    // ========================================

    @Test
    fun validationSelectAllConflictTest() {
        // This should throw during build because select * conflicts with specific attributes
        val pql = "select *, e:name"

        assertThrows(PQLSemanticException::class.java) {
            parseQuery(pql)
        }
    }

    @Test
    fun validationGroupByMissingTest() {
        // This should throw because e:name is not in group by but aggregation is used
        val pql = "select e:name, count(e:id)"

        assertThrows(PQLSemanticException::class.java) {
            parseQuery(pql)
        }
    }

    // ========================================
    // FUNCTION TESTS
    // ========================================

    @Test
    fun scalarFunctionYearTest() {
        val query = parseQuery("select year(e:timestamp)")

        val func = query.selectExpressions[Scope.Event]?.first() as com.processm.processminterpreter.pql.model.Function
        assertEquals("year", func.name)
        assertEquals(FunctionType.Scalar, func.functionType)
        assertEquals(1, func.children.size)
    }

    @Test
    fun scalarFunctionNowTest() {
        val query = parseQuery("select now()")

        val func = query.selectExpressions[Scope.Event]?.first() as com.processm.processminterpreter.pql.model.Function
        assertEquals("now", func.name)
        assertEquals(FunctionType.Scalar, func.functionType)
        assertEquals(0, func.children.size)
    }

    @Test
    fun aggregationFunctionCountTest() {
        val query = parseQuery("select count(e:id) group by e:name")

        val func = query.selectExpressions[Scope.Event]?.first() as com.processm.processminterpreter.pql.model.Function
        assertEquals("count", func.name)
        assertEquals(FunctionType.Aggregation, func.functionType)
        assertEquals(1, func.children.size)
    }

    // ========================================
    // LITERAL TESTS
    // ========================================

    @Test
    fun literalStringTest() {
        val query = parseQuery("select * where e:name = \"test\"")

        val op = query.whereExpression as BinaryOperator
        val literal = op.right as StringLiteral
        assertEquals("test", literal.value)
    }

    @Test
    fun literalNumberTest() {
        val query = parseQuery("select * where e:cost > 100")

        val op = query.whereExpression as BinaryOperator
        val literal = op.right as NumberLiteral
        assertEquals(100.0, literal.value)
    }

    @Test
    fun literalBooleanTest() {
        val query = parseQuery("select * where e:active = true")

        val op = query.whereExpression as BinaryOperator
        val literal = op.right as BooleanLiteral
        assertEquals(true, literal.value)
    }

    @Test
    fun literalDateTimeTest() {
        val query = parseQuery("select * where e:timestamp > D2020-01-01")

        val op = query.whereExpression as BinaryOperator
        val literal = op.right as DateTimeLiteral
        assertNotNull(literal.value)
    }

    @Test
    fun literalNullTest() {
        val query = parseQuery("select * where e:name is null")

        val unaryOp = query.whereExpression as UnaryOperator
        assertTrue(unaryOp.operand is Attribute)
    }
}
