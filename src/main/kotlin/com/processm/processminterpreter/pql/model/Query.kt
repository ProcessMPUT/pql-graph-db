package com.processm.processminterpreter.pql.model

import QLLexer
import QLParser
import com.processm.processminterpreter.pql.visitor.PQLErrorListener
import com.processm.processminterpreter.pql.visitor.QueryBuilder
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
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
 * When created with a non-empty query string, the query is automatically parsed.
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
class Query private constructor(
    /**
     * The original query string.
     */
    val query: String,
    /**
     * Whether to parse the query string.
     * Internal parameter to avoid parsing when copying from QueryBuilder.
     */
    parse: Boolean
) {

    /**
     * Primary constructor - parses the query string.
     */
    constructor(query: String = "") : this(query, query.isNotBlank())

    // NOTE: init block is at the end of the class to ensure all properties are initialized first

    /**
     * Internal flag to track if parsing should be done.
     */
    private val _shouldParse: Boolean = parse

    /**
     * Parse the query string and populate this Query object.
     */
    private fun parseAndBuild(queryString: String) {
        // Create input stream from PQL query string
        val input = CharStreams.fromString(queryString)

        // Create lexer (tokenizer) with custom error listener
        val errorListener = PQLErrorListener()
        val lexer = QLLexer(input)
        lexer.removeErrorListeners()
        lexer.addErrorListener(errorListener)

        // Create token stream
        val tokens = CommonTokenStream(lexer)

        // Create parser with same error listener
        val parser = QLParser(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)

        // Parse the query and build AST
        val tree = parser.query()

        // Check for syntax errors - throws PQLParserException
        // Check both error listener and parser's internal error count
        val numErrors = parser.numberOfSyntaxErrors
        if (numErrors > 0) {
            if (errorListener.hasErrors()) {
                errorListener.throwIfErrors()
            } else {
                // Parser detected errors but listener didn't catch them
                throw PQLParserException(
                    problem = PQLParserException.Problem.Unknown,
                    line = 1,
                    charPositionInLine = 0,
                    offendingToken = TokenSequence("", -1, -1),
                    expectedTokens = null,
                    originalMessage = "Parser detected $numErrors syntax error(s)",
                    baseException = null
                )
            }
        }
        errorListener.throwIfErrors()

        // Build query using QueryBuilder (populates this object)
        QueryBuilder().buildInto(tree, this)

        // Validate the query
        validate()
    }

    companion object {
        /**
         * Create an empty Query without parsing.
         * Used internally by QueryBuilder.
         */
        internal fun empty(queryString: String = ""): Query {
            return Query(queryString, false)
        }
    }

    // ========================================
    // WARNING (ProcessM Compatibility)
    // ========================================

    /**
     * Single warning emitted during query parsing/validation.
     *
     * ProcessM compatibility: ProcessM uses a single warning property.
     * Only the first warning is stored - subsequent warnings are ignored.
     *
     * Warnings indicate potential issues but don't prevent query execution.
     * Examples:
     * - SELECT ALL mixed with specific attributes (attributes ignored)
     * - Decimal value in LIMIT/OFFSET (truncated to integer)
     * - ORDER BY removed due to implicit GROUP BY
     */
    var warning: Exception? = null
        private set

    /**
     * Emit a warning during validation.
     *
     * ProcessM compatibility: Only the first warning is stored.
     *
     * @param newWarning the warning to emit
     */
    fun emitWarning(newWarning: Exception) {
        if (warning == null) {
            warning = newWarning
        }
    }

    /**
     * Check if a warning was emitted.
     */
    fun hasWarnings(): Boolean = warning != null

    /**
     * Clear warning.
     */
    fun clearWarnings() {
        warning = null
    }

    /**
     * Deprecated: Use warning property instead.
     * Kept for backward compatibility.
     */
    @Deprecated("Use warning property instead", ReplaceWith("listOfNotNull(warning)"))
    val warnings: List<Exception>
        get() = listOfNotNull(warning)

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
     * ProcessM compatibility: Initialize with empty sets for all scopes.
     */
    private val _selectStandardAttributes: MutableMap<Scope, LinkedHashSet<Attribute>> =
        EnumMap<Scope, LinkedHashSet<Attribute>>(Scope::class.java).apply {
            Scope.values().forEach { put(it, LinkedHashSet()) }
        }

    /**
     * Internal mutable map for non-standard attributes in SELECT.
     * ProcessM compatibility: Initialize with empty sets for all scopes.
     */
    private val _selectOtherAttributes: MutableMap<Scope, LinkedHashSet<Attribute>> =
        EnumMap<Scope, LinkedHashSet<Attribute>>(Scope::class.java).apply {
            Scope.values().forEach { put(it, LinkedHashSet()) }
        }

    /**
     * Internal mutable map for complex expressions in SELECT.
     * ProcessM compatibility: Initialize with empty lists for all scopes.
     */
    private val _selectExpressions: MutableMap<Scope, ArrayList<IExpression>> =
        EnumMap<Scope, ArrayList<IExpression>>(Scope::class.java).apply {
            Scope.values().forEach { put(it, ArrayList()) }
        }

    /**
     * Internal mutable map for implicit SELECT ALL per scope.
     *
     * ProcessM compatibility: Default to true (empty query = implicit SELECT *)
     * When specific attributes are selected, isImplicitSelectAll getter will return false
     * based on whether _selectStandardAttributes/_selectOtherAttributes/_selectExpressions are populated.
     */
    private val _isImplicitSelectAll: MutableMap<Scope, Boolean> =
        EnumMap<Scope, Boolean>(Scope::class.java).apply {
            Scope.values().forEach { put(it, true) }
        }

    /**
     * Tracks whether select attributes were propagated from GROUP BY (not explicit SELECT).
     * When true, the isImplicitSelectAll getter skips the attribute-based explicit check.
     */
    private var _groupByPropagatedToSelect = false

    /**
     * Whether SELECT attributes came from GROUP BY propagation (not an explicit SELECT clause).
     * When true, the apparent select attributes were auto-populated from GROUP BY, not user-specified.
     */
    val isGroupByPropagatedToSelect: Boolean get() = _groupByPropagatedToSelect

    /**
     * Whether to select all attributes for each scope.
     *
     * - true: SELECT * explicitly specified
     * - false: SELECT with specific attributes
     * - null: Not specified for this scope
     */
    val selectAll: Map<Scope, Boolean?>
        get() = Scope.values().associateWith { scope ->
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
     *
     * ProcessM Rule: If ANY scope has explicit SELECT (attributes, expressions, or SELECT *),
     * then ALL scopes have isImplicitSelectAll = false.
     */
    val isImplicitSelectAll: Map<Scope, Boolean>
        get() {
            // Check if ANY scope has explicit selection (SELECT * or specific attributes)
            // When _groupByPropagatedToSelect is true, attributes were copied from GROUP BY,
            // not from an explicit SELECT clause — skip attribute-based check.
            val hasAnyExplicitSelect = Scope.values().any { scope ->
                _selectAll[scope] != null ||  // Explicit SELECT * or specific attributes
                _selectExpressions[scope]?.isNotEmpty() == true ||
                (!_groupByPropagatedToSelect && (
                    _selectStandardAttributes[scope]?.isNotEmpty() == true ||
                    _selectOtherAttributes[scope]?.isNotEmpty() == true
                ))
            }

            // If any explicit selection exists, all scopes have implicit = false
            if (hasAnyExplicitSelect) {
                return Scope.values().associateWith { false }
            }

            // No explicit selection - check if truly implicit SELECT ALL
            return Scope.values().associateWith { scope ->
                // If we are grouping by this scope (explicit or implicit), implicit select all is disabled
                // Also disabled if any upper scope is grouped (because lower scope attributes would need aggregation)
                var currentScope: Scope? = scope
                while (currentScope != null) {
                    if (isGroupBy[currentScope] == true || isImplicitGroupBy[currentScope] == true) {
                        return@associateWith false
                    }
                    currentScope = currentScope.upper
                }

                // Check explicit flag
                _isImplicitSelectAll[scope] == true
            }
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
     * Expression.empty if no WHERE clause is specified.
     *
     * ProcessM compatibility: Uses Expression.empty instead of null.
     *
     * Example:
     * - WHERE e:name = "A" AND e:timestamp > D2020-01-01
     * - whereExpression = BinaryOp(AND, ...)
     */
    var whereExpression: Expression = Expression.empty

    // ========================================
    // GROUP BY CLAUSE
    // ========================================

    /**
     * Internal mutable map for standard attributes in GROUP BY.
     * ProcessM compatibility: Initialize with empty sets for all scopes.
     */
    private val _groupByStandardAttributes: MutableMap<Scope, LinkedHashSet<Attribute>> =
        EnumMap<Scope, LinkedHashSet<Attribute>>(Scope::class.java).apply {
            Scope.values().forEach { put(it, LinkedHashSet()) }
        }

    /**
     * Internal mutable map for non-standard attributes in GROUP BY.
     * ProcessM compatibility: Initialize with empty sets for all scopes.
     */
    private val _groupByOtherAttributes: MutableMap<Scope, LinkedHashSet<Attribute>> =
        EnumMap<Scope, LinkedHashSet<Attribute>>(Scope::class.java).apply {
            Scope.values().forEach { put(it, LinkedHashSet()) }
        }

    /**
     * Internal mutable map for implicit GROUP BY per scope.
     */
    private val _isImplicitGroupBy: MutableMap<Scope, Boolean> =
        EnumMap<Scope, Boolean>(Scope::class.java).apply {
            Scope.values().forEach { put(it, false) }
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
        get() = Scope.values().associateWith { scope ->
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
        get() = Scope.values().associateWith { scope ->
            // If explicit GROUP BY is present, it's not implicit
            if (isGroupBy[scope] == true) return@associateWith false

            val explicit = _isImplicitGroupBy[scope] == true
            
            // Check for aggregations in SELECT
            val hasAggSelect = selectExpressions[scope]?.any { expr ->
                (expr as? Expression)?.filter { it is com.processm.processminterpreter.pql.model.Function && it.functionType == FunctionType.Aggregation }?.isNotEmpty() == true
            } == true

            // Check for aggregations in ORDER BY
            val hasAggOrder = _orderByExpressions[scope]?.any { orderedExpr ->
                (orderedExpr.base as? Expression)?.filter { it is com.processm.processminterpreter.pql.model.Function && it.functionType == FunctionType.Aggregation }?.isNotEmpty() == true
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
     * ProcessM compatibility: Initialize with empty lists for all scopes.
     */
    private val _orderByExpressions: MutableMap<Scope, ArrayList<OrderedExpression>> =
        EnumMap<Scope, ArrayList<OrderedExpression>>(Scope::class.java).apply {
            Scope.values().forEach { put(it, ArrayList()) }
        }

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
        // Use effectiveScope, defaulting to Event if null
        val scope = attr.effectiveScope ?: Scope.Event
        if (attr.isStandard) {
            val set = _selectStandardAttributes[scope] ?: LinkedHashSet<Attribute>().also { _selectStandardAttributes[scope] = it }
            set.add(attr)
        } else {
            val set = _selectOtherAttributes[scope] ?: LinkedHashSet<Attribute>().also { _selectOtherAttributes[scope] = it }
            set.add(attr)
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
     * ProcessM Rule: When SELECT * is mixed with specific attributes:
     * - Emit a warning (SelectAllConflictsWithReferencingByName)
     * - Clear the specific attribute lists (SELECT * takes precedence)
     *
     * This differs from a strict error approach - ProcessM allows the query
     * to execute but ignores the specific attributes.
     */
    fun validateSelectAll() {
        Scope.values().forEach { scope ->
            val isExplicitSelectAll = _selectAll[scope] == true

            if (isExplicitSelectAll) {
                // Check if specific attributes are also selected for this scope
                val hasStandardAttrs = _selectStandardAttributes[scope]?.isNotEmpty() == true
                val hasOtherAttrs = _selectOtherAttributes[scope]?.isNotEmpty() == true
                val hasExpressions = _selectExpressions[scope]?.isNotEmpty() == true
                val hasSpecificAttributes = hasStandardAttrs || hasOtherAttrs || hasExpressions

                if (hasSpecificAttributes) {
                    // Check if there are aggregation functions - this is an error, not a warning
                    val hasAggregation = _selectExpressions[scope]?.any { expr ->
                        (expr as? Expression)?.filter {
                            it is com.processm.processminterpreter.pql.model.Function &&
                                it.functionType == FunctionType.Aggregation
                        }?.isNotEmpty() == true
                    } == true

                    if (hasAggregation) {
                        // SELECT * with aggregation is not allowed
                        throw PQLSyntaxException(
                            PQLSyntaxException.Problem.ExplicitSelectAllWithImplicitGroupBy,
                            -1, -1, scope.toString()
                        )
                    }

                    // Emit warning (ProcessM behavior: warn but continue)
                    emitWarning(
                        PQLSyntaxException(
                            PQLSyntaxException.Problem.SelectAllConflictsWithReferencingByName,
                            -1, -1, scope.toString()
                        ),
                    )

                    // Clear the specific attributes (ProcessM behavior: SELECT * wins)
                    _selectStandardAttributes[scope]?.clear()
                    _selectOtherAttributes[scope]?.clear()
                    _selectExpressions[scope]?.clear()
                }
            }
        }
    }



    /**
     * Validate that classifiers are used correctly.
     *
     * ProcessM Rules:
     * - Classifiers CAN appear in SELECT and GROUP BY
     * - Classifiers CANNOT appear in WHERE clause
     * - Classifiers CANNOT be used at LOG scope
     *
     * Note: Unlike some strict interpretations, ProcessM allows classifiers
     * in SELECT clause for selecting classifier values.
     *
     * @throws InvalidClassifierUsageException if validation fails
     */
    fun validateClassifiers() {
        // Check WHERE expression - classifiers not allowed
        if (whereExpression != Expression.empty) {
            val classifierAttrs = whereExpression.filter { it is Attribute && it.isClassifier }
            if (classifierAttrs.isNotEmpty()) {
                val attr = classifierAttrs.first() as Attribute
                throw PQLSyntaxException(
                    PQLSyntaxException.Problem.ClassifierInWhere,
                    attr.line,
                    attr.charPositionInLine,
                    attr.name
                )
            }
        }

        // Check for classifiers at LOG scope - not allowed
        val logClassifiers = (selectStandardAttributes[Scope.Log].orEmpty() +
            selectOtherAttributes[Scope.Log].orEmpty() +
            groupByStandardAttributes[Scope.Log].orEmpty() +
            groupByOtherAttributes[Scope.Log].orEmpty())
            .filter { it.isClassifier }

        if (logClassifiers.isNotEmpty()) {
            val attr = logClassifiers.first()
            throw PQLSyntaxException(
                PQLSyntaxException.Problem.ClassifierOnLog,
                attr.line,
                attr.charPositionInLine,
                attr.name
            )
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
        if (whereExpression != Expression.empty) {
            // Check for aggregation functions in WHERE clause
            val aggFunctions = whereExpression.filter {
                it is com.processm.processminterpreter.pql.model.Function &&
                    it.functionType == FunctionType.Aggregation
            }

            if (aggFunctions.isNotEmpty()) {
                val func = aggFunctions.first() as com.processm.processminterpreter.pql.model.Function
                throw PQLSyntaxException(
                    PQLSyntaxException.Problem.AggregationFunctionInWhere,
                    func.line,
                    func.charPositionInLine,
                    func.name
                )
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
        if (whereExpression != Expression.empty) {
            checkExpression(whereExpression)
        }

        // Check GROUP BY attributes
        groupByStandardAttributes.values.forEach { checkAttributes(it) }
        groupByOtherAttributes.values.forEach { checkAttributes(it) }

        // Check ORDER BY expressions
        orderByExpressions.values.flatten().forEach { ordered ->
            checkExpression(ordered.base)
        }
    }

    /**
     * Validate that hoisting is not used in SELECT or ORDER BY clauses,
     * except within aggregation functions.
     *
     * ProcessM Rule:
     * - Hoisting (^ or ^^) is ONLY allowed in:
     *   - WHERE clause
     *   - GROUP BY clause
     *   - INSIDE aggregation functions in SELECT/ORDER BY
     *
     * - Hoisting is FORBIDDEN in:
     *   - SELECT clause (outside of aggregation)
     *   - ORDER BY clause
     *
     * Examples:
     * - SELECT ^e:name → ERROR (hoisting in SELECT)
     * - SELECT count(^e:name) → OK (hoisting inside aggregation)
     * - ORDER BY ^e:name → ERROR (hoisting in ORDER BY)
     * - WHERE ^e:name = 'X' → OK (hoisting in WHERE allowed)
     * - GROUP BY ^e:name → OK (hoisting in GROUP BY allowed)
     *
     * @throws PQLSemanticException if hoisting is found in forbidden location
     */
    fun validateHoistingInSelectAndOrderBy() {
        // Check SELECT attributes (direct hoisting not allowed UNLESS the attribute is in GROUP BY)
        val groupByAttrs = groupByAttributes
        (selectStandardAttributes.values.flatten() + selectOtherAttributes.values.flatten()).forEach { attr ->
            if (attr.hoistingPrefix.isNotEmpty()) {
                // Allow hoisting if the same hoisted attribute is in GROUP BY
                val inGroupBy = groupByAttrs.any { gb ->
                    gb.hoistingPrefix == attr.hoistingPrefix &&
                        gb.name == attr.name &&
                        gb.scope == attr.scope
                }
                if (!inGroupBy) {
                    throw PQLSyntaxException(
                        PQLSyntaxException.Problem.ScopeHoistingInSelectOrOrderBy,
                        attr.line,
                        attr.charPositionInLine,
                        attr.toString()
                    )
                }
            }
        }

        // Check SELECT expressions (hoisting allowed only inside aggregation functions)
        selectExpressions.values.flatten().forEach { expr ->
            validateExpressionHoisting(expr, "SELECT")
        }

        // Check ORDER BY expressions (hoisting allowed inside aggregation functions, same as SELECT)
        orderByExpressions.values.flatten().forEach { ordered ->
            validateExpressionHoisting(ordered.base, "ORDER BY")
        }
    }

    /**
     * Helper method to validate hoisting in expressions.
     * Hoisting is allowed inside aggregation functions, but not elsewhere.
     *
     * @param expr the expression to validate
     * @param clauseName the clause name for error messages
     */
    private fun validateExpressionHoisting(expr: IExpression, clauseName: String) {
        when (expr) {
            is Function -> {
                // If it's an aggregation function, hoisting inside is allowed - skip validation
                if (expr.functionType == FunctionType.Aggregation) {
                    return
                }
                // For scalar functions, check children
                expr.children.forEach { child ->
                    validateExpressionHoisting(child, clauseName)
                }
            }
            is Attribute -> {
                if (expr.hoistingPrefix.isNotEmpty()) {
                    throw PQLSyntaxException(
                        PQLSyntaxException.Problem.ScopeHoistingInSelectOrOrderBy,
                        expr.line,
                        expr.charPositionInLine,
                        expr.toString()
                    )
                }
            }
            is Expression -> {
                expr.children.forEach { child ->
                    validateExpressionHoisting(child, clauseName)
                }
            }
        }
    }

    /**
     * Helper method to validate hoisting strictly - no hoisting allowed at all.
     * Used for ORDER BY where hoisting is never allowed.
     *
     * @param expr the expression to validate
     * @param clauseName the clause name for error messages
     */
    private fun validateExpressionHoistingStrict(expr: IExpression, clauseName: String) {
        when (expr) {
            is Attribute -> {
                if (expr.hoistingPrefix.isNotEmpty()) {
                    throw PQLSyntaxException(
                        PQLSyntaxException.Problem.ScopeHoistingInSelectOrOrderBy,
                        expr.line,
                        expr.charPositionInLine,
                        expr.toString()
                    )
                }
            }
            is Expression -> {
                expr.children.forEach { child ->
                    validateExpressionHoistingStrict(child, clauseName)
                }
            }
        }
    }

    /**
     * Propagate GROUP BY attributes to SELECT when no explicit SELECT clause is present.
     *
     * ProcessM Rule: When GROUP BY is specified without a SELECT clause,
     * the grouped attributes are automatically added to SELECT. Scopes without
     * GROUP BY retain their implicit SELECT ALL behavior.
     */
    private fun propagateGroupByToSelect() {
        // Only propagate if there's no explicit SELECT clause
        val hasExplicitSelect = Scope.values().any { scope ->
            _selectAll[scope] != null ||
                _selectStandardAttributes[scope]?.isNotEmpty() == true ||
                _selectOtherAttributes[scope]?.isNotEmpty() == true ||
                _selectExpressions[scope]?.isNotEmpty() == true
        }
        if (hasExplicitSelect) return

        // Only propagate if there's at least one GROUP BY scope
        val hasGroupBy = Scope.values().any { isGroupBy[it] == true }
        if (!hasGroupBy) return

        // For each scope with GROUP BY, copy attributes to SELECT
        Scope.values().forEach { scope ->
            if (isGroupBy[scope] == true) {
                _groupByStandardAttributes[scope]?.forEach { attr ->
                    _selectStandardAttributes.getOrPut(scope) { LinkedHashSet() }.add(attr)
                }
                _groupByOtherAttributes[scope]?.forEach { attr ->
                    _selectOtherAttributes.getOrPut(scope) { LinkedHashSet() }.add(attr)
                }
            }
        }

        _groupByPropagatedToSelect = true
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
        // ProcessM validation: SELECT scope:* is incompatible with GROUP BY at that scope or above.
        // Must run before validateSelectAll() which would throw a less-specific error.
        for (selectAllScope in _selectAll.filterValues { it == true }.keys) {
            var checkScope: Scope? = selectAllScope
            while (checkScope != null) {
                if (isGroupBy[checkScope] == true) {
                    throw PQLSyntaxException(
                        PQLSyntaxException.Problem.MixedScopes,
                        -1, -1, selectAllScope.toString(), checkScope.toString()
                    )
                }
                checkScope = checkScope.upper
            }
        }
        validateSelectAll()
        validateOrderByWithImplicitGroupBy()
        propagateGroupByToSelect()
        validateGroupBy()
        validateClassifiers()
        validateHoisting()
        validateHoistingInSelectAndOrderBy()
        validateWhereClause()
        validateDeleteConstraints()
    }

    /**
     * Validate ORDER BY with implicit GROUP BY.
     *
     * ProcessM Rule: When ORDER BY contains only aggregation functions and
     * implicit GROUP BY is active, ordering is meaningless (only one row per scope).
     * Clear ORDER BY and emit a warning.
     */
    private fun validateOrderByWithImplicitGroupBy() {
        Scope.values().forEach { scope ->
            // Explicit GROUP BY at this scope → ORDER BY by aggregation is valid, don't clear
            if (isGroupBy[scope] == true) return@forEach

            val orderExprs = _orderByExpressions[scope] ?: return@forEach
            if (orderExprs.isEmpty()) return@forEach

            // Check if ALL order by expressions are aggregations
            val allAggregation = orderExprs.all { ordered ->
                ordered.base is com.processm.processminterpreter.pql.model.Function &&
                    (ordered.base as com.processm.processminterpreter.pql.model.Function).functionType == FunctionType.Aggregation
            }

            if (allAggregation) {
                // All ORDER BY expressions are aggregations → implicit GROUP BY at this scope
                // Record it before clearing (so isImplicitGroupBy remains correct)
                _isImplicitGroupBy[scope] = true
                // Clear ORDER BY — ordering is meaningless with implicit GROUP BY (one row per scope)
                _orderByExpressions[scope]?.clear()
                emitWarning(
                    PQLSyntaxException(
                        PQLSyntaxException.Problem.OrderByClauseRemoved,
                        -1, -1
                    )
                )
            }
        }
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
        // Check if there is any aggregation in the query (global check)
        val hasAnyAggregation = selectExpressions.values.flatten().any { expr ->
            (expr as? Expression)?.filter { it is com.processm.processminterpreter.pql.model.Function && it.functionType == FunctionType.Aggregation }?.isNotEmpty() == true
        } || _orderByExpressions.values.flatten().any { ordered ->
            (ordered.base as? Expression)?.filter { it is com.processm.processminterpreter.pql.model.Function && it.functionType == FunctionType.Aggregation }?.isNotEmpty() == true
        }

        // Check: SELECT * with aggregation is not allowed at aggregation scope
        // ProcessM allows l:*, t:* with event aggregation (upper scopes are constant per group)
        // Only reject SELECT ALL at the same or lower scope as the aggregation
        if (hasAnyAggregation) {
            // Find the lowest scope that has aggregation functions
            val aggScopes = _selectExpressions.filter { (_, exprs) ->
                exprs.any { it is Function && Function.isAggregation(it.name) }
            }.keys
            val lowestAggScope = aggScopes.maxByOrNull { it.ordinal }

            if (lowestAggScope != null) {
                Scope.values().forEach { scope ->
                    if (_selectAll[scope] == true && scope.ordinal >= lowestAggScope.ordinal) {
                        throw PQLSyntaxException(
                            PQLSyntaxException.Problem.ExplicitSelectAllWithImplicitGroupBy,
                            -1, -1, scope.toString()
                        )
                    }
                }
            }
        }

        // Iterate over all scopes
        Scope.values().forEach { scope ->
            val isGrouped = isGroupBy[scope] == true

            // Check if there is any aggregation in the query
            val hasAggregation = hasAnyAggregation

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

                // Check ORDER BY expressions
                _orderByExpressions[scope]?.forEach { ordered ->
                    validateExpressionInGroupBy(ordered.base)
                }
            }
        }

        // Also check: if ANY scope has GROUP BY, ORDER BY attributes in that scope must be validated
        Scope.values().forEach { scope ->
            if (isGroupBy[scope] == true) {
                _orderByExpressions[scope]?.forEach { ordered ->
                    validateExpressionInGroupBy(ordered.base)
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
        
        // Check 2: Lower scope is grouped
        // ProcessM allows upper-scope attributes when a lower scope has GROUP BY.
        // e.g., "select t:name, sum(e:total) group by e:name" is valid because
        // trace is a parent scope of event — each grouped row inherits the trace context.
        val lowerScopes = Scope.values().filter { it.ordinal > scope.ordinal }
        if (lowerScopes.any { isGroupBy[it] == true }) {
            return
        }

        // Check 2b: Implicit per-trace grouping
        // ProcessM implicitly groups by trace for event-level aggregation without explicit GROUP BY.
        // Upper-scope attributes (trace, log) are always valid because each trace has a single value.
        // e.g., "select t:name, count(e:name)" is valid — t:name is constant per trace.
        val hasAnyExplicitGroupBy = isGroupBy.values.any { it == true }
        if (!hasAnyExplicitGroupBy && scope.ordinal < Scope.Event.ordinal) {
            // No explicit GROUP BY and attribute is at trace or log scope → implicit grouping key
            return
        }

        // Check 3: Hoisted version is grouped (going UP)
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

        // Check 4: De-hoisted GROUP BY covers this attribute (single-level hoisting only)
        // Example: select e:name ... group by ^e:name
        // ^e:name in GROUP BY at Trace scope has effectiveScope=Trace but declared scope=Event
        // This covers e:name (same declared scope + name) in SELECT
        // Only ^ (one level) is allowed — ^^ or more means the grouping is too far up
        val allGroupByAttrs = groupByStandardAttributes.values.flatten() + groupByOtherAttributes.values.flatten()
        for (gbAttr in allGroupByAttrs) {
            if (gbAttr.hoistingPrefix == "^" &&
                gbAttr.scope == attr.scope &&
                gbAttr.name == attr.name) {
                return
            }
        }
        
        val shortName = "${attr.scope.shortName}:${attr.name}"
        throw PQLSyntaxException(
            attr.line,
            attr.charPositionInLine,
            "$shortName must be present in GROUP BY clause"
        )
    }

    private fun validateExpressionInGroupBy(expr: IExpression) {
        if (expr is com.processm.processminterpreter.pql.model.Function && expr.functionType == FunctionType.Aggregation) {
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

    /**
     * Apply limits to the query.
     *
     * ProcessM compatibility: Original signature with nullable Long parameters.
     * Sets limits only if parameter is not null.
     *
     * @param log limit for LOG scope (or null to skip)
     * @param trace limit for TRACE scope (or null to skip)
     * @param event limit for EVENT scope (or null to skip)
     */
    fun applyLimits(log: Long?, trace: Long?, event: Long?) {
        log?.let {
            val currentLimit = _limit[Scope.Log]
            if (currentLimit == null || currentLimit > it) {
                _limit[Scope.Log] = it
            }
        }
        trace?.let {
            val currentLimit = _limit[Scope.Trace]
            if (currentLimit == null || currentLimit > it) {
                _limit[Scope.Trace] = it
            }
        }
        event?.let {
            val currentLimit = _limit[Scope.Event]
            if (currentLimit == null || currentLimit > it) {
                _limit[Scope.Event] = it
            }
        }
    }

    // ========================================
    // INIT BLOCK (must be last to ensure all properties are initialized)
    // ========================================

    init {
        if (_shouldParse) {
            parseAndBuild(query)
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
 *
 * ProcessM compatibility: Uses 'base' property name instead of 'expression'.
 */
data class OrderedExpression(
    val base: IExpression,
    val direction: OrderDirection,
) : IExpression by base {
    override fun toString(): String = "$base $direction"
}
