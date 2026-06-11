package com.processm.processminterpreter.application.query

import com.processm.processminterpreter.application.ports.DataStoreRepository
import com.processm.processminterpreter.application.ports.LogRepository
import com.processm.processminterpreter.application.ports.PqlParser
import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.log.Log
import com.processm.processminterpreter.domain.pql.common.HierarchicalLimits
import com.processm.processminterpreter.domain.pql.common.HierarchicalOffsets
import com.processm.processminterpreter.domain.pql.plan.CandidateLogPlan
import com.processm.processminterpreter.domain.pql.plan.LogicalPlan
import com.processm.processminterpreter.domain.pql.semantics.AttributeResolver
import com.processm.processminterpreter.domain.pql.semantics.Planner
import com.processm.processminterpreter.domain.pql.semantics.RawClassifierProbe
import com.processm.processminterpreter.domain.pql.semantics.ResolutionContext
import com.processm.processminterpreter.domain.pql.semantics.Resolver
import com.processm.processminterpreter.domain.pql.semantics.Validator
import com.processm.processminterpreter.domain.pql.syntax.RawQuery
import org.springframework.stereotype.Component

/**
 * Application-level orchestration of the PQL compilation pipeline.
 *
 * Parser and log metadata lookup are ports. Resolver, validator, and planner
 * are pure domain phases. Keeping this flow in one component prevents execute,
 * validate, export, and verification paths from drifting into subtly different
 * compiler semantics.
 */
@Component
class PqlCompiler(
    private val parser: PqlParser,
    private val logs: LogRepository,
    private val dataStores: DataStoreRepository,
    private val resolver: Resolver = Resolver(AttributeResolver()),
    private val validator: Validator = Validator(),
    private val planner: Planner = Planner(),
) {
    fun compile(
        query: String,
        logId: String?,
        dataStoreId: String? = null,
        defaultLimits: HierarchicalLimits = HierarchicalLimits(),
    ): LogicalPlan {
        val raw = parser.parse(query)
        return compile(
            raw = raw,
            logId = logId,
            dataStoreId = dataStoreId,
            defaultLimits = defaultLimits,
        )
    }

    fun prepareForExecution(
        query: String,
        logId: String?,
        dataStoreId: String? = null,
        defaultLimits: HierarchicalLimits = HierarchicalLimits(),
    ): PreparedPqlQuery {
        val raw = parser.parse(query)
        val usesClassifier = raw is RawQuery.Select && RawClassifierProbe.containsClassifier(raw)
        val scopedLogIds = findExplicitlyScopedLogIds(logId, dataStoreId)
        val singleDataStoreLogId = scopedLogIds
            ?.singleOrNull()
            ?.takeIf { logId == null && dataStoreId != null }
        val effectiveLogId = logId ?: singleDataStoreLogId
        val effectiveDataStoreId = dataStoreId.takeIf { singleDataStoreLogId == null }
        val scopedLogs = if (usesClassifier) scopedLogIds?.mapNotNull(logs::findById) else emptyList()

        if (
            raw is RawQuery.Select &&
            effectiveLogId == null &&
            usesClassifier
        ) {
            val classifierScopedLogs = scopedLogs ?: findScopedLogs(logId, dataStoreId)
            if (classifierScopedLogs.size <= 1) {
                return PreparedPqlQuery.Single(
                    compile(
                        raw = raw,
                        logId = effectiveLogId,
                        dataStoreId = effectiveDataStoreId,
                        defaultLimits = defaultLimits,
                        scopedLogs = classifierScopedLogs,
                    ),
                )
            }
            return PreparedPqlQuery.PerLogSelect(
                raw = raw,
                candidateLogs = buildCandidateLogPlan(raw, effectiveDataStoreId),
                outerLogLimit = raw.limit.log,
                outerLogOffset = raw.offset.log,
                defaultLimits = defaultLimits,
            )
        }

        return PreparedPqlQuery.Single(
            compile(
                raw = raw,
                logId = effectiveLogId,
                dataStoreId = effectiveDataStoreId,
                defaultLimits = defaultLimits,
                scopedLogs = scopedLogs,
            ),
        )
    }

    fun compilePerLog(
        prepared: PreparedPqlQuery.PerLogSelect,
        logIds: List<String>,
    ): List<LogicalPlan.Select> =
        logIds.map { logId ->
            val log = logs.findById(logId)
            val plan = compile(
                raw = prepared.raw,
                logId = logId,
                dataStoreId = null,
                defaultLimits = prepared.defaultLimits,
                scopedLogs = listOfNotNull(log),
            )
            require(plan is LogicalPlan.Select) {
                "Classifier fan-out can only compile SELECT plans"
            }
            plan.withoutLogWindow()
        }

    private fun compile(
        raw: RawQuery,
        logId: String?,
        dataStoreId: String?,
        defaultLimits: HierarchicalLimits,
        scopedLogs: List<Log>? = null,
    ): LogicalPlan {
        val context = buildContext(logId, dataStoreId, scopedLogs)
        val resolved = resolver.resolve(raw, context)
        val validated = validator.validate(resolved)
        return planner.plan(
            validated = validated,
            logId = logId,
            dataStoreId = dataStoreId,
            defaultLimits = defaultLimits,
        )
    }

    private fun buildContext(
        logId: String?,
        dataStoreId: String?,
        scopedLogs: List<Log>? = null,
    ): ResolutionContext {
        val classifierCatalog = buildClassifierCatalog(scopedLogs ?: findScopedLogs(logId, dataStoreId))
        return ResolutionContext(
            logId = logId,
            classifiers = classifierCatalog.unambiguous,
            ambiguousClassifierNames = classifierCatalog.ambiguousNames,
        )
    }

    private fun buildClassifierCatalog(scopedLogs: List<Log>): ClassifierCatalog {
        val classifiers = mutableListOf<Classifier>()
        val ambiguousNames = mutableSetOf<String>()
        scopedLogs
            .flatMap { it.classifiers }
            .groupBy { it.name }
            .forEach { (name, definitions) ->
                val distinctKeys = definitions.map { it.keys }.distinct()
                if (distinctKeys.size == 1) {
                    classifiers += Classifier(name, distinctKeys.single())
                } else {
                    ambiguousNames += name
                }
            }
        return ClassifierCatalog(
            unambiguous = classifiers,
            ambiguousNames = ambiguousNames,
        )
    }

    private fun findScopedLogs(
        logId: String?,
        dataStoreId: String?,
    ): List<Log> =
        when {
            logId != null -> listOfNotNull(logs.findById(logId))
            dataStoreId != null -> dataStores.findLogSummaries(dataStoreId)
                .mapNotNull { summary -> logs.findById(summary.logId) }
            else -> logs.findAll()
        }

    private fun findExplicitlyScopedLogIds(
        logId: String?,
        dataStoreId: String?,
    ): List<String>? =
        when {
            logId != null -> listOf(logId)
            dataStoreId != null -> dataStores.findLogSummaries(dataStoreId).map { it.logId }
            else -> null
        }

    private fun buildCandidateLogPlan(
        raw: RawQuery.Select,
        dataStoreId: String?,
    ): CandidateLogPlan {
        val candidateRaw = RawQuery.Select(
            from = raw.from,
            columns = emptyList(),
            implicitAll = false,
            where = raw.where,
            orderBy = raw.orderBy.filterNot { RawClassifierProbe.containsClassifier(it.expression) },
            location = raw.location,
        )
        val candidatePlan = compile(
            raw = candidateRaw,
            logId = null,
            dataStoreId = dataStoreId,
            defaultLimits = HierarchicalLimits(),
            scopedLogs = emptyList(),
        )
        require(candidatePlan is LogicalPlan.Select) {
            "Candidate-log probing can only be built from SELECT queries"
        }
        return CandidateLogPlan(
            source = candidatePlan.source,
            filter = candidatePlan.filter,
            orderBy = candidatePlan.orderBy,
            location = candidatePlan.location,
        )
    }

    private fun LogicalPlan.Select.withoutLogWindow(): LogicalPlan.Select =
        copy(
            limits = HierarchicalLimits(
                log = null,
                trace = limits.trace,
                event = limits.event,
            ),
            offsets = HierarchicalOffsets(
                log = null,
                trace = offsets.trace,
                event = offsets.event,
            ),
        )

    private data class ClassifierCatalog(
        val unambiguous: List<Classifier>,
        val ambiguousNames: Set<String>,
    )
}

sealed interface PreparedPqlQuery {
    data class Single(val plan: LogicalPlan) : PreparedPqlQuery

    data class PerLogSelect(
        val raw: RawQuery.Select,
        val candidateLogs: CandidateLogPlan,
        val outerLogLimit: Long?,
        val outerLogOffset: Long?,
        val defaultLimits: HierarchicalLimits,
    ) : PreparedPqlQuery
}
