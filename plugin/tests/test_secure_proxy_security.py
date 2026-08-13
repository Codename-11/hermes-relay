"""Security acceptance gates for the plugin-owned secure Relay facade."""

from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest import mock

from aiohttp.test_utils import TestClient, TestServer

from plugin.relay.secure_proxy import create_secure_proxy_app


class SecureProxySecurityTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        relay = SimpleNamespace(config=SimpleNamespace(port=8767))
        self.server = TestServer(create_secure_proxy_app(relay))
        self.client = TestClient(self.server)
        await self.client.start_server()

    async def asyncTearDown(self) -> None:
        await self.client.close()

    async def test_only_health_and_websocket_paths_are_registered(self) -> None:
        response = await self.client.get("/relay/health")
        self.assertEqual(response.status, 200)
        self.assertEqual(
            await response.json(),
            {"status": "ok", "surface": "relay_secure_proxy"},
        )

        # These upstream paths include loopback-trusted operator surfaces.
        # The secure facade must never turn them into remotely reachable APIs.
        forbidden = (
            "/desktop/health",
            "/desktop/desktop_powershell",
            "/sessions",
            "/relay/security",
            "/bridge/status",
            "/pairing/mint",
            "/media/inspect",
            "/api/health",
            "/dashboard/api/auth/me",
            "/relay/ws/../sessions",
            "/relay/%2e%2e/sessions",
        )
        for path in forbidden:
            with self.subTest(path=path):
                denied = await self.client.get(path)
                self.assertIn(denied.status, (404, 405))

    async def test_health_is_read_only_and_bounded(self) -> None:
        for method in ("post", "put", "patch", "delete"):
            with self.subTest(method=method):
                response = await getattr(self.client, method)(
                    "/relay/health",
                    data=b"not accepted",
                )
                self.assertEqual(response.status, 405)

    async def test_websocket_upgrade_does_not_accept_http_requests(self) -> None:
        # A plain GET must not disclose or proxy any upstream response. The
        # handler may reject before attempting upstream or close after upgrade,
        # but it must never answer as an ordinary HTTP proxy.
        with mock.patch("plugin.relay.secure_proxy.aiohttp.ClientSession") as session:
            response = await self.client.get("/relay/ws")
        self.assertNotEqual(response.status, 200)
        session.assert_not_called()


if __name__ == "__main__":
    unittest.main()
