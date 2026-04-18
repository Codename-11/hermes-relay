"""Hermetic unit tests for the dashboard plugin's proxy router.

Uses FastAPI's ``TestClient`` + ``httpx.MockTransport`` patched over
``httpx.AsyncClient`` so no real HTTP hits the loopback relay during CI.
"""

from __future__ import annotations

import unittest
from typing import Callable, Optional

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

from plugin.dashboard import plugin_api


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


def _build_client() -> TestClient:
    app = FastAPI()
    app.include_router(plugin_api.router)
    return TestClient(app)


class PluginApiTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.client = _build_client()


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


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
