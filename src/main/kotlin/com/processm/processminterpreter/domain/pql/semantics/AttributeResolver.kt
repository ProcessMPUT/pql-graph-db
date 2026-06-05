package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.syntax.RawAttributeRef
import com.processm.processminterpreter.domain.pql.catalog.AttributeKind
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.domain.pql.catalog.SystemAttributeCatalog
import com.processm.processminterpreter.domain.pql.catalog.Type
import com.processm.processminterpreter.domain.pql.error.PQLSyntaxException
import com.processm.processminterpreter.domain.pql.error.Problem
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute

/**
 * Resolves a single [RawAttributeRef] into a [ResolvedAttribute] with absolute scope,
 * canonical logical name and classification.
 *
 * Responsibilities:
 *  - pick the base scope from [RawAttributeRef.scopeHint] (when written) or the given default
 *  - apply hoisting via [HoistingResolver]
 *  - recognize classifier-prefixed names (`c:*`, `classifier:*`) as CLASSIFIER
 *  - look up the name in the standard-attribute catalog (expanding shorthand like `name` -> `concept:name`)
 *  - look up PQL-visible system attributes such as `l:logId`
 *  - reject unbracketed non-standard names (ProcessM-compatible NoSuchAttribute)
 *  - accept bracketed non-standard names as CUSTOM
 *
 * Kept as a class (not object) with catalogs injected to keep tests hermetic.
 */
class AttributeResolver(
    private val standardAttributes: StandardAttributeCatalog = StandardAttributeCatalog,
    private val systemAttributes: SystemAttributeCatalog = SystemAttributeCatalog,
) {

    fun resolve(
        raw: RawAttributeRef,
        defaultScope: Scope,
        context: ResolutionContext = ResolutionContext(),
    ): ResolvedAttribute {
        val baseScope = raw.scopeHint?.let { Scope.parse(it) } ?: defaultScope
        val effectiveScope = HoistingResolver.apply(baseScope, raw.hoisting, raw.location)

        if (StandardAttributeCatalog.isClassifier(raw.name)) {
            if (baseScope == Scope.LOG) {
                throw PQLSyntaxException(
                    Problem.ClassifierOnLog,
                    raw.location,
                    "Classifiers are defined for event attributes, not log attributes",
                )
            }
            val classifierName = raw.name.removePrefix("classifier:").removePrefix("c:")
            if (classifierName in context.ambiguousClassifierNames) {
                throw PQLSyntaxException(
                    Problem.InvalidUseOfClassifiers,
                    raw.location,
                    "Classifier '$classifierName' has multiple definitions in the selected query scope",
                )
            }
            val classifier = context.classifiers.firstOrNull { it.name == classifierName }
                ?: throw PQLSyntaxException(
                    Problem.InvalidUseOfClassifiers,
                    raw.location,
                    "Classifier '$classifierName' not found",
                )
            if (classifier.keys.size == 1) {
                val classifierKey = classifier.keys.single()
                val standard = standardAttributes.lookup(baseScope, classifierKey)
                if (standard != null) {
                    return ResolvedAttribute(
                        name = classifierKey,
                        baseScope = baseScope,
                        effectiveScope = effectiveScope,
                        kind = AttributeKind.STANDARD,
                        xesStandardName = standard.canonicalName,
                        wasBracketed = raw.wasBracketed,
                        classifierName = classifierName,
                        classifierKeys = classifier.keys,
                        type = standard.type,
                        location = raw.location,
                    )
                }
                return ResolvedAttribute(
                    name = classifierKey,
                    baseScope = baseScope,
                    effectiveScope = effectiveScope,
                    kind = AttributeKind.CUSTOM,
                    xesStandardName = null,
                    wasBracketed = true,
                    classifierName = classifierName,
                    classifierKeys = classifier.keys,
                    type = Type.UNKNOWN,
                    location = raw.location,
                )
            }
            return ResolvedAttribute(
                name = raw.name,
                baseScope = baseScope,
                effectiveScope = effectiveScope,
                kind = AttributeKind.CLASSIFIER,
                xesStandardName = null,
                wasBracketed = raw.wasBracketed,
                classifierName = classifierName,
                classifierKeys = classifier.keys,
                type = Type.STRING,
                location = raw.location,
            )
        }

        val standard = standardAttributes.lookup(baseScope, raw.name)
        if (standard != null) {
            return ResolvedAttribute(
                name = raw.name,
                baseScope = baseScope,
                effectiveScope = effectiveScope,
                kind = AttributeKind.STANDARD,
                xesStandardName = standard.canonicalName,
                wasBracketed = raw.wasBracketed,
                type = standard.type,
                location = raw.location,
            )
        }

        val system = systemAttributes.lookup(baseScope, raw.name)
        if (system != null) {
            return ResolvedAttribute(
                name = system.name,
                baseScope = baseScope,
                effectiveScope = effectiveScope,
                kind = AttributeKind.SYSTEM,
                xesStandardName = null,
                wasBracketed = raw.wasBracketed,
                type = system.type,
                location = raw.location,
            )
        }

        if (!raw.wasBracketed) {
            throw PQLSyntaxException(
                Problem.NoSuchAttribute,
                raw.location,
                "Attribute '${raw.name}' is not a standard XES attribute at $baseScope scope; " +
                    "use [${raw.name}] to reference a custom attribute",
            )
        }

        return ResolvedAttribute(
            name = raw.name,
            baseScope = baseScope,
            effectiveScope = effectiveScope,
            kind = AttributeKind.CUSTOM,
            xesStandardName = null,
            wasBracketed = true,
            type = Type.UNKNOWN,
            location = raw.location,
        )
    }
}
