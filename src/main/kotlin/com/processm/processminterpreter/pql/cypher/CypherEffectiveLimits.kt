package com.processm.processminterpreter.pql.cypher

internal object CypherEffectiveLimits {
    fun log(s: CypherBuildState): Long? =
        capped(s.plan.limits.log, s.plan.defaultLimits.log)

    fun trace(s: CypherBuildState): Long? =
        capped(s.plan.limits.trace, s.plan.defaultLimits.trace)

    fun event(s: CypherBuildState): Long? =
        capped(s.plan.limits.event, s.plan.defaultLimits.event)

    private fun capped(explicit: Long?, default: Long?): Long? =
        listOfNotNull(explicit, default).filter { it >= 0 }.minOrNull()
}
