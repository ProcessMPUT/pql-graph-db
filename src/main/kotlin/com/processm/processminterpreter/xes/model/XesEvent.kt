package com.processm.processminterpreter.xes.model

import java.time.Instant
import java.util.UUID

data class XesEvent(
    val conceptName: String? = null,
    val conceptInstance: String? = null,
    val identityId: UUID? = null,
    val timeTimestamp: Instant? = null,
    val lifecycleTransition: String? = null,
    val lifecycleState: String? = null,
    val orgResource: String? = null,
    val orgRole: String? = null,
    val orgGroup: String? = null,
    val costCurrency: String? = null,
    val costTotal: Double? = null,
    val customAttributes: Map<String, Any?> = emptyMap(),
)
