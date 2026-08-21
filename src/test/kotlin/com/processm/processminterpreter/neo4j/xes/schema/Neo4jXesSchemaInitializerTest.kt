package com.processm.processminterpreter.neo4j.xes.schema

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.exceptions.Neo4jException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.neo4j.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jXesSchemaInitializerTest {
    companion object {
        @Container
        val neo4jContainer =
            Neo4jContainer("neo4j:2026.07.1-community-ubi10")
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
    fun `creates unique constraints for technical identifiers`() {
        val initializer = Neo4jXesSchemaInitializer(driver)

        initializer.ensureIndexes()
        initializer.ensureIndexes()

        driver.session().use { session ->
            val constraints = session.run(
                """
                SHOW CONSTRAINTS
                YIELD name, type, labelsOrTypes, properties
                WHERE name IN [
                    'log_logId_unique',
                    'importing_logId_unique',
                    'trace_traceId_unique',
                    'event_eventId_unique',
                    'datastore_dataStoreId_unique'
                ]
                RETURN name, type, labelsOrTypes, properties
                """.trimIndent(),
            ).list { record ->
                record["name"].asString() to ConstraintSpec(
                    // Neo4j renamed the reported type from UNIQUENESS to
                    // NODE_PROPERTY_UNIQUENESS; normalize so the assertion holds on both.
                    type = record["type"].asString().removePrefix("NODE_PROPERTY_"),
                    label = record["labelsOrTypes"].asList { it.asString() }.single(),
                    property = record["properties"].asList { it.asString() }.single(),
                )
            }.toMap()

            assertEquals(
                mapOf(
                    "log_logId_unique" to ConstraintSpec("UNIQUENESS", "Log", "logId"),
                    "importing_logId_unique" to ConstraintSpec("UNIQUENESS", "ImportingLog", "logId"),
                    "trace_traceId_unique" to ConstraintSpec("UNIQUENESS", "Trace", "traceId"),
                    "event_eventId_unique" to ConstraintSpec("UNIQUENESS", "Event", "eventId"),
                    "datastore_dataStoreId_unique" to ConstraintSpec("UNIQUENESS", "DataStore", "dataStoreId"),
                ),
                constraints,
            )

            val traceWindowIndex = session.run(
                """
                SHOW INDEXES
                YIELD name, type, labelsOrTypes, properties
                WHERE name = 'trace_parent_import_order'
                RETURN type, labelsOrTypes, properties
                """.trimIndent(),
            ).single()
            assertEquals("RANGE", traceWindowIndex["type"].asString())
            assertEquals(listOf("Trace"), traceWindowIndex["labelsOrTypes"].asList { it.asString() })
            assertEquals(listOf("parentLogId", "importOrder"), traceWindowIndex["properties"].asList { it.asString() })

            val eventWindowIndex = session.run(
                """
                SHOW INDEXES
                YIELD name, type, labelsOrTypes, properties
                WHERE name = 'event_parent_import_order'
                RETURN type, labelsOrTypes, properties
                """.trimIndent(),
            ).single()
            assertEquals("RANGE", eventWindowIndex["type"].asString())
            assertEquals(listOf("Event"), eventWindowIndex["labelsOrTypes"].asList { it.asString() })
            assertEquals(listOf("parentTraceId", "importOrder"), eventWindowIndex["properties"].asList { it.asString() })
        }
    }

    @Test
    fun `unique constraints reject duplicate technical identifiers`() {
        Neo4jXesSchemaInitializer(driver).ensureIndexes()

        driver.session().use { session ->
            val error = assertFailsWith<Neo4jException> {
                session.executeWrite { tx ->
                    tx.run(
                        """
                        CREATE (:Log {logId: 'same-log'})
                        CREATE (:Log {logId: 'same-log'})
                        """.trimIndent(),
                    ).consume()
                }
            }

            assertEquals("Neo.ClientError.Schema.ConstraintValidationFailed", error.code())
        }
    }

    @Test
    fun `schema initialization fails when a required uniqueness constraint cannot be created`() {
        Neo4jXesSchemaInitializer(driver).ensureIndexes()
        driver.session().use { session ->
            session.run("DROP CONSTRAINT importing_logId_unique IF EXISTS").consume()
            session.run(
                "CREATE (:ImportingLog {logId: 'duplicate'}), (:ImportingLog {logId: 'duplicate'})",
            ).consume()
        }

        try {
            assertFailsWith<Neo4jException> {
                Neo4jXesSchemaInitializer(driver).ensureIndexes()
            }
        } finally {
            driver.session().use { session ->
                session.run("MATCH (log:ImportingLog) DETACH DELETE log").consume()
            }
            Neo4jXesSchemaInitializer(driver).ensureIndexes()
        }
    }

    private data class ConstraintSpec(
        val type: String,
        val label: String,
        val property: String,
    )
}
