package com.processm.processminterpreter.model.hierarchical

/**
 * Trace component - represents a single case/process instance
 *
 * Compatible with ProcessM Trace model for test compatibility
 */
class Trace : TraceOrEventBase() {
    /**
     * Sequence of events in this trace
     */
    var events: Sequence<Event> = emptySequence()

    /**
     * Check if this trace contains events as a stream
     * (for compatibility with ProcessM)
     */
    val isEventStream: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Trace) return false
        return attributes == other.attributes && events.toList() == other.events.toList()
    }

    override fun hashCode(): Int {
        var result = attributes.hashCode()
        result = 31 * result + events.toList().hashCode()
        return result
    }

    override fun toString(): String {
        return "Trace(name=$conceptName, events=${events.count()})"
    }
}