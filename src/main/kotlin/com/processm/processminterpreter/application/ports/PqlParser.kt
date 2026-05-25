package com.processm.processminterpreter.application.ports

import com.processm.processminterpreter.domain.pql.syntax.RawQuery

fun interface PqlParser {
    fun parse(source: String): RawQuery
}
