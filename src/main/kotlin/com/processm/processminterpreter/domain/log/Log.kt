package com.processm.processminterpreter.domain.log

import java.time.LocalDateTime

/**
 * Persistent log entity. Metadata only - does NOT carry traces/events.
 * Use cases fetch traces via QueryPlanExecutor, which returns XesLog projections.
 */
data class Log(
    val id: String,
    val name: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val lifecycleModel: String? = null,
    val classifiers: List<Classifier> = emptyList(),
    val extensions: List<Extension> = emptyList(),
    val traceGlobals: List<GlobalAttribute> = emptyList(),
    val eventGlobals: List<GlobalAttribute> = emptyList(),
    val customAttributes: Map<String, Any?> = emptyMap(),
)
