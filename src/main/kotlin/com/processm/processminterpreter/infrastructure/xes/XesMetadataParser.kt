package com.processm.processminterpreter.infrastructure.xes

import com.processm.processminterpreter.domain.log.AttributeScope
import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.log.Extension
import com.processm.processminterpreter.domain.log.GlobalAttribute
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

internal class XesMetadataParser(private val attributes: XesAttributeParser) {
    private val logger = LoggerFactory.getLogger(XesMetadataParser::class.java)

    fun parseClassifiers(logElement: Element): List<Classifier> =
        directChildElements(logElement)
            .filter { it.tagName == "classifier" }
            .mapNotNull { element ->
                val scope = element.getAttribute("scope")
                if (scope.isNotEmpty() && scope != "trace" && scope != "event") {
                    throw XESParseException("Illegal <classifier> scope. Expected 'trace' or 'event', found $scope")
                }
                val name = element.getAttribute("name")
                val keys = element.getAttribute("keys")
                if (name.isNotEmpty() && keys.isNotEmpty()) {
                    val keyList = keys.trim().split("\\s+".toRegex())
                    logger.debug("Parsed classifier: $name -> $keyList")
                    Classifier(name, keyList)
                } else {
                    null
                }
            }
            .toList()

    fun parseExtensions(logElement: Element): List<Extension> =
        directChildElements(logElement)
            .filter { it.tagName == "extension" }
            .mapNotNull { element ->
                val name = element.getAttribute("name")
                val prefix = element.getAttribute("prefix")
                val uri = element.getAttribute("uri")
                if (name.isNotEmpty()) Extension(name, prefix, uri) else null
            }
            .toList()

    fun parseGlobals(logElement: Element): ParsedXesGlobals {
        val traceGlobals = mutableListOf<GlobalAttribute>()
        val eventGlobals = mutableListOf<GlobalAttribute>()

        directChildElements(logElement)
            .filter { it.tagName == "global" }
            .forEach { element ->
                val scope = element.getAttribute("scope")
                val parsedAttributes = attributes.parseAttributes(element)
                when (scope) {
                    "trace" -> parsedAttributes.forEach { (key, value) ->
                        traceGlobals += GlobalAttribute(AttributeScope.TRACE, key, value)
                    }
                    "", "event" -> parsedAttributes.forEach { (key, value) ->
                        eventGlobals += GlobalAttribute(AttributeScope.EVENT, key, value)
                    }
                    else -> throw XESParseException("Illegal <global> scope. Expected 'trace' or 'event', found $scope")
                }
            }

        return ParsedXesGlobals(traceGlobals, eventGlobals)
    }

    private fun directChildElements(element: Element): Sequence<Element> =
        sequence {
            val childNodes = element.childNodes
            for (i in 0 until childNodes.length) {
                val node = childNodes.item(i)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    yield(node as Element)
                }
            }
        }
}

internal data class ParsedXesGlobals(
    val trace: List<GlobalAttribute>,
    val event: List<GlobalAttribute>,
)
