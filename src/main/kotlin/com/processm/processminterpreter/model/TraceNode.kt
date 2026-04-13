package com.processm.processminterpreter.model

import java.time.LocalDateTime

/**
 * Neo4j Node representing a process trace (XES trace)
 *
 * A trace represents a single case/instance of a business process
 * and contains a sequence of events.
 */
data class TraceNode(
    // Unique identifier for the trace within the log
    val traceId: String,
    // Case identifier (concept:name from XES trace) — business identifier for the process instance
    val caseId: String,
    // Timestamp when the trace was created/imported
    val createdAt: LocalDateTime = LocalDateTime.now(),
    // Additional attributes from XES trace (stored as JSON-like map)
    val attributes: Map<String, Any> = emptyMap(),
) {
    /**
     * Get attribute value by key with type casting
     */
    inline fun <reified T> getAttribute(key: String): T? = attributes[key] as? T

    /**
     * Get concept:name attribute (standard XES attribute)
     */
    fun getConceptName(): String? = getAttribute<String>("concept:name")

    /**
     * Get org:resource attribute (standard XES attribute)
     */
    fun getResource(): String? = getAttribute<String>("org:resource")
}
