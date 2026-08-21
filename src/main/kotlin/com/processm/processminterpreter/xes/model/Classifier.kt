package com.processm.processminterpreter.xes.model

data class Classifier(
    val name: String,
    val keys: List<String>,
    val scope: AttributeScope = AttributeScope.EVENT,
)
