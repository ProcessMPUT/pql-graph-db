package com.processm.processminterpreter.xes

class LogNotFoundException(
    id: String,
) : RuntimeException("Log with ID '$id' not found")
