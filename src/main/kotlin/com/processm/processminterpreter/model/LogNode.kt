package com.processm.processminterpreter.model

import java.time.LocalDateTime

/**
 * Neo4j Node representing a process log (XES log)
 *
 * A log contains multiple traces and represents a complete event log
 * from a business process execution.
 */
data class LogNode(
    // Unique identifier for the log (identity:id from XES)
    val logId: String,
    // Human-readable name of the log
    val name: String,
    // Timestamp when the log was created/imported
    val createdAt: LocalDateTime = LocalDateTime.now(),
    // Timestamp when the log was last modified
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    // Additional attributes from XES log (stored as JSON-like map)
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
     * Get lifecycle:model attribute (standard XES attribute)
     */
    fun getLifecycleModel(): String? = getAttribute<String>("lifecycle:model")
}
