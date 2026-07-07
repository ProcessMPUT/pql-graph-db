package com.processm.processminterpreter.xes.io

import com.processm.processminterpreter.xes.model.XesAttributeValue
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
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

    fun parseAttributes(element: Element): Map<String, Any> {
        val attributes = mutableMapOf<String, Any>()
        val childNodes = element.childNodes

        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                parseAttributeElement(node as Element)?.let { (key, value) ->
                    attributes[key] = value
                }
            }
        }

        return attributes
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

    private fun parseAttributeElement(element: Element): Pair<String, Any>? {
        val key = element.getAttribute("key")
        if (key.isEmpty()) return null

        val value = element.getAttribute("value")
        val parsedValue = when (element.tagName) {
            "string" -> key to value
            "date" -> key to (parseInstant(value) ?: value)
            "int" -> key to parseInt(key, value)
            "float" -> key to parseFloat(key, value)
            "boolean" -> key to value.toBoolean()
            "id" -> key to (asUuid(value) ?: value)
            "list" -> key to parseList(element)
            else -> null
        } ?: return null

        if (element.tagName == "list") return parsedValue

        val children = parseChildAttributes(element)
        return if (children.isEmpty()) {
            parsedValue
        } else {
            parsedValue.first to XesAttributeValue(parsedValue.second, children)
        }
    }

    private fun parseChildAttributes(element: Element): Map<String, Any> {
        val attributes = mutableMapOf<String, Any>()
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            parseAttributeElement(node as Element)?.let { (key, value) ->
                attributes[key] = value
            }
        }
        return attributes
    }

    private fun parseList(element: Element): Map<String, Any> {
        val attributes = mutableMapOf<String, Any>()
        val values = mutableListOf<Map<String, Any>>()

        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue

            val child = node as Element
            if (child.tagName == "values") {
                val valueNodes = child.childNodes
                for (j in 0 until valueNodes.length) {
                    val valueNode = valueNodes.item(j)
                    if (valueNode.nodeType != Node.ELEMENT_NODE) continue
                    parseAttributeElement(valueNode as Element)?.let { (key, value) ->
                        values += mapOf(key to value)
                    }
                }
            } else {
                parseAttributeElement(child)?.let { (key, value) ->
                    attributes[key] = value
                }
            }
        }

        return mapOf("attributes" to attributes, "values" to values)
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
