package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

class ThesisReportWriterTest {
    @Test
    fun `quantile uses linear interpolation on a known 10-sample vector`() {
        // Hyndman & Fan type 7 on x = 1..10 (deliberately unsorted input):
        // h = 9p -> Q(0.25) = 3.25, Q(0.5) = 5.5, Q(0.75) = 7.75, Q(0.95) = 9.55.
        val values = listOf(10.0, 1.0, 9.0, 2.0, 8.0, 3.0, 7.0, 4.0, 6.0, 5.0)
        assertEquals(3.25, ThesisStatistics.quantile(values, 0.25), 1e-9)
        assertEquals(5.5, ThesisStatistics.quantile(values, 0.50), 1e-9)
        assertEquals(7.75, ThesisStatistics.quantile(values, 0.75), 1e-9)
        assertEquals(9.55, ThesisStatistics.quantile(values, 0.95), 1e-9)

        val stats = ThesisStatistics.stats(values)
        assertEquals(10, stats.samples)
        assertEquals(3.25, stats.q1, 1e-9)
        assertEquals(7.75, stats.q3, 1e-9)
        assertEquals(9.55, stats.p95, 1e-9)
    }

    /**
     * The hoisted trace-variant difference is a REFERENCE deviation from the PQL
     * spec's source-order rule, not a defect here — the report must say so, with the
     * citation, or the invalidated rows read as our incompatibility. Guarded by a test
     * so the argument cannot be dropped silently.
     */
    @Test
    fun `documents the source-order finding for hoisted trace-variant invalidations`(
        @TempDir tempDir: Path,
    ) {
        writeSyntheticRun(tempDir)
        val md = tempDir.resolve("thesis-report.md").readText()

        assertTrue(
            md.contains("## Kolejność zdarzeń w wariantach śladu — zgodność ze specyfikacją PQL"),
            "report must carry the dedicated spec-compliance section",
        )
        assertTrue(
            md.contains("nie z błędu niniejszej implementacji"),
            "the section must state the finding is not a defect of this implementation",
        )
        assertTrue(
            md.contains("By omitting the `order by` clause, the components are returned in the same order"),
            "the section must quote the PQL specification verbatim",
        )
        assertTrue(
            md.contains("https://github.com/ProcessMPUT/processm/blob/master/docs/pql.md"),
            "the quote must be attributed to a citable source",
        )
        // The hoisted pair is flagged in the invalidated list; the plain MISMATCH is not.
        assertTrue(
            md.contains("- ds-beta / hoistedGroup: MISMATCH") &&
                md.substringAfter("- ds-beta / hoistedGroup:").substringBefore('\n').contains("kolejność źródłowa"),
            "the hoisted pair must be marked as the source-order class",
        )
        assertFalse(
            md.substringAfter("- ds_alpha / custom_attr:").substringBefore('\n').contains("kolejność źródłowa"),
            "a plain count MISMATCH must NOT be attributed to the source-order deviation",
        )
    }

    @Test
    fun `writes thesis report and latex tables from synthetic records`(
        @TempDir tempDir: Path,
    ) {
        writeSyntheticRun(tempDir)
        val md = tempDir.resolve("thesis-report.md").readText()
        val tex = tempDir.resolve("thesis-tables.tex").readText()

        // MISMATCH pair (ds_alpha x custom_attr) is excluded from tables and listed
        // under the invalidated-measurements section with its reason.
        assertTrue(md.contains("### Pomiary unieważnione"), "md must contain the invalidated-pair section")
        assertTrue(md.contains("- ds_alpha / custom_attr: MISMATCH"), "invalidated pair must be listed with reason")
        assertTrue(md.contains("traces 5 vs 7"), "MISMATCH details must be propagated to the reason")
        assertTrue(
            md.contains("3 par (dataset, zapytanie) zgodnych (OK), 2 par unieważnionych"),
            "Q4 parity summary must count 3 OK pairs and 2 MISMATCH pairs",
        )

        // METODOLOGIA §Q3 reports per-dataset benchmark storage for REFERENCE only.
        // ds_alpha has a positive LOCAL delta (10 MiB, ratio 10.0); rendering it would
        // present an allocation jump as an expansion factor contradicting the probe.
        assertTrue(md.contains("nie raportowane (§Q3)"), "LOCAL storage cells must carry the marker")
        assertFalse(
            Regex("""\| ds_alpha \| 10\.00 \| 10\.00 \|""").containsMatchIn(md),
            "a positive LOCAL delta must not be rendered as a number",
        )

        // IQR-overlap pair (ds-beta x hierarchyWindow) is marked comparable and listed as a caveat.
        assertTrue(md.contains("porównywalne (IQR nachodzą)"), "md must mark the IQR-overlap pair as comparable")
        assertTrue(md.contains("ds-beta / hierarchyWindow"), "IQR-overlap caveat must name the pair")

        // Hand-computed warm-stats row for ds_alpha x hierarchyWindow:
        // local 10/20/30 ms -> median 20.0, IQR [15.0; 25.0], p95 29.0;
        // reference 40/50/60 ms -> median 50.0, IQR [45.0; 55.0], p95 59.0; ratio 20/50 = 0.40.
        assertTrue(
            md.contains("| hierarchyWindow | 20.0 | [15.0; 25.0] | 29.0 | 50.0 | [45.0; 55.0] | 59.0 | 0.40 | istotna (IQR rozłączne) |"),
            "warm-stats row must match the hand-computed median/IQR/p95/ratio",
        )
        // Import row: 1 MiB XES, LOCAL 2.00 s vs REFERENCE 4.00 s -> ratio 0.50.
        assertTrue(
            md.contains("| ds_alpha | 1.00 | 100 | 1000 | 2.00 | 4.00 | 0.50 |"),
            "import row must match the hand-computed L/R ratio",
        )
        // Cold table row for the clean pair: 100.0 ms vs 200.0 ms -> 0.50.
        assertTrue(
            md.contains("| ds_alpha | hierarchyWindow | 100.0 | 200.0 | 0.50 |"),
            "cold row must match the hand-computed cold ratio",
        )

        // LaTeX: booktabs tables with one \label per table environment.
        val tableCount = Regex("""\\begin\{table}""").findAll(tex).count()
        val labelCount = Regex("""\\label\{tab:bench-""").findAll(tex).count()
        assertEquals(tableCount, labelCount, "every LaTeX table must carry exactly one tab:bench-* label")
        assertEquals(8, tableCount, "environment, import, 2 query tables, cold, storage, memory, roundtrip")
        assertTrue(tex.contains("% generated by ThesisReportWriter run-test"), "tex must start with the generator comment")
        assertTrue(tex.contains("\\toprule") && tex.contains("\\midrule") && tex.contains("\\bottomrule"), "booktabs rules")

        // Underscores in labels must be escaped for LaTeX.
        assertTrue(tex.contains("ds\\_alpha"), "dataset underscore must be escaped")
        assertTrue(tex.contains("custom\\_attr"), "query-label underscore must be escaped")
        for (index in tex.indices) {
            if (tex[index] == '_') {
                assertTrue(index > 0 && tex[index - 1] == '\\', "unescaped underscore at index $index")
            }
        }

        // Comparable marking is carried into the LaTeX significance column too.
        assertTrue(tex.contains("porównywalne (IQR nachodzą)"), "tex must mark the IQR-overlap pair as comparable")
        assertFalse(md.contains("NaN"), "no NaN may leak into the report")
    }

    private fun writeSyntheticRun(tempDir: Path) {
        val settings = BenchmarkSettings(
            profile = BenchmarkProfile.SMOKE,
            localApi = "http://localhost:8080/api",
            referenceApi = "http://localhost:80/api",
            processMLogin = "admin@example.com",
            processMPassword = "secret",
            outputRoot = tempDir,
            datasetFilter = emptySet(),
            systemFilter = emptySet(),
            keepBenchmarkDataStores = false,
            localAppContainer = "processm-interpreter",
        )
        val datasets = listOf(
            dataset("ds_alpha", tempDir),
            dataset("ds-beta", tempDir),
        )
        val imports = listOf(
            importResult("local", "ds_alpha", 2.0),
            importResult("reference", "ds_alpha", 4.0),
            importResult("local", "ds-beta", 1.0),
            importResult("reference", "ds-beta", 1.0),
        )
        val queries = buildList {
            // Pair 1: ds_alpha x hierarchyWindow — clean, disjoint IQRs (hand-computed row).
            add(warm("local", "ds_alpha", "hierarchyWindow", 1, 0.010))
            add(warm("local", "ds_alpha", "hierarchyWindow", 2, 0.020))
            add(warm("local", "ds_alpha", "hierarchyWindow", 3, 0.030))
            add(warm("reference", "ds_alpha", "hierarchyWindow", 1, 0.040))
            add(warm("reference", "ds_alpha", "hierarchyWindow", 2, 0.050))
            add(warm("reference", "ds_alpha", "hierarchyWindow", 3, 0.060))
            add(cold("local", "ds_alpha", "hierarchyWindow", 0.100))
            add(cold("reference", "ds_alpha", "hierarchyWindow", 0.200))
            // Pair 2: ds_alpha x custom_attr — invalidated by response-count MISMATCH.
            add(warm("local", "ds_alpha", "custom_attr", 1, 0.010, status = QUERY_STATUS_MISMATCH, details = "Response count mismatch: traces 5 vs 7"))
            add(warm("reference", "ds_alpha", "custom_attr", 1, 0.011, status = QUERY_STATUS_MISMATCH, details = "Response count mismatch: traces 5 vs 7"))
            add(cold("local", "ds_alpha", "custom_attr", 0.050))
            add(cold("reference", "ds_alpha", "custom_attr", 0.060))
            // Pair 3: ds-beta x hierarchyWindow — overlapping IQRs ([20;40] vs [35;55] ms).
            add(warm("local", "ds-beta", "hierarchyWindow", 1, 0.010))
            add(warm("local", "ds-beta", "hierarchyWindow", 2, 0.030))
            add(warm("local", "ds-beta", "hierarchyWindow", 3, 0.050))
            add(warm("reference", "ds-beta", "hierarchyWindow", 1, 0.025))
            add(warm("reference", "ds-beta", "hierarchyWindow", 2, 0.045))
            add(warm("reference", "ds-beta", "hierarchyWindow", 3, 0.065))
            add(cold("local", "ds-beta", "hierarchyWindow", 0.070))
            add(cold("reference", "ds-beta", "hierarchyWindow", 0.080))
            // Pair 4: ds-beta x custom_attr — clean, disjoint IQRs.
            add(warm("local", "ds-beta", "custom_attr", 1, 0.001))
            add(warm("local", "ds-beta", "custom_attr", 2, 0.001))
            add(warm("reference", "ds-beta", "custom_attr", 1, 0.002))
            add(warm("reference", "ds-beta", "custom_attr", 2, 0.002))
            add(cold("local", "ds-beta", "custom_attr", 0.003))
            add(cold("reference", "ds-beta", "custom_attr", 0.004))
            // Pair 5: ds-beta x hoistedGroup — invalidated on a hoisted trace-variant
            // query, i.e. the REFERENCE source-order deviation class.
            add(warm("local", "ds-beta", "hoistedGroup", 1, 0.010, status = QUERY_STATUS_MISMATCH, details = "Response count mismatch: events 714 vs 723"))
            add(warm("reference", "ds-beta", "hoistedGroup", 1, 0.011, status = QUERY_STATUS_MISMATCH, details = "Response count mismatch: events 714 vs 723"))
            add(cold("local", "ds-beta", "hoistedGroup", 0.050))
            add(cold("reference", "ds-beta", "hoistedGroup", 0.060))
        }
        val storage = listOf(
            storageResult("local", "ds_alpha", 10L * 1024 * 1024, 10.0),
            storageResult("reference", "ds_alpha", 5L * 1024 * 1024, 5.0),
            storageResult("local", "ds-beta", 2L * 1024 * 1024, 2.0),
            storageResult("reference", "ds-beta", 4L * 1024 * 1024, 4.0),
        )
        val roundtrips = listOf(
            RoundtripBenchmarkResult("ds_alpha", "MATCH", 0, ""),
            RoundtripBenchmarkResult("ds-beta", "MATCH", 0, ""),
        )
        val memorySummaries = listOf(
            MemorySummary("processm-neo4j", MEMORY_PHASE_QUERIES, 1_073_741_824, 2_147_483_648),
            MemorySummary("local-jvm", MEMORY_PHASE_QUERIES, 536_870_912, 1_073_741_824),
            MemorySummary("processm-server", MEMORY_PHASE_QUERIES, 1_610_612_736, 3_221_225_472),
        )

        ThesisReportWriter(tempDir).write(
            runId = "run-test",
            settings = settings,
            datasets = datasets,
            imports = imports,
            queries = queries,
            storage = storage,
            roundtrips = roundtrips,
            memorySummaries = memorySummaries,
            querySpecs = listOf(
                BenchmarkQuerySpec("hierarchyWindow", "limit l:1, t:10, e:20"),
                BenchmarkQuerySpec("custom_attr", "where [e:attr_1] is not null limit l:1, t:10"),
                BenchmarkQuerySpec("hoistedGroup", "group by ^e:name order by count(t:name) desc"),
            ),
        )
    }

    private fun dataset(
        name: String,
        tempDir: Path,
    ) = PreparedDataset(
        name = name,
        series = "synthetic",
        file = tempDir.resolve("$name.xes"),
        traces = 100,
        eventsPerTrace = 10,
        totalEvents = 1000,
        attributesPerEvent = 5,
        totalAttributes = 5000,
        xesBytes = 1024L * 1024,
        xesGzBytes = 128L * 1024,
    )

    private fun importResult(
        system: String,
        datasetName: String,
        seconds: Double,
    ) = ImportBenchmarkResult(
        system = system,
        datasetName = datasetName,
        run = 1,
        seconds = seconds,
        status = "OK",
        dataStoreId = "ds-id",
        logCount = 1,
    )

    private fun warm(
        system: String,
        datasetName: String,
        queryLabel: String,
        run: Int,
        seconds: Double,
        status: String = "OK",
        details: String = "",
    ) = QueryBenchmarkResult(
        system = system,
        datasetName = datasetName,
        queryLabel = queryLabel,
        run = run,
        seconds = seconds,
        status = status,
        responseBytes = 100,
        phase = QUERY_PHASE_WARM,
        logCount = 1,
        traceCount = 10,
        eventCount = 100,
        details = details,
    )

    private fun cold(
        system: String,
        datasetName: String,
        queryLabel: String,
        seconds: Double,
    ) = QueryBenchmarkResult(
        system = system,
        datasetName = datasetName,
        queryLabel = queryLabel,
        run = 0,
        seconds = seconds,
        status = "OK",
        responseBytes = 100,
        phase = QUERY_PHASE_COLD,
        logCount = 1,
        traceCount = 10,
        eventCount = 100,
    )

    private fun storageResult(
        system: String,
        datasetName: String,
        deltaBytes: Long,
        xesRatio: Double,
    ) = StorageBenchmarkResult(
        system = system,
        datasetName = datasetName,
        beforeBytes = 0,
        afterBytes = deltaBytes,
        deltaBytes = deltaBytes,
        deltaToXesRatio = xesRatio,
        deltaToGzipRatio = xesRatio * 8,
        status = "OK",
    )
}
