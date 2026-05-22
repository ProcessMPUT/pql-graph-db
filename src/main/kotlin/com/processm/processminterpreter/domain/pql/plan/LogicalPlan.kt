package com.processm.processminterpreter.domain.pql.plan

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression

/**
 * Backend-agnostic logical execution plan.
 *
 * Knows nothing about Cypher or Neo4j — translation to a concrete backend query is the
 * responsibility of an adapter (e.g. `CypherCodegen` in the Neo4j adapter). Other backends
 * would implement their own codegen off the same plan.
 */
sealed interface LogicalPlan {
    val source: LogicalSource
    val location: SourceLocation

    data class Select(
        override val source: LogicalSource,
        val projection: Projection,
        val materializedScopes: Set<Scope> = emptySet(),
        val filter: ResolvedExpression? = null,
        val groupBy: GroupBySpec? = null,
        val orderBy: List<OrderKey> = emptyList(),
        val limits: HierarchicalLimits = HierarchicalLimits(),
        val offsets: HierarchicalOffsets = HierarchicalOffsets(),
        val defaultLimits: HierarchicalLimits = HierarchicalLimits(),
        override val location: SourceLocation,
    ) : LogicalPlan

    data class Delete(
        override val source: LogicalSource,
        val filter: ResolvedExpression? = null,
        val target: Scope,
        override val location: SourceLocation,
    ) : LogicalPlan
}
