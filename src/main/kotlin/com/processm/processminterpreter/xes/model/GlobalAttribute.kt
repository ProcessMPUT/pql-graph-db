package com.processm.processminterpreter.xes.model

data class GlobalAttribute(
    val scope: AttributeScope,
    val key: String,
    val value: Any?,
)
