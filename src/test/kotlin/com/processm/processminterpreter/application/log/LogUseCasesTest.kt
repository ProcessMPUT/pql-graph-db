package com.processm.processminterpreter.application.log

import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.log.Log
import com.processm.processminterpreter.application.ports.LogRepository
import com.processm.processminterpreter.application.ports.LogStatistics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class LogUseCasesTest {

    /** In-memory repo that keeps state in a mutable map — enough to exercise the CRUD shapes. */
    private class InMemoryLogRepository : LogRepository {
        val store = mutableMapOf<String, Log>()
        val stats = mutableMapOf<String, LogStatistics>()
        override fun save(log: Log): Log {
            store[log.id] = log
            return log
        }
        override fun findById(id: String) = store[id]
        override fun findAll() = store.values.toList()
        override fun search(namePart: String) = store.values.filter { it.name.contains(namePart) }
        override fun findByAttribute(key: String, value: Any) =
            store.values.filter { it.customAttributes[key] == value }
        override fun findCreatedAfter(date: LocalDateTime) =
            store.values.filter { it.createdAt.isAfter(date) }
        override fun findCreatedBetween(start: LocalDateTime, end: LocalDateTime) =
            store.values.filter { it.createdAt in start..end }
        override fun getStatistics(id: String) = stats[id]
        override fun getStatisticsAll(): List<Pair<Log, LogStatistics>> =
            store.values.mapNotNull { log -> stats[log.id]?.let { log to it } }
        override fun getClassifiers(id: String): Map<String, List<String>> =
            store[id]?.classifiers?.associate { it.name to it.keys }.orEmpty()
        override fun update(log: Log): Log {
            store[log.id] = log
            return log
        }
        override fun delete(id: String) = store.remove(id) != null
        override fun deleteWithData(id: String) = store.remove(id) != null
        override fun exists(id: String) = store.containsKey(id)
    }

    // ----- Create -----

    @Test
    fun `create generates a UUID when no id is supplied`() {
        val repo = InMemoryLogRepository()
        val useCase = CreateLogUseCase(repo)

        val log = useCase.create(CreateLogRequest(name = "hospital"))

        assertTrue(log.id.isNotBlank())
        assertEquals("hospital", log.name)
        assertTrue(repo.store.containsKey(log.id))
    }

    @Test
    fun `create respects an explicit id`() {
        val repo = InMemoryLogRepository()
        val useCase = CreateLogUseCase(repo)

        val log = useCase.create(CreateLogRequest(name = "n", id = "log-xyz"))

        assertEquals("log-xyz", log.id)
    }

    @Test
    fun `create rejects duplicate ids`() {
        val repo = InMemoryLogRepository()
        val useCase = CreateLogUseCase(repo)
        useCase.create(CreateLogRequest(name = "a", id = "dup"))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            useCase.create(CreateLogRequest(name = "b", id = "dup"))
        }
        assertTrue(ex.message!!.contains("already exists"))
    }

    @Test
    fun `create rejects a blank name`() {
        val repo = InMemoryLogRepository()
        val useCase = CreateLogUseCase(repo)

        assertThrows(IllegalArgumentException::class.java) {
            useCase.create(CreateLogRequest(name = "  "))
        }
    }

    // ----- Get -----

    @Test
    fun `get throws LogNotFoundException when missing`() {
        val useCase = GetLogUseCase(InMemoryLogRepository())

        assertThrows(LogNotFoundException::class.java) { useCase.get("nope") }
    }

    @Test
    fun `find returns null when missing`() {
        val useCase = GetLogUseCase(InMemoryLogRepository())

        assertNull(useCase.find("nope"))
    }

    @Test
    fun `statistics returns whatever the repository has for the id`() {
        val repo = InMemoryLogRepository()
        repo.save(sampleLog("log-1"))
        repo.stats["log-1"] = LogStatistics(traceCount = 10, eventCount = 200)
        val useCase = GetLogUseCase(repo)

        val stats = useCase.statistics("log-1")!!

        assertEquals(10L, stats.traceCount)
        assertEquals(200L, stats.eventCount)
    }

    // ----- List -----

    @Test
    fun `list returns every log`() {
        val repo = InMemoryLogRepository()
        repo.save(sampleLog("a"))
        repo.save(sampleLog("b"))
        val useCase = ListLogsUseCase(repo)

        val out = useCase.list().map { it.id }.toSet()
        assertEquals(setOf("a", "b"), out)
    }

    @Test
    fun `listWithStatistics pairs each log with its stats when available`() {
        val repo = InMemoryLogRepository()
        repo.save(sampleLog("a"))
        repo.stats["a"] = LogStatistics(traceCount = 1, eventCount = 2)
        val useCase = ListLogsUseCase(repo)

        val pairs = useCase.listWithStatistics()
        assertEquals(1, pairs.size)
        assertEquals("a", pairs[0].first.id)
        assertEquals(1L, pairs[0].second.traceCount)
    }

    // ----- Update -----

    @Test
    fun `update leaves unspecified fields unchanged`() {
        val repo = InMemoryLogRepository()
        val original = sampleLog("log-1", name = "orig", classifiers = listOf(Classifier("C", listOf("k"))))
        repo.save(original)
        val useCase = UpdateLogUseCase(repo)

        val updated = useCase.update(UpdateLogRequest(id = "log-1", name = "renamed"))

        assertEquals("renamed", updated.name)
        assertEquals(original.classifiers, updated.classifiers)
        assertTrue(updated.updatedAt.isAfter(original.updatedAt) ||
            updated.updatedAt == original.updatedAt)
    }

    @Test
    fun `update fails cleanly when the log is missing`() {
        val useCase = UpdateLogUseCase(InMemoryLogRepository())
        assertThrows(LogNotFoundException::class.java) {
            useCase.update(UpdateLogRequest(id = "nope", name = "x"))
        }
    }

    // ----- Delete -----

    @Test
    fun `delete removes only the metadata`() {
        val repo = InMemoryLogRepository()
        repo.save(sampleLog("a"))
        val useCase = DeleteLogUseCase(repo)

        assertTrue(useCase.delete("a"))
        assertNull(repo.store["a"])
    }

    @Test
    fun `delete returns false when the log is missing`() {
        assertFalse(DeleteLogUseCase(InMemoryLogRepository()).delete("nope"))
    }

    @Test
    fun `deleteWithData removes the log as well`() {
        val repo = InMemoryLogRepository()
        repo.save(sampleLog("a"))
        assertTrue(DeleteLogUseCase(repo).deleteWithData("a"))
        assertFalse(repo.exists("a"))
    }

    // ----- Search -----

    @Test
    fun `search by name part filters across all logs`() {
        val repo = InMemoryLogRepository()
        repo.save(sampleLog("a", name = "hospital-log"))
        repo.save(sampleLog("b", name = "bpi-log"))
        val useCase = SearchLogsUseCase(repo)

        assertEquals(setOf("a"), useCase.search(SearchLogsRequest(namePart = "hospital")).map { it.id }.toSet())
    }

    @Test
    fun `search without any criteria returns all logs`() {
        val repo = InMemoryLogRepository()
        repo.save(sampleLog("a"))
        repo.save(sampleLog("b"))
        val useCase = SearchLogsUseCase(repo)

        assertEquals(2, useCase.search(SearchLogsRequest()).size)
    }

    @Test
    fun `search by attribute delegates to the repository's attribute index`() {
        val repo = InMemoryLogRepository()
        repo.save(sampleLog("a", attrs = mapOf("owner" to "alice")))
        repo.save(sampleLog("b", attrs = mapOf("owner" to "bob")))
        val useCase = SearchLogsUseCase(repo)

        val hits = useCase.search(SearchLogsRequest(attribute = AttributeFilter("owner", "alice")))
        assertEquals(setOf("a"), hits.map { it.id }.toSet())
    }

    @Test
    fun `search by createdAfter + createdBefore uses the range variant`() {
        val repo = InMemoryLogRepository()
        val t0 = LocalDateTime.of(2020, 1, 1, 0, 0)
        repo.save(sampleLog("old", createdAt = t0))
        repo.save(sampleLog("new", createdAt = t0.plusYears(5)))
        val useCase = SearchLogsUseCase(repo)

        val hits = useCase.search(
            SearchLogsRequest(
                createdAfter = t0.plusYears(1),
                createdBefore = t0.plusYears(10),
            ),
        )
        assertNotNull(hits.find { it.id == "new" })
        assertNull(hits.find { it.id == "old" })
    }

    // ----- helpers -----

    private fun sampleLog(
        id: String,
        name: String = "log-$id",
        classifiers: List<Classifier> = emptyList(),
        attrs: Map<String, Any?> = emptyMap(),
        createdAt: LocalDateTime = LocalDateTime.now(),
    ) = Log(
        id = id,
        name = name,
        createdAt = createdAt,
        updatedAt = createdAt,
        classifiers = classifiers,
        customAttributes = attrs,
    )
}
