package com.processm.processminterpreter.domain.log.xes

import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.log.Extension
import com.processm.processminterpreter.domain.log.GlobalAttribute
import java.util.UUID

/**
 * Hierarchical XES projection. Used as query execution result and XES import/export payload.
 * All standard XES attributes preserved as named nullable properties.
 * All fields immutable.
 */
data class XesLog(
    val conceptName: String? = null,
    val identityId: UUID? = null,
    val lifecycleModel: String? = null,
    val classifiers: List<Classifier> = emptyList(),
    val extensions: List<Extension> = emptyList(),
    val traceGlobals: List<GlobalAttribute> = emptyList(),
    val eventGlobals: List<GlobalAttribute> = emptyList(),
    val customAttributes: Map<String, Any?> = emptyMap(),
    val traces: List<XesTrace> = emptyList(),
)
