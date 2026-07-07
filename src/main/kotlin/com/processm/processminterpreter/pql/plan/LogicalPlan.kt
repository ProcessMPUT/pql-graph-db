package com.processm.processminterpreter.pql.plan

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.common.HierarchicalOffsets
import com.processm.processminterpreter.pql.ast.PqlQuery
import com.processm.processminterpreter.pql.ast.PqlExpression

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
        val filter: PqlExpression? = null,
        val groupBy: GroupBySpec? = null,
        val orderBy: List<PqlQuery.OrderKey> = emptyList(),
        val limits: HierarchicalLimits = HierarchicalLimits(),
        val offsets: HierarchicalOffsets = HierarchicalOffsets(),
        val defaultLimits: HierarchicalLimits = HierarchicalLimits(),
        override val location: SourceLocation,
    ) : LogicalPlan

    data class Delete(
        override val source: LogicalSource,
        val filter: PqlExpression? = null,
        val target: Scope,
        override val location: SourceLocation,
    ) : LogicalPlan
}
