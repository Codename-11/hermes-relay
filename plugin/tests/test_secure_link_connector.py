from __future__ import annotations

import asyncio
import base64
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import aiohttp

from plugin.relay.secure_link_connector import (
    FLAG_CLOSE, FLAG_DATA, HEADER_BYTES, SecureLinkConnector, decode_frame,
    credential_id_for, encode_frame, load_or_create_host_id, normalize_broker_url,
    token_sha256,
)


class ContractTests(unittest.TestCase):
    def test_broker_url_requires_public_wss(self) -> None:
        self.assertEqual(normalize_broker_url("wss://reach.example/v1/connect"),
                         "wss://reach.example")
        for value in ("ws://reach.example", "wss://u:p@reach.example",
                      "wss://reach.example?token=x"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                normalize_broker_url(value)

    def test_host_identity_is_stable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "secure-link" / "host-id"
            first = load_or_create_host_id(path)
            self.assertEqual(first, load_or_create_host_id(path))
            self.assertEqual(len(base64.urlsafe_b64decode(first + "==")), 16)

    def test_existing_case_sensitive_identity_is_not_lowercased(self) -> None:
        value = base64.urlsafe_b64encode(bytes(range(16))).rstrip(b"=").decode()
        self.assertRegex(value, "[A-Z]")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "host-id"
            path.write_text(value + "\n", encoding="ascii")
            self.assertEqual(load_or_create_host_id(path), value)

    def test_multiplex_header_round_trip(self) -> None:
        stream_id = bytes(range(16))
        frame = encode_frame(stream_id, FLAG_DATA, b"opaque inner TLS")
        self.assertEqual(frame[:2], bytes((1, FLAG_DATA)))
        self.assertEqual(len(frame[:HEADER_BYTES]), HEADER_BYTES)
        self.assertEqual(decode_frame(frame), (stream_id, FLAG_DATA, b"opaque inner TLS"))
        with self.assertRaises(ValueError):
            decode_frame(bytes((2, FLAG_DATA)) + stream_id)

    def test_token_hash_is_urlsafe_and_not_raw_token(self) -> None:
        value = token_sha256("raw-secret-token")
        self.assertNotIn("raw-secret-token", value)
        self.assertNotIn("=", value)
        self.assertEqual(len(base64.urlsafe_b64decode(value + "=")), 32)
        credential_id = credential_id_for("session-secret")
        self.assertEqual(len(credential_id), 22)
        self.assertEqual(len(base64.urlsafe_b64decode(credential_id + "==")), 16)

    def test_token_hash_accepts_unicode_device_identity(self) -> None:
        value = token_sha256("Bailey's téléphone")
        self.assertEqual(len(value), 43)
        self.assertEqual(len(base64.urlsafe_b64decode(value + "=")), 32)


class _FakeWebSocket:
    def __init__(self, messages: list[aiohttp.WSMessage]) -> None:
        self.messages = list(messages); self.sent_json = []; self.sent_binary = []
    async def send_json(self, payload) -> None: self.sent_json.append(payload)
    async def send_bytes(self, payload) -> None: self.sent_binary.append(payload)
    async def receive(self): return self.messages.pop(0)
    def __aiter__(self): return self
    async def __anext__(self):
        if not self.messages: raise StopAsyncIteration
        return self.messages.pop(0)


class _Context:
    def __init__(self, value): self.value = value
    async def __aenter__(self): return self.value
    async def __aexit__(self, *_args): return None


class _Session:
    def __init__(self, ws): self.ws = ws; self.urls = []
    def ws_connect(self, url, **_kwargs): self.urls.append(url); return _Context(self.ws)


def _text(payload):
    return aiohttp.WSMessage(aiohttp.WSMsgType.TEXT, json.dumps(payload), "")
def _binary(payload):
    return aiohttp.WSMessage(aiohttp.WSMsgType.BINARY, payload, "")


class ConnectorTests(unittest.IsolatedAsyncioTestCase):
    def connector(self, ws):
        session = _Session(ws)
        connector = SecureLinkConnector(broker_url="wss://reach.example",
            host_id="AAAAAAAAAAAAAAAAAAAAAA", host_registration_token="host-secret",
            session_factory=lambda **_kwargs: _Context(session))
        return connector, session

    async def test_registers_single_outbound_control_socket(self) -> None:
        ws = _FakeWebSocket([_text({"type": "registered", "protocol_version": 1})])
        connector, session = self.connector(ws)
        await connector._run_control()
        self.assertEqual(session.urls, ["wss://reach.example/v1/connect"])
        registration = ws.sent_json[0]
        self.assertEqual(registration["role"], "host")
        self.assertEqual(registration["credential_kind"], "host")
        self.assertRegex(registration["connection_id"], r"^[A-Za-z0-9_-]{22}$")
        self.assertNotIn("host-secret", json.dumps(connector.status()))

    async def test_open_and_binary_data_are_forwarded_to_loopback(self) -> None:
        stream_id = bytes(range(16))
        encoded = base64.urlsafe_b64encode(stream_id).rstrip(b"=").decode()
        ws = _FakeWebSocket([_text({"type": "registered", "protocol_version": 1})])
        connector, _ = self.connector(ws)
        connector._ws = ws  # type: ignore[assignment]
        reader = asyncio.StreamReader(); reader.feed_data(b"host TLS"); reader.feed_eof()
        writer = MagicMock()
        writer.drain = AsyncMock()
        writer.wait_closed = AsyncMock()
        with patch("asyncio.open_connection", return_value=(reader, writer)):
            await connector._handle_control({"type": "open", "protocol_version": 1,
                "stream_id": encoded, "host_connection_id": "control"}, "control")
            await connector._handle_binary(encode_frame(stream_id, FLAG_DATA, b"client TLS"))
            await asyncio.wait_for(connector._streams[stream_id].task, 1)
        writer.write.assert_called_once_with(b"client TLS")
        sent = [decode_frame(frame) for frame in ws.sent_binary]
        self.assertIn((stream_id, FLAG_DATA, b"host TLS"), sent)
        self.assertIn((stream_id, FLAG_CLOSE, b""), sent)

    async def test_queue_overflow_closes_only_the_target_stream(self) -> None:
        stream_id = b"b" * 16
        ws = _FakeWebSocket([]); connector, _ = self.connector(ws)
        connector._ws = ws  # type: ignore[assignment]
        from plugin.relay.secure_link_connector import _Stream
        stream = _Stream(stream_id); stream.queued_bytes = 8 * 1024 * 1024
        for _ in range(stream.queue.maxsize):
            stream.queue.put_nowait(b"x")
        connector._streams[stream_id] = stream
        await asyncio.wait_for(
            connector._handle_binary(encode_frame(stream_id, FLAG_DATA, b"x")),
            timeout=0.1,
        )
        self.assertNotIn(stream_id, connector._streams)
        self.assertEqual(decode_frame(ws.sent_binary[-1]), (stream_id, FLAG_CLOSE, b""))

    async def test_broker_close_is_not_echoed_back(self) -> None:
        stream_id = bytes(range(16))
        ws = _FakeWebSocket([]); connector, _ = self.connector(ws)
        connector._ws = ws  # type: ignore[assignment]
        reader = asyncio.StreamReader()
        writer = MagicMock()
        writer.drain = AsyncMock()
        writer.wait_closed = AsyncMock()
        from plugin.relay.secure_link_connector import _Stream
        stream = _Stream(stream_id)
        connector._streams[stream_id] = stream
        with patch("asyncio.open_connection", return_value=(reader, writer)):
            stream.task = asyncio.create_task(connector._serve_stream(stream))
            await connector._handle_binary(encode_frame(stream_id, FLAG_CLOSE))
            await asyncio.wait_for(stream.task, 1)
        self.assertTrue(stream.remote_closed)
        self.assertEqual(ws.sent_binary, [])

    async def test_bootstrap_publishes_hash_and_returns_raw_only_after_ack(self) -> None:
        ws = _FakeWebSocket([]); connector, _ = self.connector(ws)
        connector._ws = ws  # type: ignore[assignment]
        connector._state = "connected"
        task = asyncio.create_task(connector.publish_bootstrap(pairing_id="pair-1", expires_at=9))
        await asyncio.sleep(0)
        published = ws.sent_json[-1]
        self.assertNotIn("token", published)
        self.assertIn("token_sha256", published)
        await connector._handle_control({"type": "published", "protocol_version": 1,
            "credential_kind": "bootstrap", "pairing_id": "pair-1"}, "control")
        raw = await task
        self.assertNotEqual(raw, published["token_sha256"])
        self.assertEqual(token_sha256(raw), published["token_sha256"])

    async def test_route_revoke_waits_for_broker_ack(self) -> None:
        ws = _FakeWebSocket([]); connector, _ = self.connector(ws)
        connector._ws = ws  # type: ignore[assignment]
        connector._state = "connected"
        task = asyncio.create_task(connector.revoke_route(credential_id="route-1"))
        await asyncio.sleep(0)
        self.assertEqual(ws.sent_json[-1], {"type": "revoke", "protocol_version": 1,
            "credential_kind": "route", "credential_id": "route-1"})
        await connector._handle_control({"type": "revoked", "protocol_version": 1,
            "credential_kind": "route", "credential_id": "route-1"}, "control")
        await task

    async def test_route_publish_reuses_one_token_per_session_scope(self) -> None:
        ws = _FakeWebSocket([]); connector, _ = self.connector(ws)
        connector._ws = ws  # type: ignore[assignment]
        connector._state = "connected"
        async def publish_once():
            task = asyncio.create_task(connector.publish_route(
                credential_id="route-1", expires_at=10, device_id_hash="device-hash"))
            await asyncio.sleep(0)
            await connector._handle_control({"type": "published", "protocol_version": 1,
                "credential_kind": "route", "credential_id": "route-1"}, "control")
            return await task
        first = await publish_once()
        second = await publish_once()
        self.assertEqual(first, second)
        self.assertEqual(ws.sent_json[-2]["token_sha256"], ws.sent_json[-1]["token_sha256"])


if __name__ == "__main__": unittest.main()
