package com.processm.processminterpreter.model

import java.time.LocalDateTime

/**
 * Neo4j Node representing a process event (XES event)
 *
 * An event represents a single activity execution within a process trace.
 * Events are the atomic units of process execution.
 */
data class EventNode(
    /**
     * Unique identifier for the event
     */
    val eventId: String,
    /**
     * Activity name (concept:name from XES event)
     * This is the name of the activity/task being executed
     */
    val activity: String,
    /**
     * Timestamp when the event occurred (time:timestamp from XES)
     */
    val timestamp: LocalDateTime,
    /**
     * Resource that executed the event (org:resource from XES)
     * Can be a person, system, or organizational unit
     */
    val resource: String? = null,
    /**
     * Lifecycle transition (lifecycle:transition from XES)
     * Common values: start, complete, suspend, resume, etc.
     */
    val lifecycle: String? = null,
    /**
     * Cost associated with the event (cost:total from XES)
     */
    val cost: Double? = null,
    /**
     * Timestamp when the event was created/imported
     */
    val createdAt: LocalDateTime = LocalDateTime.now(),
    /**
     * Additional attributes from XES event (stored as JSON-like map)
     * Can contain domain-specific attributes
     */
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
    fun getOrgResource(): String? = getAttribute<String>("org:resource")

    /**
     * Get lifecycle:transition attribute (standard XES attribute)
     */
    fun getLifecycleTransition(): String? = getAttribute<String>("lifecycle:transition")

    /**
     * Get time:timestamp attribute (standard XES attribute)
     */
    fun getTimeTimestamp(): LocalDateTime? = getAttribute<LocalDateTime>("time:timestamp")

    /**
     * Get cost:total attribute (standard XES attribute)
     */
    fun getCostTotal(): Double? = getAttribute<Double>("cost:total")

    /**
     * Check if this is a start event
     */
    fun isStartEvent(): Boolean = lifecycle?.lowercase() == "start"

    /**
     * Check if this is a complete event
     */
    fun isCompleteEvent(): Boolean = lifecycle?.lowercase() == "complete"

    /**
     * Check if this event has a specific activity name
     */
    fun hasActivity(activityName: String): Boolean = activity.equals(activityName, ignoreCase = true)

    /**
     * Check if this event was executed by a specific resource
     */
    fun wasExecutedBy(resourceName: String): Boolean =
        resource?.equals(resourceName, ignoreCase = true) == true ||
            getOrgResource()?.equals(resourceName, ignoreCase = true) == true
}
