package com.processm.processminterpreter.domain.log.xes

/**
 * ProcessM-compatible flat key encoding for addressing child attributes in PQL.
 *
 * XES keeps child attributes structurally under [XesAttributeValue.children].
 * Neo4j also stores a flat query-only property using this key shape so filters
 * such as `[l:\u001fsparent\u001fchild] = 'value'` can be evaluated without
 * decoding the parent JSON in Cypher.
 */
data class XesNestedAttributePath(
    val parentKey: String,
    val childKey: String,
) {
    companion object {
        private const val SEPARATOR_CHAR: Char = '\u001f'
        private const val STRING_MARKER: Char = 's'

        fun encodedChildKey(parentKey: String, childKey: String): String =
            "$SEPARATOR_CHAR$STRING_MARKER$parentKey$SEPARATOR_CHAR$childKey"

        fun parseEncoded(rawKey: String): XesNestedAttributePath? {
            if (!rawKey.startsWith("$SEPARATOR_CHAR$STRING_MARKER")) return null
            val childSeparator = rawKey.indexOf(SEPARATOR_CHAR, startIndex = 2)
            if (childSeparator < 0) return null

            val parent = rawKey.substring(2, childSeparator)
            val child = rawKey.substring(childSeparator + 1)
            if (parent.isEmpty() || child.isEmpty()) return null

            return XesNestedAttributePath(parentKey = parent, childKey = child)
        }
    }
}
