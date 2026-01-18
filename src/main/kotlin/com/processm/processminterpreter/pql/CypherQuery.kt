package com.processm.processminterpreter.pql

/**
 * Data class representing a Cypher query with parameters
 */
data class CypherQuery(
    val query: String,
    val parameters: Map<String, Any> = emptyMap(),

    /**
     * Hierarchical limits to be applied after reconstruction
     * Format: [logLimit, traceLimit, eventLimit]
     * Example: [1, 3, 5] means max 1 log, 3 traces per log, 5 events per trace
     * null means no limit at that level
     */
    val hierarchicalLimits: Map<String, Int?> = emptyMap(),
) {
    override fun toString(): String {
        return "CypherQuery(query='$query', parameters=$parameters, hierarchicalLimits=$hierarchicalLimits)"
    }
}
