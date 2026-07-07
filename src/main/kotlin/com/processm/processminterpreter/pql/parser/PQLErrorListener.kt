package com.processm.processminterpreter.pql.parser

import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.error.PQLSyntaxException
import com.processm.processminterpreter.pql.error.Problem
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer

/**
 * ANTLR error listener that accumulates syntax errors into domain-level
 * [PQLSyntaxException] instances rather than letting ANTLR bail out mid-parse.
 *
 * Callers inspect [errors] after parsing; throwing policy (first-vs-all) is up
 * to the adapter. Kept tiny on purpose — all classification heuristics that
 * existed in the legacy listener live in the `model` package there and do not
 * belong in the new hexagonal infrastructure layer.
 */
class PQLErrorListener : BaseErrorListener() {

    private val _errors = mutableListOf<PQLSyntaxException>()

    /** Accumulated syntax errors, in the order ANTLR reported them. */
    val errors: List<PQLSyntaxException> get() = _errors

    /** `true` if at least one syntax error was observed. */
    val hasErrors: Boolean get() = _errors.isNotEmpty()

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?,
    ) {
        val location = SourceLocation(line, charPositionInLine)
        val text = msg ?: "Syntax error"
        _errors += PQLSyntaxException(Problem.SyntaxError, location, text, e)
    }

    /** Throws the first accumulated error, if any. */
    fun throwIfAny() {
        _errors.firstOrNull()?.let { throw it }
    }
}
