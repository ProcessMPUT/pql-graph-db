package com.processm.processminterpreter.neo4j.xes.schema

/**
 * Physical Neo4j schema required by the XES persistence adapter.
 */
internal object Neo4jXesSchemaDefinitions {
    val uniqueConstraints = listOf(
        UniqueConstraintSpec("log_logId_unique", "Log", "logId", "log_logId"),
        UniqueConstraintSpec("datastore_dataStoreId_unique", "DataStore", "dataStoreId", "datastore_dataStoreId"),
        UniqueConstraintSpec("trace_traceId_unique", "Trace", "traceId", "trace_traceId"),
        UniqueConstraintSpec("event_eventId_unique", "Event", "eventId", "event_eventId"),
    )
}

internal data class UniqueConstraintSpec(
    val name: String,
    val label: String,
    val property: String,
    val legacyIndexName: String,
) {
    fun createCypher(): String =
        "CREATE CONSTRAINT $name IF NOT EXISTS FOR (n:$label) REQUIRE n.$property IS UNIQUE"

    fun duplicateCheckCypher(): String =
        """
        MATCH (n:$label)
        WHERE n.$property IS NOT NULL
        WITH n.$property AS value, count(*) AS count
        WHERE count > 1
        RETURN value, count
        LIMIT 1
        """.trimIndent()
}
