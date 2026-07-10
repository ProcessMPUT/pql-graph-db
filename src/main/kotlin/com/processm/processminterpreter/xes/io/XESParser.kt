package com.processm.processminterpreter.xes.io

import com.processm.processminterpreter.xes.model.AttributeScope
import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.Extension
import com.processm.processminterpreter.xes.model.GlobalAttribute
import com.processm.processminterpreter.xes.model.XesAttributeValue
import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Streaming (StAX) XES parser: a single forward pass both validates the tag
 * structure and builds the domain model. The previous implementation buffered
 * the whole file into a byte array and materialized a full DOM tree next to
 * the resulting [XesLog] — on 100k+-event logs the DOM alone dominated the
 * import heap peak.
 */
@Component
class XESParser {
    private val logger = LoggerFactory.getLogger(XESParser::class.java)
    private val attributes = XesAttributeParser()
    private val xmlInputFactory = XMLInputFactory.newFactory()

    fun parseXesLog(inputStream: InputStream): XesLog {
        logger.info("Starting XES parsing to XesLog")

        try {
            val reader = xmlInputFactory.createXMLStreamReader(inputStream)
            val raw = try {
                readDocument(reader)
            } finally {
                reader.close()
            }
            return buildLog(raw)
        } catch (e: Exception) {
            logger.error("Error parsing XES file", e)
            throw XESParseException("Failed to parse XES file: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------------------
    // Streaming pass: raw structures in document order
    // ---------------------------------------------------------------------

    private class RawLog {
        val attributes = linkedMapOf<String, Any>()
        val extensions = mutableListOf<Extension>()
        val classifiers = mutableListOf<Classifier>()
        val traceGlobals = mutableListOf<GlobalAttribute>()
        val eventGlobals = mutableListOf<GlobalAttribute>()
        val traces = mutableListOf<RawTrace>()
    }

    private class RawTrace {
        val attributes = linkedMapOf<String, Any>()
        val events = mutableListOf<LinkedHashMap<String, Any>>()
    }

    private fun readDocument(reader: XMLStreamReader): RawLog {
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                requireAllowedChild(parent = null, reader = reader)
                return readLog(reader)
            }
        }
        throw XESParseException("Document contains no XML elements")
    }

    private fun readLog(reader: XMLStreamReader): RawLog {
        val log = RawLog()
        forEachChildElement(reader, parent = "log") { tag ->
            when (tag) {
                "extension" -> readExtension(reader, log)
                "classifier" -> readClassifier(reader, log)
                "global" -> readGlobal(reader, log)
                "trace" -> log.traces += readTrace(reader)
                else -> readAttributeInto(reader, tag, log.attributes)
            }
        }
        return log
    }

    private fun readExtension(reader: XMLStreamReader, log: RawLog) {
        val name = reader.attributeValue("name")
        val prefix = reader.attributeValue("prefix")
        val uri = reader.attributeValue("uri")
        if (name.isNotEmpty()) log.extensions += Extension(name, prefix, uri)
        forEachChildElement(reader, parent = "extension") { }
    }

    private fun readClassifier(reader: XMLStreamReader, log: RawLog) {
        val scope = reader.attributeValue("scope")
        if (scope.isNotEmpty() && scope != "trace" && scope != "event") {
            throw XESParseException("Illegal <classifier> scope. Expected 'trace' or 'event', found $scope")
        }
        val name = reader.attributeValue("name")
        val keys = reader.attributeValue("keys")
        if (name.isNotEmpty() && keys.isNotEmpty()) {
            val keyList = keys.trim().split("\\s+".toRegex())
            logger.debug("Parsed classifier: $name -> $keyList")
            log.classifiers += Classifier(name, keyList)
        }
        forEachChildElement(reader, parent = "classifier") { }
    }

    private fun readGlobal(reader: XMLStreamReader, log: RawLog) {
        val scope = reader.attributeValue("scope")
        val parsed = linkedMapOf<String, Any>()
        forEachChildElement(reader, parent = "global") { tag ->
            readAttributeInto(reader, tag, parsed)
        }
        when (scope) {
            "trace" -> parsed.forEach { (key, value) ->
                log.traceGlobals += GlobalAttribute(AttributeScope.TRACE, key, value)
            }
            "", "event" -> parsed.forEach { (key, value) ->
                log.eventGlobals += GlobalAttribute(AttributeScope.EVENT, key, value)
            }
            else -> throw XESParseException("Illegal <global> scope. Expected 'trace' or 'event', found $scope")
        }
    }

    private fun readTrace(reader: XMLStreamReader): RawTrace {
        val trace = RawTrace()
        forEachChildElement(reader, parent = "trace") { tag ->
            if (tag == "event") {
                val event = linkedMapOf<String, Any>()
                forEachChildElement(reader, parent = "event") { eventTag ->
                    readAttributeInto(reader, eventTag, event)
                }
                trace.events += event
            } else {
                readAttributeInto(reader, tag, trace.attributes)
            }
        }
        return trace
    }

    /**
     * Reads one attribute element (and its whole subtree). A blank `key` skips
     * the attribute like the XES reference reader, but the subtree is still
     * consumed and validated. Duplicate keys keep last-write-wins semantics.
     */
    private fun readAttributeInto(
        reader: XMLStreamReader,
        tag: String,
        target: MutableMap<String, Any>,
    ) {
        val (key, value) = readAttributeElement(reader, tag) ?: return
        target[key] = value
    }

    private fun readAttributeElement(reader: XMLStreamReader, tag: String): Pair<String, Any>? {
        val key = reader.attributeValue("key")
        val value = reader.attributeValue("value")

        if (tag == "list") {
            val list = readListValue(reader)
            return if (key.isEmpty()) null else key to list
        }

        val scalar = attributes.scalarValue(tag, key, value)
        val children = linkedMapOf<String, Any>()
        forEachChildElement(reader, parent = tag) { childTag ->
            readAttributeInto(reader, childTag, children)
        }
        if (key.isEmpty() || scalar == null) return null
        return if (children.isEmpty()) {
            key to scalar
        } else {
            key to XesAttributeValue(scalar, children)
        }
    }

    private fun readListValue(reader: XMLStreamReader): Map<String, Any> {
        val listAttributes = linkedMapOf<String, Any>()
        val values = mutableListOf<Map<String, Any>>()
        forEachChildElement(reader, parent = "list") { tag ->
            if (tag == "values") {
                forEachChildElement(reader, parent = "values") { valueTag ->
                    readAttributeElement(reader, valueTag)?.let { (key, value) ->
                        values += mapOf(key to value)
                    }
                }
            } else {
                readAttributeInto(reader, tag, listAttributes)
            }
        }
        return mapOf("attributes" to listAttributes, "values" to values)
    }

    /**
     * Iterates the direct child elements of the element the reader is currently
     * positioned on, validating each child tag, and leaves the reader on this
     * element's END_ELEMENT. [handle] must consume the child's whole subtree.
     */
    private inline fun forEachChildElement(
        reader: XMLStreamReader,
        parent: String,
        handle: (tag: String) -> Unit,
    ) {
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val tag = requireAllowedChild(parent, reader)
                    handle(tag)
                }
                XMLStreamConstants.END_ELEMENT -> return
            }
        }
    }

    private fun requireAllowedChild(parent: String?, reader: XMLStreamReader): String {
        val tag = reader.localName
        val allowed = when (parent) {
            null -> tag == "log"
            "log" -> tag in ATTRIBUTE_TAGS || tag in setOf("extension", "global", "classifier", "trace")
            "trace" -> tag in ATTRIBUTE_TAGS || tag == "event"
            "event", "global", "values" -> tag in ATTRIBUTE_TAGS
            "list" -> tag in ATTRIBUTE_TAGS || tag == "values"
            in ATTRIBUTE_TAGS -> tag in ATTRIBUTE_TAGS
            else -> false
        }
        if (!allowed) {
            val location = reader.location
            throw XESParseException(
                "Found unexpected XML tag: $tag in line ${location.lineNumber} column ${location.columnNumber}",
            )
        }
        return tag
    }

    private fun XMLStreamReader.attributeValue(name: String): String =
        getAttributeValue(null, name) ?: ""

    // ---------------------------------------------------------------------
    // Finishing pass: canonical prefixes + standard-attribute extraction
    // ---------------------------------------------------------------------

    private fun buildLog(raw: RawLog): XesLog {
        val standardPrefixes = standardPrefixes(raw.extensions)

        val customAttributes = canonicalStandardAttributes(raw.attributes, standardPrefixes)
        val conceptName = customAttributes.removeScalar("concept:name") as? String
        val identityId = attributes.extractUuid(customAttributes, "identity:id")
        val lifecycleModel = customAttributes.removeScalar("lifecycle:model") as? String

        val traces = raw.traces.map { buildTrace(it, standardPrefixes) }

        logger.info(
            "Parsed XES log with ${traces.size} traces, ${raw.classifiers.size} classifiers, ${raw.extensions.size} extensions",
        )
        logger.debug("Global trace attrs: ${raw.traceGlobals}, Global event attrs: ${raw.eventGlobals}")

        return XesLog(
            conceptName = conceptName,
            identityId = identityId,
            lifecycleModel = lifecycleModel,
            classifiers = raw.classifiers,
            extensions = raw.extensions,
            traceGlobals = raw.traceGlobals,
            eventGlobals = raw.eventGlobals,
            customAttributes = customAttributes,
            traces = traces,
        )
    }

    private fun buildTrace(
        raw: RawTrace,
        standardPrefixes: Map<String, String>,
    ): XesTrace {
        val customAttributes = canonicalStandardAttributes(raw.attributes, standardPrefixes)
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
            events = raw.events.map { buildEvent(it, standardPrefixes) },
        )
    }

    private fun buildEvent(
        rawAttributes: Map<String, Any>,
        standardPrefixes: Map<String, String>,
    ): XesEvent {
        val customAttributes = canonicalStandardAttributes(rawAttributes, standardPrefixes)

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

    private companion object {
        val ATTRIBUTE_TAGS = setOf("string", "date", "int", "float", "boolean", "id", "list")
    }
}

class XESParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
