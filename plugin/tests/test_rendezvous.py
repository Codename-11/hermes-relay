from __future__ import annotations

import base64
import hashlib
import json
import tempfile
import time
import unittest
from pathlib import Path

from aiohttp.test_utils import AioHTTPTestCase, TestClient, TestServer

from plugin.rendezvous.server import (
    BROKER_KEY,
    FLAG_CLOSE,
    FLAG_DATA,
    Broker,
    BrokerConfig,
    HostCredential,
    create_app,
)


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def digest(token: str) -> str:
    return b64(hashlib.sha256(token.encode()).digest())


HOST_ID = b64(b"h" * 16)
HOST_TOKEN = "host-" + "x" * 40
ROUTE_TOKEN = "route-" + "r" * 40
BOOTSTRAP_TOKEN = "bootstrap-" + "b" * 40


def config(*, state_path: Path | None = None) -> BrokerConfig:
    return BrokerConfig(
        host_credentials={HOST_ID: HostCredential(HOST_ID, digest(HOST_TOKEN))},
        state_path=state_path,
        registration_timeout=0.5,
        idle_timeout=2,
    )


async def register_host(client: TestClient, connection: bytes = b"h" * 16):
    socket = await client.ws_connect("/v1/connect")
    await socket.send_json({
        "type": "register", "protocol_version": 1, "role": "host",
        "host_id": HOST_ID, "connection_id": b64(connection),
        "credential_kind": "host", "token": HOST_TOKEN,
    })
    assert (await socket.receive_json())["type"] == "registered"
    return socket


async def publish_route(host, token: str = ROUTE_TOKEN, identifier: str = "route-1"):
    await host.send_json({
        "type": "publish_route", "protocol_version": 1,
        "credential_id": identifier, "token_sha256": digest(token),
        "expires_at": time.time() + 3600,
        "device_id_hash": digest("device-1"),
    })
    assert (await host.receive_json())["type"] == "published"


async def register_client(client: TestClient, token: str, connection: bytes,
                          kind: str = "route"):
    socket = await client.ws_connect("/v1/connect")
    await socket.send_json({
        "type": "register", "protocol_version": 1, "role": "client",
        "host_id": HOST_ID, "connection_id": b64(connection),
        "credential_kind": kind, "token": token,
    })
    return socket


class RendezvousRoutingTests(AioHTTPTestCase):
    async def get_application(self):
        return create_app(config())

    async def test_routes_opaque_inner_tls_both_directions(self) -> None:
        host = await register_host(self.client)
        await publish_route(host)
        client = await register_client(self.client, ROUTE_TOKEN, b"c" * 16)
        match = await client.receive_json()
        opened = await host.receive_json()
        stream = base64.urlsafe_b64decode(match["stream_id"] + "==")
        self.assertEqual(opened["stream_id"], match["stream_id"])
        await client.send_bytes(b"opaque-client-tls")
        frame = await host.receive_bytes()
        self.assertEqual(frame, bytes((1, FLAG_DATA)) + stream + b"opaque-client-tls")
        await host.send_bytes(bytes((1, FLAG_DATA)) + stream + b"opaque-host-tls")
        self.assertEqual(await client.receive_bytes(), b"opaque-host-tls")
        await client.close()
        self.assertEqual(await host.receive_bytes(), bytes((1, FLAG_CLOSE)) + stream)
        await host.close()

    async def test_route_replacement_invalidates_old_bearer(self) -> None:
        host = await register_host(self.client)
        await publish_route(host)
        replacement = "route-" + "n" * 40
        await publish_route(host, replacement)
        self.assertEqual(len(self.app[BROKER_KEY].credentials), 1)
        old = await register_client(self.client, ROUTE_TOKEN, b"o" * 16)
        self.assertEqual((await old.receive_json())["code"], "unauthorized")
        new = await register_client(self.client, replacement, b"n" * 16)
        self.assertEqual((await new.receive_json())["type"], "matched")
        await new.close()
        await host.close()

    async def test_bootstrap_is_one_use_and_replay_is_explicit(self) -> None:
        host = await register_host(self.client)
        await host.send_json({
            "type": "publish_bootstrap", "protocol_version": 1,
            "pairing_id": "pair-1", "token_sha256": digest(BOOTSTRAP_TOKEN),
            "expires_at": time.time() + 3600, "max_uses": 1,
        })
        await host.receive_json()
        first = await register_client(self.client, BOOTSTRAP_TOKEN, b"a" * 16, "bootstrap")
        self.assertEqual((await first.receive_json())["type"], "matched")
        await host.receive_json()
        second = await register_client(self.client, BOOTSTRAP_TOKEN, b"b" * 16, "bootstrap")
        self.assertEqual((await second.receive_json())["code"], "replayed")
        await first.close()
        await host.close()

    async def test_credentials_in_url_are_rejected(self) -> None:
        response = await self.client.get("/v1/connect?token=secret")
        self.assertEqual(response.status, 400)


class RendezvousPersistenceTests(unittest.IsolatedAsyncioTestCase):
    async def test_durable_route_survives_restart_without_raw_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "private" / "routes.json"
            first = TestClient(TestServer(create_app(config(state_path=state))))
            await first.start_server()
            host = await register_host(first)
            await publish_route(host)
            persisted = state.read_text(encoding="utf-8")
            self.assertNotIn(ROUTE_TOKEN, persisted)
            self.assertIn(digest(ROUTE_TOKEN), persisted)
            await first.close()

            second = TestClient(TestServer(create_app(config(state_path=state))))
            await second.start_server()
            host = await register_host(second, b"i" * 16)
            client = await register_client(second, ROUTE_TOKEN, b"j" * 16)
            self.assertEqual((await client.receive_json())["type"], "matched")
            self.assertEqual((await host.receive_json())["type"], "open")
            await second.close()

    async def test_consumed_bootstrap_replay_survives_restart(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "routes.json"
            broker = Broker(config(state_path=state))
            broker.consumed_credentials[digest(BOOTSTRAP_TOKEN)] = time.time() + 3600
            async with broker._lock:
                await broker._persist_locked()
            restored = Broker(config(state_path=state))
            self.assertIn(digest(BOOTSTRAP_TOKEN), restored.consumed_credentials)
            self.assertNotIn(BOOTSTRAP_TOKEN, state.read_text(encoding="utf-8"))

    def test_expired_and_unknown_host_records_are_pruned_on_load(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "routes.json"
            state.write_text(json.dumps({
                "version": 1,
                "credentials": [{
                    "host_id": HOST_ID, "token_sha256": digest(ROUTE_TOKEN),
                    "expires_at": time.time() - 1, "uses_left": 1,
                    "kind": "route", "credential_id": "old",
                    "device_id_hash": digest("device"),
                }],
                "consumed": [],
            }), encoding="utf-8")
            self.assertEqual(Broker(config(state_path=state)).credentials, {})


class RendezvousConfigTests(unittest.TestCase):
    def test_config_accepts_only_hashed_host_tokens(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "hosts.json"
            path.write_text(json.dumps({"hosts": {
                HOST_ID: {"host_token_sha256": digest(HOST_TOKEN)},
            }}), encoding="utf-8")
            loaded = BrokerConfig.from_file(path)
            self.assertEqual(loaded.host_credentials[HOST_ID].token_sha256, digest(HOST_TOKEN))
            self.assertNotIn(HOST_TOKEN, path.read_text(encoding="utf-8"))
