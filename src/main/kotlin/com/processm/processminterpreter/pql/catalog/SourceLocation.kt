package com.processm.processminterpreter.pql.catalog

/**
 * Position in PQL source text. Carried by every IR node so that later-phase errors
 * can point back to the original location.
 */
data class SourceLocation(
    val line: Int,
    val charPositionInLine: Int,
) {
    companion object {
        val UNKNOWN = SourceLocation(-1, -1)
    }
}
