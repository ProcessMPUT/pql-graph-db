package com.processm.processminterpreter.xes

import com.processm.processminterpreter.model.EventNode
import com.processm.processminterpreter.model.LogNode
import com.processm.processminterpreter.model.TraceNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory

@Component
class XESParser {
    private val logger = LoggerFactory.getLogger(XESParser::class.java)

    // XES date format patterns
    // Formatters with timezone offset (convert to UTC for ProcessM compatibility)
    private val offsetFormatters =
        listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"),
        )

    // Formatters without timezone (stored as-is)
    private val localFormatters =
        listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        )

    /**
     * Parse XES file from InputStream
     */
    fun parseXES(
        inputStream: InputStream,
        logId: String? = null,
    ): XESLog {
        logger.info("Starting XES parsing for logId: $logId")

        try {
            val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val document: Document = documentBuilder.parse(inputStream)
            document.documentElement.normalize()

            val logElement = document.documentElement
            if (logElement.tagName != "log") {
                throw XESParseException("Root element must be 'log', found: ${logElement.tagName}")
            }

            return parseLog(logElement, logId)
        } catch (e: Exception) {
            logger.error("Error parsing XES file", e)
            throw XESParseException("Failed to parse XES file: ${e.message}", e)
        }
    }

    /**
     * Parse log element
     */
    private fun parseLog(
        logElement: Element,
        logId: String?,
    ): XESLog {
        val attributes = parseAttributes(logElement).toMutableMap()
        ensureIdentityId(attributes) // Auto-generate identity:id if missing

        // Parse classifier elements
        val classifiers = parseClassifiers(logElement)

        // Parse global attributes (default values per scope)
        val traceGlobals = mutableMapOf<String, Any>()
        val eventGlobals = mutableMapOf<String, Any>()
        parseGlobals(logElement, traceGlobals, eventGlobals)

        // Parse extensions
        val extensions = parseExtensions(logElement)

        val finalLogId = logId ?: generateLogId()
        val logName = attributes["concept:name"] as? String ?: "Unnamed Log"

        val logNode =
            LogNode(
                logId = finalLogId,
                name = logName,
                attributes = attributes,
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now(),
            )

        val traces = mutableListOf<XESTrace>()
        val traceElements = logElement.getElementsByTagName("trace")

        for (i in 0 until traceElements.length) {
            val traceElement = traceElements.item(i) as Element
            val trace = parseTrace(traceElement, finalLogId, i)
            traces.add(trace)
        }

        logger.info("Parsed XES log with ${traces.size} traces, ${classifiers.size} classifiers, ${extensions.size} extensions")
        logger.debug("Global trace attrs: $traceGlobals, Global event attrs: $eventGlobals")
        return XESLog(logNode, traces, classifiers, traceGlobals, eventGlobals, extensions)
    }

    /**
     * Parse classifier elements from a log element.
     * XES classifiers define named attribute combinations, e.g.:
     *   <classifier name="Event Name" keys="concept:name"/>
     *   <classifier name="concept:name+lifecycle:transition" keys="concept:name lifecycle:transition"/>
     */
    private fun parseClassifiers(logElement: Element): Map<String, List<String>> {
        val classifiers = mutableMapOf<String, List<String>>()
        val childNodes = logElement.childNodes

        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                if (element.tagName == "classifier") {
                    val name = element.getAttribute("name")
                    val keys = element.getAttribute("keys")
                    if (name.isNotEmpty() && keys.isNotEmpty()) {
                        classifiers[name] = keys.trim().split("\\s+".toRegex())
                        logger.debug("Parsed classifier: $name → ${classifiers[name]}")
                    }
                }
            }
        }

        return classifiers
    }

    /**
     * Parse global attributes from log element.
     * XES globals define default attribute values per scope:
     *   <global scope="trace"><string key="concept:name" value="__INVALID__"/></global>
     *   <global scope="event"><string key="concept:name" value="__INVALID__"/></global>
     */
    private fun parseGlobals(
        logElement: Element,
        traceGlobals: MutableMap<String, Any>,
        eventGlobals: MutableMap<String, Any>,
    ) {
        val childNodes = logElement.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                if (element.tagName == "global") {
                    val scope = element.getAttribute("scope")
                    val attrs = parseAttributes(element)
                    when (scope) {
                        "trace" -> traceGlobals.putAll(attrs)
                        "event" -> eventGlobals.putAll(attrs)
                    }
                }
            }
        }
    }

    /**
     * Parse extension elements from log element.
     * XES extensions declare attribute namespaces:
     *   <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
     */
    private fun parseExtensions(logElement: Element): List<XESExtension> {
        val extensions = mutableListOf<XESExtension>()
        val childNodes = logElement.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                if (element.tagName == "extension") {
                    val name = element.getAttribute("name")
                    val prefix = element.getAttribute("prefix")
                    val uri = element.getAttribute("uri")
                    if (name.isNotEmpty()) {
                        extensions.add(XESExtension(name, prefix, uri))
                    }
                }
            }
        }
        return extensions
    }

    /**
     * Parse trace element
     */
    private fun parseTrace(
        traceElement: Element,
        logId: String,
        index: Int,
    ): XESTrace {
        val attributes = parseAttributes(traceElement)
        logger.debug("Parsed trace attributes: {}", attributes)

        val caseId = attributes["concept:name"] as? String ?: "Case_$index"
        val traceId = generateTraceId(logId, caseId)

        val traceNode =
            TraceNode(
                traceId = traceId,
                caseId = caseId,
                attributes = attributes,
                createdAt = LocalDateTime.now(),
            )

        val events = mutableListOf<XESEvent>()
        val eventElements = traceElement.getElementsByTagName("event")

        for (i in 0 until eventElements.length) {
            val eventElement = eventElements.item(i) as Element
            val event = parseEvent(eventElement, traceId, i)
            events.add(event)
        }

        return XESTrace(traceNode, events)
    }

    /**
     * Parse event element
     */
    private fun parseEvent(
        eventElement: Element,
        traceId: String,
        index: Int,
    ): XESEvent {
        val attributes = parseAttributes(eventElement)

        val activity = attributes["concept:name"] as? String ?: "Unknown Activity"
        val timestampStr = attributes["time:timestamp"] as? String
        val timestamp = parseTimestamp(timestampStr) ?: LocalDateTime.now()
        val resource = attributes["org:resource"] as? String
        val lifecycle = attributes["lifecycle:transition"] as? String
        val cost = (attributes["cost:total"] as? Number)?.toDouble()

        val eventId = generateEventId(traceId, index)

        val eventNode =
            EventNode(
                eventId = eventId,
                activity = activity,
                timestamp = timestamp,
                resource = resource,
                lifecycle = lifecycle,
                cost = cost,
                attributes = attributes,
                createdAt = LocalDateTime.now(),
            )

        return XESEvent(eventNode)
    }

    /**
     * Parse attributes from element
     */
    private fun parseAttributes(element: Element): Map<String, Any> {
        val attributes = mutableMapOf<String, Any>()
        val childNodes = element.childNodes

        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val childElement = node as Element
                val tagName = childElement.tagName

                when (tagName) {
                    "string" -> {
                        val key = childElement.getAttribute("key")
                        val value = childElement.getAttribute("value")
                        if (key.isNotEmpty()) {
                            attributes[key] = value
                        }
                    }

                    "date" -> {
                        val key = childElement.getAttribute("key")
                        val value = childElement.getAttribute("value")
                        if (key.isNotEmpty()) {
                            // Convert offset-aware dates to UTC string (ProcessM compatibility)
                            // e.g. "2006-11-07T10:00:36+01:00" → "2006-11-07T09:00:36Z"
                            attributes[key] = normalizeDateToUTC(value) ?: value
                        }
                    }

                    "int" -> {
                        val key = childElement.getAttribute("key")
                        val value = childElement.getAttribute("value")
                        if (key.isNotEmpty()) {
                            try {
                                // Use toLong() for compatibility with large values (ProcessM uses BIGINT),
                                // but keep as Int when value fits (most XES files use small ints)
                                val longVal = value.toLong()
                                attributes[key] = if (longVal in Int.MIN_VALUE..Int.MAX_VALUE.toLong()) longVal.toInt() else longVal
                            } catch (e: NumberFormatException) {
                                logger.warn("Failed to parse int value: $value for key: $key")
                                attributes[key] = value
                            }
                        }
                    }

                    "float" -> {
                        val key = childElement.getAttribute("key")
                        val value = childElement.getAttribute("value")
                        if (key.isNotEmpty()) {
                            try {
                                // Use locale-independent parsing (ProcessM uses Locale.ROOT)
                                attributes[key] =
                                    java.text.NumberFormat
                                        .getInstance(java.util.Locale.ROOT)
                                        .parse(value)
                                        .toDouble()
                            } catch (e: Exception) {
                                logger.warn("Failed to parse float value: $value for key: $key")
                                attributes[key] = value
                            }
                        }
                    }

                    "boolean" -> {
                        val key = childElement.getAttribute("key")
                        val value = childElement.getAttribute("value")
                        if (key.isNotEmpty()) {
                            attributes[key] = value.toBoolean()
                        }
                    }

                    "id" -> {
                        // XES Identity extension - stores UUID values
                        val key = childElement.getAttribute("key")
                        val value = childElement.getAttribute("value")
                        if (key.isNotEmpty()) {
                            attributes[key] = value // Store as string (UUID format)
                        }
                    }
                }
            }
        }

        return attributes
    }

    /**
     * Ensure identity:id exists in attributes, generate UUID if missing
     */
    private fun ensureIdentityId(attributes: MutableMap<String, Any>): MutableMap<String, Any> {
        if (!attributes.containsKey("identity:id")) {
            attributes["identity:id"] = UUID.randomUUID().toString()
        }
        return attributes
    }

    /**
     * Normalize an offset-aware date string to UTC representation.
     * Preserves milliseconds if present.
     * Returns null if no offset info (can't determine UTC equivalent).
     * Example: "2006-11-07T10:00:36.839+01:00" → "2006-11-07T09:00:36.839Z"
     * Example: "2006-11-07T10:00:36+01:00" → "2006-11-07T09:00:36Z"
     */
    private fun normalizeDateToUTC(dateStr: String): String? {
        if (dateStr.isBlank()) return null
        for (formatter in offsetFormatters) {
            try {
                val odt = OffsetDateTime.parse(dateStr, formatter)
                val utc = odt.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
                // Preserve milliseconds only if non-zero
                return if (utc.nano != 0) {
                    utc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
                } else {
                    utc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
                }
            } catch (e: DateTimeParseException) {
                // Try next
            }
        }
        return null
    }

    /**
     * Parse timestamp string to LocalDateTime
     */
    private fun parseTimestamp(timestampStr: String?): LocalDateTime? {
        if (timestampStr.isNullOrBlank()) return null

        // First try parsing with timezone offset and convert to UTC (ProcessM compatibility)
        // XES timestamps often have timezone offsets like +01:00 or +02:00
        for (formatter in offsetFormatters) {
            try {
                val odt = OffsetDateTime.parse(timestampStr, formatter)
                return odt.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
            } catch (e: DateTimeParseException) {
                // Try next formatter
            }
        }

        // Fallback: parse as LocalDateTime (no timezone)
        for (formatter in localFormatters) {
            try {
                return LocalDateTime.parse(timestampStr, formatter)
            } catch (e: DateTimeParseException) {
                // Try next formatter
            }
        }

        logger.warn("Failed to parse timestamp: $timestampStr")
        return null
    }

    /**
     * Generate unique log ID
     */
    private fun generateLogId(): String = "log-${UUID.randomUUID().toString().substring(0, 8)}"

    /**
     * Generate unique trace ID
     */
    private fun generateTraceId(
        logId: String,
        caseId: String,
    ): String = "$logId-trace-${caseId.replace(" ", "_")}"

    /**
     * Generate unique event ID
     */
    private fun generateEventId(
        traceId: String,
        index: Int,
    ): String = "$traceId-event-${index + 1}"
}

/**
 * Data class representing parsed XES log
 */
data class XESLog(
    val logNode: LogNode,
    val traces: List<XESTrace>,
    val classifiers: Map<String, List<String>> = emptyMap(),
    val traceGlobals: Map<String, Any> = emptyMap(),
    val eventGlobals: Map<String, Any> = emptyMap(),
    val extensions: List<XESExtension> = emptyList(),
)

/**
 * Data class representing parsed XES trace
 */
data class XESTrace(
    val traceNode: TraceNode,
    val events: List<XESEvent>,
)

/**
 * Data class representing parsed XES event
 */
data class XESEvent(
    val eventNode: EventNode,
)

/**
 * Data class representing a parsed XES extension
 */
data class XESExtension(
    val name: String,
    val prefix: String,
    val uri: String,
)

/**
 * Exception thrown when XES parsing fails
 */
class XESParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
