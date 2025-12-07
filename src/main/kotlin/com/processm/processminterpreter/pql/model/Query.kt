package com.processm.processminterpreter.pql.model

import java.util.*

/**
 * Represents a parsed PQL query with all its components.
 *
 * A Query contains:
 * - SELECT clause: attributes and expressions to retrieve
 * - DELETE clause: optional scope to delete
 * - WHERE clause: filtering conditions
 * - GROUP BY clause: grouping attributes
 * - ORDER BY clause: sorting expressions
 * - LIMIT/OFFSET clauses: result pagination
 *
 * The Query class provides an immutable view of the query structure,
 * while allowing validation and modification through specific methods.
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
class Query(
    /**
     * The original query string.
     */
    val query: String = "",
) {

    // ========================================
    // SELECT CLAUSE
    // ========================================

    /**
     * Internal mutable map for selectAll.
     * True = SELECT * explicitly specified
     * False = SELECT with specific attributes
     * null = Not specified for this scope
     */
    private val _selectAll: MutableMap<Scope, Boolean?> = EnumMap<Scope, Boolean?>(Scope::class.java)

    /**
     * Internal mutable map for standard attributes in SELECT.
     */
    private val _selectStandardAttributes: MutableMap<Scope, LinkedHashSet<Attribute>> =
        EnumMap<Scope, LinkedHashSet<Attribute>>(Scope::class.java)

    /**
     * Internal mutable map for non-standard attributes in SELECT.
     */
    private val _selectOtherAttributes: MutableMap<Scope, LinkedHashSet<Attribute>> =
        EnumMap<Scope, LinkedHashSet<Attribute>>(Scope::class.java)

    /**
     * Internal mutable map for complex expressions in SELECT.
     */
    private val _selectExpressions: MutableMap<Scope, ArrayList<IExpression>> =
        EnumMap<Scope, ArrayList<IExpression>>(Scope::class.java)

    /**
     * Internal mutable map for implicit SELECT ALL per scope.
     */
    private val _isImplicitSelectAll: MutableMap<Scope, Boolean> =
        EnumMap<Scope, Boolean>(Scope::class.java).apply {
            Scope.entries.forEach { put(it, false) }
        }

    /**
     * Whether to select all attributes for each scope.
     *
     * - true: SELECT * explicitly specified
     * - false: SELECT with specific attributes
     * - null: Not specified for this scope
     */
    val selectAll: Map<Scope, Boolean?>
        get() = Scope.entries.associateWith { scope ->
            _selectAll[scope] == true || isImplicitSelectAll[scope] == true
        }

    /**
     * Standard attributes to select, organized by scope.
     *
     * Example:
     * - SELECT e:name, t:name
     * - selectStandardAttributes[EVENT] = [Attribute("e:name")]
     * - selectStandardAttributes[TRACE] = [Attribute("t:name")]
     */
    val selectStandardAttributes: Map<Scope, Set<Attribute>>
        get() = Collections.unmodifiableMap(_selectStandardAttributes)

    /**
     * Non-standard (custom) attributes to select, organized by scope.
     */
    val selectOtherAttributes: Map<Scope, Set<Attribute>>
        get() = Collections.unmodifiableMap(_selectOtherAttributes)

    /**
     * Complex expressions to select (functions, operators), organized by scope.
     *
     * Example:
     * - SELECT count(e:id), year(e:timestamp)
     * - selectExpressions[EVENT] = [Function("count", ...), Function("year", ...)]
     */
    val selectExpressions: Map<Scope, List<IExpression>>
        get() = Collections.unmodifiableMap(_selectExpressions)

    /**
     * Whether SELECT ALL is implicit for each scope.
     *
     * Implicit SELECT ALL occurs when no SELECT clause is specified,
     * defaulting to selecting all attributes.
     */
    val isImplicitSelectAll: Map<Scope, Boolean>
        get() = Scope.entries.associateWith { scope ->
            // If explicit SELECT * is set, it's not implicit
            if (_selectAll[scope] == true) return@associateWith false
            
            // If specific attributes/expressions are selected, it's not implicit
            if ((_selectStandardAttributes[scope]?.isNotEmpty() == true) ||
                (_selectOtherAttributes[scope]?.isNotEmpty() == true) ||
                (_selectExpressions[scope]?.isNotEmpty() == true)) {
                return@associateWith false
            }

            // If we are grouping by this scope, implicit select all is disabled
            // Also disabled if any upper scope is grouped (because lower scope attributes would need aggregation)
            var currentScope: Scope? = scope
            while (currentScope != null) {
                if (isGroupBy[currentScope] == true) {
                    return@associateWith false
                }
                currentScope = currentScope.upper
            }

            // Reverting to simple check + explicit flag
            val result = _isImplicitSelectAll[scope] == true

            result
        }

    // ========================================
    // DELETE CLAUSE
    // ========================================

    /**
     * The scope to delete (LOG, TRACE, or EVENT).
     * null if this is not a DELETE query.
     *
     * Example:
     * - DELETE TRACES WHERE ... → deleteScope = TRACE
     * - SELECT ... → deleteScope = null
     */
    var deleteScope: Scope? = null

    // ========================================
    // WHERE CLAUSE
    // ========================================

    /**
     * The WHERE clause filtering expression.
     * null if no WHERE clause is specified.
     *
     * Example:
     * - WHERE e:name = "A" AND e:timestamp > D2020-01-01
     * - whereExpression = BinaryOp(AND, ...)
     */
    var whereExpression: IExpression? = null

    // ========================================
    // GROUP BY CLAUSE
    // ========================================

    /**
     * Internal mutable map for standard attributes in GROUP BY.
     */
    private val _groupByStandardAttributes: MutableMap<Scope, LinkedHashSet<Attribute>> =
        EnumMap<Scope, LinkedHashSet<Attribute>>(Scope::class.java)

    /**
     * Internal mutable map for non-standard attributes in GROUP BY.
     */
    private val _groupByOtherAttributes: MutableMap<Scope, LinkedHashSet<Attribute>> =
        EnumMap<Scope, LinkedHashSet<Attribute>>(Scope::class.java)

    /**
     * Internal mutable map for implicit GROUP BY per scope.
     */
    private val _isImplicitGroupBy: MutableMap<Scope, Boolean> =
        EnumMap<Scope, Boolean>(Scope::class.java).apply {
            Scope.entries.forEach { put(it, false) }
        }

    /**
     * Standard attributes in GROUP BY clause, organized by scope.
     *
     * Example:
     * - GROUP BY e:name, t:name
     * - groupByStandardAttributes[EVENT] = [Attribute("e:name")]
     * - groupByStandardAttributes[TRACE] = [Attribute("t:name")]
     */
    val groupByStandardAttributes: Map<Scope, Set<Attribute>>
        get() = Collections.unmodifiableMap(_groupByStandardAttributes)

    /**
     * Non-standard (custom) attributes in GROUP BY clause, organized by scope.
     */
    val groupByOtherAttributes: Map<Scope, Set<Attribute>>
        get() = Collections.unmodifiableMap(_groupByOtherAttributes)

    /**
     * Whether grouping is active for each scope.
     *
     * Computed based on presence of GROUP BY attributes for that scope.
     */
    val isGroupBy: Map<Scope, Boolean>
        get() = Scope.entries.associateWith { scope ->
            (_groupByStandardAttributes[scope]?.isNotEmpty() == true) ||
                (_groupByOtherAttributes[scope]?.isNotEmpty() == true)
        }

    /**
     * Whether GROUP BY is implicit for each scope.
     *
     * Implicit GROUP BY occurs when aggregation functions are used
     * without an explicit GROUP BY clause.
     */
    val isImplicitGroupBy: Map<Scope, Boolean>
        get() = Scope.entries.associateWith { scope ->
            // If explicit GROUP BY is present, it's not implicit
            if (isGroupBy[scope] == true) return@associateWith false

            val explicit = _isImplicitGroupBy[scope] == true
            
            // Check for aggregations in SELECT
            val hasAggSelect = selectExpressions[scope]?.any { expr ->
                (expr as? Expression)?.filterRecursively { it is com.processm.processminterpreter.pql.model.Function && it.functionType == FunctionType.AGGREGATION }?.isNotEmpty() == true
            } == true

            // Check for aggregations in ORDER BY
            val hasAggOrder = _orderByExpressions[scope]?.any { orderedExpr ->
                (orderedExpr.expression as? Expression)?.filterRecursively { it is com.processm.processminterpreter.pql.model.Function && it.functionType == FunctionType.AGGREGATION }?.isNotEmpty() == true
            } == true

            explicit || hasAggSelect || hasAggOrder
        }

    /**
     * All attributes in GROUP BY clause (standard + other), flattened.
     */
    val groupByAttributes: Set<Attribute>
        get() = (groupByStandardAttributes.values.flatten() + groupByOtherAttributes.values.flatten()).toSet()

    // ========================================
    // ORDER BY CLAUSE
    // ========================================

    /**
     * Internal mutable map for ORDER BY expressions.
     */
    private val _orderByExpressions: MutableMap<Scope, ArrayList<OrderedExpression>> =
        EnumMap<Scope, ArrayList<OrderedExpression>>(Scope::class.java)

    /**
     * ORDER BY expressions with their sort direction, organized by scope.
     *
     * Example:
     * - ORDER BY e:timestamp ASC, e:name DESC
     * - orderByExpressions[EVENT] = [
     *     OrderedExpression(Attribute("e:timestamp"), ASCENDING),
     *     OrderedExpression(Attribute("e:name"), DESCENDING)
     *   ]
     */
    val orderByExpressions: Map<Scope, List<OrderedExpression>>
        get() = Collections.unmodifiableMap(_orderByExpressions)

    // ========================================
    // LIMIT & OFFSET CLAUSES
    // ========================================

    /**
     * Internal mutable map for LIMIT per scope.
     */
    private val _limit: MutableMap<Scope, Long> = EnumMap(Scope::class.java)

    /**
     * Internal mutable map for OFFSET per scope.
     */
    private val _offset: MutableMap<Scope, Long> = EnumMap(Scope::class.java)

    /**
     * Maximum number of results to return per scope.
     *
     * Example:
     * - LIMIT EVENTS 100, TRACES 50
     * - limit[EVENT] = 100
     * - limit[TRACE] = 50
     */
    val limit: Map<Scope, Long>
        get() = Collections.unmodifiableMap(_limit)

    /**
     * Number of results to skip per scope.
     *
     * Example:
     * - OFFSET EVENTS 10, TRACES 5
     * - offset[EVENT] = 10
     * - offset[TRACE] = 5
     */
    val offset: Map<Scope, Long>
        get() = Collections.unmodifiableMap(_offset)

    // ========================================
    // BUILDER METHODS (Internal Use)
    // ========================================

    /**
     * Add an attribute to SELECT clause.
     *
     * @param attr the attribute to add
     */
    internal fun addSelectAttribute(attr: Attribute) {
        val scope = attr.scope
        if (attr.isStandard) {
            _selectStandardAttributes.getOrPut(scope) { LinkedHashSet() }.add(attr)
        } else {
            _selectOtherAttributes.getOrPut(scope) { LinkedHashSet() }.add(attr)
        }
        // If specific attribute is selected, selectAll is false
        if (_selectAll[scope] != true) {
            _selectAll[scope] = false
        }
    }

    /**
     * Add an expression to SELECT clause.
     *
     * @param expr the expression to add
     * @param scope the scope for this expression
     */
    internal fun addSelectExpression(expr: IExpression, scope: Scope) {
        _selectExpressions.getOrPut(scope) { ArrayList() }.add(expr)
        // If specific expression is selected, selectAll is false
        if (_selectAll[scope] != true) {
            _selectAll[scope] = false
        }
    }

    /**
     * Set SELECT ALL for a scope.
     *
     * @param scope the scope
     * @param value true for explicit SELECT *, false for specific attributes
     */
    internal fun setSelectAll(scope: Scope, value: Boolean) {
        _selectAll[scope] = value
    }

    /**
     * Set implicit SELECT ALL for a scope.
     *
     * @param scope the scope
     * @param value true if SELECT ALL is implicit
     */
    internal fun setImplicitSelectAll(scope: Scope, value: Boolean) {
        _isImplicitSelectAll[scope] = value
    }

    /**
     * Add an attribute to GROUP BY clause.
     *
     * @param attr the attribute to add
     */
    internal fun addGroupByAttribute(attr: Attribute) {
        // Use effectiveScope to handle hoisting correctly
        val scope = attr.effectiveScope ?: attr.scope
        if (attr.isStandard) {
            _groupByStandardAttributes.getOrPut(scope) { LinkedHashSet() }.add(attr)
        } else {
            _groupByOtherAttributes.getOrPut(scope) { LinkedHashSet() }.add(attr)
        }
    }

    /**
     * Set implicit GROUP BY for a scope.
     *
     * @param scope the scope
     * @param value true if GROUP BY is implicit
     */
    internal fun setImplicitGroupBy(scope: Scope, value: Boolean) {
        _isImplicitGroupBy[scope] = value
    }

    /**
     * Add an ORDER BY expression.
     *
     * @param expr the expression to order by
     * @param direction the sort direction
     * @param scope the scope for this ordering
     */
    internal fun addOrderByExpression(expr: IExpression, direction: OrderDirection, scope: Scope) {
        _orderByExpressions.getOrPut(scope) { ArrayList() }.add(OrderedExpression(expr, direction))
    }

    /**
     * Set LIMIT for a scope.
     *
     * @param scope the scope
     * @param value the limit value
     */
    internal fun setLimit(scope: Scope, value: Long) {
        _limit[scope] = value
    }

    /**
     * Set OFFSET for a scope.
     *
     * @param scope the scope
     * @param value the offset value
     */
    internal fun setOffset(scope: Scope, value: Long) {
        _offset[scope] = value
    }

    // ========================================
    // VALIDATION METHODS
    // ========================================

    /**
     * Validate that SELECT ALL is not mixed with specific attribute selections.
     *
     * Rule: Cannot use "SELECT *" together with specific attribute names.
     *
     * @throws PQLSemanticException if validation fails
     */
    fun validateSelectAll() {
        selectAll.forEach { (scope, isAll) ->
            if (isAll == true) {
                // Check if specific attributes are also selected for this scope
                val hasSpecificAttributes = (selectStandardAttributes[scope]?.isNotEmpty() == true) ||
                    (selectOtherAttributes[scope]?.isNotEmpty() == true) ||
                    (selectExpressions[scope]?.isNotEmpty() == true)

                if (hasSpecificAttributes) {
                    throw PQLSemanticException(
                        "Cannot mix 'SELECT *' with specific attribute selections for scope $scope",
                    )
                }
            }
        }
    }



    /**
     * Validate that classifiers are used correctly.
     *
     * Rules:
     * - Classifiers (c:name or classifier:name) can only appear in GROUP BY
     * - Classifiers cannot appear in SELECT, WHERE, or ORDER BY
     *
     * @throws InvalidClassifierUsageException if validation fails
     */
    fun validateClassifiers() {
        // Check SELECT attributes
        (selectStandardAttributes.values + selectOtherAttributes.values).flatten().forEach { attr ->
            if (attr.isClassifier) {
                throw InvalidClassifierUsageException(
                    "Classifier '${attr.name}' cannot be used in SELECT clause. " +
                        "Classifiers are only allowed in GROUP BY.",
                )
            }
        }

        // Check WHERE expression
        whereExpression?.let { expr ->
            if (expr is Expression) {
                val classifierAttrs = expr.filter { it is Attribute && it.isClassifier }
                if (classifierAttrs.isNotEmpty()) {
                    val attr = classifierAttrs.first() as Attribute
                    throw InvalidClassifierUsageException(
                        "Classifier '${attr.name}' cannot be used in WHERE clause. " +
                            "Classifiers are only allowed in GROUP BY.",
                    )
                }
            }
        }

        // Check ORDER BY expressions
        orderByExpressions.values.flatten().forEach { ordered ->
            if (ordered.expression is Expression) {
                val classifierAttrs = ordered.expression.filter { it is Attribute && it.isClassifier }
                if (classifierAttrs.isNotEmpty()) {
                    val attr = classifierAttrs.first() as Attribute
                    throw InvalidClassifierUsageException(
                        "Classifier '${attr.name}' cannot be used in ORDER BY clause. " +
                            "Classifiers are only allowed in GROUP BY.",
                    )
                }
            }
        }
    }

    /**
     * Validate DELETE query constraints.
     *
     * Rules:
     * - DELETE queries cannot have SELECT clause
     * - DELETE queries must specify a scope
     * - DELETE with ORDER BY must also have LIMIT
     *
     * @throws PQLSemanticException if validation fails
     */
    fun validateDeleteConstraints() {
        if (deleteScope == null) {
            return // Not a DELETE query
        }

        // Check that no SELECT clause is present
        if (_selectAll.values.any { it == true } ||
            selectStandardAttributes.values.any { it.isNotEmpty() } ||
            selectOtherAttributes.values.any { it.isNotEmpty() } ||
            selectExpressions.values.any { it.isNotEmpty() }
        ) {
            throw PQLSemanticException(
                "DELETE queries cannot have SELECT clause",
            )
        }

        // If ORDER BY is present, LIMIT must also be present
        if (orderByExpressions.values.any { it.isNotEmpty() } &&
            limit.isEmpty()
        ) {
            throw PQLSemanticException(
                "DELETE queries with ORDER BY must also specify LIMIT",
            )
        }
    }

    /**
     * Validate WHERE clause expressions.
     *
     * Rules:
     * - WHERE clause cannot contain aggregation functions
     * - Expressions must have compatible types
     *
     * @throws PQLSemanticException if validation fails
     */
    fun validateWhereClause() {
        whereExpression?.let { expr ->
            if (expr is Expression) {
                // Check for aggregation functions in WHERE clause
                val aggFunctions = expr.filter {
                    it is com.processm.processminterpreter.pql.model.Function &&
                        it.functionType == FunctionType.AGGREGATION
                }

                if (aggFunctions.isNotEmpty()) {
                    val func = aggFunctions.first() as com.processm.processminterpreter.pql.model.Function
                    throw PQLSemanticException(
                        "Aggregation function '${func.name}' cannot be used in WHERE clause. " +
                            "Use HAVING clause instead (not yet implemented).",
                    )
                }
            }
        }
    }

    /**
     * Validate scope hoisting.
     *
     * Rules:
     * - Attributes cannot be hoisted beyond LOG scope (highest in hierarchy)
     * - Using ^ or ^^ must result in valid scope (ordinal >= 0)
     *
     * Note: This validation is mostly redundant since Attribute constructor
     * already validates hoisting. However, it provides a centralized way to
     * check all attributes in a query.
     *
     * @throws PQLSemanticException if validation fails
     */
    fun validateHoisting() {
        // Helper to check all attributes in a collection
        fun checkAttributes(attrs: Collection<Attribute>) {
            attrs.forEach { attr ->
                // Check if scope is invalid (ordinal < 0 means beyond LOG)
                if (attr.scope.ordinal < 0) {
                    throw PQLSemanticException(
                        "Attribute '$attr' hoisted beyond LOG scope. " +
                            "Hoisting prefix '${attr.hoistingPrefix}' results in invalid scope.",
                    )
                }
            }
        }

        // Helper to check attributes in expressions
        fun checkExpression(expr: IExpression) {
            if (expr is Expression) {
                val attributes = expr.filter { it is Attribute } as List<Attribute>
                checkAttributes(attributes)
            }
        }

        // Check SELECT attributes
        selectStandardAttributes.values.forEach { checkAttributes(it) }
        selectOtherAttributes.values.forEach { checkAttributes(it) }

        // Check SELECT expressions
        selectExpressions.values.flatten().forEach { checkExpression(it) }

        // Check WHERE expression
        whereExpression?.let { checkExpression(it) }

        // Check GROUP BY attributes
        groupByStandardAttributes.values.forEach { checkAttributes(it) }
        groupByOtherAttributes.values.forEach { checkAttributes(it) }

        // Check ORDER BY expressions
        orderByExpressions.values.flatten().forEach { ordered ->
            checkExpression(ordered.expression)
        }
    }

    /**
     * Validate all query constraints.
     *
     * This is a convenience method that calls all validation methods.
     * Call this after building the query to ensure it's valid.
     *
     * @throws PQLSemanticException if any validation fails
     */
    fun validate() {
        validateSelectAll()
        validateGroupBy()
        validateClassifiers()
        validateHoisting()
        validateWhereClause()
        validateDeleteConstraints()
    }

    /**
     * Validate GROUP BY clause constraints.
     *
     * Rules:
     * - If GROUP BY is present, all non-aggregated attributes in SELECT must be in GROUP BY.
     * - This applies per scope.
     *
     * @throws PQLSemanticException if validation fails
     */
    fun validateGroupBy() {
        // Iterate over all scopes
        Scope.entries.forEach { scope ->
            val isGrouped = isGroupBy[scope] == true

            // Check if there is any aggregation in the query
            val hasAggregation = selectExpressions.values.flatten().any { expr ->
                 (expr as? Expression)?.filterRecursively { it is com.processm.processminterpreter.pql.model.Function && it.functionType == FunctionType.AGGREGATION }?.isNotEmpty() == true
            }

            if (hasAggregation) {
                // If we have aggregation, all non-aggregated attributes must be grouped
                // OR belong to a scope that is implicitly grouped.
                // For now, we enforce strict grouping: if aggregation exists, everything else must be grouped or aggregated.
                
                // Check standard attributes in SELECT
                selectStandardAttributes[scope]?.forEach { attr ->
                    validateAttributeInGroupBy(attr)
                }

                // Check other attributes in SELECT
                selectOtherAttributes[scope]?.forEach { attr ->
                    validateAttributeInGroupBy(attr)
                }
                
                // Check expressions in SELECT
                selectExpressions[scope]?.forEach { expr ->
                     validateExpressionInGroupBy(expr)
                }
            } else if (isGrouped) {
                // If no aggregation but we have GROUP BY, then selected attributes must be in GROUP BY
                
                 // Check standard attributes in SELECT
                selectStandardAttributes[scope]?.forEach { attr ->
                    validateAttributeInGroupBy(attr)
                }

                // Check other attributes in SELECT
                selectOtherAttributes[scope]?.forEach { attr ->
                    validateAttributeInGroupBy(attr)
                }
                
                // Check expressions in SELECT
                selectExpressions[scope]?.forEach { expr ->
                     validateExpressionInGroupBy(expr)
                }
            }
        }
    }

    private fun validateAttributeInGroupBy(attr: Attribute) {
        // Attribute is valid if:
        // 1. It is present in GROUP BY for its scope
        // 2. OR any LOWER scope is grouped (implicit grouping of upper scopes)
        
        val scope = attr.effectiveScope ?: attr.scope
        
        // Check 1: Present in GROUP BY
        val groupAttributes = groupByStandardAttributes[scope].orEmpty() +
            groupByOtherAttributes[scope].orEmpty()
            
        if (groupAttributes.contains(attr)) {
            return
        }
        
        // Check 2: Lower scope is grouped (Only for LOG scope)
        // If we are selecting a LOG attribute (which is constant for the whole log),
        // and we group by TRACE or EVENT (which implies we are inside the log), it's valid.
        // For TRACE scope, grouping by EVENT does NOT imply TRACE is constant (unless grouping by traceId),
        // so we enforce strict GROUP BY for TRACE.
        if (scope == Scope.LOG) {
            val lowerScopes = Scope.entries.filter { it.ordinal > scope.ordinal }
            if (lowerScopes.any { isGroupBy[it] == true }) {
                return
            }
        }

        // Check 3: Hoisted version is grouped
        // Example: select e:name group by ^e:name
        // We check if ^e:name, ^^e:name, etc. are in GROUP BY
        var currentAttrStr = attr.toString()
        var currentScope = scope
        
        while (currentScope.upper != null) {
            currentAttrStr = "^$currentAttrStr"
            currentScope = currentScope.upper!!
            
            val hoistedAttr = Attribute(currentAttrStr)
            val hoistedGroupAttributes = groupByStandardAttributes[currentScope].orEmpty() +
                groupByOtherAttributes[currentScope].orEmpty()
                
            if (hoistedGroupAttributes.contains(hoistedAttr)) {
                return
            }
        }
        
        throw PQLSemanticException(
            "Attribute '$attr' must be present in GROUP BY clause or used in an aggregation function.",
        )
    }

    private fun validateExpressionInGroupBy(expr: IExpression) {
        if (expr is com.processm.processminterpreter.pql.model.Function && expr.functionType == FunctionType.AGGREGATION) {
            return // Aggregations are valid
        }

        if (expr is Attribute) {
            validateAttributeInGroupBy(expr)
            return
        }

        // For other expressions, check children recursively
        expr.children.forEach { child ->
            validateExpressionInGroupBy(child)
        }
    }

    /**
     * Apply maximum limits to the query without exceeding existing limits.
     *
     * This method sets upper bounds on results while preserving any
     * more restrictive limits already specified.
     *
     * @param maxLimits map of maximum limits per scope
     */
    fun applyLimits(maxLimits: Map<Scope, Long>) {
        maxLimits.forEach { (scope, maxLimit) ->
            val currentLimit = _limit[scope]
            if (currentLimit == null || currentLimit > maxLimit) {
                _limit[scope] = maxLimit
            }
        }
    }

    override fun toString(): String {
        return query.ifEmpty { "Query()" }
    }
}

/**
 * Represents an expression with an ordering direction.
 *
 * Used in ORDER BY clauses to specify both what to order by
 * and in which direction.
 */
data class OrderedExpression(
    val expression: IExpression,
    val direction: OrderDirection,
) {
    override fun toString(): String = "$expression ${direction.name}"
}
