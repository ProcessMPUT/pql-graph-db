"""Shared helpers for the repository's operational scripts.

Standard library only, on purpose: the scripts under `scripts/` must run on a
bare Python 3 with no virtualenv and no `pip install` step, on macOS, Linux and
Windows alike. Do not add third-party imports here.

Conventions this module exists to keep identical across scripts:

- repository root is resolved from this file, never from the caller's cwd;
- generated output goes under `<repo>/tmp/`;
- messages go to stderr, script results go to stdout;
- failures raise `ScriptError` and exit non-zero with an actionable message.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence
import json
import mimetypes
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

__all__ = [
    "ScriptError",
    "repo_root",
    "tmp_dir",
    "info",
    "warn",
    "die",
    "main_guard",
    "http_json",
    "http_post_file",
    "listening_pid",
    "wait_for_port",
    "wait_for_port_free",
    "terminate",
    "read_json",
    "write_text",
    "safe_filename",
    "run_capture",
    "is_windows",
]


class ScriptError(RuntimeError):
    """Expected, actionable failure. Reported without a traceback."""


def is_windows() -> bool:
    return os.name == "nt"


# --------------------------------------------------------------------------
# paths
# --------------------------------------------------------------------------

def repo_root() -> Path:
    """Repository root, resolved from this file rather than the caller's cwd."""
    return Path(__file__).resolve().parent.parent


def tmp_dir(*parts: str) -> Path:
    """A path under `<repo>/tmp`, with parent directories created."""
    path = repo_root().joinpath("tmp", *parts)
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


# --------------------------------------------------------------------------
# console
# --------------------------------------------------------------------------

def info(message: str = "") -> None:
    print(message, file=sys.stderr, flush=True)


def warn(message: str) -> None:
    print(f"warning: {message}", file=sys.stderr, flush=True)


def die(message: str) -> None:
    raise ScriptError(message)


def main_guard(main: Callable[[], int | None]) -> None:
    """Run `main`, turning ScriptError into a clean message and exit code 1."""
    try:
        raise SystemExit(main() or 0)
    except ScriptError as error:
        print(f"error: {error}", file=sys.stderr, flush=True)
        raise SystemExit(1) from None
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr, flush=True)
        raise SystemExit(130) from None


# --------------------------------------------------------------------------
# HTTP (urllib; no `requests` dependency)
# --------------------------------------------------------------------------

def http_json(
    url: str,
    method: str = "GET",
    body: Any = None,
    headers: Mapping[str, str] | None = None,
    timeout: float = 60.0,
) -> Any:
    """Send an optional JSON body, parse a JSON response.

    Mirrors `Invoke-RestMethod`: non-2xx raises, and the raised message carries
    the response body, which is where the API puts its actual complaint.
    """
    data = None
    request_headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    if headers:
        request_headers.update(headers)

    request = urllib.request.Request(url, data=data, method=method.upper(), headers=request_headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read()
    except urllib.error.HTTPError as error:
        detail = ""
        try:
            detail = error.read().decode("utf-8", "replace").strip()
        except Exception:  # noqa: BLE001 - diagnostics must never mask the HTTP error
            pass
        suffix = f": {detail}" if detail else ""
        raise ScriptError(f"HTTP {error.code} from {method.upper()} {url}{suffix}") from None
    except urllib.error.URLError as error:
        raise ScriptError(f"cannot reach {url} ({error.reason})") from None

    if not payload.strip():
        return None
    return json.loads(payload.decode("utf-8"))


def http_post_file(
    url: str,
    file_path: Path,
    field: str = "file",
    headers: Mapping[str, str] | None = None,
    timeout: float = 900.0,
) -> int:
    """POST a single file as multipart/form-data; return the HTTP status code.

    Replaces the `curl.exe -F file=@...` shell-out, so no external binary is
    needed. The whole file is read into memory, which is what curl effectively
    did here too; the datasets involved are the generated scaling ones.
    """
    file_path = Path(file_path)
    if not file_path.is_file():
        raise ScriptError(f"missing upload file: {file_path}")

    boundary = f"----pql{uuid.uuid4().hex}"
    content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
    prologue = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field}"; filename="{file_path.name}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8")
    epilogue = f"\r\n--{boundary}--\r\n".encode("utf-8")
    data = prologue + file_path.read_bytes() + epilogue

    request_headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
    if headers:
        request_headers.update(headers)

    request = urllib.request.Request(url, data=data, method="POST", headers=request_headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return int(response.status)
    except urllib.error.HTTPError as error:
        return int(error.code)
    except urllib.error.URLError as error:
        raise ScriptError(f"cannot reach {url} ({error.reason})") from None


# --------------------------------------------------------------------------
# processes and ports
# --------------------------------------------------------------------------

def run_capture(command: Sequence[str], timeout: float = 60.0) -> tuple[int, str]:
    """Run a command, capture stdout+stderr. Never raises on a non-zero exit."""
    try:
        completed = subprocess.run(
            list(command),
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError:
        return 127, f"{command[0]}: not found"
    except subprocess.TimeoutExpired:
        return 124, f"{command[0]}: timed out after {timeout}s"
    return completed.returncode, (completed.stdout or "") + (completed.stderr or "")


def listening_pid(port: int) -> int | None:
    """PID listening on `port`, or None.

    Windows keeps the original `netstat -ano` parse. POSIX prefers `lsof`, and
    falls back to `ss`, because neither is guaranteed to be installed.
    """
    if is_windows():
        code, output = run_capture(["netstat", "-ano"])
        if code != 0:
            return None
        for line in output.splitlines():
            parts = line.split()
            if len(parts) >= 5 and parts[1].endswith(f":{port}") and parts[3] == "LISTENING":
                try:
                    return int(parts[-1])
                except ValueError:
                    continue
        return None

    # Decide on the tool by availability, not by exit code: `lsof -t` exits 1
    # when nothing matches, which is the ordinary "port is free" answer and
    # must not be mistaken for a missing tool.
    if shutil.which("lsof"):
        _, output = run_capture(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"])
        for line in output.split():
            try:
                return int(line)
            except ValueError:
                continue
        return None

    if shutil.which("ss"):
        _, output = run_capture(["ss", "-ltnpH", f"sport = :{port}"])
        match = re.search(r"pid=(\d+)", output)
        return int(match.group(1)) if match else None

    warn(f"cannot determine the listener on port {port}: neither lsof nor ss is available")
    return None


def _alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if is_windows():
        code, output = run_capture(["tasklist", "/FI", f"PID eq {pid}", "/NH"])
        return code == 0 and str(pid) in output

    # A killed child stays a zombie until it is reaped, and `kill(pid, 0)` keeps
    # succeeding for it. Reap first when the process is ours, so a terminate()
    # of our own child does not sit out the whole grace period against a corpse.
    try:
        reaped, _ = os.waitpid(pid, os.WNOHANG)
        if reaped == pid:
            return False
    except ChildProcessError:
        pass  # not our child; fall through to the signal probe
    except OSError:
        pass

    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def terminate(pid: int, tree: bool = False, grace: float = 5.0) -> None:
    """Stop a process, optionally with its children.

    `tree=True` is the equivalent of `taskkill /T`: on POSIX it signals the
    process group, which only works when the process was started with
    `start_new_session=True` (as `restart-app-8080.py` does) and is therefore
    its own group leader. Falling back to a plain signal keeps a stale or
    foreign PID from taking this script's own process group down with it.
    """
    if pid <= 0 or not _alive(pid):
        return

    if is_windows():
        args = ["taskkill", "/PID", str(pid), "/F"]
        if tree:
            args.insert(3, "/T")
        run_capture(args)
        return

    target_group = None
    if tree:
        try:
            if os.getpgid(pid) == pid:
                target_group = pid
        except ProcessLookupError:
            return

    def signal_with(sig: int) -> None:
        try:
            if target_group is not None:
                os.killpg(target_group, sig)
            else:
                os.kill(pid, sig)
        except (ProcessLookupError, PermissionError):
            pass

    signal_with(signal.SIGTERM)
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        if not _alive(pid):
            return
        time.sleep(0.2)
    signal_with(signal.SIGKILL)


def wait_for_port(port: int, timeout: float, interval: float = 1.0) -> int | None:
    """Wait until something listens on `port`; return its PID or None."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        pid = listening_pid(port)
        if pid:
            return pid
        time.sleep(interval)
    return listening_pid(port)


def wait_for_port_free(port: int, timeout: float, interval: float = 0.5) -> bool:
    """Wait until nothing listens on `port`. True if it became free."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not listening_pid(port):
            return True
        time.sleep(interval)
    return not listening_pid(port)


# --------------------------------------------------------------------------
# files
# --------------------------------------------------------------------------

def read_json(path: Path, what: str = "file") -> Any:
    path = Path(path)
    if not path.is_file():
        raise ScriptError(f"missing {what}: {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ScriptError(f"{path} is not valid JSON: {error}") from None


def write_text(path: Path, text: str) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


_UNSAFE = re.compile(r"[^a-zA-Z0-9._-]+")


def safe_filename(value: str) -> str:
    safe = _UNSAFE.sub("_", value or "").strip("_")
    return safe or "value"


def gradle_command() -> list[str]:
    """The Gradle wrapper invocation for this host."""
    if is_windows():
        return [str(repo_root() / "gradlew.bat")]
    wrapper = repo_root() / "gradlew"
    if not os.access(wrapper, os.X_OK):
        raise ScriptError(f"{wrapper} is not executable; run: chmod +x {wrapper}")
    return [str(wrapper)]


def describe_host() -> str:
    return f"{platform.system()} {platform.release()} / Python {platform.python_version()}"
