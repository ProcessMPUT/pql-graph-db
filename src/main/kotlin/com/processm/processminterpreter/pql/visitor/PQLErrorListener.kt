package com.processm.processminterpreter.pql.visitor

import com.processm.processminterpreter.pql.model.PQLSyntaxException
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.slf4j.LoggerFactory

/**
 * Custom error listener for PQL parsing
 *
 * Collects syntax errors during parsing and provides detailed error messages.
 */
class PQLErrorListener : BaseErrorListener() {

    private val logger = LoggerFactory.getLogger(PQLErrorListener::class.java)
    private val errors = mutableListOf<String>()
    private var firstErrorLine = -1
    private var firstErrorCharPos = -1

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?,
    ) {
        if (errors.isEmpty()) {
            firstErrorLine = line
            firstErrorCharPos = charPositionInLine
        }
        val error = "Syntax error at line $line:$charPositionInLine - $msg"
        logger.error(error)
        errors.add(error)
    }

    /**
     * Check if any errors were collected
     */
    fun hasErrors(): Boolean = errors.isNotEmpty()

    /**
     * Get all collected errors
     */
    fun getErrors(): List<String> = errors.toList()

    /**
     * Throw exception if there are any errors
     */
    fun throwIfErrors() {
        if (hasErrors()) {
            throw PQLSyntaxException(firstErrorLine, firstErrorCharPos, "PQL parsing failed:\n${errors.joinToString("\n")}")
        }
    }
}
