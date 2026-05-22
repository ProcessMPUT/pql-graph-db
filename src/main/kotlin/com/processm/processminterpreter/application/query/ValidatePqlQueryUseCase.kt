package com.processm.processminterpreter.application.query

import com.processm.processminterpreter.domain.pql.error.PQLCompileError
import org.springframework.stereotype.Component

/**
 * Runs the shared PQL compiler without executing against the backend. Suitable
 * for UI "check my query before I run it" flows.
 *
 * Any [PQLCompileError] along the way is captured into the returned
 * [ValidationResult] as a human-readable error string. Unexpected runtime
 * exceptions propagate because they signal bugs, not user query errors.
 */
@Component
class ValidatePqlQueryUseCase(
    private val compiler: PqlCompiler,
) {

    fun validate(request: ValidatePqlQueryRequest): ValidationResult =
        try {
            compiler.prepareForExecution(
                query = request.query,
                logId = request.logId,
                dataStoreId = request.dataStoreId,
            )
            ValidationResult(valid = true, query = request.query)
        } catch (e: PQLCompileError) {
            ValidationResult(
                valid = false,
                query = request.query,
                errors = listOf(e.message ?: e::class.simpleName.orEmpty()),
            )
        }
}

data class ValidatePqlQueryRequest(
    val query: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
)

/**
 * Validation outcome. When [valid] is false, [errors] contains at least one
 * human-readable message (one per compile error captured).
 */
data class ValidationResult(
    val valid: Boolean,
    val query: String,
    val errors: List<String> = emptyList(),
)
