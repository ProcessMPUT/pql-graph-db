package com.processm.processminterpreter.neo4j.xes.schema

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog

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

    /**
     * Generated structural node keys used by the writer's MATCH joins and by the
     * uniqueness constraints. They are storage concerns only: they carry no XES
     * meaning and must never be confused with the standard `identity:id`, which
     * is a source UUID the importer has to preserve verbatim at every scope.
     */
    const val TRACE_ID_PROPERTY = "traceId"
    const val TRACE_PARENT_LOG_ID_PROPERTY = "parentLogId"
    const val EVENT_ID_PROPERTY = "eventId"

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
        StandardAttributeCatalog.IDENTITY_ID to StandardAttributeCatalog.IDENTITY_ID,
    )

    val tracePhysicalName: Map<String, String> = mapOf(
        StandardAttributeCatalog.CONCEPT_NAME to "caseId",
        StandardAttributeCatalog.COST_TOTAL to "cost:total",
        StandardAttributeCatalog.COST_CURRENCY to "cost:currency",
        StandardAttributeCatalog.IDENTITY_ID to StandardAttributeCatalog.IDENTITY_ID,
    )

    val logPhysicalName: Map<String, String> = mapOf(
        StandardAttributeCatalog.CONCEPT_NAME to "name",
        StandardAttributeCatalog.COST_TOTAL to "cost_total",
        StandardAttributeCatalog.COST_CURRENCY to "cost_currency",
        StandardAttributeCatalog.IDENTITY_ID to StandardAttributeCatalog.IDENTITY_ID,
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
        physicalName in storageMetadataKeys(scope) ||
            physicalName.startsWith(PROCESSM_INTERNAL_ATTRIBUTE_PREFIX)

    /**
     * Node properties that the batch writer sets explicitly in its `CREATE`
     * (Neo4jXesBatchWriter CREATE_TRACES/CREATE_EVENTS + Neo4jLogNodeWrite).
     * These MUST be stripped from any `SET node += attributes` payload: a custom
     * attribute could be named after any of them, which would overwrite the
     * generated structural id and silently break the writer's own MATCH joins,
     * dropping every event/FOLLOWS edge of the affected node.
     *
     * Storing a standard attribute under one of these names would have the same
     * effect, which is why [tracePhysicalName]/[eventPhysicalName] keep
     * `identity:id` under its own XES name instead of folding it onto
     * `traceId`/`eventId` (doing so silently discarded the source UUID).
     */
    fun writerManagedProperties(scope: Scope): Set<String> = when (scope) {
        Scope.LOG -> setOf("logId", "name", "createdAt", "updatedAt") + logStorageMetadataKeys
        Scope.TRACE -> traceWriterManaged
        Scope.EVENT -> eventWriterManaged
    }

    private val traceWriterManaged: Set<String> =
        setOf(TRACE_ID_PROPERTY, TRACE_PARENT_LOG_ID_PROPERTY, "caseId", "createdAt", "importOrder")
    private val eventWriterManaged: Set<String> =
        setOf(EVENT_ID_PROPERTY, "activity", "timestamp", "resource", "lifecycle", "cost", "createdAt", "importOrder")

    private fun storageMetadataKeys(scope: Scope): Set<String> =
        when (scope) {
            Scope.LOG -> logStorageMetadataKeys
            Scope.TRACE -> traceStorageMetadataKeys
            Scope.EVENT -> eventStorageMetadataKeys
        }

    private val logStorageMetadataKeys: Set<String> =
        setOf(
            "logId",
            "createdAt",
            "updatedAt",
            LOG_CLASSIFIERS_PROPERTY,
            LOG_EXTENSIONS_PROPERTY,
            LOG_TRACE_GLOBALS_PROPERTY,
            LOG_EVENT_GLOBALS_PROPERTY,
        )
    /*
     * The generated structural ids have no inverse XES name, so they must be
     * declared storage metadata or reads would surface them as custom
     * attributes named `traceId`/`eventId`.
     */
    private val traceStorageMetadataKeys: Set<String> =
        setOf(TRACE_ID_PROPERTY, TRACE_PARENT_LOG_ID_PROPERTY, "createdAt", "updatedAt", "importOrder")
    private val eventStorageMetadataKeys: Set<String> =
        setOf(EVENT_ID_PROPERTY, "createdAt", "updatedAt", "importOrder")

    /*
     * ProcessM-internal helper attributes can appear in imported logs and are useful
     * for storage/query ordering, but they are not XES attributes and must not be
     * materialized in PQL/XES results.
     */
    private const val PROCESSM_INTERNAL_ATTRIBUTE_PREFIX = "processm"

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
