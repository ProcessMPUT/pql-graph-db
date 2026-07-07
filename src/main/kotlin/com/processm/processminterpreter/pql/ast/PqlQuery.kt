package com.processm.processminterpreter.pql.ast

import com.processm.processminterpreter.pql.catalog.OrderDirection
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.common.HierarchicalOffsets

sealed interface PqlQuery {
    val location: SourceLocation
    val from: Scope

    data class Select(
        override val from: Scope,
        val columns: List<SelectColumn>,
        val implicitAll: Boolean = false,
        val where: PqlExpression? = null,
        val groupBy: List<PqlExpression> = emptyList(),
        val orderBy: List<OrderKey> = emptyList(),
        val limit: HierarchicalLimits = HierarchicalLimits(),
        val offset: HierarchicalOffsets = HierarchicalOffsets(),
        override val location: SourceLocation,
    ) : PqlQuery

    data class Delete(
        override val from: Scope,
        val where: PqlExpression? = null,
        override val location: SourceLocation,
    ) : PqlQuery

    data class SelectColumn(
        val expression: PqlExpression?,
        val alias: String? = null,
        val starAt: Scope? = null,
    )

    data class OrderKey(
        val expression: PqlExpression,
        val direction: OrderDirection,
    )
}
