#!/usr/bin/env python3
"""End-to-end contract tests for compare-runs.py (standard library only)."""

from __future__ import annotations

from pathlib import Path
import csv
import json
import runpy
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("compare-runs.py")
RENDER_SCRIPT = Path(__file__).with_name("render-report-html.py")
COMPARE_FUNCTIONS = runpy.run_path(str(SCRIPT))
RANDOM_SEED = 20260728
MIB = 1024 * 1024

DATASETS = [
    ("trace-100", "trace-scaling", 100, 10, 5),
    ("trace-500", "trace-scaling", 500, 10, 5),
    ("trace-2000", "trace-scaling", 2000, 10, 5),
    ("event-10", "event-scaling", 100, 10, 5),
    ("attr-5", "attribute-scaling", 100, 10, 5),
]


def write_csv(path: Path, fieldnames: list[str], rows: list[dict[str, object]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def ordered_datasets(order: str) -> list[tuple[str, str, int, int, int]]:
    if order == "reversed":
        return list(reversed(DATASETS))
    if order == "random":
        # The production runner owns the Kotlin shuffle. The aggregator additionally
        # proves that a block labelled random is neither declared nor reversed.
        return [DATASETS[index] for index in (2, 0, 4, 1, 3)]
    return DATASETS


def make_run(root: Path, name: str, order: str, seed: int = RANDOM_SEED) -> Path:
    run = root / name
    run.mkdir()
    datasets = ordered_datasets(order)
    dataset_rows = [
        {
            "datasetName": dataset,
            "series": series,
            "traces": traces,
            "eventsPerTrace": events,
            "totalEvents": traces * events,
            "attributesPerEvent": attributes,
            "totalAttributes": traces * events * attributes,
            "xesBytes": traces * events * (100 + attributes),
            "xesGzBytes": traces * events * 10,
        }
        for dataset, series, traces, events, attributes in datasets
    ]
    write_csv(run / "datasets.csv", list(dataset_rows[0]), dataset_rows)
    write_csv(
        run / "queries.csv",
        ["queryLabel", "workload", "scalingSeries", "clause", "pql"],
        [{
            "queryLabel": "scalingQuery",
            "workload": "dataDependent",
            "scalingSeries": "trace-scaling",
            "clause": "test skalowania",
            "pql": "select count(^^e:name)",
        }],
    )

    base_ms = {"trace-100": 10.0, "trace-500": 20.0, "trace-2000": 40.0,
               "event-10": 10.0, "attr-5": 10.0}
    query_rows: list[dict[str, object]] = []
    import_rows: list[dict[str, object]] = []
    for dataset, _series, _traces, _events, _attributes in datasets:
        for system, factor in (("local", 1.0), ("reference", 2.0)):
            import_rows.append({
                "system": system, "datasetName": dataset, "run": 1,
                "seconds": 0.2 * factor, "status": "OK",
                "dataStoreId": f"{name}-{dataset}-{system}", "logCount": 1, "details": "",
            })
            query_rows.append({
                "system": system, "datasetName": dataset, "queryLabel": "scalingQuery",
                "run": 0, "phase": "cold", "seconds": base_ms[dataset] * factor / 1000,
                "status": "OK", "responseBytes": 100, "logCount": 1,
                "traceCount": 1, "eventCount": 1, "details": "",
            })
            for repetition in range(1, 31):
                query_rows.append({
                    "system": system, "datasetName": dataset, "queryLabel": "scalingQuery",
                    "run": repetition, "phase": "warm", "seconds": base_ms[dataset] * factor / 1000,
                    "status": "OK", "responseBytes": 100, "logCount": 1,
                    "traceCount": 1, "eventCount": 1, "details": "",
                })
    write_csv(
        run / "import-results.csv",
        ["system", "datasetName", "run", "seconds", "status", "dataStoreId", "logCount", "details"],
        import_rows,
    )
    write_csv(
        run / "query-results.csv",
        ["system", "datasetName", "queryLabel", "run", "phase", "seconds", "status",
         "responseBytes", "logCount", "traceCount", "eventCount", "details"],
        query_rows,
    )
    write_csv(run / "query-summary.csv", ["system", "datasetName", "queryLabel"], [])
    write_csv(run / "storage-results.csv", ["system", "datasetName", "status"], [])
    write_csv(
        run / "roundtrip-results.csv",
        ["datasetName", "status", "differencesCount", "detailsPath"],
        [{"datasetName": dataset[0], "status": "MATCH", "differencesCount": 0, "detailsPath": ""}
         for dataset in datasets],
    )

    memory_rows = [
        {"timestamp": f"2026-01-01T00:00:0{phase_index}Z", "phase": phase,
         "component": component, "bytes": value}
        for phase_index, phase in enumerate(("idle", "queries"))
        for component, value in (("processm-interpreter", 100 * MIB),
                                 ("processm-neo4j", 200 * MIB),
                                 ("processm-server", 250 * MIB))
    ]
    write_csv(run / "memory-results.csv", ["timestamp", "phase", "component", "bytes"], memory_rows)
    summary_rows = [
        {"component": component, "phase": "queries", "medianBytes": value, "peakBytes": value}
        for component, value in (("processm-interpreter", 100 * MIB),
                                 ("processm-neo4j", 200 * MIB),
                                 ("processm-server", 250 * MIB),
                                 ("local-total", 300 * MIB),
                                 ("reference-total", 250 * MIB))
    ]
    write_csv(run / "memory-summary.csv", ["component", "phase", "medianBytes", "peakBytes"], summary_rows)

    cleanup_rows = [
        {"system": system, "dataStoreName": f"bench-{dataset}-{system}",
         "dataStoreId": f"{dataset}-{system}", "status": "DELETED", "details": ""}
        for dataset, *_rest in datasets for system in ("local", "reference")
    ] + [
        {"system": system, "dataStoreName": f"bench-warmup-{system}",
         "dataStoreId": f"warmup-{system}", "status": "DELETED", "details": ""}
        for system in ("local", "reference")
    ] + [
        {"system": system, "dataStoreName": f"bench-post-idle-{system}",
         "dataStoreId": f"post-idle-{system}", "status": "DELETED", "details": ""}
        for system in ("local", "reference")
    ]
    write_csv(
        run / "cleanup-results.csv",
        ["system", "dataStoreName", "dataStoreId", "status", "details"],
        cleanup_rows,
    )

    commit = "a" * 40
    containers = {
        component: {
            "imageId": f"sha256:{index:064x}",
            "memoryLimitBytes": 1_000_000_000 if component != "processm-server" else 2_000_000_000,
            "memorySwapLimitBytes": 1_000_000_000 if component != "processm-server" else 2_000_000_000,
            "nanoCpus": 2_000_000_000, "memoryConfigEnv": "fixed", "effectiveJvmHeap": "fixed",
            "running": True, "oomKilled": False, "restartCount": 0,
        }
        for index, component in enumerate(
            ("processm-interpreter", "processm-neo4j", "processm-server"), start=1,
        )
    }
    environment = {
        "benchmarkProtocolVersion": 10,
        "profile": "full", "warmups": 3, "repetitions": 30, "globalWarmupRounds": 200,
        "postIdleWarmupRounds": 200,
        "postIdleWarmupMode": "fresh-import-query-delete",
        "datasetOrder": order, "datasetOrderSeed": seed,
        "datasetFilter": [], "systemFilter": [], "keepBenchmarkDataStores": False,
        "localApi": "http://localhost:8080/api", "referenceApi": "http://localhost:80/api",
        "experiment": {
            "fingerprintSha256": "f" * 64, "datasetCount": len(datasets),
            "queryCount": 1, "replicateValidityGate": 1.25,
            "replicateValidityStatistic": "query-spread-q3",
        },
        "source": {"gitCommit": commit, "gitDirty": False},
        "host": {"processors": 8, "memoryBytes": 16_000_000_000},
        "dockerEngine": {"serverVersion": "test", "totalMemoryBytes": 8_000_000_000},
        "javaVersion": "25", "osName": "test", "osVersion": "1",
        "containers": containers,
    }
    (run / "environment.json").write_text(json.dumps(environment), encoding="utf-8")
    (run / "stack-preparation.json").write_text(json.dumps({
        "schemaVersion": 2, "preparationId": name, "preparedAtUtc": "2026-01-01T00:00:00Z",
        "freshVolumes": True, "localDatastoreCount": 0, "referenceDatastoreCount": 0,
        "gitCommit": commit,
        "imageIds": {component: data["imageId"] for component, data in containers.items()},
    }), encoding="utf-8")
    (run / "thesis-report.md").write_text(
        "# Raport bloku\n\n"
        "Niniejszy raport zawiera wyłącznie wyniki jednego przebiegu benchmarku.\n\n"
        "- **Q3 (dysk).** Kompletna izolowana sonda storage została dołączona.\n",
        encoding="utf-8",
    )
    for filename in ("summary.md", "environment.md", "thesis-tables.tex"):
        (run / filename).write_text("generated\n", encoding="utf-8")
    return run


def set_memory_totals(
    run: Path,
    local_mib: float,
    reference_mib: float,
    local_peak_mib: float,
    reference_peak_mib: float,
) -> None:
    path = run / "memory-summary.csv"
    with path.open(encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    for row in rows:
        if row["component"] == "local-total":
            row["medianBytes"] = str(int(local_mib * MIB))
            row["peakBytes"] = str(int(local_peak_mib * MIB))
        elif row["component"] == "reference-total":
            row["medianBytes"] = str(int(reference_mib * MIB))
            row["peakBytes"] = str(int(reference_peak_mib * MIB))
    write_csv(path, list(rows[0]), rows)


class CompareRunsClassificationTest(unittest.TestCase):
    def test_direction_verdict_uses_the_smallest_paired_effect(self) -> None:
        classify = COMPARE_FUNCTIONS["classify_ratios"]
        self.assertEqual(("LOCAL", 1.5, "SUPPORTED"), classify([2.0, 1.5, 1.8], 1.25))
        self.assertEqual(
            ("REFERENCE", 1.25, "BELOW_MEASUREMENT_ERROR"),
            classify([0.5, 0.8, 0.6], 1.30),
        )
        self.assertEqual(("UNRESOLVED", 1.0, "DIRECTION_CHANGES"), classify([0.9, 1.2], 1.1))

    def test_magnitude_instability_is_diagnostic_not_a_direction_verdict(self) -> None:
        diagnose = COMPARE_FUNCTIONS["magnitude_diagnostic"]
        spread, max_iqr, stability = diagnose([1.75, 30.40, 29.68], [1.1, 18.0, 1.2])
        self.assertAlmostEqual(30.40 / 1.75, spread)
        self.assertEqual(18.0, max_iqr)
        self.assertEqual("UNSTABLE_MAGNITUDE", stability)
        classify = COMPARE_FUNCTIONS["classify_ratios"]
        self.assertEqual(("LOCAL", 1.75, "SUPPORTED"), classify([1.75, 30.40, 29.68], 1.21))


class CompareRunsEndToEndTest(unittest.TestCase):
    def test_valid_series_writes_polish_final_report(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runs = [make_run(root, f"run-{order}", order) for order in ("declared", "reversed", "random")]
            output = root / "combined"
            result = subprocess.run(
                [sys.executable, str(SCRIPT), *(str(run) for run in runs), "--out-dir", str(output)],
                text=True, capture_output=True, check=False,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            report = (output / "thesis-report-series.md").read_text(encoding="utf-8")
            self.assertTrue(report.startswith("# Analiza serii kontrbalansowanych przebiegów"))
            self.assertIn("powtarzalny ponad błędem", report)
            self.assertIn("stabilny wzrost ponad błędem", report)
            self.assertIn("Efekt konserwatywny", report)
            self.assertIn("eksploracyjne skalowanie poza predeklarowanym kontraktem", report)
            self.assertIn("# Szczegóły diagnostyczne bloku kotwiczącego", report)
            self.assertIn("Poniższa część zawiera wyłącznie wyniki przebiegu kotwiczącego.", report)
            self.assertNotIn("Niniejszy raport zawiera wyłącznie wyniki jednego przebiegu benchmarku.", report)
            self.assertTrue((output / "series-scaling.csv").is_file())
            self.assertTrue((output / "series-scaling-exploratory.csv").is_file())
            self.assertTrue((output / "series-cell-stability.csv").is_file())
            self.assertTrue((output / "report-provenance.json").is_file())
            self.assertTrue((output / "thesis-tables-series.tex").is_file())

    def test_memory_direction_below_cross_run_spread_remains_unresolved(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runs = [make_run(root, f"run-{order}", order) for order in ("declared", "reversed", "random")]
            for run, values in zip(
                runs,
                ((2497.0, 2415.0, 2900.0, 2450.0),
                 (2415.0, 2250.0, 2850.0, 2440.0),
                 (2402.0, 1765.0, 2800.0, 2430.0)),
            ):
                set_memory_totals(run, *values)
            output = root / "combined"
            result = subprocess.run(
                [sys.executable, str(SCRIPT), *(str(run) for run in runs), "--out-dir", str(output)],
                text=True, capture_output=True, check=False,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            report = (output / "thesis-report-series.md").read_text(encoding="utf-8")
            self.assertIn("Q3-pamięć: nierozstrzygnięte — kierunek poniżej błędu pomiaru", report)
            self.assertIn("Nie są estymacją minimalnej wymaganej pamięci", report)

    def test_final_renderer_rejects_series_without_isolated_storage_probe(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runs = [make_run(root, f"run-{order}", order) for order in ("declared", "reversed", "random")]
            anchor = runs[1]
            compare = subprocess.run(
                [sys.executable, str(SCRIPT), *(str(run) for run in runs), "--out-dir", str(anchor)],
                text=True, capture_output=True, check=False,
            )
            self.assertEqual(0, compare.returncode, compare.stdout + compare.stderr)
            render = subprocess.run(
                [sys.executable, str(RENDER_SCRIPT), str(anchor)],
                text=True, capture_output=True, check=False,
            )
            self.assertNotEqual(0, render.returncode)
            self.assertIn("storage-scaling.csv", render.stdout + render.stderr)

    def test_final_renderer_rejects_invalid_generator_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runs = [make_run(root, f"run-{order}", order) for order in ("declared", "reversed", "random")]
            anchor = runs[1]
            compare = subprocess.run(
                [sys.executable, str(SCRIPT), *(str(run) for run in runs), "--out-dir", str(anchor)],
                text=True, capture_output=True, check=False,
            )
            self.assertEqual(0, compare.returncode, compare.stdout + compare.stderr)
            write_csv(
                anchor / "storage-scaling.csv",
                ["measurementMode"],
                [{"measurementMode": "isolated-fresh-stack"}],
            )
            provenance_path = anchor / "report-provenance.json"
            provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
            provenance["generatorGitCommit"] = None
            provenance_path.write_text(json.dumps(provenance), encoding="utf-8")
            render = subprocess.run(
                [sys.executable, str(RENDER_SCRIPT), str(anchor)],
                text=True, capture_output=True, check=False,
            )
            self.assertNotEqual(0, render.returncode)
            self.assertIn("generatorGitCommit", render.stdout + render.stderr)

    def test_random_block_with_unregistered_seed_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runs = [
                make_run(root, "run-declared", "declared"),
                make_run(root, "run-reversed", "reversed"),
                make_run(root, "run-random", "random", seed=7),
            ]
            result = subprocess.run(
                [sys.executable, str(SCRIPT), *(str(run) for run in runs), "--out-dir", str(root / "out")],
                text=True, capture_output=True, check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("preregistered seed 20260728", result.stdout + result.stderr)

    def test_series_rejects_different_post_idle_warmup_contracts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runs = [make_run(root, f"run-{order}", order) for order in ("declared", "reversed", "random")]
            environment_path = runs[-1] / "environment.json"
            environment = json.loads(environment_path.read_text(encoding="utf-8"))
            environment["postIdleWarmupRounds"] = 199
            environment_path.write_text(json.dumps(environment), encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(SCRIPT), *(str(run) for run in runs), "--out-dir", str(root / "out")],
                text=True, capture_output=True, check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("wymagane co najmniej 200", result.stdout + result.stderr)

    def test_series_rejects_oom_killed_reference_even_when_container_is_running(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runs = [make_run(root, f"run-{order}", order) for order in ("declared", "reversed", "random")]
            environment_path = runs[0] / "environment.json"
            environment = json.loads(environment_path.read_text(encoding="utf-8"))
            reference = environment["containers"]["processm-server"]
            reference["running"] = True
            reference["oomKilled"] = True
            reference["effectiveJvmHeap"] = "unavailable"
            environment_path.write_text(json.dumps(environment), encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(SCRIPT), *(str(run) for run in runs), "--out-dir", str(root / "out")],
                text=True, capture_output=True, check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("kontener processm-server odnotował OOM kill", result.stdout + result.stderr)
            self.assertIn("brak procesu JVM", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
