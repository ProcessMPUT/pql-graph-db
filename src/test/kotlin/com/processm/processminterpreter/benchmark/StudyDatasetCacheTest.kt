package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudyDatasetCacheTest {
    @Test
    fun `new preparations reuse identical bytes and preserve synthetic metadata`(@TempDir directory: Path) {
        val spec = BenchmarkDatasetSpec(DatasetType.SYNTHETIC, "small", "size-scaling", traces = 2,
            eventsPerTrace = 10, attributesPerEvent = 5)
        val cache = directory.resolve("inputs")
        val first = StudyDatasetCache.prepare(spec, cache, directory.resolve("r1"), "version-a", includePlainXes = true)
        val second = StudyDatasetCache.prepare(spec, cache, directory.resolve("r2"), "version-a")
        assertEquals(first.copy(file = second.file), second)
        assertTrue(Files.readAllBytes(first.file).contentEquals(Files.readAllBytes(second.file)))
        assertTrue(Files.exists(directory.resolve("r1/small.xes")))
        assertTrue(Files.notExists(directory.resolve("r2/small.xes")))
        assertFailsWith<IllegalArgumentException> {
            StudyDatasetCache.prepare(spec, cache, directory.resolve("r3"), "version-b")
        }
    }

    @Test
    fun `corrupt cached bytes fail instead of silently creating a new input`(@TempDir directory: Path) {
        val spec = BenchmarkDatasetSpec(DatasetType.SYNTHETIC, "small", "size-scaling", traces = 1,
            eventsPerTrace = 2, attributesPerEvent = 1)
        val cache = directory.resolve("inputs")
        StudyDatasetCache.prepare(spec, cache, directory.resolve("r1"), "v")
        Files.writeString(cache.resolve("small/small.xes.gz"), "corruption")
        assertFailsWith<IllegalArgumentException> {
            StudyDatasetCache.prepare(spec, cache, directory.resolve("r2"), "v")
        }
    }

    @Test
    fun `missing or changed plain XES fails before a storage campaign can start`(@TempDir directory: Path) {
        val spec = BenchmarkDatasetSpec(DatasetType.SYNTHETIC, "small", "size-scaling", traces = 1,
            eventsPerTrace = 2, attributesPerEvent = 1)
        val cache = directory.resolve("inputs")
        StudyDatasetCache.prepare(spec, cache, directory.resolve("r1"), "v")
        StudyDatasetCache.prepare(spec, cache, directory.resolve("r2"), "v", includePlainXes = true)
        val plain = cache.resolve("small/small.xes")
        Files.writeString(plain, "corruption")
        assertFailsWith<IllegalArgumentException> {
            StudyDatasetCache.prepare(spec, cache, directory.resolve("r3"), "v", includePlainXes = true)
        }
        Files.delete(plain)
        assertFailsWith<IllegalArgumentException> {
            StudyDatasetCache.prepare(spec, cache, directory.resolve("r4"), "v", includePlainXes = true)
        }
    }
}
