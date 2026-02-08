package com.processm.processminterpreter.pql.model

/**
 * Represents an attribute reference in a PQL query.
 *
 * Attributes can have:
 * - Scope prefix: "e:name", "t:name", "l:name"
 * - Hoisting prefix: "^e:name" (raises scope by one), "^^e:name" (raises by two)
 * - No prefix: "name" (defaults to EVENT scope)
 * - Classifier: "c:businesscase" or "classifier:activity"
 * - Multi-part names: "org:group" (XES extension:attribute format)
 *
 * Examples:
 * - "e:name" → EVENT scope, standard attribute (concept:name)
 * - "^e:name" → TRACE scope (hoisted from EVENT)
 * - "^^e:name" → LOG scope (hoisted twice from EVENT)
 * - "t:timestamp" → TRACE scope, standard attribute (time:timestamp)
 * - "e:org:group" → EVENT scope, standard attribute (org:group)
 * - "e:customAttribute" → EVENT scope, custom (non-standard) attribute
 * - "c:businesscase" → Classifier attribute
 *
 * Based on ProcessM: https://github.com/ProcessMPUT/processm
 */
class Attribute(
    attributeStr: String,
    override val line: Int = -1,
    override val charPositionInLine: Int = -1,
) : Expression(line, charPositionInLine) {

    // Handle bracket notation: [^trace:name with spaces]
    private val wasBracketed = attributeStr.startsWith("[") && attributeStr.endsWith("]")
    private val cleanAttributeStr = if (wasBracketed) {
        attributeStr.substring(1, attributeStr.length - 1)
    } else {
        attributeStr
    }

    // Regex to parse: [^]* [scope:]name
    // Groups: (hoisting) (scope:) (name)
    // Changed \S+ to .+ to allow spaces and special characters
    private val regex = Regex("^(\\^*)(?:([a-zA-Z]+):)?(.+)$")
    private val match = if (cleanAttributeStr.isBlank()) {
        throw PQLSyntaxException(line, charPositionInLine, "Attribute name cannot be empty")
    } else {
        regex.find(cleanAttributeStr) ?: throw PQLSyntaxException(
            line,
            charPositionInLine,
            "Invalid attribute syntax: $attributeStr",
        )
    }
    


    /**
     * Hoisting prefix: "", "^", or "^^"
     * Each ^ raises the scope by one level (EVENT → TRACE → LOG)
     */
    val hoistingPrefix: String = match.groupValues[1]

    private val parsedScopeAndName: Pair<Scope?, String> = run {
        val scopeStr = match.groupValues[2]
        val nameStr = match.groupValues[3]

        if (scopeStr.isEmpty()) {
            Pair(null, nameStr)
        } else {
            try {
                val s = Scope.parse(scopeStr)
                Pair(s, nameStr)
            } catch (e: IllegalArgumentException) {
                // If scope is invalid (e.g. "org" in "org:group"), treat it as part of the name
                Pair(null, "$scopeStr:$nameStr")
            }
        }
    }

    /**
     * The attribute name (after scope prefix, if any).
     *
     * Examples:
     * - "e:name" → "name"
     * - "e:org:group" → "org:group"
     * - "customAttr" → "customAttr"
     * - "org:group" → "org:group" (if org is not a scope)
     */
    val name: String = parsedScopeAndName.second.also {
        if (!wasBracketed && it.contains(" ")) {
            throw PQLSyntaxException(line, charPositionInLine, "Attribute name cannot contain spaces unless bracketed: $attributeStr")
        }
    }

    /**
     * Base scope (before hoisting is applied).
     * null if no scope prefix was specified.
     *
     * Examples:
     * - "e:name" → EVENT
     * - "t:timestamp" → TRACE
     * - "name" → null (will default to EVENT)
     */
    private val baseScope: Scope? = parsedScopeAndName.first

    /**
     * Actual scope after applying hoisting.
     *
     * Process:
     * 1. Start with scope (base)
     * 2. Apply each ^ by moving up the hierarchy
     * 3. Validate we don't hoist beyond LOG
     *
     * Examples:
     * - "e:name" → EVENT
     * - "^e:name" → TRACE (EVENT.upper)
     * - "^^e:name" → LOG (EVENT.upper.upper)
     */
    /**
     * Base scope (before hoisting is applied).
     * Examples:
     * - "e:name" → EVENT
     * - "^e:name" → EVENT (hoisting doesn't change declared scope)
     */
    override val scope: Scope = baseScope ?: Scope.Event

    /**
     * Actual scope after applying hoisting.
     *
     * Process:
     * 1. Start with scope (base)
     * 2. Apply each ^ by moving up the hierarchy
     * 3. Validate we don't hoist beyond LOG
     *
     * Examples:
     * - "e:name" → EVENT
     * - "^e:name" → TRACE (EVENT.upper)
     * - "^^e:name" → LOG (EVENT.upper.upper)
     */
    override val effectiveScope: Scope? = run {
        var currentScope = scope
        for (i in hoistingPrefix.indices) {
            currentScope = currentScope.upper
                ?: throw InvalidScopeHoistingException(
                    "Cannot hoist scope '$scope' beyond LOG (hoisting: '$hoistingPrefix')",
                )
        }
        currentScope
    }

    /**
     * Is this a standard XES attribute?
     *
     * Standard attributes can be:
     * - Shorthand names (e.g., "name", "timestamp", "group")
     * - Full XES names (e.g., "org:group", "cost:total")
     *
     * Examples:
     * - "e:name" → true (concept:name shorthand)
     * - "e:timestamp" → true (time:timestamp shorthand)
     * - "e:org:group" → true (org:group full XES name)
     * - "e:customAttr" → false
     *
     * ProcessM Rule: If attribute is NOT bracketed and NOT standard,
     * it throws PQLSyntaxException.Problem.NoSuchAttribute.
     * Use brackets [e:customAttr] for non-standard (custom) attributes.
     */
    val isStandard: Boolean = run {
        // If attribute was bracketed, treat as non-standard (force custom)
        if (wasBracketed) return@run false

        // Use base scope (before hoisting) to check if attribute is standard
        // For example, ^^e:timestamp should check if "timestamp" is standard for EVENT, not LOG
        val scopeToCheck = baseScope ?: Scope.Event

        // First check if it's a shorthand
        if (StandardAttributes.isStandard(scopeToCheck, name)) {
            return@run true
        }

        // Check if it's a full XES standard name (like org:group, cost:total)
        // These appear in ATTRIBUTE_TYPES map
        if (StandardAttributes.ATTRIBUTE_TYPES.containsKey(name)) {
            return@run true
        }

        // Not a standard attribute - treat as custom attribute
        // Custom attributes map directly to Neo4j property names
        false
    }

    /**
     * Is this a classifier attribute?
     *
     * Classifiers start with "c:" or "classifier:"
     *
     * Examples:
     * - "c:businesscase" → true
     * - "classifier:activity_resource" → true
     * - "e:name" → false
     */
    val isClassifier: Boolean by lazy {
        StandardAttributes.isClassifier(name)
    }

    /**
     * The XES standard name for this attribute (if it's a standard attribute).
     *
     * Examples:
     * - "e:name" → "concept:name"
     * - "e:timestamp" → "time:timestamp"
     * - "e:group" → "org:group"
     * - "e:org:group" → "org:group" (already full XES name)
     * - "e:customAttr" → "" (not standard)
     */
    val standardName: String = run {
        if (!isStandard) return@run ""

        // Use base scope (before hoisting) for mapping
        val scopeToCheck = baseScope ?: Scope.Event

        // Try to get from shorthand mapping first
        StandardAttributes.getStandardName(scopeToCheck, name)?.let { return@run it }

        // If not found, check if name itself is already a full XES name
        if (StandardAttributes.ATTRIBUTE_TYPES.containsKey(name)) {
            return@run name
        }

        ""
    }

    /**
     * The data type of this attribute.
     *
     * - Standard attributes have known types (from StandardAttributes)
     * - Custom attributes have UNKNOWN type
     */
    override val type: Type
        get() = if (isStandard && standardName.isNotEmpty()) {
            StandardAttributes.getType(standardName)
        } else {
            Type.UNKNOWN
        }

    /**
     * Get the Neo4j property name for this attribute.
     *
     * For standard attributes:
     * - Uses StandardAttributes mappings
     *
     * For custom attributes:
     * - Sanitizes colons to underscores (org:group → org_group)
     *
     * @return the Neo4j property name
     */
    fun toNeo4jProperty(): String {
        // Use effectiveScope to handle hoisting (e.g. ^e:name -> TRACE scope -> caseId)
        val targetScope = effectiveScope ?: scope
        return if (isStandard) {
            StandardAttributes.getNeo4jPropertyFromShorthand(targetScope, name)
                ?: name.replace(":", "_")
        } else {
            name.replace(":", "_")
        }
    }

    /**
     * String representation of this attribute.
     *
     * ProcessM compatibility: Uses full scope names ("event:", "trace:", "log:")
     * and preserves bracket notation for non-standard attributes.
     *
     * Examples:
     * - "e:name" → "event:concept:name" (standard, expanded)
     * - "^e:name" → "^event:concept:name"
     * - "[e:custom]" → "[event:custom]" (bracketed, preserved)
     * - "e:classifier:main" → "event:classifier:main"
     */
    override fun toString(): String {
        // Use full scope name for ProcessM compatibility
        val scopePrefix = scope.toString() + ":"
        val attrName = if (isStandard && standardName.isNotEmpty()) {
            standardName
        } else {
            name
        }
        val content = "$hoistingPrefix$scopePrefix$attrName"
        return if (wasBracketed) "[$content]" else content
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Attribute) return false

        return hoistingPrefix == other.hoistingPrefix &&
            baseScope == other.baseScope &&
            name == other.name
    }

    override fun hashCode(): Int {
        var result = hoistingPrefix.hashCode()
        result = 31 * result + (baseScope?.hashCode() ?: 0)
        result = 31 * result + name.hashCode()
        return result
    }

    /**
     * Returns a new Attribute without hoisting prefix.
     *
     * ProcessM compatibility: Creates a copy of this attribute with
     * hoisting removed. The resulting attribute will have the same
     * base scope and name, but without any ^ prefixes.
     *
     * Examples:
     * - "^e:name".dropHoisting() → "e:name"
     * - "^^e:timestamp".dropHoisting() → "e:timestamp"
     * - "e:name".dropHoisting() → "e:name" (unchanged)
     *
     * @return a new Attribute without hoisting, or this if no hoisting
     */
    fun dropHoisting(): Attribute {
        if (hoistingPrefix.isEmpty()) {
            return this
        }
        // Reconstruct the attribute string without hoisting
        val scopePrefix = baseScope?.shortName?.let { "$it:" } ?: ""
        val attrStr = if (wasBracketed) "[$scopePrefix$name]" else "$scopePrefix$name"
        return Attribute(attrStr, line, charPositionInLine)
    }
}
