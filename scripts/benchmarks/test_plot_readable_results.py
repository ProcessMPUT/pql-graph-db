#!/usr/bin/env python3
"""Contract test for the current standard-library SVG generator."""
from __future__ import annotations

import csv
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("plot-readable-benchmark-results.py")
SPEC = importlib.util.spec_from_file_location("plot_readable", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
RENDER_SCRIPT = Path(__file__).with_name("render-report-html.py")
RENDER_SPEC = importlib.util.spec_from_file_location("render_report", RENDER_SCRIPT)
assert RENDER_SPEC and RENDER_SPEC.loader
RENDER = importlib.util.module_from_spec(RENDER_SPEC)
RENDER_SPEC.loader.exec_module(RENDER)


class ReadablePlotTest(unittest.TestCase):
    def test_dataset_names_are_sorted_by_their_numeric_parts(self) -> None:
        names = ["size-100k", "size-1m", "size-1k", "size-500k", "size-200k", "size-20k", "size-5k"]
        self.assertEqual(
            ["size-1k", "size-5k", "size-20k", "size-100k", "size-200k", "size-500k", "size-1m"],
            sorted(names, key=MODULE.natural_sort_key),
        )

    def test_internal_network_gap_does_not_discard_exact_block_counters(self) -> None:
        io = [
            {
                "system": "local", "phase": "import", "component": "processm-interpreter",
                "blockReadBytes": "10", "blockWriteBytes": "20",
                "networkReceiveBytes": "30", "networkTransmitBytes": "40", "status": "OK",
            },
            {
                "system": "local", "phase": "import", "component": "processm-neo4j",
                "blockReadBytes": "50", "blockWriteBytes": "60",
                "networkReceiveBytes": "", "networkTransmitBytes": "", "status": "UNAVAILABLE",
            },
        ]

        self.assertEqual(60, MODULE.container_io_total(io, "local", "import", "blockReadBytes"))
        self.assertEqual(80, MODULE.container_io_total(io, "local", "import", "blockWriteBytes"))
        self.assertEqual(30, MODULE.container_io_total(io, "local", "import", "networkReceiveBytes", True))
        self.assertEqual(40, MODULE.container_io_total(io, "local", "import", "networkTransmitBytes", True))

    def test_generates_exactly_eight_linear_svg_figures(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            comparison_headers = [
                "metric", "datasetName", "series", "operationLabel", "displayName", "role", "pairs",
                "localMedianSeconds", "referenceMedianSeconds", "ratioReferenceToLocal", "confidenceLow",
                "confidenceHigh", "rawPValue", "holmPValue", "verdict", "status", "details",
            ]
            comparison_rows = [
                ["query", "size-1k", "size-scaling", "minimalWindow", "Minimal window", "baseline", 30, .003, .004, 1.33, 1.1, 1.5, "", "", "DESCRIPTIVE", "OK", ""],
                ["query", "size-1k", "size-scaling", "hierarchyWindow", "Hierarchy", "primary", 30, .1, .2, 2, 1.5, 2.5, .001, .005, "LOCAL_FASTER", "OK", ""],
                ["query", "size-1k", "size-scaling", "likeScan", "LIKE scan", "control", 30, .3, .15, .5, .4, .8, .001, .005, "REFERENCE_FASTER", "OK", ""],
                ["query", "variants-100", "variant-scaling", "hierarchyWindow", "Hierarchy", "primary", 30, .2, .2, 1, .8, 1.2, .5, 1, "NO_DIFFERENCE", "OK", ""],
                ["query", "real-sample", "real-validation", "hierarchyWindow", "Hierarchy", "primary", 30, .3, .2, .67, .5, .9, .001, .005, "REFERENCE_FASTER", "OK", ""],
                ["import", "size-1k", "size-scaling", "import", "Import XES", "primary", 10, 1, 2, 2, 1.5, 2.5, .01, .05, "NO_DIFFERENCE", "OK", ""],
            ]
            self.write_csv(run / "comparison-results.csv", comparison_headers, comparison_rows)
            self.write_csv(
                run / "queries.csv", ["queryLabel", "pql"],
                [
                    ["minimalWindow", "limit l:1, t:1, e:1"],
                    ["hierarchyWindow", "limit l:1, t:10, e:20"],
                    ["likeScan", "where e:name like '%zzq%' order by e:name, e:timestamp limit l:1, t:10, e:20"],
                ],
            )
            self.write_csv(
                run / "query-summary.csv",
                ["system", "datasetName", "queryLabel", "samples", "medianSeconds", "q1Seconds", "q3Seconds", "p95Seconds", "minSeconds", "maxSeconds", "averageSeconds"],
                [
                    ["local", "size-1k", "minimalWindow", 30, .003, .0025, .0035, .004, .002, .005, .003],
                    ["reference", "size-1k", "minimalWindow", 30, .004, .0035, .0045, .005, .003, .006, .004],
                ],
            )
            self.write_csv(
                run / "memory-summary.csv", ["component", "phase", "medianBytes", "peakBytes"],
                [["local-total", "queries", 1000, 2000], ["reference-total", "queries", 1500, 2500]],
            )
            self.write_csv(
                run / "container-io.csv",
                ["system", "phase", "component", "blockReadBytes", "blockWriteBytes", "networkReceiveBytes", "networkTransmitBytes", "status"],
                [["local", "query", "processm-interpreter", 10, 20, 30, 40, "OK"], ["reference", "query", "processm-server", 15, 25, 35, 45, "OK"]],
            )

            MODULE.generate(run)

            figures = sorted((run / "figures").glob("*.svg"))
            self.assertEqual(8, len(figures))
            for figure in figures:
                content = figure.read_text(encoding="utf-8")
                self.assertIn("<svg", content)
                self.assertIn('<rect width="100%" height="100%" fill="white"/>', content)
                self.assertNotIn("logarithmic", content.lower())

            forest = (run / "figures" / "fig-01-query-effect-size.svg").read_text(encoding="utf-8")
            unity_positions = re.findall(r'<line x1="([0-9.]+)"[^>]+class="unity"', forest)
            self.assertEqual(2, len(unity_positions))
            self.assertEqual(1, len(set(unity_positions)))
            self.assertIn("oś X:", forest)
            self.assertIn("(limit l:1, t:10, e:20)", forest)
            latency = (run / "figures" / "fig-02-query-latency-size.svg").read_text(encoding="utf-8")
            self.assertIn("n=30 sparowanych powtórzeń", latency)
            self.assertIn("Obie osie są liniowe", latency)
            self.assertIn("Baseline: niepołączone punkty i IQR", latency)
            self.assertEqual(4, latency.count("<polyline"))
            self.assertEqual(6, latency.count('class="baseline-iqr"'))
            self.assertIn("(limit l:1, t:10, e:20)", latency)
            self.assertTrue((run / "figures" / "fig-03-query-effect-variants.svg").is_file())
            import_figure = (run / "figures" / "fig-05-import-effect.svg").read_text(encoding="utf-8")
            self.assertIn("mediany w s", import_figure)
            self.assertIn("LOCAL [s]", import_figure)
            self.assertIn("REFERENCE [s]", import_figure)
            self.assertIn(">1.00</text>", import_figure)
            self.assertIn(">2.00</text>", import_figure)
            memory = (run / "figures" / "fig-06-container-memory.svg").read_text(encoding="utf-8")
            self.assertIn("Mediana i maksimum wskazania docker stats", memory)
            self.assertNotIn("Suma delt", memory)

            report = [
                "# Report",
                "",
                "Run metadata.",
                "",
                "## Najważniejszy wynik",
                "",
                "Summary.",
                "",
            ]
            report.extend(f"![Figure](figures/{figure.name})" for figure in figures)
            report.extend([
                "",
                "## Pamięć i I/O kontenerów",
                "",
                "Resources.",
                "",
                "## Ograniczenia",
                "",
                "Limitations.",
            ])
            (run / "benchmark-report.md").write_text("\n\n".join(report), encoding="utf-8")
            RENDER.build(run, run / "benchmark-report.html")
            html = (run / "benchmark-report.html").read_text(encoding="utf-8")
            self.assertEqual(8, html.count("<svg"))
            self.assertIn('<nav class="toc" aria-label="Spis treści">', html)
            self.assertIn('href="#najwazniejszy-wynik"', html)
            self.assertIn('href="#pamiec-i-io-kontenerow"', html)
            self.assertIn('href="#ograniczenia"', html)
            self.assertEqual(3, html.count('<details class="report-section"'))
            self.assertIn('<details class="report-section" id="najwazniejszy-wynik" open>', html)
            self.assertIn('<details class="report-section" id="ograniczenia">', html)
            self.assertIn('data-sections-action="expand"', html)
            self.assertIn('data-sections-action="collapse"', html)
            self.assertIn("details.report-section:not([open]) > .section-content", html)
            self.assertIn("window.addEventListener('hashchange', openHashTarget)", html)

    def test_protocol_23_adds_bpi_figures_and_contextual_resource_positions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            (run / "environment.json").write_text(
                json.dumps({"benchmarkProtocolVersion": 23}), encoding="utf-8",
            )
            self.write_csv(
                run / "datasets.csv", ["datasetName", "collection", "collectionOrder"],
                [["real-hospital", "bpi-challenge", 2011]],
            )
            self.write_csv(
                run / "comparison-results.csv",
                ["metric", "datasetName", "series", "operationLabel", "displayName", "role", "pairs",
                 "localMedianSeconds", "referenceMedianSeconds", "ratioReferenceToLocal", "confidenceLow",
                 "confidenceHigh", "rawPValue", "holmPValue", "verdict", "status", "details"],
                [["query", "real-hospital", "real-validation", "hierarchyWindow", "Hierarchy", "primary", 30,
                  .1, .2, 2, 1.5, 2.5, .001, .005, "LOCAL_FASTER", "OK", ""]],
            )
            self.write_csv(run / "queries.csv", ["queryLabel", "pql"], [["hierarchyWindow", "limit l:1"]])
            self.write_csv(run / "query-summary.csv", ["system", "datasetName", "queryLabel"], [])
            self.write_csv(
                run / "memory-summary.csv",
                ["datasetName", "operationLabel", "component", "phase", "medianBytes", "peakBytes"],
                [["real-hospital", "hierarchyWindow", "local-total", "queries", 1000, 2000],
                 ["real-hospital", "hierarchyWindow", "reference-total", "queries", 1500, 2500]],
            )
            self.write_csv(
                run / "container-io.csv",
                ["system", "phase", "datasetName", "operationLabel", "run", "component", "blockReadBytes",
                 "blockWriteBytes", "networkReceiveBytes", "networkTransmitBytes", "status"],
                [["local", "query", "real-hospital", "hierarchyWindow", 0, "processm-interpreter", 10, 20, 30, 40, "OK"],
                 ["reference", "query", "real-hospital", "hierarchyWindow", 0, "processm-server", 15, 25, 35, 45, "OK"]],
            )

            MODULE.generate(run)

            figures = sorted((run / "figures").glob("*.svg"))
            self.assertEqual(10, len(figures))
            self.assertTrue((run / "figures" / "fig-05-query-effect-bpi.svg").is_file())
            real = (run / "figures" / "fig-04-query-effect-real.svg").read_text(encoding="utf-8")
            bpi = (run / "figures" / "fig-05-query-effect-bpi.svg").read_text(encoding="utf-8")
            self.assertIn("LOCAL [ms]", real)
            self.assertIn("REFERENCE [ms]", real)
            self.assertIn("LOCAL [ms]", bpi)
            self.assertIn("REFERENCE [ms]", bpi)
            heatmap = (run / "figures" / "fig-06-query-effect-bpi-heatmap.svg").read_text(encoding="utf-8")
            self.assertIn("BPIC11 (Hospital)", heatmap)
            split = MODULE.generate_split_query_figures(run, "real-validation")
            self.assertEqual([run / "figures" / "fig-query-real-hierarchywindow.svg"], split)
            split_content = split[0].read_text(encoding="utf-8")
            self.assertIn("Logi rzeczywiste — Hierarchy", split_content)
            self.assertIn("LOCAL [ms]", split_content)
            self.assertIn(">100.00</text>", split_content)
            self.assertIn(">200.00</text>", split_content)
            self.assertTrue((run / "figures" / "fig-08-container-memory.svg").is_file())
            self.assertTrue((run / "figures" / "fig-10-container-network-io.svg").is_file())

    def test_protocol_25_gives_each_size_query_an_independent_effect_axis(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            (run / "environment.json").write_text(
                json.dumps({"benchmarkProtocolVersion": 25}), encoding="utf-8",
            )
            self.write_csv(run / "datasets.csv", ["datasetName", "collection", "collectionOrder"], [])
            headers = [
                "metric", "datasetName", "series", "operationLabel", "displayName", "role", "pairs",
                "localMedianSeconds", "referenceMedianSeconds", "ratioReferenceToLocal", "confidenceLow",
                "confidenceHigh", "rawPValue", "holmPValue", "verdict", "status", "details",
            ]
            self.write_csv(
                run / "comparison-results.csv", headers,
                [
                    ["query", "size-1k", "size-scaling", "hierarchyWindow", "Hierarchy", "primary", 30,
                     .1, .2, 2, 1.5, 2.5, .001, .005, "LOCAL_FASTER", "OK", ""],
                    ["query", "size-1k", "size-scaling", "variantGroupCount", "Variants", "primary", 30,
                     .1, 8, 80, 70, 90, .001, .005, "LOCAL_FASTER", "OK", ""],
                ],
            )
            self.write_csv(
                run / "queries.csv", ["queryLabel", "pql"],
                [["hierarchyWindow", "limit l:1"], ["variantGroupCount", "group by ^e:name"]],
            )
            self.write_csv(run / "query-summary.csv", ["system", "datasetName", "queryLabel"], [])
            self.write_csv(run / "memory-summary.csv", ["component", "phase", "medianBytes", "peakBytes"], [])
            self.write_csv(
                run / "container-io.csv",
                ["system", "phase", "component", "blockReadBytes", "blockWriteBytes",
                 "networkReceiveBytes", "networkTransmitBytes", "status"],
                [],
            )
            figures = run / "figures"
            figures.mkdir()
            (figures / "fig-03-query-effect-variants.svg").write_text("stale", encoding="utf-8")

            MODULE.generate(run)

            hierarchy_path = run / "figures" / "fig-query-size-hierarchywindow.svg"
            variants_path = run / "figures" / "fig-query-size-variantgroupcount.svg"
            self.assertTrue(hierarchy_path.is_file())
            self.assertTrue(variants_path.is_file())
            hierarchy = hierarchy_path.read_text(encoding="utf-8")
            variants = variants_path.read_text(encoding="utf-8")
            self.assertIn("0–3.00", hierarchy)
            self.assertIn("0–100.00", variants)
            self.assertIn("mediany w ms", hierarchy)
            self.assertIn("LOCAL [ms]", hierarchy)
            self.assertIn("REFERENCE [ms]", hierarchy)
            self.assertIn(">100.00</text>", hierarchy)
            self.assertIn(">200.00</text>", hierarchy)
            self.assertFalse((run / "figures" / "fig-01-query-effect-size.svg").exists())
            self.assertFalse((run / "figures" / "fig-02-query-latency-size.svg").exists())
            self.assertFalse((run / "figures" / "fig-03-query-effect-variants.svg").exists())
            self.assertFalse((run / "figures" / "fig-04-query-effect-real.svg").exists())
            self.assertFalse((run / "figures" / "fig-05-query-effect-bpi.svg").exists())
            self.assertFalse((run / "figures" / "fig-06-query-effect-bpi-heatmap.svg").exists())
            self.assertFalse((run / "figures" / "fig-07-import-effect.svg").exists())
            self.assertEqual(5, len(list((run / "figures").glob("*.svg"))))

    def test_resource_summary_figures_keep_exact_values_beside_ratios(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            size_run = root / "size"
            size_memory_run = root / "size-memory"
            variant_run = root / "variants"
            real_run = root / "real"
            for run in (size_run, size_memory_run, variant_run, real_run):
                run.mkdir()
            mib = 1024 * 1024
            self.write_csv(
                size_run / "storage-scaling.csv",
                ["datasetName", "system", "deltaBytes"],
                [
                    ["size-1k", "local", mib],
                    ["size-1k", "reference", 4 * mib],
                    ["size-1m", "local", 10 * mib],
                    ["size-1m", "reference", 20 * mib],
                ],
            )
            for run, multiplier in ((size_run, 1), (variant_run, 2), (real_run, 3)):
                self.write_csv(
                    run / "memory-summary.csv",
                    ["component", "phase", "medianBytes", "peakBytes"],
                    [
                        ["local-total", "queries", multiplier * 100 * mib, multiplier * 150 * mib],
                        ["reference-total", "queries", multiplier * 50 * mib, multiplier * 90 * mib],
                    ],
                )
            self.write_csv(
                size_memory_run / "memory-summary.csv",
                ["component", "phase", "medianBytes", "peakBytes"],
                [
                    ["local-total", "queries", 700 * mib, 800 * mib],
                    ["reference-total", "queries", 350 * mib, 400 * mib],
                ],
            )

            outputs = MODULE.generate_resource_summary_figures(
                size_run, variant_run, real_run, size_memory_run,
            )

            self.assertEqual(2, len(outputs))
            storage = outputs[0].read_text(encoding="utf-8")
            self.assertIn("Trwały rozmiar danych", storage)
            self.assertIn("logarytmiczna oś ilorazu", storage)
            self.assertIn(">1 tys.</text>", storage)
            self.assertIn(">1.0</text>", storage)
            self.assertIn(">4.0</text>", storage)
            memory = outputs[1].read_text(encoding="utf-8")
            self.assertIn("Pamięć kontenerowa", memory)
            self.assertIn("Rozmiar — mediana", memory)
            self.assertIn("Logi rzeczywiste — maksimum", memory)
            self.assertIn(">700.0</text>", memory)
            self.assertIn(">350.0</text>", memory)

    @staticmethod
    def write_csv(path: Path, headers: list[str], data: list[list[object]]) -> None:
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(headers)
            writer.writerows(data)


if __name__ == "__main__":
    unittest.main()
