package com.processm.processminterpreter.neo4j.query

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.XesAttributeReadMode

data class ExecutionOptions(
    val defaultLimits: HierarchicalLimits = HierarchicalLimits(),
    val materializedScopes: Set<Scope> = emptySet(),
    val attributeReadMode: XesAttributeReadMode = XesAttributeReadMode.FULL_XES,
)
