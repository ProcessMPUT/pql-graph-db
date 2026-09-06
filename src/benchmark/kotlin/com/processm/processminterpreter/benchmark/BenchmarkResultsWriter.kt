package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import kotlin.io.path.writeText

class BenchmarkResultsWriter(
    private val outputDirectory: Path,
) {
    fun checkpoint(datasets: List<PreparedDataset>, querySpecs: List<BenchmarkQuerySpec>,
                   imports: List<ImportBenchmarkResult>, queries: List<QueryBenchmarkResult>,
                   roundtrips: List<RoundtripBenchmarkResult>, memorySamples: List<MemorySample>,
                   containerIo: List<ContainerIoBenchmarkResult>) {
        writeDatasets(datasets)
        writeQuerySpecs(querySpecs)
        writeImports(imports)
        writeQueries(queries)
        writeRoundtrips(roundtrips)
        writeMemory(memorySamples)
        writeContainerIo(containerIo)
    }
    private val mapper = jacksonObjectMapper()

    private fun writeDatasets(datasets: List<PreparedDataset>) {
        CsvWriter.write(
            outputDirectory.resolve("datasets.csv"),
            listOf(
                "datasetName",
                "series",
                "traces",
                "eventsPerTrace",
                "totalEvents",
                "attributesPerEvent",
                "totalAttributes",
                "xesBytes",
                "xesGzBytes",
                "meanEventsPerTrace",
                "medianEventsPerTrace",
                "p95EventsPerTrace",
                "maxEventsPerTrace",
                "activityCount",
                "variantCount",
                "sourceDoi",
                "fileSha256",
                "meanEventAttributes",
                "collection",
                "collectionOrder",
            ),
            datasets.map {
                listOf(
                    it.name,
                    it.series,
                    it.traces,
                    it.eventsPerTrace,
                    it.totalEvents,
                    it.attributesPerEvent,
                    it.totalAttributes,
                    it.xesBytes,
                    it.xesGzBytes,
                    it.meanEventsPerTrace,
                    it.medianEventsPerTrace,
                    it.p95EventsPerTrace,
                    it.maxEventsPerTrace,
                    it.activityCount,
                    it.variantCount,
                    it.sourceDoi,
                    it.fileSha256,
                    it.meanEventAttributes,
                    it.collection,
                    it.collectionOrder,
                )
            },
        )
    }

    /** Query semantics used by every downstream table and figure. */
    private fun writeQuerySpecs(querySpecs: List<BenchmarkQuerySpec>) {
        CsvWriter.write(
            outputDirectory.resolve("queries.csv"),
            listOf(
                "queryLabel", "displayName", "role", "workload", "measurementSeries",
                "scalingSeries", "purpose", "clause", "pql",
            ),
            querySpecs.map {
                listOf(
                    it.label, it.displayName, it.role.name.lowercase(), it.workload,
                    it.measurementSeries.sorted().joinToString(";"),
                    it.scalingSeries.sorted().joinToString(";"), it.purpose, it.clause, it.query,
                )
            },
        )
    }

    private fun writeContainerIo(rows: List<ContainerIoBenchmarkResult>) {
        CsvWriter.write(
            outputDirectory.resolve("container-io.csv"),
            listOf(
                "system", "phase", "datasetName", "operationLabel", "run", "component",
                "blockReadBytes", "blockWriteBytes", "blockReadOperations", "blockWriteOperations",
                "networkReceiveBytes", "networkTransmitBytes", "status", "details",
                "completedOperations", "windowStartedNanos", "windowFinishedNanos",
            ),
            rows.map {
                listOf(
                    it.system, it.phase, it.datasetName, it.operationLabel, it.run, it.component,
                    it.blockReadBytes, it.blockWriteBytes, it.blockReadOperations, it.blockWriteOperations,
                    it.networkReceiveBytes, it.networkTransmitBytes, it.status, it.details,
                    it.completedOperations, it.windowStartedNanos, it.windowFinishedNanos,
                )
            },
        )
    }

    private fun writeImports(imports: List<ImportBenchmarkResult>) {
        CsvWriter.write(
            outputDirectory.resolve("import-results.csv"),
            listOf("system", "datasetName", "run", "seconds", "status", "dataStoreId", "logCount", "details", "startedAt", "startedNanos", "finishedNanos", "httpStatus"),
            imports.map { listOf(it.system, it.datasetName, it.run, it.seconds.takeIf { _ -> it.status != "ERROR" }, it.status, it.dataStoreId, it.logCount, it.details, it.startedAt, it.startedNanos, it.finishedNanos, it.httpStatus) },
        )
    }

    private fun writeQueries(queries: List<QueryBenchmarkResult>) {
        CsvWriter.write(
            outputDirectory.resolve("query-results.csv"),
            listOf(
                "system",
                "datasetName",
                "queryLabel",
                "run",
                "phase",
                "seconds",
                "status",
                "responseBytes",
                "logCount",
                "traceCount",
                "eventCount",
                "details",
                "startedAt", "startedNanos", "finishedNanos", "httpStatus", "executionIndex", "responsePath",
            ),
            queries.map {
                listOf(
                    it.system,
                    it.datasetName,
                    it.queryLabel,
                    it.run,
                    it.phase,
                    it.seconds.takeIf { _ -> it.status != "ERROR" },
                    it.status,
                    it.responseBytes,
                    it.logCount,
                    it.traceCount,
                    it.eventCount,
                    it.details,
                    it.startedAt, it.startedNanos, it.finishedNanos, it.httpStatus, it.executionIndex, it.responsePath,
                )
            },
        )
    }

    private fun writeMemory(samples: List<MemorySample>) {
        CsvWriter.write(outputDirectory.resolve("memory-results.csv"),
            listOf("timestamp", "phase", "datasetName", "operationLabel", "component", "bytes", "completedAt", "activeSystem", "withinWindow"),
            samples.map { listOf(it.timestamp, it.phase, it.datasetName, it.operationLabel, it.component,
                it.bytes, it.completedAt, it.activeSystem, it.withinWindow) })
    }

    private fun writeRoundtrips(roundtrips: List<RoundtripBenchmarkResult>) {
        CsvWriter.write(
            outputDirectory.resolve("roundtrip-results.csv"),
            listOf("datasetName", "status", "differencesCount", "detailsPath"),
            roundtrips.map { listOf(it.datasetName, it.status, it.differencesCount, it.detailsPath) },
        )
    }

    fun writeEnvironment(job: StudyJob, settings: BenchmarkSettings, details: Map<String, Any?>) {
        val environment = details + mapOf(
            "benchmarkProtocolVersion" to job.protocolVersion, "phase" to job.phase,
            "warmups" to job.warmups, "repetitions" to job.pairs, "importRepetitions" to job.importPairs,
            "globalWarmupRounds" to job.globalWarmupRounds, "datasetOrderSeed" to job.seed,
            "resourceWindowSeconds" to job.resourceWindowSeconds, "resourceMinimumSamples" to job.resourceMinimumSamples,
            "memoryMetric" to "docker-cli-usage-minus-inactive-file",
            "localApi" to settings.localApi, "referenceApi" to settings.referenceApi,
        )
        outputDirectory.resolve("environment.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(environment))
    }
}
