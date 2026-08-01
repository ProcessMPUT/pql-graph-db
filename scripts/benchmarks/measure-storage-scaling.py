#!/usr/bin/env python3
"""Measure durable database growth with one fresh stack per synthetic dataset.

Every point is isolated: remove volumes, start both applications without fixture
seeding, record baselines, import the same XES into both systems, wait until the log
is visible, checkpoint, and record durable bytes.  The regression intercept absorbs
per-stack initialization; no dataset can inherit a file-extension jump from the
dataset imported before it.

The command is destructive and delegates stack creation to
prepare-benchmark-stack.py. Pass --confirm-destroy-volumes explicitly.
"""

from __future__ import annotations

from pathlib import Path
import argparse
import csv
import json
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

NEO4J_CONTAINER = "processm-neo4j"
REFERENCE_CONTAINER = "processm-server"
HEALTH_TIMEOUT_SECONDS = 180.0
HEALTH_POLL_SECONDS = 3.0
UPLOAD_TIMEOUT_SECONDS = 900.0
LOG_POLL_SECONDS = 1.0
SCALING_SERIES = {"trace-scaling", "event-scaling", "attribute-scaling", "shape-scaling"}
FRESH_STACK_MARKER = Path("tmp/benchmark-stack-ready.json")

NEO4J_SIZE_COMMAND = (
    'total=0; for f in $(find /data/databases -type f 2>/dev/null); do '
    'size=$(stat -c %s "$f" 2>/dev/null || echo 0); total=$((total + size)); done; echo $total'
)
CSV_HEADER = [
    "datasetName", "system", "measurementMode", "stackPreparationId", "stackPreparedAtUtc", "gitCommit",
    "localAppImageId", "localDbImageId", "referenceImageId",
    "beforeBytes", "afterBytes", "deltaBytes",
    "xesBytes", "xesGzBytes", "deltaToXesRatio", "deltaToGzipRatio",
]
IMAGE_COLUMNS = {
    "localAppImageId": "processm-interpreter",
    "localDbImageId": "processm-neo4j",
    "referenceImageId": "processm-server",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--datasets-dir", type=Path, default=None)
    parser.add_argument("--out-csv", type=Path, default=Path("tmp/storage-scaling.csv"))
    parser.add_argument("--local-api", default="http://localhost:8080/api")
    parser.add_argument("--reference-api", default="http://localhost:80/api")
    parser.add_argument("--processm-login", default="admin@example.com")
    parser.add_argument("--processm-password", default="Admin1234")
    parser.add_argument("--confirm-destroy-volumes", action="store_true")
    parser.add_argument("--resume", action="store_true", help="retain completed two-row datasets in the output CSV")
    return parser.parse_args()


def docker(*args: str, timeout: float = 300.0) -> str:
    code, output = run_capture(["docker", *args], timeout=timeout)
    if code != 0:
        raise ScriptError(f"docker {' '.join(args)} failed: {output.strip()}")
    return output.strip()


def resolve_datasets_dir(explicit: Path | None) -> Path:
    if explicit:
        path = explicit.resolve()
        if not path.is_dir():
            raise ScriptError(f"datasets directory does not exist: {path}")
        return path
    runs_root = repo_root() / "tmp" / "benchmark-results"
    candidates = sorted(
        (child for child in runs_root.iterdir() if (child / "generated-datasets").is_dir()),
        key=lambda child: child.name,
    ) if runs_root.is_dir() else []
    if not candidates:
        raise ScriptError("no generated-datasets found; pass --datasets-dir")
    return candidates[-1] / "generated-datasets"


def dataset_names(datasets_dir: Path) -> list[str]:
    manifest = datasets_dir.parent / "datasets.csv"
    if not manifest.is_file():
        raise ScriptError(f"missing dataset manifest next to generated files: {manifest}")
    with manifest.open(newline="", encoding="utf-8-sig") as handle:
        rows = list(csv.DictReader(handle))
    names = [row["datasetName"] for row in rows if row.get("series") in SCALING_SERIES]
    missing = [name for name in names if not (datasets_dir / f"{name}.xes.gz").is_file()]
    if missing:
        raise ScriptError("missing generated XES files: " + ", ".join(missing))
    return names


def anchor_provenance(datasets_dir: Path) -> tuple[dict[str, str], str]:
    environment_path = datasets_dir.parent / "environment.json"
    try:
        environment = json.loads(environment_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ScriptError(f"cannot read anchor environment {environment_path}: {error}") from error
    containers = environment.get("containers") if isinstance(environment, dict) else None
    if not isinstance(containers, dict):
        raise ScriptError("anchor environment has no container provenance")
    image_ids = {}
    for component in IMAGE_COLUMNS.values():
        data = containers.get(component)
        image_ids[component] = str(data.get("imageId", "")) if isinstance(data, dict) else ""
    missing = [component for component, image_id in image_ids.items() if not image_id.startswith("sha256:")]
    if missing:
        raise ScriptError("anchor environment has no exact image ID for: " + ", ".join(missing))
    source = environment.get("source")
    git_commit = str(source.get("gitCommit", "")) if isinstance(source, dict) else ""
    if not git_commit:
        raise ScriptError("anchor environment has no Git commit")
    return image_ids, git_commit


def prepare_clean_stack(args: argparse.Namespace, expected_local_image_id: str) -> dict[str, object]:
    script = Path(__file__).with_name("prepare-benchmark-stack.py")
    command = [
        sys.executable, str(script), "--confirm-destroy-volumes",
        "--local-api", args.local_api, "--reference-api", args.reference_api,
        "--processm-login", args.processm_login, "--processm-password", args.processm_password,
        "--reuse-local-image-id", expected_local_image_id,
    ]
    code, output = run_capture(command, timeout=1_800.0)
    if code != 0:
        raise ScriptError(f"clean stack preparation failed:\n{output.strip()}")
    # This stack is consumed by the storage probe, not by BenchmarkRunner. Remove
    # the single-use benchmark marker so a later timing run cannot mistake a used
    # storage-probe stack for a pristine one.
    marker = repo_root() / FRESH_STACK_MARKER
    try:
        proof = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ScriptError(f"fresh-stack proof is missing or malformed: {error}") from error
    marker.unlink(missing_ok=True)
    if not isinstance(proof, dict) or proof.get("freshVolumes") is not True:
        raise ScriptError("fresh-stack proof does not confirm removed volumes")
    return proof


def measure_local_bytes() -> int:
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
    docker("exec", REFERENCE_CONTAINER, "sh", "-c", "psql -U postgres -c 'CHECKPOINT;'")
    output = docker(
        "exec", REFERENCE_CONTAINER, "sh", "-c",
        "psql -U postgres -t -A -c 'SELECT sum(pg_database_size(datname)) FROM pg_database;'",
    )
    return int(output.splitlines()[0].strip())


def logs_list(value: object) -> list[object]:
    if isinstance(value, list):
        return value
    if isinstance(value, dict) and isinstance(value.get("data"), list):
        return value["data"]
    return []


def wait_for_log(api: str, store_id: str, headers: dict[str, str] | None = None) -> None:
    deadline = time.monotonic() + UPLOAD_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        if logs_list(http_json(f"{api}/data-stores/{store_id}/logs", headers=headers)):
            return
        time.sleep(LOG_POLL_SECONDS)
    raise ScriptError(f"log did not materialize in datastore {store_id} within {UPLOAD_TIMEOUT_SECONDS}s")


def reference_token(args: argparse.Namespace) -> str:
    session = http_json(
        f"{args.reference_api.rstrip('/')}/users/session", method="POST",
        body={"login": args.processm_login, "password": args.processm_password},
    )
    token = session.get("authorizationToken") if isinstance(session, dict) else None
    if not token:
        raise ScriptError("REFERENCE login returned no authorizationToken")
    return str(token)


def import_dataset(args: argparse.Namespace, name: str, gz_path: Path) -> None:
    local_api = args.local_api.rstrip("/")
    local_store = http_json(f"{local_api}/data-stores", method="POST", body={"name": f"storage-{name}"})["id"]
    status = http_post_file(f"{local_api}/data-stores/{local_store}/logs", gz_path, timeout=UPLOAD_TIMEOUT_SECONDS)
    if status != 201:
        raise ScriptError(f"LOCAL import {name} failed: HTTP {status}")
    wait_for_log(local_api, local_store)

    reference_api = args.reference_api.rstrip("/")
    auth = {"Authorization": f"Bearer {reference_token(args)}"}
    reference_store = http_json(
        f"{reference_api}/data-stores", method="POST", body={"name": f"storage-{name}"}, headers=auth,
    )["id"]
    status = http_post_file(
        f"{reference_api}/data-stores/{reference_store}/logs", gz_path,
        headers=auth, timeout=UPLOAD_TIMEOUT_SECONDS,
    )
    if not 200 <= status < 300:
        raise ScriptError(f"REFERENCE import {name} failed: HTTP {status}")
    wait_for_log(reference_api, reference_store, auth)


def ratio(delta: int, baseline: int) -> str:
    if baseline == 0:
        return "0"
    return f"{delta / baseline:.6f}".rstrip("0").rstrip(".")


def resumable_rows(
    path: Path,
    git_commit: str,
    expected_image_ids: dict[str, str],
) -> list[dict[str, str]]:
    if not path.is_file():
        return []
    with path.open(newline="", encoding="utf-8-sig") as handle:
        rows = list(csv.DictReader(handle))
    by_name: dict[str, list[dict[str, str]]] = {}
    for row in rows:
        by_name.setdefault(row.get("datasetName", ""), []).append(row)
    retained: list[dict[str, str]] = []
    for dataset_rows in by_name.values():
        proof_ids = {row.get("stackPreparationId", "") for row in dataset_rows}
        if (
            len(dataset_rows) == 2
            and {row.get("system") for row in dataset_rows} == {"local", "reference"}
            and all(row.get("measurementMode") == "isolated-fresh-stack" for row in dataset_rows)
            and len(proof_ids) == 1
            and "" not in proof_ids
            and all(row.get("stackPreparedAtUtc") for row in dataset_rows)
            and all(row.get("gitCommit") == git_commit for row in dataset_rows)
            and all(
                row.get(column) == expected_image_ids[component]
                for row in dataset_rows
                for column, component in IMAGE_COLUMNS.items()
            )
            and all((float(row.get("deltaBytes", "0")) if row.get("deltaBytes", "").isdigit() else 0) > 0 for row in dataset_rows)
        ):
            retained.extend(dataset_rows)
    return retained


def main() -> int:
    args = parse_args()
    if not args.confirm_destroy_volumes:
        raise ScriptError("pass --confirm-destroy-volumes; every measured point recreates Docker volumes")
    datasets_dir = resolve_datasets_dir(args.datasets_dir)
    names = dataset_names(datasets_dir)
    expected_image_ids, expected_git_commit = anchor_provenance(datasets_dir)
    out_path = args.out_csv if args.out_csv.is_absolute() else repo_root() / args.out_csv
    out_path.parent.mkdir(parents=True, exist_ok=True)
    git_code, git_commit = run_capture(["git", "rev-parse", "HEAD"], timeout=30.0)
    if git_code != 0 or not git_commit.strip():
        raise ScriptError("cannot resolve the Git commit for storage-resume validation")
    if git_commit.strip() != expected_git_commit:
        raise ScriptError(
            f"current Git commit differs from the anchor run: "
            f"expected {expected_git_commit}, got {git_commit.strip()}"
        )
    retained = resumable_rows(out_path, git_commit.strip(), expected_image_ids) if args.resume else []
    done = {row["datasetName"] for row in retained}
    with out_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_HEADER)
        writer.writeheader()
        writer.writerows({field: row.get(field, "") for field in CSV_HEADER} for row in retained)

    for index, name in enumerate(names, start=1):
        if name in done:
            info(f"[{index}/{len(names)}] {name}: already complete, skipping")
            continue
        xes = datasets_dir / f"{name}.xes"
        gz = datasets_dir / f"{name}.xes.gz"
        info(f"[{index}/{len(names)}] {name}: preparing isolated stack")
        proof = prepare_clean_stack(args, expected_image_ids["processm-interpreter"])
        if proof.get("gitCommit") != expected_git_commit:
            raise ScriptError(
                f"isolated stack Git commit differs from the anchor run: "
                f"expected {expected_git_commit}, got {proof.get('gitCommit')}"
            )
        if proof.get("imageIds") != expected_image_ids:
            raise ScriptError(
                f"isolated stack image IDs differ from the anchor run: "
                f"expected {expected_image_ids}, got {proof.get('imageIds')}"
            )
        local_before = measure_local_bytes()
        reference_before = measure_reference_bytes()
        import_dataset(args, name, gz)
        local_after = measure_local_bytes()
        reference_after = measure_reference_bytes()
        xes_bytes = xes.stat().st_size
        gz_bytes = gz.stat().st_size
        rows = []
        for system, before, after in (
            ("local", local_before, local_after),
            ("reference", reference_before, reference_after),
        ):
            delta = after - before
            if delta <= 0:
                raise ScriptError(f"isolated {system}/{name} produced non-positive durable delta {delta} B")
            rows.append([
                name, system, "isolated-fresh-stack", proof.get("preparationId", ""),
                proof.get("preparedAtUtc", ""), proof.get("gitCommit", ""),
                proof["imageIds"]["processm-interpreter"],
                proof["imageIds"]["processm-neo4j"],
                proof["imageIds"]["processm-server"],
                before, after, delta,
                xes_bytes, gz_bytes, ratio(delta, xes_bytes), ratio(delta, gz_bytes),
            ])
        with out_path.open("a", encoding="utf-8", newline="") as handle:
            csv.writer(handle).writerows(rows)
        info(
            f"[{index}/{len(names)}] {name}: "
            f"local +{local_after - local_before:,} B; "
            f"reference +{reference_after - reference_before:,} B"
        )

    info(f"Storage measurements written to {out_path}")
    return 0


if __name__ == "__main__":
    main_guard(main)
