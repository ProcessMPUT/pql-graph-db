package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.common.HierarchicalLimits
import com.processm.processminterpreter.domain.pql.common.HierarchicalOffsets

/**
 * Top-level PQL AST. Sealed: the two valid top-level shapes are SELECT and DELETE.
 * Produced by the parser (AntlrPqlParser / AstBuilder).
 */
sealed interface RawQuery {
    val location: SourceLocation
    val from: Scope

    data class Select(
        override val from: Scope,
        val columns: List<RawSelectColumn>,
        val implicitAll: Boolean = false,
        val where: RawExpression? = null,
        val groupBy: List<RawExpression> = emptyList(),
        val orderBy: List<RawOrderKey> = emptyList(),
        val limit: HierarchicalLimits = HierarchicalLimits(),
        val offset: HierarchicalOffsets = HierarchicalOffsets(),
        override val location: SourceLocation,
    ) : RawQuery

    data class Delete(
        override val from: Scope,
        val where: RawExpression? = null,
        override val location: SourceLocation,
    ) : RawQuery
}
