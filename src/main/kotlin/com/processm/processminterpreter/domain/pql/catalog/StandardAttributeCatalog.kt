package com.processm.processminterpreter.domain.pql.catalog

/**
 * Stateless catalog of XES IEEE 1849-2016 standard attributes.
 *
 * Responsibilities:
 *  - expand shorthand references (`name` at EVENT scope -> `concept:name`)
 *  - accept canonical XES names directly (`concept:name` at EVENT scope)
 *  - report the PQL-level [Type] of a standard attribute
 *  - recognize classifier-prefixed names (`c:*`, `classifier:*`)
 *
 * Physical Neo4j property mapping is NOT this catalog's concern — that lives
 * in `infrastructure/persistence/neo4j/query/cypher/PhysicalAttributeMapper`. This catalog
 * is pure domain knowledge, safe to use in tests without any adapter wired up.
 *
 * References:
 *  - ProcessM PQL spec: https://github.com/ProcessMPUT/processm/blob/master/docs/pql.md
 *  - XES standard: http://www.xes-standard.org/
 */
object StandardAttributeCatalog {

    // Canonical XES names per IEEE 1849-2016.
    const val CONCEPT_NAME = "concept:name"
    const val CONCEPT_INSTANCE = "concept:instance"
    const val TIME_TIMESTAMP = "time:timestamp"
    const val ORG_RESOURCE = "org:resource"
    const val ORG_GROUP = "org:group"
    const val ORG_ROLE = "org:role"
    const val LIFECYCLE_TRANSITION = "lifecycle:transition"
    const val LIFECYCLE_STATE = "lifecycle:state"
    const val LIFECYCLE_MODEL = "lifecycle:model"
    const val COST_TOTAL = "cost:total"
    const val COST_CURRENCY = "cost:currency"
    const val IDENTITY_ID = "identity:id"
    const val DB_ID = "db:id"
    const val XES_VERSION = "xes:version"
    const val XES_FEATURES = "xes:features"

    private val eventShorthands: Map<String, String> = mapOf(
        "name" to CONCEPT_NAME,
        "instance" to CONCEPT_INSTANCE,
        "timestamp" to TIME_TIMESTAMP,
        "resource" to ORG_RESOURCE,
        "group" to ORG_GROUP,
        "role" to ORG_ROLE,
        "transition" to LIFECYCLE_TRANSITION,
        "state" to LIFECYCLE_STATE,
        "total" to COST_TOTAL,
        "currency" to COST_CURRENCY,
        "id" to CONCEPT_INSTANCE,
        "identity:id" to IDENTITY_ID,
        "db:id" to DB_ID,
    )

    private val traceShorthands: Map<String, String> = mapOf(
        "name" to CONCEPT_NAME,
        "id" to CONCEPT_INSTANCE,
        "total" to COST_TOTAL,
        "currency" to COST_CURRENCY,
        "identity:id" to IDENTITY_ID,
        "db:id" to DB_ID,
    )

    private val logShorthands: Map<String, String> = mapOf(
        "name" to CONCEPT_NAME,
        "id" to CONCEPT_INSTANCE,
        // `logId` is the Neo4j identity column written by XESLoader (`log.logId`).
        // PhysicalAttributeMapper maps IDENTITY_ID at LOG scope back to "logId",
        // so `l:logId = '...'` round-trips through the normal attribute pipeline
        // and matches the same property the source-pin MATCH `(log:Log {logId:$logId})`
        // uses. Previously this pointed at CONCEPT_INSTANCE, which is an unrelated
        // (and usually null) property — filters on `l:logId` silently matched
        // nothing even though the MATCH pinned the correct log.
        "logId" to IDENTITY_ID,
        "version" to XES_VERSION,
        "features" to XES_FEATURES,
        "identity:id" to IDENTITY_ID,
        "db:id" to DB_ID,
        "lifecycle:model" to LIFECYCLE_MODEL,
    )

    private val typeByCanonicalName: Map<String, Type> = mapOf(
        CONCEPT_NAME to Type.STRING,
        CONCEPT_INSTANCE to Type.ID,
        TIME_TIMESTAMP to Type.DATETIME,
        ORG_RESOURCE to Type.STRING,
        ORG_GROUP to Type.STRING,
        ORG_ROLE to Type.STRING,
        LIFECYCLE_TRANSITION to Type.STRING,
        LIFECYCLE_STATE to Type.STRING,
        LIFECYCLE_MODEL to Type.STRING,
        COST_TOTAL to Type.NUMBER,
        COST_CURRENCY to Type.STRING,
        XES_VERSION to Type.STRING,
        XES_FEATURES to Type.STRING,
        IDENTITY_ID to Type.ID,
        DB_ID to Type.NUMBER,
    )

    private fun shorthandsFor(scope: Scope): Map<String, String> = when (scope) {
        Scope.EVENT -> eventShorthands
        Scope.TRACE -> traceShorthands
        Scope.LOG -> logShorthands
    }

    /**
     * Look up a [name] (shorthand or canonical XES) at [scope].
     * Returns the [StandardAttribute] if this name is a standard attribute at that scope, null otherwise.
     */
    fun lookup(scope: Scope, name: String): StandardAttribute? {
        val shorthands = shorthandsFor(scope)
        val canonical = shorthands[name]
            ?: name.takeIf { it in shorthands.values && it in typeByCanonicalName.keys }
            ?: return null
        return StandardAttribute(
            scope = scope,
            canonicalName = canonical,
            type = typeByCanonicalName[canonical] ?: Type.UNKNOWN,
        )
    }

    /** True iff [name] is a classifier-prefixed reference (not dependent on scope). */
    fun isClassifier(name: String): Boolean =
        name.startsWith("c:") || name.startsWith("classifier:")
}
