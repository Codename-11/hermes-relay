"""
hermes-relay plugin — registers ``desktop_*`` tools into the hermes-agent registry.

Tools registered (Phase B + remote-PC ergonomics, alpha.7):

  Filesystem:
    - desktop_read_file        read a file from the connected desktop client
    - desktop_write_file       write a file on the client
    - desktop_search_files     ripgrep-backed file + content search
    - desktop_patch            apply a unified diff to a file

  Shell:
    - desktop_terminal         run a shell command (bash/cmd) — bounded 30s
    - desktop_powershell       run a PowerShell script via stdin (no quote-mangling)

  Process management:
    - desktop_spawn_detached   fire-and-forget process; returns pid + log path
    - desktop_list_processes   filter by command substring
    - desktop_kill_process     by pid or name
    - desktop_find_pid_by_port port → owning pid

  Job API (long-running tasks with persistent logs):
    - desktop_job_start        kick off a background job → {job_id, pid, logs}
    - desktop_job_status       state / exit_code / metadata
    - desktop_job_logs         tail stdout/stderr with offset+limit
    - desktop_job_cancel       TERM (or KILL with force=true)
    - desktop_job_list         enumerate jobs known to the client

  File transfer:
    - desktop_copy_directory   recursive copy
    - desktop_zip / _unzip     bundle/expand archives
    - desktop_checksum         sha256/sha1/md5 of a file

  Diagnostics:
    - desktop_health           connected client name, uptime, advertised tools,
                                last error — answered by the relay (no client RTT)

Architecture mirrors ``android_tool.py``:

    Tools ──HTTP──> Unified Relay (localhost:8767) ──WSS desktop channel──> Desktop CLI

Each handler POSTs to ``/desktop/<tool_name>`` on the relay. The relay
forwards a ``desktop.command`` envelope to the connected desktop client
(see ``plugin/relay/channels/desktop.py``), awaits a ``desktop.response``,
and returns the structured result.

``check_fn`` pings ``/desktop/_ping?tool=<name>`` — 200 if a client is
connected and advertises the tool, 503 otherwise. This is how Hermes
becomes aware: with no client, the tool fails closed and the LLM learns
to stop calling it.

``desktop_health`` is the one tool that does NOT round-trip to the client
— the relay already has the client's heartbeat-advertised metadata, so we
hit ``GET /desktop/health`` directly. This means the tool is callable even
when ``desktop_terminal`` would time out — useful for debugging.
"""

from __future__ import annotations

import json
import os
from typing import Any, Optional

import requests


# ── Config ────────────────────────────────────────────────────────────────────


def _relay_url() -> str:
    """URL of the unified relay. Defaults to localhost:8767."""
    return os.getenv("DESKTOP_RELAY_URL", "http://localhost:8767")


def _relay_token() -> Optional[str]:
    """Bearer for authenticated relay calls. Loopback skips auth on the
    relay, so this is typically unset on the agent host."""
    return os.getenv("DESKTOP_RELAY_TOKEN")


def _timeout() -> float:
    return float(os.getenv("DESKTOP_TOOL_TIMEOUT", "35"))


def _auth_headers() -> dict:
    token = _relay_token()
    if token:
        return {"Authorization": f"Bearer {token}"}
    return {}


def _post(path: str, payload: dict) -> dict:
    """POST ``payload`` to ``<relay>/desktop/<path>`` and return the JSON body.

    Surface network / HTTP failures as structured ``{"error": ...}`` dicts
    so the tool handlers can always return JSON to Hermes.
    """
    try:
        r = requests.post(
            f"{_relay_url()}{path}",
            json=payload,
            headers=_auth_headers(),
            timeout=_timeout(),
        )
    except requests.RequestException as exc:
        return {"error": f"Relay unreachable: {exc}"}
    # Error bodies still come back as JSON (relay's ``_desktop_error_response``).
    try:
        data = r.json()
    except ValueError:
        data = {"error": f"Non-JSON response from relay (HTTP {r.status_code})"}
    if r.status_code >= 400 and "error" not in data:
        data["error"] = f"HTTP {r.status_code}"
    return data


def _get(path: str, params: Optional[dict] = None) -> dict:
    """GET helper for relay-side endpoints (``/desktop/health``, ``/_ping``).

    Mirrors ``_post`` shape — always returns a dict, never raises. Used by
    ``desktop_health`` since the data is on the relay (no client round-trip).
    """
    try:
        r = requests.get(
            f"{_relay_url()}{path}",
            params=params or {},
            headers=_auth_headers(),
            timeout=_timeout(),
        )
    except requests.RequestException as exc:
        return {"error": f"Relay unreachable: {exc}"}
    try:
        data = r.json()
    except ValueError:
        data = {"error": f"Non-JSON response from relay (HTTP {r.status_code})"}
    if r.status_code >= 400 and "error" not in data:
        data["error"] = f"HTTP {r.status_code}"
    return data


def _check_tool(tool_name: str) -> bool:
    """Returns True if a desktop client is connected AND advertises ``tool_name``.

    Hits ``/desktop/_ping?tool=<tool_name>``. 200 = available, 503 = no
    client / tool not advertised.
    """
    try:
        r = requests.get(
            f"{_relay_url()}/desktop/_ping",
            params={"tool": tool_name},
            headers=_auth_headers(),
            timeout=2,
        )
        return r.status_code == 200
    except Exception:
        return False


def _check_relay() -> bool:
    """``check_fn`` for ``desktop_health`` — the relay must be reachable, but
    a client need not be connected. The whole point of ``desktop_health`` is
    to tell the agent whether a client IS connected, so it must remain callable
    when one is not."""
    try:
        r = requests.get(
            f"{_relay_url()}/desktop/health",
            headers=_auth_headers(),
            timeout=2,
        )
        return r.status_code == 200
    except Exception:
        return False


# ── Tool implementations ───────────────────────────────────────────────────────


def desktop_read_file(path: str, max_bytes: int = 1_000_000) -> str:
    """Read a file from the desktop client's filesystem."""
    data = _post("/desktop/desktop_read_file", {"path": path, "max_bytes": int(max_bytes)})
    if isinstance(data, dict):
        if "error" in data:
            return json.dumps({"error": data["error"]})
        if "content" in data and isinstance(data["content"], str):
            return data["content"]
    return json.dumps(data)


def desktop_write_file(path: str, content: str, create_dirs: bool = False) -> str:
    """Write ``content`` to ``path`` on the desktop client."""
    data = _post(
        "/desktop/desktop_write_file",
        {"path": path, "content": content, "create_dirs": bool(create_dirs)},
    )
    return json.dumps(data)


def desktop_search_files(
    pattern: str,
    cwd: Optional[str] = None,
    max_results: int = 100,
) -> str:
    """Ripgrep-backed file/content search on the desktop client."""
    payload: dict[str, Any] = {"pattern": pattern, "max_results": int(max_results)}
    if cwd is not None:
        payload["cwd"] = cwd
    data = _post("/desktop/desktop_search_files", payload)
    return json.dumps(data)


def desktop_terminal(
    command: str,
    cwd: Optional[str] = None,
    timeout: int = 30,
) -> str:
    """Run a shell command on the desktop client."""
    payload: dict[str, Any] = {"command": command, "timeout": int(timeout)}
    if cwd is not None:
        payload["cwd"] = cwd
    data = _post("/desktop/desktop_terminal", payload)
    return json.dumps(data)


def desktop_patch(path: str, patch: str) -> str:
    """Apply a unified-diff ``patch`` to ``path`` on the desktop client."""
    data = _post("/desktop/desktop_patch", {"path": path, "patch": patch})
    return json.dumps(data)


# ── Shell — PowerShell ────────────────────────────────────────────────────────


def desktop_powershell(
    script: str,
    cwd: Optional[str] = None,
    timeout: int = 30,
    prefer: Optional[str] = None,
) -> str:
    """Run a PowerShell script via the desktop client."""
    payload: dict[str, Any] = {"script": script, "timeout": int(timeout)}
    if cwd is not None:
        payload["cwd"] = cwd
    if prefer is not None:
        payload["prefer"] = prefer
    data = _post("/desktop/desktop_powershell", payload)
    return json.dumps(data)


# ── Process management ────────────────────────────────────────────────────────


def desktop_spawn_detached(
    command: str,
    cwd: Optional[str] = None,
    env: Optional[dict[str, str]] = None,
    job_id: Optional[str] = None,
) -> str:
    """Spawn a long-lived process detached from the relay's 30s RPC window."""
    payload: dict[str, Any] = {"command": command}
    if cwd is not None:
        payload["cwd"] = cwd
    if env is not None:
        payload["env"] = env
    if job_id is not None:
        payload["job_id"] = job_id
    data = _post("/desktop/desktop_spawn_detached", payload)
    return json.dumps(data)


def desktop_list_processes(
    filter: Optional[str] = None,
    limit: int = 200,
) -> str:
    """List processes on the desktop client; filter by command substring."""
    payload: dict[str, Any] = {"limit": int(limit)}
    if filter is not None:
        payload["filter"] = filter
    data = _post("/desktop/desktop_list_processes", payload)
    return json.dumps(data)


def desktop_kill_process(
    pid: Optional[int] = None,
    name: Optional[str] = None,
    force: bool = False,
    signal: Optional[str] = None,
) -> str:
    """Kill a process by pid (preferred) or by name (best-effort)."""
    payload: dict[str, Any] = {"force": bool(force)}
    if pid is not None:
        payload["pid"] = int(pid)
    if name is not None:
        payload["name"] = name
    if signal is not None:
        payload["signal"] = signal
    data = _post("/desktop/desktop_kill_process", payload)
    return json.dumps(data)


def desktop_find_pid_by_port(
    port: int,
    protocol: str = "tcp",
) -> str:
    """Find which process is listening on ``port`` on the desktop client."""
    data = _post(
        "/desktop/desktop_find_pid_by_port",
        {"port": int(port), "protocol": protocol},
    )
    return json.dumps(data)


# ── Job API ───────────────────────────────────────────────────────────────────


def desktop_job_start(
    command: str,
    cwd: Optional[str] = None,
    env: Optional[dict[str, str]] = None,
    stdin: Optional[str] = None,
    job_id: Optional[str] = None,
) -> str:
    """Start a background job. Returns immediately with job_id + log paths."""
    payload: dict[str, Any] = {"command": command}
    if cwd is not None:
        payload["cwd"] = cwd
    if env is not None:
        payload["env"] = env
    if stdin is not None:
        payload["stdin"] = stdin
    if job_id is not None:
        payload["job_id"] = job_id
    data = _post("/desktop/desktop_job_start", payload)
    return json.dumps(data)


def desktop_job_status(job_id: str) -> str:
    """Poll a job's state — running / exited / killed / failed / unknown."""
    data = _post("/desktop/desktop_job_status", {"job_id": job_id})
    return json.dumps(data)


def desktop_job_logs(
    job_id: str,
    stream: str = "both",
    offset: Optional[int] = None,
    limit: int = 8192,
) -> str:
    """Read tail (or windowed slice) of a job's stdout/stderr log."""
    payload: dict[str, Any] = {
        "job_id": job_id,
        "stream": stream,
        "limit": int(limit),
    }
    if offset is not None:
        payload["offset"] = int(offset)
    data = _post("/desktop/desktop_job_logs", payload)
    return json.dumps(data)


def desktop_job_cancel(job_id: str, force: bool = False) -> str:
    """Cancel a job — TERM by default, KILL when force=true."""
    data = _post("/desktop/desktop_job_cancel", {"job_id": job_id, "force": bool(force)})
    return json.dumps(data)


def desktop_job_list(
    state: Optional[str] = None,
    since_ms: int = 0,
    limit: int = 100,
) -> str:
    """Enumerate every job the desktop client knows about."""
    payload: dict[str, Any] = {"since_ms": int(since_ms), "limit": int(limit)}
    if state is not None:
        payload["state"] = state
    data = _post("/desktop/desktop_job_list", payload)
    return json.dumps(data)


# ── File transfer ─────────────────────────────────────────────────────────────


def desktop_copy_directory(
    source: str,
    dest: str,
    overwrite: bool = False,
    preserve_timestamps: bool = True,
    dereference: bool = False,
) -> str:
    """Recursively copy ``source`` to ``dest`` on the desktop client."""
    data = _post(
        "/desktop/desktop_copy_directory",
        {
            "source": source,
            "dest": dest,
            "overwrite": bool(overwrite),
            "preserve_timestamps": bool(preserve_timestamps),
            "dereference": bool(dereference),
        },
    )
    return json.dumps(data)


def desktop_zip(source: str, dest: str, format: str = "zip") -> str:
    """Bundle ``source`` (file or directory) into a zip archive at ``dest``."""
    data = _post("/desktop/desktop_zip", {"source": source, "dest": dest, "format": format})
    return json.dumps(data)


def desktop_unzip(source: str, dest: str) -> str:
    """Expand archive ``source`` into the directory ``dest``."""
    data = _post("/desktop/desktop_unzip", {"source": source, "dest": dest})
    return json.dumps(data)


def desktop_checksum(path: str, algorithm: str = "sha256") -> str:
    """Compute a digest (sha256/sha1/md5) of a file on the desktop client."""
    data = _post("/desktop/desktop_checksum", {"path": path, "algorithm": algorithm})
    return json.dumps(data)


# ── Diagnostics ───────────────────────────────────────────────────────────────


def desktop_health() -> str:
    """Report the connected desktop client's metadata + last error.

    Hits the relay's ``/desktop/health`` endpoint directly — does NOT
    round-trip through the desktop.command channel — so it answers even
    when the client is wedged on a long tool call.
    """
    data = _get("/desktop/health")
    return json.dumps(data)


# ── Schemas ────────────────────────────────────────────────────────────────────


_SCHEMAS: dict[str, dict[str, Any]] = {
    "desktop_read_file": {
        "name": "desktop_read_file",
        "description": (
            "Read a file from the connected desktop client's local filesystem. "
            "Returns the file contents on success (truncated to max_bytes). "
            "Use this to inspect config files, source code, or logs on the user's "
            "desktop machine. Requires a paired + connected desktop client."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Absolute path on the desktop machine."},
                "max_bytes": {
                    "type": "integer",
                    "description": "Cap on bytes returned. Default 1,000,000 (1 MB).",
                    "default": 1_000_000,
                },
            },
            "required": ["path"],
        },
    },
    "desktop_write_file": {
        "name": "desktop_write_file",
        "description": (
            "Write content to a file on the desktop client. Overwrites if the file "
            "exists. Returns {ok: true, bytes_written}. Set create_dirs=true to "
            "mkdir -p the parent directory."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Absolute path on the desktop machine."},
                "content": {"type": "string", "description": "File contents to write (UTF-8)."},
                "create_dirs": {
                    "type": "boolean",
                    "description": "Create parent directories if missing. Default false.",
                    "default": False,
                },
            },
            "required": ["path", "content"],
        },
    },
    "desktop_search_files": {
        "name": "desktop_search_files",
        "description": (
            "Ripgrep-backed search across files on the desktop client. Matches "
            "file contents by regex. Returns up to max_results hits, each with "
            "the path, 1-indexed line number, and matching text. Prefer this "
            "over desktop_terminal('rg ...') — it's typed and bounded."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Regular expression to search for."},
                "cwd": {"type": "string", "description": "Root directory for the search."},
                "max_results": {
                    "type": "integer",
                    "description": "Cap on match count. Default 100.",
                    "default": 100,
                },
            },
            "required": ["pattern"],
        },
    },
    "desktop_terminal": {
        "name": "desktop_terminal",
        "description": (
            "Run a shell command on the desktop client and return its output. "
            "Use for short-lived commands (under 30s). For long-running work, "
            "prefer desktop_job_start (persistent logs) or desktop_spawn_detached "
            "(fire-and-forget). Returns stdout, stderr, exit_code, duration_ms."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Shell command to execute."},
                "cwd": {"type": "string", "description": "Working directory."},
                "timeout": {
                    "type": "integer",
                    "description": "Max seconds to wait. Default 30. Hard ceiling at the relay's 30s RPC window.",
                    "default": 30,
                },
            },
            "required": ["command"],
        },
    },
    "desktop_patch": {
        "name": "desktop_patch",
        "description": (
            "Apply a unified diff (patch) to a file on the desktop client. "
            "The patch should be in standard unified-diff format (as emitted by "
            "`git diff` or `diff -u`). Returns {ok: true, hunks_applied} on success."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Absolute path being patched."},
                "patch": {"type": "string", "description": "Unified diff contents."},
            },
            "required": ["path", "patch"],
        },
    },
    # ── Shell — PowerShell ────────────────────────────────────────────────
    "desktop_powershell": {
        "name": "desktop_powershell",
        "description": (
            "Run a PowerShell script on the desktop client, with the script text "
            "fed to PowerShell via stdin (no cmd.exe quote-mangling). Use this "
            "instead of desktop_terminal('powershell -Command \"...\"') — the "
            "latter loses quotes, here-strings, and dollar-vars to nested parsers. "
            "Picks `pwsh` when present, falls back to Windows PowerShell on "
            "Windows. Returns {stdout, stderr, exit_code, duration_ms, shell}."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "script": {"type": "string", "description": "PowerShell script text."},
                "cwd": {"type": "string", "description": "Working directory."},
                "timeout": {"type": "integer", "default": 30},
                "prefer": {
                    "type": "string",
                    "enum": ["pwsh", "powershell", "auto"],
                    "description": "Preferred shell. Default auto (pwsh > powershell).",
                    "default": "auto",
                },
            },
            "required": ["script"],
        },
    },
    # ── Process management ────────────────────────────────────────────────
    "desktop_spawn_detached": {
        "name": "desktop_spawn_detached",
        "description": (
            "Launch a long-lived process detached from the relay's 30s RPC ceiling. "
            "Returns immediately with {pid, job_id, log_path}. Use this for servers, "
            "build watchers, or any command that should outlive the tool call. "
            "For polling status / reading logs, use the desktop_job_* tools (the "
            "spawn lands in the same on-disk job directory)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Command line, parsed by the platform shell."},
                "cwd": {"type": "string"},
                "env": {"type": "object", "description": "Environment overrides (merged with current env)."},
                "job_id": {"type": "string", "description": "Optional caller-supplied job id."},
            },
            "required": ["command"],
        },
    },
    "desktop_list_processes": {
        "name": "desktop_list_processes",
        "description": (
            "List processes on the desktop client. Optional `filter` is a "
            "case-insensitive substring matched against the command field. "
            "Returns {processes: [...], count, total, truncated}."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "filter": {"type": "string", "description": "Substring filter, case-insensitive."},
                "limit": {"type": "integer", "default": 200},
            },
        },
    },
    "desktop_kill_process": {
        "name": "desktop_kill_process",
        "description": (
            "Kill a process on the desktop client. Provide `pid` (preferred) or "
            "`name` (case-insensitive substring match — kills every match). "
            "Default sends SIGTERM (POSIX) or `taskkill` (Windows). Pass "
            "force=true for SIGKILL / `taskkill /F`."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "pid": {"type": "integer"},
                "name": {"type": "string"},
                "force": {"type": "boolean", "default": False},
                "signal": {"type": "string", "enum": ["TERM", "KILL"], "default": "TERM"},
            },
        },
    },
    "desktop_find_pid_by_port": {
        "name": "desktop_find_pid_by_port",
        "description": (
            "Find which process is listening on a TCP/UDP port on the desktop "
            "client. Returns {port, protocol, listeners: [{pid, address}]}. "
            "Cross-platform — uses netstat/lsof/ss as appropriate."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "port": {"type": "integer"},
                "protocol": {"type": "string", "enum": ["tcp", "udp"], "default": "tcp"},
            },
            "required": ["port"],
        },
    },
    # ── Job API ────────────────────────────────────────────────────────────
    "desktop_job_start": {
        "name": "desktop_job_start",
        "description": (
            "Start a long-running background job on the desktop client. Returns "
            "within ~10ms with {job_id, pid, log_paths: {stdout, stderr}}. The "
            "job's stdout/stderr stream to disk under "
            "~/.hermes/desktop-jobs/<job_id>/. Use desktop_job_status/_logs to "
            "follow up; desktop_job_cancel to terminate. Logs survive a daemon "
            "restart."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "command": {"type": "string"},
                "cwd": {"type": "string"},
                "env": {"type": "object"},
                "stdin": {"type": "string", "description": "Optional stdin text fed once on start."},
                "job_id": {"type": "string"},
            },
            "required": ["command"],
        },
    },
    "desktop_job_status": {
        "name": "desktop_job_status",
        "description": (
            "Poll a job's current state. Returns full meta plus a `state` field: "
            "running | exited | killed | failed | unknown. `exit_code` is set "
            "once the child exits."
        ),
        "parameters": {
            "type": "object",
            "properties": {"job_id": {"type": "string"}},
            "required": ["job_id"],
        },
    },
    "desktop_job_logs": {
        "name": "desktop_job_logs",
        "description": (
            "Read tail (or a windowed slice) of a job's stdout/stderr. By "
            "default returns the last 8 KiB of each stream. Pass `offset` "
            "(byte offset from start; negative counts from end) and `limit` to "
            "paginate forward through a long log without re-reading."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "job_id": {"type": "string"},
                "stream": {"type": "string", "enum": ["stdout", "stderr", "both"], "default": "both"},
                "offset": {"type": "integer"},
                "limit": {"type": "integer", "default": 8192},
            },
            "required": ["job_id"],
        },
    },
    "desktop_job_cancel": {
        "name": "desktop_job_cancel",
        "description": (
            "Cancel a running job. Default sends SIGTERM (POSIX) or "
            "`taskkill /T` (Windows — kills the whole tree). Pass force=true "
            "for SIGKILL / `taskkill /F /T`."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "job_id": {"type": "string"},
                "force": {"type": "boolean", "default": False},
            },
            "required": ["job_id"],
        },
    },
    "desktop_job_list": {
        "name": "desktop_job_list",
        "description": (
            "Enumerate every job the desktop client knows about (including "
            "completed ones — the on-disk dir is the source of truth). Filter "
            "by `state` and `since_ms` (epoch). Returns newest first."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "state": {
                    "type": "string",
                    "enum": ["running", "exited", "killed", "failed", "unknown"],
                },
                "since_ms": {"type": "integer", "default": 0},
                "limit": {"type": "integer", "default": 100},
            },
        },
    },
    # ── File transfer ─────────────────────────────────────────────────────
    "desktop_copy_directory": {
        "name": "desktop_copy_directory",
        "description": (
            "Recursively copy a file or directory on the desktop client. "
            "Returns {ok, source, dest, files_copied, dirs_copied}. Pass "
            "overwrite=true to allow stomping an existing dest."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "source": {"type": "string"},
                "dest": {"type": "string"},
                "overwrite": {"type": "boolean", "default": False},
                "preserve_timestamps": {"type": "boolean", "default": True},
                "dereference": {"type": "boolean", "default": False},
            },
            "required": ["source", "dest"],
        },
    },
    "desktop_zip": {
        "name": "desktop_zip",
        "description": (
            "Bundle a file or directory into a .zip archive on the desktop "
            "client. Picks the best available impl (tar > zip > "
            "PowerShell). Returns {ok, dest, size_bytes, sha256, impl}."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "source": {"type": "string"},
                "dest": {"type": "string"},
                "format": {"type": "string", "enum": ["zip"], "default": "zip"},
            },
            "required": ["source", "dest"],
        },
    },
    "desktop_unzip": {
        "name": "desktop_unzip",
        "description": (
            "Expand an archive on the desktop client into a destination "
            "directory. Cross-format via tar/unzip/PowerShell."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "source": {"type": "string"},
                "dest": {"type": "string"},
            },
            "required": ["source", "dest"],
        },
    },
    "desktop_checksum": {
        "name": "desktop_checksum",
        "description": (
            "Compute a digest (sha256, sha1, md5) of a file on the desktop "
            "client. Streams the file — handles arbitrary sizes. Use this to "
            "verify a transfer landed intact."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "algorithm": {"type": "string", "enum": ["sha256", "sha1", "md5"], "default": "sha256"},
            },
            "required": ["path"],
        },
    },
    # ── Diagnostics ───────────────────────────────────────────────────────
    "desktop_health": {
        "name": "desktop_health",
        "description": (
            "Report the connected desktop client's identity (host, platform, "
            "version, pid), uptime, advertised tools, last error, and the most "
            "recent commands the relay has dispatched. Answered by the relay "
            "directly — does NOT round-trip through the client — so it works "
            "even when other tools are wedged. Use this to debug 'why isn't "
            "desktop_terminal responding?' before giving up."
        ),
        "parameters": {"type": "object", "properties": {}},
    },
}


# ── Dispatch table ─────────────────────────────────────────────────────────────


_HANDLERS: dict[str, Any] = {
    # Filesystem
    "desktop_read_file":     lambda args, **kw: desktop_read_file(**args),
    "desktop_write_file":    lambda args, **kw: desktop_write_file(**args),
    "desktop_search_files":  lambda args, **kw: desktop_search_files(**args),
    "desktop_terminal":      lambda args, **kw: desktop_terminal(**args),
    "desktop_patch":         lambda args, **kw: desktop_patch(**args),
    # Shell
    "desktop_powershell":    lambda args, **kw: desktop_powershell(**args),
    # Process management
    "desktop_spawn_detached":  lambda args, **kw: desktop_spawn_detached(**args),
    "desktop_list_processes":  lambda args, **kw: desktop_list_processes(**args),
    "desktop_kill_process":    lambda args, **kw: desktop_kill_process(**args),
    "desktop_find_pid_by_port": lambda args, **kw: desktop_find_pid_by_port(**args),
    # Job API
    "desktop_job_start":   lambda args, **kw: desktop_job_start(**args),
    "desktop_job_status":  lambda args, **kw: desktop_job_status(**args),
    "desktop_job_logs":    lambda args, **kw: desktop_job_logs(**args),
    "desktop_job_cancel":  lambda args, **kw: desktop_job_cancel(**args),
    "desktop_job_list":    lambda args, **kw: desktop_job_list(**args),
    # File transfer
    "desktop_copy_directory": lambda args, **kw: desktop_copy_directory(**args),
    "desktop_zip":            lambda args, **kw: desktop_zip(**args),
    "desktop_unzip":          lambda args, **kw: desktop_unzip(**args),
    "desktop_checksum":       lambda args, **kw: desktop_checksum(**args),
    # Diagnostics — answered by the relay directly, no client round-trip.
    "desktop_health": lambda args, **kw: desktop_health(),
}


# Tools whose check_fn should ping the relay rather than a specific client tool.
# desktop_health is callable even with no client connected — the whole point is
# to *report* whether one is connected — so it must not gate on `_check_tool`.
_RELAY_ONLY_TOOLS: frozenset[str] = frozenset({"desktop_health"})


# ── Registry registration ──────────────────────────────────────────────────────


try:
    from tools.registry import registry

    for tool_name, schema in _SCHEMAS.items():
        # Per-tool check_fn closure — captures the tool name so the ping
        # can query availability per-tool instead of all-or-nothing.
        def _make_check(name: str):
            if name in _RELAY_ONLY_TOOLS:
                return lambda: _check_relay()
            return lambda: _check_tool(name)

        registry.register(
            name=tool_name,
            toolset="desktop",
            schema=schema,
            handler=_HANDLERS[tool_name],
            check_fn=_make_check(tool_name),
            requires_env=[],  # DESKTOP_RELAY_URL has a default.
        )
except ImportError:
    # Running outside hermes-agent context (e.g. smoke tests).
    pass
