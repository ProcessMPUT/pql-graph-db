package com.processm.processminterpreter.pql.model

/**
 * Abstract base class for all PQL expressions.
 *
 * Provides common functionality for expression tree traversal, filtering,
 * and scope management.
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
abstract class Expression(
    override val line: Int = -1,
    override val charPositionInLine: Int = -1,
    vararg childExpressions: IExpression,
) : IExpression {

    override val children: List<IExpression> = childExpressions.toList()

    /**
     * Most expressions don't have a scope (only Attributes and Functions do).
     * Override in subclasses if needed.
     */
    override val scope: Scope?
        get() = null

    /**
     * Default type is UNKNOWN - override in subclasses.
     */
    override val type: Type
        get() = Type.UNKNOWN

    /**
     * The effective scope of this expression - the lowest (most specific) scope
     * in the expression tree.
     *
     * Scope hierarchy: LOG (0) > TRACE (1) > EVENT (2)
     * Lower ordinal = higher in hierarchy, so we want the MIN ordinal (lowest in hierarchy).
     *
     * Examples:
     * - Expression with only EVENT attributes → EVENT
     * - Expression mixing TRACE and EVENT → EVENT (lower in hierarchy)
     * - Expression with no scoped children → EVENT (default)
     * - Attribute with TRACE scope → TRACE (uses own scope because it's terminal)
     * - Function with EVENT scope and TRACE arguments → TRACE (from arguments)
     */
    open val effectiveScope: Scope by lazy {
        // If this expression is terminal (no children) and has its own scope, use it
        // This is important for Attribute which has a scope but no children
        if (isTerminal && scope != null) {
            return@lazy scope!!
        }

        // Otherwise, compute from children
        val childScopes = children.mapNotNull { child ->
            when (child) {
                is Expression -> child.effectiveScope
                else -> child.scope
            }
        }

        // Find minimum scope (lowest in hierarchy = highest ordinal)
        childScopes.maxByOrNull { it.ordinal } ?: Scope.EVENT
    }

    /**
     * Expected types for children (for validation).
     * Override in subclasses to specify type constraints.
     */
    open val expectedChildrenTypes: List<Type>
        get() = emptyList()

    /**
     * Recursively filter expressions in the tree.
     *
     * Returns all expressions (including this one and all descendants) that match
     * the predicate. A child expression may be selected even if its parent doesn't match.
     *
     * @param predicate the filter condition
     * @return list of matching expressions
     */
    fun filter(predicate: (IExpression) -> Boolean): List<IExpression> {
        val result = mutableListOf<IExpression>()

        // Check this expression
        if (predicate(this)) {
            result.add(this)
        }

        // Recursively check children
        children.forEach { child ->
            if (child is Expression) {
                result.addAll(child.filter(predicate))
            } else if (predicate(child)) {
                result.add(child)
            }
        }

        return result
    }

    /**
     * Recursively filter expressions, but only traverse into matching expressions.
     *
     * More restrictive than filter() - only recurses into children if the
     * parent matches the predicate.
     *
     * @param predicate the filter condition
     * @return list of matching expressions
     */
    fun filterRecursively(predicate: (IExpression) -> Boolean): List<IExpression> {
        val result = mutableListOf<IExpression>()

        if (predicate(this)) {
            result.add(this)

            // Only recurse if this expression matches
            children.forEach { child ->
                if (child is Expression) {
                    result.addAll(child.filterRecursively(predicate))
                } else if (predicate(child)) {
                    result.add(child)
                }
            }
        }

        return result
    }

    companion object {
        /**
         * Empty expression singleton.
         */
        val empty = object : Expression() {}
    }
}
