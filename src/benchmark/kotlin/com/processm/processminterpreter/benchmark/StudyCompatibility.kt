package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path

internal fun collectStudyCompatibility(
    settings: BenchmarkSettings, clients: Map<BenchmarkSystem, BenchmarkHttpClient>, generator: XesDatasetGenerator,
    generated: Path, stores: MutableList<CreatedDataStoreHandle>, out: Path, journal: BenchmarkJournal,
) {
    val mapper = jacksonObjectMapper()
    val names = listOf("Hospital_log", "JournalReview", "Sepsis", "teleclaims")
    val files = names.associateWith { name ->
        journal.progress.operation("prepare-input", dataset = name) {
            generator.prepare(BenchmarkDatasetSpec(DatasetType.FIXTURE, name,
                "compatibility", resourcePath = "src/main/resources/logs/$name.xes.gz"), generated)
        }
    }
    journal.snapshot("compatibility-inputs", files.values.toList())
    fun createCase(name: String, logs: List<String>): Map<String, String> {
        val row = mutableMapOf("name" to name)
        for ((system, client) in clients) {
            maintainBenchmarkSessions(clients, journal)
            val storeName = "bench-compatibility-${system.name}-${name.replace(' ', '-')}"
            val id = journal.progress.operation("create-datastore", dataset = name, system = system.name) {
                client.createDataStore(storeName)
            }
            stores += CreatedDataStoreHandle(system, storeName, id)
            logs.forEach {
                maintainBenchmarkSessions(clients, journal)
                journal.progress.operation("import", dataset = it, system = system.name) {
                    client.uploadLogAndWait(id, files.getValue(it).file)
                }
            }
            require(client.listLogs(id).size == logs.size)
            row[if (system.name == "local") "localDataStoreId" else "remoteDataStoreId"] = id
        }
        return row
    }
    val cases = names.map { createCase(it, listOf(it)) }
    val multi = createCase("JournalReview + Sepsis", listOf("JournalReview", "Sepsis")) + mapOf(
        "primaryLogName" to "JournalReview", "secondaryLogName" to "Sepsis Cases - Event Log")
    Files.writeString(out.resolve("cases.json"), mapper.writeValueAsString(cases))
    Files.writeString(out.resolve("multi-log-cases.json"), mapper.writeValueAsString(listOf(multi)))
    val python = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"
    val command = listOf(python, "scripts/run-compatibility-report.py", "--query-source", "thesis",
        "--cases", out.resolve("cases.json").toString(), "--include-multi-log-checks", "--multi-log-cases",
        out.resolve("multi-log-cases.json").toString(), "--output-root", out.resolve("compatibility").toString(),
        "--base-url", settings.localApi.removeSuffix("/api"), "--save-full-snapshots", "--measure-payload-size")
    journal.progress.operation("compatibility-report") {
        if (ProcessBuilder(command).inheritIO().start().waitFor() != 0)
            throw StudyDataMismatch("Compatibility report contains strict problems; inspect the recorded cases")
    }
}
