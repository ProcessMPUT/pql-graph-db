package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.log.Classifier

/**
 * Everything the [Resolver] needs that is external to the [RawQuery] itself.
 * Kept explicit so the resolver stays pure — no hidden Spring/DB lookups.
 *
 *  - [logId] scopes the query to a specific log (optional; null ⇒ any).
 *  - [classifiers] are needed to validate `c:*`/`classifier:*` references against
 *    the log's declared classifiers.
 */
data class ResolutionContext(
    val logId: String? = null,
    val classifiers: List<Classifier> = emptyList(),
    val ambiguousClassifierNames: Set<String> = emptySet(),
)
