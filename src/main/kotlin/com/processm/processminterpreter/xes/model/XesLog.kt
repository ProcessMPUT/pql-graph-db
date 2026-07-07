package com.processm.processminterpreter.xes.model

import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.Extension
import com.processm.processminterpreter.xes.model.GlobalAttribute
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
