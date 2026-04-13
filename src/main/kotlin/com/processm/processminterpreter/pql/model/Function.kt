package com.processm.processminterpreter.pql.model

/**
 * Type of function (scalar or aggregation).
 */
enum class FunctionType {
    /**
     * Scalar functions operate on individual values and return a single value.
     * Examples: year(e:timestamp), upper(e:name), round(e:cost)
     */
    Scalar,

    /**
     * Aggregation functions combine multiple values into a single result.
     * Examples: count(e:id), sum(e:cost), avg(e:duration), min(e:timestamp), max(e:timestamp)
     */
    Aggregation,
}

/**
 * Represents a function call in a PQL query.
 *
 * Functions can be:
 * - Scalar: operate on individual values (year, month, upper, lower, etc.)
 * - Aggregation: combine multiple values (count, sum, avg, min, max)
 *
 * Functions can optionally have a scope prefix:
 * - "count(e:id)" - no scope prefix
 * - "t:count(e:id)" - TRACE scope prefix
 *
 * Examples:
 * - count(e:id) → count events
 * - sum(e:cost_total) → sum of costs
 * - year(e:timestamp) → extract year from timestamp
 * - upper(e:activity) → convert activity to uppercase
 * - t:count(e:id) → count events per trace
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
class Function(
    functionName: String,
    override val line: Int = -1,
    override val charPositionInLine: Int = -1,
    vararg args: IExpression,
) : Expression(line, charPositionInLine, *args) {
    // Regex to parse: [scope:]name
    // Groups: (scope:) (name)
    private val regex = Regex("^(?:(\\w+):)?(\\w+)$")
    private val match =
        regex.find(functionName)
            ?: throw PQLSyntaxException(
                line,
                charPositionInLine,
                "Invalid function name: $functionName",
            )

    /**
     * The function name (lowercase).
     * Examples: "count", "sum", "year", "upper"
     */
    val name: String = match.groupValues[2].lowercase()

    /**
     * Optional scope prefix.
     * Examples:
     * - "count(e:id)" → null
     * - "t:count(e:id)" → TRACE
     * - "l:max(e:timestamp)" → LOG
     */
    override val scope: Scope? =
        match.groupValues[1]
            .takeIf { it.isNotEmpty() }
            ?.let { Scope.parse(it) }

    /**
     * Function type (SCALAR or AGGREGATION).
     */
    val functionType: FunctionType =
        when (name) {
            in SCALAR_FUNCTIONS.keys -> FunctionType.Scalar
            in AGGREGATION_FUNCTIONS.keys -> FunctionType.Aggregation
            else -> throw InvalidFunctionException("Unknown function: $name")
        }

    /**
     * Whether this function is an aggregation function.
     */
    val isAggregation: Boolean
        get() = functionType == FunctionType.Aggregation

    /**
     * Return type of this function.
     */
    override val type: Type
        get() =
            when (functionType) {
                FunctionType.Scalar -> SCALAR_FUNCTIONS[name] ?: Type.UNKNOWN
                FunctionType.Aggregation -> AGGREGATION_FUNCTIONS[name] ?: Type.ANY
            }

    init {
        // Validate number of arguments
        val expectedArgs =
            FUNCTION_ARGS[name]
                ?: throw InvalidFunctionException("Unknown function: $name")

        if (children.size != expectedArgs) {
            throw InvalidFunctionException(
                "Function '$name' expects $expectedArgs argument(s), got ${children.size}",
            )
        }

        // Validate scope for scalar functions
        // Rule: scope of scalar function must NOT be LOWER than effective scope of arguments
        // Hierarchy: LOG (ordinal 0) > TRACE (ordinal 1) > EVENT (ordinal 2)
        // Lower ordinal = higher in hierarchy
        //
        // Valid examples:
        // - TRACE function (1) with EVENT arg (2): 1 < 2 ✓ (function higher in hierarchy)
        // - EVENT function (2) with EVENT arg (2): 2 == 2 ✓ (same level)
        //
        // Invalid examples:
        // - EVENT function (2) with TRACE arg (1): 2 > 1 ✗ (function lower in hierarchy)
        if (functionType == FunctionType.Scalar && scope != null) {
            val effScope = effectiveScope
            if (effScope != null && scope.ordinal > effScope.ordinal) {
                throw InvalidFunctionException(
                    "Scope of scalar function '$name' ($scope) cannot be lower in hierarchy than " +
                        "effective scope of its arguments ($effectiveScope)",
                )
            }
        }
    }

    override fun toString(): String {
        val scopePrefix = scope?.let { "$it:" } ?: ""
        val argsStr = children.joinToString(", ")
        return "$scopePrefix$name($argsStr)"
    }

    companion object {
        /**
         * Scalar functions: name → return type.
         *
         * Date/time extraction functions:
         * - year, month, day, hour, minute, second, millisecond, quarter, dayofweek
         * - date, time
         * - now (current timestamp)
         *
         * String functions:
         * - upper, lower
         *
         * Numeric functions:
         * - round
         */
        val SCALAR_FUNCTIONS =
            mapOf(
                // Date/time extraction (return NUMBER)
                "year" to Type.NUMBER,
                "month" to Type.NUMBER,
                "day" to Type.NUMBER,
                "hour" to Type.NUMBER,
                "minute" to Type.NUMBER,
                "second" to Type.NUMBER,
                "millisecond" to Type.NUMBER,
                "quarter" to Type.NUMBER,
                "dayofweek" to Type.NUMBER,
                // Date/time construction (return DATETIME)
                "date" to Type.DATETIME,
                "time" to Type.DATETIME,
                "now" to Type.DATETIME,
                // String functions (return STRING)
                "upper" to Type.STRING,
                "lower" to Type.STRING,
                // Numeric functions (return NUMBER)
                "round" to Type.NUMBER,
            )

        /**
         * Aggregation functions: name → return type.
         *
         * - count → NUMBER (always)
         * - sum, avg → NUMBER (for numeric inputs)
         * - min, max → ANY (depends on input type)
         */
        val AGGREGATION_FUNCTIONS =
            mapOf(
                "count" to Type.NUMBER,
                "sum" to Type.NUMBER,
                "avg" to Type.NUMBER,
                "min" to Type.ANY, // Can return any type depending on input
                "max" to Type.ANY, // Can return any type depending on input
            )

        /**
         * Expected number of arguments for each function.
         */
        val FUNCTION_ARGS =
            mapOf(
                // Date/time extraction (1 argument)
                "year" to 1,
                "month" to 1,
                "day" to 1,
                "hour" to 1,
                "minute" to 1,
                "second" to 1,
                "millisecond" to 1,
                "quarter" to 1,
                "dayofweek" to 1,
                "date" to 1,
                "time" to 1,
                // Current time (0 arguments)
                "now" to 0,
                // String functions (1 argument)
                "upper" to 1,
                "lower" to 1,
                // Numeric functions (1 argument)
                "round" to 1,
                // Aggregation functions (1 argument)
                "count" to 1,
                "sum" to 1,
                "avg" to 1,
                "min" to 1,
                "max" to 1,
            )

        /**
         * Check if a function name is a scalar function.
         */
        fun isScalar(name: String): Boolean = SCALAR_FUNCTIONS.containsKey(name.lowercase())

        /**
         * Check if a function name is an aggregation function.
         */
        fun isAggregation(name: String): Boolean = AGGREGATION_FUNCTIONS.containsKey(name.lowercase())
    }
}
