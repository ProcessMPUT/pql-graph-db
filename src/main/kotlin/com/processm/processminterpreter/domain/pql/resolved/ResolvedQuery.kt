package com.processm.processminterpreter.domain.pql.resolved

import com.processm.processminterpreter.domain.pql.syntax.OrderDirection
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation

data class ResolvedSelectColumn(
    val expression: ResolvedExpression?,
    val alias: String? = null,
    val starAt: Scope? = null,
)

data class ResolvedOrderKey(
    val expression: ResolvedExpression,
    val direction: OrderDirection,
)

data class LimitSpec(val log: Long? = null, val trace: Long? = null, val event: Long? = null)
data class OffsetSpec(val log: Long? = null, val trace: Long? = null, val event: Long? = null)

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
        val orderBy: List<ResolvedOrderKey> = emptyList(),
        val limit: LimitSpec = LimitSpec(),
        val offset: OffsetSpec = OffsetSpec(),
        override val location: SourceLocation,
    ) : ResolvedQuery

    data class Delete(
        override val from: Scope,
        val where: ResolvedExpression? = null,
        override val location: SourceLocation,
    ) : ResolvedQuery
}
