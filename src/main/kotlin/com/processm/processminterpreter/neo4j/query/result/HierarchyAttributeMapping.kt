package com.processm.processminterpreter.neo4j.query.result

import com.processm.processminterpreter.neo4j.property.NestedAttributePathCodec
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.cypher.ColumnAlias
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesCustomAttributeCodec
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchema
import com.processm.processminterpreter.neo4j.xes.metadata.XesLogMetadataCodec

internal data class ProjectedAttributeValue(
    val xesName: String?,
    val customKey: String,
    val value: Any?,
)

internal data class NodeAttributeValue(
    val physicalName: String,
    val xesName: String?,
    val customKey: String?,
    val value: Any,
)

internal fun Map<String, Any?>.projectedAttributes(
    aliases: Map<String, ColumnAlias>,
): Sequence<ProjectedAttributeValue> =
    sequence {
        for ((col, alias) in aliases) {
            if (!containsKey(col)) continue
            val value = this@projectedAttributes[col]
            if (value == null && !alias.materializeNull) continue

            val xesName = xesNameOf(alias.pqlExpression)
            yield(
                ProjectedAttributeValue(
                    xesName = xesName,
                    customKey = xesName ?: customAttributeKey(col, alias),
                    value = value,
                ),
            )
        }
    }

internal fun Map<String, Any?>.nodeAttributes(
    scope: Scope,
): Sequence<NodeAttributeValue> =
    sequence {
        for ((physical, value) in this@nodeAttributes) {
            if (value == null) continue
            if (NestedAttributePathCodec.isEncoded(physical)) continue
            if (Neo4jXesSchema.isStorageMetadata(scope, physical)) continue
            val customXesName = Neo4jXesCustomAttributeCodec.xesName(scope, physical)
            yield(
                NodeAttributeValue(
                    physicalName = physical,
                    xesName = if (customXesName == null) {
                        Neo4jXesSchema.inversePhysicalName(scope, physical)
                    } else {
                        null
                    },
                    customKey = customXesName,
                    value = value,
                ),
            )
        }
    }

internal fun Map<String, Any?>.nodeProperties(column: String): Map<String, Any?> =
    when (val value = this[column]) {
        is Map<*, *> -> value.stringKeyMap()
        is List<*> -> value.attributePairsMap()
        else -> emptyMap()
    }.withNestedPayload()

internal fun Map<String, Any?>.nodePropertyList(column: String): List<Map<String, Any?>>? =
    (this[column] as? List<*>)?.mapNotNull { value ->
        val properties = when (value) {
            is Map<*, *> -> value.stringKeyMap()
            is List<*> -> value.attributePairsMap()
            else -> null
        } ?: return@mapNotNull null
        properties.withNestedPayload()
    }

internal fun Map<String, Any?>.hasNodeColumn(column: String): Boolean =
    this[column] is Map<*, *> || this[column] is List<*>

private fun xesNameOf(pqlExpression: String): String? {
    val stripped = stripScopePrefix(pqlExpression) ?: return null
    if (stripped.contains('(') || stripped.contains(' ')) return null
    if (stripped.isProjectedLiteralName()) return null
    return stripped
}

private fun customAttributeKey(col: String, alias: ColumnAlias): String {
    val pql = alias.pqlExpression
    if (pql.isBlank() || pql.startsWith("_")) return col
    val stripped = stripScopePrefix(pql) ?: return pql
    return if (isSimpleProjectedAttribute(stripped)) stripped else pql
}

private fun Map<*, *>.stringKeyMap(): Map<String, Any?> =
    entries.mapNotNull { (key, value) ->
        (key as? String)?.let { it to value }
    }.toMap()

private fun List<*>.attributePairsMap(): Map<String, Any?> =
    mapNotNull { pair ->
        val pairMap = (pair as? Map<*, *>)?.stringKeyMap() ?: return@mapNotNull null
        val key = pairMap["key"] as? String ?: return@mapNotNull null
        key to pairMap["value"]
    }.toMap()

private fun Map<String, Any?>.withNestedPayload(): Map<String, Any?> {
    val payload = this[Neo4jXesSchema.NESTED_ATTRIBUTE_PAYLOAD_PROPERTY] as? String ?: return this
    val decoded = runCatching { XesLogMetadataCodec.deserializeArbitrary(payload) as? Map<*, *> }
        .getOrNull()
        ?.stringKeyMap()
        ?: return this
    return this + decoded
}

private fun stripScopePrefix(pql: String): String? {
    val idx = pql.indexOf(':')
    if (idx <= 0) return null
    val prefix = pql.substring(0, idx)
    if (prefix !in SCOPE_PREFIXES) return null
    return pql.substring(idx + 1)
}

private val SCOPE_PREFIXES: Set<String> = setOf("l", "t", "e", "log", "trace", "event")

private fun isSimpleProjectedAttribute(value: String): Boolean =
    !value.isProjectedLiteralName() &&
        !value.contains('(') &&
        !PROJECTED_EXPRESSION_OPERATOR.containsMatchIn(value)

private fun String.isProjectedLiteralName(): Boolean =
    this == "null" ||
        this == "true" ||
        this == "false" ||
        startsWith("D") && length > 1 && this[1].isDigit() ||
        matches(NUMBER_LITERAL_NAME)

private val NUMBER_LITERAL_NAME = Regex("""-?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][+-]?\d+)?""")
private val PROJECTED_EXPRESSION_OPERATOR = Regex("""\s(?:\+|-|\*|/|%|=|<>|!=|<|>|<=|>=)\s""")
