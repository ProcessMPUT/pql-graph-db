package com.processm.processminterpreter.pql.visitor

import com.processm.processminterpreter.pql.model.PQLParserException
import com.processm.processminterpreter.pql.model.TokenSequence
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.FailedPredicateException
import org.antlr.v4.runtime.InputMismatchException
import org.antlr.v4.runtime.LexerNoViableAltException
import org.antlr.v4.runtime.NoViableAltException
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.slf4j.LoggerFactory

/**
 * Custom error listener for PQL parsing
 *
 * Collects syntax errors during parsing and provides detailed error messages.
 * Throws PQLParserException with proper Problem classification.
 */
class PQLErrorListener : BaseErrorListener() {
    private val logger = LoggerFactory.getLogger(PQLErrorListener::class.java)
    private val errors = mutableListOf<ErrorInfo>()

    /**
     * Collected error information
     */
    private data class ErrorInfo(
        val line: Int,
        val charPositionInLine: Int,
        val message: String?,
        val offendingToken: Token?,
        val exception: RecognitionException?,
        val problem: PQLParserException.Problem,
    )

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?,
    ) {
        val token = offendingSymbol as? Token
        val problem = classifyProblem(e, msg)

        val errorInfo =
            ErrorInfo(
                line = line,
                charPositionInLine = charPositionInLine,
                message = msg,
                offendingToken = token,
                exception = e,
                problem = problem,
            )

        val errorMsg = "Syntax error at line $line:$charPositionInLine - $msg"
        logger.error(errorMsg)
        errors.add(errorInfo)
    }

    /**
     * Classify the recognition exception into a Problem enum
     */
    private fun classifyProblem(
        e: RecognitionException?,
        msg: String?,
    ): PQLParserException.Problem {
        // First try to classify by exception type
        when (e) {
            is FailedPredicateException -> return PQLParserException.Problem.FailedPredicate
            is InputMismatchException -> return PQLParserException.Problem.InputMismatch
            is LexerNoViableAltException -> return PQLParserException.Problem.LexerNoViableAlt
            is NoViableAltException -> return PQLParserException.Problem.NoViableAlt
        }

        // If exception is null or unknown, try to classify from message
        val message = msg?.lowercase() ?: ""
        return when {
            message.contains("no viable alternative") -> PQLParserException.Problem.NoViableAlt
            message.contains("mismatched input") -> PQLParserException.Problem.InputMismatch
            message.contains("missing") -> PQLParserException.Problem.MissingToken
            message.contains("extraneous input") || message.contains("unwanted") -> PQLParserException.Problem.UnwantedToken
            message.contains("failed predicate") -> PQLParserException.Problem.FailedPredicate
            else -> PQLParserException.Problem.Unknown
        }
    }

    /**
     * Check if any errors were collected
     */
    fun hasErrors(): Boolean = errors.isNotEmpty()

    /**
     * Get all collected error messages
     */
    fun getErrors(): List<String> =
        errors.map {
            "Syntax error at line ${it.line}:${it.charPositionInLine} - ${it.message}"
        }

    /**
     * Throw exception if there are any errors
     */
    fun throwIfErrors() {
        if (hasErrors()) {
            val first = errors.first()

            // Build offending token sequence
            val offendingToken =
                first.offendingToken?.let { token ->
                    TokenSequence(
                        value = token.text ?: "",
                        startIndex = token.startIndex,
                        stopIndex = token.stopIndex,
                    )
                } ?: TokenSequence(
                    value = "",
                    startIndex = -1,
                    stopIndex = -1,
                )

            // Extract expected tokens from the exception
            val expectedTokens: Collection<String>? =
                when (val ex = first.exception) {
                    is InputMismatchException -> {
                        ex.expectedTokens?.toList()?.map {
                            ex.recognizer?.vocabulary?.getDisplayName(it) ?: it.toString()
                        }
                    }

                    is NoViableAltException -> {
                        ex.expectedTokens?.toList()?.map {
                            ex.recognizer?.vocabulary?.getDisplayName(it) ?: it.toString()
                        }
                    }

                    else -> {
                        null
                    }
                }

            throw PQLParserException(
                problem = first.problem,
                line = first.line,
                charPositionInLine = first.charPositionInLine,
                offendingToken = offendingToken,
                expectedTokens = expectedTokens,
                originalMessage = first.message,
                baseException = first.exception,
            )
        }
    }
}
