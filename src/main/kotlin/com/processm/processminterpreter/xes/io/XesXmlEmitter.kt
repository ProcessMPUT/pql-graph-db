package com.processm.processminterpreter.xes.io

import com.processm.processminterpreter.xes.model.AttributeScope
import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.Extension
import com.processm.processminterpreter.xes.model.GlobalAttribute
import com.processm.processminterpreter.xes.model.XesAttributeValue
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

internal class XesXmlEmitter(private val writer: OutputStreamWriter) {
    private val isoUtc: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC)

    // Attribute keys come from imported data and can contain XML-special
    // characters (Hospital_log has keys like "Obstetrics & Gynaecology clinic"),
    // so they need the same escaping as values.
    fun string(key: String, value: String, indent: String) {
        writer.write("$indent<string key=\"${escape(key)}\" value=\"${escape(value)}\"/>\n")
    }

    fun float(key: String, value: Double, indent: String) {
        writer.write("$indent<float key=\"${escape(key)}\" value=\"$value\"/>\n")
    }

    fun int(key: String, value: Long, indent: String) {
        writer.write("$indent<int key=\"${escape(key)}\" value=\"$value\"/>\n")
    }

    fun boolean(key: String, value: Boolean, indent: String) {
        writer.write("$indent<boolean key=\"${escape(key)}\" value=\"$value\"/>\n")
    }

    fun date(key: String, value: Instant, indent: String) {
        writer.write("$indent<date key=\"${escape(key)}\" value=\"${isoUtc.format(value)}\"/>\n")
    }

    fun id(key: String, value: UUID, indent: String) {
        writer.write("$indent<id key=\"${escape(key)}\" value=\"$value\"/>\n")
    }

    fun list(key: String, value: Map<*, *>, indent: String) {
        writer.write("$indent<list key=\"${escape(key)}\">\n")
        val attributes = value["attributes"] as? Map<*, *> ?: emptyMap<Any, Any>()
        attributes.forEach { (nestedKey, nestedValue) ->
            if (nestedKey != null) customAttribute(nestedKey.toString(), nestedValue, "$indent\t")
        }
        val values = value["values"] as? List<*> ?: emptyList<Any>()
        if (values.isNotEmpty()) {
            writer.write("$indent\t<values>\n")
            values.forEach { item ->
                if (item is Map<*, *>) {
                    item.forEach { (nestedKey, nestedValue) ->
                        if (nestedKey != null) customAttribute(nestedKey.toString(), nestedValue, "$indent\t\t")
                    }
                }
            }
            writer.write("$indent\t</values>\n")
        }
        writer.write("$indent</list>\n")
    }

    fun customAttributes(attrs: Map<String, Any?>, indent: String) {
        for ((key, value) in attrs) {
            customAttribute(key, value, indent)
        }
    }

    private fun customAttribute(key: String, value: Any?, indent: String) {
        when (value) {
            null -> return
            is Boolean -> boolean(key, value, indent)
            is Long -> int(key, value, indent)
            is Int -> int(key, value.toLong(), indent)
            is Short -> int(key, value.toLong(), indent)
            is Byte -> int(key, value.toLong(), indent)
            is Double -> float(key, value, indent)
            is Float -> float(key, value.toDouble(), indent)
            is Instant -> date(key, value, indent)
            is UUID -> id(key, value, indent)
            is XesAttributeValue -> nestedAttribute(key, value, indent)
            is Map<*, *> if value.containsKey("attributes") || value.containsKey("values") -> list(key, value, indent)
            else -> string(key, value.toString(), indent)
        }
    }

    private fun nestedAttribute(key: String, attribute: XesAttributeValue, indent: String) {
        if (attribute.children.isEmpty()) {
            customAttribute(key, attribute.value, indent)
            return
        }
        val scalar = attribute.value ?: return
        val tag = tagName(scalar)
        val value = valueText(scalar)
        writer.write("$indent<$tag key=\"${escape(key)}\" value=\"${escape(value)}\">\n")
        attribute.children.forEach { (nestedKey, nestedValue) ->
            customAttribute(nestedKey, nestedValue, "$indent\t")
        }
        writer.write("$indent</$tag>\n")
    }

    private fun tagName(value: Any): String = when (value) {
        is Boolean -> "boolean"
        is Long, is Int, is Short, is Byte -> "int"
        is Double, is Float -> "float"
        is Instant -> "date"
        is UUID -> "id"
        else -> "string"
    }

    private fun valueText(value: Any): String = when (value) {
        is Instant -> isoUtc.format(value)
        else -> value.toString()
    }

    fun defaultExtensions() {
        extension(
            Extension(
                name = "Concept",
                prefix = "concept",
                uri = "http://www.xes-standard.org/concept.xesext",
            ),
        )
        extension(
            Extension(
                name = "Time",
                prefix = "time",
                uri = "http://www.xes-standard.org/time.xesext",
            ),
        )
        extension(
            Extension(
                name = "Organizational",
                prefix = "org",
                uri = "http://www.xes-standard.org/org.xesext",
            ),
        )
        extension(
            Extension(
                name = "Lifecycle",
                prefix = "lifecycle",
                uri = "http://www.xes-standard.org/lifecycle.xesext",
            ),
        )
    }

    fun extension(ext: Extension) {
        writer.write(
            "\t<extension name=\"${escape(ext.name)}\" prefix=\"${escape(ext.prefix)}\" " +
                "uri=\"${escape(ext.uri)}\"/>\n",
        )
    }

    fun globalsBlock(scope: String, globals: List<GlobalAttribute>) {
        writer.write("\t<global scope=\"$scope\">\n")
        customAttributes(
            globals.associate { it.key to it.value },
            indent = "\t\t",
        )
        writer.write("\t</global>\n")
    }

    fun classifier(classifier: Classifier) {
        val keys = classifier.keys.joinToString(" ")
        val scope = if (classifier.scope == AttributeScope.TRACE) " scope=\"trace\"" else ""
        writer.write(
            "\t<classifier$scope name=\"${escape(classifier.name)}\" keys=\"${escape(keys)}\"/>\n",
        )
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
