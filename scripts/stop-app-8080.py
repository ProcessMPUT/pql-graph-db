#!/usr/bin/env python3
"""Stop the application listening on a port.

Stops only the app on the configured port; it never rebuilds or restarts, and
never touches processes on other ports. Exit code 0 when the port is free, 1
when something is still listening after the grace period.
"""

from __future__ import annotations

from pathlib import Path
import argparse
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import (  # noqa: E402 - path shim above must run first
    info,
    listening_pid,
    main_guard,
    repo_root,
    terminate,
    wait_for_port_free,
    warn,
)

SHUTDOWN_TIMEOUT_SECONDS = 10.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8080)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    port = args.port
    pid_file = repo_root() / "build" / f"bootrun-{port}.pid"

    if pid_file.is_file():
        try:
            runner_pid = int(pid_file.read_text(encoding="utf-8").strip().splitlines()[0])
        except (ValueError, IndexError):
            runner_pid = 0
        if runner_pid > 0:
            info(f"Stopping bootRun process tree, PID {runner_pid}...")
            terminate(runner_pid, tree=True)
        pid_file.unlink(missing_ok=True)

    pid = listening_pid(port)
    if not pid:
        info(f"No app is listening on port {port}.")
        return 0

    info(f"Stopping app on port {port}, PID {pid}...")
    terminate(pid)

    if wait_for_port_free(port, SHUTDOWN_TIMEOUT_SECONDS):
        info(f"App on port {port} stopped.")
        return 0

    warn(f"App on port {port} did not stop within {SHUTDOWN_TIMEOUT_SECONDS:.0f} seconds.")
    return 1


if __name__ == "__main__":
    main_guard(main)
