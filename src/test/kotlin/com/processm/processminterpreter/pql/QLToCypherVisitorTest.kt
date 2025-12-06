package com.processm.processminterpreter.pql

import QLLexer
import QLParser
import com.processm.processminterpreter.pql.visitor.PQLErrorListener
import com.processm.processminterpreter.pql.visitor.QLToCypherVisitor
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Integration tests for QLToCypherVisitor
 * Tests ProcessM PQL queries and their translation to Neo4j Cypher
 */
class QLToCypherVisitorTest {

    private fun translateQuery(pqlQuery: String, logId: String? = null): CypherQuery {
        // Parse PQL query
        val input = CharStreams.fromString(pqlQuery)
        val lexer = QLLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = QLParser(tokens)

        // Add error listener
        val errorListener = PQLErrorListener()
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)

        // Parse and check for errors
        val tree = parser.query()
        errorListener.throwIfErrors()

        // Visit AST and generate Cypher
        val visitor = QLToCypherVisitor(logId)
        return visitor.visit(tree) as CypherQuery
    }

    // ========================================
    // BASIC SELECT TESTS
    // ========================================

    @Test
    fun `test simple select all events`() {
        val pql = "" // Implicit select all in ProcessM
        val result = translateQuery(pql)

        println("PQL: (empty - implicit select all)")
        println("Cypher: ${result.query}")
        println("Params: ${result.parameters}")

        assertTrue(result.query.contains("MATCH"))
        assertTrue(result.query.contains("event"))
        assertTrue(result.query.contains("RETURN"))
    }

    @Test
    fun `test select all events with explicit asterisk`() {
        val pql = "select *"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("MATCH"))
        assertTrue(result.query.contains("RETURN"))
    }

    @Test
    fun `test select specific fields`() {
        val pql = "select event:activity, event:timestamp"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("MATCH"))
        assertTrue(result.query.contains("event.activity"))
        assertTrue(result.query.contains("event.timestamp"))
    }

    // ========================================
    // WHERE CLAUSE TESTS
    // ========================================

    @Test
    fun `test select with simple where clause`() {
        val pql = "where event:activity = 'Registration'"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")
        println("Params: ${result.parameters}")

        assertTrue(result.query.contains("WHERE"))
        assertTrue(result.query.contains("event.activity"))
        assertTrue(result.parameters.containsValue("Registration"))
    }

    @Test
    fun `test where with AND condition`() {
        val pql = "where event:activity = 'Registration' and event:resource = 'Nurse'"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")
        println("Params: ${result.parameters}")

        assertTrue(result.query.contains("WHERE"))
        assertTrue(result.query.contains("AND"))
        assertTrue(result.parameters.containsValue("Registration"))
        assertTrue(result.parameters.containsValue("Nurse"))
    }

    @Test
    fun `test where with OR condition`() {
        val pql = "where event:activity = 'Registration' or event:activity = 'Triage'"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")
        println("Params: ${result.parameters}")

        assertTrue(result.query.contains("WHERE"))
        assertTrue(result.query.contains("OR"))
        assertTrue(result.parameters.containsValue("Registration"))
        assertTrue(result.parameters.containsValue("Triage"))
    }

    @Test
    fun `test where with IN operator`() {
        val pql = "where event:activity in ('Registration', 'Triage', 'Consultation')"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")
        println("Params: ${result.parameters}")

        assertTrue(result.query.contains("WHERE"))
        assertTrue(result.query.contains("IN"))
        // Check that the IN list parameter exists
        assertTrue(result.parameters.values.any { it is List<*> })
    }

    @Test
    fun `test where with comparison operators`() {
        val pql = "where event:timestamp > 'D2020-01-01'"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")
        println("Params: ${result.parameters}")

        assertTrue(result.query.contains("WHERE"))
        assertTrue(result.query.contains(">"))
        assertTrue(result.query.contains("event.timestamp"))
    }

    @Test
    fun `test where with NULL check`() {
        val pql = "where event:resource is not null"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("WHERE"))
        assertTrue(result.query.contains("IS NOT NULL"))
    }

    // ========================================
    // ORDER BY / LIMIT / OFFSET TESTS
    // ========================================

    @Test
    fun `test select with order by`() {
        val pql = "order by event:timestamp asc"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("ORDER BY"))
        assertTrue(result.query.contains("event.timestamp"))
        assertTrue(result.query.contains("ASC"))
    }

    @Test
    fun `test select with limit`() {
        val pql = "limit 10"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("LIMIT 10"))
    }

    @Test
    fun `test select with offset and limit`() {
        val pql = "limit 10 offset 20" // Grammar requires limit before offset
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("SKIP 20"))
        assertTrue(result.query.contains("LIMIT 10"))
    }

    // ========================================
    // AGGREGATE FUNCTION TESTS
    // ========================================

    @Test
    fun `test count aggregation`() {
        val pql = "select count(event:id)"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("count"))
        // "id" is a standard attribute that maps to "eventId" in EVENT scope
        assertTrue(result.query.contains("event.eventId"))
    }

    @Test
    fun `test select with group by`() {
        val pql = "select event:activity, count(event:id) group by event:activity"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("event.activity"))
        assertTrue(result.query.contains("count"))
    }

    // ========================================
    // SCALAR FUNCTION TESTS
    // ========================================

    @Test
    fun `test date function`() {
        val pql = "select year(event:timestamp)"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("year") || result.query.contains(".year"))
    }

    @Test
    fun `test dayofweek function`() {
        val pql = "select dayofweek(event:timestamp)"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        // Should map to something valid in Cypher, e.g., .dayOfWeek
        assertTrue(result.query.contains("dayOfWeek"), "Should contain dayOfWeek property accessor")
    }

    @Test
    fun `test string function`() {
        val pql = "select upper(event:activity)"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("toUpper") || result.query.contains("upper"))
    }

    // ========================================
    // ARITHMETIC EXPRESSION TESTS
    // ========================================

    @Test
    fun `test arithmetic expression`() {
        val pql = "select event:price * 1.2"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("event.price"))
        assertTrue(result.query.contains("*"))
    }

    // ========================================
    // DELETE QUERY TESTS
    // ========================================

    @Test
    fun `test simple delete`() {
        val pql = "delete event"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("DELETE"))
        assertTrue(result.query.contains("event"))
    }

    @Test
    fun `test delete with where clause`() {
        val pql = "delete event where event:timestamp < 'D2020-01-01'"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("DELETE"))
        assertTrue(result.query.contains("WHERE"))
        assertTrue(result.query.contains("event.timestamp"))
    }

    @Test
    fun `test delete with limit`() {
        val pql = "delete event where event:activity = 'Test' limit 5"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("DELETE"))
        assertTrue(result.query.contains("LIMIT 5"))
    }

    // ========================================
    // LOG ID FILTERING TESTS
    // ========================================

    @Test
    fun `test select with logId parameter`() {
        val pql = "" // Implicit select all
        val result = translateQuery(pql, logId = "test-log-123")

        println("PQL: $pql (logId: test-log-123)")
        println("Cypher: ${result.query}")
        println("Params: ${result.parameters}")

        assertTrue(result.query.contains("log"))
        assertTrue(result.query.contains("WHERE") || result.query.contains("logId"))
        assertTrue(result.parameters.containsKey("logId"))
        assertEquals("test-log-123", result.parameters["logId"])
    }

    // ========================================
    // COMPLEX QUERY TESTS
    // ========================================

    @Test
    fun `test complex query with multiple clauses`() {
        val pql = """
            select event:activity, count(event:id)
            where event:resource is not null and event:timestamp > 'D2020-01-01'
            group by event:activity
            order by count(event:id) desc
            limit 10
        """.trimIndent()

        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")
        println("Params: ${result.parameters}")

        assertTrue(result.query.contains("WHERE"))
        assertTrue(result.query.contains("IS NOT NULL"))
        assertTrue(result.query.contains("AND"))
        assertTrue(result.query.contains("ORDER BY"))
        assertTrue(result.query.contains("DESC"))
        assertTrue(result.query.contains("LIMIT 10"))
    }

    @Test
    fun `test org_group field mapping`() {
        val pql = "select event:org_group, event:activity where event:org_group = 'Radiotherapy' limit 5"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")
        println("Params: ${result.parameters}")

        // Check that org_group is properly translated
        assertTrue(result.query.contains("event"))
        assertTrue(result.query.contains("org_group"))
    }

    @Test
    fun `test user query from UI`() {
        val pql = """
            select event:org_group, event:activity, count(event:id)
            where event:timestamp > 'D2005-01-01'
            and event:org_group in ('Radiotherapy', 'Obstetrics & Gynaecology clinic')
            group by event:org_group, event:activity
            order by count(event:id) desc
            limit 20
        """.trimIndent().replace("\n", " ")
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")
        println("Params: ${result.parameters}")

        assertTrue(result.query.contains("WHERE"))
        assertTrue(result.query.contains("IN"))
        // Neo4j Cypher doesn't have explicit GROUP BY - it groups implicitly by non-aggregated fields in RETURN
        // "id" is a standard attribute that maps to "eventId" in EVENT scope
        assertTrue(result.query.contains("count(event.eventId)"))
        assertTrue(result.query.contains("ORDER BY"))
        // Date should not have 'D' prefix
        assertEquals("2005-01-01", result.parameters["param0"])
    }

    // ========================================
    // PROCESSM TESTS - ADAPTED FROM OFFICIAL REPO
    // https://github.com/ProcessMPUT/processm/tree/master/processm.core/src/test/kotlin/processm/core/querylanguage
    // ========================================

    /**
     * Adapted from QueryTests.kt - basicSelectTest
     * Tests: SELECT with different scopes (log, trace, event)
     * ProcessM: Tests that query.selectStandardAttributes[Scope.Log/Trace/Event] contains concept:name
     * Our adaptation: Tests that generated Cypher contains correct Neo4j property mappings
     */
    @Test
    fun `ProcessM basicSelectTest - multi-scope select with standard attributes`() {
        val pql = "select l:name, t:name, e:name"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        // l:name → log.name (concept:name in XES)
        assertTrue(result.query.contains("log.name"), "Should contain log.name")

        // t:name → trace.caseId (concept:name in XES, maps to caseId for traces)
        assertTrue(result.query.contains("trace.caseId"), "Should contain trace.caseId")

        // e:name → event.activity (concept:name in XES, maps to activity for events)
        assertTrue(result.query.contains("event.activity"), "Should contain event.activity")

        // Should have all three scopes in MATCH
        assertTrue(result.query.contains("MATCH"), "Should have MATCH clause")
    }

    /**
     * Adapted from QueryTests.kt - basicSelectTest
     * Tests: Standard shorthand attribute mappings
     */
    @Test
    fun `ProcessM standardAttributes - event scope shorthands`() {
        val pql = "select e:name, e:timestamp, e:resource, e:group, e:total, e:currency"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        // Standard attribute mappings for EVENT scope
        assertTrue(result.query.contains("event.activity"), "e:name → event.activity")
        assertTrue(result.query.contains("event.timestamp"), "e:timestamp → event.timestamp")
        assertTrue(result.query.contains("event.resource"), "e:resource → event.resource")
        assertTrue(result.query.contains("event.org_group"), "e:group → event.org_group")
        assertTrue(result.query.contains("event.cost_total"), "e:total → event.cost_total")
        assertTrue(result.query.contains("event.cost_currency"), "e:currency → event.cost_currency")
    }

    /**
     * Adapted from QueryTests.kt - basicSelectTest
     * Tests: Standard shorthand attribute mappings for TRACE scope
     */
    @Test
    fun `ProcessM standardAttributes - trace scope shorthands`() {
        val pql = "select t:name, t:id, t:total, t:currency"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        // Standard attribute mappings for TRACE scope
        assertTrue(result.query.contains("trace.caseId"), "t:name → trace.caseId")
        assertTrue(result.query.contains("trace.traceId"), "t:id → trace.traceId")
        assertTrue(result.query.contains("trace.cost_total"), "t:total → trace.cost_total")
        assertTrue(result.query.contains("trace.cost_currency"), "t:currency → trace.cost_currency")
    }

    /**
     * Adapted from QueryTests.kt - whereSimpleWithHoistingTest
     * Tests: Scope hoisting with ^ operator
     * ProcessM: ^e:name raises scope from EVENT to TRACE
     */
    @Test
    fun `ProcessM hoisting - single caret raises scope by one level`() {
        val pql = "select ^e:name"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        // ^e:name → trace.concept_name (EVENT raised to TRACE)
        // In our schema, trace concept:name maps to caseId
        assertTrue(
            result.query.contains("trace.caseId") || result.query.contains("trace.concept_name"),
            "^e:name should raise EVENT to TRACE scope, mapping name to caseId or concept_name",
        )

        // Should NOT contain event scope for this field
        assertFalse(result.query.contains("event.caseId"), "Should not use event scope after hoisting")
    }

    /**
     * Adapted from QueryTests.kt - whereSimpleWithHoistingTest
     * Tests: Double hoisting with ^^ operator
     * ProcessM: ^^e:name raises scope from EVENT to LOG
     */
    @Test
    fun `ProcessM hoisting - double caret raises scope by two levels`() {
        val pql = "select ^^e:name"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        // ^^e:name → log.name (EVENT raised to LOG)
        assertTrue(
            result.query.contains("log.name") || result.query.contains("log.concept_name"),
            "^^e:name should raise EVENT to LOG scope",
        )

        // Should NOT contain event or trace scope for this field
        assertFalse(result.query.contains("event.name"), "Should not use event scope after double hoisting")
        assertFalse(result.query.contains("trace.name"), "Should not use trace scope after double hoisting")
    }

    /**
     * Adapted from QueryTests.kt - whereSimpleWithHoistingTest
     * Tests: Hoisting in WHERE clause
     */
    @Test
    fun `ProcessM hoisting - in WHERE clause`() {
        val pql = "where ^e:name = 'test-case'"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("WHERE"), "Should have WHERE clause")
        // ^e:name in WHERE should raise to trace scope
        assertTrue(
            result.query.contains("trace.caseId") || result.query.contains("trace.concept_name"),
            "^e:name in WHERE should use trace scope",
        )
    }

    /**
     * Adapted from QueryTests.kt - groupByWithHoistingTest
     * Tests: Cross-scope aggregation with hoisting
     */
    @Test
    fun `ProcessM hoisting - cross-scope aggregation`() {
        val pql = "select ^e:name, count(e:id) group by ^e:name"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        // ^e:name → trace scope (caseId)
        assertTrue(
            result.query.contains("trace.caseId") || result.query.contains("trace.concept_name"),
            "^e:name should use trace scope",
        )

        // e:id → event.eventId
        assertTrue(result.query.contains("event.eventId"), "e:id should map to event.eventId")

        // Should have count aggregation
        assertTrue(result.query.contains("count"), "Should have count aggregation")
    }

    /**
     * Tests: Standard attribute with explicit XES name (not shorthand)
     * Example: event:org:group instead of event:group
     */
    @Test
    fun `ProcessM standardAttributes - full XES attribute names`() {
        val pql = "select e:org:group, e:cost:total"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        // Full XES names should be sanitized (: → _)
        assertTrue(
            result.query.contains("event.org_group"),
            "e:org:group should be sanitized to event.org_group",
        )
        assertTrue(
            result.query.contains("event.cost_total"),
            "e:cost:total should be sanitized to event.cost_total",
        )
    }

    /**
     * Adapted from QueryTests.kt - orderBySimpleTest
     * Tests: ORDER BY with standard attributes
     */
    @Test
    fun `ProcessM orderBy - with standard attributes`() {
        val pql = "select e:name, e:timestamp order by e:timestamp asc"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("ORDER BY"), "Should have ORDER BY clause")
        assertTrue(result.query.contains("event.timestamp"), "Should order by event.timestamp")
        assertTrue(result.query.contains("ASC"), "Should have ASC direction")
    }

    /**
     * Adapted from QueryTests.kt - limitAllTest
     * Tests: LIMIT clause
     */
    @Test
    fun `ProcessM limit - basic limit clause`() {
        val pql = "select e:name limit 10"
        val result = translateQuery(pql)

        println("PQL: $pql")
        println("Cypher: ${result.query}")

        assertTrue(result.query.contains("LIMIT 10"), "Should have LIMIT 10")
        assertTrue(result.query.contains("event.activity"), "e:name should map to event.activity")
    }
}
