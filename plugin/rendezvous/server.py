"""Bounded opaque rendezvous for outbound Hermes Secure Link connections.

The broker authenticates routing registrations but never terminates the inner
Secure Link TLS stream.  Consequently it can route and meter ciphertext, but
cannot inspect Hermes paths, credentials, commands, or content.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web

LOG = logging.getLogger("hermes_reach")

PROTOCOL_VERSION = 1
HEADER_SIZE = 18
FLAG_DATA = 2
FLAG_CLOSE = 4
MAX_FRAME_BYTES = 1024 * 1024
MAX_QUEUE_FRAMES = 32
MAX_QUEUE_BYTES = 8 * 1024 * 1024
MAX_CONTROL_BYTES = 4096
ID_RE = re.compile(r"^[A-Za-z0-9_-]{22}$")
HASH_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")
BROKER_KEY: web.AppKey["Broker"] = web.AppKey("broker")


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _token_hash(token: str) -> str:
    return _b64(hashlib.sha256(token.encode("utf-8")).digest())


def _stream_id_text(raw: bytes) -> str:
    return _b64(raw)


def _parse_expiry(value: object) -> float | None:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return float(value)
    if not isinstance(value, str) or len(value) > 64:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(timezone.utc).timestamp()


@dataclass(frozen=True)
class HostCredential:
    host_id: str
    token_sha256: str


@dataclass
class BrokerConfig:
    host_credentials: dict[str, HostCredential]
    state_path: Path | None = None
    max_streams_per_host: int = 16
    max_hosts: int = 256
    max_queue_frames: int = MAX_QUEUE_FRAMES
    max_queue_bytes: int = MAX_QUEUE_BYTES
    registration_timeout: float = 10.0
    match_timeout: float = 10.0
    idle_timeout: float = 300.0
    replay_ttl: float = 86400.0
    attempts_per_minute: int = 30
    max_attempt_sources: int = 4096
    max_credentials_per_host: int = 1024

    @classmethod
    def from_file(cls, path: Path) -> "BrokerConfig":
        payload = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict) or not isinstance(payload.get("hosts"), dict):
            raise ValueError("credential file must contain a hosts object")
        credentials: dict[str, HostCredential] = {}
        for host_id, item in payload["hosts"].items():
            if not isinstance(host_id, str) or not ID_RE.fullmatch(host_id):
                raise ValueError("invalid host id in credential file")
            if not isinstance(item, dict):
                raise ValueError("host credential must be an object")
            digest = item.get("host_token_sha256")
            if not isinstance(digest, str) or not HASH_RE.fullmatch(digest):
                raise ValueError("host token must be stored as a base64url SHA-256 digest")
            credentials[host_id] = HostCredential(host_id, digest)
        return cls(host_credentials=credentials)


@dataclass
class RouteCredential:
    host_id: str
    token_sha256: str
    expires_at: float
    uses_left: int
    kind: str
    credential_id: str
    device_id_hash: str | None = None


@dataclass
class StreamState:
    stream_id: bytes
    connection_id: str
    client: web.WebSocketResponse
    host: "HostState"
    credential_id: str
    credential_digest: str
    closed: bool = False
    host_outbound: deque[bytes] = field(default_factory=deque)
    host_outbound_bytes: int = 0
    client_outbound: asyncio.Queue[bytes] = field(default_factory=lambda: asyncio.Queue(MAX_QUEUE_FRAMES))
    client_outbound_bytes: int = 0
    client_sender_task: asyncio.Task[None] | None = None


@dataclass
class HostState:
    host_id: str
    connection_id: str
    socket: web.WebSocketResponse
    outbound: asyncio.Queue[bytes | dict[str, object]]
    streams: dict[bytes, StreamState] = field(default_factory=dict)
    queued_bytes: int = 0
    sender_task: asyncio.Task[None] | None = None
    sender_wakeup: asyncio.Event = field(default_factory=asyncio.Event)
    schedule_cursor: int = 0
    close_outbound: deque[bytes] = field(default_factory=deque)


class Broker:
    def __init__(self, config: BrokerConfig) -> None:
        self.config = config
        self.hosts: dict[str, HostState] = {}
        self.credentials: dict[str, RouteCredential] = {}
        self.consumed_credentials: dict[str, float] = {}
        self.active_credentials: dict[str, int] = defaultdict(int)
        self.connections: dict[str, float] = {}
        self.attempts: dict[str, deque[float]] = defaultdict(deque)
        self._lock = asyncio.Lock()
        self._load_state()

    def _load_state(self) -> None:
        path = self.config.state_path
        if path is None:
            return
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return
        except (OSError, ValueError, TypeError) as exc:
            raise ValueError("Hermes Reach credential state is invalid") from exc
        if not isinstance(payload, dict) or payload.get("version") != 1:
            raise ValueError("Hermes Reach credential state has unsupported format")
        records = payload.get("credentials", [])
        consumed = payload.get("consumed", [])
        if not isinstance(records, list) or not isinstance(consumed, list):
            raise ValueError("Hermes Reach credential state is malformed")
        maximum = len(self.config.host_credentials) * self.config.max_credentials_per_host
        if len(records) > maximum or len(consumed) > maximum:
            raise ValueError("Hermes Reach credential state exceeds configured bounds")
        now = time.time()
        for item in records:
            if not isinstance(item, dict) or set(item) != {
                "host_id", "token_sha256", "expires_at", "uses_left",
                "kind", "credential_id", "device_id_hash",
            }:
                raise ValueError("Hermes Reach credential record is malformed")
            host_id = item["host_id"]
            digest = item["token_sha256"]
            expiry = _parse_expiry(item["expires_at"])
            kind = item["kind"]
            uses = item["uses_left"]
            identifier = item["credential_id"]
            device_hash = item["device_id_hash"]
            if (host_id not in self.config.host_credentials
                    or not isinstance(digest, str) or not HASH_RE.fullmatch(digest)
                    or expiry is None or expiry <= now
                    or kind not in {"bootstrap", "route"}
                    or not isinstance(uses, int) or uses < 1
                    or not isinstance(identifier, str) or not 1 <= len(identifier) <= 128
                    or (device_hash is not None and (
                        not isinstance(device_hash, str) or not HASH_RE.fullmatch(device_hash)
                    ))):
                continue
            self.credentials[digest] = RouteCredential(
                host_id, digest, expiry, uses, kind, identifier, device_hash,
            )
        for item in consumed:
            if not isinstance(item, dict) or set(item) != {"token_sha256", "expires_at"}:
                raise ValueError("Hermes Reach consumed record is malformed")
            digest = item["token_sha256"]
            expiry = _parse_expiry(item["expires_at"])
            if isinstance(digest, str) and HASH_RE.fullmatch(digest) and expiry and expiry > now:
                self.consumed_credentials[digest] = expiry

    def _state_payload(self) -> dict[str, object]:
        return {
            "version": 1,
            "credentials": [
                {
                    "host_id": item.host_id,
                    "token_sha256": item.token_sha256,
                    "expires_at": item.expires_at,
                    "uses_left": item.uses_left,
                    "kind": item.kind,
                    "credential_id": item.credential_id,
                    "device_id_hash": item.device_id_hash,
                }
                for item in self.credentials.values()
            ],
            "consumed": [
                {"token_sha256": digest, "expires_at": expiry}
                for digest, expiry in self.consumed_credentials.items()
            ],
        }

    @staticmethod
    def _atomic_private_write(path: Path, payload: dict[str, object]) -> None:
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        try:
            os.chmod(path.parent, 0o700)
        except OSError:
            pass
        temporary = path.with_name(f".{path.name}.{secrets.token_hex(8)}.tmp")
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        descriptor = os.open(temporary, flags, 0o600)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
                json.dump(payload, handle, separators=(",", ":"), sort_keys=True)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
            try:
                os.chmod(path, 0o600)
            except OSError:
                pass
        finally:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass

    async def _persist_locked(self) -> None:
        if self.config.state_path is not None:
            await asyncio.to_thread(
                self._atomic_private_write,
                self.config.state_path,
                self._state_payload(),
            )

    def _peer(self, request: web.Request) -> str:
        peer = request.transport.get_extra_info("peername") if request.transport else None
        return str(peer[0]) if isinstance(peer, tuple) and peer else "unknown"

    def _rate_allowed(self, peer: str) -> bool:
        now = time.monotonic()
        if peer not in self.attempts and len(self.attempts) >= self.config.max_attempt_sources:
            self._prune_replay()
            if len(self.attempts) >= self.config.max_attempt_sources:
                return False
        attempts = self.attempts[peer]
        while attempts and attempts[0] < now - 60:
            attempts.popleft()
        if len(attempts) >= self.config.attempts_per_minute:
            return False
        attempts.append(now)
        return True

    @staticmethod
    async def _error(ws: web.WebSocketResponse, code: str) -> None:
        await ws.send_json({"type": "error", "protocol_version": 1, "code": code})
        await ws.close(code=1008)

    async def _registration(self, ws: web.WebSocketResponse) -> dict[str, object] | None:
        try:
            message = await asyncio.wait_for(ws.receive(), self.config.registration_timeout)
        except asyncio.TimeoutError:
            await self._error(ws, "unauthorized")
            return None
        if message.type is not WSMsgType.TEXT or len(message.data.encode("utf-8")) > MAX_CONTROL_BYTES:
            await self._error(ws, "unauthorized")
            return None
        try:
            payload = json.loads(message.data)
        except (TypeError, json.JSONDecodeError):
            await self._error(ws, "unauthorized")
            return None
        required = {"type", "protocol_version", "role", "host_id", "connection_id", "credential_kind", "token"}
        if not isinstance(payload, dict) or set(payload) != required or payload.get("type") != "register":
            await self._error(ws, "unauthorized")
            return None
        if payload.get("protocol_version") != PROTOCOL_VERSION:
            await self._error(ws, "unsupported_version")
            return None
        if payload.get("role") not in {"host", "client"}:
            await self._error(ws, "unauthorized")
            return None
        if not isinstance(payload.get("host_id"), str) or not ID_RE.fullmatch(payload["host_id"]):
            await self._error(ws, "unauthorized")
            return None
        if not isinstance(payload.get("connection_id"), str) or not ID_RE.fullmatch(payload["connection_id"]):
            await self._error(ws, "unauthorized")
            return None
        if not isinstance(payload.get("token"), str) or not 32 <= len(payload["token"]) <= 512:
            await self._error(ws, "unauthorized")
            return None
        return payload

    def _prune_replay(self) -> None:
        now = time.monotonic()
        for key, expires in list(self.connections.items()):
            if expires <= now:
                self.connections.pop(key, None)
        wall = time.time()
        for digest, expires in list(self.consumed_credentials.items()):
            if expires <= wall:
                self.consumed_credentials.pop(digest, None)
        for digest, credential in list(self.credentials.items()):
            if credential.expires_at <= wall:
                self.credentials.pop(digest, None)
        cutoff = now - 60
        for peer, attempts in list(self.attempts.items()):
            while attempts and attempts[0] < cutoff:
                attempts.popleft()
            if not attempts:
                self.attempts.pop(peer, None)

    async def connect(self, request: web.Request) -> web.StreamResponse:
        if request.query_string or request.headers.get("Authorization"):
            raise web.HTTPBadRequest(text="credentials must be sent in registration")
        peer = self._peer(request)
        if not self._rate_allowed(peer):
            raise web.HTTPTooManyRequests(text="rate limited")
        ws = web.WebSocketResponse(max_msg_size=MAX_FRAME_BYTES + HEADER_SIZE, heartbeat=30)
        await ws.prepare(request)
        registration = await self._registration(ws)
        if registration is None:
            return ws
        if registration["role"] == "host":
            await self._host(ws, registration)
        else:
            await self._client(ws, registration)
        return ws

    async def _host(self, ws: web.WebSocketResponse, reg: dict[str, object]) -> None:
        host_id = str(reg["host_id"])
        connection_id = str(reg["connection_id"])
        expected = self.config.host_credentials.get(host_id)
        if reg["credential_kind"] != "host" or expected is None or not hmac.compare_digest(
            expected.token_sha256, _token_hash(str(reg["token"]))
        ):
            await self._error(ws, "unauthorized")
            return
        async with self._lock:
            self._prune_replay()
            if connection_id in self.connections:
                await self._error(ws, "replayed")
                return
            if host_id in self.hosts or len(self.hosts) >= self.config.max_hosts:
                await self._error(ws, "host_busy")
                return
            self.connections[connection_id] = time.monotonic() + self.config.replay_ttl
            host = HostState(host_id, connection_id, ws, asyncio.Queue(self.config.max_queue_frames))
            self.hosts[host_id] = host
        host.sender_task = asyncio.create_task(self._host_sender(host))
        await ws.send_json({"type": "registered", "protocol_version": 1})
        try:
            async for message in ws:
                if message.type is WSMsgType.TEXT:
                    await self._host_control(host, message.data)
                elif message.type is WSMsgType.BINARY:
                    await self._host_binary(host, bytes(message.data))
                elif message.type in {WSMsgType.ERROR, WSMsgType.CLOSE, WSMsgType.CLOSED}:
                    break
        finally:
            await self._drop_host(host)

    async def _host_sender(self, host: HostState) -> None:
        try:
            while True:
                await host.sender_wakeup.wait()
                host.sender_wakeup.clear()
                while not host.outbound.empty():
                    await host.socket.send_json(host.outbound.get_nowait())
                while host.close_outbound:
                    await host.socket.send_bytes(host.close_outbound.popleft())
                streams = list(host.streams.values())
                if streams:
                    start = host.schedule_cursor % len(streams)
                    for offset in range(len(streams)):
                        stream = streams[(start + offset) % len(streams)]
                        if stream.host_outbound:
                            item = stream.host_outbound.popleft()
                            stream.host_outbound_bytes -= len(item)
                            host.queued_bytes -= len(item)
                            await host.socket.send_bytes(item)
                    host.schedule_cursor = (start + 1) % len(streams)
                if (not host.outbound.empty() or host.close_outbound
                        or any(stream.host_outbound for stream in host.streams.values())):
                    host.sender_wakeup.set()
        except (asyncio.CancelledError, ConnectionError, RuntimeError):
            pass

    async def _enqueue_control(self, host: HostState, item: dict[str, object]) -> bool:
        if host.outbound.full():
            return False
        host.outbound.put_nowait(item)
        host.sender_wakeup.set()
        return True

    async def _enqueue_host_data(self, stream: StreamState, item: bytes) -> bool:
        host = stream.host
        if (len(stream.host_outbound) >= self.config.max_queue_frames
                or stream.host_outbound_bytes + len(item) > self.config.max_queue_bytes
                or host.queued_bytes + len(item) > self.config.max_queue_bytes):
            return False
        stream.host_outbound.append(item)
        stream.host_outbound_bytes += len(item)
        host.queued_bytes += len(item)
        host.sender_wakeup.set()
        return True

    async def _client_sender(self, stream: StreamState) -> None:
        try:
            while True:
                item = await stream.client_outbound.get()
                stream.client_outbound_bytes -= len(item)
                await stream.client.send_bytes(item)
        except (asyncio.CancelledError, ConnectionError, RuntimeError):
            pass

    async def _enqueue_client_data(self, stream: StreamState, item: bytes) -> bool:
        if (stream.client_outbound.full()
                or stream.client_outbound_bytes + len(item) > self.config.max_queue_bytes):
            return False
        stream.client_outbound_bytes += len(item)
        stream.client_outbound.put_nowait(item)
        return True

    async def _host_control(self, host: HostState, raw: str) -> None:
        if len(raw.encode("utf-8")) > MAX_CONTROL_BYTES:
            await self._error(host.socket, "quota_exceeded")
            return
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            await self._error(host.socket, "unauthorized")
            return
        if not isinstance(payload, dict) or payload.get("protocol_version") != 1:
            await self._error(host.socket, "unsupported_version")
            return
        kind = payload.get("type")
        if kind == "publish_bootstrap":
            await self._publish(host, payload, "bootstrap")
        elif kind == "publish_route":
            await self._publish(host, payload, "route")
        elif kind == "revoke":
            await self._revoke(host, payload)
        else:
            await self._error(host.socket, "unauthorized")

    async def _publish(self, host: HostState, payload: dict[str, object], kind: str) -> None:
        identifier_key = "pairing_id" if kind == "bootstrap" else "credential_id"
        allowed = {"type", "protocol_version", identifier_key, "token_sha256", "expires_at"}
        if kind == "bootstrap":
            allowed.add("max_uses")
        else:
            allowed.add("device_id_hash")
        identifier = payload.get(identifier_key)
        digest = payload.get("token_sha256")
        expiry = _parse_expiry(payload.get("expires_at"))
        if set(payload) != allowed or not isinstance(identifier, str) or not 1 <= len(identifier) <= 128:
            await self._error(host.socket, "unauthorized")
            return
        if not isinstance(digest, str) or not HASH_RE.fullmatch(digest) or expiry is None or expiry <= time.time():
            await self._error(host.socket, "unauthorized")
            return
        max_uses = payload.get("max_uses", 2**31 - 1)
        if kind == "bootstrap" and max_uses != 1:
            await self._error(host.socket, "unauthorized")
            return
        if kind == "route" and (
            not isinstance(payload.get("device_id_hash"), str)
            or not HASH_RE.fullmatch(str(payload["device_id_hash"]))
        ):
            await self._error(host.socket, "unauthorized")
            return
        credential = RouteCredential(host.host_id, digest, expiry, int(max_uses), kind, identifier,
                                     str(payload.get("device_id_hash")) if kind == "route" else None)
        async with self._lock:
            self._prune_replay()
            existing = self.credentials.get(digest)
            if existing is not None and existing.host_id != host.host_id:
                await self._error(host.socket, "unauthorized")
                return
            if kind == "route":
                # A Relay restart may lose its in-memory raw-token cache and
                # republish the stable per-session credential_id. Replace the
                # prior bearer atomically so it cannot register a new stream;
                # an already-live inner TLS stream remains locally authorized.
                for old_digest, old in list(self.credentials.items()):
                    if (old.host_id == host.host_id
                            and old.credential_id == identifier
                            and old_digest != digest):
                        self.credentials.pop(old_digest, None)
            active = sum(1 for item in self.credentials.values() if item.host_id == host.host_id)
            if active >= self.config.max_credentials_per_host and digest not in self.credentials:
                await self._error(host.socket, "quota_exceeded")
                return
            self.credentials[digest] = credential
            await self._persist_locked()
        await host.socket.send_json({"type": "published", "protocol_version": 1,
                                     "credential_kind": kind, identifier_key: identifier})

    async def _revoke(self, host: HostState, payload: dict[str, object]) -> None:
        if (set(payload) != {
            "type", "protocol_version", "credential_kind", "credential_id"
        } or payload.get("credential_kind") != "route"):
            await self._error(host.socket, "unauthorized")
            return
        identifier = payload.get("credential_id")
        victims: list[StreamState] = []
        async with self._lock:
            for digest, credential in list(self.credentials.items()):
                if credential.host_id == host.host_id and credential.credential_id == identifier:
                    self.credentials.pop(digest, None)
            victims = [stream for stream in host.streams.values() if stream.credential_id == identifier]
            await self._persist_locked()
        for stream in victims:
            await self._close_stream(stream, notify_host=True)
        await host.socket.send_json({
            "type": "revoked", "protocol_version": 1,
            "credential_kind": "route", "credential_id": identifier,
        })

    async def _client(self, ws: web.WebSocketResponse, reg: dict[str, object]) -> None:
        host_id = str(reg["host_id"])
        connection_id = str(reg["connection_id"])
        kind = str(reg["credential_kind"])
        if kind not in {"bootstrap", "route"}:
            await self._error(ws, "unauthorized")
            return
        digest = _token_hash(str(reg["token"]))
        async with self._lock:
            self._prune_replay()
            credential = self.credentials.get(digest)
            host = self.hosts.get(host_id)
            if connection_id in self.connections:
                error = "replayed"
            elif digest in self.consumed_credentials:
                error = "replayed"
            elif credential is None or credential.host_id != host_id or credential.kind != kind:
                error = "unauthorized"
            elif credential.expires_at <= time.time():
                self.credentials.pop(digest, None)
                await self._persist_locked()
                error = "expired"
            elif credential.uses_left <= 0:
                error = "replayed"
            elif kind == "route" and self.active_credentials[digest] >= 1:
                error = "host_busy"
            elif host is None:
                error = "host_offline"
            elif len(host.streams) >= self.config.max_streams_per_host:
                error = "host_busy"
            else:
                error = ""
                credential.uses_left -= 1
                if credential.uses_left <= 0:
                    self.credentials.pop(digest, None)
                    lifetime = max(self.config.replay_ttl, credential.expires_at - time.time())
                    self.consumed_credentials[digest] = time.time() + lifetime
                    await self._persist_locked()
                lifetime = max(self.config.replay_ttl, credential.expires_at - time.time())
                self.connections[connection_id] = time.monotonic() + lifetime
                stream_id = secrets.token_bytes(16)
                self.active_credentials[digest] += 1
                stream = StreamState(
                    stream_id, connection_id, ws, host,
                    credential.credential_id, digest,
                )
                host.streams[stream_id] = stream
        if error:
            await self._error(ws, error)
            return
        stream.client_sender_task = asyncio.create_task(self._client_sender(stream))
        opened = await self._enqueue_control(host, {"type": "open", "protocol_version": 1,
                                                  "stream_id": _stream_id_text(stream_id),
                                                  "host_connection_id": host.connection_id})
        if not opened:
            await self._close_stream(stream, notify_host=False)
            await self._error(ws, "quota_exceeded")
            return
        await ws.send_json({"type": "matched", "protocol_version": 1,
                            "stream_id": _stream_id_text(stream_id)})
        try:
            while True:
                try:
                    message = await asyncio.wait_for(ws.receive(), self.config.idle_timeout)
                except asyncio.TimeoutError:
                    break
                if message.type is WSMsgType.BINARY:
                    data = bytes(message.data)
                    if not data or len(data) > MAX_FRAME_BYTES:
                        break
                    if not await self._enqueue_host_data(stream, bytes((1, FLAG_DATA)) + stream_id + data):
                        await self._error(ws, "quota_exceeded")
                        break
                elif message.type is WSMsgType.TEXT:
                    await self._error(ws, "unauthorized")
                    break
                else:
                    break
        finally:
            await self._close_stream(stream, notify_host=True)

    async def _host_binary(self, host: HostState, data: bytes) -> None:
        if len(data) < HEADER_SIZE or len(data) > MAX_FRAME_BYTES + HEADER_SIZE:
            await self._error(host.socket, "quota_exceeded")
            return
        version, flags, stream_id = data[0], data[1], data[2:18]
        stream = host.streams.get(stream_id)
        if version != 1 or flags not in {FLAG_DATA, FLAG_CLOSE}:
            await self._error(host.socket, "unauthorized")
            return
        if stream is None or stream.closed:
            # CLOSE is idempotent. The client and host-side inner TLS socket
            # can close concurrently, so a valid host CLOSE may arrive just
            # after client cleanup removed the broker stream. Treating that
            # normal race as a protocol violation disconnected the long-lived
            # host registration and made the next route report host_offline.
            if flags == FLAG_CLOSE and len(data) == HEADER_SIZE:
                return
            await self._error(host.socket, "unauthorized")
            return
        if flags == FLAG_CLOSE:
            if len(data) != HEADER_SIZE:
                await self._error(host.socket, "unauthorized")
                return
            await self._close_stream(stream, notify_host=False)
        elif len(data) == HEADER_SIZE:
            await self._error(host.socket, "unauthorized")
        else:
            if not await self._enqueue_client_data(stream, data[HEADER_SIZE:]):
                await self._close_stream(stream, notify_host=True)

    async def _close_stream(self, stream: StreamState, *, notify_host: bool) -> None:
        if stream.closed:
            return
        stream.closed = True
        active = self.active_credentials.get(stream.credential_digest, 0)
        if active <= 1:
            self.active_credentials.pop(stream.credential_digest, None)
        else:
            self.active_credentials[stream.credential_digest] = active - 1
        stream.host.streams.pop(stream.stream_id, None)
        stream.host.queued_bytes -= stream.host_outbound_bytes
        stream.host_outbound.clear()
        stream.host_outbound_bytes = 0
        if stream.client_sender_task:
            stream.client_sender_task.cancel()
        if notify_host and not stream.host.socket.closed:
            stream.host.close_outbound.append(bytes((1, FLAG_CLOSE)) + stream.stream_id)
            stream.host.sender_wakeup.set()
        if not stream.client.closed:
            await stream.client.close()

    async def _drop_host(self, host: HostState) -> None:
        async with self._lock:
            if self.hosts.get(host.host_id) is host:
                self.hosts.pop(host.host_id, None)
            streams = list(host.streams.values())
        for stream in streams:
            await self._close_stream(stream, notify_host=False)
        if host.sender_task:
            host.sender_task.cancel()

    async def shutdown(self) -> None:
        for host in list(self.hosts.values()):
            await host.socket.close(code=1001)
            await self._drop_host(host)


def create_app(config: BrokerConfig) -> web.Application:
    broker = Broker(config)
    app = web.Application(client_max_size=MAX_CONTROL_BYTES)
    app[BROKER_KEY] = broker

    async def health(_: web.Request) -> web.Response:
        return web.json_response({
            "ok": True,
            "service": "hermes_reach",
            "protocol_version": PROTOCOL_VERSION,
            "hosts_online": len(broker.hosts),
            "active_streams": sum(len(host.streams) for host in broker.hosts.values()),
        })

    async def shutdown(_: web.Application) -> None:
        await broker.shutdown()

    app.router.add_get("/health", health)
    app.router.add_get("/v1/connect", broker.connect)
    app.on_shutdown.append(shutdown)
    return app
