package com.processm.processminterpreter.web.common.dto

import java.time.LocalDateTime

data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val path: String? = null,
)
