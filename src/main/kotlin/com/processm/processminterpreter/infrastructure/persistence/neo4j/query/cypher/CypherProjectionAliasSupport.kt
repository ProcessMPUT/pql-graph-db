package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.plan.ProjectedColumn
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression

internal fun CypherBuildState.registerProjectedColumnAlias(column: ProjectedColumn) {
    registerColumnAlias(
        alias = column.alias,
        pqlExpression = PqlExpressionText.of(column.expression),
        scope = column.scope,
        materializeNull = materializeProjectedNull(column.expression),
    )
}

internal fun materializeProjectedNull(expression: ResolvedExpression): Boolean =
    expression !is ResolvedAttribute

internal fun samePqlAttribute(
    left: ResolvedAttribute,
    right: ResolvedAttribute,
): Boolean =
    left.baseScope == right.baseScope &&
        (left.xesStandardName ?: left.name) == (right.xesStandardName ?: right.name)

internal fun cypherMapKey(ref: PropertyRef): String =
    if (ref.requiresBackticks) "`${ref.property}`" else ref.property

internal fun baseEventAttributeText(attribute: ResolvedAttribute): String =
    "${Scope.EVENT.fullName}:${attribute.xesStandardName ?: attribute.name}"
