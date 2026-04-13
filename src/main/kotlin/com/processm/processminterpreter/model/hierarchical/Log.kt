package com.processm.processminterpreter.model.hierarchical

/**
 * Log component - represents a complete event log
 *
 * Compatible with ProcessM Log model for test compatibility
 */
class Log : XESComponent() {
    /**
     * Sequence of traces in this log
     */
    var traces: Sequence<Trace> = emptySequence()

    /**
     * Standard attribute: lifecycle:model
     */
    var lifecycleModel: String? = null

    /**
     * Event classifiers defined for this log
     */
    val eventClassifiers: MutableList<EventClassifier> = mutableListOf()

    /**
     * Global attributes for events
     */
    val eventGlobals: MutableList<GlobalAttribute> = mutableListOf()

    /**
     * Global attributes for traces
     */
    val traceGlobals: MutableList<GlobalAttribute> = mutableListOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Log) return false
        return attributes == other.attributes && traces.toList() == other.traces.toList()
    }

    override fun hashCode(): Int {
        var result = attributes.hashCode()
        result = 31 * result + traces.toList().hashCode()
        return result
    }

    override fun toString(): String = "Log(name=$conceptName, traces=${traces.count()})"
}

/**
 * Type alias for compatibility - represents a stream of XES components
 */
typealias XESInputStream = Sequence<XESComponent>

/**
 * Convert a single log to a flat sequence of components
 */
fun Log.toFlatSequence(): XESInputStream =
    sequence {
        yield(this@toFlatSequence)
        traces.forEach { trace ->
            yield(trace)
            trace.events.forEach { event ->
                yield(event)
            }
        }
    }

/**
 * Convert a sequence of logs to a flat sequence of components
 */
fun Sequence<Log>.toFlatSequence(): XESInputStream =
    sequence {
        this@toFlatSequence.forEach { log ->
            yieldAll(log.toFlatSequence())
        }
    }
