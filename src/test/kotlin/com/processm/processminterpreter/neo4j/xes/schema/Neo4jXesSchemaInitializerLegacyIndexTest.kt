package com.processm.processminterpreter.neo4j.xes.schema

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.neo4j.Neo4jContainer
import kotlin.test.assertEquals

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jXesSchemaInitializerLegacyIndexTest {
    companion object {
        @Container
        val neo4jContainer =
            Neo4jContainer("neo4j:5.26.25-community-ubi10")
                .withAdminPassword("password")
    }

    private lateinit var driver: Driver

    @BeforeAll
    fun setUp() {
        driver = GraphDatabase.driver(
            neo4jContainer.boltUrl,
            AuthTokens.basic("neo4j", "password"),
        )
    }

    @AfterAll
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `replaces legacy technical indexes with unique constraints`() {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run("CREATE INDEX log_logId IF NOT EXISTS FOR (n:Log) ON (n.logId)").consume()
                tx.run("CREATE INDEX datastore_dataStoreId IF NOT EXISTS FOR (n:DataStore) ON (n.dataStoreId)").consume()
                tx.run("CREATE INDEX trace_traceId IF NOT EXISTS FOR (n:Trace) ON (n.traceId)").consume()
                tx.run("CREATE INDEX event_eventId IF NOT EXISTS FOR (n:Event) ON (n.eventId)").consume()
            }
        }

        Neo4jXesSchemaInitializer(driver).ensureIndexes()

        driver.session().use { session ->
            val legacyIndexes = session.run(
                """
                SHOW INDEXES
                YIELD name
                WHERE name IN ['log_logId', 'datastore_dataStoreId', 'trace_traceId', 'event_eventId']
                RETURN count(*) AS count
                """.trimIndent(),
            ).single()["count"].asLong()

            val uniqueConstraints = session.run(
                """
                SHOW CONSTRAINTS
                YIELD name
                WHERE name IN [
                    'log_logId_unique',
                    'trace_traceId_unique',
                    'event_eventId_unique',
                    'datastore_dataStoreId_unique'
                ]
                RETURN count(*) AS count
                """.trimIndent(),
            ).single()["count"].asLong()

            assertEquals(0, legacyIndexes)
            assertEquals(4, uniqueConstraints)
        }
    }
}
