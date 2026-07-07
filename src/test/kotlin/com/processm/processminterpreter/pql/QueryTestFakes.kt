package com.processm.processminterpreter.pql

import com.processm.processminterpreter.xes.DataStoreLogSummary
import com.processm.processminterpreter.xes.DataStoreRepository
import com.processm.processminterpreter.xes.LogRepository
import com.processm.processminterpreter.xes.LogStatistics
import com.processm.processminterpreter.xes.datastore.DataStore
import com.processm.processminterpreter.xes.model.Log
import com.processm.processminterpreter.neo4j.query.CypherTypeMapper
import com.processm.processminterpreter.neo4j.query.Neo4jQueryPlanExecutor
import com.processm.processminterpreter.pql.cypher.CypherCodegen
import com.processm.processminterpreter.pql.cypher.PhysicalAttributeMapper
import com.processm.processminterpreter.neo4j.query.result.HierarchyReconstructor
import org.mockito.Mockito
import org.neo4j.driver.Driver
import java.time.LocalDateTime

/**
 * Shared hand-rolled fakes for the [PqlQueryService] test suite
 * ([PqlQueryServiceExecuteTest], [PqlQueryServiceValidateTest],
 * [PqlQueryServiceExportTest]) — Kotlin non-null parameter types plus
 * Mockito's `any(Class)` matcher fight each other ugly, so the repositories
 * are plain classes returning canned data and recording lookups.
 */
/** Minimal [Log] for seeding [FakeLogRepository.byId] when only the id matters. */
fun logStub(id: String, name: String = id): Log {
    val now = LocalDateTime.now()
    return Log(id = id, name = name, createdAt = now, updatedAt = now)
}

open class FakeLogRepository(
    private val byId: Map<String, Log> = emptyMap(),
) : LogRepository {
    val findByIdCalls = mutableListOf<String>()

    override fun save(log: Log): Log = log

    override fun findById(id: String): Log? {
        findByIdCalls += id
        return byId[id]
    }

    override fun findAll(): List<Log> = byId.values.toList()

    override fun search(namePart: String): List<Log> = emptyList()

    override fun findByAttribute(key: String, value: Any): List<Log> = emptyList()

    override fun findCreatedAfter(date: LocalDateTime): List<Log> = emptyList()

    override fun findCreatedBetween(start: LocalDateTime, end: LocalDateTime): List<Log> = emptyList()

    override fun getStatistics(id: String): LogStatistics? = null

    override fun getStatisticsAll(): List<Pair<Log, LogStatistics>> = emptyList()

    override fun update(log: Log): Log = log

    override fun delete(id: String): Boolean = false

    override fun deleteWithData(id: String): Boolean = false

    override fun exists(id: String): Boolean = byId.containsKey(id)
}

open class FakeDataStoreRepository(
    private val logsByDataStoreId: Map<String, List<DataStoreLogSummary>> = emptyMap(),
) : DataStoreRepository {
    val attached = mutableListOf<Pair<String, String>>()

    override fun save(dataStore: DataStore): DataStore = dataStore

    override fun findById(id: String): DataStore? = null

    override fun findAll(): List<DataStore> = emptyList()

    override fun findLogSummaries(dataStoreId: String): List<DataStoreLogSummary> =
        logsByDataStoreId[dataStoreId].orEmpty()

    override fun update(dataStore: DataStore): DataStore = dataStore

    override fun deleteWithLogs(id: String): Boolean = false

    override fun exists(id: String): Boolean = logsByDataStoreId.containsKey(id)

    override fun attachLog(dataStoreId: String, logId: String) {
        attached += dataStoreId to logId
    }
}

/**
 * Base for executor test doubles: a real [Neo4jQueryPlanExecutor] (open via the
 * kotlin-spring plugin) wrapping a never-touched mocked driver. Subclass it to
 * record invocations or return canned results; instantiate it directly (via
 * [stubExecutor]) when the executor only has to satisfy a constructor.
 */
open class StubQueryPlanExecutor : Neo4jQueryPlanExecutor(
    driver = Mockito.mock(Driver::class.java),
    codegen = CypherCodegen(PhysicalAttributeMapper()),
    typeMapper = CypherTypeMapper(),
    reconstructor = HierarchyReconstructor(),
)

/** A plain (non-throwing) executor over a mocked driver, for constructor-only use. */
fun stubExecutor(): Neo4jQueryPlanExecutor = StubQueryPlanExecutor()
