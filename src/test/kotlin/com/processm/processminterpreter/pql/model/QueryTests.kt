package com.processm.processminterpreter.pql.model

import com.processm.processminterpreter.pql.visitor.BinaryOperator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for Query class.
 *
 * Tests the Query data model including SELECT, DELETE, WHERE,
 * GROUP BY, ORDER BY, LIMIT, and OFFSET clauses.
 */
class QueryTests {

    // ========================================
    // BASIC CONSTRUCTION TESTS
    // ========================================

    @Test
    fun emptyQueryTest() {
        val query = Query()

        assertEquals("", query.query)
        assertNull(query.deleteScope)
        assertNull(query.whereExpression)

        // All maps should be empty
        // selectAll contains all scopes by default (false)
        assertEquals(3, query.selectAll.size)
        assertFalse(query.selectAll[Scope.EVENT]!!)
        assertFalse(query.selectAll[Scope.TRACE]!!)
        assertFalse(query.selectAll[Scope.LOG]!!)
        assertTrue(query.selectStandardAttributes.isEmpty())
        assertTrue(query.selectOtherAttributes.isEmpty())
        assertTrue(query.selectExpressions.isEmpty())
        assertTrue(query.groupByStandardAttributes.isEmpty())
        assertTrue(query.groupByOtherAttributes.isEmpty())
        assertTrue(query.orderByExpressions.isEmpty())
        assertTrue(query.limit.isEmpty())
        assertTrue(query.offset.isEmpty())
    }

    @Test
    fun queryStringTest() {
        val queryStr = "SELECT * FROM LOGS"
        val query = Query(queryStr)

        assertEquals(queryStr, query.query)
        assertEquals(queryStr, query.toString())
    }

    // ========================================
    // SELECT CLAUSE TESTS
    // ========================================

    @Test
    fun selectStandardAttributeTest() {
        val query = Query()
        val attr = Attribute("e:name")

        query.addSelectAttribute(attr)

        assertEquals(1, query.selectStandardAttributes[Scope.EVENT]?.size)
        assertTrue(query.selectStandardAttributes[Scope.EVENT]?.contains(attr) == true)
        assertTrue(query.selectOtherAttributes[Scope.EVENT]?.isEmpty() != false)
    }

    @Test
    fun selectCustomAttributeTest() {
        val query = Query()
        val attr = Attribute("e:customAttr")

        query.addSelectAttribute(attr)

        assertEquals(1, query.selectOtherAttributes[Scope.EVENT]?.size)
        assertTrue(query.selectOtherAttributes[Scope.EVENT]?.contains(attr) == true)
        assertTrue(query.selectStandardAttributes[Scope.EVENT]?.isEmpty() != false)
    }

    @Test
    fun selectMultipleAttributesTest() {
        val query = Query()
        val attr1 = Attribute("e:name")
        val attr2 = Attribute("e:timestamp")
        val attr3 = Attribute("t:name")

        query.addSelectAttribute(attr1)
        query.addSelectAttribute(attr2)
        query.addSelectAttribute(attr3)

        // EVENT scope should have 2 attributes
        assertEquals(2, query.selectStandardAttributes[Scope.EVENT]?.size)

        // TRACE scope should have 1 attribute
        assertEquals(1, query.selectStandardAttributes[Scope.TRACE]?.size)
    }

    @Test
    fun selectExpressionTest() {
        val query = Query()
        val attr = Attribute("e:timestamp")
        val func = Function("year", args = arrayOf(attr))

        query.addSelectExpression(func, Scope.EVENT)

        assertEquals(1, query.selectExpressions[Scope.EVENT]?.size)
        assertEquals(func, query.selectExpressions[Scope.EVENT]?.get(0))
    }

    @Test
    fun selectAllTest() {
        val query = Query()

        query.setSelectAll(Scope.EVENT, true)
        query.setSelectAll(Scope.TRACE, false)

        assertEquals(true, query.selectAll[Scope.EVENT])
        assertEquals(false, query.selectAll[Scope.TRACE])
        assertEquals(false, query.selectAll[Scope.LOG])
    }

    @Test
    fun implicitSelectAllTest() {
        val query = Query()

        // Initially all false
        assertEquals(false, query.isImplicitSelectAll[Scope.EVENT])
        assertEquals(false, query.isImplicitSelectAll[Scope.TRACE])
        assertEquals(false, query.isImplicitSelectAll[Scope.LOG])

        query.setImplicitSelectAll(Scope.EVENT, true)

        assertEquals(true, query.isImplicitSelectAll[Scope.EVENT])
        assertEquals(false, query.isImplicitSelectAll[Scope.TRACE])
    }

    // ========================================
    // DELETE CLAUSE TESTS
    // ========================================

    @Test
    fun deleteScopeTest() {
        val query = Query()

        assertNull(query.deleteScope)

        query.deleteScope = Scope.TRACE

        assertEquals(Scope.TRACE, query.deleteScope)
    }

    // ========================================
    // WHERE CLAUSE TESTS
    // ========================================

    @Test
    fun whereExpressionTest() {
        val query = Query()
        val attr = Attribute("e:name")

        assertNull(query.whereExpression)

        query.whereExpression = attr

        assertEquals(attr, query.whereExpression)
    }

    // ========================================
    // GROUP BY CLAUSE TESTS
    // ========================================

    @Test
    fun groupByStandardAttributeTest() {
        val query = Query()
        val attr = Attribute("e:name")

        query.addGroupByAttribute(attr)

        assertEquals(1, query.groupByStandardAttributes[Scope.EVENT]?.size)
        assertTrue(query.groupByStandardAttributes[Scope.EVENT]?.contains(attr) == true)
        assertTrue(query.isGroupBy[Scope.EVENT] == true)
    }

    @Test
    fun groupByCustomAttributeTest() {
        val query = Query()
        val attr = Attribute("e:customAttr")

        query.addGroupByAttribute(attr)

        assertEquals(1, query.groupByOtherAttributes[Scope.EVENT]?.size)
        assertTrue(query.groupByOtherAttributes[Scope.EVENT]?.contains(attr) == true)
        assertTrue(query.isGroupBy[Scope.EVENT] == true)
    }

    @Test
    fun isGroupByTest() {
        val query = Query()

        // Initially no grouping
        assertEquals(false, query.isGroupBy[Scope.EVENT])
        assertEquals(false, query.isGroupBy[Scope.TRACE])
        assertEquals(false, query.isGroupBy[Scope.LOG])

        // Add GROUP BY attribute
        query.addGroupByAttribute(Attribute("e:name"))

        // Now EVENT scope has grouping
        assertEquals(true, query.isGroupBy[Scope.EVENT])
        assertEquals(false, query.isGroupBy[Scope.TRACE])
    }

    @Test
    fun implicitGroupByTest() {
        val query = Query()

        // Initially all false
        assertEquals(false, query.isImplicitGroupBy[Scope.EVENT])

        query.setImplicitGroupBy(Scope.EVENT, true)

        assertEquals(true, query.isImplicitGroupBy[Scope.EVENT])
        assertEquals(false, query.isImplicitGroupBy[Scope.TRACE])
    }

    // ========================================
    // ORDER BY CLAUSE TESTS
    // ========================================

    @Test
    fun orderByExpressionTest() {
        val query = Query()
        val attr1 = Attribute("e:timestamp")
        val attr2 = Attribute("e:name")

        query.addOrderByExpression(attr1, OrderDirection.ASCENDING, Scope.EVENT)
        query.addOrderByExpression(attr2, OrderDirection.DESCENDING, Scope.EVENT)

        val orderExprs = query.orderByExpressions[Scope.EVENT]
        assertEquals(2, orderExprs?.size)

        assertEquals(attr1, orderExprs?.get(0)?.expression)
        assertEquals(OrderDirection.ASCENDING, orderExprs?.get(0)?.direction)

        assertEquals(attr2, orderExprs?.get(1)?.expression)
        assertEquals(OrderDirection.DESCENDING, orderExprs?.get(1)?.direction)
    }

    @Test
    fun orderedExpressionToStringTest() {
        val attr = Attribute("e:name")
        val ordered = OrderedExpression(attr, OrderDirection.ASCENDING)

        assertEquals("e:name ASCENDING", ordered.toString())
    }

    // ========================================
    // LIMIT & OFFSET TESTS
    // ========================================

    @Test
    fun limitTest() {
        val query = Query()

        query.setLimit(Scope.EVENT, 100)
        query.setLimit(Scope.TRACE, 50)

        assertEquals(100L, query.limit[Scope.EVENT])
        assertEquals(50L, query.limit[Scope.TRACE])
        assertNull(query.limit[Scope.LOG])
    }

    @Test
    fun offsetTest() {
        val query = Query()

        query.setOffset(Scope.EVENT, 10)
        query.setOffset(Scope.TRACE, 5)

        assertEquals(10L, query.offset[Scope.EVENT])
        assertEquals(5L, query.offset[Scope.TRACE])
        assertNull(query.offset[Scope.LOG])
    }

    @Test
    fun applyLimitsTest() {
        val query = Query()

        // Set initial limits
        query.setLimit(Scope.EVENT, 1000)
        query.setLimit(Scope.TRACE, 50)

        // Apply max limits
        val maxLimits = mapOf(
            Scope.EVENT to 100L, // More restrictive than 1000
            Scope.TRACE to 100L, // Less restrictive than 50
            Scope.LOG to 10L, // New limit
        )

        query.applyLimits(maxLimits)

        // EVENT limit should be reduced to 100
        assertEquals(100L, query.limit[Scope.EVENT])

        // TRACE limit should remain 50 (more restrictive)
        assertEquals(50L, query.limit[Scope.TRACE])

        // LOG limit should be set to 10
        assertEquals(10L, query.limit[Scope.LOG])
    }

    // ========================================
    // VALIDATION TESTS
    // ========================================

    @Test
    fun validateSelectAllSuccessTest() {
        val query = Query()

        // SELECT * for EVENT scope only
        query.setSelectAll(Scope.EVENT, true)

        // Should not throw
        assertDoesNotThrow {
            query.validateSelectAll()
        }
    }

    @Test
    fun validateSelectAllFailureTest() {
        val query = Query()

        // SELECT * for EVENT scope
        query.setSelectAll(Scope.EVENT, true)

        // Also add specific attribute for EVENT scope
        query.addSelectAttribute(Attribute("e:name"))

        // Should throw exception
        assertThrows(PQLSemanticException::class.java) {
            query.validateSelectAll()
        }
    }

    @Test
    fun validateGroupByWithAggregationSuccessTest() {
        val query = Query()

        // SELECT e:name, count(e:id) GROUP BY e:name
        val attr = Attribute("e:name")
        val countFunc = Function("count", args = arrayOf(Attribute("e:id")))

        query.addSelectAttribute(attr)
        query.addSelectExpression(countFunc, Scope.EVENT)
        query.addGroupByAttribute(attr)

        // Should not throw - e:name is in GROUP BY
        assertDoesNotThrow {
            query.validateGroupBy()
        }
    }

    @Test
    fun validateGroupByWithAggregationFailureTest() {
        val query = Query()

        // SELECT e:name, count(e:id) - missing GROUP BY
        val attr = Attribute("e:name")
        val countFunc = Function("count", args = arrayOf(Attribute("e:id")))

        query.addSelectAttribute(attr)
        query.addSelectExpression(countFunc, Scope.EVENT)
        // NOT adding e:name to GROUP BY

        // Should throw - e:name is not in GROUP BY but aggregation is used
        assertThrows(PQLSemanticException::class.java) {
            query.validateGroupBy()
        }
    }

    @Test
    fun validateGroupByWithoutAggregationTest() {
        val query = Query()

        // SELECT e:name - no aggregation, no GROUP BY needed
        query.addSelectAttribute(Attribute("e:name"))

        // Should not throw - no aggregation, so GROUP BY not required
        assertDoesNotThrow {
            query.validateGroupBy()
        }
    }

    // ========================================
    // CLASSIFIER VALIDATION TESTS
    // ========================================

    @Test
    fun validateClassifiersInGroupBySuccessTest() {
        val query = Query()

        // Classifiers are allowed in GROUP BY
        val classifier = Attribute("e:c:name")
        query.addGroupByAttribute(classifier)

        assertDoesNotThrow {
            query.validateClassifiers()
        }
    }

    @Test
    fun validateClassifiersInSelectFailureTest() {
        val query = Query()

        // Classifiers NOT allowed in SELECT
        val classifier = Attribute("e:c:name")
        query.addSelectAttribute(classifier)

        assertThrows(InvalidClassifierUsageException::class.java) {
            query.validateClassifiers()
        }
    }

    @Test
    fun validateClassifiersInWhereFailureTest() {
        val query = Query()

        // Classifiers NOT allowed in WHERE
        val classifier = Attribute("e:classifier:name")
        val whereExpr = BinaryOperator("=", classifier, StringLiteral("test"))
        query.whereExpression = whereExpr

        assertThrows(InvalidClassifierUsageException::class.java) {
            query.validateClassifiers()
        }
    }

    @Test
    fun validateClassifiersInOrderByFailureTest() {
        val query = Query()

        // Classifiers NOT allowed in ORDER BY
        val classifier = Attribute("e:c:name")
        query.addOrderByExpression(classifier, OrderDirection.ASCENDING, Scope.EVENT)

        assertThrows(InvalidClassifierUsageException::class.java) {
            query.validateClassifiers()
        }
    }

    // ========================================
    // HOISTING VALIDATION TESTS
    // ========================================

    @Test
    fun validateHoistingValidTest() {
        val query = Query()

        // ^e:name (EVENT -> TRACE) is valid
        val attr1 = Attribute("^e:name")
        query.addSelectAttribute(attr1)

        // ^^e:name (EVENT -> LOG) is valid
        val attr2 = Attribute("^^e:name")
        query.addSelectAttribute(attr2)

        // ^t:name (TRACE -> LOG) is valid
        val attr3 = Attribute("^t:name")
        query.addSelectAttribute(attr3)

        assertDoesNotThrow {
            query.validateHoisting()
        }
    }

    @Test
    fun validateHoistingBeyondLogFailureTest() {
        // Invalid hoisting is caught by Attribute constructor, not validateHoisting()
        // ^^t:name would hoist TRACE (1) up 2 levels to -1 (invalid!)
        assertThrows(InvalidScopeHoistingException::class.java) {
            Attribute("^^t:name")
        }
    }

    @Test
    fun validateHoistingInWhereTest() {
        // Invalid hoisting is caught by Attribute constructor, not validateHoisting()
        // ^^^e:name would hoist EVENT (2) up 3 levels to -1 (invalid!)
        assertThrows(InvalidScopeHoistingException::class.java) {
            Attribute("^^^e:name")
        }
    }

    @Test
    fun validateHoistingInOrderByTest() {
        // Invalid hoisting is caught by Attribute constructor, not validateHoisting()
        // ^^t:name would hoist TRACE (1) up 2 levels to -1 (invalid!)
        assertThrows(InvalidScopeHoistingException::class.java) {
            Attribute("^^t:name")
        }
    }

    // ========================================
    // WHERE CLAUSE VALIDATION TESTS
    // ========================================

    @Test
    fun validateWhereClauseNoAggregationSuccessTest() {
        val query = Query()

        // WHERE with normal expression (no aggregation) is valid
        val attr = Attribute("e:name")
        val whereExpr = BinaryOperator("=", attr, StringLiteral("A"))
        query.whereExpression = whereExpr

        assertDoesNotThrow {
            query.validateWhereClause()
        }
    }

    @Test
    fun validateWhereClauseWithScalarFunctionSuccessTest() {
        val query = Query()

        // Scalar functions are allowed in WHERE
        val func = Function("year", args = arrayOf(Attribute("e:timestamp")))
        val whereExpr = BinaryOperator(">", func, NumberLiteral(2020.0))
        query.whereExpression = whereExpr

        assertDoesNotThrow {
            query.validateWhereClause()
        }
    }

    @Test
    fun validateWhereClauseWithAggregationFailureTest() {
        val query = Query()

        // Aggregation functions NOT allowed in WHERE
        val countFunc = Function("count", args = arrayOf(Attribute("e:id")))
        val whereExpr = BinaryOperator(">", countFunc, NumberLiteral(10.0))
        query.whereExpression = whereExpr

        assertThrows(PQLSemanticException::class.java) {
            query.validateWhereClause()
        }
    }

    @Test
    fun validateWhereClauseWithNestedAggregationFailureTest() {
        val query = Query()

        // Even nested aggregation in complex expression should be caught
        val sumFunc = Function("sum", args = arrayOf(Attribute("e:cost")))
        val leftExpr = BinaryOperator("+", sumFunc, NumberLiteral(100.0))
        val whereExpr = BinaryOperator(">", leftExpr, NumberLiteral(1000.0))
        query.whereExpression = whereExpr

        assertThrows(PQLSemanticException::class.java) {
            query.validateWhereClause()
        }
    }

    // ========================================
    // DELETE CONSTRAINT VALIDATION TESTS
    // ========================================

    @Test
    fun validateDeleteValidTest() {
        val query = Query()

        // Valid DELETE query with WHERE
        query.deleteScope = Scope.TRACE
        val whereExpr = BinaryOperator("=", Attribute("t:name"), StringLiteral("BadTrace"))
        query.whereExpression = whereExpr

        assertDoesNotThrow {
            query.validateDeleteConstraints()
        }
    }

    @Test
    fun validateDeleteWithOrderByAndLimitSuccessTest() {
        val query = Query()

        // DELETE with ORDER BY and LIMIT is valid
        query.deleteScope = Scope.EVENT
        query.addOrderByExpression(Attribute("e:timestamp"), OrderDirection.DESCENDING, Scope.EVENT)
        query.setLimit(Scope.EVENT, 10)

        assertDoesNotThrow {
            query.validateDeleteConstraints()
        }
    }

    @Test
    fun validateDeleteWithSelectFailureTest() {
        val query = Query()

        // DELETE with SELECT should fail
        query.deleteScope = Scope.EVENT
        query.addSelectAttribute(Attribute("e:name"))

        assertThrows(PQLSemanticException::class.java) {
            query.validateDeleteConstraints()
        }
    }

    @Test
    fun validateDeleteWithSelectAllFailureTest() {
        val query = Query()

        // DELETE with SELECT * should fail
        query.deleteScope = Scope.TRACE
        query.setSelectAll(Scope.TRACE, true)

        assertThrows(PQLSemanticException::class.java) {
            query.validateDeleteConstraints()
        }
    }

    @Test
    fun validateDeleteWithOrderByNoLimitFailureTest() {
        val query = Query()

        // DELETE with ORDER BY but no LIMIT should fail
        query.deleteScope = Scope.EVENT
        query.addOrderByExpression(Attribute("e:timestamp"), OrderDirection.DESCENDING, Scope.EVENT)
        // NOT adding LIMIT

        assertThrows(PQLSemanticException::class.java) {
            query.validateDeleteConstraints()
        }
    }

    @Test
    fun validateDeleteNotDeleteQueryTest() {
        val query = Query()

        // Not a DELETE query - should pass validation
        query.addSelectAttribute(Attribute("e:name"))

        assertDoesNotThrow {
            query.validateDeleteConstraints()
        }
    }

    // ========================================
    // COMPOSITE VALIDATE METHOD TESTS
    // ========================================

    @Test
    fun validateAllSuccessTest() {
        val query = Query()

        // Valid query: SELECT e:name, count(e:id) GROUP BY e:name
        query.addSelectAttribute(Attribute("e:name"))
        query.addSelectExpression(Function("count", args = arrayOf(Attribute("e:id"))), Scope.EVENT)
        query.addGroupByAttribute(Attribute("e:name"))

        // Should call all validation methods and pass
        assertDoesNotThrow {
            query.validate()
        }
    }

    @Test
    fun validateAllSelectConflictFailureTest() {
        val query = Query()

        // Invalid: SELECT * with specific attributes
        query.setSelectAll(Scope.EVENT, true)
        query.addSelectAttribute(Attribute("e:name"))

        // Should fail on validateSelectAll()
        assertThrows(PQLSemanticException::class.java) {
            query.validate()
        }
    }

    @Test
    fun validateAllGroupByMissingFailureTest() {
        val query = Query()

        // Invalid: aggregation without GROUP BY for non-aggregated attributes
        query.addSelectAttribute(Attribute("e:name"))
        query.addSelectExpression(Function("count", args = arrayOf(Attribute("e:id"))), Scope.EVENT)
        // Missing GROUP BY

        // Should fail on validateGroupByAttributes()
        assertThrows(PQLSemanticException::class.java) {
            query.validate()
        }
    }

    @Test
    fun validateAllClassifierInSelectFailureTest() {
        val query = Query()

        // Invalid: classifier in SELECT
        query.addSelectAttribute(Attribute("e:c:name"))

        // Should fail on validateClassifiers()
        assertThrows(InvalidClassifierUsageException::class.java) {
            query.validate()
        }
    }

    @Test
    fun validateAllHoistingFailureTest() {
        // Invalid: hoisting beyond LOG scope
        // Attribute constructor throws before we can even add it to query
        assertThrows(InvalidScopeHoistingException::class.java) {
            Attribute("^^t:name")
        }
    }

    @Test
    fun validateAllAggregationInWhereFailureTest() {
        val query = Query()

        // Invalid: aggregation in WHERE
        val countFunc = Function("count", args = arrayOf(Attribute("e:id")))
        query.whereExpression = BinaryOperator(">", countFunc, NumberLiteral(10.0))

        // Should fail on validateWhereClause()
        assertThrows(PQLSemanticException::class.java) {
            query.validate()
        }
    }

    @Test
    fun validateAllDeleteWithSelectFailureTest() {
        val query = Query()

        // Invalid: DELETE with SELECT
        query.deleteScope = Scope.EVENT
        query.addSelectAttribute(Attribute("e:name"))

        // Should fail on validateDeleteConstraints()
        assertThrows(PQLSemanticException::class.java) {
            query.validate()
        }
    }

    // ========================================
    // IMMUTABILITY TESTS
    // ========================================

    @Test
    fun publicMapsAreImmutableTest() {
        val query = Query()
        val attr = Attribute("e:name")

        query.addSelectAttribute(attr)

        // Try to modify the public map - should throw UnsupportedOperationException
        assertThrows(UnsupportedOperationException::class.java) {
            (query.selectStandardAttributes as MutableMap)[Scope.EVENT] = LinkedHashSet()
        }
    }
}
