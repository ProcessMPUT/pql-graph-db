package com.processm.processminterpreter.benchmark

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

data class CanonicalXesLog(
    val extensions: List<Map<String, String>>,
    val classifiers: List<Map<String, String>>,
    val globals: List<CanonicalGlobal>,
    val attributes: List<CanonicalXesAttribute>,
    val traces: List<CanonicalXesTrace>,
    val duplicateKeys: List<String>,
)

data class CanonicalGlobal(
    val scope: String,
    val attributes: List<CanonicalXesAttribute>,
)

data class CanonicalXesTrace(
    val attributes: List<CanonicalXesAttribute>,
    val events: List<CanonicalXesEvent>,
)

data class CanonicalXesEvent(
    val attributes: List<CanonicalXesAttribute>,
)

data class CanonicalXesAttribute(
    val type: String,
    val key: String,
    val value: String,
    val children: List<CanonicalXesAttribute>,
)

data class CanonicalXesComparison(
    val matches: Boolean,
    val differences: List<String>,
)

object CanonicalXesParser {
    private val documentFactory: DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }

    fun parse(path: Path): CanonicalXesLog =
        XesStreams.openPossiblyCompressed(path).use { input ->
            parse(input.readBytes())
        }

    fun parse(bytes: ByteArray): CanonicalXesLog {
        val xml = XesStreams.firstZipEntry(bytes)
        val document = documentFactory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val root = document.documentElement
        val duplicates = mutableListOf<String>()
        return CanonicalXesLog(
            extensions = childElements(root, "extension").map { attributesOf(it) }.sortedBy { it.toString() },
            classifiers = childElements(root, "classifier").map { attributesOf(it) }.sortedBy { it.toString() },
            globals = childElements(root, "global")
                .map { global ->
                    CanonicalGlobal(
                        scope = global.getAttribute("scope"),
                        attributes = parseAttributes(global, "/log/global[@scope='${global.getAttribute("scope")}']", duplicates),
                    )
                }
                .sortedBy { it.scope },
            attributes = parseAttributes(root, "/log", duplicates),
            traces = childElements(root, "trace").mapIndexed { traceIndex, trace ->
                CanonicalXesTrace(
                    attributes = parseAttributes(trace, "/log/trace[$traceIndex]", duplicates),
                    events = childElements(trace, "event").mapIndexed { eventIndex, event ->
                        CanonicalXesEvent(
                            attributes = parseAttributes(
                                event,
                                "/log/trace[$traceIndex]/event[$eventIndex]",
                                duplicates,
                            ),
                        )
                    },
                )
            },
            duplicateKeys = duplicates.sorted(),
        )
    }

    private fun parseAttributes(
        element: Element,
        path: String,
        duplicates: MutableList<String>,
    ): List<CanonicalXesAttribute> {
        val attributes = childElements(element)
            .filter { it.hasAttribute("key") }
            .map { parseAttribute(it, duplicates) }
            .sortedWith(compareBy<CanonicalXesAttribute> { it.key }.thenBy { it.type }.thenBy { it.value })

        attributes
            .groupBy { it.key }
            .filterValues { it.size > 1 }
            .keys
            .forEach { duplicates += "$path duplicate key '$it'" }

        return attributes
    }

    private fun parseAttribute(
        element: Element,
        duplicates: MutableList<String>,
    ): CanonicalXesAttribute =
        CanonicalXesAttribute(
            type = element.localNameOrNodeName(),
            key = element.getAttribute("key"),
            value = normalizeValue(element.localNameOrNodeName(), element.getAttribute("value")),
            children = parseAttributes(element, "/attribute[@key='${element.getAttribute("key")}']", duplicates),
        )

    private fun normalizeValue(
        type: String,
        value: String,
    ): String =
        when (type) {
            "date" -> if (value.isBlank()) {
                value
            } else {
                runCatching {
                    DateTimeFormatter.ISO_INSTANT.format(OffsetDateTime.parse(value).toInstant())
                }.getOrDefault(value)
            }
            // Numeric values compare by semantic value, not source text:
            // "0.0020" and "0.002" are the same XES float (Hospital_log has
            // trailing-zero floats that a parse -> emit roundtrip reformats).
            "float" -> value.toDoubleOrNull()?.toString() ?: value
            "int" -> value.toLongOrNull()?.toString() ?: value
            else -> value
        }

    private fun childElements(
        element: Element,
        name: String? = null,
    ): List<Element> {
        val result = mutableListOf<Element>()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val childElement = child as Element
                if (name == null || childElement.localNameOrNodeName() == name) {
                    result += childElement
                }
            }
        }
        return result
    }

    private fun attributesOf(element: Element): Map<String, String> {
        val attrs = element.attributes
        return buildMap {
            for (index in 0 until attrs.length) {
                val attr = attrs.item(index)
                put(attr.nodeName, attr.nodeValue)
            }
        }
    }

    private fun Element.localNameOrNodeName(): String = localName ?: nodeName.substringAfter(':')
}

object CanonicalXesComparator {
    fun compareFiles(
        expected: Path,
        actualBytes: ByteArray,
        detailsPath: Path,
    ): CanonicalXesComparison {
        val expectedLog = CanonicalXesParser.parse(expected)
        val actualLog = CanonicalXesParser.parse(actualBytes)
        val differences = compare(expectedLog, actualLog)
        if (differences.isNotEmpty()) {
            Files.createDirectories(detailsPath.parent)
            Files.writeString(detailsPath, differences.joinToString(System.lineSeparator()))
        }
        return CanonicalXesComparison(matches = differences.isEmpty(), differences = differences)
    }

    fun compare(
        expected: CanonicalXesLog,
        actual: CanonicalXesLog,
    ): List<String> {
        val differences = mutableListOf<String>()
        if (expected.duplicateKeys.isNotEmpty()) differences += "Original XES has duplicate keys: ${expected.duplicateKeys}"
        if (actual.duplicateKeys.isNotEmpty()) differences += "Exported XES has duplicate keys: ${actual.duplicateKeys}"
        compareValue("extensions", expected.extensions, actual.extensions, differences)
        compareValue("classifiers", expected.classifiers, actual.classifiers, differences)
        compareValue("globals", expected.globals, actual.globals, differences)
        compareValue("log attributes", expected.attributes, actual.attributes, differences)
        compareValue("trace count", expected.traces.size, actual.traces.size, differences)
        expected.traces.zip(actual.traces).forEachIndexed { traceIndex, (expectedTrace, actualTrace) ->
            compareValue("trace[$traceIndex] attributes", expectedTrace.attributes, actualTrace.attributes, differences)
            compareValue("trace[$traceIndex] event count", expectedTrace.events.size, actualTrace.events.size, differences)
            expectedTrace.events.zip(actualTrace.events).forEachIndexed { eventIndex, (expectedEvent, actualEvent) ->
                compareValue(
                    "trace[$traceIndex].event[$eventIndex] attributes",
                    expectedEvent.attributes,
                    actualEvent.attributes,
                    differences,
                )
            }
        }
        return differences.take(MAX_DIFFERENCES)
    }

    private fun compareValue(
        label: String,
        expected: Any,
        actual: Any,
        differences: MutableList<String>,
    ) {
        if (expected != actual && differences.size < MAX_DIFFERENCES) {
            differences += "$label differs: expected=$expected actual=$actual"
        }
    }

    private const val MAX_DIFFERENCES = 100
}
