package com.processm.processminterpreter.neo4j.xes.schema

internal data class TextIndexSpec(
    val name: String,
    val label: String,
    val property: String,
) {
    fun createCypher(): String =
        "CREATE TEXT INDEX $name IF NOT EXISTS FOR (n:$label) ON (n.$property)"
}
