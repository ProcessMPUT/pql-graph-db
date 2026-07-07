package com.processm.processminterpreter.pql.semantics

import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.pql.ast.PqlQuery
import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.BinaryOperator
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.pql.catalog.SystemAttributeCatalog
import com.processm.processminterpreter.pql.catalog.Type
import com.processm.processminterpreter.pql.catalog.UnaryOperator
import com.processm.processminterpreter.pql.error.InvalidScopeHoistingException
import com.processm.processminterpreter.pql.error.PQLSyntaxException
import com.processm.processminterpreter.pql.error.Problem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Walks a [PqlQuery] top-down and produces a [PqlQuery] with:
 *  - every attribute resolved through [AttributeResolver]
 *  - literals typed via [LiteralTyper]
 *  - function calls classified as [PqlExpression.Call] or [PqlExpression.Aggregation] via [FunctionCatalog]
 *  - expression result types inferred
 *  - structural shape (expressions, ordering, groupBy) preserved in source order
 *
 * Pure: no Spring, no DB access, deterministic. Takes [ResolutionContext] explicitly
 * so callers can inject classifier lists from whichever source (repository, tests).
 */
class Resolver {

    private val attributes = AttributeResolver()

    fun resolve(raw: PqlQuery, ctx: ResolutionContext = ResolutionContext()): PqlQuery = when (raw) {
        is PqlQuery.Select -> resolveSelect(raw, ctx)
        is PqlQuery.Delete -> resolveDelete(raw, ctx)
    }

    private fun resolveSelect(raw: PqlQuery.Select, ctx: ResolutionContext): PqlQuery.Select {
        val base = raw.from
        return PqlQuery.Select(
            from = base,
            columns = raw.columns.map { resolveColumn(it, base, ctx) },
            implicitAll = raw.implicitAll,
            where = raw.where?.let { resolveExpr(it, base, ctx) },
            groupBy = raw.groupBy.map { resolveExpr(it, base, ctx) },
            orderBy = raw.orderBy.map {
                PqlQuery.OrderKey(expression = resolveExpr(it.expression, base, ctx), direction = it.direction)
            },
            limit = raw.limit,
            offset = raw.offset,
            location = raw.location,
        )
    }

    private fun resolveDelete(raw: PqlQuery.Delete, ctx: ResolutionContext): PqlQuery.Delete =
        PqlQuery.Delete(
            from = raw.from,
            where = raw.where?.let { resolveExpr(it, raw.from, ctx) },
            location = raw.location,
        )

    private fun resolveColumn(
        col: PqlQuery.SelectColumn,
        base: Scope,
        ctx: ResolutionContext,
    ): PqlQuery.SelectColumn =
        PqlQuery.SelectColumn(
            expression = col.expression?.let { resolveExpr(it, base, ctx) },
            alias = col.alias,
            starAt = col.starAt,
        )

    private fun resolveExpr(expr: PqlExpression, base: Scope, ctx: ResolutionContext): PqlExpression = when (expr) {
        is PqlExpression.AttributeRef -> attributes.resolve(expr, defaultScope = base, context = ctx)
        is PqlExpression.Literal -> LiteralTyper.type(expr)
        is PqlExpression.Binary -> PqlExpression.Binary(
            op = expr.op,
            left = resolveExpr(expr.left, base, ctx),
            right = resolveExpr(expr.right, base, ctx),
            type = inferBinaryType(expr.op),
            location = expr.location,
        )
        is PqlExpression.Unary -> {
            val operand = resolveExpr(expr.operand, base, ctx)
            PqlExpression.Unary(
                op = expr.op,
                operand = operand,
                type = if (expr.op == UnaryOperator.NOT) Type.BOOLEAN else operand.type,
                location = expr.location,
            )
        }
        is PqlExpression.Call -> resolveFunctionCall(expr, base, ctx)
        is PqlExpression.InList -> PqlExpression.InList(
            values = expr.values.map { resolveExpr(it, base, ctx) },
            location = expr.location,
        )
        // Already-resolved nodes: resolution is idempotent.
        is PqlExpression.Attribute, is PqlExpression.Aggregation -> expr
    }

    private fun resolveFunctionCall(call: PqlExpression.Call, base: Scope, ctx: ResolutionContext): PqlExpression {
        val scoped = stripFunctionScope(call.name)
        val args = call.arguments.map { resolveExpr(it, base, ctx) }
        return if (FunctionCatalog.isAggregation(scoped.name)) {
            val argType = args.firstOrNull()?.type ?: Type.UNKNOWN
            val singleArg = args.firstOrNull() ?: error(
                "Aggregation ${scoped.name} at ${call.location} requires at least one argument",
            )
            PqlExpression.Aggregation(
                name = scoped.name.lowercase(),
                argument = singleArg,
                type = FunctionCatalog.aggregationReturnType(scoped.name, argType),
                location = call.location,
                scope = scoped.scope,
            )
        } else {
            PqlExpression.Call(
                name = scoped.name.lowercase(),
                arguments = args,
                scope = scoped.scope,
                type = FunctionCatalog.scalarReturnType(scoped.name) ?: Type.UNKNOWN,
                location = call.location,
            )
        }
    }

    private fun stripFunctionScope(name: String): ScopedFunctionName {
        val idx = name.indexOf(':')
        if (idx <= 0) return ScopedFunctionName(null, name)
        val scope = Scope.tryParse(name.substring(0, idx)) ?: return ScopedFunctionName(null, name)
        return ScopedFunctionName(scope, name.substring(idx + 1))
    }

    private data class ScopedFunctionName(
        val scope: Scope?,
        val name: String,
    )

    private fun inferBinaryType(op: BinaryOperator): Type = when (op) {
        BinaryOperator.EQ, BinaryOperator.NEQ,
        BinaryOperator.LT, BinaryOperator.LTE, BinaryOperator.GT, BinaryOperator.GTE,
        BinaryOperator.AND, BinaryOperator.OR,
        BinaryOperator.LIKE, BinaryOperator.NOT_LIKE, BinaryOperator.MATCHES_REGEX,
        BinaryOperator.IS, BinaryOperator.IS_NOT,
        BinaryOperator.IN, BinaryOperator.NOT_IN,
        -> Type.BOOLEAN

        BinaryOperator.PLUS, BinaryOperator.MINUS,
        BinaryOperator.MUL, BinaryOperator.DIV,
        -> Type.NUMBER
    }

    /**
     * Resolves a single [PqlExpression.AttributeRef] into a [PqlExpression.Attribute] with absolute scope,
     * canonical logical name and classification.
     *
     * Responsibilities:
     *  - pick the base scope from [PqlExpression.AttributeRef.scopeHint] (when written) or the given default
     *  - apply hoisting via [HoistingResolver]
     *  - recognize classifier-prefixed names (`c:*`, `classifier:*`) as CLASSIFIER
     *  - look up the name in the standard-attribute catalog (expanding shorthand like `name` -> `concept:name`)
     *  - look up PQL-visible system attributes such as `l:logId`
     *  - reject unbracketed non-standard names (ProcessM-compatible NoSuchAttribute)
     *  - accept bracketed non-standard names as CUSTOM
     */
    private class AttributeResolver(
        private val standardAttributes: StandardAttributeCatalog = StandardAttributeCatalog,
        private val systemAttributes: SystemAttributeCatalog = SystemAttributeCatalog,
    ) {

        fun resolve(
            raw: PqlExpression.AttributeRef,
            defaultScope: Scope,
            context: ResolutionContext = ResolutionContext(),
        ): PqlExpression.Attribute {
            val baseScope = raw.scopeHint?.let { Scope.parse(it) } ?: defaultScope
            val effectiveScope = HoistingResolver.apply(baseScope, raw.hoisting, raw.location)

            if (StandardAttributeCatalog.isClassifier(raw.name)) {
                if (baseScope == Scope.LOG) {
                    throw PQLSyntaxException(
                        Problem.ClassifierOnLog,
                        raw.location,
                        "Classifiers are defined for event attributes, not log attributes",
                    )
                }
                val classifierName = raw.name.removePrefix("classifier:").removePrefix("c:")
                if (classifierName in context.ambiguousClassifierNames) {
                    throw PQLSyntaxException(
                        Problem.InvalidUseOfClassifiers,
                        raw.location,
                        "Classifier '$classifierName' has multiple definitions in the selected query scope",
                    )
                }
                val classifier = context.classifiers.firstOrNull { it.name == classifierName }
                    ?: throw PQLSyntaxException(
                        Problem.InvalidUseOfClassifiers,
                        raw.location,
                        "Classifier '$classifierName' not found",
                    )
                if (classifier.keys.size == 1) {
                    val classifierKey = classifier.keys.single()
                    val standard = standardAttributes.lookup(baseScope, classifierKey)
                    if (standard != null) {
                        return PqlExpression.Attribute(
                            name = classifierKey,
                            baseScope = baseScope,
                            effectiveScope = effectiveScope,
                            kind = AttributeKind.STANDARD,
                            xesStandardName = standard.canonicalName,
                            wasBracketed = raw.wasBracketed,
                            classifierName = classifierName,
                            classifierKeys = classifier.keys,
                            type = standard.type,
                            location = raw.location,
                        )
                    }
                    return PqlExpression.Attribute(
                        name = classifierKey,
                        baseScope = baseScope,
                        effectiveScope = effectiveScope,
                        kind = AttributeKind.CUSTOM,
                        xesStandardName = null,
                        wasBracketed = true,
                        classifierName = classifierName,
                        classifierKeys = classifier.keys,
                        type = Type.UNKNOWN,
                        location = raw.location,
                    )
                }
                return PqlExpression.Attribute(
                    name = raw.name,
                    baseScope = baseScope,
                    effectiveScope = effectiveScope,
                    kind = AttributeKind.CLASSIFIER,
                    xesStandardName = null,
                    wasBracketed = raw.wasBracketed,
                    classifierName = classifierName,
                    classifierKeys = classifier.keys,
                    type = Type.STRING,
                    location = raw.location,
                )
            }

            val standard = standardAttributes.lookup(baseScope, raw.name)
            if (standard != null) {
                return PqlExpression.Attribute(
                    name = raw.name,
                    baseScope = baseScope,
                    effectiveScope = effectiveScope,
                    kind = AttributeKind.STANDARD,
                    xesStandardName = standard.canonicalName,
                    wasBracketed = raw.wasBracketed,
                    type = standard.type,
                    location = raw.location,
                )
            }

            val system = systemAttributes.lookup(baseScope, raw.name)
            if (system != null) {
                return PqlExpression.Attribute(
                    name = system.name,
                    baseScope = baseScope,
                    effectiveScope = effectiveScope,
                    kind = AttributeKind.SYSTEM,
                    xesStandardName = null,
                    wasBracketed = raw.wasBracketed,
                    type = system.type,
                    location = raw.location,
                )
            }

            if (!raw.wasBracketed) {
                throw PQLSyntaxException(
                    Problem.NoSuchAttribute,
                    raw.location,
                    "Attribute '${raw.name}' is not a standard XES attribute at $baseScope scope; " +
                        "use [${raw.name}] to reference a custom attribute",
                )
            }

            return PqlExpression.Attribute(
                name = raw.name,
                baseScope = baseScope,
                effectiveScope = effectiveScope,
                kind = AttributeKind.CUSTOM,
                xesStandardName = null,
                wasBracketed = true,
                type = Type.UNKNOWN,
                location = raw.location,
            )
        }
    }

    /**
     * Pure function that applies the `^` / `^^` hoisting operators to a base scope.
     *
     *  EVENT ^  -> TRACE
     *  EVENT ^^ -> LOG
     *  TRACE ^  -> LOG
     *  LOG   ^  -> error (cannot hoist above the top scope)
     *
     * Scope enum ordering is load-bearing: LOG=0, TRACE=1, EVENT=2, so hoisting is
     * expressed as `ordinal - hoistingLevels`.
     */
    private object HoistingResolver {
        fun apply(baseScope: Scope, hoistingLevels: Int, location: SourceLocation): Scope {
            require(hoistingLevels >= 0) { "hoistingLevels must be non-negative, got $hoistingLevels" }
            if (hoistingLevels == 0) return baseScope

            val targetOrdinal = baseScope.ordinal - hoistingLevels
            if (targetOrdinal < 0) {
                throw InvalidScopeHoistingException(
                    "Cannot hoist $baseScope by $hoistingLevels level(s); would exceed LOG scope",
                    location,
                )
            }
            return Scope.entries[targetOrdinal]
        }
    }

    /**
     * Parses a [PqlExpression.Literal]'s source text into a typed value.
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
    private object LiteralTyper {

        fun type(raw: PqlExpression.Literal): PqlExpression.Literal {
            val scoped = stripScope(raw.rawText)
            val parsed = parse(raw.kind, scoped.text, raw.location)
            return PqlExpression.Literal(
                rawText = raw.rawText,
                kind = raw.kind,
                value = parsed.value,
                type = parsed.type,
                location = raw.location,
                scope = scoped.scope,
                pqlText = parsed.pqlText,
            )
        }

        private fun parse(kind: PqlExpression.LiteralKind, text: String, loc: SourceLocation): ParsedLiteral =
            when (kind) {
                PqlExpression.LiteralKind.STRING -> {
                    val value = unquote(text)
                    ParsedLiteral(value, Type.STRING, value)
                }
                PqlExpression.LiteralKind.NUMBER -> parseNumber(text, loc)
                PqlExpression.LiteralKind.DATETIME -> {
                    val value = parseDatetime(text, loc)
                    ParsedLiteral(value, Type.DATETIME, "D${value.toInstant(ZoneOffset.UTC)}")
                }
                PqlExpression.LiteralKind.BOOLEAN -> {
                    val value = parseBoolean(text, loc)
                    ParsedLiteral(value, Type.BOOLEAN, value.toString())
                }
                PqlExpression.LiteralKind.NULL -> ParsedLiteral(null, Type.NULL, "null")
                PqlExpression.LiteralKind.UUID -> {
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

    /**
     * Detects classifier references (`c:*` / `classifier:*`) directly on the raw AST.
     *
     * Lives on the resolver because deciding whether a query uses classifiers must
     * happen *before* resolution — classifier resolution depends on the per-log classifier
     * catalog, which we don't want to load when the query doesn't reference any.
     *
     * Uses the canonical predicate from [StandardAttributeCatalog.isClassifier] to keep
     * the prefix taxonomy in one place.
     */
    companion object {

        /** True iff any SELECT query expression references a classifier. */
        fun containsClassifier(query: PqlQuery.Select): Boolean =
            query.columns.any { it.expression?.let { e -> containsClassifier(e) } == true } ||
                query.where?.let { containsClassifier(it) } == true ||
                query.groupBy.any { containsClassifier(it) } ||
                query.orderBy.any { containsClassifier(it.expression) }

        /** True iff [expr] or any sub-expression references a classifier. */
        fun containsClassifier(expr: PqlExpression): Boolean = when (expr) {
            is PqlExpression.AttributeRef -> StandardAttributeCatalog.isClassifier(expr.name)
            is PqlExpression.Binary -> containsClassifier(expr.left) || containsClassifier(expr.right)
            is PqlExpression.Unary -> containsClassifier(expr.operand)
            is PqlExpression.Call -> expr.arguments.any { containsClassifier(it) }
            is PqlExpression.InList -> expr.values.any { containsClassifier(it) }
            else -> false
        }
    }
}
