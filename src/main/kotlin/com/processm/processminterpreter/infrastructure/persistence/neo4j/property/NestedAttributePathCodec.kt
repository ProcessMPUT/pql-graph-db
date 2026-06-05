package com.processm.processminterpreter.infrastructure.persistence.neo4j.property

/**
 * ProcessM-compatible flat encoding for nested XES attribute paths in Neo4j.
 *
 * XES allows any attribute to carry child attributes (see
 * `com.processm.processminterpreter.domain.log.xes.XesAttributeValue`).
 * To make those queryable in Cypher without decoding the parent JSON, we additionally
 * write a flat property whose key encodes (parent, child) using ASCII US (0x1F) as a
 * structural separator and a leading `s` marker. Filters like `[l:foo].child = 'value'`
 * compile against this flat property.
 *
 * The codec is purely a storage detail of the Neo4j adapter — the domain only deals
 * in (parentKey, childKey) tuples and is not aware of the underlying byte layout.
 */
object NestedAttributePathCodec {
    private const val SEPARATOR_CHAR: Char = ''
    private const val STRING_MARKER: Char = 's'

    /** Build the flat physical key for a (parent, child) attribute path. */
    fun encodedChildKey(parentKey: String, childKey: String): String =
        "$SEPARATOR_CHAR$STRING_MARKER$parentKey$SEPARATOR_CHAR$childKey"

    /**
     * Recover (parentKey, childKey) from an encoded flat key, or null when the input
     * is not in encoded form (a normal property key).
     */
    fun parseEncoded(rawKey: String): NestedAttributePath? {
        if (!rawKey.startsWith("$SEPARATOR_CHAR$STRING_MARKER")) return null
        val childSeparator = rawKey.indexOf(SEPARATOR_CHAR, startIndex = 2)
        if (childSeparator < 0) return null

        val parent = rawKey.substring(2, childSeparator)
        val child = rawKey.substring(childSeparator + 1)
        if (parent.isEmpty() || child.isEmpty()) return null

        return NestedAttributePath(parentKey = parent, childKey = child)
    }
}

data class NestedAttributePath(
    val parentKey: String,
    val childKey: String,
)
