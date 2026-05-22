package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope

/**
 * A parameterized Cypher query ready for dispatch to Neo4j.
 *
 *  - [cypher] is the query string with `$paramN` placeholders
 *  - [parameters] is the bound-parameter map (populated by [CypherCodegen] so user input is never inlined)
 *  - [columnAliases] maps output column name to the original PQL expression + scope
 *    used by `HierarchyReconstructor` to rehydrate the projection into a hierarchical `XesLog`.
 */
data class CypherQuery(
    val cypher: String,
    val parameters: Map<String, Any?>,
    val columnAliases: Map<String, ColumnAlias>,
)

/** Metadata about a single projected column — carries just enough context for row→XES reconstruction.
 *
 * [synthetic] columns are scaffolding emitted by the codegen (e.g. `log.logId AS _log_id_`
 * to force Cypher implicit grouping per-trace for event-scope aggregations). The reconstructor
 * uses synthetic columns as grouping keys but does NOT surface them as projected attributes.
 */
data class ColumnAlias(
    val pqlExpression: String,
    val scope: Scope,
    val synthetic: Boolean = false,
    val materializeNull: Boolean = false,
)
