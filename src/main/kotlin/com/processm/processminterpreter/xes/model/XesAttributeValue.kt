package com.processm.processminterpreter.xes.model

/**
 * XES allows any attribute to carry child attributes. The scalar [value] is still
 * the attribute value used by normal queries; [children] preserves the nested XES
 * structure for round-tripping and ProcessM-compatible hierarchy reads.
 */
data class XesAttributeValue(
    val value: Any?,
    val children: Map<String, Any?> = emptyMap(),
)
