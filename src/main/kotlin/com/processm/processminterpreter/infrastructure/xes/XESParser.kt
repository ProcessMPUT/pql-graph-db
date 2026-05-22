package com.processm.processminterpreter.infrastructure.xes

import com.processm.processminterpreter.domain.log.xes.XesEvent
import com.processm.processminterpreter.domain.log.xes.XesAttributeValue
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.log.xes.XesTrace
import com.processm.processminterpreter.domain.log.Extension
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

@Component
class XESParser {
    private val logger = LoggerFactory.getLogger(XESParser::class.java)
    private val attributes = XesAttributeParser()
    private val metadata = XesMetadataParser(attributes)
    private val xmlInputFactory = XMLInputFactory.newFactory()

    fun parseXesLog(inputStream: InputStream): XesLog {
        logger.info("Starting XES parsing to XesLog")

        try {
            val bytes = inputStream.readBytes()
            validateKnownXesTags(bytes)
            return parseXesLog(readLogElement(ByteArrayInputStream(bytes)))
        } catch (e: Exception) {
            logger.error("Error parsing XES file", e)
            throw XESParseException("Failed to parse XES file: ${e.message}", e)
        }
    }

    private fun readLogElement(inputStream: InputStream): Element {
        val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val document: Document = documentBuilder.parse(inputStream)
        document.documentElement.normalize()

        val logElement = document.documentElement
        if (logElement.tagName != "log") {
            throw XESParseException("Root element must be 'log', found: ${logElement.tagName}")
        }
        return logElement
    }

    private fun validateKnownXesTags(bytes: ByteArray) {
        val attributeTags = setOf("string", "date", "int", "float", "boolean", "id", "list")
        val reader = xmlInputFactory.createXMLStreamReader(ByteArrayInputStream(bytes))
        val stack = mutableListOf<String>()
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                val tag = reader.localName
                val parent = stack.lastOrNull()
                val allowed = when (parent) {
                    null -> tag == "log"
                    "log" -> tag in attributeTags || tag in setOf("extension", "global", "classifier", "trace")
                    "trace" -> tag in attributeTags || tag == "event"
                    "event", "global", "values" -> tag in attributeTags
                    "list" -> tag in attributeTags || tag == "values"
                    in attributeTags -> tag in attributeTags
                    else -> false
                }
                if (!allowed) {
                    val location = reader.location
                    throw XESParseException(
                        "Found unexpected XML tag: $tag in line ${location.lineNumber} column ${location.columnNumber}",
                    )
                }
                stack += tag
            } else if (reader.eventType == XMLStreamConstants.END_ELEMENT) {
                if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            }
        }
    }

    private fun parseXesLog(logElement: Element): XesLog {
        val extensions = metadata.parseExtensions(logElement)
        val standardPrefixes = standardPrefixes(extensions)

        val customAttributes = canonicalStandardAttributes(attributes.parseAttributes(logElement), standardPrefixes)
        val conceptName = customAttributes.removeScalar("concept:name") as? String
        val identityId = attributes.extractUuid(customAttributes, "identity:id")
        val lifecycleModel = customAttributes.removeScalar("lifecycle:model") as? String

        val globals = metadata.parseGlobals(logElement)
        val classifiers = metadata.parseClassifiers(logElement)
        val traces = parseTraces(logElement, standardPrefixes)

        logger.info(
            "Parsed XES log with ${traces.size} traces, ${classifiers.size} classifiers, ${extensions.size} extensions",
        )
        logger.debug("Global trace attrs: ${globals.trace}, Global event attrs: ${globals.event}")

        return XesLog(
            conceptName = conceptName,
            identityId = identityId,
            lifecycleModel = lifecycleModel,
            classifiers = classifiers,
            extensions = extensions,
            traceGlobals = globals.trace,
            eventGlobals = globals.event,
            customAttributes = customAttributes,
            traces = traces,
        )
    }

    private fun parseTraces(
        logElement: Element,
        standardPrefixes: Map<String, String>,
    ): List<XesTrace> {
        val traceElements = logElement.getElementsByTagName("trace")
        return List(traceElements.length) { index ->
            parseTrace(traceElements.item(index) as Element, standardPrefixes)
        }
    }

    private fun parseTrace(
        traceElement: Element,
        standardPrefixes: Map<String, String>,
    ): XesTrace {
        val customAttributes = canonicalStandardAttributes(attributes.parseAttributes(traceElement), standardPrefixes)
        logger.trace("Parsed trace attributes: {}", customAttributes)

        val conceptName = customAttributes.removeScalar("concept:name") as? String
        val identityId = attributes.extractUuid(customAttributes, "identity:id")
        val costCurrency = customAttributes.removeScalar("cost:currency") as? String
        val costTotal = attributes.asDouble(customAttributes.removeScalar("cost:total"))

        return XesTrace(
            conceptName = conceptName,
            identityId = identityId,
            costCurrency = costCurrency,
            costTotal = costTotal,
            customAttributes = customAttributes,
            events = parseEvents(traceElement, standardPrefixes),
        )
    }

    private fun parseEvents(
        traceElement: Element,
        standardPrefixes: Map<String, String>,
    ): List<XesEvent> {
        val eventElements = traceElement.getElementsByTagName("event")
        return List(eventElements.length) { index ->
            parseEvent(eventElements.item(index) as Element, standardPrefixes)
        }
    }

    private fun parseEvent(
        eventElement: Element,
        standardPrefixes: Map<String, String>,
    ): XesEvent {
        val customAttributes = canonicalStandardAttributes(attributes.parseAttributes(eventElement), standardPrefixes)

        val conceptName = customAttributes.removeScalar("concept:name") as? String
        val conceptInstance = customAttributes.removeScalar("concept:instance") as? String
        val identityId = attributes.extractUuid(customAttributes, "identity:id")
        val timeTimestamp = attributes.toInstant(customAttributes.removeScalar("time:timestamp"))
        val lifecycleTransition = customAttributes.removeScalar("lifecycle:transition") as? String
        val lifecycleState = customAttributes.removeScalar("lifecycle:state") as? String
        val orgResource = customAttributes.removeScalar("org:resource") as? String
        val orgRole = customAttributes.removeScalar("org:role") as? String
        val orgGroup = customAttributes.removeScalar("org:group") as? String
        val costCurrency = customAttributes.removeScalar("cost:currency") as? String
        val costTotal = attributes.asDouble(customAttributes.removeScalar("cost:total"))

        return XesEvent(
            conceptName = conceptName,
            conceptInstance = conceptInstance,
            identityId = identityId,
            timeTimestamp = timeTimestamp,
            lifecycleTransition = lifecycleTransition,
            lifecycleState = lifecycleState,
            orgResource = orgResource,
            orgRole = orgRole,
            orgGroup = orgGroup,
            costCurrency = costCurrency,
            costTotal = costTotal,
            customAttributes = customAttributes,
        )
    }

    private fun standardPrefixes(extensions: List<Extension>): Map<String, String> {
        val canonicalByName =
            mapOf(
                "Concept" to "concept",
                "Identity" to "identity",
                "Lifecycle" to "lifecycle",
                "Time" to "time",
                "Organizational" to "org",
                "Cost" to "cost",
            )
        return extensions
            .mapNotNull { extension ->
                val canonical = canonicalByName[extension.name] ?: return@mapNotNull null
                if (extension.prefix.isBlank()) null else extension.prefix to canonical
            }.toMap()
    }

    private fun canonicalStandardAttributes(
        parsedAttributes: Map<String, Any>,
        standardPrefixes: Map<String, String>,
    ): MutableMap<String, Any> =
        parsedAttributes
            .mapKeys { (key, _) ->
                val prefix = key.substringBefore(':', missingDelimiterValue = "")
                val localName = key.substringAfter(':', missingDelimiterValue = "")
                val canonicalPrefix = standardPrefixes[prefix]
                if (canonicalPrefix != null && localName.isNotEmpty()) "$canonicalPrefix:$localName" else key
            }.toMutableMap()

    private fun MutableMap<String, Any>.removeScalar(key: String): Any? =
        when (val value = remove(key)) {
            is XesAttributeValue -> value.value
            else -> value
        }
}

class XESParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
