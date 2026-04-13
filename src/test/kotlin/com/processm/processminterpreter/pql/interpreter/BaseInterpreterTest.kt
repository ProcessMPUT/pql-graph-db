package com.processm.processminterpreter.pql.interpreter

import com.processm.processminterpreter.pql.AntlrPQLTranslator
import com.processm.processminterpreter.service.PQLQueryService
import com.processm.processminterpreter.xes.XESWriter
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseInterpreterTest {
    companion object {
        @Container
        val neo4jContainer =
            Neo4jContainer("neo4j:5.15.0")
                .withAdminPassword("password")
    }

    protected lateinit var driver: Driver
    protected lateinit var pqlQueryService: PQLQueryService
    protected lateinit var xesWriter: XESWriter

    @BeforeAll
    fun setup() {
        driver =
            GraphDatabase.driver(
                neo4jContainer.boltUrl,
                AuthTokens.basic("neo4j", "password"),
            )

        // Use real XESWriter since it has no external dependencies
        xesWriter = XESWriter()

        pqlQueryService =
            PQLQueryService(
                AntlrPQLTranslator(),
                driver,
                xesWriter,
            )
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
    }
}
