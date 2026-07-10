package com.processm.processminterpreter.neo4j.xes.mapping

import java.time.LocalDateTime

data class Neo4jXesImportBatch(
    val logId: String,
    val logName: String,
    val importedAt: LocalDateTime,
    val logAttributes: Map<String, Any>,
    val classifiers: String?,
    val traceGlobals: String?,
    val eventGlobals: String?,
    val extensions: String?,
    val traceCount: Int,
    val eventCount: Int,
    /**
     * Lazily built row maps: each batch is materialized when the writer reaches
     * it and becomes garbage right after its transaction commits, so an import
     * never holds the row-map copy of the whole log next to the domain objects.
     */
    val traceBatches: Sequence<Neo4jXesTraceBatch>,
)

data class Neo4jXesTraceBatch(
    val traces: List<Map<String, Any?>>,
    val events: List<Map<String, Any?>>,
    val follows: List<Map<String, String>>,
)
