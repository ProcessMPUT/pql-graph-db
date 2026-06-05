package com.processm.processminterpreter.domain.log.xes

import java.util.UUID

data class XesTrace(
    val conceptName: String? = null,
    val identityId: UUID? = null,
    val costCurrency: String? = null,
    val costTotal: Double? = null,
    val count: Int = 1,
    val customAttributes: Map<String, Any?> = emptyMap(),
    val events: List<XesEvent> = emptyList(),
    /** ProcessM output quirk: count of null-event placeholders to emit during XES serialization. */
    val nullEventCount: Int = 0,
)
