package com.processm.processminterpreter.model.hierarchical

import java.time.Instant

/**
 * Event component - represents a single activity execution
 *
 * Compatible with ProcessM Event model for test compatibility
 */
class Event : TraceOrEventBase() {
    /**
     * Standard attribute: concept:instance
     */
    var conceptInstance: String? = null

    /**
     * Standard attribute: lifecycle:transition
     */
    var lifecycleTransition: String? = null

    /**
     * Standard attribute: lifecycle:state
     */
    var lifecycleState: String? = null

    /**
     * Standard attribute: org:resource
     */
    var orgResource: String? = null

    /**
     * Standard attribute: org:role
     */
    var orgRole: String? = null

    /**
     * Standard attribute: org:group
     */
    var orgGroup: String? = null

    /**
     * Standard attribute: time:timestamp
     */
    var timeTimestamp: Instant? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Event) return false
        return attributes == other.attributes
    }

    override fun hashCode(): Int = attributes.hashCode()

    override fun toString(): String = "Event(name=$conceptName, timestamp=$timeTimestamp, resource=$orgResource)"
}
