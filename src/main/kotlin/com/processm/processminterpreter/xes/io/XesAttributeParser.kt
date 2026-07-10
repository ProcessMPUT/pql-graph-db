package com.processm.processminterpreter.xes.io

import com.processm.processminterpreter.xes.model.XesAttributeValue
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

internal class XesAttributeParser {
    private val logger = LoggerFactory.getLogger(XesAttributeParser::class.java)

    private val offsetFormatters =
        listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"),
        )

    private val localFormatters =
        listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        )

    /**
     * Converts one typed XES attribute value from its XML text form. Returns
     * null for tags that are not scalar attribute tags (`list` has its own
     * structural handling in the parser).
     */
    fun scalarValue(tag: String, key: String, value: String): Any? =
        when (tag) {
            "string" -> value
            "date" -> parseInstant(value) ?: value
            "int" -> parseInt(key, value)
            "float" -> parseFloat(key, value)
            "boolean" -> value.toBoolean()
            "id" -> asUuid(value) ?: value
            else -> null
        }

    fun extractUuid(attributes: MutableMap<String, Any>, key: String): UUID? {
        val raw = attributes[key] ?: return null
        val parsed = asUuid(unwrap(raw)) ?: return null
        attributes.remove(key)
        return parsed
    }

    fun toInstant(value: Any?): Instant? =
        when (val scalar = unwrap(value)) {
            is Instant -> scalar
            is LocalDateTime -> scalar.toInstant(ZoneOffset.UTC)
            is String -> parseTimestamp(scalar)?.toInstant(ZoneOffset.UTC)
            else -> null
        }

    fun asDouble(value: Any?): Double? =
        when (val scalar = unwrap(value)) {
            is Number -> scalar.toDouble()
            is String -> scalar.toDoubleOrNull()
            else -> null
        }

    private fun parseInt(key: String, value: String): Any =
        try {
            val longValue = value.toLong()
            if (longValue in Int.MIN_VALUE..Int.MAX_VALUE.toLong()) longValue.toInt() else longValue
        } catch (e: NumberFormatException) {
            logger.warn("Failed to parse int value: $value for key: $key")
            value
        }

    private fun parseFloat(key: String, value: String): Any =
        try {
            java.text.NumberFormat
                .getInstance(Locale.ROOT)
                .parse(value)
                .toDouble()
        } catch (e: Exception) {
            logger.warn("Failed to parse float value: $value for key: $key")
            value
        }

    private fun parseInstant(dateStr: String): Instant? {
        if (dateStr.isBlank()) return null
        for (formatter in offsetFormatters) {
            try {
                return OffsetDateTime.parse(dateStr, formatter).toInstant()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    private fun parseTimestamp(timestampStr: String?): LocalDateTime? {
        if (timestampStr.isNullOrBlank()) return null

        for (formatter in offsetFormatters) {
            try {
                val odt = OffsetDateTime.parse(timestampStr, formatter)
                return odt.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
            } catch (_: DateTimeParseException) {
            }
        }

        for (formatter in localFormatters) {
            try {
                return LocalDateTime.parse(timestampStr, formatter)
            } catch (_: DateTimeParseException) {
            }
        }

        logger.warn("Failed to parse timestamp: $timestampStr")
        return null
    }

    private fun asUuid(value: Any?): UUID? =
        when (val scalar = unwrap(value)) {
            is UUID -> scalar
            is String -> runCatching { UUID.fromString(scalar) }.getOrNull()
            else -> null
        }

    private fun unwrap(value: Any?): Any? =
        if (value is XesAttributeValue) value.value else value
}
