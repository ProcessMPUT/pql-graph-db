package com.processm.processminterpreter.application.log

class LogNotFoundException(
    id: String,
) : RuntimeException("Log with ID '$id' not found")
