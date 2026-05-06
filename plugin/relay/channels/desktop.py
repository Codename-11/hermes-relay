"""Desktop channel handler — tool routing (Phase B) + workspace awareness.

Two jobs over the same WSS ``desktop`` envelope stream:

1. **Tool routing (Phase B / alpha.1).** Mirror of the ``bridge``
   channel for the Node thin-client CLI / future TUI. Agent-side
   Python tools in ``plugin/tools/desktop_tool.py`` POST to
   ``/desktop/*`` HTTP routes; the relay forwards them over the
   ``desktop.command`` envelope; the connected client services them
   locally and returns ``desktop.response``, which bubbles back as the
   HTTP response. Single-client MVP — the most recent client wins
   ``self.client_ws``. ``self.pending`` is asyncio-locked so concurrent
   add/remove can't drop futures. Normal desktop tools keep the bridge-like
   30 s timeout; computer-use calls get a longer timeout because they may
   wait on a visible human approval prompt.

2. **Workspace awareness (alpha.6).** The desktop CLI advertises its
   local workspace once per auth.ok and optionally polls an
   active-editor hint every ~5 s. Both stash into a per-WS
   :class:`DesktopSession`. In-memory only — no persistence; the next
   client auth re-advertises if the relay restarts.

Wire envelopes (frozen — do not rename fields):
  * ``desktop.command``       — server → client: ``{request_id, tool, args}``
  * ``desktop.response``      — client → server: ``{request_id, status, result}``
  * ``desktop.status``        — client → server: ``{advertised_tools, host?, platform?, cwd?, ...}``
  * ``desktop.workspace``     — client → server: opaque dict (``cwd`` / ``git_root`` / ``git_branch`` / ...)
  * ``desktop.active_editor`` — client → server: opaque dict (``source`` / ``editor`` / ...)
  * ``desktop.error``         — server → client: ``{message, request_id?}`` (advisory)

Unknown envelope types log-and-drop (forward-compat).
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from collections import deque
from dataclasses import asdict, dataclass, field
from typing import Any

from aiohttp import web

logger = logging.getLogger(__name__)


RESPONSE_TIMEOUT = 30.0  # seconds — matches bridge.RESPONSE_TIMEOUT
COMPUTER_USE_RESPONSE_TIMEOUT = 180.0  # seconds — allows human approval prompts

# Keys whose values must never appear in the ring buffer. Matched
# case-insensitively against the full key name.
_REDACT_KEYS = frozenset({"password", "token", "secret", "otp", "bearer", "api_key"})

# Cap for the recent-commands ring buffer.
RECENT_COMMANDS_MAX = 100


class DesktopError(Exception):
    """Raised when a desktop command cannot be dispatched or times out."""


def _redact_args(value: Any) -> Any:
    """Return a copy of ``value`` with sensitive-key values replaced by
    ``"[redacted]"``. Recurses into nested dicts and lists.
    """
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for k, v in value.items():
            if isinstance(k, str) and k.lower() in _REDACT_KEYS:
                out[k] = "[redacted]"
            else:
                out[k] = _redact_args(v)
        return out
    if isinstance(value, list):
        return [_redact_args(v) for v in value]
    return value


@dataclass
class DesktopCommandRecord:
    """Audit entry for a single desktop command, stored in the ring buffer."""

    request_id: str
    tool: str
    args: dict[str, Any] = field(default_factory=dict)
    sent_at: float = 0.0  # epoch milliseconds
    response_status: int | None = None
    result_summary: str | None = None
    error: str | None = None
    # One of: pending, executed, error, timeout
    decision: str = "pending"


class DesktopSession:
    """Per-WebSocket scratch space.

    Holds BOTH:
      * The single-client tool-routing latch fields (``advertised_tools``
        / ``client_status`` / ``last_seen_at``) — these are mirrored on
        the outer :class:`DesktopHandler` for the latched client and
        kept here per-WS for diagnostics + future multi-client support.
      * The workspace + active-editor snapshots advertised by the
        desktop CLI (alpha.6).

    All fields are best-effort — a client may advertise a workspace
    with only ``cwd`` and ``hostname`` if git isn't installed, may
    never send an active-editor hint at all, or may not advertise any
    tools. Consumers should null-check liberally.
    """

    def __init__(self) -> None:
        # Latest ``desktop.workspace`` payload as-sent. Opaque dict — we
        # don't parse fields here because the wire schema is owned by
        # the client (versioned as ``version: 1``); future versions
        # round-trip harmlessly.
        self.workspace_context: dict[str, Any] | None = None
        self.workspace_received_at: float | None = None

        # Latest ``desktop.active_editor`` payload. Gets overwritten in
        # place on each poll — we don't keep history.
        self.active_editor: dict[str, Any] | None = None
        self.active_editor_received_at: float | None = None

        # Per-WS view of the latest ``desktop.status`` envelope. The
        # outer handler also keeps a flattened copy for the latched
        # client; storing it here makes future multi-client diagnostics
        # straightforward.
        self.advertised_tools: set[str] = set()
        self.client_status: dict[str, Any] = {}
        self.last_seen_at: float | None = None


def _envelope(msg_type: str, payload: dict[str, Any], msg_id: str | None = None) -> str:
    return json.dumps(
        {
            "channel": "desktop",
            "type": msg_type,
            "id": msg_id or str(uuid.uuid4()),
            "payload": payload,
        }
    )


class DesktopHandler:
    """Routes agent tool calls to the connected desktop client AND
    stashes per-WS workspace context.

    All state is held on the handler instance — there is no module-level
    global. Lifetime matches :class:`plugin.relay.server.RelayServer`: one
    handler per relay process, reused across client reconnects.
    """

    def __init__(self) -> None:
        # The currently-connected desktop client's WebSocket. Assigned
        # when a client sends its first desktop envelope and cleared via
        # :meth:`detach_ws` when the client disconnects.
        #
        # TODO(multi-client): graduate to a dict keyed by session_id +
        # per-tool routing. For the MVP the latest-wins model matches
        # bridge.py exactly.
        self.client_ws: web.WebSocketResponse | None = None

        # Tools advertised by the currently-attached client. Populated
        # from ``desktop.status`` envelopes. Cleared on detach.
        self.advertised_tools: set[str] = set()

        # Metadata from the latest ``desktop.status`` (host, platform,
        # version, cwd, ...). Not interpreted by the handler — just
        # surfaced for diagnostics + the ``/desktop/_ping`` endpoint.
        self.client_status: dict[str, Any] = {}
        self.last_seen_at: float | None = None

        # request_id → Future. Populated by handle_command before the
        # command is sent; resolved by handle_response; cancelled with
        # ConnectionError on detach.
        self.pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._lock = asyncio.Lock()

        # Bounded ring buffer of recent commands.
        self.recent_commands: deque[DesktopCommandRecord] = deque(
            maxlen=RECENT_COMMANDS_MAX
        )

        # ws → DesktopSession. Cleared per-ws in :meth:`detach_ws` so
        # disconnected clients don't leak session structs. Lives
        # alongside the single-client tool-routing fields above; the
        # latched client always has an entry here too.
        self._sessions: dict[web.WebSocketResponse, DesktopSession] = {}

    # ── Envelope dispatch ────────────────────────────────────────────────

    async def handle_envelope(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
    ) -> None:
        """Route an incoming desktop-channel envelope from the client.

        Dispatches on the bare ``type`` field (after stripping any
        leading ``desktop.`` prefix that older clients/servers may
        still emit on the wire). Recognized types:

          * ``command``        — server → client only; logged + dropped
            if seen from a client direction.
          * ``response``       — client → server tool result; correlates
            to a pending future (see :meth:`handle_response`).
          * ``status``         — client heartbeat advertising toolset.
          * ``workspace``      — capture WorkspaceContext snapshot.
          * ``active_editor``  — capture ActiveEditorHint snapshot.

        Unknown types log-and-drop for forward compat.
        """
        raw_type = envelope.get("type", "")
        # Accept both bare ("response") and prefixed ("desktop.response")
        # forms — some clients have shipped with the prefixed names and
        # the server has historically tolerated both.
        msg_type = raw_type
        if isinstance(msg_type, str) and msg_type.startswith("desktop."):
            msg_type = msg_type[len("desktop.") :]

        payload = envelope.get("payload") or {}
        if not isinstance(payload, dict):
            logger.debug(
                "desktop: dropping non-dict payload (type=%s value_type=%s)",
                raw_type,
                type(payload).__name__,
            )
            return

        # Ensure a per-WS session struct exists for any client that
        # speaks on this channel — even tool-routing clients that never
        # send a workspace envelope still get an entry, which keeps
        # all_sessions() honest about who's connected.
        session = self._sessions.get(ws)
        if session is None:
            session = DesktopSession()
            self._sessions[ws] = session

        if msg_type == "response":
            # Tool routing — client is replying to a pending command.
            await self._latch_client_ws(ws)
            await self.handle_response(ws, envelope)
            return

        if msg_type == "status":
            await self._latch_client_ws(ws)
            await self.handle_status(ws, envelope, session=session)
            return

        if msg_type == "command":
            # Commands are server → client only. Seeing one from a
            # client direction means a buggy client; log + drop.
            logger.warning("desktop: ignoring unexpected desktop.command from client")
            return

        if msg_type == "workspace":
            session.workspace_context = dict(payload)
            session.workspace_received_at = time.time()
            logger.info(
                "desktop: workspace advertised repo=%s branch=%s host=%s",
                payload.get("repo_name", "(none)"),
                payload.get("git_branch", "(none)"),
                payload.get("hostname", "(none)"),
            )
            return

        if msg_type == "active_editor":
            session.active_editor = dict(payload)
            session.active_editor_received_at = time.time()
            logger.debug(
                "desktop: active_editor source=%s editor=%s",
                payload.get("source", "?"),
                payload.get("editor", "(none)"),
            )
            return

        logger.debug("desktop: ignoring unknown type %r", raw_type)

    # Backwards-compat alias. Older callers (and tests written against
    # either the alpha.1 or alpha.6 file) call ``handle()``; the merged
    # API name is ``handle_envelope`` but both work.
    async def handle(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
    ) -> None:
        await self.handle_envelope(ws, envelope)

    async def _latch_client_ws(self, ws: web.WebSocketResponse) -> None:
        """Opportunistically latch ``ws`` as the active tool-routing client.

        Mirrors alpha.1's behaviour: the first envelope wins, but a new
        ws can take over and any pending futures bound to the old ws are
        failed with a ``replaced by new client`` error.
        """
        if self.client_ws is ws:
            return
        if self.client_ws is not None and not self.client_ws.closed:
            logger.info(
                "desktop: replacing previous client ws — new client took over"
            )
            await self._fail_pending("replaced by new client")
        self.client_ws = ws

    # ── Outbound commands (called from HTTP handlers) ────────────────────

    async def handle_command(
        self,
        method: str,
        args: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Dispatch a ``desktop_*`` tool call to the connected client.

        Returns the parsed ``desktop.response`` payload
        ``{request_id, status, result}``. Raises :class:`DesktopError` if
        no client is connected, the send fails, or the client doesn't
        respond within the tool-specific response timeout.
        """
        ws = self.client_ws
        if ws is None or ws.closed:
            raise DesktopError(
                "No desktop client connected. Start the Hermes desktop CLI and pair it."
            )

        # Fail-closed on tools the client hasn't advertised. This is what
        # lets the agent side's ``check_fn`` tell Hermes "this tool isn't
        # available right now" without even attempting a round-trip.
        if method.startswith("desktop_computer_") and not self.advertised_tools:
            raise DesktopError(
                f"Desktop client has not advertised experimental computer-use tool {method!r}"
            )
        if self.advertised_tools and method not in self.advertised_tools:
            raise DesktopError(
                f"Desktop client does not advertise tool {method!r}"
            )

        request_id = str(uuid.uuid4())
        future: asyncio.Future[dict[str, Any]] = asyncio.get_event_loop().create_future()

        async with self._lock:
            self.pending[request_id] = future

        command_payload = {
            "request_id": request_id,
            "tool": method,
            "args": args or {},
        }
        logger.info(
            "desktop >>> %s args=%s",
            method,
            json.dumps(_redact_args(args or {})),
        )

        record = DesktopCommandRecord(
            request_id=request_id,
            tool=method,
            args=_redact_args(args or {}),
            sent_at=time.time() * 1000.0,
            decision="pending",
        )
        self.recent_commands.append(record)

        try:
            await ws.send_str(_envelope("desktop.command", command_payload, request_id))
        except Exception as exc:
            async with self._lock:
                self.pending.pop(request_id, None)
            record.decision = "error"
            record.error = f"Failed to send command to client: {exc}"
            logger.error("desktop: failed to send command: %s", exc)
            raise DesktopError(f"Failed to send command to client: {exc}") from exc

        timeout = (
            COMPUTER_USE_RESPONSE_TIMEOUT
            if method.startswith("desktop_computer_")
            else RESPONSE_TIMEOUT
        )
        try:
            return await asyncio.wait_for(future, timeout=timeout)
        except asyncio.TimeoutError:
            async with self._lock:
                self.pending.pop(request_id, None)
            record.decision = "timeout"
            record.error = f"Desktop client did not respond within {timeout:.0f}s"
            logger.warning(
                "desktop: client did not respond within %.0fs for %s",
                timeout,
                method,
            )
            raise DesktopError(
                f"Desktop client did not respond within {timeout:.0f}s"
            ) from None

    # ── Inbound response routing ────────────────────────────────────────

    async def handle_response(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
    ) -> None:
        """Resolve the pending future for an incoming ``desktop.response``."""
        payload = envelope.get("payload") or {}
        request_id = payload.get("request_id")
        if not isinstance(request_id, str) or not request_id:
            logger.warning("desktop: response missing request_id: %s", payload)
            return

        async with self._lock:
            future = self.pending.pop(request_id, None)

        self._update_record_from_response(request_id, payload)

        if future is None:
            logger.debug("desktop: no pending future for request_id=%s", request_id)
            return

        if not future.done():
            future.set_result(payload)

    def _update_record_from_response(
        self, request_id: str, payload: dict[str, Any]
    ) -> None:
        record: DesktopCommandRecord | None = None
        for entry in self.recent_commands:
            if entry.request_id == request_id:
                record = entry
                break
        if record is None:
            return

        status = payload.get("status")
        if isinstance(status, int):
            record.response_status = status

        result = payload.get("result")
        error_msg = payload.get("error")

        if isinstance(error_msg, str) and error_msg:
            record.error = error_msg
        if result is not None and record.result_summary is None:
            try:
                summary = json.dumps(result, default=str)
            except (TypeError, ValueError):
                summary = str(result)
            if len(summary) > 500:
                summary = summary[:497] + "..."
            record.result_summary = summary

        if isinstance(status, int) and status >= 400:
            record.decision = "error"
        elif isinstance(status, int):
            record.decision = "executed"

    async def handle_status(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
        session: DesktopSession | None = None,
    ) -> None:
        """Cache the latest status snapshot + advertised toolset from the client."""
        payload = envelope.get("payload") or {}
        if not isinstance(payload, dict):
            return
        self.client_status = dict(payload)
        self.last_seen_at = time.time()

        advertised = payload.get("advertised_tools")
        if isinstance(advertised, list):
            self.advertised_tools = {
                name for name in advertised if isinstance(name, str) and name
            }

        # Mirror onto the per-WS session so future multi-client
        # diagnostics can see exactly what each client advertised.
        if session is None:
            session = self._sessions.get(ws)
        if session is not None:
            session.client_status = dict(self.client_status)
            session.last_seen_at = self.last_seen_at
            session.advertised_tools = set(self.advertised_tools)

        logger.debug(
            "desktop: status update advertised=%d keys=%s",
            len(self.advertised_tools),
            sorted(self.client_status.keys()),
        )

    # ── Public API for HTTP + tool handlers ─────────────────────────────

    def is_client_connected(self) -> bool:
        ws = self.client_ws
        return ws is not None and not ws.closed

    def has_client_for(self, tool_name: str) -> bool:
        """True if a client is connected AND advertises ``tool_name``.

        If the client never sent a ``desktop.status`` (empty advertised
        set), we optimistically return True when connected — lets older
        clients that don't advertise still work. Clients that DO
        advertise take the strict path.
        """
        if not self.is_client_connected():
            return False
        if tool_name.startswith("desktop_computer_") and not self.advertised_tools:
            return False
        if not self.advertised_tools:
            # Client hasn't advertised yet — assume it can handle the call.
            return True
        return tool_name in self.advertised_tools

    def status_snapshot(self) -> dict[str, Any]:
        """Dict suitable for ``/desktop/_ping`` and diagnostics."""
        return {
            "connected": self.is_client_connected(),
            "advertised_tools": sorted(self.advertised_tools),
            "client_status": dict(self.client_status),
            "last_seen_at": self.last_seen_at,
            "pending_commands": len(self.pending),
        }

    # ── Workspace / editor accessors (alpha.6) ──────────────────────────

    def session_for(self, ws: web.WebSocketResponse) -> DesktopSession | None:
        """Return the DesktopSession for ``ws`` if any.

        Exposed so future plugin hooks / HTTP introspection routes can
        read ``session.workspace_context`` / ``session.active_editor``
        without importing the internal dict.
        """
        return self._sessions.get(ws)

    def all_sessions(self) -> list[DesktopSession]:
        """Snapshot every known session. Useful for a future
        ``/desktop/sessions`` debug route that lists what every
        connected client has advertised.
        """
        return list(self._sessions.values())

    # ── Activity feed ───────────────────────────────────────────────────

    def get_recent(self, limit: int = RECENT_COMMANDS_MAX) -> list[dict[str, Any]]:
        if limit <= 0:
            return []
        out: list[dict[str, Any]] = []
        for record in reversed(self.recent_commands):
            out.append(asdict(record))
            if len(out) >= limit:
                break
        return out

    # ── Lifecycle ───────────────────────────────────────────────────────

    async def _fail_pending(self, reason: str) -> None:
        async with self._lock:
            pending = dict(self.pending)
            self.pending.clear()
        if pending:
            err = ConnectionError(
                f"Desktop client disconnected ({reason})" if reason else
                "Desktop client disconnected"
            )
            for fut in pending.values():
                if not fut.done():
                    fut.set_exception(err)
            logger.info(
                "desktop: failed %d pending commands (%s)",
                len(pending),
                reason or "unknown",
            )

    async def detach_ws(
        self,
        ws: web.WebSocketResponse,
        reason: str = "",
    ) -> None:
        """Drop per-ws state when a client disconnects.

        Cleans up BOTH:
          * Tool-routing state — if ``ws`` is the latched client, clear
            the latch and fail any in-flight futures with a
            "client disconnected" ConnectionError.
          * Workspace state — pop the per-ws DesktopSession entry.

        Called from the main ``_on_disconnect`` path in ``server.py``.
        """
        # Workspace cleanup first (so detach is observable even if the
        # ws was never latched as the tool-routing client).
        session = self._sessions.pop(ws, None)
        if session is not None and session.workspace_context is not None:
            logger.debug(
                "desktop: session detached (had workspace from %s)",
                session.workspace_context.get("hostname", "?"),
            )

        # Tool-routing cleanup — only if this ws was the latched client.
        if self.client_ws is ws:
            self.client_ws = None
            self.advertised_tools = set()
            # keep client_status around for post-mortem diagnostics —
            # it's small and the next connect overwrites it anyway.
            await self._fail_pending(reason)

    async def close(self) -> None:
        """Server shutdown — cancel all pending commands, drop the
        client ref, and clear all per-ws workspace sessions.
        """
        self.client_ws = None
        self.advertised_tools = set()
        self._sessions.clear()
        await self._fail_pending("Relay server shutting down")


# Backwards-compat alias. The alpha.6 file exported this name; some
# callers may still import ``DesktopChannel`` directly.
DesktopChannel = DesktopHandler
