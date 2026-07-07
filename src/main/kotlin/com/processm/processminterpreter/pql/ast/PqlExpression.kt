package com.processm.processminterpreter.pql.ast

import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.BinaryOperator
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.catalog.Type
import com.processm.processminterpreter.pql.catalog.UnaryOperator

/**
 * The single PQL expression tree. The parser produces *surface* nodes
 * ([AttributeRef], [Literal] with value == null, [Call]); the resolver rewrites
 * them in place into *resolved* nodes ([Attribute], [Literal] with value/type
 * filled, [Call]/[Aggregation] with type filled). Everything after the
 * Validator may assume resolved nodes only.
 */
sealed interface PqlExpression {
    val location: SourceLocation
    val type: Type // Type.UNKNOWN until resolved

    /** Surface attribute reference — parser output, resolver input. (former RawAttributeRef) */
    data class AttributeRef(
        val rawText: String,
        val hoisting: Int,
        val scopeHint: String?,
        val name: String,
        val wasBracketed: Boolean,
        override val location: SourceLocation,
    ) : PqlExpression {
        override val type: Type get() = Type.UNKNOWN
    }

    /** Resolved attribute — fields exactly as in the former ResolvedAttribute. */
    data class Attribute(
        val name: String,
        val baseScope: Scope,
        val effectiveScope: Scope,
        val kind: AttributeKind,
        val xesStandardName: String?,
        val wasBracketed: Boolean,
        val classifierName: String? = null,
        val classifierKeys: List<String> = emptyList(),
        override val type: Type,
        override val location: SourceLocation,
    ) : PqlExpression

    /**
     * Literal. Parser sets rawText/kind, leaves value null + type UNKNOWN;
     * the resolver fills value/type/scope/pqlText. (former RawLiteral + TypedLiteral)
     */
    data class Literal(
        val rawText: String,
        val kind: LiteralKind,
        val value: Any? = null,
        val scope: Scope? = null,
        /**
         * Canonical PQL rendering of the resolved value, used as the expression
         * key in generated Cypher. The resolver must set this to the parsed
         * value's textual form (`value?.toString() ?: "null"`) — the [rawText]
         * default is only correct for surface nodes; for a STRING literal it
         * still carries the source quotes.
         */
        val pqlText: String = rawText,
        override val type: Type = Type.UNKNOWN,
        override val location: SourceLocation,
    ) : PqlExpression

    enum class LiteralKind { STRING, NUMBER, DATETIME, BOOLEAN, NULL, UUID }

    data class Binary(
        val op: BinaryOperator,
        val left: PqlExpression,
        val right: PqlExpression,
        override val type: Type = Type.UNKNOWN,
        override val location: SourceLocation,
    ) : PqlExpression

    data class Unary(
        val op: UnaryOperator,
        val operand: PqlExpression,
        override val type: Type = Type.UNKNOWN,
        override val location: SourceLocation,
    ) : PqlExpression

    /** Function call. Parser output for ALL calls; resolver rewrites aggregates to [Aggregation]. */
    data class Call(
        val name: String,
        val arguments: List<PqlExpression>,
        val scope: Scope? = null,
        override val type: Type = Type.UNKNOWN,
        override val location: SourceLocation,
    ) : PqlExpression

    /** count/sum/avg/min/max — produced only by the resolver. (former resolved.Aggregation) */
    data class Aggregation(
        val name: String,
        val argument: PqlExpression,
        val scope: Scope? = null,
        override val type: Type,
        override val location: SourceLocation,
    ) : PqlExpression

    data class InList(
        val values: List<PqlExpression>,
        override val location: SourceLocation,
    ) : PqlExpression {
        override val type: Type get() = Type.UNKNOWN
    }
}
