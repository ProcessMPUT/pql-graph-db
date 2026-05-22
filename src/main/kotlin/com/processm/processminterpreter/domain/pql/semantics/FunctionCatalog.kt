package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.catalog.Type

/**
 * Catalog of PQL function names — distinguishes scalar functions from aggregations
 * and provides a best-effort return-type hint.
 *
 * Kept as a single object: function taxonomy is a fixed domain rule, not a policy.
 * Scalar function return types follow ProcessM conventions:
 *  - date/time extraction (year/month/.../dayofweek): NUMBER
 *  - string (upper/lower): STRING
 *  - math (round): NUMBER
 *  - now(): DATETIME
 *
 * For unknown functions the catalog returns null so callers can decide to throw or
 * pass through.
 */
object FunctionCatalog {

    private val aggregations = setOf("count", "sum", "avg", "min", "max")

    private val scalarTypes: Map<String, Type> = mapOf(
        "year" to Type.NUMBER, "month" to Type.NUMBER, "day" to Type.NUMBER,
        "hour" to Type.NUMBER, "minute" to Type.NUMBER, "second" to Type.NUMBER,
        "millisecond" to Type.NUMBER, "quarter" to Type.NUMBER,
        "dayofweek" to Type.NUMBER, "date" to Type.DATE, "time" to Type.TIME,
        "now" to Type.DATETIME,
        "upper" to Type.STRING, "lower" to Type.STRING,
        "round" to Type.NUMBER,
    )

    fun isAggregation(name: String): Boolean = name.lowercase() in aggregations

    /** Aggregation return type: count -> INTEGER; sum/avg -> NUMBER; min/max -> operand's type. */
    fun aggregationReturnType(name: String, argType: Type): Type = when (name.lowercase()) {
        "count" -> Type.INTEGER
        "sum", "avg" -> Type.NUMBER
        "min", "max" -> argType
        else -> Type.UNKNOWN
    }

    /** Scalar function return type, or null if unknown. */
    fun scalarReturnType(name: String): Type? = scalarTypes[name.lowercase()]
}
