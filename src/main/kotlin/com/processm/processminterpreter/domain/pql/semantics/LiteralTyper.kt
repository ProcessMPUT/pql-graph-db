package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.catalog.Type
import com.processm.processminterpreter.domain.pql.error.PQLSyntaxException
import com.processm.processminterpreter.domain.pql.error.Problem
import com.processm.processminterpreter.domain.pql.resolved.TypedLiteral
import com.processm.processminterpreter.domain.pql.syntax.RawLiteral
import com.processm.processminterpreter.domain.pql.syntax.RawLiteralKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Parses a [RawLiteral]'s source text into a typed value.
 *
 * Output:
 *  - STRING   -> ([String], [Type.STRING])
 *  - NUMBER   -> ([Double]; [Type.NUMBER])
 *  - DATETIME -> ([LocalDateTime] in UTC; [Type.DATETIME])
 *  - BOOLEAN  -> ([Boolean]; [Type.BOOLEAN])
 *  - NULL     -> (null; [Type.NULL])
 *  - UUID     -> ([UUID]; [Type.ID])
 *
 * Throws [PQLSyntaxException] with literal-specific [Problem] values on malformed literals.
 */
object LiteralTyper {

    fun type(raw: RawLiteral): TypedLiteral {
        val scoped = stripScope(raw.rawText)
        val parsed = parse(raw.kind, scoped.text, raw.location)
        return TypedLiteral(
            value = parsed.value,
            type = parsed.type,
            location = raw.location,
            scope = scoped.scope,
            pqlText = parsed.pqlText,
        )
    }

    private fun parse(kind: RawLiteralKind, text: String, loc: SourceLocation): ParsedLiteral =
        when (kind) {
            RawLiteralKind.STRING -> {
                val value = unquote(text)
                ParsedLiteral(value, Type.STRING, value)
            }
            RawLiteralKind.NUMBER -> parseNumber(text, loc)
            RawLiteralKind.DATETIME -> {
                val value = parseDatetime(text, loc)
                ParsedLiteral(value, Type.DATETIME, "D${value.toInstant(ZoneOffset.UTC)}")
            }
            RawLiteralKind.BOOLEAN -> {
                val value = parseBoolean(text, loc)
                ParsedLiteral(value, Type.BOOLEAN, value.toString())
            }
            RawLiteralKind.NULL -> ParsedLiteral(null, Type.NULL, "null")
            RawLiteralKind.UUID -> {
                val value = parseUuid(text, loc)
                ParsedLiteral(value, Type.ID, value.toString())
            }
        }

    private fun unquote(text: String): String {
        if (text.length >= 2 && (text.startsWith("'") && text.endsWith("'") ||
                text.startsWith("\"") && text.endsWith("\""))
        ) {
            return text.substring(1, text.length - 1)
        }
        return text
    }

    private fun parseNumber(text: String, loc: SourceLocation): ParsedLiteral {
        val s = text.trim()
        return try {
            val value = s.toDouble()
            ParsedLiteral(value, Type.NUMBER, value.toString())
        } catch (e: NumberFormatException) {
            throw PQLSyntaxException(Problem.InvalidNumber, loc, "Invalid numeric literal: $text")
        }
    }

    private fun parseDatetime(text: String, loc: SourceLocation): LocalDateTime {
        val stripped = text.removePrefix("D").removeSurrounding("\"").removeSurrounding("'")
        parseOffsetDateTime(stripped)?.let { return it }
        parseLocalDateTime(stripped)?.let { return it }
        parseLocalDate(stripped)?.let { return it.atStartOfDay() }
        parseYearMonth(stripped)?.let { return it.atDay(1).atStartOfDay() }
        throw PQLSyntaxException(Problem.InvalidDateTime, loc, "Invalid datetime literal: $text")
    }

    private fun parseOffsetDateTime(text: String): LocalDateTime? =
        OFFSET_DATE_TIME_FORMATTERS.firstNotNullOfOrNull { formatter ->
            parseOrNull {
                OffsetDateTime.parse(text, formatter)
                    .atZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime()
            }
        }

    private fun parseLocalDateTime(text: String): LocalDateTime? =
        LOCAL_DATE_TIME_FORMATTERS.firstNotNullOfOrNull { formatter ->
            parseOrNull {
                LocalDateTime.parse(text, formatter)
            }
        }

    private fun parseLocalDate(text: String): LocalDate? =
        parseOrNull { LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE) }
            ?: parseOrNull { LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE) }

    private fun parseYearMonth(text: String): YearMonth? =
        parseOrNull { YearMonth.parse(text, DateTimeFormatter.ofPattern("yyyy-MM")) }

    private inline fun <T> parseOrNull(block: () -> T): T? =
        try {
            block()
        } catch (e: DateTimeParseException) {
            null
        }

    private fun parseBoolean(text: String, loc: SourceLocation): Boolean =
        when (text.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw PQLSyntaxException(Problem.InvalidBoolean, loc, "Invalid boolean literal: $text")
        }

    private fun parseUuid(text: String, loc: SourceLocation): UUID {
        val stripped = text.removeSurrounding("'").removeSurrounding("\"")
        return try {
            UUID.fromString(stripped)
        } catch (e: IllegalArgumentException) {
            throw PQLSyntaxException(Problem.InvalidUUID, loc, "Invalid UUID literal: $text")
        }
    }

    private fun stripScope(text: String): ScopedLiteralText {
        val idx = text.indexOf(':')
        if (idx <= 0) return ScopedLiteralText(null, text)
        val scope = Scope.tryParse(text.substring(0, idx)) ?: return ScopedLiteralText(null, text)
        return ScopedLiteralText(scope, text.substring(idx + 1))
    }

    private data class ScopedLiteralText(
        val scope: Scope?,
        val text: String,
    )

    private data class ParsedLiteral(
        val value: Any?,
        val type: Type,
        val pqlText: String,
    )

    private val OFFSET_DATE_TIME_FORMATTERS: List<DateTimeFormatter> =
        listOf(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmX"),
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmXX"),
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX"),
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssXX"),
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSX"),
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSXX"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmX"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmXX"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssX"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssXX"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss.SSSX"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss.SSSXX"),
        )

    private val LOCAL_DATE_TIME_FORMATTERS: List<DateTimeFormatter> =
        listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm"),
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"),
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmm"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss.SSS"),
        )
}
