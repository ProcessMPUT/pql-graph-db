package com.processm.processminterpreter.neo4j.property

import com.processm.processminterpreter.xes.model.XesAttributeValue
import com.processm.processminterpreter.neo4j.property.NestedAttributePathCodec
import com.processm.processminterpreter.neo4j.xes.metadata.XesLogMetadataCodec
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Normalizes arbitrary XES attribute values to property values accepted by Neo4j.
 */
object Neo4jPropertySanitizer {
    fun sanitizeAttributes(attributes: Map<String, Any?>): Map<String, Any> =
        sanitize(attributes, includeNulls = false) { it.replace(".", "_") }
            .mapValuesTo(linkedMapOf()) { checkNotNull(it.value) }

    fun sanitizeCustomAttributes(attributes: Map<String, Any?>): Map<String, Any?> =
        sanitize(attributes, includeNulls = true) { it }

    private fun sanitize(
        attributes: Map<String, Any?>,
        includeNulls: Boolean,
        keyTransform: (String) -> String,
    ): Map<String, Any?> {
        val sanitized = linkedMapOf<String, Any?>()
        for ((rawKey, value) in attributes) {
            val key = keyTransform(rawKey)
            if (value == null) {
                if (includeNulls) sanitized[key] = null
                continue
            }
            sanitized.putSanitizedAttribute(key, value)
        }
        return sanitized
    }

    private fun MutableMap<String, Any?>.putSanitizedAttribute(key: String, value: Any) {
        put(key, sanitizeValue(value))
        if (value is XesAttributeValue) {
            for ((childKey, childValue) in value.children) {
                if (childValue == null) continue
                put(
                    NestedAttributePathCodec.encodedChildKey(key, childKey),
                    sanitizeValue(unwrapScalar(childValue)),
                )
            }
        }
    }

    private fun unwrapScalar(value: Any): Any =
        if (value is XesAttributeValue) value.value ?: "" else value

    private fun sanitizeValue(value: Any): Any =
        when (value) {
            is Instant -> value.toNeo4jDateTime()
            is XesAttributeValue, is Map<*, *>, is Collection<*> -> XesLogMetadataCodec.serializeArbitrary(value)
            else -> value
        }

    private fun Instant.toNeo4jDateTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
