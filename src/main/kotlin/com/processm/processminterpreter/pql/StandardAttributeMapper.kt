package com.processm.processminterpreter.pql

/**
 * Maps ProcessM PQL shorthand attribute names to XES standard attributes and Neo4j properties.
 *
 * Based on ProcessM's standard attribute mappings from:
 * https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/main/kotlin/processm/core/querylanguage/Attribute.kt
 */
object StandardAttributeMapper {

    /**
     * Scope levels in ProcessM PQL hierarchy
     */
    enum class Scope {
        LOG,
        TRACE,
        EVENT,
        ;

        /**
         * Get the scope one level up (for hoisting with ^)
         */
        val upper: Scope?
            get() =
                when (this) {
                    EVENT -> TRACE
                    TRACE -> LOG
                    LOG -> null
                }

        /**
         * Get the scope one level down
         */
        val lower: Scope?
            get() =
                when (this) {
                    LOG -> TRACE
                    TRACE -> EVENT
                    EVENT -> null
                }

        companion object {
            /**
             * Parse scope from PQL prefix: l/log/t/trace/e/event
             */
            fun parse(prefix: String?): Scope {
                return when (prefix?.lowercase()) {
                    "l", "log" -> LOG
                    "t", "trace" -> TRACE
                    "e", "event", null -> EVENT // Default to EVENT
                    else -> throw IllegalArgumentException("Unknown scope: $prefix")
                }
            }
        }
    }

    /**
     * Standard attribute mappings per scope
     * Format: "shorthand" to Pair("xes:attribute", "neo4j_property")
     *
     * Based on ProcessM mappings but adapted for our Neo4j schema:
     * - XES concept:name → Neo4j activity (for events)
     * - XES time:timestamp → Neo4j timestamp
     * - XES org:* → Neo4j org_* (sanitized)
     * - XES cost:* → Neo4j cost_*
     */
    private val standardMappings: Map<Scope, Map<String, Pair<String, String>>> =
        mapOf(
            Scope.LOG to
                mapOf(
                    "name" to ("concept:name" to "name"),
                    "id" to ("identity:id" to "logId"),
                    "version" to ("xes:version" to "xes_version"),
                    "features" to ("xes:features" to "xes_features"),
                    "currency" to ("cost:currency" to "cost_currency"),
                    "total" to ("cost:total" to "cost_total"),
                ),
            Scope.TRACE to
                mapOf(
                    "name" to ("concept:name" to "caseId"), // Trace name is typically caseId
                    "currency" to ("cost:currency" to "cost_currency"),
                    "total" to ("cost:total" to "cost_total"),
                    "id" to ("identity:id" to "traceId"),
                ),
            Scope.EVENT to
                mapOf(
                    "name" to ("concept:name" to "activity"), // Event name is activity
                    "instance" to ("concept:instance" to "concept_instance"),
                    "currency" to ("cost:currency" to "cost_currency"),
                    "total" to ("cost:total" to "cost_total"),
                    "id" to ("identity:id" to "eventId"),
                    "transition" to ("lifecycle:transition" to "lifecycle"),
                    "state" to ("lifecycle:state" to "lifecycle_state"),
                    "resource" to ("org:resource" to "resource"),
                    "role" to ("org:role" to "org_role"),
                    "group" to ("org:group" to "org_group"),
                    "timestamp" to ("time:timestamp" to "timestamp"),
                ),
        )

    /**
     * Check if a field name is a standard shorthand for the given scope
     */
    fun isStandardAttribute(
        fieldName: String,
        scope: Scope,
    ): Boolean {
        return standardMappings[scope]?.containsKey(fieldName.lowercase()) == true
    }

    /**
     * Get the XES attribute name for a shorthand
     * Example: "name" in EVENT scope → "concept:name"
     */
    fun getXESAttributeName(
        fieldName: String,
        scope: Scope,
    ): String? {
        return standardMappings[scope]?.get(fieldName.lowercase())?.first
    }

    /**
     * Get the Neo4j property name for a shorthand
     * Example: "name" in EVENT scope → "activity"
     */
    fun getNeo4jPropertyName(
        fieldName: String,
        scope: Scope,
    ): String? {
        return standardMappings[scope]?.get(fieldName.lowercase())?.second
    }

    /**
     * Translate a field reference to Neo4j property
     * Handles both standard shorthands and full XES names
     *
     * @param fieldName The field name from PQL (e.g., "name", "activity", "org:group")
     * @param scope The scope context
     * @return Neo4j property name (e.g., "activity", "org_group")
     */
    fun translateToNeo4jProperty(
        fieldName: String,
        scope: Scope,
    ): String {
        // Check if it's a standard shorthand
        val neo4jProperty = getNeo4jPropertyName(fieldName, scope)
        if (neo4jProperty != null) {
            return neo4jProperty
        }

        // Not a standard shorthand - check if it's already a full XES name (e.g., "concept:name")
        if (fieldName.contains(":")) {
            // It's an XES attribute name - sanitize it for Neo4j (replace : with _)
            return fieldName.replace(":", "_").replace(".", "_")
        }

        // It's a custom attribute - use as-is
        return fieldName
    }

    /**
     * Apply hoisting to a scope based on hoisting prefix
     *
     * @param scope The base scope
     * @param hoistingPrefix The hoisting markers (e.g., "^", "^^")
     * @return The hoisted scope
     * @throws IllegalArgumentException if hoisting beyond LOG
     */
    fun applyHoisting(
        scope: Scope,
        hoistingPrefix: String,
    ): Scope {
        var currentScope = scope
        for (c in hoistingPrefix) {
            if (c == '^') {
                currentScope = currentScope.upper
                    ?: throw IllegalArgumentException("Cannot hoist beyond LOG scope")
            }
        }
        return currentScope
    }
}
