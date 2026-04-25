"""TUI channel handler — Phase 1 of the Desktop TUI MVP.

Pipes line-delimited JSON-RPC 2.0 between a remote desktop TUI client
and a spawned ``tui_gateway`` subprocess on the relay host. The agent
loop, tool execution, approval flows, session DB, and image attach all
run server-side unchanged — the relay is a transparent envelope-pump.

Wire envelopes (see ``docs/relay-protocol.md`` §3.7, frozen):
  * ``tui.attach``        client → server: ``{cols, rows, profile?, resume_session_id?}``
  * ``tui.attached``      server → client: ``{pid, server_version}``
  * ``tui.rpc.request``   client → server: JSON-RPC request, forwarded verbatim
                          to subprocess stdin as ``json.dumps(payload) + "\\n"``.
  * ``tui.rpc.response``  server → client: JSON-RPC response object with ``id``.
  * ``tui.rpc.event``     server → client: JSON-RPC notification with
                          ``method: "event"`` (e.g. tool.start, message.delta).
  * ``tui.resize``        client → server: forwarded as a ``terminal.resize``
                          JSON-RPC request to the subprocess.
  * ``tui.detach``        client → server: kills subprocess cleanly.
  * ``tui.error``         server → client: ``{message}``.

Concurrency model:
  * One subprocess per connected WebSocket. Tracked in ``self._sessions``.
  * A reader task tails subprocess stdout line-by-line and emits envelopes.
  * A stderr-drain task tails stderr so the pipe never fills up.
  * Writes to stdin are serialized by an ``asyncio.Lock`` per session.
  * ``tui.detach`` or client disconnect triggers :meth:`detach_ws`, which
    SIGTERMs the subprocess and waits up to 2 s before SIGKILL.

Subprocess invocation mirrors ``hermes_cli/main.py:1034`` via the Node
TUI's ``gatewayClient.ts`` — the relay spawns the exact same module entry
point (``python -m tui_gateway.entry``) with a ``PYTHONPATH`` rooted at
the hermes-agent project so ``tui_gateway`` and its peer modules resolve.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import shutil
import signal
import sys
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from aiohttp import web

from .. import __version__

logger = logging.getLogger(__name__)


# Time to wait between SIGTERM and SIGKILL when tearing down a subprocess.
# Matches the patience level used by the Node TUI's gatewayClient.
SHUTDOWN_GRACE_SECONDS = 2.0

# Default path guess for the hermes-agent checkout. ``PYTHONPATH`` needs to
# point at the directory that *contains* the ``tui_gateway`` package so
# ``python -m tui_gateway.entry`` can resolve the module.
_DEFAULT_HERMES_AGENT_ROOT = Path.home() / ".hermes" / "hermes-agent"


def _envelope(msg_type: str, payload: dict[str, Any], msg_id: str | None = None) -> str:
    """Build a ``channel: "tui"`` envelope JSON string."""
    return json.dumps(
        {
            "channel": "tui",
            "type": msg_type,
            "id": msg_id or str(uuid.uuid4()),
            "payload": payload,
        }
    )


def _resolve_python_src_root() -> str:
    """Return the path to add to PYTHONPATH so ``tui_gateway.entry`` imports.

    Prefers ``HERMES_PYTHON_SRC_ROOT`` (same env var the Node TUI uses),
    then falls back to ``~/.hermes/hermes-agent``. We don't hard-fail if
    the path doesn't exist — spawn will surface the import error as
    subprocess stderr, which becomes a ``tui.error`` envelope.
    """
    configured = os.environ.get("HERMES_PYTHON_SRC_ROOT", "").strip()
    if configured:
        return configured
    return str(_DEFAULT_HERMES_AGENT_ROOT)


def _resolve_python_executable(src_root: str) -> str:
    """Return the python interpreter to use for the subprocess.

    Mirrors the Node TUI's ``resolvePython`` logic: prefer explicit env
    vars, then a project-local venv, then the current ``sys.executable``.
    The CLI invocation path (``subprocess.call(argv, ..., env=env)`` at
    ``hermes_cli/main.py:1034``) leaves python selection to the Node
    child which runs ``resolvePython`` — we replicate it here so the
    relay-spawned subprocess picks up the hermes-agent venv in the
    common case where the relay and agent share a host.
    """
    for var in ("HERMES_PYTHON", "PYTHON"):
        configured = os.environ.get(var, "").strip()
        if configured:
            return configured

    candidates = [
        Path(src_root) / ".venv" / "bin" / "python",
        Path(src_root) / ".venv" / "bin" / "python3",
        Path(src_root) / "venv" / "bin" / "python",
        Path(src_root) / "venv" / "bin" / "python3",
    ]
    for candidate in candidates:
        if candidate.exists():
            return str(candidate)

    # Last resort: the python running the relay. Will only work if the
    # relay venv also has tui_gateway on its path, but better to spawn
    # and surface a clean import error than to bail silently here.
    return sys.executable or shutil.which("python3") or "python3"


@dataclass
class _TuiSession:
    """Per-WebSocket subprocess state."""

    proc: asyncio.subprocess.Process
    ws: web.WebSocketResponse
    reader_task: asyncio.Task[None] | None = None
    stderr_task: asyncio.Task[None] | None = None
    stdin_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    # Guards teardown so tui.detach + client disconnect don't double-kill.
    closing: bool = False


class TuiHandler:
    """Routes ``tui`` channel envelopes to a per-connection subprocess.

    One :class:`TuiHandler` lives on :class:`RelayServer`; it holds a
    ``ws → _TuiSession`` map so each connected desktop TUI client gets
    its own ``tui_gateway`` subprocess with an isolated session DB
    cursor. Subprocesses are killed on ``tui.detach`` or client
    disconnect.
    """

    # The JSON-RPC method name the subprocess expects for terminal resize.
    # Confirmed by inspecting ``tui_gateway/server.py`` method registry —
    # the "resize" handler is registered via ``@method`` decorators; we
    # keep the dispatch verbatim so phase 2 can choose the exact method.
    RESIZE_METHOD = "terminal.resize"

    def __init__(self) -> None:
        self._sessions: dict[web.WebSocketResponse, _TuiSession] = {}
        # Lock guards session add/remove so teardown doesn't race with
        # a concurrent tui.attach on the same ws (shouldn't happen but
        # cheap insurance).
        self._lock = asyncio.Lock()

    # ── Entry point ─────────────────────────────────────────────────────

    async def handle(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
    ) -> None:
        """Route an incoming ``tui`` channel envelope."""
        msg_type = envelope.get("type", "")
        payload = envelope.get("payload")
        if payload is None:
            payload = {}
        if not isinstance(payload, dict):
            await self._send_error(ws, "payload must be a JSON object")
            return

        try:
            if msg_type == "tui.attach":
                await self._handle_attach(ws, payload)
            elif msg_type == "tui.rpc.request":
                await self._handle_rpc_request(ws, payload)
            elif msg_type == "tui.resize":
                await self._handle_resize(ws, payload)
            elif msg_type == "tui.detach":
                await self.detach_ws(ws, reason="client detach")
            else:
                await self._send_error(ws, f"unknown tui message type: {msg_type!r}")
        except Exception as exc:  # pragma: no cover — last-resort guard
            logger.exception("tui: unhandled error routing envelope type=%s", msg_type)
            await self._send_error(ws, f"internal tui handler error: {exc}")

    # ── Attach ──────────────────────────────────────────────────────────

    async def _handle_attach(
        self,
        ws: web.WebSocketResponse,
        payload: dict[str, Any],
    ) -> None:
        """Spawn ``python -m tui_gateway.entry`` for this client."""
        # Re-attach on an existing session is idempotent — kill the old
        # subprocess and start fresh. The desktop client only needs this
        # path on a reconnect; a duplicate attach on a live session is
        # treated as "user really means it".
        existing = self._sessions.get(ws)
        if existing is not None:
            logger.info("tui: re-attach on live session, killing previous subprocess")
            await self._teardown_session(existing, reason="re-attach")

        src_root = _resolve_python_src_root()
        python = _resolve_python_executable(src_root)
        cwd = os.environ.get("HERMES_CWD") or src_root

        env = os.environ.copy()
        env.setdefault("HERMES_PYTHON_SRC_ROOT", src_root)
        env.setdefault("HERMES_PYTHON", python)
        env.setdefault("HERMES_CWD", cwd)

        # Prepend src_root to PYTHONPATH so the subprocess can import
        # ``tui_gateway`` and its hermes-agent peers without needing a
        # full editable install in whatever venv the relay happens to
        # be using. Mirrors gatewayClient.ts's PYTHONPATH rewrite.
        existing_py_path = env.get("PYTHONPATH", "").strip()
        if existing_py_path:
            env["PYTHONPATH"] = f"{src_root}{os.pathsep}{existing_py_path}"
        else:
            env["PYTHONPATH"] = src_root

        # Optional profile override — the client can ask for a named
        # profile and the subprocess will pick it up from the env the
        # same way the local CLI does.
        profile = payload.get("profile")
        if isinstance(profile, str) and profile:
            env["HERMES_PROFILE"] = profile
        resume_session_id = payload.get("resume_session_id")
        if isinstance(resume_session_id, str) and resume_session_id:
            env["HERMES_TUI_RESUME"] = resume_session_id

        try:
            proc = await asyncio.create_subprocess_exec(
                python,
                "-m",
                "tui_gateway.entry",
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                cwd=cwd,
                env=env,
            )
        except (OSError, FileNotFoundError) as exc:
            logger.error("tui: failed to spawn tui_gateway: %s", exc)
            await self._send_error(ws, f"failed to spawn tui_gateway: {exc}")
            return

        session = _TuiSession(proc=proc, ws=ws)
        async with self._lock:
            self._sessions[ws] = session

        # Kick off the pumps before acking so any early stdout (e.g. the
        # subprocess's own gateway.ready event) is already flowing.
        session.reader_task = asyncio.create_task(
            self._pump_stdout(session), name="tui-stdout-pump"
        )
        session.stderr_task = asyncio.create_task(
            self._pump_stderr(session), name="tui-stderr-pump"
        )

        if not ws.closed:
            await ws.send_str(
                _envelope(
                    "tui.attached",
                    {"pid": proc.pid, "server_version": __version__},
                )
            )
        logger.info("tui: spawned tui_gateway pid=%s for ws=%s", proc.pid, id(ws))

    # ── RPC forwarding ──────────────────────────────────────────────────

    async def _handle_rpc_request(
        self,
        ws: web.WebSocketResponse,
        payload: dict[str, Any],
    ) -> None:
        """Forward a JSON-RPC request object to subprocess stdin."""
        session = self._sessions.get(ws)
        if session is None:
            await self._send_error(
                ws, "no active tui session — send tui.attach first"
            )
            return
        await self._write_rpc(session, payload)

    async def _handle_resize(
        self,
        ws: web.WebSocketResponse,
        payload: dict[str, Any],
    ) -> None:
        """Translate tui.resize → a JSON-RPC request on the terminal.resize method.

        We mint a fresh ``id`` so the subprocess reply comes back as a
        ``tui.rpc.response`` envelope correlating on that id. Clients
        that don't care about the reply can ignore it.
        """
        session = self._sessions.get(ws)
        if session is None:
            await self._send_error(
                ws, "no active tui session — send tui.attach first"
            )
            return

        cols = payload.get("cols")
        rows = payload.get("rows")
        rpc: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": f"resize-{uuid.uuid4()}",
            "method": self.RESIZE_METHOD,
            "params": {"cols": cols, "rows": rows},
        }
        await self._write_rpc(session, rpc)

    async def _write_rpc(
        self,
        session: _TuiSession,
        obj: dict[str, Any],
    ) -> None:
        """Serialize ``obj`` as a single line and write to subprocess stdin."""
        stdin = session.proc.stdin
        if stdin is None or session.proc.returncode is not None:
            await self._send_error(session.ws, "tui_gateway subprocess is not running")
            return
        try:
            line = (json.dumps(obj) + "\n").encode("utf-8")
        except (TypeError, ValueError) as exc:
            await self._send_error(session.ws, f"malformed RPC payload: {exc}")
            return

        async with session.stdin_lock:
            try:
                stdin.write(line)
                await stdin.drain()
            except (BrokenPipeError, ConnectionResetError) as exc:
                logger.warning("tui: stdin write failed pid=%s: %s", session.proc.pid, exc)
                await self._send_error(
                    session.ws, f"tui_gateway subprocess closed stdin: {exc}"
                )

    # ── Output pumps ────────────────────────────────────────────────────

    async def _pump_stdout(self, session: _TuiSession) -> None:
        """Tail subprocess stdout line-by-line, emit envelopes per line."""
        stdout = session.proc.stdout
        assert stdout is not None, "spawn always opens PIPE"
        ws = session.ws
        try:
            while True:
                raw = await stdout.readline()
                if not raw:
                    break  # EOF — subprocess exited
                try:
                    line = raw.decode("utf-8").rstrip("\r\n")
                except UnicodeDecodeError:
                    logger.warning("tui: non-utf8 stdout line dropped")
                    continue
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    logger.warning("tui: malformed stdout line: %s", line[:200])
                    continue

                # Classify: response has an ``id`` and result/error;
                # event is a JSON-RPC notification with method "event".
                msg_type = "tui.rpc.response"
                if isinstance(obj, dict):
                    if obj.get("method") == "event":
                        msg_type = "tui.rpc.event"
                    elif "id" not in obj:
                        # No id, not an event — treat as event so the
                        # client at least sees it instead of dropping.
                        msg_type = "tui.rpc.event"

                if ws.closed:
                    break
                try:
                    await ws.send_str(_envelope(msg_type, obj))
                except ConnectionResetError:
                    break
        except asyncio.CancelledError:
            raise
        except Exception:  # pragma: no cover — defensive
            logger.exception("tui: stdout pump crashed pid=%s", session.proc.pid)
        finally:
            # If the subprocess exited on its own, surface that to the
            # client so it can tear down cleanly.
            if not session.closing and session.proc.returncode is not None:
                await self._send_error(
                    ws,
                    f"tui_gateway exited (code={session.proc.returncode})",
                )

    async def _pump_stderr(self, session: _TuiSession) -> None:
        """Drain subprocess stderr into the relay log so the pipe can't fill.

        The Node TUI surfaces stderr as ``gateway.stderr`` events; we
        log here and leave structured event plumbing to the future when
        we want to pipe these back to the desktop for a "server logs"
        panel.
        """
        stderr = session.proc.stderr
        assert stderr is not None, "spawn always opens PIPE"
        try:
            while True:
                raw = await stderr.readline()
                if not raw:
                    return
                try:
                    line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                except Exception:
                    continue
                if line:
                    logger.debug("tui[%s] stderr: %s", session.proc.pid, line)
        except asyncio.CancelledError:
            raise
        except Exception:  # pragma: no cover — defensive
            logger.exception("tui: stderr pump crashed pid=%s", session.proc.pid)

    # ── Error envelope ──────────────────────────────────────────────────

    async def _send_error(
        self,
        ws: web.WebSocketResponse,
        message: str,
    ) -> None:
        if ws.closed:
            return
        try:
            await ws.send_str(_envelope("tui.error", {"message": message}))
        except ConnectionResetError:
            pass

    # ── Teardown ────────────────────────────────────────────────────────

    async def detach_ws(
        self,
        ws: web.WebSocketResponse,
        reason: str = "",
    ) -> None:
        """Kill the subprocess for ``ws`` if any; safe to call unconditionally."""
        async with self._lock:
            session = self._sessions.pop(ws, None)
        if session is None:
            return
        await self._teardown_session(session, reason=reason)

    async def _teardown_session(
        self,
        session: _TuiSession,
        reason: str = "",
    ) -> None:
        """SIGTERM → 2s → SIGKILL. Also cancels the pumps."""
        if session.closing:
            return
        session.closing = True
        proc = session.proc
        pid = proc.pid

        if proc.returncode is None:
            try:
                proc.send_signal(signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(proc.wait(), timeout=SHUTDOWN_GRACE_SECONDS)
            except asyncio.TimeoutError:
                logger.warning(
                    "tui: SIGTERM grace expired pid=%s — escalating to SIGKILL", pid
                )
                try:
                    proc.kill()
                except ProcessLookupError:
                    pass
                try:
                    await asyncio.wait_for(proc.wait(), timeout=1.0)
                except asyncio.TimeoutError:
                    logger.error("tui: SIGKILL didn't reap pid=%s", pid)

        # Pumps will observe EOF and exit naturally; cancel to be sure.
        for task in (session.reader_task, session.stderr_task):
            if task is not None and not task.done():
                task.cancel()
                try:
                    await task
                except (asyncio.CancelledError, Exception):
                    pass

        logger.info(
            "tui: torn down tui_gateway pid=%s (reason=%s, exit=%s)",
            pid,
            reason or "unspecified",
            proc.returncode,
        )

    async def close(self) -> None:
        """Server shutdown — tear down every live session."""
        async with self._lock:
            sessions = list(self._sessions.values())
            self._sessions.clear()
        for session in sessions:
            await self._teardown_session(session, reason="server shutdown")
