package com.processm.processminterpreter.neo4j.query.result

import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.cypher.ColumnAlias
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_EVENT_ALIAS
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_LOG_ID_ALIAS
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_LOG_METADATA_ALIAS
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_LOG_NODE_ALIAS
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_NULL_EVENT_COUNT_ALIAS
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_TRACE_COUNT_ALIAS

internal class ProjectedRowHierarchyBuilder {
    fun reconstruct(
        rows: List<Map<String, Any?>>,
        columnAliases: Map<String, ColumnAlias>,
        selectAllScopes: Set<Scope> = emptySet(),
    ): List<XesLog> {
        val accumulator = accumulator(columnAliases, selectAllScopes)
        rows.forEach(accumulator::absorb)
        return accumulator.build()
    }

    fun accumulator(
        columnAliases: Map<String, ColumnAlias>,
        selectAllScopes: Set<Scope> = emptySet(),
    ): Accumulator = Accumulator(
        aliases = ProjectedAliases(columnAliases),
        selectAllScopes = selectAllScopes,
    )

    class Accumulator(
        private val aliases: ProjectedAliases,
        private val selectAllScopes: Set<Scope>,
    ) {
        private val hasUserEventProjection = aliases.hasUserEventProjection(selectAllScopes)
        private val logs = linkedMapOf<Any?, LogBuilder>()
        private val logsById = mutableMapOf<String, MutableSet<LogBuilder>>()

        fun absorb(row: Map<String, Any?>) {
            val logBuilder = absorbLog(row)
            val traceBuilder = absorbTrace(row, logBuilder)
            traceBuilder.absorbTraceCount(row)
            traceBuilder.absorbNullEventCount(row)
            traceBuilder.absorbEvent(row)
        }

        /**
         * Late-merges full log properties fetched once per distinct log (see
         * `CypherQuery.hydrateLogProperties`) into every builder keyed by that
         * `_log_id_` hierarchy key. Must be called before [build].
         */
        fun hydrateLog(logId: String, properties: Map<String, Any?>) {
            logsById[logId]?.forEach { it.absorbNode(properties) }
        }

        private fun absorbLog(row: Map<String, Any?>): LogBuilder {
            val logKey = row.keyFor(aliases.log)
            val logBuilder = logs.getOrPut(logKey) { LogBuilder() }
            (row[SYNTHETIC_LOG_ID_ALIAS] as? String)?.let { logId ->
                logsById.getOrPut(logId, ::linkedSetOf).add(logBuilder)
            }
            logBuilder.absorbMetadataNode(row.nodeProperties(SYNTHETIC_LOG_METADATA_ALIAS))
            logBuilder.absorbNode(row.nodeProperties(SYNTHETIC_LOG_NODE_ALIAS))
            if (Scope.LOG in selectAllScopes) logBuilder.absorbNode(row.nodeProperties("log"))
            logBuilder.absorb(row, aliases.logAbsorb)
            return logBuilder
        }

        private fun absorbTrace(row: Map<String, Any?>, logBuilder: LogBuilder): TraceBuilder {
            val traceKey = row.keyFor(aliases.trace)
            val traceBuilder = logBuilder.traces.getOrPut(traceKey) { TraceBuilder() }
            if (Scope.TRACE in selectAllScopes) traceBuilder.absorbNode(row.nodeProperties("trace"))
            traceBuilder.absorb(row, aliases.traceAbsorb)
            return traceBuilder
        }

        private fun TraceBuilder.absorbNullEventCount(row: Map<String, Any?>) {
            if (hasUserEventProjection) return
            val count = row[SYNTHETIC_NULL_EVENT_COUNT_ALIAS] as? Number ?: return
            nullEventCount = maxOf(nullEventCount, count.toInt())
        }

        private fun TraceBuilder.absorbTraceCount(row: Map<String, Any?>) {
            val groupedCount = row[SYNTHETIC_TRACE_COUNT_ALIAS] as? Number ?: return
            count = maxOf(count, groupedCount.toInt())
        }

        private fun TraceBuilder.absorbEvent(row: Map<String, Any?>) {
            if (!aliases.shouldMaterializeEvent(row, hasUserEventProjection)) return
            val eventBuilder = EventBuilder()
            if (Scope.EVENT in selectAllScopes) eventBuilder.absorbNode(row.nodeProperties("event"))
            if (aliases.eventAbsorb.isNotEmpty()) {
                eventBuilder.absorb(row, aliases.eventAbsorb)
            }
            events.add(eventBuilder)
        }

        fun build(): List<XesLog> = logs.values.map { it.build() }

        /**
         * The common hierarchy key is a single synthetic id. Keep that scalar
         * directly instead of allocating a one-entry map for every result row.
         * Multi-column grouping still uses an ordered value list, whose equality
         * is equivalent because [aliases] has one stable column order per query.
         */
        private fun Map<String, Any?>.keyFor(aliases: Map<String, ColumnAlias>): Any? =
            when (aliases.size) {
                0 -> SyntheticHierarchyKey
                1 -> this[aliases.keys.first()]
                else -> aliases.keys.map { this[it] }
            }
    }

    class ProjectedAliases(columnAliases: Map<String, ColumnAlias>) {
        val log = columnAliases.filterValues { it.scope == Scope.LOG }
        val trace = columnAliases.filterValues { it.scope == Scope.TRACE }
        val event = columnAliases.filterValues { it.scope == Scope.EVENT }

        val logAbsorb = log.filterValues { !it.synthetic }
        val traceAbsorb = trace.filterValues { !it.synthetic }
        val eventAbsorb = event.filterValues { !it.synthetic }

        private val hasHierarchyKeys = log.isNotEmpty() || trace.isNotEmpty()
        private val hasSyntheticEventPlaceholder = event.any { (col, alias) ->
            col == SYNTHETIC_EVENT_ALIAS && alias.synthetic
        }

        fun hasUserEventProjection(selectAllScopes: Set<Scope>): Boolean =
            eventAbsorb.isNotEmpty() || Scope.EVENT in selectAllScopes

        fun shouldMaterializeEvent(row: Map<String, Any?>, hasUserEventProjection: Boolean): Boolean =
            hasUserEventProjection ||
                !hasHierarchyKeys ||
                (hasSyntheticEventPlaceholder && row.hasNodeColumn("event"))
    }
}
