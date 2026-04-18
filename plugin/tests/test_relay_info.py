"""Tests for GET /relay/info — the dashboard's overview endpoint.

Asserts the aggregate status shape, loopback gating, and that the
numeric counters are wired to the right underlying state.
"""

from __future__ import annotations

import unittest

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app, handle_relay_info


class RelayInfoRouteTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        config = RelayConfig()
        return create_app(config)

    async def test_loopback_returns_all_required_keys(self) -> None:
        resp = await self.client.get("/relay/info")
        self.assertEqual(resp.status, 200)
        body = await resp.json()

        required = {
            "version",
            "uptime_seconds",
            "session_count",
            "paired_device_count",
            "pending_commands",
            "media_entry_count",
            "health",
        }
        self.assertTrue(required.issubset(set(body.keys())))

        self.assertEqual(body["health"], "ok")
        self.assertIsInstance(body["version"], str)
        self.assertGreater(len(body["version"]), 0)

        for counter in (
            "uptime_seconds",
            "session_count",
            "paired_device_count",
            "pending_commands",
            "media_entry_count",
        ):
            self.assertIsInstance(body[counter], int, msg=counter)
            self.assertGreaterEqual(body[counter], 0, msg=counter)

    async def test_session_count_reflects_paired_devices(self) -> None:
        server = self.app["server"]
        server.sessions.create_session("dev-a", "dev-a-id")
        server.sessions.create_session("dev-b", "dev-b-id")

        resp = await self.client.get("/relay/info")
        body = await resp.json()
        self.assertEqual(body["session_count"], 2)
        self.assertEqual(body["paired_device_count"], 2)


class RelayInfoNonLoopbackTests(unittest.IsolatedAsyncioTestCase):
    async def test_non_loopback_raises_forbidden(self) -> None:
        config = RelayConfig()
        app = create_app(config)

        class _Req:
            def __init__(self, remote: str, a: web.Application) -> None:
                self.remote = remote
                self.app = a
                self.query: dict[str, str] = {}

            @property
            def headers(self):
                return {}

        req = _Req(remote="10.1.2.3", a=app)
        with self.assertRaises(web.HTTPForbidden):
            await handle_relay_info(req)  # type: ignore[arg-type]


if __name__ == "__main__":
    unittest.main()
