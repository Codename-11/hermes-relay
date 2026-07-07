"""Bridge channel handler — Phase 3.

Migrated from the legacy standalone relay (``plugin/tools/android_relay.py``,
port 8766) into the unified relay on port 8767. The wire protocol is
identical to the pre-migration ``android_relay.py`` — envelopes are just
wrapped in the multiplexed ``channel: "bridge"`` envelope instead of a
standalone WebSocket.

Flow:
  1. Android clients connect to unified relay's ``/ws`` and authenticate normally.
  2. Agent's Python tool (``plugin/tools/android_tool.py``) POSTs to one of
     the HTTP endpoints registered on the unified relay.
  3. HTTP handler delegates to :meth:`BridgeHandler.handle_command`, which
     resolves the target bridge device, sends a ``bridge.command`` envelope,
     and awaits a ``bridge.response`` from that same WebSocket.
  4. Android's bridge channel sends ``bridge.response`` — :meth:`handle` routes
     it to :meth:`handle_response`, which resolves the pending future.

Wire envelopes (frozen — do not rename fields):
  * ``bridge.command``   — server → app: ``{request_id, method, path, params?, body?}``
  * ``bridge.response``  — app → server: ``{request_id, status, result}``
  * ``bridge.status``    — app → server: ``{screen_on, battery, current_app, accessibility_enabled}``

Concurrency model:
  * A single ``BridgeHandler`` instance lives on :class:`RelayServer`.
  * Multiple bridge-capable Android clients may be connected at once. They are
    registered by stable relay session ``device_id`` and can be selected by
    ``device_id``, device name, or derived aliases.
  * Untargeted commands preserve the historical compatibility path: active
    device if set, otherwise the most-recent bridge client.
  * Pending command responses are scoped to the WebSocket that received the
    command so a different paired device cannot answer another device's
    request_id.
  * ``self.pending`` is protected by an asyncio lock so add/remove races
    from concurrent commands and responses can't drop futures.
  * 30 s timeout matches the legacy ``android_relay._RESPONSE_TIMEOUT``.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
import uuid
from collections import deque
from dataclasses import asdict, dataclass, field
from typing import Any

from aiohttp import web

logger = logging.getLogger(__name__)


RESPONSE_TIMEOUT = 30.0  # seconds — matches legacy android_relay._RESPONSE_TIMEOUT

# Keys whose values must never appear in the ring buffer. Matched
# case-insensitively against the full key name.
_REDACT_KEYS = frozenset({"password", "token", "secret", "otp", "bearer"})

# Selectors consumed by the relay and never forwarded to Android handlers.
_DEVICE_SELECTOR_KEYS = frozenset({"device", "device_id", "deviceId"})

# Cap for the recent-commands ring buffer. Sized so bridge activity can't grow
# the relay's heap without bound.
RECENT_COMMANDS_MAX = 100


class BridgeError(Exception):
    """Raised when a bridge command cannot be dispatched or times out."""

    def __init__(self, message: str, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


def _redact_params(value: Any) -> Any:
    """Return a copy of ``value`` with values under sensitive keys redacted."""
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for k, v in value.items():
            if isinstance(k, str) and k.lower() in _REDACT_KEYS:
                out[k] = "[redacted]"
            else:
                out[k] = _redact_params(v)
        return out
    if isinstance(value, list):
        return [_redact_params(v) for v in value]
    return value


def _normalize_selector(value: str) -> str:
    """Normalize user/device selectors for forgiving alias matching."""
    return re.sub(r"[^a-z0-9]+", "", value.strip().lower())


def _session_attr(session: Any | None, name: str, default: Any = None) -> Any:
    return getattr(session, name, default) if session is not None else default


def _payload_device(payload: dict[str, Any]) -> dict[str, Any]:
    device = payload.get("device")
    return device if isinstance(device, dict) else {}


def _payload_device_id(payload: dict[str, Any]) -> str | None:
    device = _payload_device(payload)
    for source in (device, payload):
        for key in ("device_id", "deviceId", "id"):
            value = source.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    return None


def _payload_device_name(payload: dict[str, Any]) -> str | None:
    device = _payload_device(payload)
    for key in ("display_name", "displayName", "name", "model"):
        value = device.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    for key in ("device_name", "deviceName"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _derive_aliases(
    device_id: str,
    device_name: str,
    client_surface: str = "",
    device_form_factor: str = "",
) -> set[str]:
    aliases = {device_id, _normalize_selector(device_id)}
    if device_name:
        aliases.add(device_name)
        aliases.add(_normalize_selector(device_name))

    haystack = " ".join(
        part for part in (device_id, device_name, client_surface, device_form_factor) if part
    ).lower()
    compact = _normalize_selector(haystack)

    if any(token in compact for token in ("notemax", "boox", "onyx")):
        aliases.update({"boox", "notemax", "note", "tablet"})
    if "pixel" in compact or "fold" in compact:
        aliases.update({"phone", "fold", "pixel"})
    if device_form_factor.lower() == "phone":
        aliases.add("phone")
    if device_form_factor.lower() in ("tablet", "eink", "boox"):
        aliases.add("tablet")

    return {alias for alias in aliases if alias}


def _envelope(msg_type: str, payload: dict[str, Any], msg_id: str | None = None) -> str:
    return json.dumps(
        {
            "channel": "bridge",
            "type": msg_type,
            "id": msg_id or str(uuid.uuid4()),
            "payload": payload,
        }
    )


@dataclass
class BridgeCommandRecord:
    """Audit entry for a single bridge command, stored in the ring buffer."""

    request_id: str
    method: str
    path: str
    params: dict[str, Any] = field(default_factory=dict)
    sent_at: float = 0.0  # epoch milliseconds
    response_status: int | None = None
    result_summary: str | None = None
    error: str | None = None
    # One of: pending, executed, blocked, confirmed, timeout, error
    decision: str = "pending"
    device_id: str | None = None
    device_name: str | None = None
    alias_used: str | None = None


@dataclass
class BridgeClient:
    """Relay-side view of one connected Android bridge client."""

    device_id: str
    device_name: str
    ws: web.WebSocketResponse
    connected_at: float = field(default_factory=time.time)
    last_seen_at: float = field(default_factory=time.time)
    latest_status: dict[str, Any] | None = None
    aliases: set[str] = field(default_factory=set)
    session_token_prefix: str | None = None
    client_surface: str = "unknown"
    device_form_factor: str = "unknown"

    @property
    def connected(self) -> bool:
        return not self.ws.closed

    def last_seen_seconds_ago(self) -> int | None:
        if self.last_seen_at is None:
            return None
        return max(0, int(time.time() - self.last_seen_at))

    def status_payload(self, active: bool = False) -> dict[str, Any]:
        payload = dict(self.latest_status or {})
        device = dict(payload.get("device") or {})
        device.setdefault("name", self.device_name)
        device.setdefault("device_id", self.device_id)
        payload["device"] = device
        payload.setdefault("device_id", self.device_id)
        payload.setdefault("device_name", self.device_name)
        payload.update(
            {
                "phone_connected": self.connected,
                "connected": self.connected,
                "active": active,
                "last_seen_seconds_ago": self.last_seen_seconds_ago(),
                "aliases": sorted(self.aliases),
                "session_token_prefix": self.session_token_prefix,
                "client_surface": self.client_surface,
                "device_form_factor": self.device_form_factor,
            }
        )
        return payload


@dataclass
class _PendingCommand:
    future: asyncio.Future[dict[str, Any]]
    ws: web.WebSocketResponse
    device_id: str | None
    record: BridgeCommandRecord


class BridgeHandler:
    """Routes agent tool calls to connected Android bridge devices."""

    def __init__(self) -> None:
        # Compatibility pointer for existing callers/tests. This always points
        # to the latest/default bridge WebSocket and remains the source of
        # truth only when the multi-device registry is empty.
        self.phone_ws: web.WebSocketResponse | None = None

        # Multi-device registry.
        self.clients_by_device_id: dict[str, BridgeClient] = {}
        self.device_id_by_ws: dict[web.WebSocketResponse, str] = {}
        self.active_device_id: str | None = None
        self.most_recent_device_id: str | None = None

        # request_id → pending command metadata.
        self.pending: dict[str, _PendingCommand] = {}
        self._lock = asyncio.Lock()

        # Compatibility caches used by the existing /bridge/status contract.
        self.phone_status: dict[str, Any] = {}
        self.latest_status: dict[str, Any] | None = None
        self.last_seen_at: float | None = None

        self.recent_commands: deque[BridgeCommandRecord] = deque(
            maxlen=RECENT_COMMANDS_MAX
        )

    # ── Envelope dispatch ────────────────────────────────────────────────

    async def handle(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
        session: Any | None = None,
    ) -> None:
        """Route an incoming bridge-channel envelope from an Android client."""
        msg_type = envelope.get("type", "")
        payload = envelope.get("payload") or {}
        if not isinstance(payload, dict):
            logger.warning("bridge: non-dict payload for type=%s", msg_type)
            return

        if msg_type == "bridge.response":
            await self.handle_response(ws, envelope)
        elif msg_type == "bridge.status":
            await self.handle_status(ws, envelope, session=session)
        elif msg_type == "bridge.command":
            logger.warning("bridge: ignoring unexpected bridge.command from client")
        else:
            logger.warning("bridge: unknown message type %r", msg_type)

    # ── Device registry / selection ──────────────────────────────────────

    def _register_or_update_client(
        self,
        ws: web.WebSocketResponse,
        payload: dict[str, Any],
        session: Any | None = None,
    ) -> BridgeClient:
        now = time.time()
        device_id = (
            str(_session_attr(session, "device_id", "") or "").strip()
            or _payload_device_id(payload)
            or self.device_id_by_ws.get(ws)
            or f"ws-{id(ws)}"
        )
        device_name = (
            str(_session_attr(session, "device_name", "") or "").strip()
            or _payload_device_name(payload)
            or device_id
        )
        client_surface = str(_session_attr(session, "client_surface", "unknown") or "unknown")
        device_form_factor = str(
            _session_attr(session, "device_form_factor", "unknown") or "unknown"
        )
        token = str(_session_attr(session, "token", "") or "")
        token_prefix = token[:8] if token else None

        previous_id = self.device_id_by_ws.get(ws)
        if previous_id and previous_id != device_id:
            self.clients_by_device_id.pop(previous_id, None)

        existing = self.clients_by_device_id.get(device_id)
        if existing is not None and existing.ws is not ws:
            logger.info("bridge: device %s reconnected; replacing previous ws", device_id)
            self._fail_pending_for_ws_sync(
                existing.ws,
                ConnectionError(f"Bridge device {device_name} reconnected"),
            )

        client = existing if existing is not None and existing.ws is ws else None
        if client is None:
            client = BridgeClient(device_id=device_id, device_name=device_name, ws=ws)
            self.clients_by_device_id[device_id] = client
        client.ws = ws
        client.device_name = device_name
        client.latest_status = dict(payload)
        client.last_seen_at = now
        client.session_token_prefix = token_prefix
        client.client_surface = client_surface
        client.device_form_factor = device_form_factor
        client.aliases = _derive_aliases(
            device_id=device_id,
            device_name=device_name,
            client_surface=client_surface,
            device_form_factor=device_form_factor,
        )
        self.device_id_by_ws[ws] = device_id

        self.phone_ws = ws
        self.phone_status = dict(payload)
        self.latest_status = dict(payload)
        self.last_seen_at = now
        self.most_recent_device_id = device_id
        if self.active_device_id is None or not self._is_connected_device(self.active_device_id):
            self.active_device_id = device_id
        return client

    def _is_connected_device(self, device_id: str | None) -> bool:
        if device_id is None:
            return False
        client = self.clients_by_device_id.get(device_id)
        return client is not None and client.connected

    def _connected_clients(self) -> list[BridgeClient]:
        return [c for c in self.clients_by_device_id.values() if c.connected]

    def _resolve_client(
        self,
        selector: str | None = None,
        *,
        require_connected: bool = True,
    ) -> tuple[BridgeClient | None, str | None]:
        if require_connected:
            clients = self._connected_clients()
        else:
            clients = list(self.clients_by_device_id.values())
        if selector and selector.strip():
            raw = selector.strip()
            normalized = _normalize_selector(raw)
            matches: list[BridgeClient] = []
            for client in clients:
                candidates = set(client.aliases)
                candidates.add(client.device_id)
                candidates.add(_normalize_selector(client.device_id))
                candidates.add(client.device_name)
                candidates.add(_normalize_selector(client.device_name))
                if raw in candidates or normalized in candidates:
                    matches.append(client)
            # Deduplicate by device_id while preserving order.
            seen: set[str] = set()
            unique = []
            for match in matches:
                if match.device_id not in seen:
                    unique.append(match)
                    seen.add(match.device_id)
            if not unique:
                raise BridgeError(
                    f"Unknown bridge device selector: {raw}",
                    status_code=404,
                )
            if len(unique) > 1:
                raise BridgeError(
                    f"Ambiguous bridge device selector: {raw}",
                    status_code=409,
                )
            return unique[0], raw

        # Backward compatible no-selector routing.
        for device_id in (self.active_device_id, self.most_recent_device_id):
            if self._is_connected_device(device_id):
                return self.clients_by_device_id[device_id or ""], None
        if len(clients) == 1:
            return clients[0], None
        if clients:
            newest = max(clients, key=lambda c: c.last_seen_at)
            return newest, None

        # Legacy tests/manual callers may seed only phone_ws.
        if self.phone_ws is not None and not self.phone_ws.closed:
            return None, None
        raise BridgeError(
            "No phone connected. Open the Hermes app on your phone and connect.",
            status_code=503,
        )

    def devices_payload(self) -> dict[str, Any]:
        devices = [
            client.status_payload(active=client.device_id == self.active_device_id)
            for client in sorted(
                self.clients_by_device_id.values(),
                key=lambda c: c.last_seen_at,
                reverse=True,
            )
        ]
        return {
            "active_device_id": self.active_device_id,
            "most_recent_device_id": self.most_recent_device_id,
            "devices": devices,
            "count": len(devices),
        }

    def status_payload(self, selector: str | None = None) -> dict[str, Any]:
        if selector:
            client, _alias = self._resolve_client(selector, require_connected=False)
            assert client is not None
            return client.status_payload(active=client.device_id == self.active_device_id)

        try:
            client, _alias = self._resolve_client(None, require_connected=False)
        except BridgeError:
            if self.latest_status is None:
                raise BridgeError("no phone connected", status_code=503)
            response = {
                "phone_connected": self.is_phone_connected(),
                "last_seen_seconds_ago": (
                    max(0, int(time.time() - self.last_seen_at))
                    if self.last_seen_at is not None
                    else None
                ),
            }
            response.update(dict(self.latest_status))
            return response
        if client is not None:
            return client.status_payload(active=client.device_id == self.active_device_id)
        if self.latest_status is None:
            raise BridgeError("no phone connected", status_code=503)
        response = {
            "phone_connected": self.is_phone_connected(),
            "last_seen_seconds_ago": (
                max(0, int(time.time() - self.last_seen_at))
                if self.last_seen_at is not None
                else None
            ),
        }
        response.update(dict(self.latest_status))
        return response

    def select_active(self, selector: str) -> dict[str, Any]:
        client, alias_used = self._resolve_client(selector, require_connected=True)
        assert client is not None
        self.active_device_id = client.device_id
        self.phone_ws = client.ws
        self.latest_status = dict(client.latest_status or {})
        self.last_seen_at = client.last_seen_at
        return {
            "ok": True,
            "active_device_id": client.device_id,
            "device_id": client.device_id,
            "device_name": client.device_name,
            "alias_used": alias_used,
            "aliases": sorted(client.aliases),
        }

    # ── Outbound commands (called from HTTP handlers) ────────────────────

    async def handle_command(
        self,
        method: str,
        path: str,
        params: dict[str, Any] | None = None,
        body: dict[str, Any] | None = None,
        device: str | None = None,
        device_id: str | None = None,
    ) -> dict[str, Any]:
        """Dispatch an ``android_*`` HTTP call to a bridge device."""
        selector = device_id or device
        client, alias_used = self._resolve_client(selector, require_connected=True)
        if client is None:
            ws = self.phone_ws
            if ws is None or ws.closed:
                raise BridgeError(
                    "No phone connected. Open the Hermes app on your phone and connect.",
                    status_code=503,
                )
            resolved_device_id: str | None = None
            resolved_device_name: str | None = None
        else:
            ws = client.ws
            resolved_device_id = client.device_id
            resolved_device_name = client.device_name

        request_id = str(uuid.uuid4())
        future: asyncio.Future[dict[str, Any]] = asyncio.get_event_loop().create_future()

        command_payload = {
            "request_id": request_id,
            "method": method,
            "path": path,
            "params": params or {},
            "body": body or {},
        }
        logger.info(
            "bridge >>> %s %s device=%s body=%s",
            method,
            path,
            resolved_device_id or "legacy",
            json.dumps(body) if body else "{}",
        )

        record = BridgeCommandRecord(
            request_id=request_id,
            method=method,
            path=path,
            params=_redact_params(params or {}),
            sent_at=time.time() * 1000.0,
            decision="pending",
            device_id=resolved_device_id,
            device_name=resolved_device_name,
            alias_used=alias_used,
        )
        pending = _PendingCommand(
            future=future,
            ws=ws,
            device_id=resolved_device_id,
            record=record,
        )

        async with self._lock:
            self.pending[request_id] = pending
        self.recent_commands.append(record)

        try:
            await ws.send_str(_envelope("bridge.command", command_payload, request_id))
        except Exception as exc:
            async with self._lock:
                self.pending.pop(request_id, None)
            record.decision = "error"
            record.error = f"Failed to send command to phone: {exc}"
            logger.error("bridge: failed to send command: %s", exc)
            raise BridgeError(f"Failed to send command to phone: {exc}", status_code=502) from exc

        try:
            return await asyncio.wait_for(future, timeout=RESPONSE_TIMEOUT)
        except asyncio.TimeoutError:
            async with self._lock:
                self.pending.pop(request_id, None)
            record.decision = "timeout"
            record.error = f"Phone did not respond within {RESPONSE_TIMEOUT:.0f}s"
            logger.warning(
                "bridge: phone did not respond within %.0fs for %s %s device=%s",
                RESPONSE_TIMEOUT,
                method,
                path,
                resolved_device_id or "legacy",
            )
            raise BridgeError(
                f"Phone did not respond within {RESPONSE_TIMEOUT:.0f}s",
                status_code=504,
            ) from None

    # ── Inbound response routing ────────────────────────────────────────

    async def handle_response(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
    ) -> None:
        """Resolve the pending future for an incoming ``bridge.response``."""
        payload = envelope.get("payload") or {}
        request_id = payload.get("request_id")
        if not isinstance(request_id, str) or not request_id:
            logger.warning("bridge: response missing request_id: %s", payload)
            return

        async with self._lock:
            pending = self.pending.get(request_id)
            if pending is not None and pending.ws is not ws:
                expected_id = pending.device_id or "legacy"
                actual_id = self.device_id_by_ws.get(ws, "unknown")
                logger.warning(
                    "bridge: ignoring response for request_id=%s from wrong device "
                    "(expected=%s actual=%s)",
                    request_id,
                    expected_id,
                    actual_id,
                )
                return
            if pending is not None:
                self.pending.pop(request_id, None)

        if pending is None:
            logger.debug("bridge: no pending future for request_id=%s", request_id)
            return

        self._update_record_from_response(request_id, payload)
        if not pending.future.done():
            pending.future.set_result(payload)

    def _update_record_from_response(
        self, request_id: str, payload: dict[str, Any]
    ) -> None:
        """Mutate the ring-buffer entry for ``request_id`` with outcome details."""
        record: BridgeCommandRecord | None = None
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

        blocked = False
        confirmed = False
        if isinstance(result, dict):
            if result.get("blocked") is True:
                blocked = True
            if result.get("confirmation_required") is True or result.get("confirmed") is True:
                confirmed = True
        if payload.get("blocked") is True:
            blocked = True
        if isinstance(error_msg, str) and "safety" in error_msg.lower():
            blocked = True

        if blocked:
            record.decision = "blocked"
        elif confirmed:
            record.decision = "confirmed"
        elif isinstance(status, int) and status >= 400:
            record.decision = "error"
        elif isinstance(status, int):
            record.decision = "executed"

    async def handle_status(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
        session: Any | None = None,
    ) -> None:
        """Cache the latest device status snapshot from an Android client."""
        payload = envelope.get("payload") or {}
        if not isinstance(payload, dict):
            return
        client = self._register_or_update_client(ws, payload, session=session)
        logger.debug("bridge: status update device=%s %s", client.device_id, self.phone_status)

    # ── Activity feed ───────────────────────────────────────────────────

    def get_recent(self, limit: int = RECENT_COMMANDS_MAX) -> list[dict[str, Any]]:
        """Return the most recent command records, newest-first."""
        if limit <= 0:
            return []
        out: list[dict[str, Any]] = []
        for record in reversed(self.recent_commands):
            out.append(asdict(record))
            if len(out) >= limit:
                break
        return out

    # ── Lifecycle ───────────────────────────────────────────────────────

    def is_phone_connected(self) -> bool:
        if self._is_connected_device(self.active_device_id):
            return True
        if self._is_connected_device(self.most_recent_device_id):
            return True
        ws = self.phone_ws
        return ws is not None and not ws.closed

    def _fail_pending_for_ws_sync(self, ws: web.WebSocketResponse, err: Exception) -> int:
        failed = 0
        for request_id, pending in list(self.pending.items()):
            if pending.ws is ws:
                self.pending.pop(request_id, None)
                pending.record.decision = "error"
                pending.record.error = str(err)
                if not pending.future.done():
                    pending.future.set_exception(err)
                failed += 1
        return failed

    async def detach_ws(self, ws: web.WebSocketResponse, reason: str = "") -> None:
        """Release ``ws`` and fail only commands pending for that device."""
        device_id = self.device_id_by_ws.pop(ws, None)
        if device_id is not None:
            client = self.clients_by_device_id.get(device_id)
            if client is not None and client.ws is ws:
                self.clients_by_device_id.pop(device_id, None)
                logger.info("bridge: detached device %s (%s)", device_id, reason or "unknown")
        elif self.phone_ws is not ws:
            return

        if self.phone_ws is ws:
            self.phone_ws = None

        err = ConnectionError(f"Phone disconnected ({reason})" if reason else "Phone disconnected")
        async with self._lock:
            failed = self._fail_pending_for_ws_sync(ws, err)

        if failed:
            logger.info(
                "bridge: failed %d pending commands after device disconnect (%s)",
                failed,
                reason or "unknown",
            )

        if self.active_device_id == device_id:
            self.active_device_id = None
        if self.most_recent_device_id == device_id:
            self.most_recent_device_id = None
        connected = self._connected_clients()
        if connected:
            newest = max(connected, key=lambda c: c.last_seen_at)
            self.most_recent_device_id = newest.device_id
            if self.active_device_id is None:
                self.active_device_id = newest.device_id
            self.phone_ws = newest.ws
            self.latest_status = dict(newest.latest_status or {})
            self.last_seen_at = newest.last_seen_at

    async def close(self) -> None:
        """Server shutdown — cancel all pending commands and drop refs."""
        self.phone_ws = None
        self.clients_by_device_id.clear()
        self.device_id_by_ws.clear()
        self.active_device_id = None
        self.most_recent_device_id = None

        async with self._lock:
            pending = dict(self.pending)
            self.pending.clear()

        for item in pending.values():
            if not item.future.done():
                item.future.set_exception(ConnectionError("Relay server shutting down"))

        if pending:
            logger.info("bridge: cancelled %d pending commands on shutdown", len(pending))
