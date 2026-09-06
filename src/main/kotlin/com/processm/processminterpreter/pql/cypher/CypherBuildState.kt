package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.plan.LogicalPlan
import com.processm.processminterpreter.pql.XesAttributeReadMode
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Mutable accumulator shared by all `CypherCodegen.emit*` passes. Keeping the state
 * external (rather than in the codegen itself) makes each emit step a pure function
 * of (plan, state) and keeps the codegen thread-safe per-invocation.
 */
internal class CypherBuildState(
    val plan: LogicalPlan.Select,
    val attributeReadMode: XesAttributeReadMode = XesAttributeReadMode.FULL_XES,
) {
    val facts: SelectPlanFacts = SelectPlanFacts(plan)

    val cypher: StringBuilder = StringBuilder()
    private val parameters: MutableMap<String, Any?> = linkedMapOf()
    private val columnAliases: MutableMap<String, ColumnAlias> = linkedMapOf()

    private val complexAggregationAliasByExpr: MutableMap<String, String> = linkedMapOf()
    private val groupByAliasByExpr: MutableMap<String, String> = linkedMapOf()
    private val orderAliasByExpr: MutableMap<String, String> = linkedMapOf()

    fun expressionAlias(exprKey: String): String? =
        complexAggregationAliasByExpr[exprKey] ?: groupByAliasByExpr[exprKey]

    fun registerGroupByAlias(exprKey: String, alias: String) {
        groupByAliasByExpr[exprKey] = alias
    }

    fun complexAggregationAlias(exprKey: String): String? =
        complexAggregationAliasByExpr[exprKey]

    fun registerComplexAggregationAlias(exprKey: String, alias: String) {
        complexAggregationAliasByExpr[exprKey] = alias
    }

    fun <T> withExpressionAliases(aliases: Map<String, String>, block: () -> T): T {
        val previous = complexAggregationAliasByExpr.toMap()
        complexAggregationAliasByExpr.putAll(aliases)
        return try {
            block()
        } finally {
            complexAggregationAliasByExpr.clear()
            complexAggregationAliasByExpr.putAll(previous)
        }
    }

    fun orderAlias(exprKey: String): String? =
        orderAliasByExpr[exprKey]

    fun registerOrderAlias(exprKey: String, alias: String) {
        orderAliasByExpr[exprKey] = alias
    }

    fun registerColumnAlias(
        alias: String,
        pqlExpression: String,
        scope: Scope,
        synthetic: Boolean = false,
        materializeNull: Boolean = false,
    ) {
        columnAliases[alias] = ColumnAlias(
            pqlExpression = pqlExpression,
            scope = scope,
            synthetic = synthetic,
            materializeNull = materializeNull,
        )
    }

    fun registerSyntheticColumnAlias(
        alias: String,
        scope: Scope,
        pqlExpression: String = alias,
    ) {
        registerColumnAlias(
            alias = alias,
            pqlExpression = pqlExpression,
            scope = scope,
            synthetic = true,
        )
    }

    fun hasColumnAlias(alias: String): Boolean =
        alias in columnAliases

    /**
     * While true, expression rendering skips the group-by / cagg alias substitution and
     * always emits the physical property reference. Required because Cypher aggregate
     * *arguments* must reference the underlying column directly — referring to a WITH
     * alias defined in the same clause produces a scoping error.
     */
    private var renderingAggregationArgument: Boolean = false

    val canUseExpressionAliases: Boolean
        get() = !renderingAggregationArgument

    fun <T> withAggregationArgumentRendering(block: () -> T): T {
        val previous = renderingAggregationArgument
        renderingAggregationArgument = true
        return try {
            block()
        } finally {
            renderingAggregationArgument = previous
        }
    }

    private var attributeNodeVariables: Map<Pair<Scope, Scope>, String> = emptyMap()

    fun attributeNodeVariable(baseScope: Scope, effectiveScope: Scope): String? =
        attributeNodeVariables[baseScope to effectiveScope]

    fun <T> withAttributeNodeVariables(variables: Map<Pair<Scope, Scope>, String>, block: () -> T): T {
        val previous = attributeNodeVariables
        attributeNodeVariables = variables
        return try {
            block()
        } finally {
            attributeNodeVariables = previous
        }
    }

    /**
     * When set, hoisted-event attribute references render against this alias instead
     * of the default `event` node variable. Used to implement WHERE hoisting via an
     * `EXISTS { MATCH (trace)-[:HAS_EVENT]->(_hev:Event) WHERE ... }` subquery: the
     * outer MATCH doesn't bind `event`, so the subquery introduces `_hev` and we
     * redirect hoisted refs to it.
     */
    private var hoistedEventNodeVarOverride: String? = null

    val hoistedEventNodeVar: String?
        get() = hoistedEventNodeVarOverride

    fun <T> withHoistedEventNodeVar(nodeVar: String, block: () -> T): T {
        val previous = hoistedEventNodeVarOverride
        hoistedEventNodeVarOverride = nodeVar
        return try {
            block()
        } finally {
            hoistedEventNodeVarOverride = previous
        }
    }

    private var nextParamId: Int = 0
    private var nextCaggId: Int = 0
    private var queryNowParamName: String? = null
    private var hydrateLogProperties: Boolean = false
    private var pendingOptionalEventMatch: Boolean = false

    /**
     * Marks the query as returning `log.logId AS _logKey` instead of the log node,
     * so the executor hydrates `properties(log)` once per distinct log afterwards.
     */
    fun deferLogProperties() {
        hydrateLogProperties = true
    }

    /**
     * ProcessM JSON reads only top-level values. Returning key/value pairs lets
     * Neo4j omit nested helper and cold-payload properties before Bolt transfer;
     * full XES keeps the normal node-properties map.
     */
    fun xesProperties(nodeVar: String): String =
        if (attributeReadMode == XesAttributeReadMode.PROCESSM_JSON) {
            "[key IN keys($nodeVar) " +
                "WHERE NOT key STARTS WITH '\u001f' AND NOT key STARTS WITH 'processm' " +
                "| {key: key, value: $nodeVar[key]}]"
        } else {
            "properties($nodeVar)"
        }

    fun logProperties(nodeVar: String = "log"): String = xesProperties(nodeVar)

    /** See [CypherMatchEmitter.emit] — the event expansion must wait for the WHERE clause. */
    fun markPendingOptionalEventMatch() {
        pendingOptionalEventMatch = true
    }

    /** Returns whether an optional event match is pending, clearing the flag. */
    fun consumePendingOptionalEventMatch(): Boolean {
        val pending = pendingOptionalEventMatch
        pendingOptionalEventMatch = false
        return pending
    }

    /** Drops a pending optional event match for queries that cannot observe events. */
    fun discardPendingOptionalEventMatch() {
        pendingOptionalEventMatch = false
    }

    /** Bind a value to a fresh `$paramN` placeholder; returns the placeholder name (e.g. `param0`). */
    fun bindParam(value: Any?): String {
        val name = "param${nextParamId++}"
        parameters[name] = value
        return name
    }

    fun bindNamedParam(name: String, value: Any?) {
        parameters[name] = value
    }

    fun bindHierarchyLimitParams(
        logLimit: Long?,
        traceLimit: Long?,
        eventLimit: Long?,
    ) {
        logLimit?.let { bindNamedParam("logLimit", it) }
        traceLimit?.let { bindNamedParam("traceLimit", it) }
        eventLimit?.let { bindNamedParam("eventLimit", it) }
    }

    fun queryNowParam(): String {
        queryNowParamName?.let { return it }
        return bindParam(ZonedDateTime.now(ZoneOffset.UTC)).also { queryNowParamName = it }
    }

    fun nextCaggId(): Int = nextCaggId++

    fun finish(): CypherQuery = CypherQuery(
        cypher = cypher.toString().trim(),
        parameters = parameters.toMap(),
        columnAliases = columnAliases.toMap(),
        hydrateLogProperties = hydrateLogProperties,
        attributeReadMode = attributeReadMode,
    )
}
