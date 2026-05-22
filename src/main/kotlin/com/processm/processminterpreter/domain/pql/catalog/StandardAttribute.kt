package com.processm.processminterpreter.domain.pql.catalog

/**
 * One entry in the XES standard-attribute catalog.
 *
 * Example: (EVENT, "concept:name", STRING) means the event-scope standard attribute
 * whose canonical name is `concept:name` and whose PQL-level type is STRING.
 *
 * Shorthand lookups (e.g. `name` -> `concept:name`) happen via [StandardAttributeCatalog.lookup].
 */
data class StandardAttribute(
    val scope: Scope,
    val canonicalName: String,
    val type: Type,
)
