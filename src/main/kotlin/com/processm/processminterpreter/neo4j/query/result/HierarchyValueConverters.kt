package com.processm.processminterpreter.neo4j.query.result

import com.processm.processminterpreter.xes.model.XesAttributeValue
import com.processm.processminterpreter.neo4j.xes.metadata.XesLogMetadataCodec
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

internal fun asUuid(value: Any?): UUID? = when (value) {
    is UUID -> value
    is String -> runCatching { UUID.fromString(value) }.getOrNull()
    else -> null
}

internal fun asDouble(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}

internal fun asInstant(value: Any?): Instant? =
    temporalValueAsInstant(value) ?: (value as? String)?.let(::parseInstant)

internal fun normalize(value: Any?): Any? =
    temporalValueAsInstant(value)
        ?: when (value) {
            is String -> decodeNestedAttribute(value) ?: value
            else -> value
        }

private fun temporalValueAsInstant(value: Any?): Instant? = when (value) {
    is Instant -> value
    is ZonedDateTime -> value.toInstant()
    is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
    is OffsetDateTime -> value.toInstant()
    else -> null
}

private fun parseInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()

private fun decodeNestedAttribute(value: String): XesAttributeValue? {
    if (!value.contains("\"__type\"") || !value.contains("\"xes-attribute\"")) return null
    return runCatching { XesLogMetadataCodec.deserializeArbitrary(value) as? XesAttributeValue }.getOrNull()
}
