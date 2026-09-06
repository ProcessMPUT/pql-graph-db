package com.processm.processminterpreter.processm.json

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode

/**
 * Reads the XML sibling representation emitted by ProcessM's StAXON stream.
 * Noncontiguous runs of an element name may appear as repeated JSON fields;
 * ordinary readTree/readValue would silently retain only the last run.
 * Keep every sibling, including null placeholders and duplicate XES attributes.
 * Duplicate XML attribute fields and unknown JSON fields are ambiguous and fail.
 */
object ProcessMXesJsonParser {
    private val mapper = ObjectMapper()
    private val siblingElements = setOf(
        "log", "trace", "event", "extension", "classifier", "global",
        "string", "date", "int", "float", "boolean", "id", "list", "container", "values",
    )

    fun parse(body: String): JsonNode = mapper.factory.createParser(body).use { parser ->
        require(parser.nextToken() != null) { "Empty XES-JSON response" }
        val root = read(parser)
        require(parser.nextToken() == null) { "Trailing content after XES-JSON response" }
        root
    }

    private fun read(parser: JsonParser): JsonNode = when (parser.currentToken()) {
        JsonToken.START_OBJECT -> mapper.createObjectNode().also { objectNode ->
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.FIELD_NAME) { "Expected an XES-JSON field" }
                val name = parser.currentName()
                require(parser.nextToken() != null) { "Missing value for XES-JSON field $name" }
                val value = read(parser)
                val previous = objectNode.get(name)
                if (previous == null) {
                    objectNode.set<JsonNode>(name, value)
                } else {
                    require(name in siblingElements) { "Duplicate non-element XES-JSON field $name" }
                    val siblings = previous as? ArrayNode ?: mapper.createArrayNode().add(previous)
                    if (value.isArray) siblings.addAll(value as ArrayNode) else siblings.add(value)
                    objectNode.set<JsonNode>(name, siblings)
                }
            }
        }
        JsonToken.START_ARRAY -> mapper.createArrayNode().also { array ->
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                require(parser.currentToken() != null) { "Unterminated XES-JSON array" }
                array.add(read(parser))
            }
        }
        else -> mapper.readTree(parser)
    }
}
