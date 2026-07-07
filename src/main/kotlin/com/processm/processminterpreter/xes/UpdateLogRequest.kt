package com.processm.processminterpreter.xes

import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.Extension
import com.processm.processminterpreter.xes.model.GlobalAttribute

data class UpdateLogRequest(
    val id: String,
    val name: String? = null,
    val lifecycleModel: String? = null,
    val classifiers: List<Classifier>? = null,
    val extensions: List<Extension>? = null,
    val traceGlobals: List<GlobalAttribute>? = null,
    val eventGlobals: List<GlobalAttribute>? = null,
    val customAttributes: Map<String, Any?>? = null,
)
