package com.processm.processminterpreter.domain.pql.catalog

/**
 * PQL-visible structural attributes that belong to the application's query model
 * but are not XES payload attributes.
 */
object SystemAttributeCatalog {
    const val LOG_ID = "logId"

    fun lookup(scope: Scope, name: String): SystemAttribute? =
        when {
            scope == Scope.LOG && name == LOG_ID -> SystemAttribute(
                scope = Scope.LOG,
                name = LOG_ID,
                type = Type.ID,
            )
            else -> null
        }
}

data class SystemAttribute(
    val scope: Scope,
    val name: String,
    val type: Type,
)
