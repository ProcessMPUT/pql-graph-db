#!/usr/bin/env python3
"""ProcessM compatibility report.

Runs every (case x query) pair through the local `/api/query/verify` endpoint,
which executes the query against both this implementation and reference
ProcessM and compares the results semantically. Writes a timestamped, immutable
run directory under `tmp/compatibility-reports/`.

The report is evidence, not a mechanism for hiding differences: `MATCH` comes
from the endpoint's semantic comparison, never from an HTTP status or an equal
payload size. Exit code is 1 when any strict problem is found.
"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Any
import argparse
import csv
import json
import re
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import (  # noqa: E402 - path shim above must run first
    ScriptError,
    http_json,
    info,
    main_guard,
    read_json,
    repo_root,
    safe_filename,
    write_text,
)
from compatibility_query_set import (  # noqa: E402
    Query,
    compare_dropdown_queries,
    compatibility_queries,
    default_index_html,
    discovery_queries,
    multi_log_compatibility_queries,
    multi_log_discovery_queries,
)

PAYLOAD_WARNING_PERCENT = 20.0
PLACEHOLDER_RE = re.compile(r"\{[A-Za-z0-9_]+\}")

FIELDS = [
    "Log", "LocalDataStoreId", "RemoteDataStoreId", "Query", "QueryText", "Comparison",
    "Group", "Source", "Status", "Seconds", "Details", "LocalSuccess", "RemoteSuccess",
    "Match", "LocalJsonLines", "RemoteJsonLines", "LineDelta", "LineDeltaPercent",
    "LocalJsonBytes", "RemoteJsonBytes", "ByteDelta", "ByteDeltaPercent",
    "PayloadWarning", "SnapshotPath",
]

EMPTY_PAYLOAD = {
    "LocalJsonLines": None, "RemoteJsonLines": None, "LineDelta": None,
    "LineDeltaPercent": None, "LocalJsonBytes": None, "RemoteJsonBytes": None,
    "ByteDelta": None, "ByteDeltaPercent": None, "PayloadWarning": "",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument(
        "--cases",
        type=Path,
        default=Path(__file__).resolve().parent / "verify-compatibility.cases.local.json",
        help="cases file; generate from verify-compatibility.cases.example.json",
    )
    parser.add_argument("--query-source", choices=("dropdown", "matrix", "discovery"), default="dropdown")
    parser.add_argument("--profile", choices=("quick", "extended"), default="extended")
    parser.add_argument("--index-html", type=Path, default=default_index_html())
    parser.add_argument("--output-root", type=Path, default=repo_root() / "tmp" / "compatibility-reports")
    parser.add_argument("--timeout", type=float, default=420.0, metavar="SECONDS")
    parser.add_argument("--save-full-snapshots", action="store_true")
    parser.add_argument("--skip-failure-snapshots", action="store_true")
    parser.add_argument("--measure-payload-size", action="store_true")
    parser.add_argument("--include-multi-log-checks", action="store_true")
    parser.add_argument(
        "--multi-log-cases",
        type=Path,
        default=Path(__file__).resolve().parent / "verify-compatibility.multi-log.cases.local.json",
    )
    return parser.parse_args()


def load_cases(path: Path, what: str) -> list[dict[str, Any]]:
    cases = read_json(path, what)
    if not isinstance(cases, list) or not cases:
        raise ScriptError(f"{what} is empty or not a JSON array: {path}")
    return cases


def select_queries(args: argparse.Namespace) -> list[Query]:
    if args.query_source == "dropdown":
        return compare_dropdown_queries(args.index_html)
    if args.query_source == "matrix":
        return compatibility_queries(args.profile)
    return discovery_queries()


def resolve_for_case(case: dict[str, Any], query: Query) -> Query:
    """Substitute {PrimaryLogName}/{SecondaryLogName} from the multi-log case."""
    resolved = query.query
    for placeholder in ("PrimaryLogName", "SecondaryLogName"):
        token = "{" + placeholder + "}"
        if token not in resolved:
            continue
        property_name = placeholder[0].lower() + placeholder[1:]
        value = case.get(property_name)
        if not value or not str(value).strip():
            raise ScriptError(
                f"multi-log case '{case.get('name')}' must define '{property_name}' "
                f"for query '{query.label}'"
            )
        # PQL string literals escape a quote by doubling it.
        resolved = resolved.replace(token, str(value).replace("'", "''"))

    if PLACEHOLDER_RE.search(resolved):
        raise ScriptError(f"unresolved query placeholder in '{query.label}': {resolved}")
    return query.with_query(resolved)


def first_comparison_line(details: Any) -> str:
    if not details or not str(details).strip():
        return ""
    for line in str(details).splitlines():
        if line.startswith("Comparison:"):
            return line
    return ""


def markdown_cell(value: Any) -> str:
    if value is None:
        return ""
    text = str(value).replace("|", r"\|")
    return re.sub(r"\s+", " ", text).strip()


def delta_percent(delta: int, left: int, right: int) -> float:
    baseline = max(left, right)
    if baseline <= 0:
        return 0.0
    return round((delta / baseline) * 100.0, 2)


class Report:
    def __init__(self, args: argparse.Namespace, output_directory: Path) -> None:
        self.args = args
        self.output_directory = output_directory
        self.failure_directory = output_directory / "snapshots"

    def verify(self, request: dict[str, Any], fmt: str) -> Any:
        return http_json(
            f"{self.args.base_url}/api/query/verify?format={fmt}",
            method="POST",
            body=request,
            timeout=self.args.timeout,
        )

    def save_full_snapshot(self, case: dict[str, Any], query: Query, request: dict[str, Any]) -> str:
        self.failure_directory.mkdir(parents=True, exist_ok=True)
        name = f"{safe_filename(str(case.get('name')))}__{safe_filename(query.label)}.json"
        path = self.failure_directory / name
        try:
            snapshot = self.verify(request, "full")
        except ScriptError as error:
            error_path = path.with_suffix(".error.txt")
            write_text(error_path, str(error))
            return str(error_path)
        write_text(path, json.dumps(snapshot, indent=2, ensure_ascii=False))
        return str(path)

    def measure_payload(self, request: dict[str, Any]) -> dict[str, Any]:
        """Compare serialized local and remote payloads.

        Absolute line and byte counts depend on this serializer, so compare them
        within a run rather than against numbers produced by another tool. The
        deltas and percentages, which is what the warning is based on, are
        serializer-independent because both sides go through the same dump.
        """
        snapshot = self.verify(request, "full")

        def dump(value: Any) -> str:
            if value is None:
                return "null"
            return json.dumps(value, indent=2, ensure_ascii=False)

        local_json = dump((snapshot or {}).get("localResults"))
        remote_json = dump((snapshot or {}).get("remoteResults"))
        local_lines = len(local_json.splitlines()) if local_json else 0
        remote_lines = len(remote_json.splitlines()) if remote_json else 0
        local_bytes = len(local_json.encode("utf-8"))
        remote_bytes = len(remote_json.encode("utf-8"))
        line_delta = abs(local_lines - remote_lines)
        byte_delta = abs(local_bytes - remote_bytes)
        line_percent = delta_percent(line_delta, local_lines, remote_lines)
        byte_percent = delta_percent(byte_delta, local_bytes, remote_bytes)
        warning = (
            "payload-size-delta"
            if line_percent > PAYLOAD_WARNING_PERCENT or byte_percent > PAYLOAD_WARNING_PERCENT
            else ""
        )
        return {
            "LocalJsonLines": local_lines, "RemoteJsonLines": remote_lines,
            "LineDelta": line_delta, "LineDeltaPercent": line_percent,
            "LocalJsonBytes": local_bytes, "RemoteJsonBytes": remote_bytes,
            "ByteDelta": byte_delta, "ByteDeltaPercent": byte_percent,
            "PayloadWarning": warning,
        }

    def check(self, case: dict[str, Any], query: Query) -> dict[str, Any]:
        request = {
            "query": query.query,
            "dataStoreId": case.get("localDataStoreId"),
            "remoteDataStoreId": case.get("remoteDataStoreId"),
            "includeTraces": True,
            "includeEvents": True,
        }
        base = {
            "Log": case.get("name"),
            "LocalDataStoreId": case.get("localDataStoreId"),
            "RemoteDataStoreId": case.get("remoteDataStoreId"),
            "Query": query.label,
            "QueryText": query.query,
            "Comparison": query.comparison,
            "Group": query.group,
            "Source": query.source,
        }
        started = time.monotonic()

        try:
            response = self.verify(request, "light") or {}
            seconds = round(time.monotonic() - started, 2)

            if response.get("comparisonStatus") == "NONDETERMINISTIC_MATCH":
                status = "INFO"
            elif response.get("match"):
                status = "MATCH"
            elif not response.get("localSuccess") or not response.get("remoteSuccess"):
                status = "ERROR"
            elif query.comparison == "informational":
                status = "INFO"
            else:
                status = "MISMATCH"

            snapshot_path = ""
            if self.args.save_full_snapshots or (
                status in ("MISMATCH", "ERROR") and not self.args.skip_failure_snapshots
            ):
                snapshot_path = self.save_full_snapshot(case, query, request)

            payload = dict(EMPTY_PAYLOAD)
            if self.args.measure_payload_size:
                payload = self.measure_payload(request)

            return {
                **base, "Status": status, "Seconds": seconds,
                "Details": first_comparison_line(response.get("details")),
                "LocalSuccess": response.get("localSuccess"),
                "RemoteSuccess": response.get("remoteSuccess"),
                "Match": response.get("match"),
                **payload, "SnapshotPath": snapshot_path,
            }
        except ScriptError as error:
            seconds = round(time.monotonic() - started, 2)
            snapshot_path = ""
            if not self.args.skip_failure_snapshots:
                snapshot_path = self.save_full_snapshot(case, query, request)
            return {
                **base, "Status": "ERROR", "Seconds": seconds, "Details": str(error),
                "LocalSuccess": False, "RemoteSuccess": False, "Match": False,
                **EMPTY_PAYLOAD, "SnapshotPath": snapshot_path,
            }


def build_markdown(
    args: argparse.Namespace,
    cases: list[dict[str, Any]],
    queries: list[Query],
    multi_log_cases: list[dict[str, Any]],
    multi_log_queries: list[Query],
    results: list[dict[str, Any]],
    counts: dict[str, int],
) -> str:
    lines = [
        "# ProcessM Compatibility Report",
        "",
        f"- Generated: {datetime.now().astimezone().strftime('%Y-%m-%d %H:%M:%S %z')}",
        f"- Base URL: {args.base_url}",
        f"- Query source: {args.query_source}",
    ]
    if args.query_source == "dropdown":
        lines.append(f"- Dropdown source file: {args.index_html}")
    elif args.query_source == "discovery":
        lines.append("- Discovery query set: discovery_queries()")
    else:
        lines.append(f"- Matrix profile: {args.profile}")

    lines.append(f"- Cases file: {args.cases}")
    lines.append(f"- Logs: {len(cases)}")
    if args.include_multi_log_checks:
        lines.append(f"- Multi-log cases file: {args.multi_log_cases}")
        lines.append(f"- Multi-log cases: {len(multi_log_cases)}")
        lines.append(f"- Multi-log queries: {len(multi_log_queries)}")
    else:
        lines.append("- Multi-log checks: disabled")

    lines.append(f"- Checks: {len(results)}")
    lines.append(f"- Matches: {counts['matches']}")
    lines.append(f"- Accepted compatibility checks: {counts['accepted']}")
    lines.append(f"- Strict problems: {counts['strict']}")
    lines.append(f"- Informational mismatches: {counts['informational']}")
    if args.measure_payload_size:
        lines.append("- Payload size measurement: enabled")
        lines.append(f"- Payload warnings: {counts['payload_warnings']}")
    else:
        lines.append("- Payload size measurement: disabled")

    lines.append("")
    lines.append(
        "Result: all strict compatibility checks matched ProcessM."
        if counts["strict"] == 0
        else "Result: strict compatibility problems were found."
    )

    lines += ["", "## Data stores", "", "| Log | Local datastore | Remote datastore |", "|---|---|---|"]
    for case in cases:
        lines.append(
            f"| {markdown_cell(case.get('name'))} "
            f"| {markdown_cell(case.get('localDataStoreId'))} "
            f"| {markdown_cell(case.get('remoteDataStoreId'))} |"
        )

    lines += ["", "## Queries", "", "| Group | Label | Query | Comparison | Cases |", "|---|---|---|---|---|"]
    for query in queries:
        lines.append(
            f"| {markdown_cell(query.group)} | {markdown_cell(query.label)} "
            f"| `{markdown_cell(query.query)}` | {markdown_cell(query.comparison)} "
            f"| {markdown_cell(query.case_scope)} |"
        )

    lines += [
        "", "## Results", "",
        "| Log | Query | Status | Seconds | Local lines | Remote lines | Line delta "
        "| Local bytes | Remote bytes | Byte delta | Payload warning | Details | Snapshot |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|",
    ]
    for result in results:
        cells = [
            result["Log"], result["Query"], result["Status"], result["Seconds"],
            result["LocalJsonLines"], result["RemoteJsonLines"], result["LineDelta"],
            result["LocalJsonBytes"], result["RemoteJsonBytes"], result["ByteDelta"],
            result["PayloadWarning"], result["Details"], result["SnapshotPath"],
        ]
        lines.append("| " + " | ".join(markdown_cell(cell) for cell in cells) + " |")

    return "\n".join(lines) + "\n"


def print_result_table(results: list[dict[str, Any]]) -> None:
    columns = ["Log", "Query", "Status", "Seconds", "Details"]
    rows = [[markdown_cell(result[column]) for column in columns] for result in results]
    widths = [
        min(60, max(len(column), *(len(row[index]) for row in rows)) if rows else len(column))
        for index, column in enumerate(columns)
    ]

    def render(cells: list[str]) -> str:
        return "  ".join(cell[:width].ljust(width) for cell, width in zip(cells, widths))

    print(render(columns))
    print(render(["-" * width for width in widths]))
    for row in rows:
        print(render(row))


def main() -> int:
    args = parse_args()

    cases = load_cases(args.cases, "compatibility cases file")
    queries = select_queries(args)

    multi_log_cases: list[dict[str, Any]] = []
    multi_log_queries: list[Query] = []
    if args.include_multi_log_checks:
        multi_log_cases = load_cases(args.multi_log_cases, "multi-log compatibility cases file")
        multi_log_queries = (
            multi_log_discovery_queries()
            if args.query_source == "discovery"
            else multi_log_compatibility_queries()
        )

    run_id = datetime.now().strftime("%Y%m%d-%H%M%S")
    output_directory = args.output_root / run_id
    output_directory.mkdir(parents=True, exist_ok=True)
    report = Report(args, output_directory)

    checks: list[tuple[dict[str, Any], Query]] = [
        (case, query)
        for case in cases
        for query in queries
        if query.applies_to(case.get("name"))
    ]
    checks += [
        (case, resolve_for_case(case, query))
        for case in multi_log_cases
        for query in multi_log_queries
    ]

    results = []
    for index, (case, query) in enumerate(checks, start=1):
        info(f"[{index}/{len(checks)}] {case.get('name')} :: {query.label}")
        results.append(report.check(case, query))

    strict = [r for r in results if r["Status"] in ("MISMATCH", "ERROR")]
    counts = {
        "matches": sum(1 for r in results if r["Status"] == "MATCH"),
        "informational": sum(1 for r in results if r["Status"] == "INFO"),
        "accepted": sum(1 for r in results if r["Status"] in ("MATCH", "INFO")),
        "strict": len(strict),
        "payload_warnings": sum(1 for r in results if r["PayloadWarning"]),
    }

    json_path = output_directory / "results.json"
    csv_path = output_directory / "results.csv"
    markdown_path = output_directory / "summary.md"

    write_text(json_path, json.dumps(results, indent=2, ensure_ascii=False))
    with csv_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(results)
    write_text(
        markdown_path,
        build_markdown(args, cases, queries, multi_log_cases, multi_log_queries, results, counts),
    )

    print_result_table(results)

    info("")
    info("Report written to:")
    for path in (markdown_path, csv_path, json_path):
        info(f"  {path}")
    if report.failure_directory.is_dir():
        info(f"  {report.failure_directory}")

    info("")
    info("Summary:")
    info(f"  Query source: {args.query_source}")
    info(f"  Logs: {len(cases)}")
    if args.include_multi_log_checks:
        info(f"  Multi-log cases: {len(multi_log_cases)}")
        info(f"  Multi-log queries: {len(multi_log_queries)}")
    info(f"  Checks: {len(results)}")
    info(f"  Matches: {counts['matches']}")
    info(f"  Accepted compatibility checks: {counts['accepted']}")
    info(f"  Strict problems: {counts['strict']}")
    info(f"  Informational mismatches: {counts['informational']}")
    if args.measure_payload_size:
        info(f"  Payload warnings: {counts['payload_warnings']}")

    return 1 if strict else 0


if __name__ == "__main__":
    main_guard(main)
