package com.processm.processminterpreter.domain.pql.resolved

import com.processm.processminterpreter.domain.pql.catalog.AttributeKind
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.catalog.Type

/**
 * Semantically resolved attribute reference.
 *
 *  - [baseScope] is the scope written in source (or inferred from FROM if none was specified).
 *  - [effectiveScope] is the scope after applying hoisting; equal to [baseScope] when hoisting=0.
 *  - [kind] classifies the attribute as STANDARD / CUSTOM / CLASSIFIER / SYSTEM.
 *  - [xesStandardName] carries the canonical XES name (e.g. `concept:name`) when kind == STANDARD, null otherwise.
 *  - [classifierName] marks references that were written through a classifier, even
 *    if a single-key classifier was resolved to its underlying standard/custom key.
 *
 * Carries no Neo4j-specific details - physical property mapping is the adapter's job.
 */
data class ResolvedAttribute(
    val name: String,
    val baseScope: Scope,
    val effectiveScope: Scope,
    val kind: AttributeKind,
    val xesStandardName: String?,
    val wasBracketed: Boolean,
    val classifierName: String? = null,
    val classifierKeys: List<String> = emptyList(),
    override val type: Type,
    override val location: SourceLocation,
) : ResolvedExpression
