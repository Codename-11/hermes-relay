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
import base64
import hashlib
import json
import logging
import math
import tempfile
import time
import uuid
from collections import deque
from dataclasses import asdict, dataclass, field
from pathlib import Path, PureWindowsPath
from typing import Any

from aiohttp import web

logger = logging.getLogger(__name__)


RESPONSE_TIMEOUT = 30.0  # seconds — matches bridge.RESPONSE_TIMEOUT
COMPUTER_USE_RESPONSE_TIMEOUT = 180.0  # seconds — allows human approval prompts
ADB_RESPONSE_TIMEOUT = 300.0  # seconds — approval plus a bounded 120 s operation

# File-transfer channel — chunked streaming over the desktop envelope stream.
TRANSFER_CHUNK_SIZE = 256 * 1024  # raw bytes per chunk (base64 ≈ 341 KB)
TRANSFER_IDLE_TIMEOUT = 60.0
TRANSFER_TIMEOUT = 600.0
TRANSFER_MAX_BYTES = 8 * 1024 ** 3

# Keys whose values must never appear in the ring buffer. Matched
# case-insensitively against the full key name.
_REDACT_KEYS = frozenset({
    "password", "token", "secret", "otp", "bearer", "api_key",
    "bytes_base64", "screenshot_base64",
})

# Cap for the recent-commands ring buffer.
RECENT_COMMANDS_MAX = 100
CONTROL_SESSIONS_MAX = 256
CONTROL_SESSION_IDLE_SECONDS = 900.0


class DesktopError(Exception):
    """Raised when a desktop command cannot be dispatched or times out."""


@dataclass(frozen=True)
class DesktopRequesterContext:
    """Trusted caller context supplied by the loopback HTTP boundary."""

    requester_device_id: str | None = None
    chat_session_id: str | None = None
    run_id: str | None = None
    profile: str | None = None


@dataclass
class _ControlSessionRecord:
    session_id: str
    touched_at: float
    target_ws: web.WebSocketResponse
    target_device_id: str
    expiry_task: asyncio.Task[None]


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


@dataclass
class TransferSession:
    """State for one in-flight file transfer (server-initiated push/pull)."""

    transfer_id: str
    direction: str  # 'push' (relay → desktop) | 'pull' (desktop → relay)
    target_ws: web.WebSocketResponse
    path: str  # desktop-side absolute path
    size: int = 0
    sha256: str = ""  # expected (push) or computed (pull, via hasher)
    total_chunks: int = 0
    received_chunks: int = 0
    received_bytes: int = 0
    stage_path: str = ""  # pull: relay-local assembly path
    hasher: Any = None  # pull: hashlib.sha256() instance
    done_future: asyncio.Future[dict[str, Any]] | None = None
    ack: asyncio.Event = field(default_factory=asyncio.Event)
    created_at: float = 0.0
    touched_at: float = 0.0


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
        self._control_sessions: dict[tuple[str, str, str, str, str], _ControlSessionRecord] = {}

        # In-flight file transfers keyed by transfer_id.
        self.transfers: dict[str, TransferSession] = {}
        self._transfer_lock = asyncio.Lock()

    async def _send_control_session_end(
        self,
        record: _ControlSessionRecord,
        reason: str,
    ) -> None:
        if record.target_ws.closed:
            return
        await record.target_ws.send_str(json.dumps({
            "channel": "desktop",
            "type": "desktop.control_session_end",
            "payload": {
                "version": 1,
                "id": record.session_id,
                "target_device_id": record.target_device_id,
                "reason": reason[:256],
            },
        }))

    async def _expire_control_session(
        self,
        key: tuple[str, str, str, str, str],
        session_id: str,
    ) -> None:
        try:
            while True:
                await asyncio.sleep(CONTROL_SESSION_IDLE_SECONDS)
                record = self._control_sessions.get(key)
                if record is None or record.session_id != session_id:
                    return
                if time.monotonic() - record.touched_at < CONTROL_SESSION_IDLE_SECONDS:
                    continue
                self._control_sessions.pop(key, None)
                await self._send_control_session_end(record, "control session idle timeout")
                return
        except asyncio.CancelledError:
            return

    async def _control_session(
        self,
        *,
        request_id: str,
        target_device_id: str,
        target_ws: web.WebSocketResponse,
        requester: DesktopRequesterContext | None,
    ) -> dict[str, Any] | None:
        context = requester or DesktopRequesterContext()
        # A server-owned UUID is not useful authority by itself. Require an
        # executor-authenticated requester and run before advertising the
        # identity; older/unauthenticated callers stay in compatibility mode.
        if not context.requester_device_id or not context.run_id:
            return None
        now = time.monotonic()
        key = (
            context.requester_device_id,
            context.chat_session_id or "",
            context.run_id or "",
            context.profile or "",
            target_device_id or "legacy-desktop",
        )
        # One requester/chat/profile/target may own only one active run. End
        # the prior authority before minting a replacement for a new run.
        transitions = [
            (other_key, record)
            for other_key, record in self._control_sessions.items()
            if other_key[0] == key[0] and other_key[1] == key[1]
            and other_key[3] == key[3] and other_key[4] == key[4]
            and other_key[2] != key[2]
        ]
        for other_key, record in transitions:
            self._control_sessions.pop(other_key, None)
            record.expiry_task.cancel()
            await self._send_control_session_end(record, "requester run changed")
        current = self._control_sessions.get(key)
        if current is None:
            if len(self._control_sessions) >= CONTROL_SESSIONS_MAX:
                oldest = min(self._control_sessions, key=lambda item: self._control_sessions[item].touched_at)
                evicted = self._control_sessions.pop(oldest)
                evicted.expiry_task.cancel()
                await self._send_control_session_end(evicted, "control session capacity eviction")
            session_id = f"control-{uuid.uuid4()}"
            task = asyncio.create_task(self._expire_control_session(key, session_id))
            self._control_sessions[key] = _ControlSessionRecord(
                session_id, now, target_ws, key[4], task
            )
        else:
            session_id = current.session_id
            current.touched_at = now
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

        if msg_type == "transfer_chunk":
            await self._handle_transfer_chunk(ws, envelope)
            return

        if msg_type == "transfer_progress":
            await self._handle_transfer_progress(ws, envelope)
            return

        if msg_type == "transfer_done":
            await self._handle_transfer_done(ws, envelope)
            return

        if msg_type in ("transfer_error", "transfer_abort"):
            await self._handle_transfer_failure(ws, envelope)
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
            control_session = await self._control_session(
                request_id=request_id,
                target_device_id=str(command_payload["target_device_id"] or "legacy-desktop"),
                target_ws=ws,
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

    # ── File transfer (server-initiated push/pull) ───────────────────────

    @staticmethod
    async def _sha256_file(path: Path) -> str:
        hasher = hashlib.sha256()
        with open(path, "rb") as f:
            while True:
                chunk = f.read(TRANSFER_CHUNK_SIZE)
                if not chunk:
                    break
                hasher.update(chunk)
                if f.tell() > TRANSFER_MAX_BYTES:
                    raise DesktopError("Source exceeds 8 GiB")
                await asyncio.sleep(0)
        return hasher.hexdigest()

    async def push_file(
        self,
        local_path: str,
        remote_path: str,
        overwrite: bool = False,
        device: str | None = None,
    ) -> dict[str, Any]:
        """Stream a relay-local file to the desktop (control → controlled).

        Reads the file, sends ``desktop_transfer_receive`` to initialize the
        desktop session, then streams ``desktop.transfer_chunk`` envelopes and
        awaits the desktop's ``desktop.transfer_done``.
        """
        started_at = time.monotonic()
        local = Path(local_path).expanduser().resolve()
        if not local.is_file():
            raise DesktopError(f"push_file: not a regular file: {local_path}")

        size = local.stat().st_size
        self._validate_transfer_size(size)
        if len(self.transfers) >= 4:
            raise DesktopError("Too many active transfers")
        sha256 = await asyncio.wait_for(self._sha256_file(local), timeout=TRANSFER_TIMEOUT)
        transfer_id = str(uuid.uuid4())
        total = max(1, math.ceil(size / TRANSFER_CHUNK_SIZE))
        ws, _session = self._resolve_target(device)
        if len(self.transfers) >= 4:
            raise DesktopError("Too many active transfers")

        loop = asyncio.get_event_loop()
        done_future: asyncio.Future[dict[str, Any]] = loop.create_future()
        now = started_at
        record = TransferSession(
            transfer_id=transfer_id,
            direction="push",
            target_ws=ws,
            path=remote_path,
            size=size,
            sha256=sha256,
            total_chunks=total,
            done_future=done_future,
            created_at=now,
            touched_at=now,
        )
        async with self._transfer_lock:
            self.transfers[transfer_id] = record

        try:
            async with asyncio.timeout(max(0, TRANSFER_TIMEOUT - (time.monotonic() - record.created_at))):
                resp = await self.handle_command(
                    "desktop_transfer_receive",
                    {
                        "transfer_id": transfer_id,
                        "path": remote_path,
                        "size": size,
                        "sha256": sha256,
                        "overwrite": bool(overwrite),
                    },
                    device=device,
                )
                result = resp.get("result") if isinstance(resp, dict) else None
                if (not isinstance(result, dict) or result.get("status") != "ready"
                        or result.get("protocol") != 1 or result.get("transfer_id") != transfer_id):
                    raise DesktopError(
                        f"desktop did not acknowledge transfer_receive: {result!r}"
                    )

                with open(local, "rb") as f:
                    for index in range(total):
                        if done_future.done():
                            done_future.result()
                            raise DesktopError("Premature transfer completion")
                        chunk = f.read(TRANSFER_CHUNK_SIZE)
                        expected = min(TRANSFER_CHUNK_SIZE, size - index * TRANSFER_CHUNK_SIZE)
                        if len(chunk) != expected:
                            raise DesktopError("Source changed during transfer")
                        record.ack.clear()
                        record.received_chunks = index + 1
                        await ws.send_str(
                            _envelope(
                                "desktop.transfer_chunk",
                                {
                                    "transfer_id": transfer_id,
                                    "index": index,
                                    "total": total,
                                    "data": base64.b64encode(chunk).decode(),
                                },
                            )
                        )
                        record.touched_at = time.monotonic()
                        await self._wait_transfer(record, ack=index + 1 < total)

                done = await self._wait_transfer(record)
                return {
                    "ok": True,
                    "target": {"device_id": _session.device_id, "device_name": _session.device_name} if _session else {},
                    "path": remote_path,
                    "size": int(done.get("size", size)),
                    "sha256": done.get("sha256", sha256),
                }
        except asyncio.CancelledError:
            await self._abort_transfer(transfer_id, "Transfer cancelled")
            raise
        except asyncio.TimeoutError:
            await self._abort_transfer(transfer_id, "transfer idle timeout")
            raise DesktopError("push_file timed out waiting for desktop transfer_done")
        except Exception as exc:
            await self._abort_transfer(transfer_id, str(exc))
            if isinstance(exc, DesktopError):
                raise
            raise DesktopError(f"push_file failed: {exc}") from exc
        finally:
            async with self._transfer_lock:
                self.transfers.pop(transfer_id, None)
            if done_future.done() and not done_future.cancelled():
                done_future.exception()
            else:
                done_future.cancel()

    async def pull_file(
        self,
        path: str,
        dest_dir: str,
        device: str | None = None,
    ) -> dict[str, Any]:
        """Pull a large file from the desktop (controlled → control).

        Sends ``desktop_transfer_send`` to initialize the desktop session, then
        accumulates ``desktop.transfer_chunk`` envelopes into a staging file and
        moves it into ``dest_dir`` once the desktop reports ``transfer_done``.
        """
        transfer_id = str(uuid.uuid4())
        ws, _session = self._resolve_target(device)
        if len(self.transfers) >= 4:
            raise DesktopError("Too many active transfers")

        name = PureWindowsPath(path).name if "\\" in path else Path(path).name
        if not name or name in (".", ".."):
            raise DesktopError("Invalid source filename")
        final_dir = Path(dest_dir).expanduser().resolve()
        final_dir.mkdir(parents=True, exist_ok=True)
        dest = final_dir / name
        if dest.exists():
            raise DesktopError("Destination exists; choose a different dest_dir")
        stage_dir = Path(tempfile.mkdtemp(prefix=".hermes-transfer-", dir=final_dir))
        stage_path = stage_dir / "data"
        stage_path.touch(mode=0o600)

        loop = asyncio.get_event_loop()
        done_future: asyncio.Future[dict[str, Any]] = loop.create_future()
        now = time.monotonic()
        record = TransferSession(
            transfer_id=transfer_id,
            direction="pull",
            target_ws=ws,
            path=path,
            stage_path=str(stage_path),
            hasher=hashlib.sha256(),
            done_future=done_future,
            created_at=now,
            touched_at=now,
        )
        async with self._transfer_lock:
            self.transfers[transfer_id] = record

        try:
            async with asyncio.timeout(max(0, TRANSFER_TIMEOUT - (time.monotonic() - record.created_at))):
                resp = await self.handle_command(
                    "desktop_transfer_send",
                    {"transfer_id": transfer_id, "path": path},
                    device=device,
                )
                result = resp.get("result") if isinstance(resp, dict) else None
                if (not isinstance(result, dict) or result.get("status") != "ready"
                        or result.get("protocol") != 1 or result.get("transfer_id") != transfer_id):
                    raise DesktopError(
                        f"desktop did not acknowledge transfer_send: {result!r}"
                    )
                record.size = self._validate_transfer_size(result.get("size"))
                record.total_chunks = max(1, math.ceil(record.size / TRANSFER_CHUNK_SIZE))
                if result.get("total_chunks") != record.total_chunks:
                    raise DesktopError("Invalid total_chunks")
                record.touched_at = time.monotonic()
                await ws.send_str(_envelope("desktop.transfer_start", {"transfer_id": transfer_id}))
                done = await self._wait_transfer(record)
                dest.hardlink_to(stage_path)
                return {
                    "ok": True,
                    "target": {"device_id": _session.device_id, "device_name": _session.device_name} if _session else {},
                    "path": str(dest),
                    "size": int(done.get("size", record.size)),
                    "sha256": done.get("sha256", ""),
                }
        except asyncio.CancelledError:
            await self._abort_transfer(transfer_id, "Transfer cancelled")
            raise
        except asyncio.TimeoutError:
            await self._abort_transfer(transfer_id, "transfer idle timeout")
            raise DesktopError("pull_file timed out waiting for desktop transfer_done")
        except Exception as exc:
            await self._abort_transfer(transfer_id, str(exc))
            if isinstance(exc, DesktopError):
                raise
            raise DesktopError(f"pull_file failed: {exc}") from exc
        finally:
            async with self._transfer_lock:
                self.transfers.pop(transfer_id, None)
            if done_future.done() and not done_future.cancelled():
                done_future.exception()
            else:
                done_future.cancel()
            try:
                stage_path.unlink(missing_ok=True)
                stage_dir.rmdir()
            except Exception:  # noqa: BLE001
                pass

    async def _abort_transfer(self, transfer_id: str, reason: str) -> None:
        record = self.transfers.get(transfer_id)
        if record is None or record.target_ws.closed:
            return
        try:
            async with asyncio.timeout(3):
                await record.target_ws.send_str(
                    _envelope(
                        "desktop.transfer_abort",
                        {"transfer_id": transfer_id, "reason": reason[:256]},
                    )
                )
        except Exception:  # noqa: BLE001
            pass

    @staticmethod
    def _validate_transfer_size(value: Any) -> int:
        if type(value) is not int or not 0 <= value <= TRANSFER_MAX_BYTES:
            raise DesktopError("Invalid transfer size (maximum 8 GiB)")
        return value

    async def _wait_transfer(self, record: TransferSession, *, ack: bool = False) -> dict[str, Any]:
        assert record.done_future is not None
        while not record.done_future.done():
            if ack and record.ack.is_set():
                return {}
            now = time.monotonic()
            if now - record.touched_at >= TRANSFER_IDLE_TIMEOUT or now - record.created_at >= TRANSFER_TIMEOUT:
                raise asyncio.TimeoutError("Transfer timeout")
            await asyncio.sleep(0.01)
        return record.done_future.result()

    def _incoming_transfer(self, ws: web.WebSocketResponse, envelope: dict[str, Any]) -> tuple[TransferSession | None, dict[str, Any]]:
        payload = envelope.get("payload")
        if not isinstance(payload, dict) or not isinstance(payload.get("transfer_id"), str):
            return None, {}
        record = self.transfers.get(payload["transfer_id"])
        if (record is None or record.target_ws is not ws or record.done_future is None
                or record.done_future.done()):
            return None, payload
        return record, payload

    async def _handle_transfer_chunk(self, ws: web.WebSocketResponse, envelope: dict[str, Any]) -> None:
        record, payload = self._incoming_transfer(ws, envelope)
        if record is None:
            return
        assert record.done_future is not None
        try:
            if (record.direction != "pull" or not record.total_chunks
                    or type(payload.get("index")) is not int
                    or payload.get("index") != record.received_chunks
                    or type(payload.get("total")) is not int
                    or payload.get("total") != record.total_chunks
                    or record.received_chunks >= record.total_chunks):
                raise DesktopError("Invalid chunk sequence")
            data = payload.get("data")
            if not isinstance(data, str) or len(data) > 4 * math.ceil(TRANSFER_CHUNK_SIZE / 3):
                raise DesktopError("Invalid chunk data")
            chunk = base64.b64decode(data, validate=True)
            expected = min(TRANSFER_CHUNK_SIZE, record.size - record.received_bytes)
            if len(chunk) != expected or base64.b64encode(chunk).decode() != data:
                raise DesktopError("Chunk size or encoding mismatch")
            with open(record.stage_path, "ab") as output:
                output.write(chunk)
            record.hasher.update(chunk)
            record.received_chunks += 1
            record.received_bytes += len(chunk)
            record.touched_at = time.monotonic()
            await ws.send_str(_envelope("desktop.transfer_progress", {
                "transfer_id": record.transfer_id, "received": record.received_chunks,
            }))
        except Exception as exc:
            record.done_future.set_exception(DesktopError(str(exc)))

    async def _handle_transfer_progress(self, ws: web.WebSocketResponse, envelope: dict[str, Any]) -> None:
        record, payload = self._incoming_transfer(ws, envelope)
        if record is None or record.direction != "push":
            return
        assert record.done_future is not None
        if (type(payload.get("received")) is not int
                or payload.get("received") != record.received_chunks
                or record.received_chunks == 0 or record.ack.is_set()):
            record.done_future.set_exception(DesktopError("Invalid transfer acknowledgement"))
            return
        record.touched_at = time.monotonic()
        record.ack.set()

    async def _handle_transfer_done(self, ws: web.WebSocketResponse, envelope: dict[str, Any]) -> None:
        record, payload = self._incoming_transfer(ws, envelope)
        if record is None:
            return
        assert record.done_future is not None
        expected_sha = record.hasher.hexdigest() if record.direction == "pull" else record.sha256
        if (not record.total_chunks or record.received_chunks != record.total_chunks
                or type(payload.get("size")) is not int or payload.get("size") != record.size
                or (record.direction == "pull" and record.received_bytes != record.size)
                or payload.get("sha256") != expected_sha):
            record.done_future.set_exception(DesktopError("Transfer size, count or sha256 mismatch"))
            return
        record.done_future.set_result({"size": record.size, "sha256": expected_sha})

    async def _handle_transfer_failure(self, ws: web.WebSocketResponse, envelope: dict[str, Any]) -> None:
        record, payload = self._incoming_transfer(ws, envelope)
        if record is not None and record.done_future is not None:
            message = payload.get("message") or payload.get("reason") or "Transfer failed"
            record.done_future.set_exception(DesktopError(str(message)[:512]))

    async def _fail_transfers_for_ws(self, ws: web.WebSocketResponse, reason: str) -> None:
        async with self._transfer_lock:
            doomed = [
                transfer_id
                for transfer_id, record in self.transfers.items()
                if record.target_ws is ws
            ]
            for transfer_id in doomed:
                record = self.transfers.get(transfer_id)
                if record is not None and record.done_future is not None and not record.done_future.done():
                    record.done_future.set_exception(
                        ConnectionError(f"Desktop client disconnected ({reason})")
                    )

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
            logger.warning("desktop: response missing request_id")
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
                if record.tool == "desktop_computer_screenshot":
                    summary = "Desktop screenshot response received"
                else:
                    summary = json.dumps(_redact_args(result), default=str)
            except (TypeError, ValueError):
                summary = "Desktop result unavailable for activity summary"
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
                removed = [
                    key for key in self._control_sessions if key[4] == target_device_id
                ]
                for key in removed:
                    record = self._control_sessions.pop(key)
                    record.expiry_task.cancel()

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

        # Fail any in-flight file transfers bound to this WebSocket.
        await self._fail_transfers_for_ws(ws, reason)

    async def close(self) -> None:
        """Server shutdown — cancel all pending commands, drop the
        client ref, and clear all per-ws workspace sessions.
        """
        self.client_ws = None
        self.advertised_tools = set()
        self._sessions.clear()
        for record in self._control_sessions.values():
            record.expiry_task.cancel()
        self._control_sessions.clear()
        await self._fail_pending("Relay server shutting down")
        async with self._transfer_lock:
            for record in self.transfers.values():
                if record.done_future is not None and not record.done_future.done():
                    record.done_future.set_exception(
                        ConnectionError("Relay server shutting down")
                    )
            self.transfers.clear()


# Backwards-compat alias. The alpha.6 file exported this name; some
# callers may still import ``DesktopChannel`` directly.
DesktopChannel = DesktopHandler
