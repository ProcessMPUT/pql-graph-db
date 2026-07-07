package com.processm.processminterpreter.pql

/**
 * Validation outcome. When [valid] is false, [errors] contains at least one
 * human-readable message (one per compile error captured).
 */
data class ValidationResult(
    val valid: Boolean,
    val query: String,
    val errors: List<String> = emptyList(),
)
