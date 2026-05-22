package com.processm.processminterpreter.application.query

import com.processm.processminterpreter.domain.pql.syntax.RawQuery

interface PqlParser {
    fun parse(source: String): RawQuery
}
