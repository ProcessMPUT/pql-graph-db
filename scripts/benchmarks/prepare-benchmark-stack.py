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
    return parser.parse_args()


def compose(*args: str, timeout: float = 1_200.0) -> str:
    code, output = run_capture(["docker", "compose", *args], timeout=timeout)
    if code != 0:
        raise ScriptError(f"docker compose {' '.join(args)} failed:\n{output.strip()}")
    return output.strip()


def build_local_jar() -> None:
    """Make the Docker build consume the current source, never a stale build/libs JAR."""
    command = [*gradle_command(), "bootJar"]
    code, output = run_capture(command, timeout=1_800.0)
    if code != 0:
        raise ScriptError(f"Gradle bootJar failed before stack destruction:\n{output.strip()}")


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
    info("Building the LOCAL executable JAR from the current source...")
    build_local_jar()
    compose("down", "-v", "--remove-orphans")
    # Naming the services is deliberate: ordinary `docker compose up` also starts
    # processm-init, whose compatibility fixtures would seed only REFERENCE.
    compose("up", "-d", "--build", "neo4j", "processm", "app")
    compose(
        "run", "--rm", "-e", "PROCESSM_SEED_DATASETS=false",
        "processm-init",
    )

    wait_for_json(f"{args.local_api.rstrip('/')}/query/features", args.health_timeout)
    assert_empty_datastores(args)
    image_ids = container_image_ids()
    git_code, git_commit = run_capture(["git", "rev-parse", "HEAD"], timeout=30.0)
    if git_code != 0 or not git_commit.strip():
        raise ScriptError("cannot record the Git commit for the fresh-stack marker")
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
                "gitCommit": git_commit.strip(),
                "imageIds": image_ids,
                "command": "docker compose down -v --remove-orphans",
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
