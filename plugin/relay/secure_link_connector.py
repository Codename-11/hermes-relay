"""Outbound-only connector for brokered Hermes Secure Link reachability.

One public-trust WSS carries multiplexed opaque byte streams. Each byte stream
contains a client-owned TLS 1.3 connection to the existing local Secure Link
listener, so the reachability broker never sees Relay/API/Dashboard plaintext
or credentials. The authoritative identity remains the QR-paired SPKI pin.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import os
import random
import re
import secrets
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit, urlunsplit

import aiohttp

logger = logging.getLogger(__name__)

PROTOCOL_VERSION = 1
FLAG_DATA = 2
FLAG_CLOSE = 4
HEADER_BYTES = 18
MAX_FRAME_BYTES = 1024 * 1024
MAX_QUEUE_FRAMES = 32
MAX_QUEUE_BYTES = 8 * 1024 * 1024
MAX_STREAMS = 16
REGISTRATION_TIMEOUT_SECONDS = 10.0
IDLE_TIMEOUT_SECONDS = 5 * 60.0
_ID_RE = re.compile(r"^[A-Za-z0-9_-]{22}$")


def _random_id() -> str:
    return base64.urlsafe_b64encode(secrets.token_bytes(16)).rstrip(b"=").decode()


def _canonical_id(value: str) -> bool:
    if not _ID_RE.fullmatch(value):
        return False
    try:
        decoded = base64.urlsafe_b64decode(value + "==")
    except (ValueError, TypeError):
        return False
    return len(decoded) == 16 and _random_id_from(decoded) == value


def _random_id_from(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def normalize_broker_url(raw: str) -> str:
    parsed = urlsplit(raw.strip())
    if (parsed.scheme != "wss" or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment):
        raise ValueError("Secure Link broker URL must be a credential-free wss:// URL")
    path = parsed.path.rstrip("/")
    if path.endswith("/v1/connect"):
        path = path[:-len("/v1/connect")]
    return urlunsplit(("wss", parsed.netloc, path, "", ""))


def load_or_create_host_id(path: Path) -> str:
    try:
        value = path.read_text(encoding="ascii").strip()
        if _canonical_id(value):
            return value
        raise ValueError("stored Secure Link host id is malformed")
    except FileNotFoundError:
        pass
    value = _random_id()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        os.chmod(path.parent, 0o700)
    except OSError:
        pass
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        if hasattr(os, "fchmod"):
            os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="ascii", newline="\n") as handle:
            handle.write(value + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
    return value


def token_sha256(token: str) -> str:
    # Device identifiers are included in route scoping and are not guaranteed
    # to be ASCII (for example, a user-assigned phone name).  UTF-8 keeps the
    # hash deterministic without constraining otherwise valid identifiers.
    digest = hashlib.sha256(token.encode("utf-8")).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


def credential_id_for(value: str) -> str:
    """Derive a canonical opaque 128-bit id without exposing a session token."""
    return _random_id_from(hashlib.sha256(value.encode("utf-8")).digest()[:16])


def encode_frame(stream_id: bytes, flags: int, payload: bytes = b"") -> bytes:
    if len(stream_id) != 16:
        raise ValueError("broker stream id must be 16 bytes")
    if flags not in (FLAG_DATA, FLAG_CLOSE):
        raise ValueError("invalid broker stream flags")
    if len(payload) > MAX_FRAME_BYTES - HEADER_BYTES:
        raise ValueError("broker stream payload exceeds frame limit")
    return bytes((PROTOCOL_VERSION, flags)) + stream_id + payload


def decode_frame(frame: bytes) -> tuple[bytes, int, bytes]:
    if len(frame) < HEADER_BYTES or len(frame) > MAX_FRAME_BYTES:
        raise ValueError("invalid broker frame size")
    if frame[0] != PROTOCOL_VERSION or frame[1] not in (FLAG_DATA, FLAG_CLOSE):
        raise ValueError("invalid broker frame header")
    payload = frame[HEADER_BYTES:]
    if frame[1] == FLAG_CLOSE and payload:
        raise ValueError("close frame must not contain payload")
    return frame[2:HEADER_BYTES], frame[1], payload


def _stream_id(raw: str) -> bytes:
    try:
        value = base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4))
    except (ValueError, TypeError) as exc:
        raise ValueError("invalid broker stream id") from exc
    if len(value) != 16 or base64.urlsafe_b64encode(value).rstrip(b"=").decode() != raw:
        raise ValueError("invalid broker stream id")
    return value


@dataclass
class _Stream:
    stream_id: bytes
    queue: asyncio.Queue[bytes | None] = field(
        default_factory=lambda: asyncio.Queue(maxsize=MAX_QUEUE_FRAMES)
    )
    queued_bytes: int = 0
    task: asyncio.Task[None] | None = None
    last_activity: float = field(default_factory=time.monotonic)
    remote_closed: bool = False


class SecureLinkConnector:
    def __init__(self, *, broker_url: str, host_id: str,
                 host_registration_token: str, local_host: str = "127.0.0.1",
                 local_port: int = 9443, max_streams: int = MAX_STREAMS,
                 session_factory=aiohttp.ClientSession) -> None:
        self.broker_url = normalize_broker_url(broker_url)
        if not _canonical_id(host_id):
            raise ValueError("Secure Link host id must be a base64url 128-bit value")
        if not host_registration_token.strip():
            raise ValueError("Secure Link host registration token is required")
        if local_host not in {"127.0.0.1", "::1", "localhost"}:
            raise ValueError("Secure Link connector target must be loopback")
        if not 1 <= local_port <= 65535 or not 1 <= max_streams <= MAX_STREAMS:
            raise ValueError("invalid Secure Link connector limit")
        self.host_id, self._token = host_id, host_registration_token
        self.local_host, self.local_port = local_host, local_port
        self.max_streams, self._session_factory = max_streams, session_factory
        self._supervisor: asyncio.Task[None] | None = None
        self._ws: aiohttp.ClientWebSocketResponse | None = None
        self._send_lock = asyncio.Lock()
        self._streams: dict[bytes, _Stream] = {}
        self._pending_publications: dict[tuple[str, str], asyncio.Future[None]] = {}
        self._route_tokens: dict[str, tuple[str, float, str]] = {}
        self._stopping = False
        self._state, self._last_error = "stopped", None
        self._connected_at: float | None = None
        self._attempts = self._completed_streams = 0

    @property
    def connect_url(self) -> str:
        return f"{self.broker_url}/v1/connect"

    def status(self) -> dict[str, Any]:
        return {"enabled": True, "state": self._state,
                "connected": self._state == "connected",
                "broker_url": self.broker_url, "host_id": self.host_id,
                "local_target": f"{self.local_host}:{self.local_port}",
                "active_streams": len(self._streams), "stream_limit": self.max_streams,
                "completed_streams": self._completed_streams,
                "connect_attempts": self._attempts, "connected_at": self._connected_at,
                "last_error": self._last_error, "transport": "opaque_inner_tls"}

    async def start(self) -> None:
        if self._supervisor and not self._supervisor.done():
            return
        self._stopping, self._state = False, "connecting"
        self._supervisor = asyncio.create_task(self._run_forever(),
                                               name="secure-link-broker")

    async def stop(self) -> None:
        self._stopping = True
        tasks = [s.task for s in self._streams.values() if s.task]
        if self._supervisor:
            self._supervisor.cancel(); tasks.append(self._supervisor)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._streams.clear(); self._ws = None; self._supervisor = None
        self._state, self._connected_at = "stopped", None

    async def publish_bootstrap(self, *, pairing_id: str, expires_at: float) -> str:
        """Publish a one-use hash and return raw token for the signed QR only."""
        token = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
        await self._publish("bootstrap", pairing_id, {
            "type": "publish_bootstrap", "protocol_version": PROTOCOL_VERSION,
            "pairing_id": pairing_id, "token_sha256": token_sha256(token),
            "expires_at": expires_at, "max_uses": 1,
        })
        return token

    async def publish_route(self, *, credential_id: str, expires_at: float,
                            device_id_hash: str) -> str:
        cached = self._route_tokens.get(credential_id)
        token = (
            cached[0]
            if cached is not None and cached[1] == expires_at and cached[2] == device_id_hash
            else base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
        )
        await self._publish("route", credential_id, {
            "type": "publish_route", "protocol_version": PROTOCOL_VERSION,
            "credential_id": credential_id, "token_sha256": token_sha256(token),
            "expires_at": expires_at, "device_id_hash": device_id_hash,
        })
        # Broker publish_route atomically replaces the hash for credential_id;
        # cache only after acknowledgement so failed publishes never leak an
        # unusable credential through auth.ok.
        self._route_tokens[credential_id] = (token, expires_at, device_id_hash)
        return token

    async def revoke_route(self, *, credential_id: str) -> None:
        """Revoke a durable client route credential by opaque id."""
        await self._publish("revoke", credential_id, {
            "type": "revoke", "protocol_version": PROTOCOL_VERSION,
            "credential_kind": "route", "credential_id": credential_id,
        })
        self._route_tokens.pop(credential_id, None)

    async def _publish(self, kind: str, key: str, payload: dict[str, Any]) -> None:
        if self._ws is None or self._state != "connected":
            raise RuntimeError("Secure Link broker is not connected")
        future = asyncio.get_running_loop().create_future()
        identity = (kind, key)
        if identity in self._pending_publications:
            raise RuntimeError("Secure Link broker publication already pending")
        self._pending_publications[identity] = future
        try:
            async with self._send_lock:
                await self._ws.send_json(payload)
            await asyncio.wait_for(future, REGISTRATION_TIMEOUT_SECONDS)
        finally:
            self._pending_publications.pop(identity, None)

    async def _run_forever(self) -> None:
        delay = 1.0
        while not self._stopping:
            self._attempts += 1; self._state = "connecting"
            try:
                await self._run_control(); delay = 1.0
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self._last_error = str(exc)[:500]; self._state = "backoff"
                self._connected_at = None
                logger.warning("Secure Link broker disconnected: %s", exc)
            await self._close_streams()
            if not self._stopping:
                await asyncio.sleep(delay + random.uniform(0, min(1.0, delay / 4)))
                delay = min(delay * 2, 60.0)

    async def _run_control(self) -> None:
        timeout = aiohttp.ClientTimeout(total=None, connect=10, sock_read=None)
        async with self._session_factory(timeout=timeout) as session:
            async with session.ws_connect(self.connect_url, heartbeat=30,
                    max_msg_size=MAX_FRAME_BYTES, compress=0) as ws:
                self._ws = ws
                connection_id = _random_id()
                await ws.send_json({"type": "register", "protocol_version": 1,
                    "role": "host", "host_id": self.host_id,
                    "connection_id": connection_id, "credential_kind": "host",
                    "token": self._token})
                message = await asyncio.wait_for(ws.receive(), REGISTRATION_TIMEOUT_SECONDS)
                payload = _json_message(message)
                if payload.get("type") != "registered" or payload.get("protocol_version") != 1:
                    raise RuntimeError(_broker_error(payload, "broker registration rejected"))
                self._state, self._connected_at, self._last_error = "connected", time.time(), None
                async for message in ws:
                    if message.type == aiohttp.WSMsgType.TEXT:
                        await self._handle_control(_json_message(message), connection_id)
                    elif message.type == aiohttp.WSMsgType.BINARY:
                        await self._handle_binary(bytes(message.data))
                    elif message.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED):
                        break
                    else:
                        raise RuntimeError("unexpected broker message type")
        self._ws = None

    async def _handle_control(self, payload: dict[str, Any], connection_id: str) -> None:
        kind = payload.get("type")
        if kind == "error":
            raise RuntimeError(_broker_error(payload, "broker error"))
        if kind == "published":
            credential_kind = payload.get("credential_kind")
            key_name = "pairing_id" if credential_kind == "bootstrap" else "credential_id"
            future = self._pending_publications.get((str(credential_kind), str(payload.get(key_name))))
            if future and not future.done():
                future.set_result(None)
            return
        if kind == "revoked":
            future = self._pending_publications.get(("revoke", str(payload.get("credential_id"))))
            if future and not future.done():
                future.set_result(None)
            return
        if (kind != "open" or payload.get("protocol_version") != 1
                or payload.get("host_connection_id") != connection_id):
            raise RuntimeError("invalid broker control message")
        raw_id = _stream_id(payload.get("stream_id"))
        if raw_id in self._streams:
            return
        if len(self._streams) >= self.max_streams:
            await self._send_frame(raw_id, FLAG_CLOSE); return
        stream = _Stream(raw_id); self._streams[raw_id] = stream
        stream.task = asyncio.create_task(self._serve_stream(stream),
            name=f"secure-link-stream-{raw_id.hex()[:8]}")
        stream.task.add_done_callback(lambda done, sid=raw_id: self._stream_done(sid, done))

    async def _handle_binary(self, frame: bytes) -> None:
        stream_id, flags, payload = decode_frame(frame)
        stream = self._streams.get(stream_id)
        if stream is None:
            await self._send_frame(stream_id, FLAG_CLOSE); return
        if flags == FLAG_CLOSE:
            stream.remote_closed = True
            if stream.queue.full():
                self._abort_stream(stream_id, stream)
            else:
                stream.queue.put_nowait(None)
            return
        if stream.queue.full() or stream.queued_bytes + len(payload) > MAX_QUEUE_BYTES:
            self._abort_stream(stream_id, stream)
            await self._send_frame(stream_id, FLAG_CLOSE); return
        stream.queued_bytes += len(payload); stream.last_activity = time.monotonic()
        stream.queue.put_nowait(payload)

    def _abort_stream(self, stream_id: bytes, stream: _Stream) -> None:
        """Drop one overloaded stream without blocking the shared receive loop."""
        self._streams.pop(stream_id, None)
        if stream.task is not None:
            stream.task.cancel()

    async def _serve_stream(self, stream: _Stream) -> None:
        reader, writer = await asyncio.open_connection(self.local_host, self.local_port)
        async def to_local() -> None:
            while True:
                chunk = await stream.queue.get()
                if chunk is None: return
                stream.queued_bytes -= len(chunk); writer.write(chunk); await writer.drain()
                stream.last_activity = time.monotonic()
        async def to_broker() -> None:
            while True:
                chunk = await reader.read(MAX_FRAME_BYTES - HEADER_BYTES)
                if not chunk: return
                stream.last_activity = time.monotonic()
                await self._send_frame(stream.stream_id, FLAG_DATA, chunk)
        async def idle() -> None:
            while True:
                await asyncio.sleep(30)
                if time.monotonic() - stream.last_activity >= IDLE_TIMEOUT_SECONDS:
                    raise TimeoutError("Secure Link stream idle timeout")
        tasks = {asyncio.create_task(to_local()), asyncio.create_task(to_broker()),
                 asyncio.create_task(idle())}
        try:
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in pending: task.cancel()
            results = await asyncio.gather(*done, *pending, return_exceptions=True)
            for result in results:
                if isinstance(result, BaseException) and not isinstance(result, asyncio.CancelledError):
                    raise result
        finally:
            writer.close(); await writer.wait_closed()
            # A broker-originated CLOSE already completed the wire lifecycle.
            # Echoing it races the broker removing the stream and previously
            # caused the entire host control connection to be rejected.
            if not stream.remote_closed:
                await self._send_frame(stream.stream_id, FLAG_CLOSE)

    async def _send_frame(self, stream_id: bytes, flags: int, payload: bytes = b"") -> None:
        if self._ws is None: return
        async with self._send_lock:
            await self._ws.send_bytes(encode_frame(stream_id, flags, payload))

    def _stream_done(self, stream_id: bytes, task: asyncio.Task[None]) -> None:
        self._streams.pop(stream_id, None); self._completed_streams += 1
        if not task.cancelled() and task.exception():
            logger.warning("Secure Link stream %s failed: %s", stream_id.hex()[:8], task.exception())

    async def _close_streams(self) -> None:
        tasks = [s.task for s in self._streams.values() if s.task]
        for task in tasks: task.cancel()
        if tasks: await asyncio.gather(*tasks, return_exceptions=True)
        self._streams.clear(); self._ws = None


def _json_message(message: aiohttp.WSMessage) -> dict[str, Any]:
    if message.type != aiohttp.WSMsgType.TEXT:
        raise RuntimeError("broker control message must be JSON text")
    try: payload = json.loads(message.data)
    except (TypeError, json.JSONDecodeError) as exc: raise RuntimeError("broker sent invalid JSON") from exc
    if not isinstance(payload, dict): raise RuntimeError("broker JSON must be an object")
    return payload


def _broker_error(payload: dict[str, Any], fallback: str) -> str:
    code = payload.get("code")
    return f"{fallback}: {code}" if isinstance(code, str) and code else fallback


__all__ = ["FLAG_CLOSE", "FLAG_DATA", "HEADER_BYTES", "MAX_FRAME_BYTES",
           "MAX_STREAMS", "PROTOCOL_VERSION", "SecureLinkConnector",
           "credential_id_for", "decode_frame", "encode_frame", "load_or_create_host_id",
           "normalize_broker_url", "token_sha256"]
