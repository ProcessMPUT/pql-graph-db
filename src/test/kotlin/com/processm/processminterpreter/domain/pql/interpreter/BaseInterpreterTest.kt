package com.processm.processminterpreter.domain.pql.interpreter

import com.processm.processminterpreter.application.query.ExecutePqlQueryUseCase
import com.processm.processminterpreter.application.query.DataStorePqlQueryResult
import com.processm.processminterpreter.application.query.PQLQueryService
import com.processm.processminterpreter.application.query.PqlCompiler
import com.processm.processminterpreter.application.query.ValidatePqlQueryUseCase
import com.processm.processminterpreter.domain.datastore.DataStore
import com.processm.processminterpreter.infrastructure.parser.antlr.AntlrPqlParser
import com.processm.processminterpreter.infrastructure.parser.antlr.AstBuilder
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher.CypherCodegen
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.CypherTypeMapper
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.result.HierarchyReconstructor
import com.processm.processminterpreter.infrastructure.persistence.neo4j.repository.Neo4jDataStoreReadCache
import com.processm.processminterpreter.infrastructure.persistence.neo4j.repository.Neo4jDataStoreRepository
import com.processm.processminterpreter.infrastructure.persistence.neo4j.repository.Neo4jLogRepository
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.Neo4jQueryPlanExecutor
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher.PhysicalAttributeMapper
import com.processm.processminterpreter.infrastructure.xes.XESWriter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.neo4j.Neo4jContainer
import java.time.LocalDateTime

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseInterpreterTest {
    companion object {
        private const val NO_DEFAULT_LIMIT = -1

        @Container
        val neo4jContainer =
            Neo4jContainer("neo4j:5.26.25-community-ubi10")
                .withAdminPassword("password")
    }

    protected lateinit var driver: Driver
    protected lateinit var pqlQueryService: PQLQueryService
    protected lateinit var xesWriter: XESWriter
    protected val interpreterDataStoreId = "interpreter-test-store"

    private lateinit var dataStores: Neo4jDataStoreRepository

    @BeforeAll
    fun setup() {
        driver =
            GraphDatabase.driver(
                neo4jContainer.boltUrl,
                AuthTokens.basic("neo4j", "password"),
            )

        // Use real XESWriter since it has no external dependencies
        xesWriter = XESWriter()

        // Wire the hex pipeline by hand — mirrors HexPipelineConfig, minus Spring.
        // Keeping this explicit here (rather than spinning up a @SpringBootTest)
        // matches the rest of the integration tests and avoids the Spring cold-start
        // cost for every interpreter suite.
        val parser = AntlrPqlParser(AstBuilder())
        val executor = Neo4jQueryPlanExecutor(
            driver = driver,
            codegen = CypherCodegen(PhysicalAttributeMapper()),
            typeMapper = CypherTypeMapper(),
            reconstructor = HierarchyReconstructor(),
        )
        val dataStoreReadCache = Neo4jDataStoreReadCache()
        val logs = Neo4jLogRepository(driver, dataStoreReadCache)
        dataStores = Neo4jDataStoreRepository(driver, dataStoreReadCache)
        val compiler = PqlCompiler(parser, logs, dataStores)
        val executeUseCase = ExecutePqlQueryUseCase(compiler, executor)
        val validateUseCase = ValidatePqlQueryUseCase(compiler)

        pqlQueryService = PQLQueryService(executeUseCase, validateUseCase)
        ensureInterpreterDataStore()
    }

    @AfterAll
    fun tearDown() {
        driver.close()
    }

    protected fun executeCypher(query: String) {
        driver.session().use { session ->
            session.run(query)
        }
    }

    protected fun clearDatabase() {
        executeCypher("MATCH (n) DETACH DELETE n")
        ensureInterpreterDataStore()
    }

    protected fun attachLogToInterpreterDataStore(logId: String) {
        ensureInterpreterDataStore()
        dataStores.attachLog(interpreterDataStoreId, logId)
    }

    protected fun executeDataStoreQuery(
        query: String,
        logId: String? = null,
        defaultTraceLimit: Int? = NO_DEFAULT_LIMIT,
    ): DataStorePqlQueryResult =
        pqlQueryService.executeDataStoreQuery(
            pqlQuery = query,
            dataStoreId = interpreterDataStoreId,
            logId = logId,
            defaultTraceLimit = defaultTraceLimit,
        )

    private fun ensureInterpreterDataStore() {
        if (::dataStores.isInitialized && dataStores.exists(interpreterDataStoreId)) return
        val now = LocalDateTime.now()
        dataStores.save(
            DataStore(
                id = interpreterDataStoreId,
                name = "Interpreter integration tests",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

}
