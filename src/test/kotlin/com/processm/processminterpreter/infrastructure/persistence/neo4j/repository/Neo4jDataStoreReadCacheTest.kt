package com.processm.processminterpreter.infrastructure.persistence.neo4j.repository

import com.processm.processminterpreter.application.ports.DataStoreLogSummary
import com.processm.processminterpreter.domain.datastore.DataStore
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Neo4jDataStoreReadCacheTest {
    private val cache = Neo4jDataStoreReadCache()

    @Test
    fun `invalidating a log removes only summaries that contain it`() {
        val affected = listOf(summary("log-1"))
        val unaffected = listOf(summary("log-2"))
        cache.putLogSummaries("store-1", affected)
        cache.putLogSummaries("store-2", unaffected)

        cache.invalidateLog("log-1")

        assertNull(cache.getLogSummaries("store-1"))
        assertEquals(unaffected, cache.getLogSummaries("store-2"))
    }

    @Test
    fun `invalidating a datastore removes its metadata and summaries`() {
        val dataStore =
            DataStore(
                id = "store-1",
                name = "Store",
                createdAt = LocalDateTime.MIN,
                updatedAt = LocalDateTime.MIN,
            )
        cache.putDataStore(dataStore)
        cache.putLogSummaries(dataStore.id, listOf(summary("log-1")))

        cache.invalidateDataStore(dataStore.id)

        assertNull(cache.getDataStore(dataStore.id))
        assertNull(cache.getLogSummaries(dataStore.id))
    }

    private fun summary(logId: String) =
        DataStoreLogSummary(
            logId = logId,
            name = logId,
            createdAt = LocalDateTime.MIN,
            updatedAt = LocalDateTime.MIN,
        )
}
