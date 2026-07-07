package com.processm.processminterpreter.pql

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.common.HierarchicalLimits

data class ExecutePqlQueryRequest(
    val query: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
    val defaultLimits: HierarchicalLimits = HierarchicalLimits(),
    val materializedScopes: Set<Scope> = FULL_HIERARCHY_SCOPES,
)

private val FULL_HIERARCHY_SCOPES: Set<Scope> =
    setOf(Scope.LOG, Scope.TRACE, Scope.EVENT)
