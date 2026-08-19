#!/usr/bin/env python3
"""Validate and combine a counterbalanced series of thesis benchmark runs.

The unit of replication is a complete run, not one of the 30 autocorrelated HTTP
requests inside it.  This tool therefore validates every run and the series as a
whole, reports run-to-run spread, and estimates each effect from the paired LOCAL /
REFERENCE median ratio in every run.  A system advantage is supported only when its
direction is identical in all runs and its smallest magnitude clears the replicate
error measured inside those runs.

Writes repeatability.csv/md, the confirmatory and descriptive series CSVs,
report-provenance.json, thesis-tables-series.tex and a combined
thesis-report-series.md into the median-metric anchor run (or --out-dir).
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import median
from typing import Any
import argparse
import csv
import hashlib
import json
import math
import re
import subprocess
import sys

MS = 1000.0
MIB = 1024.0 * 1024.0
VALIDITY_GATE_DEFAULT = 1.25
SERIES_RANDOM_SEED = 20260728
MIN_BENCHMARK_PROTOCOL_VERSION = 10
MIN_GLOBAL_WARMUP_ROUNDS = 200
MIN_POST_IDLE_WARMUP_ROUNDS = 200
POST_IDLE_WARMUP_MODE = "fresh-import-query-delete"
REPLICATE_VALIDITY_STATISTIC = "query-spread-q3"
IMPORT_REPLICATE_LABEL = "IMPORT (Q1)"
MAGNITUDE_ALERT_FACTOR = 3.0
REQUIRED_MEMORY_COMPONENTS = {"processm-interpreter", "processm-neo4j", "processm-server"}
REQUIRED_FILES = {
    "summary.md", "datasets.csv", "queries.csv", "import-results.csv",
    "query-results.csv", "query-summary.csv", "storage-results.csv",
    "roundtrip-results.csv", "memory-results.csv", "memory-summary.csv",
    "environment.json", "environment.md", "cleanup-results.csv",
    "thesis-report.md", "thesis-tables.tex", "stack-preparation.json",
}
SCALING_AXES = {
    "trace-scaling": ("liczba śladów", "traces"),
    "event-scaling": ("łączna liczba zdarzeń", "totalEvents"),
    "attribute-scaling": ("atrybuty/zdarzenie", "attributesPerEvent"),
}


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def read_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def first_seen(values: list[str]) -> list[str]:
    """Stable unique order used to prove the recorded execution sequence."""
    return list(dict.fromkeys(values))


def as_float(value: Any) -> float | None:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return parsed if math.isfinite(parsed) else None


def type7_quantile(values: list[float], probability: float) -> float:
    """Hyndman--Fan type 7, matching ThesisStatistics and query-summary.csv."""
    ordered = sorted(value for value in values if math.isfinite(value))
    if not ordered:
        return math.nan
    position = (len(ordered) - 1) * probability
    lower = math.floor(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (position - lower) * (ordered[upper] - ordered[lower])


def query_medians(run: Path) -> dict[tuple[str, str, str], float]:
    grouped: dict[tuple[str, str, str], list[float]] = {}
    for row in read_csv(run / "query-results.csv"):
        if row.get("phase") != "warm" or row.get("status") != "OK":
            continue
        value = as_float(row.get("seconds"))
        if value is None or value <= 0:
            continue
        key = (row.get("datasetName", ""), row.get("queryLabel", ""), row.get("system", ""))
        grouped.setdefault(key, []).append(value * MS)
    return {key: type7_quantile(values, 0.50) for key, values in grouped.items()}


def query_payload_medians(run: Path) -> dict[tuple[str, str, str], float]:
    """Median serialized response bytes per warm cell, including mismatches.

    Mismatching bodies remain unsuitable for latency comparison, but their size is
    still an important black-box transport/serialization diagnostic.
    """
    grouped: dict[tuple[str, str, str], list[float]] = {}
    for row in read_csv(run / "query-results.csv"):
        if row.get("phase") != "warm" or row.get("status") not in {"OK", "MISMATCH"}:
            continue
        value = as_float(row.get("responseBytes"))
        if value is None or value < 0:
            continue
        key = (row.get("datasetName", ""), row.get("queryLabel", ""), row.get("system", ""))
        grouped.setdefault(key, []).append(value)
    return {key: type7_quantile(values, 0.50) for key, values in grouped.items()}


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compatibility_summary(path: Path | None) -> dict[str, Any] | None:
    """Read the small, stable counter block from a compatibility report."""
    if path is None:
        return None
    report_dir = path if path.is_dir() else path.parent
    summary_path = report_dir / "summary.md"
    results_path = report_dir / "results.json"
    if not summary_path.is_file() or not results_path.is_file():
        sys.exit("error: --compatibility-report needs summary.md and results.json")
    text = summary_path.read_text(encoding="utf-8")

    def count(label: str) -> int:
        match = re.search(rf"^- {re.escape(label)}: (\d+)$", text, re.M)
        if not match:
            sys.exit(f"error: compatibility summary is missing '{label}'")
        return int(match.group(1))

    return {
        "path": str(report_dir.resolve()),
        "checks": count("Checks"),
        "matches": count("Matches"),
        "accepted": count("Accepted compatibility checks"),
        "strictProblems": count("Strict problems"),
        "informationalMismatches": count("Informational mismatches"),
        "summarySha256": sha256_file(summary_path),
        "resultsSha256": sha256_file(results_path),
    }


def query_iqr_metrics(run: Path) -> dict[tuple[str, str, str], tuple[float, float, float]]:
    """Warm-sample Q1, Q3 and Q3/Q1 for a descriptive regime-shift diagnostic."""
    grouped: dict[tuple[str, str, str], list[float]] = {}
    for row in read_csv(run / "query-results.csv"):
        if row.get("phase") != "warm" or row.get("status") != "OK":
            continue
        value = as_float(row.get("seconds"))
        if value is None or value <= 0:
            continue
        key = (row.get("datasetName", ""), row.get("queryLabel", ""), row.get("system", ""))
        grouped.setdefault(key, []).append(value * MS)
    out: dict[tuple[str, str, str], tuple[float, float, float]] = {}
    for key, values in grouped.items():
        q1 = type7_quantile(values, 0.25)
        q3 = type7_quantile(values, 0.75)
        if q1 > 0:
            out[key] = (q1, q3, q3 / q1)
    return out


def import_times(run: Path) -> dict[tuple[str, str], float]:
    out: dict[tuple[str, str], float] = {}
    for row in read_csv(run / "import-results.csv"):
        value = as_float(row.get("seconds"))
        if row.get("status") == "OK" and value is not None and value > 0:
            out[(row.get("datasetName", ""), row.get("system", ""))] = value
    return out


def memory_metrics(run: Path) -> tuple[dict[str, float], dict[str, float]]:
    medians: dict[str, float] = {}
    peaks: dict[str, float] = {}
    for row in read_csv(run / "memory-summary.csv"):
        if row.get("phase") != "queries":
            continue
        component = row.get("component", "")
        median_value = as_float(row.get("medianBytes"))
        peak_value = as_float(row.get("peakBytes"))
        if median_value is not None:
            medians[component] = median_value / MIB
        if peak_value is not None:
            peaks[component] = peak_value / MIB
    return medians, peaks


def replicate_spreads(
    datasets: list[dict[str, str]],
    medians: dict[tuple[str, str, str], float],
    imports: dict[tuple[str, str], float],
) -> tuple[float | None, float | None, dict[str, float], list[str]]:
    shapes: dict[tuple[str, str, str], list[str]] = {}
    for row in datasets:
        if row.get("series") == "real-validation":
            continue
        key = (row.get("traces", ""), row.get("eventsPerTrace", ""), row.get("attributesPerEvent", ""))
        shapes.setdefault(key, []).append(row.get("datasetName", ""))
    groups = [sorted(names) for names in shapes.values() if len(names) > 1]
    issues: list[str] = []
    if not groups:
        return None, None, {}, ["brak grupy replikacyjnej"]

    labels = sorted({query for _, query, _ in medians})
    systems = sorted({system for _, _, system in medians})
    per_label: dict[str, float] = {}
    query_spreads: list[float] = []
    for group in groups:
        for label in labels:
            for system in systems:
                values = [medians.get((dataset, label, system)) for dataset in group]
                present = [value for value in values if value is not None and value > 0]
                if present and len(present) != len(group):
                    issues.append(f"niepełne replikaty {group}/{label}/{system}")
                if len(present) > 1:
                    spread = max(present) / min(present)
                    query_spreads.append(spread)
                    per_label[label] = max(per_label.get(label, 1.0), spread)
        for system in ("local", "reference"):
            values = [imports.get((dataset, system)) for dataset in group]
            present = [value for value in values if value is not None and value > 0]
            if present and len(present) != len(group):
                issues.append(f"niepełne replikaty importu {group}/{system}")
            if len(present) > 1:
                spread = max(present) / min(present)
                per_label[IMPORT_REPLICATE_LABEL] = max(
                    per_label.get(IMPORT_REPLICATE_LABEL, 1.0), spread,
                )
    return (
        type7_quantile(query_spreads, 0.75) if query_spreads else None,
        max(query_spreads) if query_spreads else None,
        per_label,
        issues,
    )


def nested(environment: dict[str, Any], *keys: str) -> Any:
    value: Any = environment
    for key in keys:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value


def classify_ratios(ratios: list[float], floor: float) -> tuple[str, float, str]:
    """Conservative cross-run verdict for ratios expressed as REFERENCE/LOCAL."""
    if min(ratios) > 1.0:
        direction = "LOCAL"
        conservative_magnitude = min(ratios)
    elif max(ratios) < 1.0:
        direction = "REFERENCE"
        conservative_magnitude = 1.0 / max(ratios)
    else:
        direction = "UNRESOLVED"
        conservative_magnitude = 1.0
    verdict = (
        "SUPPORTED" if direction != "UNRESOLVED" and conservative_magnitude >= floor
        else "BELOW_MEASUREMENT_ERROR" if direction != "UNRESOLVED"
        else "DIRECTION_CHANGES"
    )
    return direction, conservative_magnitude, verdict


def magnitude_diagnostic(
    ratios: list[float],
    within_cell_iqr_factors: list[float],
) -> tuple[float, float, str]:
    """Describe effect-size instability without changing the paired direction verdict.

    A threefold alert is intentionally coarse and descriptive. It exposes large
    regime changes, but is neither a preregistered significance threshold nor a
    diagnosis of their implementation-level cause.
    """
    ratio_spread = max(ratios) / min(ratios)
    max_cell_iqr = max(within_cell_iqr_factors, default=1.0)
    if min(ratios) <= 1.0 <= max(ratios):
        stability = "NOT_APPLICABLE_DIRECTION_CHANGES"
    elif max(ratio_spread, max_cell_iqr) >= MAGNITUDE_ALERT_FACTOR:
        stability = "UNSTABLE_MAGNITUDE"
    else:
        stability = "NO_LARGE_VARIATION"
    return ratio_spread, max_cell_iqr, stability


def replicate_group_names(datasets: list[dict[str, str]]) -> list[list[str]]:
    """Synthetic datasets that describe an identical experiment under several names."""
    shapes: dict[tuple[str, str, str], list[str]] = {}
    for row in datasets:
        if row.get("series") == "real-validation":
            continue
        key = (
            row.get("traces", ""),
            row.get("eventsPerTrace", ""),
            row.get("attributesPerEvent", ""),
        )
        shapes.setdefault(key, []).append(row.get("datasetName", ""))
    return [sorted(names) for names in shapes.values() if len(names) > 1]


def distinct_experiment_count(
    datasets: list[dict[str, str]],
    comparison_rows: list[dict[str, Any]],
) -> tuple[int, list[list[str]]]:
    """Pair count after collapsing each replicate group to one representative.

    `trace-100`, `event-10` and `attr-5` are the same 100x10x5 experiment under three
    names — the point where the scaling series intersect. Counting all three inflates
    the headline pair count, because the extra rows are re-measurements of one
    condition rather than additional conditions. The full count stays the basis of the
    verdicts (the replicates are legitimate repeated measurements); this number says
    how many *distinct* experiments they cover.
    """
    groups = replicate_group_names(datasets)
    duplicates = {name for group in groups for name in group[1:]}
    distinct = sum(1 for row in comparison_rows if row.get("datasetName") not in duplicates)
    return distinct, groups


def degenerate_response_queries(anchor_path: Path) -> dict[str, tuple[str, int]]:
    """Queries whose warm response counts never vary, with how many pairs they cover.

    A per-repetition count check compares two constants for these, and an all-empty
    response makes the strict XES-JSON comparison compare `[]` with `[]`. Both are
    legitimate latency workloads — a predicate that matches nothing still costs a full
    scan — but the Q4 parity claim must not present them as if they exercised the
    semantic check.
    """
    counts: dict[str, set[tuple[str, str, str]]] = defaultdict(set)
    datasets_per_query: dict[str, set[str]] = defaultdict(set)
    for row in read_csv(anchor_path / "query-results.csv"):
        if row.get("phase") != "warm" or row.get("status") != "OK":
            continue
        label = row.get("queryLabel", "")
        counts[label].add((row.get("logCount", ""), row.get("traceCount", ""), row.get("eventCount", "")))
        datasets_per_query[label].add(row.get("datasetName", ""))
    degenerate: dict[str, tuple[str, int]] = {}
    for label, observed in counts.items():
        if len(observed) != 1:
            continue
        only = next(iter(observed))
        kind = "pusta odpowiedź po obu stronach" if only == ("0", "0", "0") else f"stała liczność {'/'.join(only)}"
        degenerate[label] = (kind, len(datasets_per_query[label]))
    return degenerate


def scaling_series(query: dict[str, str]) -> set[str]:
    return {value.strip() for value in query.get("scalingSeries", "").split(";") if value.strip()}


def power_law_fit(points: list[tuple[float, float]]) -> tuple[float, float] | None:
    """OLS fit of log10(time) = intercept + alpha*log10(axis)."""
    valid = [(math.log10(x), math.log10(y)) for x, y in points if x > 0 and y > 0]
    if len(valid) < 3:
        return None
    xs = [point[0] for point in valid]
    ys = [point[1] for point in valid]
    mean_x, mean_y = sum(xs) / len(xs), sum(ys) / len(ys)
    sxx = sum((value - mean_x) ** 2 for value in xs)
    if sxx <= 0:
        return None
    slope = sum((x - mean_x) * (y - mean_y) for x, y in valid) / sxx
    intercept = mean_y - slope * mean_x
    total = sum((y - mean_y) ** 2 for y in ys)
    residual = sum((y - (intercept + slope * x)) ** 2 for x, y in valid)
    r2 = 1.0 - residual / total if total > 0 else math.nan
    return slope, r2


def scaling_verdict(
    fits: list[tuple[float, float]],
    endpoint_factors: list[float],
    practical_floor: float,
) -> str:
    """Conservative stability label across complete-run fits."""
    if not fits or any(not math.isfinite(r2) or r2 < 0.30 for _slope, r2 in fits):
        return "LOW_FIT"
    slopes = [slope for slope, _r2 in fits]
    if all(slope > 0.0 for slope in slopes):
        return "STABLE_INCREASE" if min(endpoint_factors) >= practical_floor else "BELOW_MEASUREMENT_ERROR"
    if all(slope < 0.0 for slope in slopes):
        return (
            "STABLE_DECREASE_ARTIFACT"
            if min(endpoint_factors) >= practical_floor
            else "BELOW_MEASUREMENT_ERROR"
        )
    # Mixed signs are indistinguishable from flat only if no complete block's
    # endpoint change clears the measured error floor. Otherwise the shape is
    # genuinely unstable across the experimental blocks.
    if max(endpoint_factors, default=1.0) < practical_floor:
        return "NO_GROWTH_DETECTED"
    return "UNSTABLE"


VERDICT_PL = {
    "SUPPORTED": "powtarzalny ponad błędem",
    "BELOW_MEASUREMENT_ERROR": "poniżej błędu pomiaru",
    "DIRECTION_CHANGES": "zmienny kierunek",
    "UNSTABLE_MEASUREMENT": "niestabilny pomiar",
    "LOW_FIT": "niska jakość dopasowania",
    "STABLE_INCREASE": "stabilny wzrost ponad błędem",
    "NO_GROWTH_DETECTED": "nie wykryto wzrostu",
    "STABLE_DECREASE_ARTIFACT": "stabilny spadek — artefakt diagnostyczny",
    "UNSTABLE": "niestabilny między blokami",
}

MAGNITUDE_STABILITY_PL = {
    "UNSTABLE_MAGNITUDE": "niestabilna — duża zmiana reżimu",
    "NO_LARGE_VARIATION": "bez alarmu ×3",
    "NOT_APPLICABLE_DIRECTION_CHANGES": "nie dotyczy — zmienny kierunek",
}


def verdict_pl(code: str) -> str:
    """Human-facing Polish label; CSVs keep stable machine-readable codes."""
    return VERDICT_PL.get(code, code)


def direction_pl(code: str) -> str:
    return "nierozstrzygnięty" if code == "UNRESOLVED" else code


def magnitude_stability_pl(code: str) -> str:
    return MAGNITUDE_STABILITY_PL.get(code, code)


def conservative_effect_text(row: dict[str, Any], prefix: str = "×", unresolved: str = "—") -> str:
    if row.get("direction") == "UNRESOLVED":
        return unresolved
    return f"{prefix}{float(row['conservativeMagnitude']):.2f}"


def generator_provenance() -> dict[str, Any]:
    """Identify the report generator independently from the measured-code commit."""
    script = Path(__file__).resolve()
    repo = script.parents[2]
    digest = hashlib.sha256(script.read_bytes()).hexdigest()
    commit: str | None = None
    dirty: bool | None = None
    try:
        commit_result = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "HEAD"],
            text=True, capture_output=True, check=False,
        )
        if commit_result.returncode == 0:
            commit = commit_result.stdout.strip() or None
        status_result = subprocess.run(
            ["git", "-C", str(repo), "status", "--porcelain", "--untracked-files=no"],
            text=True, capture_output=True, check=False,
        )
        if status_result.returncode == 0:
            dirty = bool(status_result.stdout.strip())
    except OSError:
        pass
    return {
        "schemaVersion": 1,
        "generatorGitCommit": commit,
        "generatorGitDirty": dirty,
        "compareRunsPath": "scripts/benchmarks/compare-runs.py",
        "compareRunsSha256": digest,
    }


@dataclass
class RunData:
    name: str
    path: Path
    environment: dict[str, Any]
    datasets: list[dict[str, str]]
    queries: dict[str, dict[str, str]]
    medians: dict[tuple[str, str, str], float]
    query_iqr: dict[tuple[str, str, str], tuple[float, float, float]]
    imports: dict[tuple[str, str], float]
    memory: dict[str, float]
    memory_peaks: dict[str, float]
    replicate_worst: float | None
    replicate_q3: float | None
    replicate_floor: dict[str, float]
    mismatch_pairs: set[tuple[str, str]]
    total_query_pairs: int
    issues: list[str]

    @property
    def metric(self) -> float | None:
        values = [value for (_, _, system), value in self.medians.items() if system == "local"]
        return median(values) if values else None

    @property
    def signature(self) -> dict[str, Any]:
        containers = self.environment.get("containers", {})
        image_ids = {
            name: data.get("imageId")
            for name, data in containers.items()
            if isinstance(data, dict)
        } if isinstance(containers, dict) else {}
        container_resources = {
            name: {
                key: data.get(key)
                for key in (
                    "memoryLimitBytes", "memorySwapLimitBytes", "nanoCpus",
                    "memoryConfigEnv", "effectiveJvmHeap",
                )
            }
            for name, data in containers.items()
            if isinstance(data, dict)
        } if isinstance(containers, dict) else {}
        return {
            "benchmarkProtocolVersion": self.environment.get("benchmarkProtocolVersion"),
            "profile": self.environment.get("profile"),
            "warmups": self.environment.get("warmups"),
            "repetitions": self.environment.get("repetitions"),
            "globalWarmupRounds": self.environment.get("globalWarmupRounds"),
            "postIdleWarmupRounds": self.environment.get("postIdleWarmupRounds"),
            "postIdleWarmupMode": self.environment.get("postIdleWarmupMode"),
            "replicateValidityGate": nested(self.environment, "experiment", "replicateValidityGate"),
            "replicateValidityStatistic": nested(self.environment, "experiment", "replicateValidityStatistic"),
            "fingerprint": nested(self.environment, "experiment", "fingerprintSha256"),
            "gitCommit": nested(self.environment, "source", "gitCommit"),
            "host": self.environment.get("host"),
            "dockerEngine": self.environment.get("dockerEngine"),
            "javaVersion": self.environment.get("javaVersion"),
            "osName": self.environment.get("osName"),
            "osVersion": self.environment.get("osVersion"),
            "imageIds": image_ids,
            "containerResources": container_resources,
        }


def load_run(path: Path) -> RunData:
    issues: list[str] = []
    missing_files = sorted(name for name in REQUIRED_FILES if not (path / name).is_file())
    if missing_files:
        issues.append("brak artefaktów: " + ", ".join(missing_files))

    environment = read_json(path / "environment.json")
    stack_preparation = read_json(path / "stack-preparation.json")
    datasets = read_csv(path / "datasets.csv")
    queries = read_csv(path / "queries.csv")
    queries_by_label = {row.get("queryLabel", ""): row for row in queries if row.get("queryLabel")}
    medians = query_medians(path)
    imports = import_times(path)
    query_iqr = query_iqr_metrics(path)
    memory, memory_peaks = memory_metrics(path)
    replicate_q3, worst, floors, replicate_issues = replicate_spreads(datasets, medians, imports)
    issues.extend(replicate_issues)

    if environment.get("profile") != "full":
        issues.append(f"profil {environment.get('profile')!r}, wymagany 'full'")
    protocol_version = environment.get("benchmarkProtocolVersion")
    if not isinstance(protocol_version, int) or protocol_version < MIN_BENCHMARK_PROTOCOL_VERSION:
        issues.append(
            f"wersja protokołu {protocol_version!r}, "
            f"wymagana co najmniej {MIN_BENCHMARK_PROTOCOL_VERSION}"
        )
    if int(environment.get("globalWarmupRounds") or 0) < MIN_GLOBAL_WARMUP_ROUNDS:
        issues.append(
            f"globalna rozgrzewka ma {environment.get('globalWarmupRounds')!r} rund, "
            f"wymagane co najmniej {MIN_GLOBAL_WARMUP_ROUNDS}"
        )
    if int(environment.get("postIdleWarmupRounds") or 0) < MIN_POST_IDLE_WARMUP_ROUNDS:
        issues.append(
            f"rozgrzewka po bezczynności ma {environment.get('postIdleWarmupRounds')!r} rund, "
            f"wymagane co najmniej {MIN_POST_IDLE_WARMUP_ROUNDS}"
        )
    if environment.get("postIdleWarmupMode") != POST_IDLE_WARMUP_MODE:
        issues.append(f"tryb rozgrzewki po bezczynności inny niż {POST_IDLE_WARMUP_MODE}")
    if nested(environment, "experiment", "replicateValidityStatistic") != REPLICATE_VALIDITY_STATISTIC:
        issues.append(f"statystyka bramki replikacyjnej inna niż {REPLICATE_VALIDITY_STATISTIC}")
    if environment.get("datasetFilter") not in ([], None):
        issues.append("aktywny filtr datasetów")
    if environment.get("systemFilter") not in ([], None):
        issues.append("aktywny filtr systemów")
    if environment.get("keepBenchmarkDataStores") is not False:
        issues.append("datastore'y benchmarku nie były skonfigurowane do usunięcia")
    if nested(environment, "source", "gitDirty") is not False:
        issues.append("brak potwierdzenia czystego drzewa Git")
    environment_containers = environment.get("containers", {})
    environment_image_ids = {
        component: data.get("imageId")
        for component, data in environment_containers.items()
        if component in REQUIRED_MEMORY_COMPONENTS and isinstance(data, dict)
    } if isinstance(environment_containers, dict) else {}
    if (
        stack_preparation.get("schemaVersion") != 2
        or not stack_preparation.get("preparationId")
        or not stack_preparation.get("preparedAtUtc")
        or stack_preparation.get("freshVolumes") is not True
        or stack_preparation.get("localDatastoreCount") != 0
        or stack_preparation.get("referenceDatastoreCount") != 0
    ):
        issues.append("brak dowodu świeżych wolumenów i pustych API")
    if stack_preparation.get("gitCommit") != nested(environment, "source", "gitCommit"):
        issues.append("marker świeżego stacku pochodzi z innego commita")
    if stack_preparation.get("imageIds") != environment_image_ids:
        issues.append("image ID markera przygotowania nie zgadzają się ze środowiskiem przebiegu")
    for key, label in (
        (("source", "gitCommit"), "commit Git"),
        (("experiment", "fingerprintSha256"), "fingerprint workloadu"),
        (("dockerEngine", "totalMemoryBytes"), "budżet pamięci Docker VM"),
    ):
        if not nested(environment, *key):
            issues.append(f"brak: {label}")

    containers = environment.get("containers", {})
    for component in REQUIRED_MEMORY_COMPONENTS:
        data = containers.get(component) if isinstance(containers, dict) else None
        if not isinstance(data, dict) or not data.get("imageId"):
            issues.append(f"brak dokładnego imageId kontenera {component}")
            continue
        if data.get("running") is not True:
            issues.append(f"kontener {component} nie działał na końcu przebiegu")
        if data.get("oomKilled") is not False:
            issues.append(f"kontener {component} odnotował OOM kill")
        if data.get("restartCount") != 0:
            issues.append(f"kontener {component} miał restartCount={data.get('restartCount')!r}")
        if data.get("effectiveJvmHeap") in (None, "unavailable"):
            issues.append(f"brak procesu JVM na końcu przebiegu w {component}")
        memory_limit = data.get("memoryLimitBytes")
        memory_swap = data.get("memorySwapLimitBytes")
        if not isinstance(memory_limit, int) or memory_limit <= 0:
            issues.append(f"kontener {component} nie ma skończonego limitu pamięci")
        elif memory_swap != memory_limit:
            issues.append(f"kontener {component} nie ma wyłączonego swapu w budżecie benchmarku")

    if isinstance(containers, dict):
        local_app = nested(containers, "processm-interpreter", "memoryLimitBytes")
        local_db = nested(containers, "processm-neo4j", "memoryLimitBytes")
        reference = nested(containers, "processm-server", "memoryLimitBytes")
        if all(isinstance(value, int) for value in (local_app, local_db, reference)):
            if local_app + local_db != reference:
                issues.append(
                    f"asymetryczny budżet pamięci: LOCAL={local_app + local_db} B, "
                    f"REFERENCE={reference} B"
                )

    memory_rows = read_csv(path / "memory-results.csv")
    components = {row.get("component", "") for row in memory_rows}
    missing_memory = REQUIRED_MEMORY_COMPONENTS - components
    if missing_memory:
        issues.append("brak składników pamięci: " + ", ".join(sorted(missing_memory)))
    if "local-jvm" in components:
        issues.append("LOCAL mierzony niesymetryczną sondą local-jvm")
    if not {"local-total", "reference-total"}.issubset(memory):
        issues.append("brak median sumowanych per timestamp (local-total/reference-total)")
    for phase in ("idle", "queries"):
        timestamps = {
            component: {
                row.get("timestamp", "") for row in memory_rows
                if row.get("phase") == phase and row.get("component") == component
            }
            for component in REQUIRED_MEMORY_COMPONENTS
        }
        counts = {component: len(values) for component, values in timestamps.items()}
        if min(counts.values(), default=0) == 0:
            issues.append(f"brak próbek pamięci fazy {phase}: {counts}")
        elif len({frozenset(values) for values in timestamps.values()}) != 1:
            issues.append(f"niekompletne wspólne timestampy pamięci fazy {phase}: {counts}")

    dataset_names = {row.get("datasetName", "") for row in datasets}
    query_labels = {row.get("queryLabel", "") for row in queries}
    dataset_order = [row.get("datasetName", "") for row in datasets]
    if len(dataset_order) != len(dataset_names) or "" in dataset_names:
        issues.append("datasets.csv zawiera puste lub zduplikowane nazwy")
    query_order = [row.get("queryLabel", "") for row in queries]
    if len(query_order) != len(query_labels) or "" in query_labels:
        issues.append("queries.csv zawiera puste lub zduplikowane etykiety")
    recorded_dataset_count = nested(environment, "experiment", "datasetCount")
    recorded_query_count = nested(environment, "experiment", "queryCount")
    if recorded_dataset_count != len(dataset_names):
        issues.append(f"liczba datasetów nie zgadza się z fingerprintem: {len(dataset_names)}/{recorded_dataset_count}")
    if recorded_query_count != len(query_labels):
        issues.append(f"liczba zapytań nie zgadza się z fingerprintem: {len(query_labels)}/{recorded_query_count}")
    unknown_scaling_series = sorted({
        series
        for query in queries
        for series in scaling_series(query)
        if series not in SCALING_AXES and series != "shape-scaling"
    })
    if unknown_scaling_series:
        issues.append("nieznane serie skalowania w queries.csv: " + ", ".join(unknown_scaling_series))
    raw_imports = read_csv(path / "import-results.csv")
    if first_seen([row.get("datasetName", "") for row in raw_imports]) != dataset_order:
        issues.append("kolejność importów nie zgadza się z datasets.csv")
    expected_imports = {(dataset, system) for dataset in dataset_names for system in ("local", "reference")}
    if set(imports) != expected_imports:
        issues.append(f"niepełne importy: {len(imports)}/{len(expected_imports)} udanych")
    import_counts: dict[tuple[str, str], int] = {}
    for row in raw_imports:
        key = (row.get("datasetName", ""), row.get("system", ""))
        import_counts[key] = import_counts.get(key, 0) + 1
    malformed_imports = {key: count for key, count in import_counts.items() if count != 1}
    if malformed_imports:
        issues.append(f"zduplikowane lub nadmiarowe próby importu: {malformed_imports}")

    raw_queries = read_csv(path / "query-results.csv")
    if first_seen([row.get("datasetName", "") for row in raw_queries]) != dataset_order:
        issues.append("kolejność pomiarów zapytań nie zgadza się z datasets.csv")
    if any(row.get("status") == "ERROR" for row in raw_queries):
        issues.append("query-results.csv zawiera ERROR")
    mismatch_pairs = {
        (row.get("datasetName", ""), row.get("queryLabel", ""))
        for row in raw_queries if row.get("status") == "MISMATCH"
    }
    dataset_series = {row.get("datasetName", ""): row.get("series", "") for row in datasets}
    known_hoisted_labels = {
        row.get("queryLabel", "")
        for row in queries
        if "group by" in row.get("pql", "").lower()
        and "^" in row.get("pql", "").lower().split("group by", 1)[1]
    }
    mismatch_details = {
        pair: {
            row.get("details", "") for row in raw_queries
            if row.get("status") == "MISMATCH"
            and (row.get("datasetName", ""), row.get("queryLabel", "")) == pair
        }
        for pair in mismatch_pairs
    }
    unexpected_mismatches = {
        pair for pair in mismatch_pairs
        if dataset_series.get(pair[0]) != "real-validation"
        or pair[1] not in known_hoisted_labels
        or any(not detail.startswith("Response count mismatch:") for detail in mismatch_details[pair])
    }
    if unexpected_mismatches:
        issues.append("nieoczekiwane MISMATCH Q4: " + ", ".join(f"{d}/{q}" for d, q in sorted(unexpected_mismatches)))
    expected_cells = {
        (dataset, query, system)
        for dataset in dataset_names for query in query_labels for system in ("local", "reference")
    }
    actual_cells = {
        (row.get("datasetName", ""), row.get("queryLabel", ""), row.get("system", ""))
        for row in raw_queries
    }
    if query_labels and actual_cells != expected_cells:
        issues.append(f"niepełna macierz zapytań: {len(actual_cells)}/{len(expected_cells)} komórek")
    repetitions = int(environment.get("repetitions") or 0)
    for cell in actual_cells:
        rows = [row for row in raw_queries if (row.get("datasetName"), row.get("queryLabel"), row.get("system")) == cell]
        warm_rows = [row for row in rows if row.get("phase") == "warm" and row.get("run", "").isdigit()]
        warm_runs = [int(row["run"]) for row in warm_rows]
        if sorted(warm_runs) != list(range(1, repetitions + 1)):
            issues.append(f"niepełne lub zduplikowane repetycje {cell}: {len(warm_runs)}/{repetitions}")
            break
        cold_rows = [row for row in rows if row.get("phase") == "cold"]
        if len(cold_rows) != 1:
            issues.append(f"liczba próbek cold {cell}: {len(cold_rows)}/1")
            break

    roundtrips = read_csv(path / "roundtrip-results.csv")
    roundtrip_names = {row.get("datasetName", "") for row in roundtrips if row.get("status") == "MATCH"}
    if roundtrip_names != dataset_names:
        issues.append(f"roundtrip MATCH dla {len(roundtrip_names)}/{len(dataset_names)} datasetów")
    roundtrip_counts: dict[str, int] = {}
    for row in roundtrips:
        name = row.get("datasetName", "")
        roundtrip_counts[name] = roundtrip_counts.get(name, 0) + 1
    if set(roundtrip_counts) != dataset_names or any(count != 1 for count in roundtrip_counts.values()):
        issues.append("roundtrip-results.csv nie zawiera dokładnie jednego wiersza na dataset")
    cleanup = read_csv(path / "cleanup-results.csv")
    expected_cleanup = (
        2 * len(dataset_names)
        + (2 if int(environment.get("globalWarmupRounds") or 0) > 0 else 0)
        + (2 if int(environment.get("postIdleWarmupRounds") or 0) > 0 else 0)
    )
    if len(cleanup) != expected_cleanup or any(row.get("status") != "DELETED" for row in cleanup):
        issues.append(f"sprzątanie datastore'ów niepotwierdzone: {len(cleanup)}/{expected_cleanup} wpisów DELETED")

    gate = as_float(nested(environment, "experiment", "replicateValidityGate")) or VALIDITY_GATE_DEFAULT
    if replicate_q3 is None:
        issues.append("nie można policzyć bramki replikatów Q2")
    elif replicate_q3 > gate:
        issues.append(f"górny kwartyl rozrzutów replikatów Q2 ×{replicate_q3:.3f} przekracza ×{gate:.3f}")

    return RunData(
        path.name, path, environment, datasets, queries_by_label, medians, query_iqr, imports,
        memory, memory_peaks, worst, replicate_q3, floors, mismatch_pairs,
        len(dataset_names) * len(query_labels), issues,
    )


def write_csv(
    path: Path,
    rows: list[dict[str, Any]],
    fieldnames: list[str] | None = None,
) -> None:
    if not rows and fieldnames is None:
        return
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames or list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def latex_escape(value: Any) -> str:
    text = str(value)
    replacements = {
        "\\": r"\textbackslash{}", "&": r"\&", "%": r"\%", "$": r"\$",
        "#": r"\#", "_": r"\_", "{": r"\{", "}": r"\}",
        "~": r"\textasciitilde{}", "^": r"\textasciicircum{}",
    }
    return "".join(replacements.get(char, char) for char in text)


def system_memory_budget_mib(run: RunData, system: str) -> float | None:
    containers = run.environment.get("containers", {})
    if not isinstance(containers, dict):
        return None
    names = (
        ("processm-interpreter", "processm-neo4j")
        if system == "local"
        else ("processm-server",)
    )
    values = [nested(containers, name, "memoryLimitBytes") for name in names]
    if not all(isinstance(value, int) and value > 0 for value in values):
        return None
    return sum(values) / MIB


def memory_series_analysis(runs: list[RunData]) -> dict[str, Any]:
    """Conservative Q3-memory verdict from complete-block system totals."""
    local = [run.memory["local-total"] for run in runs]
    reference = [run.memory["reference-total"] for run in runs]
    local_spread = max(local) / min(local)
    reference_spread = max(reference) / min(reference)
    floor = max(local_spread, reference_spread)
    ratios_reference_to_local = [ref / loc for loc, ref in zip(local, reference)]
    direction, magnitude, verdict = classify_ratios(ratios_reference_to_local, floor)
    return {
        "local": local,
        "reference": reference,
        "differences": [loc - ref for loc, ref in zip(local, reference)],
        "localSpread": local_spread,
        "referenceSpread": reference_spread,
        "practicalFloor": floor,
        "direction": direction,
        "conservativeMagnitude": magnitude,
        "verdict": verdict,
    }


def write_series_latex(
    path: Path,
    runs: list[RunData],
    comparison_rows: list[dict[str, Any]],
    import_rows: list[dict[str, Any]],
    import_comparison_rows: list[dict[str, Any]],
    scaling_rows: list[dict[str, Any]],
    exploratory_scaling_rows: list[dict[str, Any]],
    memory_analysis: dict[str, Any],
    provenance: dict[str, Any],
) -> None:
    """Write the cross-run tables that do not exist in a single-run .tex file."""
    lines = [
        "% generated by scripts/benchmarks/compare-runs.py",
        f"% generator git commit: {provenance.get('generatorGitCommit')}",
        f"% generator git dirty: {provenance.get('generatorGitDirty')}",
        f"% compare-runs.py SHA-256: {provenance.get('compareRunsSha256')}",
        "% requires booktabs and longtable",
        r"\begingroup\small\setlength{\tabcolsep}{3pt}",
        r"\begin{longtable}{p{1.5cm}p{2.2cm}rlrlrp{1.6cm}p{2.2cm}}",
        r"\caption{Q2: efekty w serii pełnych przebiegów}\label{tab:bench-series-q2}\\",
        r"\toprule",
        r"Dataset & Zapytanie & Efekt kons. & Kierunek & Próg & Werdykt & Med. R/L & Zakres R/L & Stabilność wielkości \\",
        r"\midrule",
        r"\endfirsthead",
        r"\toprule",
        r"Dataset & Zapytanie & Efekt kons. & Kierunek & Próg & Werdykt & Med. R/L & Zakres R/L & Stabilność wielkości \\",
        r"\midrule",
        r"\endhead",
    ]
    for row in comparison_rows:
        lines.append(
            f"{latex_escape(row['datasetName'])} & {latex_escape(row['queryLabel'])} & "
            f"{conservative_effect_text(row, prefix='', unresolved='--')} & "
            f"{latex_escape(direction_pl(str(row['direction'])))} & "
            f"{float(row['practicalFloor']):.2f} & {latex_escape(verdict_pl(str(row['verdict'])))} & "
            f"{float(row['medianRatioReferenceToLocal']):.2f} & "
            f"[{float(row['minRatio']):.2f}; {float(row['maxRatio']):.2f}] & "
            f"{latex_escape(magnitude_stability_pl(str(row['magnitudeStability'])))} \\\\"
        )
    lines += [r"\bottomrule", r"\end{longtable}", r"\endgroup", ""]
    if scaling_rows:
        lines += [
            r"\begin{longtable}{lllrrrrrrl}",
            r"\caption{Q2: stabilność wykładników skalowania między blokami}\label{tab:bench-series-scaling}\\",
            r"\toprule",
            r"Zapytanie & Oś & System & $\alpha$ med. & $\alpha$ min & $\alpha$ max & min $R^2$ & min efekt & próg & Werdykt \\",
            r"\midrule", r"\endfirsthead", r"\toprule",
            r"Zapytanie & Oś & System & $\alpha$ med. & $\alpha$ min & $\alpha$ max & min $R^2$ & min efekt & próg & Werdykt \\",
            r"\midrule", r"\endhead",
        ]
        for row in scaling_rows:
            lines.append(
                f"{latex_escape(row['queryLabel'])} & {latex_escape(row['axis'])} & "
                f"{latex_escape(row['system'])} & {float(row['medianAlpha']):+.2f} & "
                f"{float(row['minAlpha']):+.2f} & {float(row['maxAlpha']):+.2f} & "
                f"{float(row['minR2']):.2f} & {float(row['minEndpointFactor']):.2f} & "
                f"{float(row['practicalFloor']):.2f} & "
                f"{latex_escape(verdict_pl(str(row['verdict'])))} \\\\"
            )
        lines += [r"\bottomrule", r"\end{longtable}", ""]
    if exploratory_scaling_rows:
        lines += [
            r"\begingroup\small\setlength{\tabcolsep}{3pt}",
            r"\begin{longtable}{p{2.5cm}lrrrrrrl}",
            r"\caption{Q2: eksploracyjne skalowanie po liczbie śladów poza kontraktem predeklarowanym}\label{tab:bench-series-scaling-exploratory}\\",
            r"\toprule",
            r"Zapytanie & System & Start [ms] & Koniec [ms] & K/S med. & $\alpha$ min & $\alpha$ med. & $\alpha$ max & Dodatnie bloki \\",
            r"\midrule", r"\endfirsthead", r"\toprule",
            r"Zapytanie & System & Start [ms] & Koniec [ms] & K/S med. & $\alpha$ min & $\alpha$ med. & $\alpha$ max & Dodatnie bloki \\",
            r"\midrule", r"\endhead",
        ]
        for row in exploratory_scaling_rows:
            lines.append(
                f"{latex_escape(row['queryLabel'])} & {latex_escape(row['system'])} & "
                f"{float(row['medianStartMs']):.2f} & {float(row['medianEndMs']):.2f} & "
                f"{float(row['medianEndToStartRatio']):.2f} & {float(row['minAlpha']):+.2f} & "
                f"{float(row['medianAlpha']):+.2f} & {float(row['maxAlpha']):+.2f} & "
                f"{row['positiveSlopeRuns']}/{row['runs']} \\\\"
            )
        lines += [r"\bottomrule", r"\end{longtable}", r"\endgroup", ""]
    lines += [r"\begingroup\small\setlength{\tabcolsep}{3pt}",
              r"\begin{longtable}{p{1.8cm}rlrlrrrp{1.6cm}}",
              r"\caption{Q1: czasy importu i efekty między przebiegami}\label{tab:bench-series-q1}\\",
              r"\toprule",
              r"Dataset & Efekt kons. & Kierunek & Próg & Werdykt & L med. [s] & R med. [s] & R/L med. & Zakres R/L \\",
              r"\midrule", r"\endfirsthead", r"\toprule",
              r"Dataset & Efekt kons. & Kierunek & Próg & Werdykt & L med. [s] & R med. [s] & R/L med. & Zakres R/L \\",
              r"\midrule", r"\endhead"]
    imports_by_key = {(row["datasetName"], row["system"]): row for row in import_rows}
    for comparison in import_comparison_rows:
        local = imports_by_key[(comparison["datasetName"], "local")]
        reference = imports_by_key[(comparison["datasetName"], "reference")]
        lines.append(
            f"{latex_escape(comparison['datasetName'])} & "
            f"{conservative_effect_text(comparison, prefix='', unresolved='--')} & "
            f"{latex_escape(direction_pl(str(comparison['direction'])))} & "
            f"{float(comparison['practicalFloor']):.2f} & "
            f"{latex_escape(verdict_pl(str(comparison['verdict'])))} & "
            f"{float(local['medianSeconds']):.2f} & {float(reference['medianSeconds']):.2f} & "
            f"{float(comparison['medianRatioReferenceToLocal']):.2f} & "
            f"[{float(comparison['minRatio']):.2f}; {float(comparison['maxRatio']):.2f}] \\\\"
        )
    lines += [r"\bottomrule", r"\end{longtable}", r"\endgroup", "",
              r"\begin{table}[htbp]", r"\centering",
              r"\caption{Q3: sumy pamięci w fazie zapytań}", r"\label{tab:bench-series-memory}",
              r"\begin{tabular}{lrrr}", r"\toprule",
              r"Przebieg & LOCAL [MiB] & REFERENCE [MiB] & LOCAL--REFERENCE [MiB] \\", r"\midrule"]
    for run in runs:
        local = run.memory["local-total"]
        reference = run.memory["reference-total"]
        lines.append(f"{latex_escape(run.name)} & {local:.0f} & {reference:.0f} & {local - reference:+.0f} \\\\"
        )
    lines += [
        r"\bottomrule", r"\end{tabular}", r"\par\smallskip",
        "Werdykt Q3-pamięć: "
        + latex_escape(verdict_pl(str(memory_analysis["verdict"])))
        + f"; efekt konserwatywny {float(memory_analysis['conservativeMagnitude']):.2f}, "
        + f"próg międzyblokowy {float(memory_analysis['practicalFloor']):.2f}.",
        r"\end{table}", "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("run_dirs", type=Path, nargs="+", help="at least three FULL run directories")
    parser.add_argument("--out-dir", type=Path, default=None)
    parser.add_argument(
        "--compatibility-report",
        type=Path,
        default=None,
        help="directory containing the broad compatibility summary.md and results.json",
    )
    args = parser.parse_args()
    if len(args.run_dirs) < 3:
        sys.exit("error: methodology requires at least three complete runs")

    runs = [load_run(path) for path in args.run_dirs]
    print("Ważność przebiegów:")
    invalid = False
    for run in runs:
        if run.issues:
            invalid = True
            print(f"  {run.name}: NIEWAŻNY")
            for issue in run.issues:
                print(f"    - {issue}")
        else:
            import_spread = run.replicate_floor.get(IMPORT_REPLICATE_LABEL, math.nan)
            print(
                f"  {run.name}: Q2 ważne; replikaty Q2 Q3 ×{run.replicate_q3:.3f}, "
                f"max ×{run.replicate_worst:.3f}; "
                f"Q1 ×{import_spread:.3f}; metryka {run.metric:.2f} ms",
            )
    if invalid:
        sys.exit("error: invalid runs cannot be combined into thesis evidence")

    reference_signature = runs[0].signature
    for run in runs[1:]:
        if run.signature != reference_signature:
            print(f"signature {runs[0].name}: {json.dumps(reference_signature, sort_keys=True)}")
            print(f"signature {run.name}: {json.dumps(run.signature, sort_keys=True)}")
            sys.exit("error: runs differ in code, workload, images, Docker budget, or profile")
        if set(run.medians) != set(runs[0].medians):
            sys.exit(f"error: {run.name} has a different comparable query key set")
        if set(run.imports) != set(runs[0].imports):
            sys.exit(f"error: {run.name} has a different import key set")
        if run.mismatch_pairs != runs[0].mismatch_pairs:
            sys.exit(f"error: {run.name} has a different Q4 mismatch set")

    orders = {str(run.environment.get("datasetOrder")) for run in runs}
    required_orders = {"declared", "reversed", "random"}
    if not required_orders.issubset(orders):
        sys.exit(f"error: counterbalanced series needs {sorted(required_orders)}, got {sorted(orders)}")
    random_runs = [run for run in runs if run.environment.get("datasetOrder") == "random"]
    if any(run.environment.get("datasetOrderSeed") != SERIES_RANDOM_SEED for run in random_runs):
        observed = {run.name: run.environment.get("datasetOrderSeed") for run in random_runs}
        sys.exit(
            f"error: random counterbalancing block must use preregistered seed "
            f"{SERIES_RANDOM_SEED}, got {observed}"
        )
    declared_run = next(run for run in runs if run.environment.get("datasetOrder") == "declared")
    declared_names = [row.get("datasetName", "") for row in declared_run.datasets]
    for run in runs:
        names = [row.get("datasetName", "") for row in run.datasets]
        order = run.environment.get("datasetOrder")
        if set(names) != set(declared_names):
            sys.exit(f"error: {run.name} contains a different dataset set")
        if order == "declared" and names != declared_names:
            sys.exit(f"error: multiple declared blocks disagree on dataset order ({run.name})")
        if order == "reversed" and names != list(reversed(declared_names)):
            sys.exit(f"error: {run.name} is labelled reversed but its manifest is not reversed")
        if order == "random" and names in (declared_names, list(reversed(declared_names))):
            sys.exit(f"error: {run.name} is labelled random but repeats a deterministic order")

    by_metric = sorted(runs, key=lambda run: (run.metric or math.inf, run.name))
    anchor = by_metric[(len(by_metric) - 1) // 2]
    out_dir = args.out_dir or anchor.path
    out_dir.mkdir(parents=True, exist_ok=True)

    floor_by_label = {
        label: max(run.replicate_floor.get(label, 1.0) for run in runs)
        for _, label, _ in runs[0].medians
    }
    repeatability_rows: list[dict[str, Any]] = []
    for key in sorted(runs[0].medians):
        values = [run.medians[key] for run in runs]
        spread = max(values) / min(values)
        repeatability_rows.append({
            "datasetName": key[0], "queryLabel": key[1], "system": key[2],
            "runs": len(values), "minMs": f"{min(values):.3f}", "maxMs": f"{max(values):.3f}",
            "medianMs": f"{median(values):.3f}", "spreadRatio": f"{spread:.3f}",
            "practicalFloor": f"{floor_by_label.get(key[1], 1.0):.3f}",
        })

    cell_stability_rows: list[dict[str, Any]] = []
    for run in runs:
        for (dataset, query, system), (q1, q3, factor) in sorted(run.query_iqr.items()):
            cell_stability_rows.append({
                "run": run.name,
                "datasetName": dataset,
                "queryLabel": query,
                "system": system,
                "q1Ms": f"{q1:.6f}",
                "q3Ms": f"{q3:.6f}",
                "iqrFactor": f"{factor:.6f}",
                "largeRegimeAlert": str(factor >= MAGNITUDE_ALERT_FACTOR).lower(),
            })

    pair_keys = sorted({(dataset, query) for dataset, query, _ in runs[0].medians})
    comparison_rows: list[dict[str, Any]] = []
    for dataset, query in pair_keys:
        ratios = [
            run.medians[(dataset, query, "reference")] / run.medians[(dataset, query, "local")]
            for run in runs
        ]
        floor = floor_by_label.get(query, 1.0)
        direction, conservative_magnitude, verdict = classify_ratios(ratios, floor)
        within_cell_iqr_factors = [
            run.query_iqr[(dataset, query, system)][2]
            for run in runs
            for system in ("local", "reference")
        ]
        ratio_magnitude_spread, max_cell_iqr, magnitude_stability = magnitude_diagnostic(
            ratios, within_cell_iqr_factors,
        )
        row: dict[str, Any] = {
            "datasetName": dataset,
            "queryLabel": query,
        }
        for run, ratio in zip(runs, ratios):
            row[f"ratioReferenceToLocal_{run.name}"] = f"{ratio:.6f}"
        row.update({
            "medianRatioReferenceToLocal": f"{median(ratios):.6f}",
            "minRatio": f"{min(ratios):.6f}",
            "maxRatio": f"{max(ratios):.6f}",
            "direction": direction,
            "conservativeMagnitude": f"{conservative_magnitude:.6f}",
            "practicalFloor": f"{floor:.6f}",
            "verdict": verdict,
            "ratioMagnitudeSpread": f"{ratio_magnitude_spread:.6f}",
            "maxWithinCellIqrFactor": f"{max_cell_iqr:.6f}",
            "magnitudeStability": magnitude_stability,
        })
        comparison_rows.append(row)

    import_rows: list[dict[str, Any]] = []
    for dataset, system in sorted(runs[0].imports):
        values = [run.imports[(dataset, system)] for run in runs]
        import_rows.append({
            "datasetName": dataset, "system": system, "runs": len(values),
            "minSeconds": f"{min(values):.6f}", "medianSeconds": f"{median(values):.6f}",
            "maxSeconds": f"{max(values):.6f}", "spreadRatio": f"{max(values) / min(values):.6f}",
        })

    import_floor = max(
        run.replicate_floor.get(IMPORT_REPLICATE_LABEL, 1.0)
        for run in runs
    )
    import_gate = as_float(reference_signature.get("replicateValidityGate")) or VALIDITY_GATE_DEFAULT
    import_quality_ok = import_floor <= import_gate
    import_comparison_rows: list[dict[str, Any]] = []
    for dataset in sorted({dataset for dataset, _ in runs[0].imports}):
        ratios = [
            run.imports[(dataset, "reference")] / run.imports[(dataset, "local")]
            for run in runs
        ]
        direction, conservative_magnitude, verdict = classify_ratios(ratios, import_floor)
        if not import_quality_ok:
            verdict = "UNSTABLE_MEASUREMENT"
        import_comparison_rows.append({
            "datasetName": dataset,
            "medianRatioReferenceToLocal": f"{median(ratios):.6f}",
            "minRatio": f"{min(ratios):.6f}",
            "maxRatio": f"{max(ratios):.6f}",
            "direction": direction,
            "conservativeMagnitude": f"{conservative_magnitude:.6f}",
            "practicalFloor": f"{import_floor:.6f}",
            "verdict": verdict,
        })

    scaling_rows: list[dict[str, Any]] = []
    for query_label, query_spec in sorted(runs[0].queries.items()):
        for series in sorted(scaling_series(query_spec) & set(SCALING_AXES)):
            axis_label, axis_field = SCALING_AXES[series]
            for system in ("local", "reference"):
                fits: list[tuple[float, float]] = []
                endpoint_factors: list[float] = []
                for run in runs:
                    points: list[tuple[float, float]] = []
                    for dataset in run.datasets:
                        if dataset.get("series") != series:
                            continue
                        axis_value = as_float(dataset.get(axis_field))
                        elapsed = run.medians.get((dataset.get("datasetName", ""), query_label, system))
                        if axis_value is not None and elapsed is not None:
                            points.append((axis_value, elapsed))
                    fit = power_law_fit(points)
                    if fit is not None:
                        fits.append(fit)
                        x_values = [point[0] for point in points]
                        endpoint_factors.append((max(x_values) / min(x_values)) ** abs(fit[0]))
                if len(fits) != len(runs):
                    sys.exit(f"error: cannot fit scaling for {query_label}/{series}/{system} in every run")
                slopes = [fit[0] for fit in fits]
                r2_values = [fit[1] for fit in fits]
                practical_floor = floor_by_label.get(query_label, 1.0)
                scaling_rows.append({
                    "queryLabel": query_label,
                    "series": series,
                    "axis": axis_label,
                    "system": system,
                    "runs": len(fits),
                    "minAlpha": f"{min(slopes):.6f}",
                    "medianAlpha": f"{median(slopes):.6f}",
                    "maxAlpha": f"{max(slopes):.6f}",
                    "minR2": f"{min(r2_values):.6f}",
                    "maxR2": f"{max(r2_values):.6f}",
                    "minEndpointFactor": f"{min(endpoint_factors):.6f}",
                    "practicalFloor": f"{practical_floor:.6f}",
                    "verdict": scaling_verdict(fits, endpoint_factors, practical_floor),
                })

    exploratory_scaling_rows: list[dict[str, Any]] = []
    exploratory_series = "trace-scaling"
    exploratory_axis_label, exploratory_axis_field = SCALING_AXES[exploratory_series]
    for query_label, query_spec in sorted(runs[0].queries.items()):
        if exploratory_series in scaling_series(query_spec):
            continue
        for system in ("local", "reference"):
            fits: list[tuple[float, float]] = []
            starts: list[float] = []
            ends: list[float] = []
            endpoint_ratios: list[float] = []
            for run in runs:
                points = sorted(
                    (
                        axis_value,
                        run.medians[(dataset.get("datasetName", ""), query_label, system)],
                    )
                    for dataset in run.datasets
                    if dataset.get("series") == exploratory_series
                    for axis_value in [as_float(dataset.get(exploratory_axis_field))]
                    if axis_value is not None
                    and (dataset.get("datasetName", ""), query_label, system) in run.medians
                )
                fit = power_law_fit(points)
                if fit is None or len(points) < 2:
                    sys.exit(
                        f"error: cannot fit exploratory scaling for "
                        f"{query_label}/{exploratory_series}/{system} in every run"
                    )
                fits.append(fit)
                starts.append(points[0][1])
                ends.append(points[-1][1])
                endpoint_ratios.append(points[-1][1] / points[0][1])
            slopes = [fit[0] for fit in fits]
            r2_values = [fit[1] for fit in fits]
            exploratory_scaling_rows.append({
                "queryLabel": query_label,
                "series": exploratory_series,
                "axis": exploratory_axis_label,
                "system": system,
                "runs": len(runs),
                "medianStartMs": f"{median(starts):.6f}",
                "medianEndMs": f"{median(ends):.6f}",
                "medianEndToStartRatio": f"{median(endpoint_ratios):.6f}",
                "minAlpha": f"{min(slopes):.6f}",
                "medianAlpha": f"{median(slopes):.6f}",
                "maxAlpha": f"{max(slopes):.6f}",
                "minR2": f"{min(r2_values):.6f}",
                "maxR2": f"{max(r2_values):.6f}",
                "positiveSlopeRuns": sum(slope > 0 for slope in slopes),
                "status": "EXPLORATORY_NOT_PREREGISTERED",
            })

    query_outcome_rows: list[dict[str, Any]] = []
    for query_label in sorted({str(row["queryLabel"]) for row in comparison_rows}):
        rows = [row for row in comparison_rows if row["queryLabel"] == query_label]
        query_outcome_rows.append({
            "queryLabel": query_label,
            "comparablePairs": len(rows),
            "supportedLocal": sum(row["verdict"] == "SUPPORTED" and row["direction"] == "LOCAL" for row in rows),
            "supportedReference": sum(row["verdict"] == "SUPPORTED" and row["direction"] == "REFERENCE" for row in rows),
            "belowMeasurementError": sum(row["verdict"] == "BELOW_MEASUREMENT_ERROR" for row in rows),
            "directionChanges": sum(row["verdict"] == "DIRECTION_CHANGES" for row in rows),
            "magnitudeAlerts": sum(row["magnitudeStability"] == "UNSTABLE_MAGNITUDE" for row in rows),
        })

    dataset_series = {row.get("datasetName", ""): row.get("series", "") for row in anchor.datasets}
    real_dataset_outcome_rows: list[dict[str, Any]] = []
    for dataset_name in sorted(name for name, series in dataset_series.items() if series == "real-validation"):
        rows = [row for row in comparison_rows if row["datasetName"] == dataset_name]
        real_dataset_outcome_rows.append({
            "datasetName": dataset_name,
            "comparablePairs": len(rows),
            "supportedLocal": sum(row["verdict"] == "SUPPORTED" and row["direction"] == "LOCAL" for row in rows),
            "supportedReference": sum(row["verdict"] == "SUPPORTED" and row["direction"] == "REFERENCE" for row in rows),
            "belowMeasurementError": sum(row["verdict"] == "BELOW_MEASUREMENT_ERROR" for row in rows),
            "directionChanges": sum(row["verdict"] == "DIRECTION_CHANGES" for row in rows),
            "mismatchPairs": sum((dataset_name, query) in anchor.mismatch_pairs for query in anchor.queries),
        })

    payloads_by_run = [query_payload_medians(run.path) for run in runs]
    payload_pair_keys = sorted({(dataset, query) for dataset, query, _ in payloads_by_run[0]})
    payload_rows: list[dict[str, Any]] = []
    for dataset, query in payload_pair_keys:
        local_values = [payloads[(dataset, query, "local")] for payloads in payloads_by_run]
        reference_values = [payloads[(dataset, query, "reference")] for payloads in payloads_by_run]
        local_bytes = median(local_values)
        reference_bytes = median(reference_values)
        smaller = min(local_bytes, reference_bytes)
        factor = max(local_bytes, reference_bytes) / smaller if smaller > 0 else math.inf
        larger = "equal" if local_bytes == reference_bytes else ("local" if local_bytes > reference_bytes else "reference")
        payload_rows.append({
            "datasetName": dataset,
            "queryLabel": query,
            "pairStatus": "MISMATCH" if (dataset, query) in anchor.mismatch_pairs else "COMPARABLE",
            "medianLocalBytes": f"{local_bytes:.0f}",
            "medianReferenceBytes": f"{reference_bytes:.0f}",
            "largerPayloadSystem": larger,
            "largerToSmallerFactor": "inf" if not math.isfinite(factor) else f"{factor:.6f}",
        })

    memory_rows: list[dict[str, Any]] = []
    for run in runs:
        memory_rows.append({
            "run": run.name,
            "localMedianMiB": f"{run.memory['local-total']:.6f}",
            "referenceMedianMiB": f"{run.memory['reference-total']:.6f}",
            "localPeakMiB": f"{run.memory_peaks['local-total']:.6f}",
            "referencePeakMiB": f"{run.memory_peaks['reference-total']:.6f}",
        })

    provenance = generator_provenance()
    provenance["measurementGitCommit"] = reference_signature["gitCommit"]
    compatibility = compatibility_summary(args.compatibility_report)
    provenance["compatibilityReport"] = compatibility
    evidence_path = out_dir / "hoisted-group-evidence.json"
    evidence = read_json(evidence_path)
    if evidence:
        provenance["hoistedGroupEvidenceSha256"] = sha256_file(evidence_path)
    memory_analysis = memory_series_analysis(runs)

    for artifact in (
        "repeatability.csv", "repeatability.md", "series-comparison.csv",
        "series-query-outcomes.csv", "series-real-dataset-outcomes.csv",
        "series-payload.csv", "series-memory.csv",
        "series-cell-stability.csv", "series-import.csv", "series-import-comparison.csv",
        "series-scaling.csv", "series-scaling-exploratory.csv", "report-provenance.json",
        "thesis-tables-series.tex", "thesis-report-series.md",
    ):
        (out_dir / artifact).unlink(missing_ok=True)

    write_csv(out_dir / "repeatability.csv", repeatability_rows)
    write_csv(out_dir / "series-comparison.csv", comparison_rows)
    write_csv(out_dir / "series-query-outcomes.csv", query_outcome_rows)
    write_csv(
        out_dir / "series-real-dataset-outcomes.csv",
        real_dataset_outcome_rows,
        fieldnames=[
            "datasetName", "comparablePairs", "supportedLocal", "supportedReference",
            "belowMeasurementError", "directionChanges", "mismatchPairs",
        ],
    )
    write_csv(out_dir / "series-payload.csv", payload_rows)
    write_csv(out_dir / "series-memory.csv", memory_rows)
    write_csv(out_dir / "series-cell-stability.csv", cell_stability_rows)
    write_csv(out_dir / "series-import.csv", import_rows)
    write_csv(out_dir / "series-import-comparison.csv", import_comparison_rows)
    write_csv(out_dir / "series-scaling.csv", scaling_rows)
    write_csv(
        out_dir / "series-scaling-exploratory.csv",
        exploratory_scaling_rows,
        fieldnames=[
            "queryLabel", "series", "axis", "system", "runs", "medianStartMs",
            "medianEndMs", "medianEndToStartRatio", "minAlpha", "medianAlpha",
            "maxAlpha", "minR2", "maxR2", "positiveSlopeRuns", "status",
        ],
    )
    (out_dir / "report-provenance.json").write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    write_series_latex(
        out_dir / "thesis-tables-series.tex", runs, comparison_rows, import_rows,
        import_comparison_rows, scaling_rows, exploratory_scaling_rows,
        memory_analysis, provenance,
    )

    spread_ratios = [float(row["spreadRatio"]) for row in repeatability_rows]
    worst = max(repeatability_rows, key=lambda row: float(row["spreadRatio"]))
    supported = [row for row in comparison_rows if row["verdict"] == "SUPPORTED"]
    supported_local = [row for row in supported if row["direction"] == "LOCAL"]
    supported_reference = [row for row in supported if row["direction"] == "REFERENCE"]
    magnitude_alerts = [
        row for row in supported
        if row["magnitudeStability"] == "UNSTABLE_MAGNITUDE"
    ]
    top_magnitude_alerts = sorted(
        magnitude_alerts,
        key=lambda row: float(row["ratioMagnitudeSpread"]),
        reverse=True,
    )[:10]
    below = [row for row in comparison_rows if row["verdict"] == "BELOW_MEASUREMENT_ERROR"]
    changing = [row for row in comparison_rows if row["verdict"] == "DIRECTION_CHANGES"]
    top_payload_rows = sorted(
        (row for row in payload_rows if math.isfinite(float(row["largerToSmallerFactor"]))),
        key=lambda row: float(row["largerToSmallerFactor"]),
        reverse=True,
    )[:10]
    supported_imports = [row for row in import_comparison_rows if row["verdict"] == "SUPPORTED"]
    supported_imports_local = [row for row in supported_imports if row["direction"] == "LOCAL"]
    supported_imports_reference = [row for row in supported_imports if row["direction"] == "REFERENCE"]
    distinct_pairs, replicate_groups = distinct_experiment_count(anchor.datasets, comparison_rows)
    distinct_pairs_note = (
        f"Te {len(comparison_rows)} par pokrywa **{distinct_pairs} odrębnych warunków**: "
        + "; ".join(" = ".join(group) for group in replicate_groups)
        + " to ten sam zbiór pod kilkoma nazwami (punkt przecięcia serii skalowania), "
        "więc jego pary są powtórzonym pomiarem jednego warunku, a nie osobnymi warunkami. "
        "Werdykty liczone są ze wszystkich par — powtórzenia są prawomocnymi pomiarami; "
        "liczba warunków mówi natomiast, ilu **różnych** sytuacji dotyczą wnioski."
        if replicate_groups
        else f"Wszystkie {len(comparison_rows)} par dotyczy odrębnych warunków."
    )

    degenerate = degenerate_response_queries(anchor.path)
    empty_pairs = sum(count for kind, count in degenerate.values() if kind.startswith("pusta"))
    degenerate_note = (
        "Kontrola liczności per repetycja nie różnicuje zapytań o stałej odpowiedzi: "
        + "; ".join(
            f"`{label}` ({kind}, {count} par)"
            for label, (kind, count) in sorted(degenerate.items())
        )
        + f". Dla {empty_pairs} par o pustej odpowiedzi ścisłe porównanie XES-JSON zestawia "
        "puste wyniki po obu stronach. Jako obciążenie wydajnościowe pozostają one ważne "
        "(predykat bez dopasowań nadal kosztuje pełny skan), ale nie stanowią dowodu "
        "równoważności semantycznej."
        if degenerate
        else ""
    )

    base_report = anchor.path / "thesis-report.md"
    base_report_text = base_report.read_text(encoding="utf-8")
    disk_verdict = next(
        (
            line
            for line in base_report_text.splitlines()
            if line.startswith("- **Q3 (dysk).**")
        ),
        "- **Q3 (dysk).** Brak automatycznie wygenerowanego werdyktu.",
    )

    md = [
        "# Analiza serii kontrbalansowanych przebiegów",
        "",
        f"- Przebiegi: {', '.join(run.name for run in runs)}",
        f"- Kolejności datasetów: {', '.join(str(run.environment.get('datasetOrder')) for run in runs)}",
        f"- Commit: `{reference_signature['gitCommit']}`",
        f"- Wersja protokołu benchmarku: {reference_signature['benchmarkProtocolVersion']}",
        f"- Fingerprint workloadu: `{reference_signature['fingerprint']}`",
        f"- Generator raportu: commit `{provenance.get('generatorGitCommit') or 'unavailable'}`, "
        f"dirty=`{str(provenance.get('generatorGitDirty')).lower()}`",
        f"- SHA-256 `scripts/benchmarks/compare-runs.py`: `{provenance['compareRunsSha256']}`",
        f"- Przebieg kotwiczący szczegółowe tabele/wykresy: **{anchor.name}** "
        "(środkowa mediana LOCAL; wybór służy wyłącznie prezentacji, nie estymacji efektu)",
        "- Pełne tabele pojedynczego bloku pozostają w `thesis-report.md`; raport serii "
        "nie duplikuje ich automatycznie.",
        "",
        "## Werdykt serii — Q2",
        "",
        f"Spośród {len(comparison_rows)} porównywalnych par efekt powtórzył kierunek we wszystkich "
        f"przebiegach i przekroczył błąd replikacyjny dla **{len(supported)}** par. "
        f"Wśród nich {len(supported_local)} przemawia za LOCAL, a "
        f"{len(supported_reference)} za REFERENCE. "
        f"Dla {len(below)} kierunek był stały, ale efekt nie przekroczył błędu pomiaru; "
        f"dla {len(changing)} kierunek zmieniał się między przebiegami.",
        "",
        distinct_pairs_note,
        "",
        "Werdykt „powtarzalny ponad błędem” (`SUPPORTED` w CSV) jest liczony z pełnych "
        "przebiegów jako bloków: najmniejszy efekt "
        "w serii musi mieć ten sam kierunek i przekraczać największy rozrzut replikatów dla "
        "danego zapytania. Trzydzieści żądań wewnątrz przebiegu nie jest traktowane jako "
        "trzydzieści niezależnych replik eksperymentu.",
        "Przy tej liczbie bloków jest to kryterium powtarzalności efektu w zarejestrowanym "
        "środowisku, nie test istotności dla populacji maszyn ani podstaw do uniwersalizacji "
        "wyniku poza wersje, limity zasobów i workload zapisane w artefaktach.",
        "",
        f"Osobny alarm opisowy wykrył dużą niestabilność **wielkości** efektu dla "
        f"{len(magnitude_alerts)} par z werdyktem `SUPPORTED`. Alarm nie zmienia sparowanego "
        "werdyktu kierunku: pojawia się, gdy zakres ilorazów między blokami albo Q3/Q1 "
        f"którejkolwiek 30-próbkowej komórki osiąga co najmniej ×{MAGNITUDE_ALERT_FACTOR:.0f}. "
        "W takich wierszach główną liczbą pozostaje najmniejszy efekt, a mediana R/L nie jest "
        "reprezentatywnym oszacowaniem skali przewagi.",
        "",
        *(
            [
                f"- {row['datasetName']} / {row['queryLabel']}: efekt konserwatywny "
                f"×{float(row['conservativeMagnitude']):.2f} ({direction_pl(str(row['direction']))}), "
                f"zakres R/L [{float(row['minRatio']):.2f}; {float(row['maxRatio']):.2f}], "
                f"maks. Q3/Q1 komórki ×{float(row['maxWithinCellIqrFactor']):.2f}."
                for row in top_magnitude_alerts
            ]
            if magnitude_alerts
            else ["- Brak par objętych alarmem dużej niestabilności wielkości."]
        ),
        *(
            [f"- Pokazano 10 największych z {len(magnitude_alerts)} alarmów; pełny wykaz jest w `series-comparison.csv`."]
            if len(magnitude_alerts) > 10 else []
        ),
        "",
        "Dane pozwalają stwierdzić obecność różnych reżimów opóźnienia, lecz nie wskazują "
        "ich przyczyny. Bez planów wykonania lub logów `EXPLAIN` raport nie przypisuje ich "
        "przełączeniu planu PostgreSQL ani żadnemu innemu mechanizmowi.",
        "",
        "## Q2 — przekrój po rodzaju zapytania",
        "",
        "Poniższe zestawienie zapobiega dominacji licznych punktów siatki syntetycznej. "
        "Jednostką wniosku nadal jest para dataset–zapytanie; tabela jedynie agreguje "
        f"werdykty, a komplet {len(comparison_rows)} wierszy pozostaje w `series-comparison.csv`.",
        "",
        "![Werdykty Q2 według zapytania](plots/series-query-outcomes.svg)",
        "",
        "| Zapytanie | Pary | LOCAL | REFERENCE | Poniżej błędu | Zmiana kierunku | Alarm wielkości |",
        "| :--- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in query_outcome_rows:
        md.append(
            f"| {row['queryLabel']} | {row['comparablePairs']} | {row['supportedLocal']} "
            f"| {row['supportedReference']} | {row['belowMeasurementError']} "
            f"| {row['directionChanges']} | {row['magnitudeAlerts']} |"
        )

    if real_dataset_outcome_rows:
        md += [
            "",
            "### Walidacja na logach rzeczywistych",
            "",
            "![Werdykty Q2 na logach rzeczywistych](plots/series-real-datasets.svg)",
            "",
            "| Dataset | Porównywalne | LOCAL | REFERENCE | Poniżej błędu | Zmiana kierunku | MISMATCH |",
            "| :--- | ---: | ---: | ---: | ---: | ---: | ---: |",
            *[
                f"| {row['datasetName']} | {row['comparablePairs']} | {row['supportedLocal']} "
                f"| {row['supportedReference']} | {row['belowMeasurementError']} "
                f"| {row['directionChanges']} | {row['mismatchPairs']} |"
                for row in real_dataset_outcome_rows
            ],
        ]

    md += [
        "",
        "## Q2 — rozmiar odpowiedzi HTTP",
        "",
        "Czas end-to-end obejmuje serializację i transfer, dlatego różne rozmiary odpowiedzi "
        "mogą współtworzyć obserwowany efekt. To diagnostyka mechanizmu, nie korekta czasu: "
        "nie odejmujemy kosztu transportu ani nie przeliczamy wyniku na bajt.",
        "",
        "![Największe różnice rozmiaru odpowiedzi](plots/series-payload.svg)",
        "",
        "| Dataset | Zapytanie | Status pary | LOCAL [B] | REFERENCE [B] | Większy payload | Faktor |",
        "| :--- | :--- | :--- | ---: | ---: | :--- | ---: |",
        *[
            f"| {row['datasetName']} | {row['queryLabel']} | {row['pairStatus']} "
            f"| {row['medianLocalBytes']} | {row['medianReferenceBytes']} "
            f"| {row['largerPayloadSystem']} | ×{float(row['largerToSmallerFactor']):.2f} |"
            for row in top_payload_rows
        ],
        "",
        "Pełne wartości dla wszystkich par, w tym unieważnionych MISMATCH, zapisano w `series-payload.csv`.",
    ]

    md += [
        "",
        "## Q2 — skalowanie między przebiegami",
        "",
        "Każdy wykładnik α dopasowano osobno w każdym pełnym bloku, wyłącznie dla "
        "zadeklarowanych przed pomiarem par zapytanie–seria. Werdykt wzrostu wymaga "
        "R² ≥ 0,30 i dodatniego nachylenia w każdym bloku, a najmniejsza przewidywana "
        "zmiana między końcami osi musi przekroczyć błąd replikacyjny zapytania. Trzy "
        "bloki nie wystarczają do "
        "testowania różnicy wykładników między systemami, dlatego zakresów α nie wolno "
        "czytać jako takiego testu. Seria `shape-scaling` ma stałą liczbę zdarzeń i jest "
        "raportowana punktowo na wykresach, bez dopasowania potęgowego.",
        "",
        "| Zapytanie | Oś | System | α mediana | Zakres α | Zakres R² | Min. efekt osi | Próg | Werdykt |",
        "| :--- | :--- | :--- | ---: | :--- | :--- | ---: | ---: | :--- |",
    ]
    for row in scaling_rows:
        md.append(
            f"| {row['queryLabel']} | {row['axis']} | {row['system']} "
            f"| {float(row['medianAlpha']):+.2f} "
            f"| [{float(row['minAlpha']):+.2f}; {float(row['maxAlpha']):+.2f}] "
            f"| [{float(row['minR2']):.2f}; {float(row['maxR2']):.2f}] "
            f"| ×{float(row['minEndpointFactor']):.2f} | ×{float(row['practicalFloor']):.2f} "
            f"| {verdict_pl(str(row['verdict']))} |"
        )

    exploratory_queries = sorted({str(row["queryLabel"]) for row in exploratory_scaling_rows})
    local_positive_all = sum(
        row["system"] == "local" and int(row["positiveSlopeRuns"]) == int(row["runs"])
        for row in exploratory_scaling_rows
    )
    md += [
        "",
        "## Q2 — eksploracyjne skalowanie poza predeklarowanym kontraktem",
        "",
        "Poniższa analiza obejmuje **wszystkie**, a nie wybrane po wyniku, zapytania bez "
        "predeklarowanego `trace-scaling`: "
        + ", ".join(f"`{query}`" for query in exploratory_queries)
        + ". Nie zmienia pola `scalingSeries`, fingerprintu ani werdyktów konfirmacyjnych. "
        "Pokazuje wyłącznie wzorce warte dalszej hipotezy.",
        "",
        f"Dodatnie nachylenie LOCAL wystąpiło we wszystkich blokach dla "
        f"{local_positive_all}/{len(exploratory_queries)} takich par zapytanie–oś. "
        "REFERENCE również rośnie dla części zapytań; nie jest opisywany jako system "
        "zawsze płaski. Trzy bloki i brak predeklaracji nie pozwalają testować różnicy "
        "wykładników ani nadawać tym wierszom werdyktu przewagi.",
        "",
        "| Zapytanie | System | Mediana początku [ms] | Mediana końca [ms] | Mediana koniec/początek | Zakres α | Zakres R² | Dodatnie bloki | Status |",
        "| :--- | :--- | ---: | ---: | ---: | :--- | :--- | ---: | :--- |",
    ]
    for row in exploratory_scaling_rows:
        md.append(
            f"| {row['queryLabel']} | {row['system']} | {float(row['medianStartMs']):.2f} "
            f"| {float(row['medianEndMs']):.2f} | ×{float(row['medianEndToStartRatio']):.2f} "
            f"| [{float(row['minAlpha']):+.2f}; {float(row['maxAlpha']):+.2f}] "
            f"| [{float(row['minR2']):.2f}; {float(row['maxR2']):.2f}] "
            f"| {row['positiveSlopeRuns']}/{row['runs']} | eksploracyjne — niepredeklarowane |"
        )

    md += [
        "",
        "## Powtarzalność median Q2",
        "",
        f"- Mediana rozrzutu max/min: **×{median(spread_ratios):.2f}**",
        f"- Maksymalny rozrzut: **×{float(worst['spreadRatio']):.2f}** "
        f"({worst['datasetName']} / {worst['queryLabel']} / {worst['system']})",
        "",
        "Pełne wartości znajdują się w `repeatability.csv`; efekty serii w `series-comparison.csv`.",
        "",
        "## Q1 — import między przebiegami",
        "",
        f"Próg błędu replikacyjnego importu w tej serii: **×{import_floor:.2f}**. "
        + (
            "Werdykt stosuje tę samą konserwatywną regułę blokową co Q2."
            if import_quality_ok
            else f"Przekracza on bramkę jakości ×{import_gate:.2f}; Q1 pozostaje nierozstrzygnięte, "
                 "a ilorazy są wyłącznie diagnostyczne."
        ),
        (
            f"Spośród {len(import_comparison_rows)} datasetów wynik jest wsparty dla "
            f"{len(supported_imports)}: {len(supported_imports_local)} na korzyść LOCAL i "
            f"{len(supported_imports_reference)} na korzyść REFERENCE."
            if import_quality_ok
            else "Żaden dataset nie otrzymuje werdyktu o przewadze przy niestabilnym pomiarze importu."
        ),
        "",
        "| Dataset | Efekt konserwatywny | Kierunek | Próg | Werdykt | LOCAL med. [s] (diag.) | REFERENCE med. [s] (diag.) | Mediana R/L (diag.) | Zakres R/L (diag.) |",
        "| :--- | ---: | :--- | ---: | :--- | ---: | ---: | ---: | :--- |",
    ]
    by_import = {(row["datasetName"], row["system"]): row for row in import_rows}
    for comparison in import_comparison_rows:
        dataset = comparison["datasetName"]
        local = by_import[(dataset, "local")]
        reference = by_import[(dataset, "reference")]
        md.append(
            f"| {dataset} | {conservative_effect_text(comparison)} "
            f"| {direction_pl(str(comparison['direction']))} "
            f"| ×{float(comparison['practicalFloor']):.2f} "
            f"| {verdict_pl(str(comparison['verdict']))} "
            f"| {float(local['medianSeconds']):.2f} | {float(reference['medianSeconds']):.2f} "
            f"| ×{float(comparison['medianRatioReferenceToLocal']):.2f} "
            f"| [{float(comparison['minRatio']):.2f}; {float(comparison['maxRatio']):.2f}] |"
        )

    components = sorted(REQUIRED_MEMORY_COMPONENTS)
    md += [
        "",
        "## Q3 — dysk",
        "",
        disk_verdict.removeprefix("- "),
        "",
        "![Q3: przyrost storage względem liczby zdarzeń](plots/storage_scaling_delta_by_totalEvents.svg)",
        "",
        "## Q3 — pamięć między przebiegami",
        "",
        "| Składnik | " + " | ".join(run.name for run in runs) + " | Rozrzut |",
        "| :--- | " + " | ".join("---:" for _ in runs) + " | ---: |",
    ]
    for component in components:
        values = [run.memory[component] for run in runs]
        md.append(
            f"| {component} | " + " | ".join(f"{value:.0f}" for value in values) +
            f" | ×{max(values) / min(values):.2f} |"
        )
    md += [
        "",
        "| Przebieg | LOCAL med. [MiB] | REFERENCE med. [MiB] | L/R | LOCAL peak / budżet | REFERENCE peak / budżet |",
        "| :--- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for run in runs:
        local_budget = system_memory_budget_mib(run, "local")
        reference_budget = system_memory_budget_mib(run, "reference")
        local_peak = run.memory_peaks["local-total"]
        reference_peak = run.memory_peaks["reference-total"]
        md.append(
            f"| {run.name} | {run.memory['local-total']:.0f} | {run.memory['reference-total']:.0f} "
            f"| ×{run.memory['local-total'] / run.memory['reference-total']:.3f} "
            f"| {local_peak:.0f} / {local_budget:.0f} MiB ({100.0 * local_peak / local_budget:.1f}%) "
            f"| {reference_peak:.0f} / {reference_budget:.0f} MiB "
            f"({100.0 * reference_peak / reference_budget:.1f}%) |"
        )
    local_totals = memory_analysis["local"]
    reference_totals = memory_analysis["reference"]
    differences = memory_analysis["differences"]
    memory_verdict = str(memory_analysis["verdict"])
    if memory_verdict == "SUPPORTED":
        memory_conclusion = (
            f"wsparty kierunek niższej obserwowanej pamięci: "
            f"{direction_pl(str(memory_analysis['direction']))}"
        )
    elif memory_verdict == "BELOW_MEASUREMENT_ERROR":
        memory_conclusion = "nierozstrzygnięte — kierunek poniżej błędu pomiaru"
    else:
        memory_conclusion = "nierozstrzygnięte — kierunek zmienia się między blokami"
    md += [
        "",
        "![Metryka pamięci docker stats między blokami](plots/series-memory.svg)",
        "",
        f"**Q3-pamięć: {memory_conclusion}.** Konserwatywny efekt kierunku niższego wskazania "
        "`docker stats` "
        f"wynosi ×{float(memory_analysis['conservativeMagnitude']):.3f}, a próg wynikający "
        f"z większego rozrzutu międzyblokowego obu systemów wynosi "
        f"×{float(memory_analysis['practicalFloor']):.3f} "
        f"(LOCAL ×{float(memory_analysis['localSpread']):.3f}, "
        f"REFERENCE ×{float(memory_analysis['referenceSpread']):.3f}).",
        "",
        f"Mediana sumy LOCAL wynosi {median(local_totals):.0f} MiB, REFERENCE "
        f"{median(reference_totals):.0f} MiB. Różnica LOCAL−REFERENCE w {len(runs)} blokach: "
        f"{', '.join(f'{value:+.0f} MiB' for value in differences)}. Zgodny znak różnicy "
        "jest warunkiem koniecznym, lecz w tej serii nie wystarcza do wniosku o przewadze.",
        "",
        "Są to wartości metryki kontenerowej `docker stats` w rozgrzanej fazie zapytań "
        "przy równych, stałych budżetach całych aplikacji. Na Linuksie CLI raportuje "
        "użycie cgroup pomniejszone o nieaktywny cache plikowy, więc nie jest to procesowy "
        "RSS. Nie są też estymacją minimalnej "
        "wymaganej pamięci. Piki pozostały poniżej limitów, a walidacja serii potwierdziła "
        "zakończenie obu aplikacji bez OOM i restartów; limity nie zostały więc potraktowane "
        "jako wyjaśnienie samego kierunku obserwacji.",
        "",
    ]

    mismatches = sorted(runs[0].mismatch_pairs)
    matched_pairs = runs[0].total_query_pairs - len(mismatches)
    md += [
        "## Q4 — zgodność odpowiedzi między przebiegami",
        "",
        f"Zestaw statusów był identyczny we wszystkich blokach: {matched_pairs} par przeszło "
        f"kontrolę semantyczną protokołu i {len(mismatches)} par miało status MISMATCH "
        f"na {runs[0].total_query_pairs}.",
    ]
    if compatibility:
        md += [
            "",
            "### Niezależna szeroka kontrola kompatybilności",
            "",
            f"Do serii dołączono raport obejmujący **{compatibility['checks']} kontroli**: "
            f"{compatibility['matches']} MATCH, {compatibility['informationalMismatches']} "
            f"zaakceptowanych różnic informacyjnych i **{compatibility['strictProblems']} "
            "ścisłych problemów**. Hashe `summary.md` i `results.json` zapisano w "
            "`report-provenance.json`. Wynik ten jest właściwą podstawą twierdzenia o "
            "szerokiej zgodności; sam benchmark wydajnościowy nie zastępuje testów semantycznych.",
        ]
    else:
        md += [
            "",
            "**Brak dołączonego szerokiego raportu kompatybilności.** Ten artefakt trzeba "
            "wskazać przez `--compatibility-report`, zanim Q4 zostanie użyte jako finalny dowód.",
        ]
    if degenerate_note:
        md += ["", degenerate_note]
    if mismatches:
        evidence_confirmed = (
            evidence.get("classification") == "NON_TOTAL_ORDER_WITH_LIMIT_BOUNDARY_TIE"
            and evidence.get("boundaryTieExplanationConfirmed") is True
            and evidence.get("timestampOrderingHypothesisRejected") is True
            and evidence.get("referenceBugProven") is False
            and evidence.get("localBugFixProven") is False
        )
        if evidence_confirmed:
            md += [
                "",
                "### Audyt trzech MISMATCH `hoistedGroup`",
                "",
                "Audyt surowych XES, kodu obu implementacji oraz replay live potwierdził "
                "**remis na granicy domyślnego limitu 30**, przy sortowaniu tylko po "
                "`count(t:name) desc`. Oba systemy mogą zgodnie z tak określonym zapytaniem "
                "wybrać inny podzbiór grup remisujących. Hipoteza sortowania REFERENCE po "
                "timestampie została odrzucona.",
                "",
                "**Mismatch nie dowodzi błędu REFERENCE ani poprawki LOCAL.** Pary pozostają "
                "unieważnione dla Q2, bo odpowiedzi różnią się i ich czasów nie wolno porównywać. "
                "Dowód maszynowy: `hoisted-group-evidence.json`; opis: `hoisted-group-evidence.md`.",
                "",
                "| Dataset | Ponad progiem | Remis na progu | Wybierane z remisu | Możliwe zdarzenia |",
                "| :--- | ---: | ---: | ---: | :--- |",
                *[
                    f"| {dataset_name} | {row.get('variantsAboveCutoff')} "
                    f"| {row.get('variantsTiedAtCutoff')} | {row.get('tiedVariantsToSelect')} "
                    f"| {row.get('minimumPossibleEventCount')}–{row.get('maximumPossibleEventCount')} |"
                    for dataset_name, row in sorted(evidence.get("datasets", {}).items())
                    if isinstance(row, dict)
                ],
            ]
        else:
            md += [
                "",
                "MISMATCH nie mają kompletnego, zweryfikowanego artefaktu przyczynowego. "
                "Nie wolno przypisywać ich błędowi żadnej strony:",
                "",
                *[f"- {dataset} / {query}" for dataset, query in mismatches],
            ]
    else:
        md.append("Nie wystąpił żaden MISMATCH Q4.")
    md += [
        "",
        "## Luki obecnego eksperymentu i następne pomiary",
        "",
        "Obecna seria dobrze mierzy porównanie end-to-end dla ustalonego workloadu, lecz "
        "nie odpowiada na wszystkie pytania wydajnościowe. Następny, osobno wersjonowany "
        "protokół powinien dodać:",
        "",
        "- profil strukturalny logów (liczba wariantów, długości śladów, selektywność filtrów), "
        "aby wyjaśniać, a nie tylko opisywać różnice między datasetami;",
        "- jawne poziomy selektywności predykatów (0%, rzadkie, średnie, częste dopasowania);",
        "- wydajność datastore'u wielologowego i zapytań przekrojowych;",
        "- osobny czas pełnego eksportu XES, bo obecnie roundtrip sprawdza poprawność, nie raportuje latencji;",
        "- izolowany pomiar dysku także dla logów rzeczywistych; obecna sonda przyczynowa obejmuje serię syntetyczną;",
        "- przepustowość i skalowanie przy współbieżności oraz użycie CPU. Dzisiejszy protokół jest celowo jednowątkowy i mierzy opóźnienie, nie throughput.",
        "",
        "Nie należy dokładać tych osi do istniejącej serii po fakcie. Zmieniają workload i "
        "wymagają nowej wersji protokołu oraz nowych trzech kontrbalansowanych bloków.",
        "",
    ]

    # Only the repeatability narrative, not the whole series report. Writing the full
    # `md` here made this file a byte-exact prefix of `thesis-report-series.md` — 60 kB
    # of duplicate under a name that promises one section, which is both misleading and
    # a place for the two copies to drift apart.
    repeatability_path = out_dir / "repeatability.md"
    repeatability_start = md.index("## Powtarzalność median Q2")
    repeatability_end = md.index("## Q1 — import między przebiegami")
    repeatability_path.write_text(
        "\n".join(
            [
                "# Powtarzalność median Q2 między przebiegami",
                "",
                f"- Przebiegi: {', '.join(run.name for run in runs)}",
                f"- Commit mierzony: `{reference_signature['gitCommit']}`",
                "",
                "Pełny raport serii wraz z werdyktami: `thesis-report-series.md`.",
                "",
                *md[repeatability_start:repeatability_end],
            ],
        )
        + "\n",
        encoding="utf-8",
    )
    combined = "\n".join(md) + "\n"
    (out_dir / "thesis-report-series.md").write_text(combined, encoding="utf-8")

    print(f"\nSeria ważna. Przebieg kotwiczący: {anchor.name}")
    print(f"Zapisano artefakty serii w {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
