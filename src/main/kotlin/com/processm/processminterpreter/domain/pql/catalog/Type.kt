package com.processm.processminterpreter.domain.pql.catalog

/**
 * PQL-level data type of an expression or attribute.
 * UNKNOWN is used for custom attributes and values whose type cannot be determined at compile time.
 */
enum class Type {
    STRING, NUMBER, INTEGER, DATETIME, DATE, TIME, BOOLEAN, ID, NULL, UNKNOWN
}
