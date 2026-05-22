package com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.schema

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.StandardAttributeCatalog

/**
 * Shared definition of how canonical XES attributes are stored in Neo4j.
 *
 * Keeping the schema in one place avoids silent drift between query codegen,
 * repository reads/writes, and hierarchy reconstruction.
 */
object Neo4jXesSchema {
    const val LOG_CLASSIFIERS_PROPERTY = "classifiers"
    const val LOG_EXTENSIONS_PROPERTY = "extensions"
    const val LOG_TRACE_GLOBALS_PROPERTY = "traceGlobals"
    const val LOG_EVENT_GLOBALS_PROPERTY = "eventGlobals"

    val eventPhysicalName: Map<String, String> = mapOf(
        StandardAttributeCatalog.CONCEPT_NAME to "activity",
        StandardAttributeCatalog.CONCEPT_INSTANCE to "concept_instance",
        StandardAttributeCatalog.TIME_TIMESTAMP to "timestamp",
        StandardAttributeCatalog.ORG_RESOURCE to "resource",
        StandardAttributeCatalog.ORG_ROLE to "org_role",
        StandardAttributeCatalog.ORG_GROUP to "org_group",
        StandardAttributeCatalog.LIFECYCLE_TRANSITION to "lifecycle",
        StandardAttributeCatalog.LIFECYCLE_STATE to "lifecycle_state",
        StandardAttributeCatalog.COST_TOTAL to "cost",
        StandardAttributeCatalog.COST_CURRENCY to "cost:currency",
        StandardAttributeCatalog.IDENTITY_ID to "eventId",
    )

    val tracePhysicalName: Map<String, String> = mapOf(
        StandardAttributeCatalog.CONCEPT_NAME to "caseId",
        StandardAttributeCatalog.COST_TOTAL to "cost:total",
        StandardAttributeCatalog.COST_CURRENCY to "cost:currency",
        StandardAttributeCatalog.IDENTITY_ID to "traceId",
    )

    val logPhysicalName: Map<String, String> = mapOf(
        StandardAttributeCatalog.CONCEPT_NAME to "name",
        StandardAttributeCatalog.COST_TOTAL to "cost_total",
        StandardAttributeCatalog.COST_CURRENCY to "cost_currency",
        StandardAttributeCatalog.IDENTITY_ID to "logId",
        StandardAttributeCatalog.XES_VERSION to "xes_version",
        StandardAttributeCatalog.XES_FEATURES to "xes_features",
    )

    fun physicalName(scope: Scope, canonicalXesName: String): String =
        when (scope) {
            Scope.EVENT -> eventPhysicalName[canonicalXesName] ?: canonicalXesName
            Scope.TRACE -> tracePhysicalName[canonicalXesName] ?: canonicalXesName
            Scope.LOG -> logPhysicalName[canonicalXesName] ?: canonicalXesName
        }

    fun inversePhysicalName(scope: Scope, physicalName: String): String? =
        when (scope) {
            Scope.EVENT -> inverseEventProps[physicalName]
            Scope.TRACE -> inverseTraceProps[physicalName]
            Scope.LOG -> inverseLogProps[physicalName]
        }

    val logNonAttributeKeys: Set<String> = setOf(
        "logId",
        "name",
        "createdAt",
        "updatedAt",
        LOG_CLASSIFIERS_PROPERTY,
        LOG_EXTENSIONS_PROPERTY,
        LOG_TRACE_GLOBALS_PROPERTY,
        LOG_EVENT_GLOBALS_PROPERTY,
        StandardAttributeCatalog.LIFECYCLE_MODEL,
    )

    fun logMetadataProperty(physicalName: String): LogMetadataProperty? =
        LogMetadataProperty.entries.firstOrNull { it.physicalName == physicalName }

    fun isLogStorageMetadata(physicalName: String): Boolean =
        physicalName == "createdAt" ||
            physicalName == "updatedAt" ||
            logMetadataProperty(physicalName) != null

    fun isStorageMetadata(scope: Scope, physicalName: String): Boolean =
        physicalName in storageMetadataKeys(scope)

    private fun storageMetadataKeys(scope: Scope): Set<String> =
        when (scope) {
            Scope.LOG -> logStorageMetadataKeys
            Scope.TRACE -> traceStorageMetadataKeys
            Scope.EVENT -> eventStorageMetadataKeys
        }

    private val logStorageMetadataKeys: Set<String> =
        setOf(
            "createdAt",
            "updatedAt",
            LOG_CLASSIFIERS_PROPERTY,
            LOG_EXTENSIONS_PROPERTY,
            LOG_TRACE_GLOBALS_PROPERTY,
            LOG_EVENT_GLOBALS_PROPERTY,
        )
    private val traceStorageMetadataKeys: Set<String> = setOf("createdAt", "updatedAt", "importOrder")
    private val eventStorageMetadataKeys: Set<String> = setOf("createdAt", "updatedAt", "importOrder")

    private val inverseEventProps: Map<String, String> = eventPhysicalName.entries.associate { (k, v) -> v to k }
    private val inverseTraceProps: Map<String, String> = tracePhysicalName.entries.associate { (k, v) -> v to k }
    private val inverseLogProps: Map<String, String> = logPhysicalName.entries.associate { (k, v) -> v to k }
}

enum class LogMetadataProperty(val physicalName: String) {
    CLASSIFIERS(Neo4jXesSchema.LOG_CLASSIFIERS_PROPERTY),
    EXTENSIONS(Neo4jXesSchema.LOG_EXTENSIONS_PROPERTY),
    TRACE_GLOBALS(Neo4jXesSchema.LOG_TRACE_GLOBALS_PROPERTY),
    EVENT_GLOBALS(Neo4jXesSchema.LOG_EVENT_GLOBALS_PROPERTY),
}
