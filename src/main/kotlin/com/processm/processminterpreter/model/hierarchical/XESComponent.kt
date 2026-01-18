package com.processm.processminterpreter.model.hierarchical

import java.time.Instant
import java.time.ZonedDateTime
import java.util.*

/**
 * Base class for all XES components (Log, Trace, Event)
 *
 * Simplified version compatible with ProcessM hierarchical model
 * Used for query results and test compatibility
 */
abstract class XESComponent {
    /**
     * Custom attributes map (non-standard XES attributes)
     */
    val attributes: MutableMap<String, Any?> = mutableMapOf()

    /**
     * Extensions defined for this component
     */
    val extensions: MutableMap<String, Extension> = mutableMapOf()

    /**
     * Standard attribute: concept:name
     */
    var conceptName: String? = null

    /**
     * Standard attribute: identity:id
     */
    var identityId: UUID? = null

    /**
     * ProcessM compatibility: set custom attributes
     */
    fun setCustomAttributes(attrs: Map<String, Any?>) {
        attributes.putAll(attrs)
    }
}

/**
 * XES Extension definition
 */
data class Extension(
    val name: String,
    val prefix: String,
    val uri: String
)

/**
 * Base class for Trace and Event components
 */
abstract class TraceOrEventBase : XESComponent() {
    /**
     * Standard attribute: cost:currency
     */
    var costCurrency: String? = null

    /**
     * Standard attribute: cost:total
     */
    var costTotal: Double? = null
}

/**
 * Event classifier definition
 */
data class EventClassifier(
    val name: String,
    val keys: List<String>
)

/**
 * Global attribute definition
 */
data class GlobalAttribute(
    val scope: String,
    val attributes: Map<String, Any?>
)