package com.processm.processminterpreter.xes

import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.Extension
import com.processm.processminterpreter.xes.model.GlobalAttribute

data class CreateLogRequest(
    val name: String,
    val id: String? = null,
    val lifecycleModel: String? = null,
    val classifiers: List<Classifier> = emptyList(),
    val extensions: List<Extension> = emptyList(),
    val traceGlobals: List<GlobalAttribute> = emptyList(),
    val eventGlobals: List<GlobalAttribute> = emptyList(),
    val customAttributes: Map<String, Any?> = emptyMap(),
)
