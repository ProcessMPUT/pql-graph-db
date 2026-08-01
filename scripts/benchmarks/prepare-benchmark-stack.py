#!/usr/bin/env python3
"""Create the clean, symmetric Docker stack required for thesis measurements.

This command is intentionally destructive: it removes the Compose volumes, starts
only the two measured applications and their databases, creates the REFERENCE API
account without uploading compatibility fixtures, then proves that both APIs expose
zero datastores.  It avoids the ordinary development seed (`processm-init`) giving
REFERENCE hundreds of thousands of pre-existing events before a benchmark.

Run with `--confirm-destroy-volumes`; without that explicit flag the script only
prints its refusal and changes nothing.
"""

from __future__ import annotations

from pathlib import Path
import argparse
from datetime import datetime, timezone
import json
import os
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from _common import (  # noqa: E402 - path shim above must run first
    ScriptError,
    http_json,
    info,
    gradle_command,
    main_guard,
    repo_root,
    run_capture,
)

DEFAULT_LOCAL_API = "http://localhost:8080/api"
DEFAULT_REFERENCE_API = "http://localhost:80/api"
DEFAULT_LOGIN = "admin@example.com"
DEFAULT_PASSWORD = "Admin1234"
FRESH_STACK_MARKER = Path("tmp/benchmark-stack-ready.json")
BENCHMARK_COMPOSE_FILE = "docker-compose.benchmark.yml"
LOCAL_IMAGE = "processm-interpreter:local"
SOURCE_REVISION_LABEL = "org.opencontainers.image.revision"
MAX_MEASURED_SHARE_OF_DOCKER_MEMORY = 0.85
MEASURED_CONTAINERS = {
    "processm-interpreter": "processm-interpreter",
    "processm-neo4j": "processm-neo4j",
    "processm-server": "processm-server",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--confirm-destroy-volumes", action="store_true")
    parser.add_argument("--local-api", default=DEFAULT_LOCAL_API)
    parser.add_argument("--reference-api", default=DEFAULT_REFERENCE_API)
    parser.add_argument("--processm-login", default=DEFAULT_LOGIN)
    parser.add_argument("--processm-password", default=DEFAULT_PASSWORD)
    parser.add_argument("--health-timeout", type=float, default=300.0)
    parser.add_argument(
        "--reuse-local-image-id",
        metavar="SHA256",
        help=(
            "do not rebuild LOCAL; require the named processm-interpreter:local image ID "
            "and its embedded source revision to match the clean current checkout"
        ),
    )
    return parser.parse_args()


def compose(*args: str, timeout: float = 1_200.0) -> str:
    code, output = run_capture(
        ["docker", "compose", "-f", "docker-compose.yml", "-f", BENCHMARK_COMPOSE_FILE, *args],
        timeout=timeout,
    )
    if code != 0:
        raise ScriptError(f"docker compose {' '.join(args)} failed:\n{output.strip()}")
    return output.strip()


def container_memory_limits() -> dict[str, tuple[int, int]]:
    """Return (RAM, RAM+swap) cgroup limits and reject uninspectable containers."""
    limits: dict[str, tuple[int, int]] = {}
    for component, container in MEASURED_CONTAINERS.items():
        code, output = run_capture(
            ["docker", "inspect", "--format", "{{.HostConfig.Memory}} {{.HostConfig.MemorySwap}}", container],
            timeout=30.0,
        )
        fields = output.strip().split()
        if code != 0 or len(fields) != 2 or not all(field.isdigit() for field in fields):
            raise ScriptError(f"cannot read memory limits for {container}: {output.strip()}")
        memory, memory_swap = map(int, fields)
        if memory <= 0 or memory_swap != memory:
            raise ScriptError(
                f"{container} must have a finite no-swap benchmark limit; "
                f"got memory={memory}, memory+swap={memory_swap}"
            )
        limits[component] = (memory, memory_swap)
    return limits


def docker_memory_bytes() -> int:
    code, output = run_capture(["docker", "info", "--format", "{{.MemTotal}}"], timeout=30.0)
    value = output.strip()
    if code != 0 or not value.isdigit() or int(value) <= 0:
        raise ScriptError(f"cannot read Docker memory budget: {output.strip()}")
    return int(value)


def assert_symmetric_resource_budget() -> dict[str, object]:
    limits = container_memory_limits()
    local_budget = limits["processm-interpreter"][0] + limits["processm-neo4j"][0]
    reference_budget = limits["processm-server"][0]
    if local_budget != reference_budget:
        raise ScriptError(
            f"benchmark memory budgets are asymmetric: LOCAL={local_budget} B, "
            f"REFERENCE={reference_budget} B"
        )
    docker_budget = docker_memory_bytes()
    measured_total = local_budget + reference_budget
    if measured_total > docker_budget * MAX_MEASURED_SHARE_OF_DOCKER_MEMORY:
        raise ScriptError(
            f"measured containers reserve {measured_total / docker_budget:.1%} of Docker memory; "
            f"maximum is {MAX_MEASURED_SHARE_OF_DOCKER_MEMORY:.0%} so the VM/kernel retains headroom"
        )
    return {
        "policy": "equal-system-cgroup-no-swap",
        "localBytes": local_budget,
        "referenceBytes": reference_budget,
        "dockerBytes": docker_budget,
        "measuredShare": measured_total / docker_budget,
        "containerLimits": {
            component: {"memoryBytes": memory, "memorySwapBytes": memory_swap}
            for component, (memory, memory_swap) in limits.items()
        },
    }


def build_local_jar() -> None:
    """Make the Docker build consume the current source, never a stale build/libs JAR."""
    command = [*gradle_command(), "bootJar"]
    code, output = run_capture(command, timeout=1_800.0)
    if code != 0:
        raise ScriptError(f"Gradle bootJar failed before stack destruction:\n{output.strip()}")


def git_provenance() -> str:
    code, commit = run_capture(["git", "rev-parse", "HEAD"], timeout=30.0)
    if code != 0 or not commit.strip():
        raise ScriptError("cannot resolve the Git commit for stack preparation")
    status_code, status = run_capture(["git", "status", "--porcelain"], timeout=30.0)
    if status_code != 0:
        raise ScriptError("cannot verify that the Git worktree is clean")
    if status.strip():
        raise ScriptError(
            "benchmark stack preparation requires a clean Git worktree; "
            "commit the benchmark version on the side branch first"
        )
    return commit.strip()


def local_image_provenance() -> tuple[str, str]:
    code, output = run_capture(["docker", "image", "inspect", LOCAL_IMAGE], timeout=30.0)
    if code != 0:
        raise ScriptError(f"cannot inspect LOCAL image {LOCAL_IMAGE}: {output.strip()}")
    try:
        rows = json.loads(output)
        image = rows[0]
        image_id = str(image["Id"])
        labels = image.get("Config", {}).get("Labels") or {}
        revision = str(labels.get(SOURCE_REVISION_LABEL, ""))
    except (json.JSONDecodeError, IndexError, KeyError, TypeError) as error:
        raise ScriptError(f"malformed Docker inspection for {LOCAL_IMAGE}: {error}") from error
    if not image_id.startswith("sha256:"):
        raise ScriptError(f"LOCAL image has no exact sha256 ID: {image_id or '<missing>'}")
    if not revision or revision == "unknown":
        raise ScriptError(f"LOCAL image has no usable {SOURCE_REVISION_LABEL} label")
    return image_id, revision


def prepare_local_image(commit: str, reuse_image_id: str | None) -> tuple[str, str]:
    if reuse_image_id:
        if not reuse_image_id.startswith("sha256:"):
            raise ScriptError("--reuse-local-image-id must be an exact sha256:... image ID")
        image_id, revision = local_image_provenance()
        if image_id != reuse_image_id:
            raise ScriptError(
                f"LOCAL image ID differs from requested reuse image: expected {reuse_image_id}, got {image_id}"
            )
        if revision != commit:
            raise ScriptError(
                f"LOCAL image source revision differs from current Git commit: expected {commit}, got {revision}"
            )
        return image_id, "reused"

    info("Building the LOCAL executable JAR from the current source...")
    build_local_jar()
    compose("build", "--build-arg", f"BENCHMARK_SOURCE_COMMIT={commit}", "app")
    image_id, revision = local_image_provenance()
    if revision != commit:
        raise ScriptError(
            f"new LOCAL image source revision differs from current Git commit: expected {commit}, got {revision}"
        )
    return image_id, "built"


def container_image_ids() -> dict[str, str]:
    image_ids: dict[str, str] = {}
    for component, container in MEASURED_CONTAINERS.items():
        code, output = run_capture(
            ["docker", "inspect", "--format", "{{.Image}}", container],
            timeout=30.0,
        )
        image_id = output.strip().splitlines()[0] if code == 0 and output.strip() else ""
        if not image_id.startswith("sha256:"):
            raise ScriptError(f"cannot record exact image ID for {container}: {output.strip()}")
        image_ids[component] = image_id
    return image_ids


def wait_for_json(url: str, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    last_error = ""
    while time.monotonic() < deadline:
        try:
            http_json(url, timeout=10.0)
            return
        except Exception as error:  # noqa: BLE001 - preserve the last readiness diagnostic
            last_error = str(error)
            time.sleep(2.0)
    raise ScriptError(f"API did not become ready at {url}: {last_error}")


def reference_token(api: str, login: str, password: str) -> str:
    session = http_json(
        f"{api.rstrip('/')}/users/session",
        method="POST",
        body={"login": login, "password": password},
    )
    token = session.get("authorizationToken") if isinstance(session, dict) else None
    if not token:
        raise ScriptError("REFERENCE login succeeded without an authorizationToken")
    return str(token)


def assert_empty_datastores(args: argparse.Namespace) -> None:
    local_api = args.local_api.rstrip("/")
    reference_api = args.reference_api.rstrip("/")
    local = http_json(f"{local_api}/data-stores")
    token = reference_token(reference_api, args.processm_login, args.processm_password)
    reference = http_json(
        f"{reference_api}/data-stores",
        headers={"Authorization": f"Bearer {token}"},
    )
    for system, rows in (("LOCAL", local), ("REFERENCE", reference)):
        if not isinstance(rows, list):
            raise ScriptError(f"{system} datastore endpoint returned {type(rows).__name__}, expected a list")
        if rows:
            names = ", ".join(str(row.get("name", "<unnamed>")) for row in rows if isinstance(row, dict))
            raise ScriptError(f"{system} is not clean: {len(rows)} datastore(s): {names}")


def main() -> int:
    args = parse_args()
    if not args.confirm_destroy_volumes:
        raise ScriptError(
            "refusing to remove Docker volumes without --confirm-destroy-volumes; "
            "this command deletes every datastore in the Compose stack",
        )

    root = repo_root()
    info(f"Repository: {root}")
    os.chdir(root)
    marker = root / FRESH_STACK_MARKER
    # A failed preparation must never leave a proof from an older stack usable.
    marker.unlink(missing_ok=True)
    git_commit = git_provenance()
    expected_local_image_id, local_image_mode = prepare_local_image(
        git_commit,
        args.reuse_local_image_id,
    )
    compose("down", "-v", "--remove-orphans")
    # Naming the services is deliberate: ordinary `docker compose up` also starts
    # processm-init, whose compatibility fixtures would seed only REFERENCE.
    compose("up", "-d", "--no-build", "neo4j", "processm", "app")
    compose(
        "run", "--rm", "-e", "PROCESSM_SEED_DATASETS=false",
        "processm-init",
    )

    wait_for_json(f"{args.local_api.rstrip('/')}/query/features", args.health_timeout)
    assert_empty_datastores(args)
    resource_budget = assert_symmetric_resource_budget()
    image_ids = container_image_ids()
    if image_ids["processm-interpreter"] != expected_local_image_id:
        raise ScriptError(
            "running LOCAL container does not use the image verified before volume destruction: "
            f"expected {expected_local_image_id}, got {image_ids['processm-interpreter']}"
        )
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text(
        json.dumps(
            {
                "schemaVersion": 2,
                "preparationId": str(uuid.uuid4()),
                "preparedAtUtc": datetime.now(timezone.utc).isoformat(),
                "freshVolumes": True,
                "localDatastoreCount": 0,
                "referenceDatastoreCount": 0,
                "gitCommit": git_commit,
                "imageIds": image_ids,
                "localImageMode": local_image_mode,
                "localImageSourceRevision": git_commit,
                "resourceBudget": resource_budget,
                "command": "docker compose -f docker-compose.yml -f "
                f"{BENCHMARK_COMPOSE_FILE} down -v --remove-orphans",
            },
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )
    info(
        "Benchmark stack ready: both APIs expose zero datastores; "
        f"single-use proof written to {marker}.",
    )
    return 0


if __name__ == "__main__":
    main_guard(main)
