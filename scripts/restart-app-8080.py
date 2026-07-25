#!/usr/bin/env python3
"""Restart the application on a port via Gradle `bootRun`.

Replaces the previous app on the configured port only; unrelated processes on
other ports are never touched. Logs and the PID file go under `tmp/` and
`build/`.

The PowerShell version needed a generated `.cmd` wrapper plus `taskkill /T` to
stop the Gradle process together with the Java process it spawns. Here the
child is started in its own session, so it is a process-group leader and the
whole tree is signalled directly — no wrapper file is produced.
"""

from __future__ import annotations

from pathlib import Path
import argparse
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _common import (  # noqa: E402 - path shim above must run first
    ScriptError,
    gradle_command,
    info,
    is_windows,
    listening_pid,
    main_guard,
    repo_root,
    terminate,
    wait_for_port,
    wait_for_port_free,
    warn,
)

STARTUP_TIMEOUT_SECONDS = 60.0
SHUTDOWN_TIMEOUT_SECONDS = 10.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8080)
    return parser.parse_args()


def tail(path: Path, lines: int) -> list[str]:
    if not path.is_file():
        return []
    return path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:]


def stop_previous(port: int, pid_file: Path) -> None:
    if pid_file.is_file():
        try:
            runner_pid = int(pid_file.read_text(encoding="utf-8").strip().splitlines()[0])
        except (ValueError, IndexError):
            runner_pid = 0
        if runner_pid > 0:
            info(f"Stopping previous bootRun process tree, PID {runner_pid}...")
            terminate(runner_pid, tree=True)
        pid_file.unlink(missing_ok=True)

    pid = listening_pid(port)
    if pid:
        info(f"Stopping process on port {port}, PID {pid}...")
        terminate(pid)
        if not wait_for_port_free(port, SHUTDOWN_TIMEOUT_SECONDS):
            warn(f"port {port} is still in use after {SHUTDOWN_TIMEOUT_SECONDS:.0f}s")
    else:
        info(f"No process is listening on port {port}.")


def main() -> int:
    args = parse_args()
    port = args.port
    root = repo_root()
    out_log = root / "tmp" / f"bootRun-{port}.log"
    err_log = root / "tmp" / f"bootRun-{port}.err.log"
    pid_file = root / "build" / f"bootrun-{port}.pid"

    info(f"Restarting ProcessM Interpreter on port {port}...")
    stop_previous(port, pid_file)

    out_log.parent.mkdir(parents=True, exist_ok=True)
    pid_file.parent.mkdir(parents=True, exist_ok=True)
    out_log.unlink(missing_ok=True)
    err_log.unlink(missing_ok=True)

    info("Starting Gradle bootRun...")
    info(f"stdout: {out_log}")
    info(f"stderr: {err_log}")

    command = gradle_command() + ["--no-daemon", "bootRun"]
    with out_log.open("wb") as out_handle, err_log.open("wb") as err_handle:
        try:
            process = subprocess.Popen(
                command,
                cwd=root,
                stdout=out_handle,
                stderr=err_handle,
                stdin=subprocess.DEVNULL,
                # New session on POSIX / new process group on Windows: makes the
                # child a group leader so stopping it also stops the JVM it forks.
                start_new_session=not is_windows(),
                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if is_windows() else 0,
            )
        except FileNotFoundError:
            raise ScriptError(f"cannot run {command[0]}") from None

    pid_file.write_text(str(process.pid), encoding="utf-8")

    info(f"Waiting for http://localhost:{port} ...")
    if wait_for_port(port, STARTUP_TIMEOUT_SECONDS):
        info(f"App is listening on http://localhost:{port}")
        return 0

    warn(f"App did not start listening on port {port} within {STARTUP_TIMEOUT_SECONDS:.0f} seconds.")
    if process.poll() is not None:
        warn(f"bootRun already exited with code {process.returncode}.")
    warn("Check logs:")
    warn(f"  {out_log}")
    warn(f"  {err_log}")
    for label, path, count in (("stderr", err_log, 40), ("stdout", out_log, 80)):
        lines = tail(path, count)
        if lines:
            warn(f"{label} tail:")
            for line in lines:
                warn(f"  {line}")
    return 1


if __name__ == "__main__":
    main_guard(main)
