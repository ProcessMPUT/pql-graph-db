package com.processm.processminterpreter.xes

import java.time.LocalDateTime

data class SearchLogsRequest(
    val namePart: String? = null,
    val attribute: AttributeFilter? = null,
    val createdAfter: LocalDateTime? = null,
    val createdBefore: LocalDateTime? = null,
)
