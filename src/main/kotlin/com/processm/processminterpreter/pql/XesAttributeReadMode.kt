package com.processm.processminterpreter.pql

/**
 * Controls how much XES attribute data is hydrated from Neo4j.
 *
 * ProcessM's JSON endpoint deliberately reads only top-level scalar values,
 * while XES/ZIP export must retain the complete nested attribute tree.
 */
enum class XesAttributeReadMode {
    FULL_XES,
    PROCESSM_JSON,
}
