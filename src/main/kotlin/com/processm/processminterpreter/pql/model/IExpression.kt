package com.processm.processminterpreter.pql.model

/**
 * Base interface for all PQL expressions.
 *
 * An expression can be:
 * - Attribute (e.g., "e:name", "^t:timestamp")
 * - Literal (e.g., "hello", 42, true, D2020-01-01)
 * - Function (e.g., "count(e:id)", "year(e:timestamp)")
 * - Operator (e.g., "a + b", "a > b", "a AND b")
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
interface IExpression {
    /**
     * Line number in the source query where this expression appears.
     * Used for error reporting.
     */
    val line: Int

    /**
     * Character position in the line where this expression appears.
     * Used for error reporting.
     */
    val charPositionInLine: Int

    /**
     * The scope of this expression (LOG, TRACE, EVENT), or null if not applicable.
     *
     * - Attributes have a scope (e.g., "e:name" has EVENT scope)
     * - Functions can have an optional scope (e.g., "t:count(e:id)" has TRACE scope)
     * - Literals and operators don't have a scope (null)
     */
    val scope: Scope?

    /**
     * The effective scope of this expression - the lowest (most specific) scope
     * in the expression tree, considering all children.
     *
     * - For terminal expressions (attributes, literals), returns scope or EVENT as default
     * - For composite expressions, returns the lowest scope among all children
     * - Scope hierarchy: LOG (0) > TRACE (1) > EVENT (2)
     */
    val effectiveScope: Scope?
        get() = scope

    /**
     * The data type of this expression.
     *
     * Examples:
     * - "hello" → Type.STRING
     * - 42 → Type.NUMBER
     * - e:timestamp → Type.DATETIME
     * - count(e:id) → Type.NUMBER
     */
    val type: Type

    /**
     * Child expressions (operands for operators, arguments for functions).
     * Empty list for terminal expressions (attributes, literals).
     */
    val children: List<IExpression>

    /**
     * Whether this is a terminal expression (no children).
     * Terminal expressions: attributes, literals.
     * Non-terminal expressions: functions, operators.
     */
    val isTerminal: Boolean
        get() = children.isEmpty()
}
