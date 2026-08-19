#!/usr/bin/env python3
"""Audit the real-log ``hoistedGroup`` benchmark mismatches.

The measured query orders trace-variant groups only by ``count(t:name) desc``.
Both REST APIs then cap the trace window at 30.  When many variants have the
same count at that boundary, different valid members of the tied set can enter
the response and produce different event totals.

This script checks that explanation against the raw XES files and one or more
benchmark run directories.  With ``--live`` it additionally imports the three
logs into both APIs, captures the selected variant lengths, and removes only
the datastore IDs it created.  It deliberately does not normalize responses or
turn the mismatch into MATCH; it classifies what the mismatch can and cannot
prove.
"""

from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
import argparse
import csv
import gzip
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from _common import ScriptError, http_json, http_post_file, main_guard, repo_root  # noqa: E402


QUERY_LABEL = "hoistedGroup"
QUERY = "group by ^e:name order by count(t:name) desc"
TRACE_LIMIT = 30
PQL_SPEC_QUOTE = (
    "By omitting the `order by` clause, the components are returned in the same "
    "order as provided by the data source."
)
REAL_DATASETS = {
    "real-hospital": "Hospital_log.xes.gz",
    "real-journal-review": "JournalReview.xes.gz",
    "real-sepsis": "Sepsis.xes.gz",
}


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        raise ScriptError(f"missing benchmark artifact: {path}")
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def attribute(element: ET.Element, key: str) -> str | None:
    for child in element:
        if child.attrib.get("key") == key:
            return child.attrib.get("value")
    return None


def parse_timestamp(value: str | None) -> datetime | None:
    if not value:
        return None
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def subset_sums(values: list[int], count: int) -> set[int]:
    """All exact sums formed by selecting ``count`` distinct list entries."""
    possible: list[set[int]] = [set() for _ in range(count + 1)]
    possible[0].add(0)
    for value in values:
        for selected in range(count, 0, -1):
            possible[selected].update(total + value for total in possible[selected - 1])
    return possible[count]


def analyze_xes(path: Path) -> dict[str, Any]:
    sequences: list[tuple[str, ...]] = []
    traces_with_equal_timestamps = 0
    traces_out_of_timestamp_order = 0
    traces_changed_by_stable_timestamp_sort = 0

    with gzip.open(path, "rb") as handle:
        root = ET.parse(handle).getroot()
    for trace in (element for element in root if local_name(element.tag) == "trace"):
        events = [element for element in trace if local_name(element.tag) == "event"]
        names = tuple(attribute(event, "concept:name") or "" for event in events)
        timestamps = [parse_timestamp(attribute(event, "time:timestamp")) for event in events]
        present = [value for value in timestamps if value is not None]
        if len(present) != len(set(present)):
            traces_with_equal_timestamps += 1
        if any(
            left is not None and right is not None and left > right
            for left, right in zip(timestamps, timestamps[1:])
        ):
            traces_out_of_timestamp_order += 1
        stable_timestamp_order = sorted(
            range(len(events)),
            key=lambda index: (
                timestamps[index] is None,
                timestamps[index] or datetime.max.replace(tzinfo=timezone.utc),
            ),
        )
        if tuple(names[index] for index in stable_timestamp_order) != names:
            traces_changed_by_stable_timestamp_sort += 1
        sequences.append(names)

    variants = Counter(sequences)
    frequencies = sorted(variants.values(), reverse=True)
    if len(frequencies) < TRACE_LIMIT:
        raise ScriptError(f"{path.name} has only {len(frequencies)} variants; expected at least {TRACE_LIMIT}")
    cutoff = frequencies[TRACE_LIMIT - 1]
    fixed = [sequence for sequence, frequency in variants.items() if frequency > cutoff]
    tied = [sequence for sequence, frequency in variants.items() if frequency == cutoff]
    tied_to_select = TRACE_LIMIT - len(fixed)
    tied_lengths = sorted(len(sequence) for sequence in tied)
    sums = subset_sums(tied_lengths, tied_to_select)
    fixed_event_count = sum(len(sequence) for sequence in fixed)

    return {
        "file": str(path.resolve()),
        "fileSha256": sha256(path),
        "traces": len(sequences),
        "variants": len(variants),
        "tracesWithEqualTimestamps": traces_with_equal_timestamps,
        "tracesOutOfTimestampOrder": traces_out_of_timestamp_order,
        "tracesChangedByStableTimestampSort": traces_changed_by_stable_timestamp_sort,
        "traceLimit": TRACE_LIMIT,
        "cutoffFrequency": cutoff,
        "variantsAboveCutoff": len(fixed),
        "variantsTiedAtCutoff": len(tied),
        "tiedVariantsToSelect": tied_to_select,
        "fixedEventCount": fixed_event_count,
        "minimumPossibleEventCount": fixed_event_count + min(sums),
        "maximumPossibleEventCount": fixed_event_count + max(sums),
        "possibleTiedTailSums": sorted(sums),
        "tiedVariantLengths": tied_lengths,
    }


def benchmark_observations(run_dirs: Iterable[Path]) -> dict[str, Any]:
    runs: dict[str, Any] = {}
    mismatch_sets: list[set[tuple[str, str]]] = []
    for run_dir in run_dirs:
        rows = read_csv(run_dir / "query-results.csv")
        mismatches = {
            (row.get("datasetName", ""), row.get("queryLabel", ""))
            for row in rows
            if row.get("status") == "MISMATCH"
        }
        mismatch_sets.append(mismatches)
        observations: dict[str, Any] = {}
        for dataset in REAL_DATASETS:
            by_system: dict[str, list[int]] = {}
            for system in ("local", "reference"):
                counts = {
                    int(row["eventCount"])
                    for row in rows
                    if row.get("datasetName") == dataset
                    and row.get("queryLabel") == QUERY_LABEL
                    and row.get("system") == system
                    and row.get("status") == "MISMATCH"
                    and row.get("eventCount", "").isdigit()
                }
                if len(counts) != 1:
                    raise ScriptError(
                        f"{run_dir.name}: expected one stable MISMATCH event count for "
                        f"{dataset}/{system}, got {sorted(counts)}"
                    )
                by_system[system] = sorted(counts)
            observations[dataset] = {
                system: values[0] for system, values in by_system.items()
            }
        runs[run_dir.name] = observations

    expected = {(dataset, QUERY_LABEL) for dataset in REAL_DATASETS}
    if not mismatch_sets or any(mismatches != expected for mismatches in mismatch_sets):
        raise ScriptError(
            "benchmark mismatch set is not exactly the three real-log hoistedGroup pairs: "
            + repr(mismatch_sets)
        )
    return {"runs": runs, "mismatchSetStable": len(set(map(frozenset, mismatch_sets))) == 1}


def collect_nodes(value: Any, key: str) -> list[dict[str, Any]]:
    if value is None:
        return []
    if isinstance(value, list):
        return [node for item in value for node in collect_nodes(item, key)]
    if isinstance(value, dict):
        return [value, *collect_nodes(value.get(key), key)]
    return []


def authorized_headers(api: str, login: str, password: str) -> dict[str, str]:
    if api.rstrip("/").endswith(":8080/api"):
        return {}
    session = http_json(
        f"{api.rstrip('/')}/users/session",
        method="POST",
        body={"login": login, "password": password},
    )
    token = session.get("authorizationToken") if isinstance(session, dict) else None
    if not token:
        raise ScriptError("REFERENCE login did not return authorizationToken")
    return {"Authorization": f"Bearer {token}"}


def query_body(api: str, store_id: str, headers: dict[str, str]) -> str:
    encoded = urllib.parse.quote(QUERY, safe="")
    request = urllib.request.Request(
        f"{api.rstrip('/')}/data-stores/{store_id}/logs?query={encoded}",
        headers={"Accept": "application/json", **headers},
    )
    with urllib.request.urlopen(request, timeout=600.0) as response:
        return response.read().decode("utf-8")


def wait_for_log(api: str, store_id: str, headers: dict[str, str]) -> None:
    for _ in range(900):
        logs = http_json(f"{api.rstrip('/')}/data-stores/{store_id}/logs", headers=headers)
        if isinstance(logs, list) and logs:
            return
        time.sleep(1.0)
    raise ScriptError(f"timed out waiting for imported log in {store_id}")


def docker_image_ids() -> dict[str, str]:
    ids: dict[str, str] = {}
    for container in ("processm-interpreter", "processm-neo4j", "processm-server"):
        completed = subprocess.run(
            ["docker", "inspect", "--format", "{{.Image}}", container],
            text=True,
            capture_output=True,
            check=False,
        )
        if completed.returncode == 0 and completed.stdout.strip().startswith("sha256:"):
            ids[container] = completed.stdout.strip()
    return ids


def live_observations(
    analyses: dict[str, dict[str, Any]],
    local_api: str,
    reference_api: str,
    login: str,
    password: str,
) -> dict[str, Any]:
    systems = {
        "local": (local_api.rstrip("/"), authorized_headers(local_api, login, password)),
        "reference": (
            reference_api.rstrip("/"),
            authorized_headers(reference_api, login, password),
        ),
    }
    created: list[tuple[str, str, dict[str, str]]] = []
    observations: dict[str, Any] = {}
    try:
        for dataset, filename in REAL_DATASETS.items():
            observations[dataset] = {}
            for system, (api, headers) in systems.items():
                store = http_json(
                    f"{api}/data-stores",
                    method="POST",
                    body={"name": f"audit-hoisted-group-{dataset}-{system}-{uuid.uuid4().hex[:8]}"},
                    headers=headers,
                )
                store_id = store.get("id") if isinstance(store, dict) else None
                if not store_id:
                    raise ScriptError(f"{system} create datastore returned no id")
                created.append((api, str(store_id), headers))
                xes = repo_root() / "src" / "main" / "resources" / "logs" / filename
                status = http_post_file(f"{api}/data-stores/{store_id}/logs", xes, headers=headers)
                if status not in range(200, 300):
                    raise ScriptError(f"{system}/{dataset} upload returned HTTP {status}")
                wait_for_log(api, str(store_id), headers)
                body = query_body(api, str(store_id), headers)
                documents = json.loads(body)
                logs = [
                    document["log"]
                    for document in documents
                    if isinstance(document, dict) and isinstance(document.get("log"), dict)
                ]
                traces = [
                    trace
                    for log in logs
                    for trace in collect_nodes(log.get("trace"), "trace")
                ]
                lengths = [len(collect_nodes(trace.get("event"), "event")) for trace in traces]
                analysis = analyses[dataset]
                fixed_groups = int(analysis["variantsAboveCutoff"])
                fixed_events = int(analysis["fixedEventCount"])
                observations[dataset][system] = {
                    "logs": len(logs),
                    "traces": len(traces),
                    "events": sum(lengths),
                    "responseBytes": len(body.encode("utf-8")),
                    "selectedVariantLengths": lengths,
                    "aboveCutoffPrefixEvents": sum(lengths[:fixed_groups]),
                    "tiedTailLengths": lengths[fixed_groups:],
                    "tiedTailEvents": sum(lengths[fixed_groups:]),
                    "aboveCutoffPrefixMatchesRawXes": sum(lengths[:fixed_groups]) == fixed_events,
                }
    finally:
        for api, store_id, headers in reversed(created):
            try:
                http_json(f"{api}/data-stores/{store_id}", method="DELETE", headers=headers)
            except Exception as error:  # noqa: BLE001 - preserve all cleanup attempts
                print(f"warning: could not delete audit datastore {store_id}: {error}", file=sys.stderr)
    return {
        "performed": True,
        "apis": {"local": local_api, "reference": reference_api},
        "containerImageIds": docker_image_ids(),
        "datasets": observations,
    }


def source_evidence() -> dict[str, Any]:
    repository = repo_root()
    reference = Path(os.environ.get("PROCESSM_REFERENCE_REPO", repository.parent / "processm"))
    spec = reference / "docs" / "pql.md"
    translated = reference / "processm.core" / "src" / "main" / "kotlin" / "processm" / "core" / "log" / "hierarchical" / "TranslatedQuery.kt"
    importer = repository / "src" / "main" / "kotlin" / "com" / "processm" / "processminterpreter" / "neo4j" / "xes" / "mapping" / "Neo4jXesImportMapper.kt"
    renderer = repository / "src" / "main" / "kotlin" / "com" / "processm" / "processminterpreter" / "pql" / "cypher" / "CypherGroupByRenderer.kt"
    for path in (spec, translated, importer, renderer):
        if not path.is_file():
            raise ScriptError(f"source evidence file not found: {path}")
    spec_text = spec.read_text(encoding="utf-8")
    reference_text = translated.read_text(encoding="utf-8")
    importer_text = importer.read_text(encoding="utf-8")
    renderer_text = renderer.read_text(encoding="utf-8")
    return {
        "referenceRepository": str(reference.resolve()),
        "pqlSpecification": {
            "path": str(spec.resolve()),
            "sha256": sha256(spec),
            "sourceOrderQuotePresent": PQL_SPEC_QUOTE in spec_text,
            "tieBreakRuleSpecified": False,
        },
        "referenceTranslatedQuery": {
            "path": str(translated.resolve()),
            "sha256": sha256(translated),
            "hoistedArrayUsesScopeOrder": "pql.orderByExpressions[attribute.scope]!!.toSQL" in reference_text,
            "emptyScopeOrderFallsBackToId": '"${attribute.scope!!.alias}.id"' in reference_text,
            "timestampFallbackFound": "time:timestamp" in reference_text[
                reference_text.find("private fun selectInnerGroup"):
                reference_text.find("private fun <T> whereGroup")
            ],
        },
        "localImplementation": {
            "importerPath": str(importer.resolve()),
            "importerSha256": sha256(importer),
            "eventIndexStoredAsImportOrder": "importOrder = eventIndex" in importer_text,
            "groupRendererPath": str(renderer.resolve()),
            "groupRendererSha256": sha256(renderer),
            "groupRendererUsesImportOrder": "importOrder" in renderer_text,
        },
    }


def write_markdown(path: Path, evidence: dict[str, Any]) -> None:
    lines = [
        "# Audyt mismatchów `hoistedGroup`",
        "",
        "## Werdykt",
        "",
        "**Te trzy mismatchy nie dowodzą błędu REFERENCE ani poprawki w LOCAL.** "
        "Zapytanie sortuje grupy tylko po `count(t:name) desc`, a oba API zwracają "
        "najwyżej 30 grup śladów. Na 30. pozycji występuje remis, więc systemy wybierają "
        "różne, ale dopuszczalne warianty o tej samej liczności. Różne długości tych "
        "wariantów dają dokładnie różne liczby zdarzeń w odpowiedzi.",
        "",
        "Hipoteza o sortowaniu REFERENCE po `time:timestamp` została odrzucona: surowe "
        "pliki są już niemalejące czasowo, stabilne sortowanie po timestampie nie zmienia "
        "żadnej sekwencji nazw, a tłumacz REFERENCE używa przy pustym porządku zdarzeń "
        "identyfikatora `e.id`, nie timestampu.",
        "",
        "| Dataset | Warianty > próg | Remis przy progu | Wybór z remisu | Stałe zdarzenia | Dopuszczalny zakres | LOCAL | REFERENCE |",
        "| :--- | ---: | ---: | ---: | ---: | :--- | ---: | ---: |",
    ]
    first_run = next(iter(evidence["benchmark"]["runs"].values()))
    for dataset in REAL_DATASETS:
        analysis = evidence["datasets"][dataset]
        observation = first_run[dataset]
        lines.append(
            f"| {dataset} | {analysis['variantsAboveCutoff']} | {analysis['variantsTiedAtCutoff']} "
            f"| {analysis['tiedVariantsToSelect']} | {analysis['fixedEventCount']} "
            f"| {analysis['minimumPossibleEventCount']}–{analysis['maximumPossibleEventCount']} "
            f"| {observation['local']} | {observation['reference']} |"
        )
    lines += [
        "",
        "W każdym pełnym przebiegu uzyskano te same liczności. Oba wyniki każdego "
        "datasetu są osiągalne przez wybór wymaganej liczby wariantów z remisującego "
        "zbioru; grupy o większej liczności dają wspólną, stałą część odpowiedzi.",
        "",
        "## Konsekwencja metodologiczna",
        "",
        "Pary pozostają poprawnie oznaczone jako `MISMATCH` i są wykluczone z Q2. "
        "Nie wolno jednak przedstawiać ich jako przewagi poprawności LOCAL. Przyszły "
        "workload powinien oddzielić koszt grupowania wariantów od arbitralnego wyboru "
        "ich treści na granicy limitu, np. projekcją samego `count(t:name)`.",
        "",
        "Pełne dane, sumy możliwych ogonów, hashe źródeł i opcjonalne obserwacje live "
        "znajdują się w `hoisted-group-evidence.json`.",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_dirs", type=Path, nargs="+", help="benchmark run directories")
    parser.add_argument("--out-dir", type=Path, default=None)
    parser.add_argument("--live", action="store_true", help="also reproduce the query through both APIs")
    parser.add_argument("--local-api", default="http://localhost:8080/api")
    parser.add_argument("--reference-api", default="http://localhost:80/api")
    parser.add_argument("--processm-login", default="admin@example.com")
    parser.add_argument("--processm-password", default="Admin1234")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output = args.out_dir or args.run_dirs[0]
    output.mkdir(parents=True, exist_ok=True)
    analyses = {
        dataset: analyze_xes(repo_root() / "src" / "main" / "resources" / "logs" / filename)
        for dataset, filename in REAL_DATASETS.items()
    }
    benchmark = benchmark_observations(args.run_dirs)

    for run in benchmark["runs"].values():
        for dataset, counts in run.items():
            analysis = analyses[dataset]
            possible = set(analysis["possibleTiedTailSums"])
            for system, count in counts.items():
                tail = count - int(analysis["fixedEventCount"])
                if tail not in possible:
                    raise ScriptError(
                        f"{dataset}/{system}: observed {count} cannot be formed from the tied boundary"
                    )

    source = source_evidence()
    timestamp_hypothesis_rejected = all(
        analysis["tracesOutOfTimestampOrder"] == 0
        and analysis["tracesChangedByStableTimestampSort"] == 0
        for analysis in analyses.values()
    ) and source["referenceTranslatedQuery"]["timestampFallbackFound"] is False
    evidence: dict[str, Any] = {
        "schemaVersion": 1,
        "queryLabel": QUERY_LABEL,
        "query": QUERY,
        "effectiveTraceLimit": TRACE_LIMIT,
        "classification": "NON_TOTAL_ORDER_WITH_LIMIT_BOUNDARY_TIE",
        "referenceBugProven": False,
        "localBugFixProven": False,
        "timestampOrderingHypothesisRejected": timestamp_hypothesis_rejected,
        "boundaryTieExplanationConfirmed": True,
        "datasets": analyses,
        "benchmark": benchmark,
        "source": source,
        "live": {"performed": False},
    }
    if args.live:
        evidence["live"] = live_observations(
            analyses,
            args.local_api,
            args.reference_api,
            args.processm_login,
            args.processm_password,
        )
        for dataset, systems in evidence["live"]["datasets"].items():
            for system, observation in systems.items():
                if not observation["aboveCutoffPrefixMatchesRawXes"]:
                    raise ScriptError(f"live {dataset}/{system}: groups above cutoff differ from raw XES")
                expected = next(iter(benchmark["runs"].values()))[dataset][system]
                if observation["events"] != expected:
                    raise ScriptError(
                        f"live {dataset}/{system}: {observation['events']} events, benchmark has {expected}"
                    )

    json_path = output / "hoisted-group-evidence.json"
    md_path = output / "hoisted-group-evidence.md"
    json_path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_markdown(md_path, evidence)
    print(md_path)
    return 0


if __name__ == "__main__":
    main_guard(main)
