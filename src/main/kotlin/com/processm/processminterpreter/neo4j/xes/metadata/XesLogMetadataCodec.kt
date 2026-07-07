package com.processm.processminterpreter.neo4j.xes.metadata

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.processm.processminterpreter.xes.model.AttributeScope
import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.Extension
import com.processm.processminterpreter.xes.model.GlobalAttribute
import com.processm.processminterpreter.xes.model.XesAttributeValue
import java.time.Instant

/**
 * Shared JSON encoding for log-level XES metadata stored as Neo4j properties.
 */
object XesLogMetadataCodec {
    private val json: ObjectMapper = jacksonObjectMapper()

    fun serializeArbitrary(value: Any): String = json.writeValueAsString(encodeValue(value))

    fun deserializeArbitrary(jsonString: String): Any? =
        decodeValue(json.readValue(jsonString, object : TypeReference<Any?>() {}))

    fun serializeClassifiers(classifiers: List<Classifier>): String? {
        if (classifiers.isEmpty()) return null
        return json.writeValueAsString(classifiers.associate { it.name to it.keys })
    }

    fun deserializeClassifiers(jsonString: String): List<Classifier> {
        if (jsonString.isBlank()) return emptyList()
        val asMap: Map<String, List<String>> =
            json.readValue(jsonString, object : TypeReference<Map<String, List<String>>>() {})
        return asMap.map { (name, keys) -> Classifier(name, keys) }
    }

    fun serializeExtensions(extensions: List<Extension>): String? {
        if (extensions.isEmpty()) return null
        return json.writeValueAsString(extensions)
    }

    fun deserializeExtensions(jsonString: String): List<Extension> {
        if (jsonString.isBlank()) return emptyList()
        return json.readValue(jsonString, object : TypeReference<List<Extension>>() {})
    }

    fun serializeGlobals(globals: List<GlobalAttribute>): String? {
        if (globals.isEmpty()) return null
        return json.writeValueAsString(globals.associate { it.key to encodeValue(it.value) })
    }

    fun deserializeGlobals(jsonString: String, scope: AttributeScope): List<GlobalAttribute> {
        if (jsonString.isBlank()) return emptyList()
        val asMap: Map<String, Any?> =
            json.readValue(jsonString, object : TypeReference<Map<String, Any?>>() {})
        return asMap.map { (k, v) -> GlobalAttribute(scope, k, decodeValue(v)) }
    }

    private fun encodeValue(value: Any?): Any? =
        when (value) {
            null -> null
            is Instant -> mapOf(TYPE_FIELD to INSTANT_TYPE, VALUE_FIELD to value.toString())
            is XesAttributeValue -> mapOf(
                TYPE_FIELD to XES_ATTRIBUTE_TYPE,
                VALUE_FIELD to encodeValue(value.value),
                CHILDREN_FIELD to value.children.entries.associate { (key, nested) -> key to encodeValue(nested) },
            )
            is Map<*, *> -> value.entries.associate { (key, nested) -> key.toString() to encodeValue(nested) }
            is Iterable<*> -> value.map { encodeValue(it) }
            is Array<*> -> value.map { encodeValue(it) }
            else -> value
        }

    private fun decodeValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> {
                when (value[TYPE_FIELD]) {
                INSTANT_TYPE -> (value[VALUE_FIELD] as? String)?.let { Instant.parse(it) }
                XES_ATTRIBUTE_TYPE -> {
                    val children = (value[CHILDREN_FIELD] as? Map<*, *>)
                        ?.entries
                        ?.associate { (key, nested) -> key.toString() to decodeValue(nested) }
                        ?: emptyMap()
                        XesAttributeValue(
                            value = decodeValue(value[VALUE_FIELD]),
                            children = children,
                        )
                    }
                    else -> value.entries.associate { (key, nested) -> key.toString() to decodeValue(nested) }
                }
            }
            is List<*> -> value.map { decodeValue(it) }
            else -> value
        }

    private const val TYPE_FIELD = "__type"
    private const val VALUE_FIELD = "value"
    private const val CHILDREN_FIELD = "children"
    private const val INSTANT_TYPE = "instant"
    private const val XES_ATTRIBUTE_TYPE = "xes-attribute"
}
