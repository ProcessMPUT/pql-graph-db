package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

internal object CypherLikePattern {
    fun toRegex(pattern: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    val next = pattern[i + 1]
                    if (next == '%' || next == '_') {
                        out.append(java.util.regex.Pattern.quote(next.toString()))
                    } else {
                        out.append(java.util.regex.Pattern.quote("\\$next"))
                    }
                    i += 2
                }
                c == '%' -> {
                    out.append(".*")
                    i++
                }
                c == '_' -> {
                    out.append('.')
                    i++
                }
                else -> {
                    out.append(java.util.regex.Pattern.quote(c.toString()))
                    i++
                }
            }
        }
        return out.toString()
    }
}
