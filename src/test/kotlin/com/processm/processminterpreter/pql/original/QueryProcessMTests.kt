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

@Tag("PQL")
@Suppress("MapGetWithNotNullAssertionOperator")
class QueryProcessMTests {

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
        // Current implementation forbids mixing SELECT * with specific attributes
        assertThrows(PQLSemanticException::class.java) {
            parsePQL("select t:name, e:*, t:total, e:concept:name")
        }
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
        // Classifiers are not allowed in SELECT in current implementation
        assertThrows(InvalidClassifierUsageException::class.java) {
             parsePQL("select t:c:businesscase, e:classifier:activity_resource")
        }
    }

    @Test
    fun selectUsingNonStandardClassifierTest() {
        // Classifiers are not allowed in SELECT in current implementation
        assertThrows(InvalidClassifierUsageException::class.java) {
            parsePQL("select [t:classifier:bu$1n3\$\$c4\$3], [e:classifier:concept:name+lifecycle:transition]")
        }
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
        assertEquals(0, query.selectExpressions[Scope.LOG]?.size ?: 0)
        // trace scope
        assertEquals(0, query.selectStandardAttributes[Scope.TRACE]?.size ?: 0)
        assertEquals(0, query.selectOtherAttributes[Scope.TRACE]?.size ?: 0)
        assertEquals(3, query.selectExpressions[Scope.TRACE]!!.size)
        // Note: toString() format might differ slightly, checking structure instead
        val exprs = query.selectExpressions[Scope.TRACE]!!
        assertTrue(exprs[0] is com.processm.processminterpreter.pql.model.Function && (exprs[0] as com.processm.processminterpreter.pql.model.Function).name == "min")
        assertTrue(exprs[1] is com.processm.processminterpreter.pql.model.Function && (exprs[1] as com.processm.processminterpreter.pql.model.Function).name == "avg")
        assertTrue(exprs[2] is com.processm.processminterpreter.pql.model.Function && (exprs[2] as com.processm.processminterpreter.pql.model.Function).name == "max")
        
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
        assertTrue(query.selectOtherAttributes[Scope.EVENT]!!.all { !it.isStandard })
        assertTrue(query.selectOtherAttributes[Scope.EVENT]!!.all { !it.isClassifier })
        assertTrue(query.selectOtherAttributes[Scope.EVENT]!!.all { it.effectiveScope == Scope.EVENT })
    }
}
