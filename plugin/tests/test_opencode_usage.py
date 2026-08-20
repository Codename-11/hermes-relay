"""Tests for GET /usage/opencode (OpenCode Go subscription usage proxy).

The endpoint is a thin authenticated proxy: the phone sends its relay bearer
token, and the server reads ``OPENCODE_GO_API_KEY`` from ``~/.hermes/.env``,
calls the upstream OpenCode Go usage API, and returns the per-window usage plus
the dollar caps. The API key must never appear in the response.
"""

from __future__ import annotations

import os
import unittest
from unittest import mock

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app

_SAMPLE_UPSTREAM: dict = {
    "usage": {
        "rolling": {"status": "ok", "percent": 42, "resetsAt": "2026-08-20T05:00:00Z"},
        "weekly": {"status": "ok", "percent": 18, "resetsAt": "2026-08-24T00:00:00Z"},
        "monthly": {"status": "ok", "percent": 55, "resetsAt": "2026-09-01T00:00:00Z"},
    }
}


class _FakeResponse:
    def __init__(self, status: int = 200, payload: dict | None = None, text: str = ""):
        self.status = status
        self._payload = payload if payload is not None else _SAMPLE_UPSTREAM
        self._text = text

    async def __aenter__(self) -> "_FakeResponse":
        return self

    async def __aexit__(self, *exc: object) -> bool:
        return False

    async def json(self) -> dict:
        return self._payload

    async def text(self) -> str:
        return self._text


class _FakeSession:
    def __init__(self, response: _FakeResponse):
        self._response = response
        self.last_get: tuple[str, dict | None] | None = None

    async def __aenter__(self) -> "_FakeSession":
        return self

    async def __aexit__(self, *exc: object) -> bool:
        return False

    def get(self, url: str, headers: dict | None = None, timeout=None) -> _FakeResponse:
        self.last_get = (url, headers)
        return self._response


class OpenCodeUsageTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        config = RelayConfig()
        return create_app(config)

    def _server(self):
        return self.app["server"]

    async def _mint(self, name: str = "dev") -> str:
        session = self._server().sessions.create_session(name, name + "-id")
        return session.token

    @mock.patch.dict(os.environ, {"OPENCODE_GO_API_KEY": "test-key"}, clear=False)
    async def test_requires_bearer(self) -> None:
        resp = await self.client.get("/usage/opencode")
        self.assertEqual(resp.status, 401)
        resp = await self.client.get(
            "/usage/opencode", headers={"Authorization": "Bearer nope"}
        )
        self.assertEqual(resp.status, 401)

    async def test_missing_key_returns_404(self) -> None:
        token = await self._mint()
        with mock.patch.dict(os.environ, {}, clear=True):
            os.environ.pop("OPENCODE_GO_API_KEY", None)
            resp = await self.client.get(
                "/usage/opencode", headers={"Authorization": f"Bearer {token}"}
            )
        # 404 = OpenCode Go simply not configured on this host (feature N/A),
        # NOT a 500 — the phone renders this as a quiet "not available".
        self.assertEqual(resp.status, 404)

    @mock.patch.dict(os.environ, {"OPENCODE_GO_API_KEY": "test-key"}, clear=False)
    async def test_proxies_usage_to_paired_bearer(self) -> None:
        token = await self._mint()
        fake = _FakeSession(_FakeResponse(200))
        with mock.patch(
            "plugin.relay.server.aiohttp.ClientSession", return_value=fake
        ):
            resp = await self.client.get(
                "/usage/opencode", headers={"Authorization": f"Bearer {token}"}
            )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["usage"], _SAMPLE_UPSTREAM["usage"])
        self.assertEqual(body["limits"]["rolling"]["limit"], 12.0)
        self.assertEqual(body["limits"]["weekly"]["limit"], 30.0)
        self.assertEqual(body["limits"]["monthly"]["limit"], 60.0)
        # The API key must be forwarded upstream but never echoed back.
        assert fake.last_get is not None
        url, headers = fake.last_get
        self.assertIn("/usage", url)
        self.assertEqual(headers["Authorization"], "Bearer test-key")
        self.assertEqual(headers["User-Agent"], "curl/8.4.0")
        self.assertNotIn("test-key", body)

    @mock.patch.dict(os.environ, {"OPENCODE_GO_API_KEY": "test-key"}, clear=False)
    async def test_upstream_500_propagates_502(self) -> None:
        token = await self._mint()
        fake = _FakeSession(_FakeResponse(500, text="boom"))
        with mock.patch(
            "plugin.relay.server.aiohttp.ClientSession", return_value=fake
        ):
            resp = await self.client.get(
                "/usage/opencode", headers={"Authorization": f"Bearer {token}"}
            )
        self.assertEqual(resp.status, 502)


if __name__ == "__main__":
    unittest.main()
