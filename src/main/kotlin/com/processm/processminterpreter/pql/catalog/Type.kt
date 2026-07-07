package com.processm.processminterpreter.pql.catalog

/**
 * PQL-level data type of an expression or attribute.
 * UNKNOWN is used for custom attributes and values whose type cannot be determined at compile time.
 */
enum class Type {
    STRING, NUMBER, INTEGER, DATETIME, DATE, TIME, BOOLEAN, ID, NULL, UNKNOWN,
    ;

    /** True for both [NUMBER] (floating) and [INTEGER]. Used by renderers to pick numeric code paths. */
    val isNumeric: Boolean get() = this == NUMBER || this == INTEGER

    /** True for any time-like type: full timestamps and the date / time projections. */
    val isTemporal: Boolean get() = this == DATETIME || this == DATE || this == TIME
}
