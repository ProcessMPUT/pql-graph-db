package com.processm.processminterpreter.domain.pql.resolved

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.common.HierarchicalLimits
import com.processm.processminterpreter.domain.pql.common.HierarchicalOffsets
import com.processm.processminterpreter.domain.pql.common.OrderKey

data class ResolvedSelectColumn(
    val expression: ResolvedExpression?,
    val alias: String? = null,
    val starAt: Scope? = null,
)

/**
 * Query after the Resolver phase: names classified, types inferred, hoisting materialized.
 * Not yet guaranteed semantically valid — see [ValidatedQuery].
 */
sealed interface ResolvedQuery {
    val location: SourceLocation
    val from: Scope

    data class Select(
        override val from: Scope,
        val columns: List<ResolvedSelectColumn>,
        val implicitAll: Boolean = false,
        val where: ResolvedExpression? = null,
        val groupBy: List<ResolvedExpression> = emptyList(),
        val orderBy: List<OrderKey> = emptyList(),
        val limit: HierarchicalLimits = HierarchicalLimits(),
        val offset: HierarchicalOffsets = HierarchicalOffsets(),
        override val location: SourceLocation,
    ) : ResolvedQuery

    data class Delete(
        override val from: Scope,
        val where: ResolvedExpression? = null,
        override val location: SourceLocation,
    ) : ResolvedQuery
}
