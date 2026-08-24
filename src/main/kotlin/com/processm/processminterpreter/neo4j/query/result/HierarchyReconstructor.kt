package com.processm.processminterpreter.neo4j.query.result

import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.common.HierarchicalOffsets
import com.processm.processminterpreter.pql.cypher.ColumnAlias
import org.springframework.stereotype.Component

/**
 * Rebuilds flat Neo4j query rows into XES hierarchy.
 *
 * The class is intentionally only an orchestrator:
 *  - node-shaped rows are handled by [NodeRowHierarchyBuilder],
 *  - projected scalar rows are handled by [ProjectedRowHierarchyBuilder],
 *  - hierarchical limit/offset is handled by [HierarchicalWindowing].
 */
@Component
class HierarchyReconstructor {
    private val projectedRows = ProjectedRowHierarchyBuilder()
    private val nodeRows = NodeRowHierarchyBuilder()
    private val windowing = HierarchicalWindowing

    fun reconstruct(
        rows: List<Map<String, Any?>>,
        columnAliases: Map<String, ColumnAlias>,
        limits: HierarchicalLimits = HierarchicalLimits(),
        offsets: HierarchicalOffsets = HierarchicalOffsets(),
        defaultLimits: HierarchicalLimits = HierarchicalLimits(),
        selectAllScopes: Set<Scope> = emptySet(),
    ): List<XesLog> {
        if (rows.isEmpty()) return emptyList()

        val logs = if (rows.first().isNodeShape() && columnAliases.isEmpty()) {
            nodeRows.reconstruct(rows, selectAllScopes)
        } else {
            projectedRows.reconstruct(rows, columnAliases, selectAllScopes)
        }

        return windowing.apply(
            logs = logs,
            limits = limits,
            offsets = offsets,
            defaultLimits = defaultLimits,
        )
    }

    private fun Map<String, Any?>.isNodeShape(): Boolean =
        this["log"] is Map<*, *> ||
            this["log"] is List<*> ||
            this["trace"] is Map<*, *> ||
            this["trace"] is List<*> ||
            this["event"] is Map<*, *> ||
            this["event"] is List<*>
}
