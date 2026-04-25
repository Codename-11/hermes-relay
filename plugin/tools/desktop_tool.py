"""
hermes-relay plugin — registers ``desktop_*`` tools into the hermes-agent registry.

Tools registered:
  - desktop_read_file     read a file from the connected desktop client's filesystem
  - desktop_write_file    write a file on the client's filesystem
  - desktop_search_files  ripgrep-backed file + content search on the client
  - desktop_terminal      run a shell command on the client
  - desktop_patch         apply a unified diff to a file on the client

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


# ── Tool implementations ───────────────────────────────────────────────────────


def desktop_read_file(path: str, max_bytes: int = 1_000_000) -> str:
    """Read a file from the desktop client's filesystem.

    Returns the file contents on success. On failure returns a JSON string
    ``{"error": "..."}`` so the LLM gets a readable failure.
    """
    data = _post("/desktop/desktop_read_file", {"path": path, "max_bytes": int(max_bytes)})
    # Prefer a raw ``content`` string for easy LLM consumption; fall back
    # to JSON-encoding the whole payload if the client returns structured
    # data.
    if isinstance(data, dict):
        if "error" in data:
            return json.dumps({"error": data["error"]})
        if "content" in data and isinstance(data["content"], str):
            return data["content"]
    return json.dumps(data)


def desktop_write_file(path: str, content: str, create_dirs: bool = False) -> str:
    """Write ``content`` to ``path`` on the desktop client.

    Returns JSON: ``{"ok": true, "bytes_written": N}`` or ``{"error": "..."}``.
    """
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
    """Ripgrep-backed file/content search on the desktop client.

    Returns JSON:
    ``{"matches": [{"path": str, "line": int, "text": str}, ...], "truncated": bool}``
    or ``{"error": "..."}``.
    """
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
    """Run a shell command on the desktop client.

    Returns JSON: ``{"stdout": str, "stderr": str, "exit_code": int, "duration_ms": int}``
    or ``{"error": "..."}``.
    """
    payload: dict[str, Any] = {"command": command, "timeout": int(timeout)}
    if cwd is not None:
        payload["cwd"] = cwd
    data = _post("/desktop/desktop_terminal", payload)
    return json.dumps(data)


def desktop_patch(path: str, patch: str) -> str:
    """Apply a unified-diff ``patch`` to ``path`` on the desktop client.

    Returns JSON: ``{"ok": true, "hunks_applied": N}`` or ``{"error": "..."}``.
    """
    data = _post("/desktop/desktop_patch", {"path": path, "patch": patch})
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
                "path": {
                    "type": "string",
                    "description": "Absolute path to the file on the desktop machine.",
                },
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
                "path": {
                    "type": "string",
                    "description": "Absolute path to the file on the desktop machine.",
                },
                "content": {
                    "type": "string",
                    "description": "File contents to write (UTF-8).",
                },
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
                "pattern": {
                    "type": "string",
                    "description": "Regular expression to search for.",
                },
                "cwd": {
                    "type": "string",
                    "description": "Root directory for the search. Defaults to the client's cwd.",
                },
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
            "Use for build commands, file ops the typed tools don't cover, or "
            "ad-hoc inspection. Returns stdout, stderr, exit code, and duration."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "Shell command to execute.",
                },
                "cwd": {
                    "type": "string",
                    "description": "Working directory. Defaults to the client's cwd.",
                },
                "timeout": {
                    "type": "integer",
                    "description": "Max seconds to wait. Default 30.",
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
                "path": {
                    "type": "string",
                    "description": "Absolute path to the file being patched.",
                },
                "patch": {
                    "type": "string",
                    "description": "Unified diff contents.",
                },
            },
            "required": ["path", "patch"],
        },
    },
}


# ── Dispatch table ─────────────────────────────────────────────────────────────


_HANDLERS = {
    "desktop_read_file":    lambda args, **kw: desktop_read_file(**args),
    "desktop_write_file":   lambda args, **kw: desktop_write_file(**args),
    "desktop_search_files": lambda args, **kw: desktop_search_files(**args),
    "desktop_terminal":     lambda args, **kw: desktop_terminal(**args),
    "desktop_patch":        lambda args, **kw: desktop_patch(**args),
}


# ── Registry registration ──────────────────────────────────────────────────────


try:
    from tools.registry import registry

    for tool_name, schema in _SCHEMAS.items():
        # Per-tool check_fn closure — captures the tool name so the ping
        # can query availability per-tool instead of all-or-nothing.
        def _make_check(name: str):
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
