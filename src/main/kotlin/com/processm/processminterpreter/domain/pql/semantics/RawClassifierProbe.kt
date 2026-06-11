package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.domain.pql.syntax.RawAttributeRef
import com.processm.processminterpreter.domain.pql.syntax.RawBinaryOp
import com.processm.processminterpreter.domain.pql.syntax.RawExpression
import com.processm.processminterpreter.domain.pql.syntax.RawFunctionCall
import com.processm.processminterpreter.domain.pql.syntax.RawInList
import com.processm.processminterpreter.domain.pql.syntax.RawQuery
import com.processm.processminterpreter.domain.pql.syntax.RawUnaryOp

/**
 * Detects classifier references (`c:*` / `classifier:*`) directly on the raw AST.
 *
 * Lives next to the resolver because deciding whether a query uses classifiers must
 * happen *before* resolution — classifier resolution depends on the per-log classifier
 * catalog, which we don't want to load when the query doesn't reference any.
 *
 * Uses the canonical predicate from [StandardAttributeCatalog.isClassifier] to keep
 * the prefix taxonomy in one place.
 */
object RawClassifierProbe {

    /** True iff any SELECT query expression references a classifier. */
    fun containsClassifier(query: RawQuery.Select): Boolean =
        query.columns.any { it.expression?.let { e -> containsClassifier(e) } == true } ||
            query.where?.let { containsClassifier(it) } == true ||
            query.groupBy.any { containsClassifier(it) } ||
            query.orderBy.any { containsClassifier(it.expression) }

    /** True iff [expr] or any sub-expression references a classifier. */
    fun containsClassifier(expr: RawExpression): Boolean = when (expr) {
        is RawAttributeRef -> StandardAttributeCatalog.isClassifier(expr.name)
        is RawBinaryOp -> containsClassifier(expr.left) || containsClassifier(expr.right)
        is RawUnaryOp -> containsClassifier(expr.operand)
        is RawFunctionCall -> expr.arguments.any { containsClassifier(it) }
        is RawInList -> expr.values.any { containsClassifier(it) }
        else -> false
    }
}
