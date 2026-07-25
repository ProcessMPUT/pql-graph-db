#!/usr/bin/env python3
"""Sequential storage-scaling probe (thesis question 5).

Imports the synthetic scaling datasets one by one into BOTH systems WITHOUT
deleting anything in between, checkpointing and measuring the database size
after each import. Because neither store ever shrinks mid-run, successive
deltas are attributable per dataset - this sidesteps the page-reuse effect that
makes per-dataset deltas unmeasurable in the benchmark's
import-measure-cleanup protocol (METODOLOGIA section Q3).

MUST run on a fresh stack (docker compose down -v && up -d, app restarted, no
imports or deletions since start) or the first deltas absorb reused pages.

Ported from measure-storage-scaling.ps1. The measurement method is unchanged:
same container commands, same size sources, same order, same CSV columns.
"""

from __future__ import annotations

from pathlib import Path
import argparse
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from _common import (  # noqa: E402 - path shim above must run first
    ScriptError,
    http_json,
    http_post_file,
    info,
    main_guard,
    repo_root,
    run_capture,
)

DATASETS = [
    "trace-100", "trace-500", "trace-2000", "trace-10000",
    "event-5", "event-10", "event-50", "event-200",
    "attr-1", "attr-5", "attr-20",
]

NEO4J_CONTAINER = "processm-neo4j"
REFERENCE_CONTAINER = "processm-server"
HEALTH_TIMEOUT_SECONDS = 180.0
HEALTH_POLL_SECONDS = 3.0
UPLOAD_TIMEOUT_SECONDS = 900.0

# Sizes count the store files only (/data/databases); transaction logs are the
# WAL equivalent and are excluded on both sides so the expansion factor
# compares durable data.
NEO4J_SIZE_COMMAND = (
    'total=0; for f in $(find /data/databases -type f 2>/dev/null); do '
    'size=$(stat -c %s "$f" 2>/dev/null || echo 0); total=$((total + size)); done; echo $total'
)

CSV_HEADER = (
    "datasetName,system,beforeBytes,afterBytes,deltaBytes,"
    "xesBytes,xesGzBytes,deltaToXesRatio,deltaToGzipRatio"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--datasets-dir", type=Path, default=None)
    parser.add_argument("--out-csv", type=Path, default=Path("tmp/storage-scaling.csv"))
    parser.add_argument("--local-api", default="http://localhost:8080/api")
    parser.add_argument("--reference-api", default="http://localhost:80/api")
    parser.add_argument("--processm-login", default="admin@example.com")
    parser.add_argument("--processm-password", default="Admin1234")
    return parser.parse_args()


def docker(*args: str, timeout: float = 300.0) -> str:
    code, output = run_capture(["docker", *args], timeout=timeout)
    if code != 0:
        raise ScriptError(f"docker {' '.join(args)} failed: {output.strip()}")
    return output.strip()


def resolve_datasets_dir(explicit: Path | None) -> Path:
    if explicit:
        path = Path(explicit)
        if not path.is_dir():
            raise ScriptError(f"datasets directory does not exist: {path}")
        return path

    runs_root = repo_root() / "tmp" / "benchmark-results"
    if not runs_root.is_dir():
        raise ScriptError("no generated-datasets found; pass --datasets-dir")
    candidates = sorted(
        (child for child in runs_root.iterdir() if (child / "generated-datasets").is_dir()),
        key=lambda child: child.name,
    )
    if not candidates:
        raise ScriptError("no generated-datasets found; pass --datasets-dir")
    return candidates[-1] / "generated-datasets"


def measure_local_bytes() -> int:
    """Neo4j store size after a clean restart.

    Neo4j 5.26 community exposes NO manual checkpoint procedure, so the only
    reliable flush is a clean shutdown (checkpoint on stop).
    """
    docker("restart", NEO4J_CONTAINER)
    deadline = time.monotonic() + HEALTH_TIMEOUT_SECONDS
    status = ""
    while time.monotonic() < deadline:
        time.sleep(HEALTH_POLL_SECONDS)
        status = docker("ps", "--filter", f"name={NEO4J_CONTAINER}", "--format", "{{.Status}}")
        if "(healthy)" in status:
            break
    if "(healthy)" not in status:
        raise ScriptError(f"neo4j did not become healthy after restart (status: {status or 'unknown'})")

    output = docker("exec", NEO4J_CONTAINER, "sh", "-c", NEO4J_SIZE_COMMAND)
    return int(output.splitlines()[0].strip())


def measure_reference_bytes() -> int:
    """PostgreSQL relation size after a CHECKPOINT.

    pg_database_size sums relation files (no WAL), so recycled 16 MiB WAL
    segments stop polluting per-dataset deltas; CHECKPOINT first flushes dirty
    shared buffers into those relation files.
    """
    docker("exec", REFERENCE_CONTAINER, "sh", "-c", "psql -U postgres -c 'CHECKPOINT;'")
    output = docker(
        "exec", REFERENCE_CONTAINER, "sh", "-c",
        "psql -U postgres -t -A -c 'SELECT sum(pg_database_size(datname)) FROM pg_database;'",
    )
    return int(output.splitlines()[0].strip())


def import_dataset(args: argparse.Namespace, token: str, name: str, gz_path: Path) -> None:
    local_store = http_json(
        f"{args.local_api}/data-stores", method="POST", body={"name": f"storage-scaling-{name}"}
    )["id"]
    status = http_post_file(
        f"{args.local_api}/data-stores/{local_store}/logs", gz_path, timeout=UPLOAD_TIMEOUT_SECONDS
    )
    if status != 201:
        raise ScriptError(f"local import {name} failed: HTTP {status}")

    auth = {"Authorization": f"Bearer {token}"}
    reference_store = http_json(
        f"{args.reference_api}/data-stores",
        method="POST",
        body={"name": f"storage-scaling-{name}"},
        headers=auth,
    )["id"]
    status = http_post_file(
        f"{args.reference_api}/data-stores/{reference_store}/logs",
        gz_path,
        headers=auth,
        timeout=UPLOAD_TIMEOUT_SECONDS,
    )
    if not 200 <= status < 300:
        raise ScriptError(f"reference import {name} failed: HTTP {status}")


def ratio(delta: int, baseline: int) -> str:
    """Format like PowerShell's '0.####' under the invariant culture."""
    if baseline == 0:
        return "0"
    text = f"{delta / baseline:.4f}".rstrip("0").rstrip(".")
    return text or "0"


def main() -> int:
    args = parse_args()
    datasets_dir = resolve_datasets_dir(args.datasets_dir)
    info(f"Datasets: {datasets_dir}")

    out_path = Path(args.out_csv)
    if not out_path.is_absolute():
        out_path = repo_root() / out_path
    out_path.parent.mkdir(parents=True, exist_ok=True)

    login = http_json(
        f"{args.reference_api}/users/session",
        method="POST",
        body={"login": args.processm_login, "password": args.processm_password},
    )
    token = login["authorizationToken"]

    lines = [CSV_HEADER]
    out_path.write_text(lines[0] + "\n", encoding="utf-8")

    local_before = measure_local_bytes()
    reference_before = measure_reference_bytes()
    info(f"baseline  local={local_before:,} B  reference={reference_before:,} B")

    for name in DATASETS:
        xes = datasets_dir / f"{name}.xes"
        gz = datasets_dir / f"{name}.xes.gz"
        if not gz.is_file():
            raise ScriptError(f"missing {gz}")
        xes_bytes = xes.stat().st_size
        gz_bytes = gz.stat().st_size

        import_dataset(args, token, name, gz)

        local_after = measure_local_bytes()
        reference_after = measure_reference_bytes()
        local_delta = local_after - local_before
        reference_delta = reference_after - reference_before

        rows = [
            f"{name},local,{local_before},{local_after},{local_delta},{xes_bytes},{gz_bytes},"
            f"{ratio(local_delta, xes_bytes)},{ratio(local_delta, gz_bytes)}",
            f"{name},reference,{reference_before},{reference_after},{reference_delta},"
            f"{xes_bytes},{gz_bytes},"
            f"{ratio(reference_delta, xes_bytes)},{ratio(reference_delta, gz_bytes)}",
        ]
        with out_path.open("a", encoding="utf-8") as handle:
            handle.write("\n".join(rows) + "\n")

        info(
            f"{name:<12} local +{local_delta:>12,} B ({local_delta / xes_bytes:,.2f}x XES)   "
            f"reference +{reference_delta:>12,} B ({reference_delta / xes_bytes:,.2f}x XES)"
        )

        local_before = local_after
        reference_before = reference_after

    info(f"Wrote {out_path}")
    return 0


if __name__ == "__main__":
    main_guard(main)
