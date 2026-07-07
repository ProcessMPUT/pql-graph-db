package com.processm.processminterpreter.neo4j.repository

import com.processm.processminterpreter.xes.model.Log
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.neo4j.property.Neo4jPropertySanitizer
import com.processm.processminterpreter.neo4j.xes.metadata.XesLogMetadataCodec
import java.time.LocalDateTime

internal object Neo4jLogNodeWrite {
    val MERGE =
        """
        MERGE (log:Log {logId: ${'$'}logId})
        ON CREATE SET log.createdAt = ${'$'}createdAt
        SET log.name = ${'$'}name,
            log.updatedAt = ${'$'}updatedAt,
            log.classifiers = ${'$'}classifiers,
            log.extensions = ${'$'}extensions,
            log.traceGlobals = ${'$'}traceGlobals,
            log.eventGlobals = ${'$'}eventGlobals
        SET log += ${'$'}attributes
        """.trimIndent()

    fun parameters(log: Log): Map<String, Any?> =
        parameters(
            logId = log.id,
            name = log.name,
            createdAt = log.createdAt,
            updatedAt = log.updatedAt,
            classifiers = XesLogMetadataCodec.serializeClassifiers(log.classifiers),
            extensions = XesLogMetadataCodec.serializeExtensions(log.extensions),
            traceGlobals = XesLogMetadataCodec.serializeGlobals(log.traceGlobals),
            eventGlobals = XesLogMetadataCodec.serializeGlobals(log.eventGlobals),
            attributes = Neo4jPropertySanitizer.sanitizeCustomAttributes(log.logAttributes()),
        )

    fun parameters(
        logId: String,
        name: String,
        createdAt: LocalDateTime,
        updatedAt: LocalDateTime,
        classifiers: String?,
        extensions: String?,
        traceGlobals: String?,
        eventGlobals: String?,
        attributes: Map<String, Any?>,
    ): Map<String, Any?> =
        mapOf(
            "logId" to logId,
            "name" to name,
            "createdAt" to createdAt,
            "updatedAt" to updatedAt,
            "classifiers" to classifiers,
            "extensions" to extensions,
            "traceGlobals" to traceGlobals,
            "eventGlobals" to eventGlobals,
            "attributes" to attributes,
        )

    private fun Log.logAttributes(): Map<String, Any?> = buildMap {
        putAll(customAttributes)
        lifecycleModel?.let { put(StandardAttributeCatalog.LIFECYCLE_MODEL, it) }
    }
}
