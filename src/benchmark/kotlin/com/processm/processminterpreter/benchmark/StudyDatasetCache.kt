package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Reuses immutable XES bytes; independent preparation concerns the databases and processes, not XML generation. */
object StudyDatasetCache {
    private val mapper = jacksonObjectMapper()

    fun prepare(spec: BenchmarkDatasetSpec, cache: Path, output: Path, sourceKey: String, includePlainXes: Boolean = false): PreparedDataset {
        val directory = cache.resolve(spec.name)
        val metadata = directory.resolve("dataset.json")
        Files.createDirectories(directory)
        val data = if (Files.exists(metadata)) {
            val node = mapper.readTree(metadata.toFile())
            require(node["sourceKey"].asText() == sourceKey) { "Input cache belongs to a different study/code version" }
            require(node["definition"] == mapper.valueToTree(spec)) { "Input cache has a different dataset definition" }
            if (includePlainXes) {
                val plain = directory.resolve("${spec.name}.xes")
                require(Files.isRegularFile(plain) && node["plainFileSha256"]?.asText() == sha256(plain)) {
                    "Missing or changed plain XES required by the storage measurement: ${spec.name}"
                }
            }
            mapper.treeToValue(node["dataset"], PreparedDataset::class.java).also {
                require(it.name == spec.name && it.series == spec.series && sha256(it.file) == it.fileSha256) {
                    "Cached input bytes or metadata changed: ${spec.name}"
                }
            }
        } else {
            val prepared = XesDatasetGenerator().prepare(spec, directory)
            val stored = directory.resolve("${spec.name}.xes.gz")
            if (stored.toAbsolutePath() != prepared.file.toAbsolutePath()) Files.copy(prepared.file, stored)
            prepared.copy(file = stored).also { value ->
                val temporary = directory.resolve("dataset.json.tmp")
                val plain = directory.resolve("${spec.name}.xes")
                Files.writeString(temporary, mapper.writeValueAsString(mapOf("sourceKey" to sourceKey,
                    "definition" to spec, "dataset" to value,
                    "plainFileSha256" to plain.takeIf(Files::isRegularFile)?.let(::sha256))))
                Files.move(temporary, metadata)
            }
        }
        Files.createDirectories(output)
        val files = listOf(data.file) + if (includePlainXes) listOf(directory.resolve("${spec.name}.xes")) else emptyList()
        require(files.all(Files::isRegularFile)) { "Missing input artifact: ${spec.name}" }
        for (file in files) {
            val target = output.resolve(file.fileName)
            // Same-volume hard links save space. Cross-filesystem and unsupported filesystems use byte copies.
            try { Files.createLink(target, file) }
            catch (_: UnsupportedOperationException) { Files.copy(file, target) }
            catch (_: java.io.IOException) { Files.copy(file, target) }
        }
        return data.copy(file = output.resolve(data.file.fileName))
    }

    private fun sha256(path: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                hash.update(buffer, 0, read)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
