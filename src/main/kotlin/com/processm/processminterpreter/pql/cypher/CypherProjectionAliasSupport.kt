package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.plan.ProjectedColumn
import com.processm.processminterpreter.pql.ast.PqlExpression

internal fun CypherBuildState.registerProjectedColumnAlias(column: ProjectedColumn) {
    registerColumnAlias(
        alias = column.alias,
        pqlExpression = PqlExpressionText.of(column.expression),
        scope = column.scope,
        materializeNull = materializeProjectedNull(column.expression),
    )
}

internal fun materializeProjectedNull(expression: PqlExpression): Boolean =
    expression !is PqlExpression.Attribute

internal fun samePqlAttribute(
    left: PqlExpression.Attribute,
    right: PqlExpression.Attribute,
): Boolean =
    left.baseScope == right.baseScope &&
        (left.xesStandardName ?: left.name) == (right.xesStandardName ?: right.name)

internal fun cypherMapKey(ref: PropertyRef): String =
    // Backticks doubled for the same reason as PropertyRef.toCypher: an embedded
    // backtick in an attribute/classifier name must not close the identifier.
    if (ref.requiresBackticks) "`${ref.property.replace("`", "``")}`" else ref.property

internal fun baseEventAttributeText(attribute: PqlExpression.Attribute): String =
    "${Scope.EVENT.fullName}:${attribute.xesStandardName ?: attribute.name}"
