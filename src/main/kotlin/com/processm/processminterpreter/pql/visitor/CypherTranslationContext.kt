package com.processm.processminterpreter.pql.visitor

import com.processm.processminterpreter.pql.ColumnAlias
import com.processm.processminterpreter.pql.StandardAttributeMapper.Scope

/**
 * Shared mutable state for Cypher query translation.
 *
 * Passed to all translator components (FunctionTranslator, WhereClauseTranslator, etc.)
 * so they can access and modify shared state without tight coupling to QLToCypherVisitor.
 */
class CypherTranslationContext(
    val logId: String? = null,
    val defaultTraceLimit: Int? = null,
    val classifiers: Map<String, List<String>> = emptyMap(),
) {
    val parameters = mutableMapOf<String, Any>()
    var paramCounter = 0

    var currentScope: Scope = Scope.Event
    val scopeStack = mutableListOf<Scope>()

    val usedScopes = mutableSetOf<Scope>()
    val columnAliases = mutableMapOf<String, ColumnAlias>()

    /** When non-null, hoisted attributes use this node variable instead of standard scope label. */
    var hoistedEventNodeVar: String? = null

    fun nextParamName(): String = "param${paramCounter++}"

    fun clear() {
        usedScopes.clear()
        parameters.clear()
        paramCounter = 0
        columnAliases.clear()
        hoistedEventNodeVar = null
    }

    fun scopeToNodeLabel(scope: Scope): String =
        when (scope) {
            Scope.Log -> "log"
            Scope.Trace -> "trace"
            Scope.Event -> "event"
        }
}
