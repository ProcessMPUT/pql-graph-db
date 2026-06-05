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
 * Physical Neo4j property mapping is NOT this catalog's concern - that lives
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

    /**
     * Per-scope index of accepted attribute references.
     *
     * Each entry maps an accepted input string (shorthand like `name` or canonical
     * XES name like `concept:name`) to the [StandardAttribute] that input resolves to.
     *
     * A canonical name appears as its own key only at scopes where it is a valid
     * standard attribute - that's how `e:lifecycle:model` correctly returns null
     * (events do not have a lifecycle model) while `l:lifecycle:model` succeeds.
     */
    private val eventCatalog: Map<String, StandardAttribute> = buildScopeCatalog(
        Scope.EVENT,
        shorthands = mapOf(
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
        ),
    )

    private val traceCatalog: Map<String, StandardAttribute> = buildScopeCatalog(
        Scope.TRACE,
        shorthands = mapOf(
            "name" to CONCEPT_NAME,
            "id" to CONCEPT_INSTANCE,
            "total" to COST_TOTAL,
            "currency" to COST_CURRENCY,
            "identity:id" to IDENTITY_ID,
            "db:id" to DB_ID,
        ),
    )

    private val logCatalog: Map<String, StandardAttribute> = buildScopeCatalog(
        Scope.LOG,
        shorthands = mapOf(
            "name" to CONCEPT_NAME,
            "id" to CONCEPT_INSTANCE,
            "version" to XES_VERSION,
            "features" to XES_FEATURES,
            "identity:id" to IDENTITY_ID,
            "db:id" to DB_ID,
            "lifecycle:model" to LIFECYCLE_MODEL,
        ),
    )

    /**
     * Look up a [name] (shorthand or canonical XES) at [scope].
     * Returns the [StandardAttribute] when [name] is a standard attribute at that scope,
     * null otherwise.
     */
    fun lookup(scope: Scope, name: String): StandardAttribute? =
        catalogFor(scope)[name]

    /** True iff [name] is a classifier-prefixed reference (not dependent on scope). */
    fun isClassifier(name: String): Boolean =
        name.startsWith("c:") || name.startsWith("classifier:")

    private fun catalogFor(scope: Scope): Map<String, StandardAttribute> = when (scope) {
        Scope.EVENT -> eventCatalog
        Scope.TRACE -> traceCatalog
        Scope.LOG -> logCatalog
    }

    /**
     * Build a flat (input -> StandardAttribute) map: every shorthand entry becomes a key,
     * and every canonical name in the shorthand's range also becomes a key under itself.
     */
    private fun buildScopeCatalog(
        scope: Scope,
        shorthands: Map<String, String>,
    ): Map<String, StandardAttribute> {
        val canonicalNames = shorthands.values.toSet()
        val entries = mutableMapOf<String, StandardAttribute>()
        shorthands.forEach { (input, canonical) ->
            entries[input] = standardAttribute(scope, canonical)
        }
        canonicalNames.forEach { canonical ->
            entries.putIfAbsent(canonical, standardAttribute(scope, canonical))
        }
        return entries
    }

    private fun standardAttribute(scope: Scope, canonicalName: String): StandardAttribute =
        StandardAttribute(
            scope = scope,
            canonicalName = canonicalName,
            type = typeByCanonicalName[canonicalName] ?: Type.UNKNOWN,
        )
}
