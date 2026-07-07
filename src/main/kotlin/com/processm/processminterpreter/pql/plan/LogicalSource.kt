package com.processm.processminterpreter.pql.plan

import com.processm.processminterpreter.pql.catalog.Scope

/**
 * Describes which hierarchy levels a query touches.
 *
 *  - [fromScope] is the FROM clause scope.
 *  - [usedScopes] is the transitive set of scopes referenced by any clause of the query.
 *  - [logId] optionally scopes execution to a single log.
 *  - [dataStoreId] optionally scopes execution to logs contained in one ProcessM data store.
 */
data class LogicalSource(
    val fromScope: Scope,
    val usedScopes: Set<Scope>,
    val logId: String? = null,
    val dataStoreId: String? = null,
)
