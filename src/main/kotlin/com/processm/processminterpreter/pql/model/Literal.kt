package com.processm.processminterpreter.pql.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Base class for literal values in PQL queries.
 *
 * Literals represent constant values:
 * - Strings: "hello", 'world'
 * - Numbers: 42, 3.14
 * - Booleans: true, false
 * - Dates/Times: D2020-01-01, D2020-01-01T12:30:00
 * - UUIDs: 550e8400-e29b-41d4-a716-446655440000
 * - Null: null
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
sealed class Literal<T>(
    val value: T,
    override val scope: Scope? = null,
    line: Int = -1,
    charPositionInLine: Int = -1,
) : Expression(line, charPositionInLine) {

    override val type: Type
        get() = when (this) {
            is StringLiteral -> Type.STRING
            is NumberLiteral -> Type.NUMBER
            is BooleanLiteral -> Type.BOOLEAN
            is DateTimeLiteral -> Type.DATETIME
            is UUIDLiteral -> Type.UUID
            is NullLiteral -> Type.UNKNOWN
        }

    /**
     * String representation of the literal.
     *
     * ProcessM compatibility: Includes scope prefix if present.
     * Format: {scope:}value
     */
    override fun toString(): String {
        val prefix = scope?.let { "${it}:" } ?: ""
        return "$prefix${valueToString()}"
    }

    /**
     * Convert value to string representation.
     * Override in subclasses for custom formatting.
     */
    protected open fun valueToString(): String = value.toString()
}

/**
 * String literal.
 *
 * Supports escape sequences:
 * - \" - double quote
 * - \t - tab
 * - \n - newline
 * - \r - carriage return
 * - \f - form feed
 * - \b - backspace
 * - \\ - backslash
 */
class StringLiteral(
    value: String,
    scope: Scope? = null,
    line: Int = -1,
    charPositionInLine: Int = -1,
) : Literal<String>(value, scope, line, charPositionInLine) {

    companion object {
        /**
         * Parse a string literal from PQL.
         * Removes quotes and unescapes escape sequences.
         *
         * @param s the string with quotes (e.g., "hello" or 'hello')
         * @param line line number for error reporting
         * @param charPos character position for error reporting
         * @return parsed StringLiteral
         */
        fun parse(s: String, line: Int = -1, charPos: Int = -1): StringLiteral {
            // Strip scope prefix if present (e.g. l:'abc')
            val colonIndex = s.indexOf(':')
            var scope: Scope? = null
            var content = s
            if (colonIndex >= 0) {
                val scopeStr = s.substring(0, colonIndex)
                try {
                    scope = Scope.parse(scopeStr)
                    content = s.substring(colonIndex + 1)
                } catch (e: IllegalArgumentException) {
                    // Not a valid scope, treat as content
                    scope = null
                    content = s
                }
            }

            if (content.length < 2) {
                throw PQLSyntaxException(line, charPos, "String literal too short: $s")
            }

            // Determine quote type
            val quoteChar = content[0]
            if (quoteChar != '"' && quoteChar != '\'') {
                throw PQLSyntaxException(line, charPos, "String literal must start with quote: $s")
            }

            // Find the closing quote by scanning and skipping escaped quotes
            // This handles edge cases like "abc\"" where the closing quote is escaped
            val closingQuoteIndex = findClosingQuote(content, quoteChar)
            val foundClosingQuote = closingQuoteIndex < content.length

            // Extract content between quotes
            val unquoted = content.substring(1, closingQuoteIndex)

            // Unescape Java escape sequences
            // If no closing quote was found, remove trailing escape sequence (ProcessM behavior)
            val unescaped = unescapeJava(unquoted, removeTrailingEscape = !foundClosingQuote)
            return StringLiteral(unescaped, scope, line, charPos)
        }

        /**
         * Find the index of the closing quote, properly handling escape sequences.
         *
         * Scans the string from position 1 (after opening quote) and tracks
         * whether the previous character was an escape backslash.
         *
         * Examples:
         * - "hello" → returns 5 (index of closing ")
         * - "abc\"" → returns 6 (skips escaped " at index 4)
         * - "abc jr\" → returns 9 (end of string, closing quote is escaped)
         *
         * @param s the string to scan
         * @param quoteChar the quote character being used (" or ')
         * @return index of closing quote, or s.length if no unescaped closing quote found
         */
        private fun findClosingQuote(s: String, quoteChar: Char): Int {
            var i = 1 // Start after opening quote
            var escaped = false

            while (i < s.length) {
                val currentChar = s[i]

                if (escaped) {
                    // Previous char was \, so this char is escaped
                    escaped = false
                } else if (currentChar == '\\') {
                    // Start of escape sequence
                    escaped = true
                } else if (currentChar == quoteChar) {
                    // Found unescaped closing quote
                    return i
                }

                i++
            }

            // No closing quote found - return end of string
            // This handles malformed strings like "abc jr\" where closing quote is escaped
            return s.length
        }

        /**
         * Unescape Java escape sequences.
         *
         * Supports: \", \', \t, \n, \r, \f, \b, \\
         *
         * IMPORTANT: \\ must be processed FIRST, otherwise it will unescape
         * the backslashes we just added from other escape sequences.
         *
         * @param removeTrailingEscape If true, removes trailing escape sequence (ProcessM behavior
         *                             for malformed strings without closing quote)
         */
        private fun unescapeJava(s: String, removeTrailingEscape: Boolean = false): String {
            // Remove trailing escape sequence if requested (ProcessM behavior for malformed strings)
            // This handles cases like "abc jr\" where the closing quote is escaped but there's no real closing quote
            val cleaned = if (removeTrailingEscape && s.length >= 2 && s[s.length - 2] == '\\') {
                // Remove last 2 chars (backslash + escaped char)
                s.dropLast(2)
            } else if (removeTrailingEscape && s.endsWith("\\")) {
                // Remove trailing backslash
                s.dropLast(1)
            } else {
                s
            }
            return cleaned
                .replace("\\\\", "\u0000") // Temp placeholder for backslash
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\t", "\t")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\f", "\u000C")
                .replace("\\b", "\b")
                .replace("\u0000", "\\") // Replace placeholder with actual backslash
        }
    }

    /**
     * String representation - just the value without quotes.
     * ProcessM outputs string values without surrounding quotes.
     */
    override fun valueToString(): String = value
}

/**
 * Number literal (integer or floating-point).
 */
class NumberLiteral(
    value: Double,
    scope: Scope? = null,
    line: Int = -1,
    charPositionInLine: Int = -1,
) : Literal<Double>(value, scope, line, charPositionInLine) {

    companion object {
        /**
         * Parse a number literal from PQL.
         *
         * @param s the number string (e.g., "42" or "3.14")
         * @param line line number for error reporting
         * @param charPos character position for error reporting
         * @return parsed NumberLiteral
         */
        fun parse(s: String, line: Int = -1, charPos: Int = -1): NumberLiteral {
            val colonIndex = s.indexOf(':')
            var scope: Scope? = null
            var content = s
            if (colonIndex >= 0) {
                val scopeStr = s.substring(0, colonIndex)
                try {
                    scope = Scope.parse(scopeStr)
                    content = s.substring(colonIndex + 1)
                } catch (e: IllegalArgumentException) {
                    scope = null
                    content = s
                }
            }
            return try {
                NumberLiteral(content.toDouble(), scope, line, charPos)
            } catch (e: NumberFormatException) {
                throw PQLSyntaxException(line, charPos, "Invalid number: $s", e)
            }
        }
    }
}

/**
 * Boolean literal (true or false).
 */
class BooleanLiteral(
    value: Boolean,
    scope: Scope? = null,
    line: Int = -1,
    charPositionInLine: Int = -1,
) : Literal<Boolean>(value, scope, line, charPositionInLine) {

    companion object {
        /**
         * Parse a boolean literal from PQL.
         *
         * @param s the boolean string ("true" or "false", case-insensitive)
         * @param line line number for error reporting
         * @param charPos character position for error reporting
         * @return parsed BooleanLiteral
         */
        fun parse(s: String, line: Int = -1, charPos: Int = -1): BooleanLiteral {
            val colonIndex = s.indexOf(':')
            var scope: Scope? = null
            var content = s
            if (colonIndex >= 0) {
                val scopeStr = s.substring(0, colonIndex)
                try {
                    scope = Scope.parse(scopeStr)
                    content = s.substring(colonIndex + 1)
                } catch (e: IllegalArgumentException) {
                    scope = null
                    content = s
                }
            }
            return when (content.lowercase()) {
                "true" -> BooleanLiteral(true, scope, line, charPos)
                "false" -> BooleanLiteral(false, scope, line, charPos)
                else -> throw PQLSyntaxException(line, charPos, "Invalid boolean: $s (expected 'true' or 'false')")
            }
        }
    }
}

/**
 * DateTime literal.
 *
 * Supports formats:
 * - Date only: D2020-01-01 (defaults to 00:00:00)
 * - Date and time: D2020-01-01T12:30:00
 * - ISO 8601: D2020-01-01T12:30:00.123
 *
 * The 'D' prefix is optional but recommended for clarity.
 */
class DateTimeLiteral(
    value: LocalDateTime,
    scope: Scope? = null,
    line: Int = -1,
    charPositionInLine: Int = -1,
) : Literal<LocalDateTime>(value, scope, line, charPositionInLine) {

    companion object {
        private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss][.SSS][XXX][XX][X]")
        private val dateFormatter = DateTimeFormatter.ISO_DATE

        private val formatters = listOf(
            // Standard ISO with separators
            DateTimeFormatter.ISO_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss][.SSS][XXX][XX][X]"),

            // Basic ISO (compact)
            DateTimeFormatter.BASIC_ISO_DATE,
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm[ss][.SSS][XXX][XX][X]"),

            // Compact without T
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss[.SSS][XXX][XX][X]"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmm[XXX][XX][X]")
        )

        fun parse(s: String, line: Int = -1, charPos: Int = -1): DateTimeLiteral {
            // Strip scope prefix if present
            val colonIndex = s.indexOf(':')
            var scope: Scope? = null
            var content = s
            if (colonIndex >= 0) {
                val scopeStr = s.substring(0, colonIndex)
                try {
                    scope = Scope.parse(scopeStr)
                    content = s.substring(colonIndex + 1)
                } catch (e: IllegalArgumentException) {
                    scope = null
                    content = s
                }
            }

            // Remove 'D' or 'd' prefix if present
            val cleaned = content.removePrefix("D").removePrefix("d").removePrefix("'").removeSuffix("'")

            var lastException: Exception? = null

            // Try all formatters
            for (formatter in formatters) {
                try {
                    val dateTime = if (formatter == DateTimeFormatter.BASIC_ISO_DATE || formatter == dateFormatter) {
                         LocalDate.parse(cleaned, formatter).atStartOfDay()
                    } else {
                        // Try OffsetDateTime first to properly handle timezone offsets
                        try {
                            OffsetDateTime.parse(cleaned, formatter)
                                .atZoneSameInstant(ZoneOffset.UTC)
                                .toLocalDateTime()
                        } catch (e: Exception) {
                            // Fall back to LocalDateTime (no offset info)
                            LocalDateTime.parse(cleaned, formatter)
                        }
                    }
                    return DateTimeLiteral(dateTime, scope, line, charPos)
                } catch (e: Exception) {
                    lastException = e
                }
            }

            // Fallback to simple date formatter if all else fails (legacy support)
            try {
                val date = LocalDate.parse(cleaned, dateFormatter)
                return DateTimeLiteral(date.atStartOfDay(), scope, line, charPos)
            } catch (e: Exception) {
                // Ignore
            }

            throw PQLSyntaxException(
                line,
                charPos,
                "Invalid datetime: $s (expected format: D2020-01-01 or D2020-01-01T12:30:00)",
                lastException ?: Exception("Unknown parsing error"),
            )
        }
    }

    /**
     * String representation in ProcessM format.
     * Format: D{yyyy-MM-dd}T{HH:mm:ss}[.SSS]Z
     * Always outputs Z (UTC) suffix and at least seconds precision.
     */
    override fun valueToString(): String {
        val millis = value.nano / 1_000_000
        return if (millis > 0) {
            "D${value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))}.${String.format("%03d", millis)}Z"
        } else {
            "D${value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))}Z"
        }
    }
}

/**
 * UUID literal.
 */
class UUIDLiteral(
    value: UUID,
    scope: Scope? = null,
    line: Int = -1,
    charPositionInLine: Int = -1,
) : Literal<UUID>(value, scope, line, charPositionInLine) {

    companion object {
        /**
         * Parse a UUID literal from PQL.
         *
         * @param s the UUID string (e.g., "550e8400-e29b-41d4-a716-446655440000")
         * @param line line number for error reporting
         * @param charPos character position for error reporting
         * @return parsed UUIDLiteral
         */
        fun parse(s: String, line: Int = -1, charPos: Int = -1): UUIDLiteral {
            val colonIndex = s.indexOf(':')
            var scope: Scope? = null
            var content = s
            if (colonIndex >= 0) {
                val scopeStr = s.substring(0, colonIndex)
                try {
                    scope = Scope.parse(scopeStr)
                    content = s.substring(colonIndex + 1)
                } catch (e: IllegalArgumentException) {
                    scope = null
                    content = s
                }
            }
            return try {
                UUIDLiteral(UUID.fromString(content), scope, line, charPos)
            } catch (e: Exception) {
                throw PQLSyntaxException(
                    line,
                    charPos,
                    "Invalid UUID: $s",
                    e,
                )
            }
        }
    }
}

/**
 * Null literal.
 */
class NullLiteral(
    scope: Scope? = null,
    line: Int = -1,
    charPositionInLine: Int = -1,
) : Literal<Nothing?>(null, scope, line, charPositionInLine) {

    /**
     * String representation - just "null" (scope handled by base class).
     */
    override fun valueToString(): String = "null"

    companion object {
        fun parse(s: String, line: Int = -1, charPos: Int = -1): NullLiteral {
            val colonIndex = s.indexOf(':')
            var scope: Scope? = null
            if (colonIndex >= 0) {
                val scopeStr = s.substring(0, colonIndex)
                try {
                    scope = Scope.parse(scopeStr)
                } catch (e: IllegalArgumentException) {
                    scope = null
                }
            }
            return NullLiteral(scope, line, charPos)
        }
    }
}
