"""Terminal channel handler — PTY-backed interactive shell over WebSocket.

Allocates a PTY for each attached client, spawns the configured shell, and
streams stdin/stdout over the relay WebSocket using the standard envelope
protocol. Output is batched in ~16ms frames to avoid flooding the wire.

Protocol (incoming on channel="terminal"):
    terminal.attach   { session_name?, shell?, cols, rows }
    terminal.input    { session_name?, data }         # raw UTF-8 bytes
    terminal.resize   { session_name?, cols, rows }
    terminal.detach   { session_name? }               # preserves tmux session
    terminal.kill     { session_name? }               # destroys tmux session
    terminal.list     {}

Protocol (outgoing):
    terminal.attached { session_name, pid, shell, cols, rows, tmux_available }
    terminal.output   { session_name, data }
    terminal.detached { session_name, reason }
    terminal.sessions { sessions, tmux_available }
    terminal.error    { message }

Unix-only. On platforms without pty/termios/fcntl (Windows), `_PTY_AVAILABLE`
is False and every attach attempt returns a clean `terminal.error` — the rest
of the relay still starts cleanly so Windows developers can exercise chat/bridge
without a PTY host.
"""

from __future__ import annotations

import asyncio
import errno
import json
import logging
import os
import shutil
import signal
import struct
import uuid
from typing import Any

from aiohttp import web

try:
    import fcntl
    import pty
    import termios
    _PTY_AVAILABLE = True
except ImportError:  # pragma: no cover — Windows fallback
    _PTY_AVAILABLE = False

logger = logging.getLogger(__name__)

# Output batching — flush every ~16ms (60fps) or when buffer hits this size.
_FLUSH_INTERVAL_S = 0.016
_FLUSH_MAX_BYTES = 4096
_READ_CHUNK_SIZE = 8192

# Per-client session cap (defends against an abusive client opening hundreds of shells).
_MAX_SESSIONS_PER_CLIENT = 4
_TMUX_PREFIX = "hermes-"
_TMUX_REPLAY_LINES = 200
_TMUX_REPLAY_MAX_BYTES = 64 * 1024

# Dedicated tmux socket + config so the relay's sessions run on their own tmux
# *server*, fully isolated from the user's personal tmux (their ~/.tmux.conf
# and default socket). This is the only safe way to set server-global options
# like `escape-time` without altering the user's environment, and it keeps our
# sessions out of their `tmux ls`. The persistence model is unchanged — the
# tmux server on this socket outlives the relay process, so reconnects still
# re-attach the same shells.
_TMUX_SOCKET = "hermes-relay"

# Tuned for clean TUI behavior over a remote/mobile terminal. Applied only when
# the `-L hermes-relay` server first starts (tmux loads config at server boot;
# changes here take effect on the next fresh server, not a live one).
#   escape-time 0       — kill the 500ms ESC delay (snappy vim / Alt-combos)
#   default-terminal    — keep a 256color/truecolor-capable $TERM inside panes
#   terminal-features   — advertise RGB truecolor (tmux ≥3.2)
#   terminal-overrides  — Tc truecolor fallback for older tmux
#   mouse on            — wheel→scroll / click→pane (pairs with our SGR wheel)
#   focus-events on     — forward CSI ?1004 focus in/out to TUIs
#   set-clipboard on    — let apps set the clipboard via OSC 52
#   aggressive-resize   — size to the smallest *attached* client (phone + CLI)
#   status off          — we render our own chrome; reclaim the bottom row
# If colors look wrong, the host is likely missing the `tmux-256color` terminfo
# entry (install ncurses-term) — `screen-256color` is the universal fallback.
_TMUX_CONF = """\
set -s escape-time 0
set -g  default-terminal "tmux-256color"
set -ga terminal-features ",*:RGB"
set -ga terminal-overrides ",*:Tc"
set -g  mouse on
set -g  focus-events on
set -g  set-clipboard on
setw -g aggressive-resize on
set -g  history-limit 10000
set -g  status off
"""


def _make_envelope(
    msg_type: str,
    payload: dict[str, Any],
    msg_id: str | None = None,
) -> str:
    return json.dumps(
        {
            "channel": "terminal",
            "type": msg_type,
            "id": msg_id or str(uuid.uuid4()),
            "payload": payload,
        }
    )


class _Session:
    """One PTY-backed shell session bound to one WebSocket client."""

    __slots__ = (
        "ws",
        "master_fd",
        "pid",
        "name",
        "shell",
        "buffer",
        "flush_handle",
        "closed",
    )

    def __init__(
        self,
        ws: web.WebSocketResponse,
        master_fd: int,
        pid: int,
        name: str,
        shell: str,
    ) -> None:
        self.ws = ws
        self.master_fd = master_fd
        self.pid = pid
        self.name = name
        self.shell = shell
        self.buffer = bytearray()
        self.flush_handle: asyncio.TimerHandle | None = None
        self.closed = False


class TerminalHandler:
    """Manages PTY shell sessions across all connected clients.

    Sessions are keyed by (ws, session_name). Most clients will keep a single
    session; the cap guards against runaway allocation but a thoughtful client
    can multiplex a few at once.
    """

    def __init__(
        self,
        default_shell: str | None = None,
        force_tmux: bool | None = None,
    ) -> None:
        """Build a TerminalHandler.

        Args:
            default_shell: Override the auto-resolved shell ($SHELL → /bin/bash).
            force_tmux: Test override. ``None`` (default) auto-detects tmux via
                ``shutil.which("tmux")``. ``True`` forces tmux mode (must be on
                PATH or spawn will fail in the child). ``False`` forces bare-shell
                mode even when tmux is installed — useful for tests that want to
                exercise the legacy ephemeral spawn path.
        """
        self.default_shell = default_shell
        self._sessions: dict[web.WebSocketResponse, dict[str, _Session]] = {}
        if force_tmux is None:
            self._tmux_path = shutil.which("tmux")
            self.tmux_available = self._tmux_path is not None
        else:
            self._tmux_path = shutil.which("tmux") if force_tmux else None
            self.tmux_available = force_tmux
        # Written lazily on first tmux use (see _ensure_tmux_conf).
        self._tmux_conf_path: str | None = None
        self._tmux_conf_written = False

    # ── Envelope dispatch ────────────────────────────────────────────────

    async def handle(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
    ) -> None:
        msg_type = envelope.get("type", "")
        msg_id = envelope.get("id")
        payload = envelope.get("payload") or {}

        try:
            if msg_type == "terminal.attach":
                await self._handle_attach(ws, payload, msg_id)
            elif msg_type == "terminal.input":
                await self._handle_input(ws, payload)
            elif msg_type == "terminal.resize":
                await self._handle_resize(ws, payload)
            elif msg_type == "terminal.detach":
                await self._handle_detach(ws, payload)
            elif msg_type == "terminal.kill":
                await self._handle_kill(ws, payload)
            elif msg_type == "terminal.list":
                await self._handle_list(ws, msg_id)
            else:
                await _send(
                    ws,
                    _make_envelope(
                        "terminal.error",
                        {"message": f"Unknown terminal message type: {msg_type}"},
                        msg_id,
                    ),
                )
        except Exception as exc:  # noqa: BLE001 — surface any unexpected failure
            logger.exception("Terminal handler error handling %s", msg_type)
            await _send(
                ws,
                _make_envelope("terminal.error", {"message": str(exc)}, msg_id),
            )

    # ── Attach: spawn shell in a PTY ─────────────────────────────────────

    async def _handle_attach(
        self,
        ws: web.WebSocketResponse,
        payload: dict[str, Any],
        msg_id: str | None,
    ) -> None:
        if not _PTY_AVAILABLE:
            await _send(
                ws,
                _make_envelope(
                    "terminal.error",
                    {
                        "message": (
                            "Terminal channel requires Unix PTY support "
                            "(pty/termios/fcntl). Not available on this host."
                        )
                    },
                    msg_id,
                ),
            )
            return

        existing = self._sessions.get(ws, {})
        if len(existing) >= _MAX_SESSIONS_PER_CLIENT:
            await _send(
                ws,
                _make_envelope(
                    "terminal.error",
                    {
                        "message": (
                            f"Session cap reached ({_MAX_SESSIONS_PER_CLIENT}). "
                            "Detach an existing session first."
                        )
                    },
                    msg_id,
                ),
            )
            return

        cols = max(1, int(payload.get("cols") or 80))
        rows = max(1, int(payload.get("rows") or 24))
        session_name = payload.get("session_name") or f"shell-{uuid.uuid4().hex[:8]}"

        # If the client re-attaches to a name it already owns, just ack.
        if session_name in existing:
            session = existing[session_name]
            _set_winsize(session.master_fd, rows, cols)
            await _send(
                ws,
                _make_envelope(
                    "terminal.attached",
                    {
                        "session_name": session.name,
                        "pid": session.pid,
                        "shell": session.shell,
                        "cols": cols,
                        "rows": rows,
                        "tmux_available": self.tmux_available,
                        "reattach": True,
                    },
                    msg_id,
                ),
            )
            return

        shell = self._resolve_shell(payload.get("shell"))
        tmux_preexisting = (
            await self._tmux_session_exists(session_name)
            if self.tmux_available
            else False
        )
        tmux_replay = (
            await self._capture_tmux_replay(session_name)
            if tmux_preexisting
            else ""
        )

        # ── Spawn argv selection ─────────────────────────────────────────
        # tmux-backed when available so shells persist across disconnects —
        # the Android client sends a stable session_name per tab,
        # `tmux new-session -A` attaches-or-creates, and all shell state
        # (cwd, env, running processes, scrollback, bash history) is
        # preserved across reconnects. The tmux server keeps the bash child
        # alive in the background after the WebSocket drops or the client
        # explicitly detaches; the next attach with the same session_name
        # re-enters the same running session.
        #
        # When tmux is not on PATH we fall back to a bare ``bash -l``: the
        # shell is then ephemeral and dies with the PTY exactly like the
        # pre-tmux behavior, since there is no out-of-band process to host it.
        if self.tmux_available:
            tmux_binary = self._tmux_path or "tmux"
            tmux_session_name = _tmux_session_name(session_name)
            exec_path = tmux_binary
            # `_tmux_base_args()` adds `-L hermes-relay -f <conf>` and writes the
            # config first, so the dedicated server boots with our hardening.
            exec_argv = [
                tmux_binary,
                *self._tmux_base_args(),
                "-u",
                "new-session",
                "-A",
                "-s",
                tmux_session_name,
            ]
        else:
            exec_path = shell
            exec_argv = [shell, "-l"]

        master_fd, slave_fd = pty.openpty()
        _set_winsize(master_fd, rows, cols)

        # Non-blocking master so the event-loop reader can safely os.read.
        flags = fcntl.fcntl(master_fd, fcntl.F_GETFL)
        fcntl.fcntl(master_fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)

        pid = os.fork()
        if pid == 0:
            # --- Child ---
            try:
                os.close(master_fd)
                os.setsid()
                fcntl.ioctl(slave_fd, termios.TIOCSCTTY, 0)
                os.dup2(slave_fd, 0)
                os.dup2(slave_fd, 1)
                os.dup2(slave_fd, 2)
                if slave_fd > 2:
                    os.close(slave_fd)

                env = os.environ.copy()
                env["TERM"] = "xterm-256color"
                env["COLORTERM"] = "truecolor"
                # A recognizable marker for anyone spelunking through process lists.
                # Tmux inherits this env so the variable is still visible inside
                # the persistent shell across reconnects.
                env["HERMES_RELAY_TERMINAL"] = session_name

                os.execvpe(exec_path, exec_argv, env)
            except Exception:  # noqa: BLE001
                os._exit(1)
        else:
            # --- Parent ---
            os.close(slave_fd)
            session = _Session(
                ws=ws,
                master_fd=master_fd,
                pid=pid,
                name=session_name,
                shell=shell,
            )
            self._sessions.setdefault(ws, {})[session_name] = session

            loop = asyncio.get_running_loop()
            loop.add_reader(master_fd, self._on_pty_readable, session)

            attached_payload: dict[str, Any] = {
                "session_name": session_name,
                "pid": pid,
                "shell": shell,
                "cols": cols,
                "rows": rows,
                "tmux_available": self.tmux_available,
                "reattach": tmux_preexisting,
            }
            if tmux_replay:
                attached_payload["replay"] = tmux_replay
            await _send(
                ws,
                _make_envelope(
                    "terminal.attached",
                    attached_payload,
                    msg_id,
                ),
            )
            logger.info(
                "Terminal session attached: name=%s pid=%d shell=%s",
                session_name,
                pid,
                shell,
            )

    def _resolve_shell(self, requested: str | None) -> str:
        """Pick a shell: request → default → $SHELL → /bin/sh fallback.

        We *allow* any absolute-path shell the client asks for because the
        relay is already gated by pairing-code auth — an attacker with a
        valid session token can do a lot more than pick a shell. We still
        refuse relative paths to avoid accidental PATH surprises.
        """
        candidates: list[str] = []
        if requested:
            candidates.append(requested)
        if self.default_shell:
            candidates.append(self.default_shell)
        env_shell = os.environ.get("SHELL")
        if env_shell:
            candidates.append(env_shell)
        candidates.extend(["/bin/bash", "/bin/sh"])

        for candidate in candidates:
            if not candidate:
                continue
            if not os.path.isabs(candidate):
                continue
            if os.path.exists(candidate) and os.access(candidate, os.X_OK):
                return candidate

        raise RuntimeError("No executable shell found on this host")

    # ── Output: PTY → WebSocket ──────────────────────────────────────────

    def _on_pty_readable(self, session: _Session) -> None:
        """Called by the event loop when master_fd has data (or EOF)."""
        if session.closed:
            return

        try:
            data = os.read(session.master_fd, _READ_CHUNK_SIZE)
        except OSError as exc:
            if exc.errno in (errno.EAGAIN, errno.EWOULDBLOCK):
                return
            # fd closed / child died unexpectedly. With tmux this usually
            # means the WS dropped — the tmux server is still alive holding
            # the shell, so we preserve it for the next reconnect. With
            # bare-bash there is nothing to preserve.
            asyncio.create_task(
                self._close_session(
                    session,
                    reason="pty closed",
                    preserve_shell=self.tmux_available,
                )
            )
            return

        if not data:
            # EOF — child exited (or tmux client detached). Preserve the
            # background tmux server when available.
            asyncio.create_task(
                self._close_session(
                    session,
                    reason="eof",
                    preserve_shell=self.tmux_available,
                )
            )
            return

        session.buffer.extend(data)

        if len(session.buffer) >= _FLUSH_MAX_BYTES:
            # Over threshold — flush now, cancel any pending timer.
            if session.flush_handle is not None:
                session.flush_handle.cancel()
                session.flush_handle = None
            asyncio.create_task(self._flush(session))
        elif session.flush_handle is None:
            # Schedule a flush window so rapid small writes coalesce.
            loop = asyncio.get_running_loop()
            session.flush_handle = loop.call_later(
                _FLUSH_INTERVAL_S,
                lambda s=session: asyncio.create_task(self._flush(s)),
            )

    async def _flush(self, session: _Session) -> None:
        session.flush_handle = None
        if session.closed or not session.buffer:
            return
        data = bytes(session.buffer)
        session.buffer.clear()
        text = data.decode("utf-8", errors="replace")
        logger.info(
            "terminal.output: flushing %d bytes from session=%s",
            len(data),
            session.name,
        )
        try:
            await _send(
                session.ws,
                _make_envelope(
                    "terminal.output",
                    {"session_name": session.name, "data": text},
                ),
            )
        except (ConnectionResetError, RuntimeError):
            await self._close_session(
                session,
                reason="ws closed during flush",
                preserve_shell=self.tmux_available,
            )

    # ── Input / resize / detach ──────────────────────────────────────────

    async def _handle_input(
        self,
        ws: web.WebSocketResponse,
        payload: dict[str, Any],
    ) -> None:
        session = self._lookup(ws, payload.get("session_name"))
        if session is None:
            logger.info(
                "terminal.input: no session found (requested=%r, open=%r)",
                payload.get("session_name"),
                list((self._sessions.get(ws) or {}).keys()),
            )
            return
        data = payload.get("data")
        if not isinstance(data, str):
            return
        try:
            nwritten = os.write(session.master_fd, data.encode("utf-8"))
            logger.info(
                "terminal.input: wrote %d bytes to session=%s pid=%d",
                nwritten,
                session.name,
                session.pid,
            )
        except OSError:
            await self._close_session(
                session,
                reason="write failed",
                preserve_shell=self.tmux_available,
            )

    async def _handle_resize(
        self,
        ws: web.WebSocketResponse,
        payload: dict[str, Any],
    ) -> None:
        session = self._lookup(ws, payload.get("session_name"))
        if session is None:
            return
        cols = max(1, int(payload.get("cols") or 80))
        rows = max(1, int(payload.get("rows") or 24))
        try:
            _set_winsize(session.master_fd, rows, cols)
        except OSError as exc:
            logger.warning("Resize failed for %s: %s", session.name, exc)

    async def _handle_detach(
        self,
        ws: web.WebSocketResponse,
        payload: dict[str, Any],
    ) -> None:
        session = self._lookup(ws, payload.get("session_name"))
        if session is None:
            return
        # When tmux is hosting the shell, an explicit client detach should
        # leave the tmux server (and the shell inside it) running so the
        # next attach lands in the same session. Bare-bash sessions are
        # ephemeral — they have nowhere to live without the PTY — so we
        # still hup+kill them on detach.
        await self._close_session(
            session,
            reason="client detach",
            preserve_shell=self.tmux_available,
        )

    async def _handle_kill(
        self,
        ws: web.WebSocketResponse,
        payload: dict[str, Any],
    ) -> None:
        """Hard-destroy a session — unlike detach, the underlying shell dies.

        With tmux, we invoke ``tmux kill-session -t <name>`` out-of-band so
        the tmux server tears down its background bash child; without that
        the shell would survive the PTY close. For bare-shell mode the PTY
        close already does the right thing via ``_close_session`` with
        ``preserve_shell=False``.
        """
        requested = payload.get("session_name")
        session = self._lookup(ws, requested)
        if session is None:
            if isinstance(requested, str) and requested and self.tmux_available:
                killed = await self._kill_tmux_session(requested)
                await _send(
                    ws,
                    _make_envelope(
                        "terminal.detached"
                        if killed
                        else "terminal.error",
                        {
                            "session_name": requested,
                            "reason": "client kill",
                            "message": f"no tmux session named {requested}",
                        }
                        if not killed
                        else {"session_name": requested, "reason": "client kill"},
                    ),
                )
                return
            return
        if self.tmux_available:
            await self._kill_tmux_session(session.name)
        await self._close_session(
            session,
            reason="client kill",
            preserve_shell=False,
        )

    async def _handle_list(
        self,
        ws: web.WebSocketResponse,
        msg_id: str | None,
    ) -> None:
        sessions = await self._terminal_session_summaries(ws)
        await _send(
            ws,
            _make_envelope(
                "terminal.sessions",
                {
                    "sessions": sessions,
                    "tmux_available": self.tmux_available,
                },
                msg_id,
            ),
        )

    # ── Lookup / teardown ────────────────────────────────────────────────

    def _lookup(
        self,
        ws: web.WebSocketResponse,
        name: str | None,
    ) -> _Session | None:
        sessions = self._sessions.get(ws)
        if not sessions:
            return None
        if name and name in sessions:
            return sessions[name]
        if not name and sessions:
            # Default to the most-recently-added session.
            return next(reversed(list(sessions.values())))
        return None

    async def _terminal_session_summaries(
        self,
        ws: web.WebSocketResponse,
    ) -> list[dict[str, Any]]:
        live_sessions = {
            session.name: session
            for client_sessions in self._sessions.values()
            for session in client_sessions.values()
        }
        client_live = self._sessions.get(ws, {})

        if self.tmux_available:
            summaries = await self._list_tmux_sessions()
            seen: set[str] = set()
            for item in summaries:
                name = str(item.get("name") or "")
                seen.add(name)
                live = live_sessions.get(name)
                if live is not None:
                    item["pid"] = live.pid
                    item["shell"] = live.shell
                    item["live"] = True
                    item["owned_by_client"] = name in client_live
                else:
                    item["shell"] = self.default_shell or _default_shell()
                    item["live"] = False
                    item["owned_by_client"] = False

            for session in live_sessions.values():
                if session.name in seen:
                    continue
                summaries.append(
                    {
                        "name": session.name,
                        "pid": session.pid,
                        "shell": session.shell,
                        "live": True,
                        "owned_by_client": session.name in client_live,
                    }
                )
            return summaries

        return [
            {
                "name": session.name,
                "pid": session.pid,
                "shell": session.shell,
                "live": True,
                "owned_by_client": session.name in client_live,
            }
            for session in client_live.values()
        ]

    def _ensure_tmux_conf(self) -> str | None:
        """Write the isolated tmux config once; return its path (or None).

        Best-effort and idempotent: if ``~/.hermes`` isn't writable we fall back
        to no ``-f`` (tmux defaults), which still works — just without the
        escape-time/truecolor hardening. Written lazily so unit tests that only
        exercise the pure helpers never touch the filesystem.
        """
        if self._tmux_conf_written:
            return self._tmux_conf_path
        self._tmux_conf_written = True
        try:
            conf_dir = os.path.expanduser("~/.hermes")
            os.makedirs(conf_dir, exist_ok=True)
            path = os.path.join(conf_dir, "hermes-relay-tmux.conf")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(_TMUX_CONF)
            self._tmux_conf_path = path
            logger.info("Wrote isolated tmux config: %s", path)
        except OSError as exc:  # noqa: BLE001 — degrade to tmux defaults
            logger.warning(
                "Could not write tmux config (%s); using tmux defaults", exc
            )
            self._tmux_conf_path = None
        return self._tmux_conf_path

    def _tmux_base_args(self) -> list[str]:
        """Global tmux flags every invocation must share: dedicated socket +
        (when available) our config file. Ensures the config exists before any
        command that might start the server."""
        conf = self._ensure_tmux_conf()
        args = ["-L", _TMUX_SOCKET]
        if conf:
            args += ["-f", conf]
        return args

    async def _run_tmux(
        self,
        *args: str,
        capture: bool = False,
    ) -> tuple[int, str]:
        tmux_binary = self._tmux_path or "tmux"
        stdout = asyncio.subprocess.PIPE if capture else asyncio.subprocess.DEVNULL
        proc = await asyncio.create_subprocess_exec(
            tmux_binary,
            *self._tmux_base_args(),
            *args,
            stdout=stdout,
            stderr=asyncio.subprocess.DEVNULL,
        )
        out, _ = await proc.communicate()
        text = out.decode("utf-8", errors="replace") if out else ""
        return proc.returncode or 0, text

    async def _tmux_session_exists(self, session_name: str) -> bool:
        try:
            code, _ = await self._run_tmux(
                "has-session",
                "-t",
                _tmux_session_name(session_name),
            )
            return code == 0
        except Exception as exc:  # noqa: BLE001
            logger.debug("tmux has-session failed for %s: %s", session_name, exc)
            return False

    async def _capture_tmux_replay(self, session_name: str) -> str:
        try:
            code, text = await self._run_tmux(
                "capture-pane",
                "-t",
                _tmux_session_name(session_name),
                "-p",
                "-S",
                f"-{_TMUX_REPLAY_LINES}",
                capture=True,
            )
            if code != 0:
                return ""
            if len(text) > _TMUX_REPLAY_MAX_BYTES:
                text = text[-_TMUX_REPLAY_MAX_BYTES:]
            return text
        except Exception as exc:  # noqa: BLE001
            logger.debug("tmux capture-pane failed for %s: %s", session_name, exc)
            return ""

    async def _kill_tmux_session(self, session_name: str) -> bool:
        try:
            code, _ = await self._run_tmux(
                "kill-session",
                "-t",
                _tmux_session_name(session_name),
            )
            return code == 0
        except Exception as exc:  # noqa: BLE001
            logger.warning("tmux kill-session failed for %s: %s", session_name, exc)
            return False

    async def _list_tmux_sessions(self) -> list[dict[str, Any]]:
        try:
            code, text = await self._run_tmux(
                "list-sessions",
                "-F",
                "#{session_name}\t#{session_attached}\t#{session_windows}\t#{session_created}",
                capture=True,
            )
            if code != 0:
                return []
            sessions: list[dict[str, Any]] = []
            for line in text.splitlines():
                parsed = _parse_tmux_session_line(line)
                if parsed is not None:
                    sessions.append(parsed)
            sessions.sort(key=lambda item: str(item.get("name") or ""))
            return sessions
        except Exception as exc:  # noqa: BLE001
            logger.debug("tmux list-sessions failed: %s", exc)
            return []

    async def _close_session(
        self,
        session: _Session,
        reason: str = "",
        preserve_shell: bool = False,
    ) -> None:
        """Tear down a session.

        Args:
            session: The session to close.
            reason: Human-readable reason for the close (sent in the
                ``terminal.detached`` envelope).
            preserve_shell: If True, only the local PTY-side resources are
                released — the child process (the tmux client running inside
                the PTY) is left to exit on its own. With tmux this means the
                tmux server keeps the bash session alive in the background so
                the next attach with the same session_name re-enters it. The
                default is False (legacy behavior: SIGHUP + reap + SIGKILL),
                which is correct for bare-bash spawns and for unrecoverable
                errors. Tmux call sites pass True via the corresponding
                ``preserve_shell=self.tmux_available`` argument from the
                handlers above.
        """
        if session.closed:
            return
        session.closed = True

        if session.flush_handle is not None:
            session.flush_handle.cancel()
            session.flush_handle = None

        # Flush anything still pending — one last chance to deliver output.
        if session.buffer:
            try:
                text = bytes(session.buffer).decode("utf-8", errors="replace")
                session.buffer.clear()
                await _send(
                    session.ws,
                    _make_envelope(
                        "terminal.output",
                        {"session_name": session.name, "data": text},
                    ),
                )
            except Exception:  # noqa: BLE001
                pass

        # Unhook from the event loop before closing the fd.
        try:
            asyncio.get_running_loop().remove_reader(session.master_fd)
        except (ValueError, KeyError):
            pass

        if not preserve_shell:
            # Legacy / bare-bash path: SIGHUP, then reap, then SIGKILL on
            # holdouts. This matches the pre-tmux behavior exactly.
            try:
                os.kill(session.pid, signal.SIGHUP)
            except ProcessLookupError:
                pass

            try:
                for _ in range(20):  # up to ~1s of grace
                    pid, _status = os.waitpid(session.pid, os.WNOHANG)
                    if pid != 0:
                        break
                    await asyncio.sleep(0.05)
                else:
                    try:
                        os.kill(session.pid, signal.SIGKILL)
                        os.waitpid(session.pid, 0)
                    except (ProcessLookupError, ChildProcessError):
                        pass
            except (ChildProcessError, ProcessLookupError):
                pass
        else:
            # tmux preservation path: closing the PTY master makes the tmux
            # *client* (the foreground process inside the PTY) detach
            # cleanly. The tmux *server*, which actually owns the bash
            # process, keeps running outside our process tree. We do a
            # non-blocking reap so we don't leak a zombie if the client
            # exits quickly, but we never SIGHUP / SIGKILL — that would
            # defeat the whole point of persistence.
            try:
                os.waitpid(session.pid, os.WNOHANG)
            except (ChildProcessError, ProcessLookupError):
                pass

        try:
            os.close(session.master_fd)
        except OSError:
            pass

        client_sessions = self._sessions.get(session.ws)
        if client_sessions is not None:
            client_sessions.pop(session.name, None)
            if not client_sessions:
                self._sessions.pop(session.ws, None)

        if not session.ws.closed:
            try:
                await _send(
                    session.ws,
                    _make_envelope(
                        "terminal.detached",
                        {"session_name": session.name, "reason": reason},
                    ),
                )
            except Exception:  # noqa: BLE001
                pass

        logger.info(
            "Terminal session closed: %s (%s, preserve_shell=%s)",
            session.name,
            reason,
            preserve_shell,
        )

    async def close(self) -> None:
        """Close every open session — called from RelayServer.close().

        With tmux, the relay shutting down does NOT mean the user wants
        their shells killed — the tmux server keeps running outside our
        process tree and the next relay boot will re-attach. With bare-bash
        the shell dies with us and we may as well clean it up.
        """
        for ws, sessions in list(self._sessions.items()):
            for session in list(sessions.values()):
                await self._close_session(
                    session,
                    reason="server shutdown",
                    preserve_shell=self.tmux_available,
                )


# ── Private helpers ──────────────────────────────────────────────────────


def _tmux_session_name(client_session_name: str) -> str:
    """Sanitize a client-provided session name for tmux.

    Tmux session names cannot contain ``.``, ``:``, or whitespace, so we
    replace any of those with ``_``. We also prefix with ``hermes-`` so our
    sessions don't collide with the user's personal tmux setup — anyone
    looking at ``tmux ls`` can immediately tell which sessions belong to
    the relay.
    """
    sanitized_chars: list[str] = []
    for ch in client_session_name:
        if ch.isspace() or ch in ".:":
            sanitized_chars.append("_")
        else:
            sanitized_chars.append(ch)
    sanitized = "".join(sanitized_chars).strip("_") or "default"
    return f"{_TMUX_PREFIX}{sanitized}"


def _client_session_name(tmux_session_name: str) -> str | None:
    if not tmux_session_name.startswith(_TMUX_PREFIX):
        return None
    return tmux_session_name[len(_TMUX_PREFIX):] or "default"


def _parse_tmux_session_line(line: str) -> dict[str, Any] | None:
    parts = line.split("\t")
    if len(parts) < 4:
        return None
    tmux_name = parts[0]
    name = _client_session_name(tmux_name)
    if name is None:
        return None

    def parse_int(raw: str) -> int:
        try:
            return int(raw)
        except (TypeError, ValueError):
            return 0

    return {
        "name": name,
        "tmux_name": tmux_name,
        "attached": parse_int(parts[1]),
        "windows": parse_int(parts[2]),
        "created_at": parse_int(parts[3]),
    }


def _set_winsize(fd: int, rows: int, cols: int) -> None:
    """Set terminal window size via TIOCSWINSZ ioctl."""
    if not _PTY_AVAILABLE:
        return
    winsize = struct.pack("HHHH", rows, cols, 0, 0)
    fcntl.ioctl(fd, termios.TIOCSWINSZ, winsize)


async def _send(ws: web.WebSocketResponse, text: str) -> None:
    """Send text on the WebSocket, ignoring already-closed errors."""
    if ws.closed:
        return
    try:
        await ws.send_str(text)
    except (ConnectionResetError, RuntimeError):
        pass
