package com.processm.processminterpreter.pql.common

/**
 * Per-scope limit / offset for the LOG / TRACE / EVENT hierarchy.
 *
 * Identical shape across syntax, resolved and plan layers — kept in one place to avoid
 * three near-duplicate types and rewrap boilerplate in the resolver.
 */
data class HierarchicalLimits(
    val log: Long? = null,
    val trace: Long? = null,
    val event: Long? = null,
)

data class HierarchicalOffsets(
    val log: Long? = null,
    val trace: Long? = null,
    val event: Long? = null,
)
