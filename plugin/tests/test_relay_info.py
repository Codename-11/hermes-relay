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
            "plugin_version",
            "protocol_version",
            "capabilities",
            "profiles",
            "uptime_seconds",
            "session_count",
            "paired_device_count",
            "pending_commands",
            "media_entry_count",
            "health",
            "gateway_heartbeat",
        }
        self.assertTrue(required.issubset(set(body.keys())))

        self.assertEqual(body["health"], "ok")
        self.assertIsInstance(body["version"], str)
        self.assertGreater(len(body["version"]), 0)
        self.assertEqual(body["plugin_version"], body["version"])
        self.assertEqual(body["protocol_version"], 1)
        self.assertIn("profiles", body["capabilities"])
        self.assertLessEqual(
            set(body["gateway_heartbeat"]),
            {"status", "supported", "age_seconds"},
        )
        self.assertNotIn("pid", body["gateway_heartbeat"])
        self.assertNotIn("path", body["gateway_heartbeat"])
        self.assertNotIn("updated_at", body["gateway_heartbeat"])

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
    async def test_non_loopback_requires_bearer(self) -> None:
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
        with self.assertRaises(web.HTTPUnauthorized):
            await handle_relay_info(req)  # type: ignore[arg-type]

    async def test_non_loopback_accepts_paired_bearer(self) -> None:
        app = create_app(RelayConfig())
        session = app["server"].sessions.create_session("phone", "phone-id")

        class _Req:
            remote = "10.1.2.3"
            query: dict[str, str] = {}
            headers = {"Authorization": f"Bearer {session.token}"}

            def __init__(self, a: web.Application) -> None:
                self.app = a

        response = await handle_relay_info(_Req(app))  # type: ignore[arg-type]
        self.assertEqual(response.status, 200)


if __name__ == "__main__":
    unittest.main()
