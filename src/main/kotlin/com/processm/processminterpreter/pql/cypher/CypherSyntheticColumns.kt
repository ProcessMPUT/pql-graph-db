package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchema

internal const val SYNTHETIC_LOG_KEY_ALIAS = "_logKey"
internal const val SYNTHETIC_LOG_ID_ALIAS = "_log_id_"
internal const val SYNTHETIC_LOG_ORDER_ALIAS = "_log_order_"
internal const val SYNTHETIC_TRACE_ID_ALIAS = "_trace_id_"
internal const val SYNTHETIC_TRACE_ORDER_ALIAS = "_trace_order_"
internal const val SYNTHETIC_EVENT_GROUP_ORDER_ALIAS = "_event_group_order_"
internal const val SYNTHETIC_LOG_METADATA_ALIAS = "_log_meta_"
internal const val SYNTHETIC_LOG_NODE_ALIAS = "_log_node_"
internal const val SYNTHETIC_EVENT_ALIAS = "event"
internal const val SYNTHETIC_GROUPED_EVENT_ALIAS = "_grouped_event_"
internal const val SYNTHETIC_NULL_EVENT_COUNT_ALIAS = "_null_event_count_"
internal const val SYNTHETIC_TRACE_COUNT_ALIAS = "_trace_count_"
internal const val TRACE_GROUP_ORDER_ALIAS = "_trace_group_order_"

internal fun logMetadataProjection(): String =
    "log { " +
        ".${Neo4jXesSchema.LOG_CLASSIFIERS_PROPERTY}, " +
        ".${Neo4jXesSchema.LOG_EXTENSIONS_PROPERTY}, " +
        ".${Neo4jXesSchema.LOG_TRACE_GLOBALS_PROPERTY}, " +
        ".${Neo4jXesSchema.LOG_EVENT_GLOBALS_PROPERTY}" +
        " } AS $SYNTHETIC_LOG_METADATA_ALIAS"

internal fun logNodeProjection(): String =
    "properties(log) AS $SYNTHETIC_LOG_NODE_ALIAS"

internal fun conditionalLogMetadataProjection(predicate: String): String =
    "CASE WHEN $predicate THEN log { " +
        ".${Neo4jXesSchema.LOG_CLASSIFIERS_PROPERTY}, " +
        ".${Neo4jXesSchema.LOG_EXTENSIONS_PROPERTY}, " +
        ".${Neo4jXesSchema.LOG_TRACE_GLOBALS_PROPERTY}, " +
        ".${Neo4jXesSchema.LOG_EVENT_GLOBALS_PROPERTY}" +
        " } ELSE null END AS $SYNTHETIC_LOG_METADATA_ALIAS"

internal fun conditionalLogNodeProjection(predicate: String): String =
    "CASE WHEN $predicate THEN properties(log) ELSE null END AS $SYNTHETIC_LOG_NODE_ALIAS"
