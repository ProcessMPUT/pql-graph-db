package com.processm.processminterpreter.neo4j.repository

import com.processm.processminterpreter.xes.model.AttributeScope
import com.processm.processminterpreter.xes.model.Log
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesCustomAttributeCodec
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchema
import com.processm.processminterpreter.neo4j.xes.metadata.XesLogMetadataCodec
import com.processm.processminterpreter.neo4j.property.NestedAttributePathCodec
import org.neo4j.driver.types.Node

object Neo4jLogNodeMapper {
    fun toDomain(node: Node): Log {
        val logId = node["logId"].asString()
        val name = node["name"].asString()
        val createdAt = node["createdAt"].asLocalDateTime()
        val updatedAt = if (node.containsKey("updatedAt")) node["updatedAt"].asLocalDateTime() else createdAt

        val classifiers = if (node.hasNonNull("classifiers")) {
            XesLogMetadataCodec.deserializeClassifiers(node["classifiers"].asString())
        } else {
            emptyList()
        }

        val extensions = if (node.hasNonNull("extensions")) {
            XesLogMetadataCodec.deserializeExtensions(node["extensions"].asString())
        } else {
            emptyList()
        }

        val traceGlobals = if (node.hasNonNull("traceGlobals")) {
            XesLogMetadataCodec.deserializeGlobals(node["traceGlobals"].asString(), AttributeScope.TRACE)
        } else {
            emptyList()
        }

        val eventGlobals = if (node.hasNonNull("eventGlobals")) {
            XesLogMetadataCodec.deserializeGlobals(node["eventGlobals"].asString(), AttributeScope.EVENT)
        } else {
            emptyList()
        }
        val lifecycleModel = if (node.hasNonNull(StandardAttributeCatalog.LIFECYCLE_MODEL)) {
            node[StandardAttributeCatalog.LIFECYCLE_MODEL].asString()
        } else {
            null
        }

        val nestedPayload = node.nestedAttributePayload()
        val customAttributes = (node.keys() + nestedPayload.keys)
            .distinct()
            .filter {
                it !in Neo4jXesSchema.logNonAttributeKeys &&
                    !Neo4jXesSchema.isStorageMetadata(Scope.LOG, it) &&
                    !NestedAttributePathCodec.isEncoded(it)
            }
            .associate { physicalName ->
                val xesName = Neo4jXesCustomAttributeCodec.xesName(Scope.LOG, physicalName) ?: physicalName
                xesName to (nestedPayload[physicalName] ?: node[physicalName].asObject())
            }

        return Log(
            id = logId,
            name = name,
            createdAt = createdAt,
            updatedAt = updatedAt,
            classifiers = classifiers,
            extensions = extensions,
            traceGlobals = traceGlobals,
            eventGlobals = eventGlobals,
            lifecycleModel = lifecycleModel,
            customAttributes = customAttributes,
        )
    }

    private fun Node.hasNonNull(key: String): Boolean =
        containsKey(key) && !this[key].isNull

    private fun Node.nestedAttributePayload(): Map<String, Any?> {
        val key = Neo4jXesSchema.NESTED_ATTRIBUTE_PAYLOAD_PROPERTY
        if (!hasNonNull(key)) return emptyMap()
        val decoded = runCatching { XesLogMetadataCodec.deserializeArbitrary(this[key].asString()) as? Map<*, *> }
            .getOrNull()
            ?: return emptyMap()
        return decoded.entries.mapNotNull { (physicalName, value) ->
            (physicalName as? String)?.let { it to value }
        }.toMap()
    }
}
