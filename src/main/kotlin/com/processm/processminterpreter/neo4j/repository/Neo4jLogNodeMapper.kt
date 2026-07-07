package com.processm.processminterpreter.neo4j.repository

import com.processm.processminterpreter.xes.model.AttributeScope
import com.processm.processminterpreter.xes.model.Log
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchema
import com.processm.processminterpreter.neo4j.xes.metadata.XesLogMetadataCodec
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

        val customAttributes = node.keys()
            .filter { it !in Neo4jXesSchema.logNonAttributeKeys }
            .associateWith { node[it].asObject() }

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
}
