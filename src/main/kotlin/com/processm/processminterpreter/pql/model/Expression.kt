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
     * needed to evaluate the expression.
     *
     * Scope hierarchy: LOG (0) > TRACE (1) > EVENT (2)
     * Lower ordinal = higher in hierarchy (more encompassing).
     * Higher ordinal = lower in hierarchy (more specific).
     *
     * ProcessM Rule: The effective scope is the LOWEST scope (highest ordinal) among
     * all scoped children. Literals and literal-only expressions are ignored in
     * parent calculations but return EVENT as their own effectiveScope.
     *
     * Examples:
     * - Expression with only EVENT attributes → EVENT (ordinal 2)
     * - Expression with ^^e:timestamp (hoisted to LOG) → LOG (ordinal 0)
     *   (because ^^e:timestamp's effectiveScope is already LOG due to hoisting)
     * - Expression mixing TRACE and EVENT → EVENT (ordinal 2, lowest in hierarchy)
     * - Literal → EVENT (but ignored when computing parent scope)
     * - Attribute with effectiveScope LOG (due to hoisting) → LOG
     */
    override val effectiveScope: Scope? by lazy {
        // If this expression is terminal (no children), use its own scope or default to EVENT
        // For Attribute, this is overridden to compute hoisting
        // For Literal, scope is null → defaults to EVENT
        if (isTerminal) {
            return@lazy scope ?: Scope.Event
        }

        // Otherwise, compute from children - find the LOWEST scope (highest ordinal)
        // among explicitly scoped children. Unscoped literals are ignored.
        val childScopes = children.mapNotNull { child ->
            when (child) {
                is Literal<*> -> child.scope  // Only include scoped literals (e.g., l:1), ignore unscoped
                is Expression -> {
                    // Check if this child only contains unscoped literals (scope-neutral)
                    val scope = child.effectiveScope
                    if (scope == Scope.Event && child.hasOnlyUnscopedLiterals()) null else scope
                }
                else -> child.effectiveScope ?: child.scope
            }
        }

        // Find lowest scope (highest ordinal) among scoped children
        // If no children have explicit scope, default to EVENT
        childScopes.maxByOrNull { it.ordinal } ?: Scope.Event
    }

    /**
     * Check if this expression contains only unscoped literals (no attributes or scoped elements).
     * Used to determine if the expression is scope-neutral.
     * Scoped literals like l:1 are NOT considered unscoped.
     */
    private fun hasOnlyUnscopedLiterals(): Boolean {
        if (this is Literal<*>) return this.scope == null
        if (children.isEmpty()) return false
        return children.all { child ->
            when (child) {
                is Literal<*> -> child.scope == null
                is Expression -> child.hasOnlyUnscopedLiterals()
                else -> false
            }
        }
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
