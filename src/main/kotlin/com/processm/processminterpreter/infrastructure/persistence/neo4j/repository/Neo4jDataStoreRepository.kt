package com.processm.processminterpreter.infrastructure.persistence.neo4j.repository

import com.processm.processminterpreter.domain.datastore.DataStore
import com.processm.processminterpreter.application.ports.DataStoreNotFoundException
import com.processm.processminterpreter.application.ports.DataStoreLogSummary
import com.processm.processminterpreter.application.ports.DataStoreRepository
import org.neo4j.driver.Driver
import org.neo4j.driver.types.Node
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class Neo4jDataStoreRepository(private val driver: Driver) : DataStoreRepository {

    override fun save(dataStore: DataStore): DataStore {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    MERGE (ds:DataStore {dataStoreId: ${'$'}dataStoreId})
                    ON CREATE SET ds.createdAt = ${'$'}createdAt
                    SET ds.name = ${'$'}name,
                        ds.updatedAt = ${'$'}updatedAt
                    """.trimIndent(),
                    mapOf(
                        "dataStoreId" to dataStore.id,
                        "name" to dataStore.name,
                        "createdAt" to dataStore.createdAt,
                        "updatedAt" to dataStore.updatedAt,
                    ),
                ).consume()
            }
        }
        return dataStore
    }

    override fun findById(id: String): DataStore? =
        driver.session().use { session ->
            session.executeRead { tx ->
                val result = tx.run(
                    "MATCH (ds:DataStore {dataStoreId: \$dataStoreId}) RETURN ds",
                    mapOf("dataStoreId" to id),
                )
                if (result.hasNext()) toDomain(result.single()["ds"].asNode()) else null
            }
        }

    override fun findAll(): List<DataStore> =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx.run("MATCH (ds:DataStore) RETURN ds ORDER BY ds.createdAt DESC")
                    .list { toDomain(it["ds"].asNode()) }
            }
        }

    override fun findLogSummaries(dataStoreId: String): List<DataStoreLogSummary> =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx.run(
                    """
                    MATCH (:DataStore {dataStoreId: ${'$'}dataStoreId})-[:CONTAINS_LOG]->(log:Log)
                    RETURN log.logId AS logId,
                           coalesce(log.name, log.logId) AS name,
                           log.createdAt AS createdAt,
                           log.updatedAt AS updatedAt
                    ORDER BY log.createdAt DESC
                    """.trimIndent(),
                    mapOf("dataStoreId" to dataStoreId),
                ).list { record ->
                    DataStoreLogSummary(
                        logId = record["logId"].asString(),
                        name = record["name"].asString(),
                        createdAt = record["createdAt"].takeUnless { it.isNull }?.asLocalDateTime(),
                        updatedAt = record["updatedAt"].takeUnless { it.isNull }?.asLocalDateTime(),
                    )
                }
            }
        }

    override fun update(dataStore: DataStore): DataStore {
        if (!exists(dataStore.id)) throw DataStoreNotFoundException(dataStore.id)
        return save(dataStore.copy(updatedAt = LocalDateTime.now()))
    }

    override fun deleteWithLogs(id: String): Boolean {
        if (!exists(id)) return false
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    MATCH (ds:DataStore {dataStoreId: ${'$'}dataStoreId})
                    OPTIONAL MATCH (ds)-[:CONTAINS_LOG]->(log:Log)
                    OPTIONAL MATCH (log)-[:CONTAINS]->(trace:Trace)
                    OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)
                    DETACH DELETE event, trace, log, ds
                    """.trimIndent(),
                    mapOf("dataStoreId" to id),
                ).consume()
            }
        }
        return true
    }

    override fun exists(id: String): Boolean =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx.run(
                    "MATCH (ds:DataStore {dataStoreId: \$dataStoreId}) RETURN count(ds) > 0 AS present",
                    mapOf("dataStoreId" to id),
                ).single()["present"].asBoolean()
            }
        }

    override fun attachLog(dataStoreId: String, logId: String) {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    MATCH (ds:DataStore {dataStoreId: ${'$'}dataStoreId})
                    MATCH (log:Log {logId: ${'$'}logId})
                    MERGE (ds)-[:CONTAINS_LOG]->(log)
                    """.trimIndent(),
                    mapOf("dataStoreId" to dataStoreId, "logId" to logId),
                ).consume()
            }
        }
    }

    private fun toDomain(node: Node): DataStore {
        val createdAt = node["createdAt"].asLocalDateTime()
        return DataStore(
            id = node["dataStoreId"].asString(),
            name = node["name"].asString(),
            createdAt = createdAt,
            updatedAt = if (node.containsKey("updatedAt")) node["updatedAt"].asLocalDateTime() else createdAt,
        )
    }
}
