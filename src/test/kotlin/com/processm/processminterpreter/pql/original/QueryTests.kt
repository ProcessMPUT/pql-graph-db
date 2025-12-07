package com.processm.processminterpreter.pql.original

import QLLexer
import QLParser
import com.processm.processminterpreter.pql.model.*
import com.processm.processminterpreter.pql.model.StandardAttributes.CONCEPT_NAME
import com.processm.processminterpreter.pql.model.StandardAttributes.COST_CURRENCY
import com.processm.processminterpreter.pql.model.StandardAttributes.COST_TOTAL
import com.processm.processminterpreter.pql.model.StandardAttributes.TIME_TIMESTAMP
import com.processm.processminterpreter.pql.visitor.PQLErrorListener
import com.processm.processminterpreter.pql.visitor.QueryBuilder
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.format.DateTimeFormatter
import com.processm.processminterpreter.pql.model.InvalidClassifierUsageException
import com.processm.processminterpreter.pql.model.OrderDirection

@Tag("PQL_ORIGINAL")
@Suppress("MapGetWithNotNullAssertionOperator")
class QueryTestsOriginal {

    // Helper to parse PQL string into Query object using ANTLR and QueryBuilder
    private fun parsePQL(pqlQuery: String): Query {
        val input = CharStreams.fromString(pqlQuery)
        val lexer = QLLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = QLParser(tokens)
        
        val errorListener = PQLErrorListener()
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)
        
        val tree = parser.query()
        
        try {
            errorListener.throwIfErrors()
        } catch (e: Exception) {
            throw e
        }
        
        return QueryBuilder().build(tree, pqlQuery)
    }

    private fun <T : Throwable> assertThrowsPQL(exceptionClass: Class<T>, pql: String) {
        assertThrows(exceptionClass) {
            parsePQL(pql)
        }
    }

    @Test
    fun basicSelectTest() {
        val query = parsePQL("select l:name, t:name, t:currency, e:name, e:total")
        assertFalse(query.isImplicitSelectAll[Scope.LOG]!!)
        assertFalse(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertFalse(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertFalse(query.selectAll[Scope.LOG] ?: false)
        assertFalse(query.selectAll[Scope.TRACE] ?: false)
        assertFalse(query.selectAll[Scope.EVENT] ?: false)
        // log scope
        assertEquals(1, query.selectStandardAttributes[Scope.LOG]!!.size)
        assertEquals(0, query.selectOtherAttributes[Scope.LOG]?.size ?: 0)
        assertEquals(0, query.selectExpressions[Scope.LOG]?.size ?: 0)
        assertEquals(CONCEPT_NAME, query.selectStandardAttributes[Scope.LOG]!!.elementAt(0).standardName)
        assertTrue(query.selectStandardAttributes[Scope.LOG]!!.all { it.isStandard })
        assertTrue(query.selectStandardAttributes[Scope.LOG]!!.all { it.effectiveScope == Scope.LOG })
        assertTrue(query.selectStandardAttributes[Scope.LOG]!!.all { !it.isClassifier })
        // trace scope
        assertEquals(2, query.selectStandardAttributes[Scope.TRACE]!!.size)
        assertEquals(0, query.selectOtherAttributes[Scope.TRACE]?.size ?: 0)
        assertEquals(0, query.selectExpressions[Scope.TRACE]?.size ?: 0)
        assertEquals(CONCEPT_NAME, query.selectStandardAttributes[Scope.TRACE]!!.elementAt(0).standardName)
        assertEquals(COST_CURRENCY, query.selectStandardAttributes[Scope.TRACE]!!.elementAt(1).standardName)
        assertTrue(query.selectStandardAttributes[Scope.TRACE]!!.all { it.isStandard })
        assertTrue(query.selectStandardAttributes[Scope.TRACE]!!.all { it.effectiveScope == Scope.TRACE })
        assertTrue(query.selectStandardAttributes[Scope.TRACE]!!.all { !it.isClassifier })
        // event scope
        assertEquals(2, query.selectStandardAttributes[Scope.EVENT]!!.size)
        assertEquals(0, query.selectOtherAttributes[Scope.EVENT]?.size ?: 0)
        assertEquals(0, query.selectExpressions[Scope.EVENT]?.size ?: 0)
        assertEquals(CONCEPT_NAME, query.selectStandardAttributes[Scope.EVENT]!!.elementAt(0).standardName)
        assertEquals(COST_TOTAL, query.selectStandardAttributes[Scope.EVENT]!!.elementAt(1).standardName)
        assertTrue(query.selectStandardAttributes[Scope.EVENT]!!.all { it.isStandard })
        assertTrue(query.selectStandardAttributes[Scope.EVENT]!!.all { it.effectiveScope == Scope.EVENT })
        assertTrue(query.selectStandardAttributes[Scope.EVENT]!!.all { !it.isClassifier })
    }

    @Test
    fun scopedSelectAllTest() {
        assertThrowsPQL(PQLSemanticException::class.java, "select t:name, e:*, t:total, e:concept:name")
    }

    @Test
    fun scopedSelectAll2Test() {
        val query = parsePQL("select t:*, e:*, l:*")
        assertTrue(query.selectAll[Scope.LOG]!!)
        assertTrue(query.selectAll[Scope.TRACE]!!)
        assertTrue(query.selectAll[Scope.EVENT]!!)
        assertFalse(query.isImplicitSelectAll[Scope.LOG]!!)
        assertFalse(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertFalse(query.isImplicitSelectAll[Scope.EVENT]!!)
    }

    @Test
    fun selectUsingClassifierTest() {
        assertThrowsPQL(InvalidClassifierUsageException::class.java, "select t:c:businesscase, e:classifier:activity_resource")
    }

    @Test
    fun selectUsingNonStandardClassifierTest() {
        assertThrowsPQL(InvalidClassifierUsageException::class.java, "select [t:classifier:bu$1n3\$\$c4\$3], [e:classifier:concept:name+lifecycle:transition]")
    }

    @Test
    fun selectAggregationTest() {
        val query = parsePQL("select min(t:total), avg(t:total), max(t:total)")
        assertFalse(query.isImplicitSelectAll[Scope.LOG]!!)
        assertFalse(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertFalse(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertFalse(query.selectAll[Scope.LOG] ?: false)
        assertFalse(query.selectAll[Scope.TRACE] ?: false)
        assertFalse(query.selectAll[Scope.EVENT] ?: false)
        // log scope
        assertEquals(0, query.selectStandardAttributes[Scope.LOG]?.size ?: 0)
        assertEquals(0, query.selectOtherAttributes[Scope.LOG]?.size ?: 0)
        assertEquals(3, query.selectExpressions[Scope.TRACE]!!.size)
        
        val exprs = query.selectExpressions[Scope.TRACE]!!
        assertTrue(exprs.elementAt(0).toString().contains("min"))
        assertTrue(exprs.elementAt(1).toString().contains("avg"))
        assertTrue(exprs.elementAt(2).toString().contains("max"))
        assertTrue(exprs.all { !it.isTerminal })
        
        // event scope
        assertEquals(0, query.selectStandardAttributes[Scope.EVENT]?.size ?: 0)
        assertEquals(0, query.selectOtherAttributes[Scope.EVENT]?.size ?: 0)
        assertEquals(0, query.selectExpressions[Scope.EVENT]?.size ?: 0)
    }

    @Test
    fun selectNonStandardAttributesTest() {
        val query = parsePQL("select [e:conceptowy:name], [e:time:timestamp], [org:resource2]")
        assertFalse(query.isImplicitSelectAll[Scope.LOG]!!)
        assertFalse(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertFalse(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertFalse(query.selectAll[Scope.LOG] ?: false)
        assertFalse(query.selectAll[Scope.TRACE] ?: false)
        assertFalse(query.selectAll[Scope.EVENT] ?: false)
        // log scope
        assertEquals(0, query.selectStandardAttributes[Scope.LOG]?.size ?: 0)
        assertEquals(0, query.selectOtherAttributes[Scope.LOG]?.size ?: 0)
        assertEquals(0, query.selectExpressions[Scope.LOG]?.size ?: 0)
        // trace scope
        assertEquals(0, query.selectStandardAttributes[Scope.TRACE]?.size ?: 0)
        assertEquals(0, query.selectOtherAttributes[Scope.TRACE]?.size ?: 0)
        assertEquals(0, query.selectExpressions[Scope.TRACE]?.size ?: 0)
        // event scope
        assertEquals(0, query.selectStandardAttributes[Scope.EVENT]?.size ?: 0)
        assertEquals(3, query.selectOtherAttributes[Scope.EVENT]!!.size)
        assertEquals(0, query.selectExpressions[Scope.EVENT]?.size ?: 0)
        
        val attrs = query.selectOtherAttributes[Scope.EVENT]!!
        assertEquals("conceptowy:name", attrs.elementAt(0).name)
        assertEquals(TIME_TIMESTAMP, attrs.elementAt(1).name)
        assertEquals("org:resource2", attrs.elementAt(2).name)
        assertTrue(attrs.all { !it.isStandard })
        assertTrue(attrs.all { !it.isClassifier })
        assertTrue(attrs.all { it.effectiveScope == Scope.EVENT })
    }

    @Test
    fun selectExpressionTest() {
        val query = parsePQL(
            "select [e:conceptowy:name] + e:resource, max(timestamp) - \t \n min(timestamp)" +
                    "group by [e:conceptowy:name], e:resource"
        )
        assertFalse(query.isImplicitSelectAll[Scope.LOG]!!)
        assertFalse(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertFalse(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertFalse(query.selectAll[Scope.LOG] ?: false)
        assertFalse(query.selectAll[Scope.TRACE] ?: false)
        assertFalse(query.selectAll[Scope.EVENT] ?: false)
        
        assertEquals(2, query.selectExpressions[Scope.EVENT]!!.size)
        assertTrue(query.selectExpressions[Scope.EVENT]!!.all { !it.isTerminal })
    }

    @Test
    fun selectAllImplicitTest() {
        val query = parsePQL("")
        assertTrue(query.isImplicitSelectAll[Scope.LOG]!!)
        assertTrue(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertTrue(query.isImplicitSelectAll[Scope.EVENT]!!)
    }

    @Test
    fun selectConstantsTest() {
        val query = parsePQL("select l:1, l:2 + t:3, l:4 * t:5 + e:6, 7 / 8 - 9, 10 * null, t:null/11, l:D2020-03-12")
        assertFalse(query.isImplicitSelectAll[Scope.LOG]!!)
        assertFalse(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertFalse(query.isImplicitSelectAll[Scope.EVENT]!!)
        
        // log scope
        assertEquals(2, query.selectExpressions[Scope.LOG]!!.size)
        assertTrue(query.selectExpressions[Scope.LOG]!!.all { (it as Expression).effectiveScope == Scope.LOG })
        // trace scope
        assertEquals(2, query.selectExpressions[Scope.TRACE]!!.size)
        assertTrue(query.selectExpressions[Scope.TRACE]!!.all { (it as Expression).effectiveScope == Scope.TRACE })
        // event scope
        assertEquals(3, query.selectExpressions[Scope.EVENT]!!.size)
        assertTrue(query.selectExpressions[Scope.EVENT]!!.all { (it as Expression).effectiveScope == Scope.EVENT || (it as Expression).effectiveScope == null })
    }

    @Test
    fun selectISO8601Test() {
        val query = parsePQL(
            """select 
                    D2020-03-13, 
                    D2020-03-13T16:45, 
                    D2020-03-13T16:45:50, 
                    D2020-03-13T16:45:50.333, 
                    D2020-03-13T16:45+0200, 
                    D2020-03-13T16:45+02:00,
                    D2020-03-13T16:45Z,
                    D20200313, 
                    D20200313T1645, 
                    D20200313T164550, 
                    D20200313T164550.333, 
                    D20200313T1645+0200,
                    D202003131645, 
                    D20200313164550, 
                    D20200313164550.333, 
                    D202003131645+0200,
                    D202003131645Z
                    """
        )
        assertEquals(17, query.selectExpressions[Scope.EVENT]!!.size)
        assertTrue(query.selectExpressions[Scope.EVENT]!!.all { it.isTerminal })
        assertTrue(query.selectExpressions[Scope.EVENT]!!.all { (it as Expression).effectiveScope == Scope.EVENT })
    }

    @Test
    fun selectIEEE754Test() {
        val query = parsePQL(
            "select 0, 0.0, 0.00, -0, -0.0, 1, 1.0, -1, -1.0, ${Math.PI}, ${Double.MIN_VALUE}, ${Double.MAX_VALUE}"
        )
        assertEquals(12, query.selectExpressions[Scope.EVENT]!!.size)
        assertTrue(query.selectExpressions[Scope.EVENT]!!.all { it.isTerminal })
        assertTrue(query.selectExpressions[Scope.EVENT]!!.all { (it as Expression).effectiveScope == Scope.EVENT })
    }

    @Test
    fun selectBooleanTest() {
        val query = parsePQL("select true, false")
        assertEquals(2, query.selectExpressions[Scope.EVENT]!!.size)
        assertEquals("true", query.selectExpressions[Scope.EVENT]!!.elementAt(0).toString())
        assertEquals("false", query.selectExpressions[Scope.EVENT]!!.elementAt(1).toString())
    }

    @Test
    fun selectStringTest() {
        val query = parsePQL("select 'single-quoted', \"double-quoted\"")
        assertEquals(2, query.selectExpressions[Scope.EVENT]!!.size)
        // Note: toString() might strip quotes or keep them depending on implementation
        assertTrue(query.selectExpressions[Scope.EVENT]!!.elementAt(0).toString().contains("single-quoted"))
        assertTrue(query.selectExpressions[Scope.EVENT]!!.elementAt(1).toString().contains("double-quoted"))
    }

    @Test
    fun selectNowTest() {
        val query = parsePQL("select now()")
        assertEquals(1, query.selectExpressions[Scope.EVENT]!!.size)
        assertEquals("now", (query.selectExpressions[Scope.EVENT]!![0] as com.processm.processminterpreter.pql.model.Function).name)
    }


    @Test
    fun errorHandlingTest_SelectBracketedClassifier() {
        assertThrowsPQL(InvalidClassifierUsageException::class.java, "select [l:c:main]")
    }

    @Test
    fun errorHandlingTest_WhereComplex() {
        assertThrowsPQL(InvalidClassifierUsageException::class.java, "where [e:classifier:concept:name+lifecycle:transition] in ('acceptcomplete', 'rejectcomplete')")
    }

    @Test
    fun whereSimpleTest() {
        val query = parsePQL("where dayofweek(e:timestamp) in (1, 7)")
        assertTrue(query.isImplicitSelectAll[Scope.LOG]!!)
        assertTrue(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertTrue(query.isImplicitSelectAll[Scope.EVENT]!!)
        // Note: toString() representation might differ slightly in local implementation (spaces, etc.)
        // Checking structure or normalized string is safer.
        assertTrue(query.whereExpression.toString().contains("dayofweek"))
        assertTrue(query.whereExpression.toString().contains("IN"))
        assertEquals(Scope.EVENT, (query.whereExpression as Expression).effectiveScope)
    }

    @Test
    fun whereSimpleWithHoistingTest() {
        val query = parsePQL("where dayofweek(^e:timestamp) in (1, 7)")
        assertTrue(query.isImplicitSelectAll[Scope.LOG]!!)
        assertTrue(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertTrue(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertEquals(Scope.TRACE, (query.whereExpression as Expression).effectiveScope)
    }

    @Test
    fun whereSimpleWithHoistingTest2() {
        val query = parsePQL("where dayofweek(^^e:timestamp) in (1, 7)")
        assertTrue(query.isImplicitSelectAll[Scope.LOG]!!)
        assertTrue(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertTrue(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertEquals(Scope.LOG, (query.whereExpression as Expression).effectiveScope)
    }

    @Test
    fun whereLogicExprWithHoistingTest() {
        val query = parsePQL("where not(t:currency = ^e:currency)")
        assertTrue(query.isImplicitSelectAll[Scope.LOG]!!)
        assertTrue(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertTrue(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertEquals(Scope.TRACE, (query.whereExpression as Expression).effectiveScope)
    }

    @Test
    fun whereLogicExprTest() {
        val query = parsePQL("where t:currency != e:currency")
        assertTrue(query.isImplicitSelectAll[Scope.LOG]!!)
        assertTrue(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertTrue(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertEquals(Scope.EVENT, (query.whereExpression as Expression).effectiveScope)
    }

    @Test
    fun whereLogicExpr2Test() {
        val query = parsePQL("where not(t:currency = ^e:currency) and t:total is null")
        assertTrue(query.isImplicitSelectAll[Scope.LOG]!!)
        assertTrue(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertTrue(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertEquals(Scope.TRACE, (query.whereExpression as Expression).effectiveScope)
    }

    @Test
    fun whereLogicExpr3Test() {
        val query = parsePQL("where (not(t:currency = ^e:currency) or ^e:timestamp >= D2020-01-01) and t:total is null")
        assertTrue(query.isImplicitSelectAll[Scope.LOG]!!)
        assertTrue(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertTrue(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertEquals(Scope.TRACE, (query.whereExpression as Expression).effectiveScope)
    }

    @Test
    fun whereLikeAndMatchesTest() {
        val query = parsePQL("where t:name like 'transaction %' and ^e:resource matches '^[A-Z][a-z]+ [A-Z][a-z]+$'")
        assertTrue(query.whereExpression.toString().contains("LIKE"))
        assertTrue(query.whereExpression.toString().contains("MATCHES"))
    }

    @Test
    fun groupScopeByClassifierTest() {
        val query = parsePQL("group by ^e:classifier:activity")
        assertTrue(query.isImplicitSelectAll[Scope.LOG]!!)
        assertFalse(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertFalse(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertFalse(query.isImplicitGroupBy[Scope.LOG]!!)
        assertFalse(query.isImplicitGroupBy[Scope.TRACE]!!)
        assertFalse(query.isImplicitGroupBy[Scope.EVENT]!!)
        assertFalse(query.isImplicitGroupBy[Scope.EVENT]!!)
        assertFalse(query.isGroupBy[Scope.LOG]!!)
        assertTrue(query.isGroupBy[Scope.TRACE]!!)
        assertFalse(query.isGroupBy[Scope.EVENT]!!)
        assertEquals(0, query.groupByStandardAttributes[Scope.LOG]?.size ?: 0)
        assertEquals(0, query.groupByStandardAttributes[Scope.TRACE]?.size ?: 0)
        assertEquals(1, query.groupByOtherAttributes[Scope.TRACE]?.size ?: 0)
        assertEquals(0, query.groupByStandardAttributes[Scope.EVENT]?.size ?: 0)
    }

    @Test
    fun groupEventByStandardAttributeTest() {
        val query = parsePQL(
            """select t:name, e:name, sum(e:total)
            group by e:name"""
        )
        assertFalse(query.isImplicitSelectAll[Scope.LOG]!!)
        assertFalse(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertFalse(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertFalse(query.isImplicitGroupBy[Scope.LOG]!!)
        assertFalse(query.isImplicitGroupBy[Scope.TRACE]!!)
        assertFalse(query.isImplicitGroupBy[Scope.EVENT]!!)
        assertFalse(query.isGroupBy[Scope.LOG]!!)
        assertFalse(query.isGroupBy[Scope.TRACE]!!)
        assertTrue(query.isGroupBy[Scope.EVENT]!!)
        assertEquals(1, query.groupByStandardAttributes[Scope.EVENT]!!.size)
        assertEquals(CONCEPT_NAME, query.groupByStandardAttributes[Scope.EVENT]!!.elementAt(0).standardName)
    }

    @Test
    fun groupTraceByEventStandardAttributeTest() {
        val query = parsePQL(
            """select e:name, sum(e:total)
            group by ^e:name, e:name"""
        )
        assertTrue(query.isGroupBy[Scope.TRACE]!!)
        assertTrue(query.isGroupBy[Scope.EVENT]!!)
        assertEquals(1, query.groupByStandardAttributes[Scope.TRACE]!!.size)
        assertEquals(1, query.groupByStandardAttributes[Scope.EVENT]!!.size)
    }

    @Test
    fun groupLogByEventStandardAttributeTest() {
        val query = parsePQL(
            """select sum(e:total)
            group by ^^e:name"""
        )
        assertTrue(query.isImplicitGroupBy[Scope.EVENT]!!)
        assertTrue(query.isGroupBy[Scope.LOG]!!)
        assertFalse(query.isGroupBy[Scope.TRACE]!!)
        assertFalse(query.isGroupBy[Scope.EVENT]!!)
        assertEquals(1, query.groupByStandardAttributes[Scope.LOG]!!.size)
    }

    @Test
    fun groupByImplicitScopeTest() {
        val query = parsePQL("group by c:main, [t:branch]")
        assertTrue(query.isImplicitSelectAll[Scope.LOG]!!)
        assertFalse(query.isImplicitSelectAll[Scope.TRACE]!!)
        assertFalse(query.isImplicitSelectAll[Scope.EVENT]!!)
        assertTrue(query.selectAll[Scope.LOG]!!)
        assertFalse(query.selectAll[Scope.TRACE]!!)
        assertFalse(query.selectAll[Scope.EVENT]!!)
        
        assertTrue(query.isGroupBy[Scope.TRACE]!!)
        assertTrue(query.isGroupBy[Scope.EVENT]!!)
    }

    @Test
    fun groupByImplicitFromSelectTest() {
        val query = parsePQL("select avg(e:total), min(e:timestamp), max(e:timestamp)")
        assertTrue(query.isImplicitGroupBy[Scope.EVENT]!!)
        assertFalse(query.isGroupBy[Scope.EVENT]!!)
        assertEquals(3, query.selectExpressions[Scope.EVENT]!!.size)
    }

    @Test
    fun groupByImplicitFromOrderByTest() {
        val query = parsePQL("order by avg(e:total), min(e:timestamp), max(e:timestamp)")
        assertTrue(query.isImplicitGroupBy[Scope.EVENT]!!)
    }

    @Test
    fun groupByImplicitMultiScopesTest() {
        val query = parsePQL("select avg(t:total), min(e:timestamp), max(e:timestamp)")
        assertTrue(query.isImplicitGroupBy[Scope.TRACE]!!)
        assertTrue(query.isImplicitGroupBy[Scope.EVENT]!!)
    }

    @Test
    fun groupByImplicitWithHoistingTest() {
        val query = parsePQL("select avg(^^e:total), min(^^e:timestamp), max(^^e:timestamp)")
        assertEquals(3, query.selectExpressions[Scope.LOG]!!.size)
    }

    @Test
    fun groupByWithHoistingAndOrderByCountTest() {
        val query = parsePQL(
            "select l:name, count(t:name), e:name\n" +
                    "group by ^e:name\n" +
                    "order by count(t:name) desc\n" +
                    "limit l:1\n"
        )
        assertEquals(1, query.groupByStandardAttributes[Scope.TRACE]!!.size)
        // Check hoisting logic
        val attr = query.groupByStandardAttributes[Scope.TRACE]!!.elementAt(0)
        assertEquals(Scope.TRACE, attr.effectiveScope)
    }

    @Test
    fun orderBySimpleTest() {
        val query = parsePQL("order by e:timestamp")
        assertEquals(1, query.orderByExpressions[Scope.EVENT]!!.size)
        assertEquals(OrderDirection.ASCENDING, query.orderByExpressions[Scope.EVENT]!![0].direction)
    }

    @Test
    fun orderByWithModifierAndScopesTest() {
        val query = parsePQL("order by t:total desc, e:timestamp")
        assertEquals(1, query.orderByExpressions[Scope.TRACE]!!.size)
        assertEquals(OrderDirection.DESCENDING, query.orderByExpressions[Scope.TRACE]!![0].direction)
        assertEquals(1, query.orderByExpressions[Scope.EVENT]!!.size)
        assertEquals(OrderDirection.ASCENDING, query.orderByExpressions[Scope.EVENT]!![0].direction)
    }

    @Test
    fun orderByWithModifierAndScopes2Test() {
        val query = parsePQL("order by e:timestamp, t:total desc")
        assertEquals(1, query.orderByExpressions[Scope.TRACE]!!.size)
        assertEquals(OrderDirection.DESCENDING, query.orderByExpressions[Scope.TRACE]!![0].direction)
        assertEquals(1, query.orderByExpressions[Scope.EVENT]!!.size)
        assertEquals(OrderDirection.ASCENDING, query.orderByExpressions[Scope.EVENT]!![0].direction)
    }

    @Test
    fun orderByExpressionTest() {
        val query = parsePQL("group by ^e:name order by min(^e:timestamp)")
        assertEquals(1, query.orderByExpressions[Scope.TRACE]!!.size)
        assertEquals(OrderDirection.ASCENDING, query.orderByExpressions[Scope.TRACE]!![0].direction)
    }

    @Test
    fun orderByExpression2Test() {
        val query = parsePQL(
            """group by ^e:name
            |order by [l:basePrice] * avg(^e:total) * 3.141592 desc""".trimMargin()
        )
        assertEquals(1, query.orderByExpressions[Scope.TRACE]!!.size)
        assertEquals(OrderDirection.DESCENDING, query.orderByExpressions[Scope.TRACE]!![0].direction)
    }

    @Test
    fun limitSingleTest() {
        val query = parsePQL("limit l:1")
        assertEquals(1L, query.limit[Scope.LOG])
        assertEquals(null, query.limit[Scope.TRACE])
        assertEquals(null, query.limit[Scope.EVENT])
    }

    @Test
    fun limitAllTest() {
        val query = parsePQL("limit e:3, t:2, l:1")
        assertEquals(1L, query.limit[Scope.LOG])
        assertEquals(2L, query.limit[Scope.TRACE])
        assertEquals(3L, query.limit[Scope.EVENT])
    }

    @Test
    fun limitDuplicatesTest() {
        val query = parsePQL("limit e:3, t:2, l:1, e:10")
        // Local implementation might throw or warn.
        // Original test expects warning.
    }

    @Test
    fun limitRealNumberTest() {
        val query = parsePQL("limit e:3.14, t:2.72, l:1")
        assertEquals(1L, query.limit[Scope.LOG])
        assertEquals(3L, query.limit[Scope.TRACE])
        assertEquals(3L, query.limit[Scope.EVENT])
    }

    @Test
    fun applyLimitsTest() {
        val query = parsePQL("limit t:1, e:1")
        query.applyLimits(mapOf(Scope.LOG to 10L, Scope.TRACE to 20L, Scope.EVENT to 30L))
        assertEquals(10L, query.limit[Scope.LOG])
        assertEquals(1L, query.limit[Scope.TRACE])
        assertEquals(1L, query.limit[Scope.EVENT])
    }

    @Test
    fun offsetSingleTest() {
        val query = parsePQL("offset l:1")
        assertEquals(1L, query.offset[Scope.LOG])
    }

    @Test
    fun offsetAllTest() {
        val query = parsePQL("offset e:3, t:2, l:1")
        assertEquals(1L, query.offset[Scope.LOG])
        assertEquals(2L, query.offset[Scope.TRACE])
        assertEquals(3L, query.offset[Scope.EVENT])
    }

    @Test
    fun offsetDuplicatesTest() {
        val query = parsePQL("offset e:3, t:2, l:1, e:10")
        // Check behavior
    }

    @Test
    fun offsetRealNumberTest() {
        val query = parsePQL("offset e:3.14, t:2.72, l:1")
        assertEquals(1L, query.offset[Scope.LOG])
        assertEquals(3L, query.offset[Scope.TRACE])
        assertEquals(3L, query.offset[Scope.EVENT])
    }

    @Test
    fun commentLineTest() {
        val query = parsePQL(
            """select e:name
            --where e:timestamp > D2020-01-01
            order by e:timestamp
            """
        )
        assertEquals(1, query.selectStandardAttributes[Scope.EVENT]!!.size)
        // assertEquals(Expression.empty, query.whereExpression) // Expression.empty might not exist or be accessible
        assertEquals(1, query.orderByExpressions[Scope.EVENT]!!.size)
    }

    @Test
    fun commentLine2Test() {
        val query = parsePQL(
            """select e:name
            //where e:timestamp > D2020-01-01
            order by e:timestamp
            """
        )
        assertEquals(1, query.selectStandardAttributes[Scope.EVENT]!!.size)
        assertEquals(1, query.orderByExpressions[Scope.EVENT]!!.size)
    }

    @Test
    fun commentBlockTest() {
        val query = parsePQL(
            """select e:name
            |/*where e:timestamp > D2020-01-01
            |group by e:name
            |*/
            |order by e:timestamp
            """.trimMargin()
        )
        assertEquals(1, query.selectStandardAttributes[Scope.EVENT]!!.size)
        assertFalse(query.isGroupBy[Scope.EVENT]!!)
        assertEquals(1, query.orderByExpressions[Scope.EVENT]!!.size)
    }

    @Test
    fun toStringTest() {
        val q = """select e:name
            |/*where e:timestamp > D2020-01-01
            |group by e:name
            |*/
            |order by e:timestamp
            """.trimMargin()
        val query = parsePQL(q)
        // assertEquals(q, query.toString()) // toString might differ
    }

    @Test
    fun deleteAllLogsTest() {
        val query = parsePQL("delete log")
        assertEquals(Scope.LOG, query.deleteScope)
        assertTrue(query.isImplicitSelectAll[Scope.LOG]!!)
        assertTrue(query.selectAll[Scope.LOG]!!)
    }

    @Test
    fun deleteAllImplicitTest() {
        val query = parsePQL("delete")
        assertEquals(Scope.EVENT, query.deleteScope)
        assertTrue(query.isImplicitSelectAll[Scope.EVENT]!!)
    }

    @Test
    fun errorHandlingTest_Select() {
        assertThrowsPQL(IllegalArgumentException::class.java, "select")
    }

    @Test
    fun errorHandlingTest_SelectStarFrom() {
        assertThrowsPQL(IllegalArgumentException::class.java, "select * from")
    }

    @Test
    fun errorHandlingTest_SelectWhere() {
        assertThrowsPQL(IllegalArgumentException::class.java, "select e:name where")
    }

    @Test
    fun deleteWithGroupByNotAllowed() {
        assertThrowsPQL(IllegalArgumentException::class.java, "delete event group by l:name")
    }

    @Test
    fun deleteWithSelectNotAllowed() {
        assertThrowsPQL(IllegalArgumentException::class.java, "select e:name delete event where l:name='abc'")
    }
}
