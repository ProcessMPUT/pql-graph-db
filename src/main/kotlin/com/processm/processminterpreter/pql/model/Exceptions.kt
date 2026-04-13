package com.processm.processminterpreter.pql.model

import org.antlr.v4.runtime.RecognitionException

/**
 * Base exception for all PQL-related errors.
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
sealed class PQLException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A syntax error in PQL other than [PQLParserException].
 *
 * This exception is thrown for semantic/validation errors that are
 * detected after parsing but before query execution.
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
class PQLSyntaxException : PQLException {
    val problem: Problem
    val line: Int
    val charPositionInLine: Int
    val args: Array<out Any>

    /**
     * Primary constructor with Problem enum.
     */
    constructor(
        problem: Problem,
        line: Int = -1,
        charPositionInLine: Int = -1,
        vararg args: Any,
    ) : super("Line $line position $charPositionInLine: $problem ${args.toList()}") {
        this.problem = problem
        this.line = line
        this.charPositionInLine = charPositionInLine
        this.args = args
    }

    /**
     * Secondary constructor for simple error messages (backward compatibility).
     */
    constructor(
        line: Int,
        charPositionInLine: Int,
        message: String,
        cause: Throwable? = null,
    ) : super("Line $line position $charPositionInLine: $message", cause) {
        this.problem = Problem.UnknownAttributeType
        this.line = line
        this.charPositionInLine = charPositionInLine
        this.args = arrayOf(message)
    }

    /**
     * Enumeration of all possible PQL syntax/semantic problems.
     *
     * ProcessM compatibility: These match the original ProcessM Problem enum.
     */
    enum class Problem {
        /** Aggregation function used in WHERE clause (not allowed) */
        AggregationFunctionInWhere,

        /** Classifier used in WHERE clause (not allowed) */
        ClassifierInWhere,

        /** Same scope specified multiple times in LIMIT */
        DuplicateLimit,

        /** Same scope specified multiple times in OFFSET */
        DuplicateOffset,

        /** LIMIT/OFFSET requires positive integer */
        PositiveIntegerRequired,

        /** Decimal part was dropped from LIMIT/OFFSET value (warning) */
        DecimalPartDropped,

        /** Scope prefix required for attribute */
        ScopeRequired,

        /** Scope hoisting (^ or ^^) not allowed in SELECT or ORDER BY */
        ScopeHoistingInSelectOrOrderBy,

        /** Unexpected child expression type */
        UnexpectedChild,

        /** SELECT * used with implicit GROUP BY */
        ExplicitSelectAllWithImplicitGroupBy,

        /** Missing attributes when aggregation is used */
        MissingAttributesInAggregation,

        /** SELECT * conflicts with specific attribute references (warning) */
        SelectAllConflictsWithReferencingByName,

        /** Mixed scopes in expression */
        MixedScopes,

        /** Attribute not in GROUP BY clause */
        AttributeNotInGroupBy,

        /** ORDER BY clause was removed due to implicit GROUP BY (warning) */
        OrderByClauseRemoved,

        /** Classifier used on LOG scope (not allowed) */
        ClassifierOnLog,

        /** Cannot hoist beyond LOG scope */
        NoHoistingBeyondLong,

        /** Unknown attribute type */
        UnknownAttributeType,

        /** No such attribute exists */
        NoSuchAttribute,

        /** Invalid boolean value */
        InvalidBoolean,

        /** Invalid number value */
        InvalidNumber,

        /** Invalid datetime value */
        InvalidDateTime,

        /** Invalid UUID value */
        InvalidUUID,
    }
}

/**
 * Represents an offending token sequence.
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
data class TokenSequence(
    val value: String,
    val startIndex: Int = -1,
    val stopIndex: Int = -1,
) {
    override fun toString(): String = value
}

/**
 * A PQL error detected by the ANTLR parser.
 *
 * This exception is thrown when the parser cannot recognize the input.
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
class PQLParserException(
    val problem: Problem,
    val line: Int,
    val charPositionInLine: Int,
    val offendingToken: TokenSequence,
    val expectedTokens: Collection<String>?,
    originalMessage: String?,
    val baseException: RecognitionException? = null,
) : PQLException(
        "Line $line position $charPositionInLine: $originalMessage (offendingToken='$offendingToken' expectedTokens='$expectedTokens' problem=$problem)",
    ) {
    /**
     * Enumeration of all possible ANTLR parser problems.
     *
     * ProcessM compatibility: These match the original ProcessM Problem enum.
     */
    enum class Problem {
        /** Unknown parser error */
        Unknown,

        /** Failed predicate in grammar */
        FailedPredicate,

        /** Input doesn't match expected token */
        InputMismatch,

        /** Lexer couldn't find viable alternative */
        LexerNoViableAlt,

        /** Parser couldn't find viable alternative */
        NoViableAlt,

        /** Missing expected token */
        MissingToken,

        /** Unwanted token in input */
        UnwantedToken,
    }
}

/**
 * Semantic error in PQL query.
 *
 * Thrown when the query is syntactically correct but semantically invalid.
 *
 * Examples:
 * - Using aggregation function in WHERE clause
 * - Using classifier in WHERE clause
 * - Hoisting beyond LOG scope
 * - GROUP BY without all non-aggregated SELECT fields
 */
open class PQLSemanticException(
    message: String,
    cause: Throwable? = null,
) : PQLException(message, cause)

/**
 * Invalid scope hoisting.
 *
 * Examples:
 * - ^^^e:name (hoisting beyond LOG)
 * - ^l:name (LOG has no parent scope)
 */
class InvalidScopeHoistingException(
    message: String,
) : PQLSemanticException(message)

/**
 * Invalid classifier usage.
 *
 * Classifiers are only allowed in SELECT and GROUP BY clauses,
 * not in WHERE or at LOG scope.
 *
 * Examples:
 * - where c:businesscase = 'xyz' (classifiers not allowed in WHERE)
 * - select l:c:activity (classifiers not allowed at LOG scope)
 */
class InvalidClassifierUsageException(
    message: String,
) : PQLSemanticException(message)

/**
 * Invalid GROUP BY clause.
 *
 * Examples:
 * - SELECT e:name, count(e:id) without GROUP BY e:name
 * - GROUP BY on non-selected field
 */
class InvalidGroupByException(
    message: String,
) : PQLSemanticException(message)

/**
 * Invalid DELETE clause.
 *
 * Examples:
 * - DELETE with GROUP BY
 * - DELETE with SELECT
 * - SELECT with DELETE
 */
class InvalidDeleteException(
    message: String,
) : PQLSemanticException(message)

/**
 * Invalid function usage.
 *
 * Examples:
 * - Unknown function name
 * - Wrong number of arguments
 * - Scalar function with scope greater than argument scope
 */
class InvalidFunctionException(
    message: String,
) : IllegalArgumentException(message)
