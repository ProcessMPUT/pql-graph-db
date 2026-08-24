package com.processm.processminterpreter.neo4j.property

import com.processm.processminterpreter.xes.model.XesAttributeValue
import com.processm.processminterpreter.neo4j.property.NestedAttributePathCodec
import com.processm.processminterpreter.neo4j.xes.metadata.XesLogMetadataCodec
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchema
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

    /**
     * Stores nested log parents as their queryable scalar and moves the complete
     * tree into one cold payload. ProcessM JSON can then read the hot properties
     * without transferring nested children, while full XES hydration merges the
     * payload back into the parent values.
     */
    fun sanitizeAttributesWithNestedPayload(attributes: Map<String, Any?>): Map<String, Any> =
        sanitizeWithColdNestedPayload(attributes, includeNulls = false) { it.replace(".", "_") }
            .mapValuesTo(linkedMapOf()) { checkNotNull(it.value) }

    fun sanitizeCustomAttributes(attributes: Map<String, Any?>): Map<String, Any?> =
        sanitize(attributes, includeNulls = true) { it }

    fun sanitizeCustomAttributesWithNestedPayload(attributes: Map<String, Any?>): Map<String, Any?> =
        sanitizeWithColdNestedPayload(attributes, includeNulls = true) { it }

    private fun sanitizeWithColdNestedPayload(
        attributes: Map<String, Any?>,
        includeNulls: Boolean,
        keyTransform: (String) -> String,
    ): Map<String, Any?> {
        val sanitized = linkedMapOf<String, Any?>()
        val nestedPayload = linkedMapOf<String, XesAttributeValue>()
        for ((rawKey, value) in attributes) {
            val key = keyTransform(rawKey)
            if (value == null) {
                if (includeNulls) sanitized[key] = null
                continue
            }
            if (value is XesAttributeValue) {
                value.value?.let { sanitized[key] = sanitizeValue(it) }
                for ((childKey, childValue) in value.children) {
                    if (childValue == null) continue
                    sanitized[NestedAttributePathCodec.encodedChildKey(key, childKey)] =
                        sanitizeValue(unwrapScalar(childValue))
                }
                nestedPayload[key] = value
            } else {
                sanitized.putSanitizedAttribute(key, value)
            }
        }
        if (nestedPayload.isNotEmpty()) {
            sanitized[Neo4jXesSchema.NESTED_ATTRIBUTE_PAYLOAD_PROPERTY] =
                XesLogMetadataCodec.serializeArbitrary(nestedPayload)
        }
        return sanitized
    }

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
