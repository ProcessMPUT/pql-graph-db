package com.processm.processminterpreter.infrastructure.persistence.neo4j.repository

import com.processm.processminterpreter.application.ports.DataStoreLogSummary
import com.processm.processminterpreter.domain.datastore.DataStore
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@Component
class Neo4jDataStoreReadCache {
    private val dataStores = ExpiringCache<String, DataStore>()
    private val logSummaries = ExpiringCache<String, List<DataStoreLogSummary>>()

    fun getDataStore(id: String): DataStore? = dataStores[id]

    fun putDataStore(dataStore: DataStore) {
        dataStores[dataStore.id] = dataStore
    }

    fun getLogSummaries(dataStoreId: String): List<DataStoreLogSummary>? = logSummaries[dataStoreId]

    fun putLogSummaries(dataStoreId: String, summaries: List<DataStoreLogSummary>) {
        logSummaries[dataStoreId] = summaries
    }

    fun invalidateDataStore(dataStoreId: String) {
        dataStores.remove(dataStoreId)
        logSummaries.remove(dataStoreId)
    }

    fun invalidateLogSummaries(dataStoreId: String) {
        logSummaries.remove(dataStoreId)
    }

    fun invalidateLog(logId: String) {
        logSummaries.removeIf { summaries -> summaries.any { it.logId == logId } }
    }

    private class ExpiringCache<K, V> {
        private val values = ConcurrentHashMap<K, CacheEntry<V>>()

        operator fun get(key: K): V? {
            val entry = values[key] ?: return null
            if (System.nanoTime() - entry.createdAtNanos > TTL_NANOS) {
                values.remove(key, entry)
                return null
            }
            return entry.value
        }

        operator fun set(key: K, value: V) {
            values[key] = CacheEntry(value, System.nanoTime())
        }

        fun remove(key: K) {
            values.remove(key)
        }

        fun removeIf(predicate: (V) -> Boolean) {
            values.entries.removeIf { predicate(it.value.value) }
        }
    }

    private data class CacheEntry<V>(
        val value: V,
        val createdAtNanos: Long,
    )

    private companion object {
        val TTL_NANOS: Long = 1.seconds.inWholeNanoseconds
    }
}
