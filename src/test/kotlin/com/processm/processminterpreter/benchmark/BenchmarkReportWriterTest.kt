package com.processm.processminterpreter.benchmark

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkReportWriterTest {
    @Test
    fun `protocol 22 report explains temporal diagnostics and separates the appendix`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val dataset = PreparedDataset("size-1k", "size-scaling", Path.of("size-1k.xes"), 100, 10, 1_000, 5, 5_000, 1, 1)
        val spec = BenchmarkQuerySpec(
            label = "hierarchyWindow",
            displayName = "Pobranie hierarchii",
            query = "limit l:1, t:10, e:20",
            role = BenchmarkQueryRole.PRIMARY,
            measurementSeries = listOf("size-scaling"),
        )
        val comparison = BenchmarkComparisonResult(
            metric = "query",
            datasetName = dataset.name,
            series = dataset.series,
            operationLabel = spec.label,
            displayName = spec.displayName,
            role = "primary",
            pairs = 30,
            localMedianSeconds = 0.1,
            referenceMedianSeconds = 0.2,
            ratioReferenceToLocal = 2.0,
            confidenceLow = 1.7,
            confidenceHigh = 2.3,
            rawPValue = 0.001,
            holmPValue = 0.00001,
            verdict = "LOCAL_FASTER",
            status = "OK",
            stabilityWindowSamples = 10,
            localEarlyMedianSeconds = 0.101,
            localLateMedianSeconds = 0.100,
            localEarlyLateRatio = 1.01,
            referenceEarlyMedianSeconds = 0.202,
            referenceLateMedianSeconds = 0.200,
            referenceEarlyLateRatio = 1.01,
            pairedEarlyMedianRatio = 2.0,
            pairedLateMedianRatio = 2.0,
            pairedEarlyLateRatio = 1.0,
        )
        val settings = BenchmarkSettings(
            BenchmarkProfile.FULL, "local", "reference", "", "", tempDir,
            emptySet(), emptySet(), false, "processm-interpreter", protocolVersion = 22,
        )

        BenchmarkReportWriter(tempDir).write(
            "run", settings, listOf(dataset), listOf(spec), emptyList(), emptyList(),
            listOf(comparison), emptyList(), emptyList(), emptyList(),
        )

        val report = tempDir.resolve("benchmark-report.md").readText()
        val appendix = tempDir.resolve("benchmark-appendix.md").readText()
        assertTrue(report.contains("wersja metodologii: 22"))
        assertFalse(report.contains("protokół: 15"))
        assertTrue(report.contains("każdy system wykonuje 40 rozgrzewek"))
        assertTrue(report.contains("osobnego, niemierzonego powtórzenia 30 par"))
        assertTrue(report.contains("Efekt to `REFERENCE / LOCAL`"))
        assertTrue(report.contains("### Jak powstają liczby w tabelach"))
        assertTrue(report.contains("`n` to liczba poprawnych par"))
        assertTrue(report.contains("Całe pary są losowane ze zwracaniem 10 000 razy"))
        assertTrue(report.contains("percentyle 2,5% i 97,5%"))
        assertTrue(report.contains("nie jest prawdopodobieństwem, że hipoteza zerowa jest prawdziwa"))
        assertTrue(report.contains("cały 95% CI leży po jednej stronie 1"))
        assertTrue(report.contains("Diagnostyka stabilności czasowej"))
        assertTrue(report.contains("pierwszej i ostatniej jednej trzeciej"))
        assertTrue(report.contains("Nie używa się mediany ilorazów pojedynczych par"))
        assertTrue(report.contains("nie usuwa kompletnych par"))
        assertTrue(report.contains("Stały próg 10% nie jest testem statystycznym"))
        assertTrue(report.contains("### Dokładne zapytania PQL"))
        assertTrue(report.contains("| Pobranie hierarchii | `limit l:1, t:10, e:20` | główne |"))
        assertTrue(report.contains("| Kontrola | Wynik |"))
        assertTrue(report.contains("| Błędy importu | 0 |"))
        assertTrue(report.contains("| Serie czasowe wykluczone jako niestabilne | 0 |"))
        assertTrue(report.contains("| Ostrzeżenia o dryfie efektu parowanego | 0 |"))
        assertTrue(report.contains("| Opisowe baseline’y z ostrzeżeniem o dryfie | 0 |"))
        assertTrue(report.contains("2.00 (1.70–2.30)"))
        assertTrue(report.contains("| size-1k | Pobranie hierarchii (`limit l:1, t:10, e:20`) | główne |"))
        assertTrue(report.contains("LOCAL szybszy"))
        assertTrue(report.contains("Zapytania główne (1):"))
        assertTrue(report.contains("<0.0001"))
        assertTrue(report.contains("fig-08-container-network-io.svg"))
        assertTrue(report.contains("MiB oznacza 2²⁰ bajtów"))
        assertTrue(report.contains("Blok powtarza zapytania i kolejność systemów z części czasowej"))
        assertFalse(report.contains("skala logarytmiczna", ignoreCase = true))
        assertTrue(appendix.contains("## Wszystkie porównania"))
        assertTrue(appendix.contains("## Kontrola stabilności czasowej"))
        assertTrue(appendix.contains("| size-1k | Pobranie hierarchii | 10 | 101.00 | 100.00 | 1.010 |"))
        assertTrue(appendix.contains("Pobranie hierarchii"))
        assertTrue(appendix.contains("| Zbiór | Seria |"))
        assertTrue(appendix.contains("| size-1k | size-scaling |"))
        assertTrue(appendix.contains("| Etykieta | Nazwa | PQL | Rola |"))
        assertTrue(appendix.contains("| hierarchyWindow | Pobranie hierarchii | `limit l:1, t:10, e:20` | główne |"))
        assertTrue(appendix.contains("| Faza | Zbiór | Operacja |"))
    }

    @Test
    fun `internal Neo4j network gap remains visible without invalidating complete report metrics`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val settings = BenchmarkSettings(
            BenchmarkProfile.FULL, "local", "reference", "", "", tempDir,
            emptySet(), emptySet(), false, "processm-interpreter", protocolVersion = 22,
        )
        val mebibyte = 1024L * 1024L
        val internalNeo4j = ContainerIoBenchmarkResult(
            system = "local",
            phase = "import",
            datasetName = "real-bpic15-1",
            operationLabel = "import",
            run = 1,
            component = "processm-neo4j",
            blockReadBytes = 4 * mebibyte,
            blockWriteBytes = 5 * mebibyte,
            blockReadOperations = 3,
            blockWriteOperations = 4,
            networkReceiveBytes = null,
            networkTransmitBytes = null,
            status = "UNAVAILABLE",
            details = "processm-neo4j: byte counters unavailable",
        )

        BenchmarkReportWriter(tempDir).write(
            "run", settings, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), listOf(internalNeo4j), emptyList(), emptyList(),
        )

        val report = tempDir.resolve("benchmark-report.md").readText()
        assertTrue(report.contains("Wewnętrzne bramki przebiegu przeszły"))
        assertTrue(report.contains("| Krytyczne braki lub wyzerowania pomiarów I/O | 0 |"))
        assertTrue(report.contains("| Częściowe snapshoty I/O (diagnostyczne) | 1 |"))
        assertTrue(report.contains("Surowe wiersze zachowują status `UNAVAILABLE`"))
        assertTrue(report.contains("| LOCAL | — | — | 4.0 | 5.0 | 3 | 4 |"))
    }

    @Test
    fun `application boundary network gap invalidates report`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val settings = BenchmarkSettings(
            BenchmarkProfile.FULL, "local", "reference", "", "", tempDir,
            emptySet(), emptySet(), false, "processm-interpreter",
        )
        val applicationBoundary = ContainerIoBenchmarkResult(
            system = "local",
            phase = "query",
            datasetName = "size-1k",
            operationLabel = "hierarchyWindow",
            run = 0,
            component = "processm-interpreter",
            blockReadBytes = 1,
            blockWriteBytes = 2,
            blockReadOperations = 3,
            blockWriteOperations = 4,
            networkReceiveBytes = null,
            networkTransmitBytes = null,
            status = "UNAVAILABLE",
        )

        BenchmarkReportWriter(tempDir).write(
            "run", settings, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), listOf(applicationBoundary), emptyList(), emptyList(),
        )

        val report = tempDir.resolve("benchmark-report.md").readText()
        assertTrue(report.contains("Wewnętrzne bramki przebiegu **nie przeszły**"))
        assertTrue(report.contains("| Krytyczne braki lub wyzerowania pomiarów I/O | 1 |"))
        assertTrue(report.contains("| Częściowe snapshoty I/O (diagnostyczne) | 0 |"))
    }

    @Test
    fun `protocol 22 report includes complete isolated storage evidence`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val datasets = listOf(1_000, 2_000, 3_000).map { events ->
            PreparedDataset(
                "size-${events / 1_000}k", "size-scaling", Path.of("size-$events.xes"),
                events / 10, 10, events, 5, events * 5, events * 50L, events * 10L,
            )
        }
        val storage = datasets.flatMapIndexed { index, dataset ->
            listOf("local" to 100L, "reference" to 200L).map { (system, bytesPerEvent) ->
                val delta = dataset.totalEvents * bytesPerEvent
                IsolatedStorageScalingResult(
                    datasetName = dataset.name,
                    system = system,
                    measurementMode = "isolated-fresh-stack",
                    stackPreparationId = "stack-$index",
                    gitCommit = "a".repeat(40),
                    localAppImageId = "sha256:local",
                    localDbImageId = "sha256:neo4j",
                    referenceImageId = "sha256:reference",
                    beforeBytes = 1_000_000,
                    afterBytes = 1_000_000 + delta,
                    deltaBytes = delta,
                    xesBytes = dataset.xesBytes,
                    xesGzBytes = dataset.xesGzBytes,
                    deltaToXesRatio = delta.toDouble() / dataset.xesBytes,
                    deltaToGzipRatio = delta.toDouble() / dataset.xesGzBytes,
                )
            }
        }
        val settings = BenchmarkSettings(
            BenchmarkProfile.FULL, "local", "reference", "", "", tempDir,
            emptySet(), emptySet(), false, "processm-interpreter",
        )

        BenchmarkReportWriter(tempDir).write(
            runId = "run",
            settings = settings,
            datasets = datasets,
            querySpecs = emptyList(),
            imports = emptyList(),
            queries = emptyList(),
            comparisons = emptyList(),
            containerIo = emptyList(),
            memorySummaries = emptyList(),
            roundtrips = emptyList(),
            storageScaling = storage,
        )

        val report = tempDir.resolve("benchmark-report.md").readText()
        val appendix = tempDir.resolve("benchmark-appendix.md").readText()
        assertTrue(report.contains("kompletna: 3 świeżych stosów, 6 pomiarów"))
        assertTrue(report.contains("## Trwały rozmiar danych"))
        assertTrue(report.contains("100 B/zdarzenie"))
        assertTrue(report.contains("200 B/zdarzenie"))
        assertTrue(report.contains("2.00 raza większy"))
        assertTrue(report.contains("Sonda trwałego rozmiaru ma jeden świeży stos per punkt"))
        assertTrue(appendix.contains("## Izolowana sonda trwałego rozmiaru"))
        assertTrue(appendix.contains("| size-1k | LOCAL | isolated-fresh-stack |"))
    }

    @Test
    fun `real validation is summarized as characteristics and a compact matrix`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val dataset = PreparedDataset(
            name = "real-hospital",
            series = "real-validation",
            file = Path.of("Hospital_log.xes.gz"),
            traces = 1_143,
            eventsPerTrace = 131,
            totalEvents = 150_291,
            attributesPerEvent = 9,
            totalAttributes = 1_000_000,
            xesBytes = 20_000_000,
            xesGzBytes = 2_000_000,
            meanEventsPerTrace = 131.5,
            medianEventsPerTrace = 55.0,
            p95EventsPerTrace = 511,
            maxEventsPerTrace = 1_814,
            activityCount = 624,
            variantCount = 981,
            sourceDoi = "10.4121/example",
            fileSha256 = "a".repeat(64),
            meanEventAttributes = 9.0,
        )
        val spec = BenchmarkQuerySpec(
            label = "hierarchyWindow",
            displayName = "Pobranie hierarchii",
            query = "limit l:1, t:10, e:20",
            role = BenchmarkQueryRole.PRIMARY,
            measurementSeries = listOf("real-validation"),
        )
        val comparison = BenchmarkComparisonResult(
            metric = "query",
            datasetName = dataset.name,
            series = dataset.series,
            operationLabel = spec.label,
            displayName = spec.displayName,
            role = "primary",
            pairs = 30,
            localMedianSeconds = 0.1,
            referenceMedianSeconds = 0.2,
            ratioReferenceToLocal = 2.0,
            confidenceLow = 1.7,
            confidenceHigh = 2.3,
            rawPValue = 0.001,
            holmPValue = 0.005,
            verdict = "LOCAL_FASTER",
            status = "OK",
        )
        val settings = BenchmarkSettings(
            BenchmarkProfile.FULL, "local", "reference", "", "", tempDir,
            emptySet(), emptySet(), false, "processm-interpreter",
        )

        BenchmarkReportWriter(tempDir).write(
            "run", settings, listOf(dataset), listOf(spec), emptyList(), emptyList(),
            listOf(comparison), emptyList(), emptyList(), emptyList(),
        )

        val report = tempDir.resolve("benchmark-report.md").readText()
        val appendix = tempDir.resolve("benchmark-appendix.md").readText()
        assertTrue(report.contains("Jeden opublikowany log"))
        assertTrue(report.contains("| BPIC11 (Hospital) | 1143 | 150291 | 131.5/55.0/511/1814 | 624 | 981 | 9.0 | 10.4121/example |"))
        assertTrue(report.contains("| BPIC11 (Hospital) | L 2.00× |"))
        assertFalse(report.contains("2.00 (1.70–2.30)"))
        assertTrue(appendix.contains("2.00 (1.70–2.30)"))
        assertTrue(appendix.contains("a".repeat(64)))
    }

    @Test
    fun `smoke report is explicitly non inferential and omits absent real-log section`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val settings = BenchmarkSettings(
            BenchmarkProfile.SMOKE, "local", "reference", "", "", tempDir,
            emptySet(), emptySet(), false, "processm-interpreter",
        )

        BenchmarkReportWriter(tempDir).write(
            "smoke", settings, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(),
        )

        val report = tempDir.resolve("benchmark-report.md").readText()
        assertTrue(report.contains("SMOKE ma za mało powtórzeń"))
        assertTrue(report.contains("zapytanie — 3 pary, import — 1 para"))
        assertFalse(report.contains("## Walidacja na logach rzeczywistych"))
    }

    @Test
    fun `pilot report is explicitly non inferential`(@org.junit.jupiter.api.io.TempDir tempDir: Path) {
        val settings = BenchmarkSettings(
            BenchmarkProfile.PILOT, "local", "reference", "", "", tempDir,
            emptySet(), emptySet(), false, "processm-interpreter",
        )

        BenchmarkReportWriter(tempDir).write(
            "pilot", settings, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(),
        )

        val report = tempDir.resolve("benchmark-report.md").readText()
        assertTrue(report.contains("PILOT ma za mało powtórzeń"))
        assertTrue(report.contains("nie są wynikiem badania"))
        assertTrue(report.contains("nie dotyczy przebiegu diagnostycznego"))
    }
}
