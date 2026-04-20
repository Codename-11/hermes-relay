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

        resp = self.client.get("/remote-access/status")
        self.assertEqual(resp.status_code, 200)
        body = resp.json()
        self.assertEqual(body["tailscale"]["hostname"], "hermes.tail1234.ts.net")
        self.assertIsNone(body["public"]["url"])
        self.assertFalse(body["upstream_canonical"])


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
