"""Desktop channel handler — tool routing (Phase B) + workspace awareness.

Two jobs over the same WSS ``desktop`` envelope stream:

1. **Tool routing (Phase B / alpha.1).** Mirror of the ``bridge``
   channel for the Node thin-client CLI / future TUI. Agent-side
   Python tools in ``plugin/tools/desktop_tool.py`` POST to
   ``/desktop/*`` HTTP routes; the relay forwards them over the
   ``desktop.command`` envelope; the explicitly selected client services
   them locally and returns ``desktop.response``, which bubbles back as the
   HTTP response. Multiple clients are keyed by stable device identity;
   untargeted dispatch fails closed when more than one is connected.
   ``self.pending`` is asyncio-locked so concurrent add/remove can't drop
   futures. Normal desktop tools keep the bridge-like
   30 s timeout; computer-use calls get a longer timeout because they may
   wait on a visible human approval prompt.

2. **Workspace awareness (alpha.6).** The desktop CLI advertises its
   local workspace once per auth.ok and optionally polls an
   active-editor hint every ~5 s. Both stash into a per-WS
   :class:`DesktopSession`. In-memory only — no persistence; the next
   client auth re-advertises if the relay restarts.

Wire envelopes (frozen — do not rename fields):
  * ``desktop.command``       — server → client: ``{request_id, tool, args, control_session?}``
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
ADB_RESPONSE_TIMEOUT = 300.0  # seconds — approval plus a bounded 120 s operation

# Keys whose values must never appear in the ring buffer. Matched
# case-insensitively against the full key name.
_REDACT_KEYS = frozenset({"password", "token", "secret", "otp", "bearer", "api_key"})

# Cap for the recent-commands ring buffer.
RECENT_COMMANDS_MAX = 100
CONTROL_SESSIONS_MAX = 256
CONTROL_SESSION_IDLE_SECONDS = 3600.0


class DesktopError(Exception):
    """Raised when a desktop command cannot be dispatched or times out."""


@dataclass(frozen=True)
class DesktopRequesterContext:
    """Trusted caller context supplied by the loopback HTTP boundary."""

    requester_device_id: str | None = None
    chat_session_id: str | None = None
    run_id: str | None = None
    profile: str | None = None


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
      * The routing identity and capability fields (``device_id``,
        ``advertised_tools``, ``client_status``, and ``last_seen_at``).
        These are authoritative for targeted multi-client dispatch; the
        outer :class:`DesktopHandler` mirrors the most recent status only
        for backward-compatible diagnostics.
      * The workspace + active-editor snapshots advertised by the
        desktop CLI (alpha.6).

    All fields are best-effort — a client may advertise a workspace
    with only ``cwd`` and ``hostname`` if git isn't installed, may
    never send an active-editor hint at all, or may not advertise any
    tools. Consumers should null-check liberally.
    """

    def __init__(self) -> None:
        self.device_id: str = ""
        self.device_name: str = ""
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

        # Per-WS view of the latest ``desktop.status`` envelope. The outer
        # handler also keeps a flattened compatibility snapshot, while all
        # routing decisions use this per-client state.
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
        # Backward-compatible pointer to the most recently active desktop.
        # It is never used to choose among multiple connected clients;
        # targeted routing uses ``_sessions`` and fails closed if the caller
        # omits a selector while multiple desktops are online.
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
        self.pending_ws: dict[str, web.WebSocketResponse] = {}
        self._lock = asyncio.Lock()

        # Bounded ring buffer of recent commands.
        self.recent_commands: deque[DesktopCommandRecord] = deque(
            maxlen=RECENT_COMMANDS_MAX
        )

        # ws → DesktopSession. This is the authoritative connected-target
        # registry. Cleared per-ws in :meth:`detach_ws` so disconnected
        # clients don't leak session structs.
        self._sessions: dict[web.WebSocketResponse, DesktopSession] = {}
        # Stable opaque ids for active computer-control runs. Callers provide
        # only trusted context fields; this handler always owns the id and
        # binds every emitted identity to the selected target + request id.
        self._control_sessions: dict[tuple[str, str, str, str, str], tuple[str, float]] = {}

    def _control_session(
        self,
        *,
        request_id: str,
        target_device_id: str,
        requester: DesktopRequesterContext | None,
    ) -> dict[str, Any] | None:
        context = requester or DesktopRequesterContext()
        # A server-owned UUID is not useful authority by itself. Require an
        # executor-authenticated requester and run before advertising the
        # identity; older/unauthenticated callers stay in compatibility mode.
        if not context.requester_device_id or not context.run_id:
            return None
        now = time.monotonic()
        expired = [
            key for key, (_session_id, touched) in self._control_sessions.items()
            if now - touched > CONTROL_SESSION_IDLE_SECONDS
        ]
        for key in expired:
            self._control_sessions.pop(key, None)
        key = (
            context.requester_device_id,
            context.chat_session_id or "",
            context.run_id or "",
            context.profile or "",
            target_device_id or "legacy-desktop",
        )
        current = self._control_sessions.get(key)
        if current is None:
            if len(self._control_sessions) >= CONTROL_SESSIONS_MAX:
                oldest = min(self._control_sessions, key=lambda item: self._control_sessions[item][1])
                self._control_sessions.pop(oldest, None)
            session_id = f"control-{uuid.uuid4()}"
        else:
            session_id = current[0]
        self._control_sessions[key] = (session_id, now)
        return {
            "version": 1,
            "id": session_id,
            "request_id": request_id,
            "requester_device_id": key[0],
            "target_device_id": key[4],
            **({"chat_session_id": context.chat_session_id} if context.chat_session_id else {}),
            **({"run_id": context.run_id} if context.run_id else {}),
            **({"profile": context.profile} if context.profile else {}),
        }

    # ── Envelope dispatch ────────────────────────────────────────────────

    async def handle_envelope(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
        auth_session: Any | None = None,
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
        if auth_session is not None:
            auth_device_id = str(getattr(auth_session, "device_id", "") or "").strip()
            if auth_device_id.casefold() not in {"", "unknown", "none", "null"}:
                session.device_id = auth_device_id
            session.device_name = str(getattr(auth_session, "device_name", "") or "").strip()

        if msg_type == "response":
            # Tool routing — client is replying to a pending command.
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
        session: Any | None = None,
    ) -> None:
        await self.handle_envelope(ws, envelope, auth_session=session)

    async def _latch_client_ws(self, ws: web.WebSocketResponse) -> None:
        """Refresh the legacy most-recent-client diagnostics pointer.

        Existing clients and their in-flight requests remain attached.
        Actual dispatch is resolved from the complete per-WebSocket session
        registry, never from this compatibility pointer when several targets
        are connected.
        """
        if self.client_ws is ws:
            return
        # Keep the compatibility/default pointer, but do not evict another
        # connected PC or fail its in-flight requests. Explicit routing uses
        # the per-WebSocket session registry below.
        self.client_ws = ws

    def _connected_targets(self) -> list[tuple[web.WebSocketResponse, DesktopSession]]:
        return [(ws, session) for ws, session in self._sessions.items() if not ws.closed]

    @staticmethod
    def _normalized_selector(value: str) -> str:
        return "".join(ch for ch in value.casefold() if ch.isalnum())

    def _resolve_target(
        self,
        selector: str | None,
    ) -> tuple[web.WebSocketResponse, DesktopSession | None]:
        targets = self._connected_targets()
        if selector and selector.strip():
            raw = selector.strip()
            normalized = self._normalized_selector(raw)
            matches: list[tuple[web.WebSocketResponse, DesktopSession]] = []
            for ws, session in targets:
                status = session.client_status
                candidates = {
                    session.device_id,
                    session.device_name,
                    str(status.get("device_id", "")),
                    str(status.get("device_name", "")),
                    str(status.get("host", "")),
                }
                if any(
                    candidate
                    and (
                        candidate.casefold() == raw.casefold()
                        or self._normalized_selector(candidate) == normalized
                    )
                    for candidate in candidates
                ):
                    matches.append((ws, session))
            if not matches:
                available = [
                    session.device_name
                    or str(session.client_status.get("host", ""))
                    or session.device_id
                    for _, session in targets
                ]
                raise DesktopError(
                    f"Unknown desktop device selector {raw!r}. Available: "
                    f"{', '.join(filter(None, available)) or 'none'}"
                )
            if len(matches) > 1:
                raise DesktopError(f"Ambiguous desktop device selector {raw!r}; use its device_id")
            return matches[0]

        if len(targets) == 1:
            return targets[0]
        if len(targets) > 1:
            available = [
                (
                    f"{session.device_name or session.client_status.get('host') or 'desktop'} "
                    f"({session.device_id or session.client_status.get('device_id') or 'legacy'})"
                )
                for _, session in targets
            ]
            raise DesktopError(
                "Multiple desktop clients are connected; pass device or device_id explicitly. "
                + "Available: "
                + ", ".join(available)
            )
        ws = self.client_ws
        if ws is not None and not ws.closed:
            return ws, self._sessions.get(ws)
        raise DesktopError(
            "No desktop client connected. Start the Hermes desktop CLI and pair it."
        )

    # ── Outbound commands (called from HTTP handlers) ────────────────────

    async def handle_command(
        self,
        method: str,
        args: dict[str, Any] | None = None,
        device: str | None = None,
        requester: DesktopRequesterContext | None = None,
    ) -> dict[str, Any]:
        """Dispatch a ``desktop_*`` tool call to the connected client.

        Returns the parsed ``desktop.response`` payload
        ``{request_id, status, result}``. Raises :class:`DesktopError` if
        no client is connected, the send fails, or the client doesn't
        respond within the tool-specific response timeout.
        """
        ws, target_session = self._resolve_target(device)
        target_tools = (
            target_session.advertised_tools
            if target_session is not None
            else self.advertised_tools
        )

        # Fail-closed on tools the client hasn't advertised. This is what
        # lets the agent side's ``check_fn`` tell Hermes "this tool isn't
        # available right now" without even attempting a round-trip.
        if method.startswith("desktop_computer_") and not target_tools:
            raise DesktopError(
                f"Desktop client has not advertised experimental computer-use tool {method!r}"
            )
        if target_tools and method not in target_tools:
            raise DesktopError(
                f"Desktop client does not advertise tool {method!r}"
            )

        request_id = str(uuid.uuid4())
        future: asyncio.Future[dict[str, Any]] = asyncio.get_event_loop().create_future()

        async with self._lock:
            self.pending[request_id] = future
            self.pending_ws[request_id] = ws

        command_payload = {
            "request_id": request_id,
            "tool": method,
            "args": args or {},
            "target_device_id": (
                target_session.device_id
                if target_session is not None
                else None
            ),
        }
        if method.startswith("desktop_computer_"):
            control_session = self._control_session(
                request_id=request_id,
                target_device_id=str(command_payload["target_device_id"] or "legacy-desktop"),
                requester=requester,
            )
            if control_session is not None:
                command_payload["control_session"] = control_session
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
                self.pending_ws.pop(request_id, None)
            record.decision = "error"
            record.error = f"Failed to send command to client: {exc}"
            logger.error("desktop: failed to send command: %s", exc)
            raise DesktopError(f"Failed to send command to client: {exc}") from exc

        timeout = (
            ADB_RESPONSE_TIMEOUT
            if method.startswith("desktop_adb_")
            else COMPUTER_USE_RESPONSE_TIMEOUT
            if method.startswith("desktop_computer_")
            else RESPONSE_TIMEOUT
        )
        try:
            response = await asyncio.wait_for(future, timeout=timeout)
            response.setdefault(
                "target",
                {
                    "device_id": (
                        target_session.device_id
                        if target_session is not None
                        else None
                    ),
                    "device_name": (
                        target_session.device_name
                        if target_session is not None
                        else None
                    ),
                },
            )
            return response
        except asyncio.TimeoutError:
            async with self._lock:
                self.pending.pop(request_id, None)
                self.pending_ws.pop(request_id, None)
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
        except asyncio.CancelledError:
            async with self._lock:
                self.pending.pop(request_id, None)
                self.pending_ws.pop(request_id, None)
            raise

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
            expected_ws = self.pending_ws.get(request_id)
            if expected_ws is not None and expected_ws is not ws:
                logger.warning(
                    "desktop: ignored response from wrong target request_id=%s",
                    request_id,
                )
                return
            future = self.pending.pop(request_id, None)
            self.pending_ws.pop(request_id, None)

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

        # Mirror onto the authoritative per-WS session used for targeted
        # routing and multi-client diagnostics.
        if session is None:
            session = self._sessions.get(ws)
        if session is not None:
            session.device_id = session.device_id or str(payload.get("device_id", "") or "").strip()
            session.device_name = session.device_name or str(
                payload.get("device_name", "") or payload.get("host", "")
            ).strip()
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
        return bool(self._connected_targets())

    def has_client_for(self, tool_name: str, device: str | None = None) -> bool:
        """True if a client is connected AND advertises ``tool_name``.

        If the client never sent a ``desktop.status`` (empty advertised
        set), we optimistically return True when connected — lets older
        clients that don't advertise still work. Clients that DO
        advertise take the strict path.
        """
        if device is None:
            targets = self._connected_targets()
            if not targets:
                return False
            return any(
                bool(session.advertised_tools)
                and tool_name in session.advertised_tools
                for _, session in targets
            ) or (
                not tool_name.startswith("desktop_computer_")
                and any(not session.advertised_tools for _, session in targets)
            )
        try:
            _, session = self._resolve_target(device)
        except DesktopError:
            return False
        tools = session.advertised_tools if session is not None else self.advertised_tools
        if tool_name.startswith("desktop_computer_") and not tools:
            return False
        return not tools or tool_name in tools

    def status_snapshot(self) -> dict[str, Any]:
        """Dict suitable for ``/desktop/_ping`` and diagnostics."""
        return {
            "connected": self.is_client_connected(),
            "advertised_tools": sorted(self.advertised_tools),
            "client_status": dict(self.client_status),
            "last_seen_at": self.last_seen_at,
            "pending_commands": len(self.pending),
            "clients": [
                {
                    "device_id": session.device_id or session.client_status.get("device_id"),
                    "device_name": (
                        session.device_name
                        or session.client_status.get("device_name")
                        or session.client_status.get("host")
                    ),
                    "advertised_tools": sorted(session.advertised_tools),
                    "last_seen_at": session.last_seen_at,
                }
                for _, session in self._connected_targets()
            ],
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
        """Snapshot every known desktop session."""
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
            self.pending_ws.clear()
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

        Cleans up BOTH the per-client routing/workspace state and only the
        pending futures bound to this WebSocket. Other connected desktops and
        their concurrent commands remain intact.

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
        if session is not None:
            target_device_id = (
                session.device_id
                or str(session.client_status.get("device_id", "") or "").strip()
            )
            if target_device_id:
                self._control_sessions = {
                    key: value
                    for key, value in self._control_sessions.items()
                    if key[4] != target_device_id
                }

        # Tool-routing cleanup — only if this ws was the latched client.
        if self.client_ws is ws:
            remaining = self._connected_targets()
            self.client_ws = remaining[-1][0] if remaining else None
            if remaining:
                replacement = remaining[-1][1]
                self.advertised_tools = set(replacement.advertised_tools)
                self.client_status = dict(replacement.client_status)
                self.last_seen_at = replacement.last_seen_at
            else:
                self.advertised_tools = set()
            # keep client_status around for post-mortem diagnostics —
            # it's small and the next connect overwrites it anyway.
        async with self._lock:
            request_ids = [
                request_id
                for request_id, target in self.pending_ws.items()
                if target is ws
            ]
            futures = [
                self.pending.pop(request_id)
                for request_id in request_ids
                if request_id in self.pending
            ]
            for request_id in request_ids:
                self.pending_ws.pop(request_id, None)
        for future in futures:
            if not future.done():
                future.set_exception(ConnectionError(f"Desktop client disconnected ({reason})"))

    async def close(self) -> None:
        """Server shutdown — cancel all pending commands, drop the
        client ref, and clear all per-ws workspace sessions.
        """
        self.client_ws = None
        self.advertised_tools = set()
        self._sessions.clear()
        self._control_sessions.clear()
        await self._fail_pending("Relay server shutting down")


# Backwards-compat alias. The alpha.6 file exported this name; some
# callers may still import ``DesktopChannel`` directly.
DesktopChannel = DesktopHandler
