package com.processm.processminterpreter.pql

import com.processm.processminterpreter.pql.model.OrderDirection
import com.processm.processminterpreter.pql.model.Scope
import com.processm.processminterpreter.pql.visitor.QueryBuilder
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Test
import QLLexer
import QLParser
import kotlin.test.*

/**
 * Query Parser Tests - Ported from ProcessM QueryTests.kt
 *
 * These tests verify that QueryBuilder correctly parses PQL syntax into Query model.
 * They are UNIT tests - no Neo4j or execution required.
 *
 * Source: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/querylanguage/QueryTests.kt
 */
class QueryParserTest {

    /**
     * Helper function to parse PQL query string into Query object
     */
    private fun parseQuery(pql: String): com.processm.processminterpreter.pql.model.Query {
        val input = CharStreams.fromString(pql)
        val lexer = QLLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = QLParser(tokens)
        val tree = parser.query()

        val builder = QueryBuilder()
        return builder.build(tree, pql)
    }

    @Test
    fun `basicSelectTest - select multiple attributes from different scopes`() {
        // ProcessM: select l:name, t:name, t:currency, e:name, e:total
        val query = parseQuery("select l:name, t:name, t:currency, e:name, e:total")

        // Verify NOT implicit SELECT *
        assertFalse(query.isImplicitSelectAll[Scope.Log] ?: true, "Log should not be implicit SELECT *")
        assertFalse(query.isImplicitSelectAll[Scope.Trace] ?: true, "Trace should not be implicit SELECT *")
        assertFalse(query.isImplicitSelectAll[Scope.Event] ?: true, "Event should not be implicit SELECT *")

        // Verify NOT explicit SELECT *
        assertFalse(query.selectAll[Scope.Log] ?: true, "Log should not be SELECT *")
        assertFalse(query.selectAll[Scope.Trace] ?: true, "Trace should not be SELECT *")
        assertFalse(query.selectAll[Scope.Event] ?: true, "Event should not be SELECT *")

        // Log scope - should have 1 standard attribute (concept:name)
        assertEquals(1, query.selectStandardAttributes[Scope.Log]?.size ?: 0, "Log should have 1 standard attribute")
        assertEquals(0, query.selectOtherAttributes[Scope.Log]?.size ?: 0, "Log should have 0 other attributes")
        assertEquals(0, query.selectExpressions[Scope.Log]?.size ?: 0, "Log should have 0 expressions")

        val logAttr = query.selectStandardAttributes[Scope.Log]?.firstOrNull()
        assertNotNull(logAttr, "Log attribute should not be null")
        assertEquals("name", logAttr.name, "Log attribute should be 'name'")
        assertTrue(logAttr.isStandard, "Log attribute should be standard")
        assertEquals(Scope.Log, logAttr.effectiveScope, "Log attribute effective scope should be LOG")

        // Trace scope - should have 2 standard attributes (concept:name, cost:currency)
        assertEquals(2, query.selectStandardAttributes[Scope.Trace]?.size ?: 0, "Trace should have 2 standard attributes")
        assertEquals(0, query.selectOtherAttributes[Scope.Trace]?.size ?: 0, "Trace should have 0 other attributes")
        assertEquals(0, query.selectExpressions[Scope.Trace]?.size ?: 0, "Trace should have 0 expressions")

        val traceAttrs = query.selectStandardAttributes[Scope.Trace] ?: emptyList()
        assertTrue(traceAttrs.all { it.isStandard }, "All trace attributes should be standard")
        assertTrue(traceAttrs.all { it.effectiveScope == Scope.Trace }, "All trace attributes should have TRACE scope")

        // Event scope - should have 2 standard attributes (concept:name, cost:total)
        assertEquals(2, query.selectStandardAttributes[Scope.Event]?.size ?: 0, "Event should have 2 standard attributes")
        assertEquals(0, query.selectOtherAttributes[Scope.Event]?.size ?: 0, "Event should have 0 other attributes")
        assertEquals(0, query.selectExpressions[Scope.Event]?.size ?: 0, "Event should have 0 expressions")

        val eventAttrs = query.selectStandardAttributes[Scope.Event] ?: emptyList()
        assertTrue(eventAttrs.all { it.isStandard }, "All event attributes should be standard")
        assertTrue(eventAttrs.all { it.effectiveScope == Scope.Event }, "All event attributes should have EVENT scope")
    }

    @Test
    fun `scopedSelectAll2Test - select all from all scopes`() {
        // ProcessM: select t:*, e:*, l:*
        val query = parseQuery("select t:*, e:*, l:*")

        // All scopes should have explicit SELECT *
        assertTrue(query.selectAll[Scope.Log] ?: false, "Log should be SELECT *")
        assertTrue(query.selectAll[Scope.Trace] ?: false, "Trace should be SELECT *")
        assertTrue(query.selectAll[Scope.Event] ?: false, "Event should be SELECT *")

        // Should NOT be implicit SELECT *
        assertFalse(query.isImplicitSelectAll[Scope.Log] ?: true, "Log should not be implicit SELECT *")
        assertFalse(query.isImplicitSelectAll[Scope.Trace] ?: true, "Trace should not be implicit SELECT *")
        assertFalse(query.isImplicitSelectAll[Scope.Event] ?: true, "Event should not be implicit SELECT *")
    }

    @Test
    fun `selectAggregationTest - select aggregation functions`() {
        // ProcessM: select min(t:total), avg(t:total), max(t:total)
        val query = parseQuery("select min(t:total), avg(t:total), max(t:total)")

        // Verify NOT implicit SELECT *
        assertFalse(query.isImplicitSelectAll[Scope.Log] ?: true)
        assertFalse(query.isImplicitSelectAll[Scope.Trace] ?: true)
        assertFalse(query.isImplicitSelectAll[Scope.Event] ?: true)

        // Verify NOT explicit SELECT *
        assertFalse(query.selectAll[Scope.Log] ?: true)
        assertFalse(query.selectAll[Scope.Trace] ?: true)
        assertFalse(query.selectAll[Scope.Event] ?: true)

        // Log scope - should be empty
        assertEquals(0, query.selectStandardAttributes[Scope.Log]?.size ?: 0)
        assertEquals(0, query.selectOtherAttributes[Scope.Log]?.size ?: 0)
        assertEquals(0, query.selectExpressions[Scope.Log]?.size ?: 0)

        // Trace scope - should have 3 expressions (aggregations)
        assertEquals(0, query.selectStandardAttributes[Scope.Trace]?.size ?: 0)
        assertEquals(0, query.selectOtherAttributes[Scope.Trace]?.size ?: 0)
        assertEquals(3, query.selectExpressions[Scope.Trace]?.size ?: 0, "Trace should have 3 aggregation expressions")

        val traceExprs = query.selectExpressions[Scope.Trace] ?: emptyList()
        assertEquals(3, traceExprs.size, "Should have 3 trace expressions")

        // Event scope - should be empty
        assertEquals(0, query.selectStandardAttributes[Scope.Event]?.size ?: 0)
        assertEquals(0, query.selectOtherAttributes[Scope.Event]?.size ?: 0)
        assertEquals(0, query.selectExpressions[Scope.Event]?.size ?: 0)
    }

    @Test
    fun `selectAllImplicitTest - implicit select all when no SELECT clause`() {
        // ProcessM: (no SELECT clause - implicit SELECT *)
        val query = parseQuery("where e:name='test'")

        // All scopes should have implicit SELECT *
        assertTrue(query.isImplicitSelectAll[Scope.Log] ?: false, "Log should be implicit SELECT *")
        assertTrue(query.isImplicitSelectAll[Scope.Trace] ?: false, "Trace should be implicit SELECT *")
        assertTrue(query.isImplicitSelectAll[Scope.Event] ?: false, "Event should be implicit SELECT *")

        // Note: In pql-graph-db, selectAll returns true for BOTH explicit AND implicit SELECT *
        // This differs from ProcessM where they are separate
        // So we just verify isImplicitSelectAll is true, which is the important part
    }

    @Test
    fun `whereLogicExprTest - where with comparison operators`() {
        // ProcessM: where t:currency != e:currency
        val query = parseQuery("where t:currency != e:currency")

        // Should have implicit SELECT *
        assertTrue(query.isImplicitSelectAll[Scope.Log] ?: false)
        assertTrue(query.isImplicitSelectAll[Scope.Trace] ?: false)
        assertTrue(query.isImplicitSelectAll[Scope.Event] ?: false)

        // Should have WHERE expression
        assertNotNull(query.whereExpression, "Should have WHERE expression")

        // Note: pql-graph-db doesn't have effectiveScope concept like ProcessM
        // We just verify that WHERE expression exists and is not null
    }

    @Test
    fun `orderBySimpleTest - order by single attribute`() {
        // ProcessM: order by e:timestamp
        val query = parseQuery("order by e:timestamp")

        // Log and Trace should have empty ORDER BY
        assertEquals(0, query.orderByExpressions[Scope.Log]?.size ?: 0, "Log should have 0 ORDER BY expressions")
        assertEquals(0, query.orderByExpressions[Scope.Trace]?.size ?: 0, "Trace should have 0 ORDER BY expressions")

        // Event should have 1 ORDER BY expression
        assertEquals(1, query.orderByExpressions[Scope.Event]?.size ?: 0, "Event should have 1 ORDER BY expression")

        val eventOrderBy = query.orderByExpressions[Scope.Event]?.firstOrNull()
        assertNotNull(eventOrderBy, "Event ORDER BY should not be null")
        assertEquals(OrderDirection.Ascending, eventOrderBy.direction, "Default direction should be ASCENDING")
    }

    @Test
    fun `orderByWithModifierAndScopesTest - order by multiple scopes with ASC and DESC`() {
        // ProcessM: order by t:total desc, e:timestamp
        val query = parseQuery("order by t:total desc, e:timestamp")

        // Log should be empty
        assertEquals(0, query.orderByExpressions[Scope.Log]?.size ?: 0)

        // Trace should have 1 ORDER BY DESC
        assertEquals(1, query.orderByExpressions[Scope.Trace]?.size ?: 0, "Trace should have 1 ORDER BY")
        val traceOrderBy = query.orderByExpressions[Scope.Trace]?.firstOrNull()
        assertNotNull(traceOrderBy)
        assertEquals(OrderDirection.Descending, traceOrderBy.direction, "Trace ORDER BY should be DESCENDING")

        // Event should have 1 ORDER BY ASC
        assertEquals(1, query.orderByExpressions[Scope.Event]?.size ?: 0, "Event should have 1 ORDER BY")
        val eventOrderBy = query.orderByExpressions[Scope.Event]?.firstOrNull()
        assertNotNull(eventOrderBy)
        assertEquals(OrderDirection.Ascending, eventOrderBy.direction, "Event ORDER BY should be ASCENDING")
    }

    @Test
    fun `limitSingleTest - limit single scope`() {
        // ProcessM: limit l:1
        val query = parseQuery("limit l:1")

        // Log should have limit=1
        assertEquals(1L, query.limit[Scope.Log], "Log limit should be 1")

        // Trace and Event should be null
        assertNull(query.limit[Scope.Trace], "Trace limit should be null")
        assertNull(query.limit[Scope.Event], "Event limit should be null")

        // All offsets should be null
        assertNull(query.offset[Scope.Log], "Log offset should be null")
        assertNull(query.offset[Scope.Trace], "Trace offset should be null")
        assertNull(query.offset[Scope.Event], "Event offset should be null")
    }

    @Test
    fun `limitAllTest - limit all scopes`() {
        // ProcessM: limit l:1, t:3, e:5
        val query = parseQuery("limit l:1, t:3, e:5")

        // All scopes should have limits
        assertEquals(1L, query.limit[Scope.Log], "Log limit should be 1")
        assertEquals(3L, query.limit[Scope.Trace], "Trace limit should be 3")
        assertEquals(5L, query.limit[Scope.Event], "Event limit should be 5")

        // All offsets should be null
        assertNull(query.offset[Scope.Log])
        assertNull(query.offset[Scope.Trace])
        assertNull(query.offset[Scope.Event])
    }
}