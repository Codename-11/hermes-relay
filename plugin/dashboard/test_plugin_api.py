"""Hermetic unit tests for the dashboard plugin's proxy router.

Uses FastAPI's ``TestClient`` + ``httpx.MockTransport`` patched over
``httpx.AsyncClient`` so no real HTTP hits the loopback relay during CI.
"""

from __future__ import annotations

import asyncio
import os
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from typing import Callable, Optional
from unittest.mock import AsyncMock, Mock, patch

import httpx
from aiohttp import web
from fastapi import FastAPI
from fastapi import WebSocket
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from plugin.dashboard import plugin_api
from plugin.relay.config import RelayConfig
from plugin.relay.server import RelayServer, handle_ws


# ---------------------------------------------------------------------------
# Test scaffolding
# ---------------------------------------------------------------------------


def _install_mock_transport(
    test_case: "PluginApiTestCase",
    handler: Callable[[httpx.Request], httpx.Response],
) -> list[httpx.Request]:
    """Replace ``httpx.AsyncClient`` with one backed by a MockTransport.

    Returns a list that the test can inspect post-call to see what requests
    the proxy actually issued (URL, query params, etc.). Undo on tearDown.
    """
    captured: list[httpx.Request] = []

    def _capture(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return handler(request)

    transport = httpx.MockTransport(_capture)
    original = httpx.AsyncClient

    class _PatchedClient(httpx.AsyncClient):
        def __init__(self, *args, **kwargs):  # type: ignore[no-untyped-def]
            kwargs["transport"] = transport
            super().__init__(*args, **kwargs)

    httpx.AsyncClient = _PatchedClient  # type: ignore[misc,assignment]
    test_case.addCleanup(lambda: setattr(httpx, "AsyncClient", original))
    return captured


def _build_client(*, prefix: str = "") -> TestClient:
    app = FastAPI()
    app.include_router(plugin_api.router, prefix=prefix)
    return TestClient(app)


class _EphemeralRelay:
    """Run a real aiohttp Relay WebSocket on an OS-assigned loopback port."""

    def __init__(self) -> None:
        self.loop = asyncio.new_event_loop()
        self.ready = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.port = 0
        self.error: BaseException | None = None
        self.runner: web.AppRunner | None = None
        self.server = RelayServer(
            RelayConfig(
                host="127.0.0.1",
                port=0,
                profile_discovery_enabled=False,
                session_persistence_path=None,
                secure_proxy_enabled=False,
                experimental_reach_enabled=False,
            )
        )

    async def _echo_ws(self, request: web.Request) -> web.WebSocketResponse:
        socket = web.WebSocketResponse()
        await socket.prepare(request)
        async for message in socket:
            if message.type == web.WSMsgType.TEXT:
                await socket.send_str(message.data)
            elif message.type == web.WSMsgType.BINARY:
                await socket.send_bytes(message.data)
                await socket.close(code=1001, message=b"echo complete")
        return socket

    async def _start(self) -> None:
        app = web.Application()
        app["server"] = self.server
        app.router.add_get("/ws", handle_ws)
        app.router.add_get("/echo", self._echo_ws)
        self.runner = web.AppRunner(app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, "127.0.0.1", 0)
        await site.start()
        sockets = getattr(site, "_server").sockets
        self.port = int(sockets[0].getsockname()[1])

    def _run(self) -> None:
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self._start())
        except BaseException as exc:  # pragma: no cover - startup diagnostics
            self.error = exc
            self.ready.set()
            return
        self.ready.set()
        self.loop.run_forever()

    def start(self) -> None:
        self.thread.start()
        if not self.ready.wait(timeout=10):
            raise TimeoutError("ephemeral Relay did not start")
        if self.error is not None:
            raise RuntimeError("ephemeral Relay failed to start") from self.error

    def stop(self) -> None:
        async def _stop() -> None:
            await self.server.close()
            if self.runner is not None:
                await self.runner.cleanup()

        future = asyncio.run_coroutine_threadsafe(_stop(), self.loop)
        future.result(timeout=10)
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.join(timeout=10)
        self.loop.close()


class PluginApiTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.client = _build_client()


class TransportIngressTests(PluginApiTestCase):
    def test_health_is_the_only_route_without_relay_session_header(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/health")
            self.assertNotIn("Authorization", request.headers)
            return httpx.Response(200, json={"status": "ok"})

        _install_mock_transport(self, handler)
        with patch.object(plugin_api, "_dashboard_proxy_secret", return_value="proxy-secret"):
            response = self.client.get("/transport/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ok")
        self.assertEqual(
            response.json()["dashboard_ingress"]["path"],
            "/api/plugins/hermes-relay/transport",
        )

    def test_client_route_requires_separate_relay_session_header(self) -> None:
        response = self.client.get("/transport/sessions")
        self.assertEqual(response.status_code, 401)
        self.assertIn("relay session", response.json()["detail"])

    def test_session_header_is_rewritten_and_dashboard_auth_is_not_forwarded(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/usage/providers")
            self.assertEqual(request.url.params["profile"], "victor")
            self.assertEqual(request.headers["Authorization"], "Bearer relay-token")
            self.assertEqual(
                request.headers[plugin_api._DASHBOARD_PROXY_SECRET_HEADER],
                "proxy-secret",
            )
            self.assertNotIn(plugin_api._TRANSPORT_SESSION_HEADER, request.headers)
            self.assertNotIn("Cookie", request.headers)
            return httpx.Response(200, json={"providers": []})

        _install_mock_transport(self, handler)
        with patch.object(plugin_api, "_dashboard_proxy_secret", return_value="proxy-secret"):
            response = self.client.get(
                "/transport/usage/providers?profile=victor",
                headers={
                    plugin_api._TRANSPORT_SESSION_HEADER: "relay-token",
                    "Authorization": "Bearer dashboard-token",
                    "Cookie": "session=dashboard-cookie",
                },
            )
        self.assertEqual(response.status_code, 200)

    def test_binary_body_status_and_safe_response_headers_are_preserved(self) -> None:
        payload = b"\x00voice-bytes\xff"

        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/voice/transcribe")
            self.assertEqual(request.content, payload)
            return httpx.Response(
                206,
                content=b"partial",
                headers={
                    "Content-Type": "application/octet-stream",
                    "Content-Range": "bytes 0-6/7",
                    "Set-Cookie": "must-not-escape=1",
                    "Connection": "close",
                },
            )

        _install_mock_transport(self, handler)
        with patch.object(plugin_api, "_dashboard_proxy_secret", return_value="proxy-secret"):
            response = self.client.post(
                "/transport/voice/transcribe",
                content=payload,
                headers={plugin_api._TRANSPORT_SESSION_HEADER: "relay-token"},
            )
        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.content, b"partial")
        self.assertEqual(response.headers["content-range"], "bytes 0-6/7")
        self.assertNotIn("set-cookie", response.headers)

    def test_dynamic_profile_path_is_encoded_for_fixed_loopback_hop(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.host, "127.0.0.1")
            self.assertEqual(request.url.port, plugin_api.RELAY_PORT)
            self.assertEqual(request.url.path, "/api/profiles/my profile/soul")
            self.assertEqual(request.url.raw_path, b"/api/profiles/my%20profile/soul")
            return httpx.Response(200, json={"content": "soul"})

        _install_mock_transport(self, handler)
        with patch.object(plugin_api, "_dashboard_proxy_secret", return_value="proxy-secret"):
            response = self.client.get(
                "/transport/api/profiles/my%20profile/soul",
                headers={plugin_api._TRANSPORT_SESSION_HEADER: "relay-token"},
            )
        self.assertEqual(response.status_code, 200)

    def test_admin_and_wildcard_routes_are_not_mounted(self) -> None:
        headers = {plugin_api._TRANSPORT_SESSION_HEADER: "relay-token"}
        for method, path in (
            ("POST", "/transport/pairing/register"),
            ("GET", "/transport/media/inspect"),
            ("GET", "/transport/bridge/activity"),
            ("POST", "/transport/desktop/android_tap"),
            ("PATCH", "/transport/relay/security"),
            ("POST", "/transport/phone/message"),
        ):
            with self.subTest(path=path):
                response = self.client.request(method, path, headers=headers)
                self.assertEqual(response.status_code, 404)

    def test_wrong_method_does_not_fall_through_to_loopback(self) -> None:
        def handler(_request: httpx.Request) -> httpx.Response:
            raise AssertionError("disallowed method must not reach Relay")

        _install_mock_transport(self, handler)
        response = self.client.delete(
            "/transport/media/upload",
            headers={plugin_api._TRANSPORT_SESSION_HEADER: "relay-token"},
        )
        self.assertEqual(response.status_code, 405)

    def test_oversized_relay_response_is_rejected_from_content_length(self) -> None:
        def handler(_request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, content=b"12345")

        _install_mock_transport(self, handler)
        with patch.object(plugin_api, "_TRANSPORT_RESPONSE_LIMIT", 4), patch.object(
            plugin_api, "_dashboard_proxy_secret", return_value="proxy-secret"
        ):
            response = self.client.get("/transport/health")
        self.assertEqual(response.status_code, 502)
        self.assertIn("size limit", response.json()["detail"])


class TransportWebSocketAdmissionTests(unittest.IsolatedAsyncioTestCase):
    class _Socket:
        def __init__(self) -> None:
            self.closed: tuple[int, str] | None = None
            self.headers: dict[str, str] = {}

        async def close(self, code: int, reason: str) -> None:
            self.closed = (code, reason)

    async def test_missing_upstream_private_helpers_fails_closed(self) -> None:
        socket = self._Socket()
        with patch.object(plugin_api, "_dashboard_plugin_is_enabled", return_value=True), patch.object(
            plugin_api, "_dashboard_ws_guards", return_value=None
        ):
            selected = await plugin_api._admit_transport_websocket(socket)  # type: ignore[arg-type]
        self.assertIsNone(selected)
        self.assertEqual(socket.closed[0], 1011)  # type: ignore[index]

    async def test_request_and_auth_guards_run_before_admission(self) -> None:
        socket = self._Socket()
        socket._hermes_ws_subprotocol = "hermes-gateway-v1"  # type: ignore[attr-defined]
        socket.headers["sec-websocket-protocol"] = "hermes-gateway-v1"
        request_allowed = Mock(return_value=True)
        auth_ok = Mock(return_value=True)
        with patch.object(
            plugin_api, "_dashboard_plugin_is_enabled", return_value=True
        ), patch.object(
            plugin_api,
            "_dashboard_ws_guards",
            return_value=(request_allowed, auth_ok),
        ):
            selected = await plugin_api._admit_transport_websocket(socket)  # type: ignore[arg-type]
        self.assertEqual(selected, "hermes-gateway-v1")
        self.assertIsNone(socket.closed)
        request_allowed.assert_called_once_with(socket)
        auth_ok.assert_called_once_with(socket)

    async def test_failed_dashboard_ticket_is_policy_close(self) -> None:
        socket = self._Socket()
        with patch.object(
            plugin_api, "_dashboard_plugin_is_enabled", return_value=True
        ), patch.object(
            plugin_api,
            "_dashboard_ws_guards",
            return_value=(lambda _ws: True, lambda _ws: False),
        ):
            await plugin_api._admit_transport_websocket(socket)  # type: ignore[arg-type]
        self.assertEqual(socket.closed[0], 1008)  # type: ignore[index]

    async def test_disabled_plugin_is_rejected_before_dashboard_ticket_consumption(self) -> None:
        socket = self._Socket()
        guards = Mock()
        with patch.object(plugin_api, "_dashboard_plugin_is_enabled", return_value=False), patch.object(
            plugin_api, "_dashboard_ws_guards", guards
        ):
            await plugin_api._admit_transport_websocket(socket)  # type: ignore[arg-type]
        self.assertEqual(socket.closed[0], 1008)  # type: ignore[index]
        guards.assert_not_called()


class TransportWebSocketIntegrationTests(unittest.TestCase):
    """Exercise the complete FastAPI -> aiohttp Relay WebSocket path."""

    prefix = "/base/api/plugins/hermes-relay"

    def setUp(self) -> None:
        self.relay = _EphemeralRelay()
        self.relay.start()
        self.addCleanup(self.relay.stop)

        environment = patch.dict(
            os.environ,
            {"HERMES_RELAY_DASHBOARD_PROXY_SECRET": "integration-test-secret"},
        )
        environment.start()
        self.addCleanup(environment.stop)

        relay_port = patch.object(plugin_api, "RELAY_PORT", self.relay.port)
        relay_port.start()
        self.addCleanup(relay_port.stop)

        enabled = patch.object(
            plugin_api, "_dashboard_plugin_is_enabled", return_value=True
        )
        enabled.start()
        self.addCleanup(enabled.stop)

    @staticmethod
    def _auth_payload(**payload: str) -> dict[str, object]:
        return {
            "channel": "system",
            "type": "auth",
            "id": "auth-1",
            "payload": {
                "device_name": "Hermetic phone",
                "device_id": "hermetic-phone-1",
                **payload,
            },
        }

    def _client(self, *, include_echo: bool = False) -> TestClient:
        app = FastAPI()
        app.include_router(plugin_api.router, prefix=self.prefix)
        if include_echo:
            path = f"{self.prefix}/transport/echo-test"

            @app.websocket(path)
            async def echo_transport(websocket: WebSocket) -> None:
                await plugin_api._proxy_transport_websocket(
                    websocket,
                    "/echo",
                    require_session_header=False,
                )

        return TestClient(app)

    def test_prefixed_ingress_pairs_reconnects_and_runs_guards_before_accept(self) -> None:
        pairing_code = "ABC123"
        self.assertTrue(self.relay.server.pairing.register_code(pairing_code))
        guard_events: list[tuple[str, str]] = []

        def request_allowed(websocket: WebSocket) -> bool:
            guard_events.append(("request", websocket.application_state.name))
            return True

        def auth_ok(websocket: WebSocket) -> bool:
            guard_events.append(("auth", websocket.application_state.name))
            return True

        path = f"{self.prefix}/transport/ws"
        with patch.object(
            plugin_api,
            "_dashboard_ws_guards",
            return_value=(request_allowed, auth_ok),
        ), self._client() as client:
            with client.websocket_connect(path) as socket:
                socket.send_json(self._auth_payload(pairing_code=pairing_code))
                paired = socket.receive_json()
                self.assertEqual(paired["type"], "auth.ok")
                session_token = paired["payload"]["session_token"]

                socket.send_bytes(b"relay-binary-frame")
                socket.send_json(
                    {
                        "channel": "system",
                        "type": "ping",
                        "id": "ping-1",
                        "payload": {"ts": 42},
                    }
                )
                pong = socket.receive_json()
                self.assertEqual(pong["type"], "pong")
                self.assertEqual(pong["id"], "ping-1")
                self.assertEqual(pong["payload"]["ts"], 42)

            with client.websocket_connect(path) as socket:
                socket.send_json(self._auth_payload(session_token=session_token))
                reconnected = socket.receive_json()
                self.assertEqual(reconnected["type"], "auth.ok")
                self.assertEqual(
                    reconnected["payload"]["session_token"], session_token
                )

        self.assertEqual(
            guard_events,
            [
                ("request", "CONNECTING"),
                ("auth", "CONNECTING"),
                ("request", "CONNECTING"),
                ("auth", "CONNECTING"),
            ],
        )
        deadline = time.monotonic() + 2
        while self.relay.server.client_count and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual(self.relay.server.client_count, 0)

    def test_binary_and_relay_close_are_forwarded_bidirectionally(self) -> None:
        guards = (lambda _ws: True, lambda _ws: True)
        path = f"{self.prefix}/transport/echo-test"
        with patch.object(
            plugin_api, "_dashboard_ws_guards", return_value=guards
        ), self._client(include_echo=True) as client:
            with client.websocket_connect(path) as socket:
                socket.send_bytes(b"\x00relay\xff")
                self.assertEqual(socket.receive_bytes(), b"\x00relay\xff")
                close = socket.receive()
                self.assertEqual(close["type"], "websocket.close")
                self.assertEqual(close["code"], 1001)

    def test_single_use_ticket_replay_is_denied_before_relay_connect(self) -> None:
        tickets = {"fresh-ticket"}
        guard_events: list[str] = []

        def request_allowed(_websocket: WebSocket) -> bool:
            guard_events.append("request")
            return True

        def auth_ok(websocket: WebSocket) -> bool:
            guard_events.append("auth")
            protocols = {
                item.strip()
                for item in websocket.headers.get(
                    "sec-websocket-protocol", ""
                ).split(",")
            }
            credential = next(
                (
                    item.removeprefix("hermes-gateway-ticket.")
                    for item in protocols
                    if item.startswith("hermes-gateway-ticket.")
                ),
                "",
            )
            if "hermes-gateway-v1" not in protocols or credential not in tickets:
                return False
            tickets.remove(credential)
            websocket._hermes_ws_subprotocol = "hermes-gateway-v1"  # type: ignore[attr-defined]
            return True

        subprotocols = [
            "hermes-gateway-v1",
            "hermes-gateway-ticket.fresh-ticket",
        ]
        path = f"{self.prefix}/transport/ws"
        with patch.object(
            plugin_api,
            "_dashboard_ws_guards",
            return_value=(request_allowed, auth_ok),
        ), self._client() as client:
            with client.websocket_connect(path, subprotocols=subprotocols) as socket:
                self.assertEqual(socket.accepted_subprotocol, "hermes-gateway-v1")
                socket.send_json(
                    self._auth_payload(
                        session_token=self.relay.server.sessions.create_session(
                            "Hermetic phone", "hermetic-phone-1"
                        ).token
                    )
                )
                self.assertEqual(socket.receive_json()["type"], "auth.ok")

            with self.assertRaises(WebSocketDisconnect) as denied:
                with client.websocket_connect(path, subprotocols=subprotocols):
                    pass
            self.assertEqual(denied.exception.code, 1008)

        self.assertEqual(guard_events, ["request", "auth", "request", "auth"])

    def test_missing_dashboard_helpers_denies_upgrade_without_relay_client(self) -> None:
        path = f"{self.prefix}/transport/ws"
        with patch.object(
            plugin_api, "_dashboard_ws_guards", return_value=None
        ), self._client() as client:
            with self.assertRaises(WebSocketDisconnect) as denied:
                with client.websocket_connect(path):
                    pass
        self.assertEqual(denied.exception.code, 1011)
        self.assertEqual(self.relay.server.client_count, 0)

# ---------------------------------------------------------------------------
# 2xx passthrough
# ---------------------------------------------------------------------------


class OverviewTests(PluginApiTestCase):
    def test_overview_forwards_relay_json(self) -> None:
        payload = {"version": "0.5.0", "uptime_seconds": 42, "health": "ok"}

        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/relay/info")
            self.assertEqual(request.url.host, "127.0.0.1")
            self.assertEqual(request.url.port, plugin_api.RELAY_PORT)
            return httpx.Response(200, json=payload)

        _install_mock_transport(self, handler)
        resp = self.client.get("/overview")
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json(), payload)


class SessionsTests(PluginApiTestCase):
    def test_sessions_forwards_relay_json(self) -> None:
        payload = {"sessions": [{"prefix": "abc12345", "paired_at": 1_700_000_000}]}

        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/sessions")
            return httpx.Response(200, json=payload)

        _install_mock_transport(self, handler)
        resp = self.client.get("/sessions")
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json(), payload)


class ProviderUsageTests(PluginApiTestCase):
    def test_reads_active_credential_from_live_dashboard_session(self) -> None:
        profile_home = Path("/profiles/victor").resolve()
        provider_usage = SimpleNamespace(
            resolve_profile_home=lambda _config, _profile: profile_home,
            collect_provider_usage=AsyncMock(
                return_value={"schema_version": 2, "providers": []}
            ),
        )
        hooks = SimpleNamespace(
            resolve_live_active_credential=lambda _session: {
                "profile_home": profile_home,
                "provider_id": "openai-codex",
                "credential_id": "entry-2",
            }
        )

        with patch.object(
            plugin_api,
            "_plugin_module",
            side_effect=lambda name: hooks if name == "hooks" else provider_usage,
        ):
            response = self.client.get(
                "/provider-usage",
                params={"profile": "victor", "session_id": "session-2"},
            )

        self.assertEqual(response.status_code, 200)
        provider_usage.collect_provider_usage.assert_awaited_once_with(
            profile_home=profile_home,
            session_id="session-2",
            active_credential_id="entry-2",
        )


class BridgeActivityTests(PluginApiTestCase):
    def test_limit_param_is_forwarded(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/bridge/activity")
            self.assertEqual(request.url.params.get("limit"), "5")
            return httpx.Response(200, json={"activity": []})

        captured = _install_mock_transport(self, handler)
        resp = self.client.get("/bridge-activity", params={"limit": 5})
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json(), {"activity": []})
        self.assertEqual(len(captured), 1)

    def test_no_limit_param_omits_query(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertNotIn("limit", request.url.params)
            return httpx.Response(200, json={"activity": []})

        _install_mock_transport(self, handler)
        resp = self.client.get("/bridge-activity")
        self.assertEqual(resp.status_code, 200)


class MediaTests(PluginApiTestCase):
    def test_include_expired_flag_forwards_true(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/media/inspect")
            self.assertEqual(request.url.params.get("include_expired"), "true")
            return httpx.Response(200, json={"media": []})

        _install_mock_transport(self, handler)
        resp = self.client.get("/media", params={"include_expired": "true"})
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json(), {"media": []})


# ---------------------------------------------------------------------------
# Push stub — no network call
# ---------------------------------------------------------------------------


class PushTests(PluginApiTestCase):
    def test_push_is_static_and_hits_no_network(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise AssertionError("push endpoint must not touch the network")

        captured = _install_mock_transport(self, handler)
        resp = self.client.get("/push")
        self.assertEqual(resp.status_code, 200)
        body = resp.json()
        self.assertEqual(body["configured"], False)
        self.assertIn("FCM", body["reason"])
        self.assertEqual(len(captured), 0)


# ---------------------------------------------------------------------------
# Error translation
# ---------------------------------------------------------------------------


class RelayErrorTests(PluginApiTestCase):
    def test_timeout_becomes_502_with_informative_detail(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.TimeoutException("timed out", request=request)

        _install_mock_transport(self, handler)
        resp = self.client.get("/overview")
        self.assertEqual(resp.status_code, 502)
        detail = resp.json()["detail"]
        self.assertIn("relay unreachable", detail)
        self.assertIn(f"127.0.0.1:{plugin_api.RELAY_PORT}", detail)

    def test_connection_error_becomes_502(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("connection refused", request=request)

        _install_mock_transport(self, handler)
        resp = self.client.get("/sessions")
        self.assertEqual(resp.status_code, 502)
        self.assertIn("relay unreachable", resp.json()["detail"])

    def test_relay_5xx_becomes_502(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(503, text="relay overloaded")

        _install_mock_transport(self, handler)
        resp = self.client.get("/overview")
        self.assertEqual(resp.status_code, 502)
        self.assertIn("relay unreachable", resp.json()["detail"])

    def test_relay_404_passes_through(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(404, json={"error": "not found"})

        _install_mock_transport(self, handler)
        resp = self.client.get("/overview")
        self.assertEqual(resp.status_code, 404)
        # FastAPI wraps HTTPException detail in {"detail": ...}.
        self.assertEqual(resp.json(), {"detail": {"error": "not found"}})


# ---------------------------------------------------------------------------
# Remote Access tab — tailscale helper, public URL state, /probe
# ---------------------------------------------------------------------------


class RemoteAccessStateTests(PluginApiTestCase):
    def setUp(self) -> None:
        super().setUp()
        # Redirect ``HERMES_HOME`` into a tmp dir so each test writes its
        # own relay-remote.json instead of touching the real one. We
        # monkey-patch ``_hermes_home`` rather than the env var so the
        # path the route resolves is identical to what the test
        # resolves.
        import tempfile

        self._tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmpdir.cleanup)
        self._orig_hermes_home = plugin_api._hermes_home
        plugin_api._hermes_home = lambda: __import__("pathlib").Path(self._tmpdir.name)
        self.addCleanup(lambda: setattr(plugin_api, "_hermes_home", self._orig_hermes_home))

    def test_put_public_url_persists_then_get_reads(self) -> None:
        resp = self.client.put(
            "/remote-access/public-url", json={"url": "https://relay.example.com"}
        )
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["url"], "https://relay.example.com")

        resp = self.client.get("/remote-access/public-url")
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["url"], "https://relay.example.com")

    def test_put_public_url_clears_on_empty(self) -> None:
        self.client.put("/remote-access/public-url", json={"url": "https://a.example.com"})
        resp = self.client.put("/remote-access/public-url", json={"url": ""})
        self.assertEqual(resp.status_code, 200)
        self.assertIsNone(resp.json()["url"])

        resp = self.client.get("/remote-access/public-url")
        self.assertEqual(resp.json()["url"], None)

    def test_put_public_url_rejects_bad_scheme(self) -> None:
        resp = self.client.put(
            "/remote-access/public-url", json={"url": "ftp://example.com"}
        )
        self.assertEqual(resp.status_code, 400)
        self.assertIn("http", resp.json()["detail"])

    def test_put_public_url_rejects_non_string(self) -> None:
        resp = self.client.put("/remote-access/public-url", json={"url": 42})
        self.assertEqual(resp.status_code, 400)


class RemoteAccessProbeTests(PluginApiTestCase):
    def test_probe_returns_per_candidate_reachability(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            # Accept the /health suffix the route appends.
            if request.url.path == "/health" and request.url.host == "relay.example.com":
                return httpx.Response(200, json={"ok": True})
            return httpx.Response(500, text="unexpected url")

        _install_mock_transport(self, handler)
        resp = self.client.post(
            "/remote-access/probe",
            json={"candidates": ["https://relay.example.com"]},
        )
        self.assertEqual(resp.status_code, 200)
        results = resp.json()["results"]
        self.assertEqual(len(results), 1)
        self.assertTrue(results[0]["reachable"])
        self.assertEqual(results[0]["status"], 200)

    def test_probe_captures_connect_error_per_entry(self) -> None:
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("refused", request=request)

        _install_mock_transport(self, handler)
        resp = self.client.post(
            "/remote-access/probe",
            json={"candidates": ["https://down.example.com"]},
        )
        self.assertEqual(resp.status_code, 200)
        results = resp.json()["results"]
        self.assertEqual(len(results), 1)
        self.assertFalse(results[0]["reachable"])
        self.assertIn("refused", results[0]["error"])

    def test_probe_rejects_non_array(self) -> None:
        resp = self.client.post(
            "/remote-access/probe", json={"candidates": "https://a.example.com"}
        )
        self.assertEqual(resp.status_code, 400)


class RemoteAccessStatusTests(PluginApiTestCase):
    def test_status_surfaces_tailscale_dict_and_public_pin(self) -> None:
        # Monkey-patch the tailscale helper so the test doesn't shell out.
        from plugin.relay import tailscale as ts_mod

        orig_status = ts_mod.status
        orig_canonical = ts_mod.canonical_upstream_present
        ts_mod.status = lambda: {
            "available": True,
            "hostname": "hermes.tail1234.ts.net",
            "tailscale_ip": "100.64.0.1",
            "serve_ports": [8767],
        }
        ts_mod.canonical_upstream_present = lambda: False
        self.addCleanup(lambda: setattr(ts_mod, "status", orig_status))
        self.addCleanup(lambda: setattr(ts_mod, "canonical_upstream_present", orig_canonical))

        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/health")
            return httpx.Response(200, json={"status": "ok"})

        _install_mock_transport(self, handler)

        resp = self.client.get("/remote-access/status")
        self.assertEqual(resp.status_code, 200)
        body = resp.json()
        self.assertEqual(body["tailscale"]["hostname"], "hermes.tail1234.ts.net")
        self.assertIsNone(body["public"]["url"])
        self.assertFalse(body["upstream_canonical"])
        self.assertFalse(body["secure_link"]["enabled"])

    def test_status_surfaces_secure_link_without_exposing_pin(self) -> None:
        from plugin.relay import tailscale as ts_mod

        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.url.path, "/health")
            return httpx.Response(200, json={
                "status": "ok",
                "secure_proxy": {
                    "role": "plugin_proxy",
                    "recommended": True,
                    "security": "pinned_tls",
                    "proxy": {
                        "url": "https://relay.example:9443",
                        "pin_sha256": "sha256/secret-pin-material",
                        "surfaces": ["relay", "api", "dashboard"],
                    },
                },
            })

        _install_mock_transport(self, handler)
        with patch.object(ts_mod, "status", return_value=None), patch.object(
            ts_mod, "canonical_upstream_present", return_value=False
        ):
            resp = self.client.get("/remote-access/status")
        self.assertEqual(resp.status_code, 200)
        secure_link = resp.json()["secure_link"]
        self.assertTrue(secure_link["enabled"])
        self.assertEqual(secure_link["url"], "https://relay.example:9443")
        self.assertEqual(secure_link["surfaces"], ["relay", "api", "dashboard"])
        self.assertNotIn("pin_sha256", secure_link)


class RemoteAccessTailscaleActionTests(PluginApiTestCase):
    def test_recommended_setup_enables_full_tailscale_stack(self) -> None:
        from plugin.relay import tailscale as ts_mod

        with patch.object(ts_mod, "enable_stack", return_value={"ok": True, "commands": ["relay", "api"]}) as enable_stack:
            resp = self.client.post("/remote-access/tailscale/enable", json={"stack": True})
        self.assertEqual(resp.status_code, 200)
        self.assertTrue(resp.json()["ok"])
        enable_stack.assert_called_once_with()

    def test_enable_allows_only_supported_relay_and_api_ports(self) -> None:
        from plugin.relay import tailscale as ts_mod

        with patch.object(ts_mod, "enable", side_effect=lambda port: {"ok": True, "port": port}):
            for port in (8767, 8642):
                with self.subTest(port=port):
                    resp = self.client.post(
                        "/remote-access/tailscale/enable", json={"port": port}
                    )
                    self.assertEqual(resp.status_code, 200)
                    self.assertEqual(resp.json()["port"], port)

    def test_enable_rejects_arbitrary_localhost_port_without_calling_helper(self) -> None:
        from plugin.relay import tailscale as ts_mod

        with patch.object(ts_mod, "enable") as mock_enable:
            resp = self.client.post(
                "/remote-access/tailscale/enable", json={"port": 22}
            )
        self.assertEqual(resp.status_code, 400)
        self.assertIn("8767", resp.json()["detail"])
        mock_enable.assert_not_called()

    def test_disable_rejects_arbitrary_or_boolean_port(self) -> None:
        from plugin.relay import tailscale as ts_mod

        with patch.object(ts_mod, "disable") as mock_disable:
            for port in (8000, True):
                with self.subTest(port=port):
                    resp = self.client.post(
                        "/remote-access/tailscale/disable", json={"port": port}
                    )
                    self.assertEqual(resp.status_code, 400)
        mock_disable.assert_not_called()


class PhoneConfigTests(PluginApiTestCase):
    """``GET /phone/config`` reflects the phone adapter's env resolution.

    No relay round-trip — the route reads process env directly, so these are
    hermetic with no MockTransport.
    """

    _ENV_KEYS = ("PHONE_ENABLED", "PHONE_HOME_CHANNEL", "PHONE_HOME_CHANNEL_NAME")

    def setUp(self) -> None:
        super().setUp()
        import os

        self._saved = {k: os.environ.get(k) for k in self._ENV_KEYS}
        for k in self._ENV_KEYS:
            os.environ.pop(k, None)

    def tearDown(self) -> None:
        import os

        for k, v in self._saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v

    def test_disabled_defaults(self) -> None:
        resp = self.client.get("/phone/config")
        self.assertEqual(resp.status_code, 200)
        body = resp.json()
        self.assertFalse(body["enabled"])
        self.assertEqual(body["home_channel_id"], "phone")
        self.assertEqual(body["home_channel_name"], "Phone")
        self.assertEqual(body["name_env_key"], "PHONE_HOME_CHANNEL_NAME")

    def test_enabled_custom_name(self) -> None:
        import os

        os.environ["PHONE_ENABLED"] = "1"
        os.environ["PHONE_HOME_CHANNEL_NAME"] = "Pixel 9"
        body = self.client.get("/phone/config").json()
        self.assertTrue(body["enabled"])
        self.assertEqual(body["home_channel_name"], "Pixel 9")
        self.assertEqual(body["home_channel_id"], "phone")


class UpdateCheckTests(PluginApiTestCase):
    """``GET /update-check`` compares installed vs latest GitHub plugin release."""

    def setUp(self) -> None:
        super().setUp()
        # Reset the module cache so every test triggers a (mocked) fetch.
        plugin_api._UPDATE_CACHE.update(latest=None, fetched_at=0.0, error=None)

    def test_update_available(self) -> None:
        def handler(_req: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=[{"tag_name": "server-v99.0.0"}])

        _install_mock_transport(self, handler)
        body = self.client.get("/update-check").json()
        self.assertTrue(body["update_available"])
        self.assertEqual(body["latest"], "99.0.0")
        self.assertIn("hermes", body["update_command"])

    def test_up_to_date(self) -> None:
        from plugin import update_check

        cur = update_check.current_version()

        def handler(_req: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=[{"tag_name": f"server-v{cur}"}])

        _install_mock_transport(self, handler)
        body = self.client.get("/update-check").json()
        self.assertFalse(body["update_available"])
        self.assertEqual(body["latest"], cur)

    def test_github_error_degrades_softly(self) -> None:
        def handler(_req: httpx.Request) -> httpx.Response:
            return httpx.Response(503, text="nope")

        _install_mock_transport(self, handler)
        body = self.client.get("/update-check").json()
        self.assertFalse(body["update_available"])
        self.assertIsNotNone(body["error"])


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
