package com.processm.processminterpreter.pql.model

// Based on ProcessM: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/main/kotlin/processm/core/querylanguage/Type.kt
enum class Type {
    STRING,
    NUMBER,
    BOOLEAN,
    DATETIME,
    UUID,
    ANY, // aggregation functions that can return any type (min/max)
    UNKNOWN, // default when type cannot be determined
}