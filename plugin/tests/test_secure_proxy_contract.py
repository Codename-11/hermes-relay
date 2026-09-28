"""Loopback wire tests for the Secure Link Dashboard/Gateway boundary."""
from __future__ import annotations

import asyncio
import gzip
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from aiohttp import WSMsgType, WSServerHandshakeError, web
from aiohttp.test_utils import TestClient, TestServer

from plugin.relay import __version__, secure_proxy

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "test-fixtures" / "vanilla-gateway"))
from vanilla_gateway import GatewayFixture, load_scenario  # noqa: E402


class SecureProxyContractTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.seen: list[dict[str, object]] = []
        self.inner_health_calls = 0
        self.gate_calls = 0
        self.stream_release = asyncio.Event()
        self.stream_bytes = 0
        self.tickets = {"query-ticket", "protocol-ticket"}
        app = web.Application()
        app.router.add_get("/api/health", self.gate)
        app.router.add_get("/health", self.inner_health)
        app.router.add_get("/api/ws", self.gateway)
        app.router.add_get("/chunked", self.chunked)
        app.router.add_get("/compressed", self.compressed)
        app.router.add_post("/auth/password-login", self.login)
        self.upstream = TestServer(app)
        await self.upstream.start_server()
        base = str(self.upstream.make_url("/")).rstrip("/")
        self.state = SimpleNamespace(
            config=SimpleNamespace(
                webapi_url=base,
                secure_proxy_dashboard_url=base,
                secure_proxy_host="localhost",
                secure_proxy_port=9443,
                port=self.upstream.port,
            ),
            secure_proxy_candidate=None,
            client_count=4,
            sessions=SimpleNamespace(active_count=lambda: 7),
        )
        self.client = TestClient(TestServer(secure_proxy.create_secure_proxy_app(self.state)))
        await self.client.start_server()

    async def asyncTearDown(self) -> None:
        await self.client.close()
        await self.upstream.close()

    async def gate(self, request: web.Request) -> web.Response:
        self.gate_calls += 1
        return web.json_response({"auth_required": True})

    async def inner_health(self, request: web.Request) -> web.Response:
        self.inner_health_calls += 1
        return web.json_response({"status": "ok"})

    async def gateway(self, request: web.Request) -> web.WebSocketResponse:
        # Current upstream requires the public protocol alongside exactly one
        # ticket protocol, and selects only the public protocol on admission.
        protocols = [p.strip() for p in request.headers.get("Sec-WebSocket-Protocol", "").split(",")]
        credentials = [p.removeprefix("hermes-gateway-ticket.") for p in protocols
                       if p.startswith("hermes-gateway-ticket.")]
        if credentials and (len(credentials) != 1 or "hermes-gateway-v1" not in protocols):
            raise web.HTTPForbidden()
        ticket = credentials[0] if credentials else request.query.get("ticket")
        if ticket not in self.tickets:
            raise web.HTTPForbidden()
        self.tickets.remove(ticket)
        self.seen.append({
            "profile": request.query.get("profile"),
            "extensions": request.headers.get("Sec-WebSocket-Extensions"),
            "protocols": protocols,
        })
        ws = web.WebSocketResponse(protocols=["hermes-gateway-v1"] if credentials else [])
        await ws.prepare(request)
        await ws.send_str("gateway.ready")
        async for message in ws:
            if message.type == WSMsgType.TEXT:
                await ws.send_str(message.data)
            elif message.type == WSMsgType.BINARY:
                await ws.send_bytes(message.data)
        return ws

    async def chunked(self, request: web.Request) -> web.StreamResponse:
        response = web.StreamResponse(headers={"Content-Type": "application/json"})
        await response.prepare(request)
        try:
            # Hold EOF until the client receives the size-limit rejection.
            for _ in range(3):
                await response.write(b" " * 1024)
                self.stream_bytes += 1024
            await self.stream_release.wait()
            await response.write_eof()
        except ConnectionResetError:
            pass
        return response

    async def compressed(self, request: web.Request) -> web.Response:
        self.assertEqual(request.headers.get("Accept-Encoding"), "identity")
        return web.Response(body=gzip.compress(b'{"next":"/"}'), headers={
            "Content-Type": "application/json", "Content-Encoding": "gzip",
        })

    async def login(self, request: web.Request) -> web.Response:
        return web.json_response({"next": "/"}, headers={
            "ETag": '"before-rewrite"', "Set-Cookie": "session=value; Path=/; HttpOnly",
        })

    async def test_query_ticket_profile_and_compressed_downstream_frames(self) -> None:
        async with self.client.ws_connect(
            "/dashboard/api/ws?ticket=query-ticket&profile=work", compress=15,
        ) as ws:
            self.assertEqual((await ws.receive(timeout=2)).data, "gateway.ready")
            await ws.send_str("message" * 100)
            self.assertEqual((await ws.receive(timeout=2)).data, "message" * 100)
            await ws.send_bytes(b"audio")
            self.assertEqual((await ws.receive(timeout=2)).data, b"audio")
        self.assertEqual(self.seen[0]["profile"], "work")
        self.assertIsNone(self.seen[0]["extensions"])

    async def test_ticket_subprotocol_selects_only_public_protocol_and_is_single_use(self) -> None:
        protocols = ["hermes-gateway-v1", "hermes-gateway-ticket.protocol-ticket"]
        async with self.client.ws_connect("/dashboard/api/ws?profile=work", protocols=protocols) as ws:
            self.assertEqual(ws.protocol, "hermes-gateway-v1")
            self.assertEqual((await ws.receive(timeout=2)).data, "gateway.ready")
        self.assertEqual(self.seen[0]["protocols"], protocols)
        with self.assertRaises(WSServerHandshakeError) as rejected:
            await self.client.ws_connect("/dashboard/api/ws", protocols=protocols)
        self.assertEqual(rejected.exception.status, 502)

    async def test_missing_or_ambiguous_credentials_never_upgrade(self) -> None:
        for protocols in ([], ["hermes-gateway-ticket.protocol-ticket"], [
            "hermes-gateway-v1", "hermes-gateway-ticket.protocol-ticket", "hermes-gateway-ticket.extra",
        ]):
            with self.assertRaises(WSServerHandshakeError):
                await self.client.ws_connect("/dashboard/api/ws", protocols=protocols)
        self.assertEqual(self.seen, [])

    async def test_public_health_uses_local_counts_and_coalesces_availability(self) -> None:
        async def probe() -> dict:
            async with self.client.get("/relay/health") as response:
                return await response.json()
        with patch.object(secure_proxy, "_api_available", new=AsyncMock(return_value=True)) as api:
            results = await asyncio.gather(*(probe() for _ in range(20)))
            api.assert_awaited_once()
        self.assertEqual(self.gate_calls, 1)
        self.assertEqual(self.inner_health_calls, 0)
        for result in results:
            self.assertEqual((result["version"], result["clients"], result["sessions"]),
                             (__version__, 4, 7))

    async def test_chunked_rewrite_stops_at_limit_without_waiting_for_eof(self) -> None:
        with patch.object(secure_proxy, "MAX_PROXY_RESPONSE_BYTES", 2048):
            try:
                response = await asyncio.wait_for(self.client.get("/dashboard/chunked"), timeout=2)
                self.assertEqual(response.status, 502)
                await response.read()
            finally:
                self.stream_release.set()

    async def test_unexpected_compression_fails_closed(self) -> None:
        response = await self.client.get("/dashboard/compressed")
        self.assertEqual(response.status, 502)
        self.assertIn("encoded Dashboard response", await response.text())

    async def test_rewritten_login_has_scoped_cookie_and_fresh_length(self) -> None:
        response = await self.client.post("/dashboard/auth/password-login")
        body = await response.read()
        self.assertEqual(await response.json(), {"next": "/dashboard/"})
        self.assertEqual(int(response.headers["Content-Length"]), len(body))
        self.assertNotIn("ETag", response.headers)
        self.assertIn("Path=/dashboard", response.headers["Set-Cookie"])

    async def test_declarative_gateway_scenario_through_proxy_reconnects_to_same_session(self) -> None:
        fixture = GatewayFixture(load_scenario("secure_link_gateway_auth"))
        fixture.app.router.add_get("/api/health", self.gate)
        async with TestServer(fixture.app) as upstream:
            self.state.config.secure_proxy_dashboard_url = str(upstream.make_url("/")).rstrip("/")
            async with TestClient(TestServer(secure_proxy.create_secure_proxy_app(self.state))) as proxy:
                for use_protocol in (False, True):
                    response = await proxy.post("/dashboard/api/auth/ws-ticket")
                    ticket = (await response.json())["ticket"]
                    url = "/dashboard/api/ws"
                    protocols = ["hermes-gateway-v1", f"hermes-gateway-ticket.{ticket}"] if use_protocol else []
                    if not use_protocol:
                        url += f"?ticket={ticket}"
                    async with proxy.ws_connect(url, protocols=protocols, compress=15) as ws:
                        self.assertEqual((await ws.receive_json(timeout=2))["params"]["type"], "gateway.ready")
                        self.assertEqual(ws.protocol, "hermes-gateway-v1" if use_protocol else None)
                        await ws.send_json({"jsonrpc": "2.0", "id": 1, "method": "session.activate",
                                            "params": {"session_id": fixture.scenario.live_session_id}})
                        while True:
                            frame = await ws.receive_json(timeout=2)
                            if frame.get("id") == 1:
                                self.assertEqual(frame["result"]["session_id"], fixture.scenario.live_session_id)
                                break
                        if not use_protocol:
                            await ws.send_json({"jsonrpc": "2.0", "id": 2, "method": "prompt.submit", "params": {}})
                            while True:
                                frame = await ws.receive_json(timeout=2)
                                if frame.get("params", {}).get("type") == "message.complete":
                                    self.assertEqual(frame["params"]["payload"]["text"], "Secure Link Gateway response.")
                                    break
                    with self.assertRaises(WSServerHandshakeError):
                        await proxy.ws_connect(url, protocols=protocols)
