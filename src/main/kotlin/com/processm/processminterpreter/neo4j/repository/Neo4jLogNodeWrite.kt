package com.processm.processminterpreter.neo4j.repository

import com.processm.processminterpreter.xes.model.Log
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.neo4j.property.Neo4jPropertySanitizer
import com.processm.processminterpreter.neo4j.xes.metadata.XesLogMetadataCodec
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesCustomAttributeCodec
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchema
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

    val CREATE_IMPORT =
        """
        CREATE (log:ImportingLog {logId: ${'$'}logId})
        SET log.createdAt = ${'$'}createdAt,
            log.name = ${'$'}name,
            log.updatedAt = ${'$'}updatedAt,
            log.classifiers = ${'$'}classifiers,
            log.extensions = ${'$'}extensions,
            log.traceGlobals = ${'$'}traceGlobals,
            log.eventGlobals = ${'$'}eventGlobals
        SET log += ${'$'}attributes
        """.trimIndent()

    val UPDATE =
        """
        MATCH (log:Log {logId: ${'$'}logId})
        WITH log, [key IN keys(log) WHERE NOT key IN ${'$'}managedProperties] AS previousAttributeKeys
        FOREACH (key IN previousAttributeKeys | SET log[key] = null)
        SET log.name = ${'$'}name,
            log.updatedAt = ${'$'}updatedAt,
            log.classifiers = ${'$'}classifiers,
            log.extensions = ${'$'}extensions,
            log.traceGlobals = ${'$'}traceGlobals,
            log.eventGlobals = ${'$'}eventGlobals
        SET log += ${'$'}attributes
        RETURN log
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
            attributes = writableAttributes(log.logAttributes()),
        ) + ("managedProperties" to Neo4jXesSchema.writerManagedProperties(Scope.LOG).toList())

    /**
     * `SET log += $attributes` would otherwise let a custom attribute named after
     * a structural column (`logId`, `name`, ...) overwrite it. Stripped after
     * sanitize, since sanitizing can rewrite a key into a reserved name.
     */
    private fun writableAttributes(attributes: Map<String, Any?>): Map<String, Any?> {
        val reserved = Neo4jXesSchema.writerManagedProperties(Scope.LOG)
        return Neo4jPropertySanitizer.sanitizeCustomAttributesWithNestedPayload(attributes)
            .filterKeys { it !in reserved }
    }

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
        customAttributes.forEach { (xesName, value) ->
            put(Neo4jXesCustomAttributeCodec.physicalName(Scope.LOG, xesName), value)
        }
        lifecycleModel?.let { put(StandardAttributeCatalog.LIFECYCLE_MODEL, it) }
    }
}
