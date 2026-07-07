package com.processm.processminterpreter.neo4j.xes.schema

import org.neo4j.driver.Driver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Owns Neo4j schema setup needed by the XES persistence adapter.
 */
@Component
class Neo4jXesSchemaInitializer(
    private val driver: Driver,
) {
    private val logger = LoggerFactory.getLogger(Neo4jXesSchemaInitializer::class.java)

    fun ensureIndexes() {
        logger.info("Ensuring Neo4j XES schema constraints are created...")
        try {
            driver.session().use { session ->
                session.executeWrite { tx ->
                    val legacyIndexes = tx.run("SHOW INDEXES YIELD name RETURN collect(name) AS names")
                        .single()["names"]
                        .asList { it.asString() }
                        .toSet()

                    Neo4jXesSchemaDefinitions.uniqueConstraints.forEach { constraint ->
                        if (constraint.legacyIndexName in legacyIndexes) {
                            val duplicateResult = tx.run(constraint.duplicateCheckCypher())
                            if (duplicateResult.hasNext()) {
                                val duplicate = duplicateResult.single()
                                error(
                                    "Cannot replace legacy index ${constraint.legacyIndexName} with unique constraint " +
                                        "${constraint.name}: duplicate ${constraint.label}.${constraint.property} value " +
                                        "'${duplicate["value"].asObject()}' appears ${duplicate["count"].asLong()} times",
                                )
                            }
                            tx.run("DROP INDEX ${constraint.legacyIndexName} IF EXISTS").consume()
                        }
                        tx.run(constraint.createCypher()).consume()
                    }
                }
            }
            logger.info("Neo4j XES schema constraints are in place.")
        } catch (e: Exception) {
            logger.error("Failed to create Neo4j XES schema constraints. Integrity and performance may be degraded.", e)
        }
    }
}
