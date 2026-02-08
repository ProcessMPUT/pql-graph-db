package com.processm.processminterpreter.pql.model

/**
 * Standard XES (eXtensible Event Stream) attributes and their mappings.
 *
 * ProcessM PQL supports shorthand names for standard XES attributes:
 * - e:name → concept:name (maps to 'activity' in Neo4j for events)
 * - e:timestamp → time:timestamp
 * - e:resource → org:resource
 * - etc.
 *
 * This object maintains mappings between shorthand names, full XES names,
 * and Neo4j property names for all three scopes (Log, Trace, Event).
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 * XES Standard: http://www.xes-standard.org/
 */
object StandardAttributes {

    // ========================================
    // XES Standard Attribute Names (extension:attribute format)
    // ========================================

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

    // ========================================
    // Shorthand Mappings per Scope
    // ========================================
    // Format: shorthand -> XES standard name

    /**
     * EVENT scope standard attributes
     * Maps shorthand (e.g., "name") to XES standard name (e.g., "concept:name")
     */
    val EVENT_SHORTHANDS = mapOf(
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
        "id" to CONCEPT_INSTANCE, // Special: e:id maps to concept:instance
        // ProcessM additional attributes
        "identity:id" to IDENTITY_ID, // Full XES name also works as shorthand
        "db:id" to DB_ID, // Database internal ID
    )

    /**
     * TRACE scope standard attributes
     */
    val TRACE_SHORTHANDS = mapOf(
        "name" to CONCEPT_NAME,
        "id" to CONCEPT_INSTANCE,
        "total" to COST_TOTAL,
        "currency" to COST_CURRENCY,
        // ProcessM additional attributes
        "identity:id" to IDENTITY_ID, // Full XES name also works as shorthand
        "db:id" to DB_ID, // Database internal ID
    )

    /**
     * LOG scope standard attributes
     */
    val LOG_SHORTHANDS = mapOf(
        "name" to CONCEPT_NAME,
        "id" to CONCEPT_INSTANCE,
        "version" to XES_VERSION,
        "features" to XES_FEATURES,
        // ProcessM additional attributes
        "identity:id" to IDENTITY_ID, // Full XES name also works as shorthand
        "db:id" to DB_ID, // Database internal ID
        "lifecycle:model" to LIFECYCLE_MODEL, // Lifecycle model for the log
    )

    // ========================================
    // Neo4j Property Mappings
    // ========================================
    // Maps XES standard names to Neo4j property names
    // (Different per scope because same XES attribute maps to different properties)

    /**
     * EVENT scope: XES standard name → Neo4j property name
     */
    val EVENT_NEO4J_MAPPINGS = mapOf(
        CONCEPT_NAME to "activity", // concept:name → activity
        CONCEPT_INSTANCE to "eventId", // concept:instance → eventId
        TIME_TIMESTAMP to "timestamp", // time:timestamp → timestamp
        ORG_RESOURCE to "resource", // org:resource → resource
        ORG_GROUP to "org_group", // org:group → org_group
        ORG_ROLE to "org_role", // org:role → org_role
        LIFECYCLE_TRANSITION to "lifecycle_transition",
        LIFECYCLE_STATE to "lifecycle_state",
        COST_TOTAL to "cost_total", // cost:total → cost_total
        COST_CURRENCY to "cost_currency", // cost:currency → cost_currency
        // ProcessM additional attributes
        IDENTITY_ID to "identityId", // identity:id → identityId (XES UUID)
        DB_ID to "dbId", // db:id → dbId (internal database ID)
    )

    /**
     * TRACE scope: XES standard name → Neo4j property name
     */
    val TRACE_NEO4J_MAPPINGS = mapOf(
        CONCEPT_NAME to "caseId", // concept:name → caseId (trace name is case ID)
        CONCEPT_INSTANCE to "traceId", // concept:instance → traceId
        COST_TOTAL to "cost_total",
        COST_CURRENCY to "cost_currency",
        // ProcessM additional attributes
        IDENTITY_ID to "identityId", // identity:id → identityId (XES UUID)
        DB_ID to "dbId", // db:id → dbId (internal database ID)
    )

    /**
     * LOG scope: XES standard name → Neo4j property name
     */
    val LOG_NEO4J_MAPPINGS = mapOf(
        CONCEPT_NAME to "name", // concept:name → name
        CONCEPT_INSTANCE to "logId", // concept:instance → logId
        XES_VERSION to "version",
        XES_FEATURES to "features",
        // ProcessM additional attributes
        IDENTITY_ID to "identityId", // identity:id → identityId (XES UUID)
        DB_ID to "dbId", // db:id → dbId (internal database ID)
        LIFECYCLE_MODEL to "lifecycleModel", // lifecycle:model → lifecycleModel
    )

    // ========================================
    // Type Mappings
    // ========================================
    // Maps XES standard names to their data types

    val ATTRIBUTE_TYPES = mapOf(
        CONCEPT_NAME to Type.STRING,
        CONCEPT_INSTANCE to Type.UUID,
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
        // ProcessM additional attributes
        IDENTITY_ID to Type.UUID,
        DB_ID to Type.NUMBER, // Database internal ID is typically a number
    )

    // ========================================
    // Helper Functions
    // ========================================

    /**
     * Check if a shorthand name is a standard attribute for the given scope.
     *
     * @param scope the scope (LOG, TRACE, or EVENT)
     * @param shorthand the shorthand name (e.g., "name", "timestamp")
     * @return true if it's a standard attribute
     */
    fun isStandard(scope: Scope, shorthand: String): Boolean {
        return when (scope) {
            Scope.Event -> EVENT_SHORTHANDS.containsKey(shorthand)
            Scope.Trace -> TRACE_SHORTHANDS.containsKey(shorthand)
            Scope.Log -> LOG_SHORTHANDS.containsKey(shorthand)
        }
    }

    /**
     * Get the XES standard name for a shorthand.
     *
     * Examples:
     * - getStandardName(EVENT, "name") → "concept:name"
     * - getStandardName(EVENT, "timestamp") → "time:timestamp"
     *
     * @param scope the scope
     * @param shorthand the shorthand name
     * @return the XES standard name, or null if not a standard attribute
     */
    fun getStandardName(scope: Scope, shorthand: String): String? {
        return when (scope) {
            Scope.Event -> EVENT_SHORTHANDS[shorthand]
            Scope.Trace -> TRACE_SHORTHANDS[shorthand]
            Scope.Log -> LOG_SHORTHANDS[shorthand]
        }
    }

    /**
     * Get the Neo4j property name for a standard attribute.
     *
     * Examples:
     * - getNeo4jProperty(EVENT, "concept:name") → "activity"
     * - getNeo4jProperty(TRACE, "concept:name") → "caseId"
     * - getNeo4jProperty(LOG, "concept:name") → "name"
     *
     * @param scope the scope
     * @param standardName the XES standard name (e.g., "concept:name")
     * @return the Neo4j property name, or null if not found
     */
    fun getNeo4jProperty(scope: Scope, standardName: String): String? {
        return when (scope) {
            Scope.Event -> EVENT_NEO4J_MAPPINGS[standardName]
            Scope.Trace -> TRACE_NEO4J_MAPPINGS[standardName]
            Scope.Log -> LOG_NEO4J_MAPPINGS[standardName]
        }
    }

    /**
     * Get the Neo4j property name directly from a shorthand.
     *
     * This is a convenience method that combines getStandardName + getNeo4jProperty.
     *
     * Examples:
     * - getNeo4jPropertyFromShorthand(EVENT, "name") → "activity"
     * - getNeo4jPropertyFromShorthand(TRACE, "name") → "caseId"
     *
     * @param scope the scope
     * @param shorthand the shorthand name
     * @return the Neo4j property name, or null if not a standard attribute
     */
    fun getNeo4jPropertyFromShorthand(scope: Scope, shorthand: String): String? {
        val standardName = getStandardName(scope, shorthand) ?: return null
        return getNeo4jProperty(scope, standardName)
    }

    /**
     * Get the data type for a standard attribute.
     *
     * @param standardName the XES standard name
     * @return the Type, or Type.UNKNOWN if not found
     */
    fun getType(standardName: String): Type {
        return ATTRIBUTE_TYPES[standardName] ?: Type.UNKNOWN
    }

    /**
     * Check if an attribute name is a classifier.
     * Classifiers start with "c:" or "classifier:"
     *
     * Examples:
     * - isClassifier("c:businesscase") → true
     * - isClassifier("classifier:activity_resource") → true
     * - isClassifier("name") → false
     *
     * @param attributeName the attribute name to check
     * @return true if it's a classifier
     */
    fun isClassifier(attributeName: String): Boolean {
        return attributeName.startsWith("c:") || attributeName.startsWith("classifier:")
    }
}
