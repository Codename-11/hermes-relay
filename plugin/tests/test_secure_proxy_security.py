"""Security acceptance gates for the plugin-owned secure Relay facade."""

from __future__ import annotations

import asyncio
import unittest
from types import SimpleNamespace
from unittest import mock

from aiohttp.test_utils import TestClient, TestServer

from plugin.relay.secure_proxy import (
    PROXY_HTTP_IDLE_TIMEOUT_SECONDS,
    PROXY_HTTP_TOTAL_TIMEOUT_SECONDS,
    create_secure_proxy_app,
)


class SecureProxySecurityTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        relay = SimpleNamespace(
            config=SimpleNamespace(
                port=8767,
                webapi_url="http://127.0.0.1:8642",
                secure_proxy_dashboard_url="http://127.0.0.1:9119",
            ),
            secure_proxy_internal_secret="test-secret",
        )
        self.server = TestServer(create_secure_proxy_app(relay))
        self.client = TestClient(self.server)
        await self.client.start_server()

    async def asyncTearDown(self) -> None:
        await self.client.close()

    async def test_only_fixed_namespaces_are_registered(self) -> None:
        response = await self.client.get("/relay/health")
        self.assertEqual(response.status, 200)
        body = await response.json()
        self.assertEqual(body["status"], "ok")
        self.assertEqual(body["surface"], "hermes_secure_proxy")
        self.assertEqual(body["display_name"], "Hermes Secure Link")
        self.assertEqual(body["security"], "pinned_tls")
        self.assertEqual(body["capabilities"], ["relay", "api", "dashboard"])
        self.assertEqual(body["namespaces"], ["relay", "api", "dashboard"])
        self.assertEqual(body["services"]["relay"]["websocket_path"], "/relay/ws")

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
            "/relay/ws/../sessions",
            "/relay/%2e%2e/sessions",
        )
        for path in forbidden:
            with self.subTest(path=path):
                denied = await self.client.get(path)
                self.assertIn(denied.status, (404, 405))

        # Fixed API/Dashboard namespaces exist, but never expose arbitrary
        # Relay/operator routes or an unauthenticated loopback Dashboard.
        self.assertEqual((await self.client.get("/api/health")).status, 502)
        self.assertEqual((await self.client.get("/dashboard/api/auth/me")).status, 503)

    async def test_health_is_read_only_and_bounded(self) -> None:
        for method in ("post", "put", "patch", "delete"):
            with self.subTest(method=method):
                response = await getattr(self.client, method)(
                    "/relay/health",
                    data=b"not accepted",
                )
                self.assertEqual(response.status, 405)

        for method in ("CONNECT", "TRACE"):
            with self.subTest(method=method):
                response = await self.client.request(method, "/api/health")
                self.assertEqual(response.status, 405)

    async def test_websocket_upgrade_does_not_accept_http_requests(self) -> None:
        # A plain GET must not disclose or proxy any upstream response. The
        # handler may reject before attempting upstream or close after upgrade,
        # but it must never answer as an ordinary HTTP proxy.
        with mock.patch("plugin.relay.secure_proxy.aiohttp.ClientSession") as session:
            response = await self.client.get("/relay/ws")
        self.assertNotEqual(response.status, 200)
        session.assert_not_called()

    async def test_concurrent_health_requests_share_one_availability_refresh(self) -> None:
        with (
            mock.patch(
                "plugin.relay.secure_proxy._api_available",
                new=mock.AsyncMock(return_value=True),
            ) as api_probe,
            mock.patch(
                "plugin.relay.secure_proxy._dashboard_gate_enabled",
                new=mock.AsyncMock(return_value=True),
            ) as dashboard_probe,
        ):
            responses = await asyncio.gather(*[
                self.client.get("/relay/health") for _ in range(5)
            ])
            bodies = await asyncio.gather(*[
                response.json() for response in responses
            ])
        self.assertTrue(all(
            body["services"]["api"]["available"] for body in bodies
        ))
        self.assertEqual(api_probe.await_count, 1)
        self.assertEqual(dashboard_probe.await_count, 1)

    async def test_dashboard_requests_reuse_coalesced_auth_gate(self) -> None:
        with (
            mock.patch(
                "plugin.relay.secure_proxy._api_available",
                new=mock.AsyncMock(return_value=False),
            ) as api_probe,
            mock.patch(
                "plugin.relay.secure_proxy._dashboard_gate_enabled",
                new=mock.AsyncMock(return_value=False),
            ) as dashboard_probe,
        ):
            responses = await asyncio.gather(*[
                self.client.get("/dashboard/api/auth/me") for _ in range(5)
            ])
        self.assertTrue(all(response.status == 503 for response in responses))
        self.assertEqual(api_probe.await_count, 1)
        self.assertEqual(dashboard_probe.await_count, 1)

    def test_http_upstream_timeouts_are_finite(self) -> None:
        self.assertGreater(PROXY_HTTP_TOTAL_TIMEOUT_SECONDS, 0)
        self.assertGreater(PROXY_HTTP_IDLE_TIMEOUT_SECONDS, 0)
        self.assertLessEqual(
            PROXY_HTTP_IDLE_TIMEOUT_SECONDS,
            PROXY_HTTP_TOTAL_TIMEOUT_SECONDS,
        )


if __name__ == "__main__":
    unittest.main()
