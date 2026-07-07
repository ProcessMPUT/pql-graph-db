package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchema
import org.springframework.stereotype.Component

/**
 * An addressable Neo4j property: a `nodeVar.property` reference, possibly requiring
 * backtick escaping (property names containing `:`, as used for XES standard attributes
 * stored via `SET node += attributes`).
 */
data class PropertyRef(
    val nodeVar: String,
    val property: String,
    val requiresBackticks: Boolean,
) {
    // Property names are interpolated as Cypher identifiers, not parameters —
    // an embedded backtick must be doubled or it closes the identifier and the
    // remainder of the name is parsed as Cypher (injection via attribute names
    // or classifier keys, which come from log data).
    fun toCypher(): String =
        if (requiresBackticks) "$nodeVar.`${property.replace("`", "``")}`" else "$nodeVar.$property"
}

/**
 * Maps a [PqlExpression.Attribute] (logical layer) to a [PropertyRef] in our Neo4j schema.
 *
 * This mapper encapsulates every physical detail of how XES attributes are stored:
 *  - explicit columns (`event.activity`, `event.timestamp`, `event.resource`),
 *  - hybrid columns written via `SET node += attributes` that keep XES colons
 *    (`trace.`cost:total``, `event.`cost:currency``),
 *  - pure custom attributes stored with their raw PQL name.
 *  - system attributes (`l:logId`) exposed by the query model.
 *
 * Pure data, no Neo4j session access. Safe to unit-test against any plan.
 *
 * Cross-reference: MEMORY.md §"Neo4j Property Naming (2026-02-08)".
 */
@Component
class PhysicalAttributeMapper {

    fun map(attr: PqlExpression.Attribute, nodeVar: String): PropertyRef {
        val propertyName = when (attr.kind) {
            AttributeKind.STANDARD -> neo4jPropertyFor(attr.effectiveScope, attr.xesStandardName ?: attr.name)
            AttributeKind.CUSTOM -> attr.name
            AttributeKind.CLASSIFIER -> attr.name
            AttributeKind.SYSTEM -> attr.name
        }
        return PropertyRef(
            nodeVar = nodeVar,
            property = propertyName,
            requiresBackticks = requiresBackticks(propertyName),
        )
    }

    /**
     * Looks up the Neo4j property name for a canonical XES attribute at the given scope.
     * Falls back to the XES name itself when unmapped (custom standard attribute stored
     * verbatim via `SET += attributes`).
     */
    private fun neo4jPropertyFor(scope: Scope, canonicalXesName: String): String =
        Neo4jXesSchema.physicalName(scope, canonicalXesName)

    private fun requiresBackticks(propertyName: String): Boolean =
        !Regex("[A-Za-z_][A-Za-z0-9_]*").matches(propertyName)
}
