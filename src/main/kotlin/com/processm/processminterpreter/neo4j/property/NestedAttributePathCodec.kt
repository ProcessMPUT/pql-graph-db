package com.processm.processminterpreter.neo4j.property

/**
 * ProcessM-compatible flat encoding for nested XES attribute paths in Neo4j.
 *
 * XES allows any attribute to carry child attributes (see
 * `com.processm.processminterpreter.xes.model.XesAttributeValue`).
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
     * True when [rawKey] is a flat-encoded nested-attribute key (as produced by
     * [encodedChildKey]) rather than a normal property key. Callers only need to
     * distinguish encoded keys — the (parent, child) components are not consumed
     * anywhere, so they are not reconstructed.
     */
    fun isEncoded(rawKey: String): Boolean {
        if (!rawKey.startsWith("$SEPARATOR_CHAR$STRING_MARKER")) return false
        val childSeparator = rawKey.indexOf(SEPARATOR_CHAR, startIndex = 2)
        if (childSeparator < 0) return false
        val parent = rawKey.substring(2, childSeparator)
        val child = rawKey.substring(childSeparator + 1)
        return parent.isNotEmpty() && child.isNotEmpty()
    }
}
