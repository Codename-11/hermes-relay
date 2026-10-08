"""Session policy through the actual Secure Link application and HTTP routes."""

from __future__ import annotations

import shutil
import ssl
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

from aiohttp import ClientSession, ClientConnectorCertificateError, WSCloseCode, web
from aiohttp.test_utils import AioHTTPTestCase, TestServer

from plugin.relay.auth import Session
from plugin.relay.config import RelayConfig
from plugin.relay.secure_link_connector import credential_id_for
from plugin.relay.secure_proxy import create_secure_proxy_app, ensure_tls_identity, tls_context
from plugin.relay.server import RelayServer


class SecureProxySessionTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        self.relay = RelayServer(RelayConfig())
        return create_secure_proxy_app(self.relay)

    async def asyncTearDown(self) -> None:
        await super().asyncTearDown()
        await self.relay.close()

    def mint(self, name: str, ttl_seconds: int = 3600) -> Session:
        return self.relay.sessions.create_session(name, name + "-id", ttl_seconds=ttl_seconds)

    def headers(self, token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    @unittest.skipUnless(shutil.which("openssl"), "openssl is required")
    async def test_session_routes_over_verified_secure_link_tls(self) -> None:
        session = self.mint("phone")
        with tempfile.TemporaryDirectory() as directory:
            cert, key = Path(directory) / "cert.pem", Path(directory) / "key.pem"
            ensure_tls_identity(cert, key, "127.0.0.1")
            secure_server = TestServer(create_secure_proxy_app(self.relay))
            await secure_server.start_server(ssl=tls_context(cert, key))
            try:
                async with ClientSession() as client:
                    url = secure_server.make_url("/relay/sessions")
                    self.assertEqual(url.scheme, "https")
                    with self.assertRaises(ClientConnectorCertificateError):
                        await client.get(url, headers=self.headers(session.token))
                    trusted = ssl.create_default_context(cafile=str(cert))
                    for method, path, body in (
                        ("GET", "/relay/sessions", None),
                        ("PATCH", f"/relay/sessions/{session.token[:8]}", {"ttl_seconds": 60}),
                        ("DELETE", f"/relay/sessions/{session.token[:8]}", None),
                    ):
                        async with client.request(
                            method, secure_server.make_url(path), ssl=trusted,
                            headers=self.headers(session.token), json=body,
                        ) as response:
                            self.assertEqual(response.status, 200)
                            await response.read()
                    self.assertIsNone(self.relay.sessions.get_session(session.token))
            finally:
                await secure_server.close()

    async def test_all_routes_require_valid_bearer_even_on_loopback(self) -> None:
        target = self.mint("phone")
        for authorization in (None, "", "Basic test", "Bearer ", "Bearer invalid"):
            for method, path in (
                ("GET", "/relay/sessions"),
                ("PATCH", f"/relay/sessions/{target.token[:8]}"),
                ("DELETE", f"/relay/sessions/{target.token[:8]}"),
            ):
                with self.subTest(authorization=authorization, method=method):
                    headers = {} if authorization is None else {"Authorization": authorization}
                    # Neither forwarding nor internal proxy headers confer operator authority.
                    headers["X-Forwarded-For"] = "127.0.0.1"
                    headers["X-Hermes-Proxy-Secret"] = self.relay.secure_proxy_internal_secret
                    response = await self.client.request(method, path, headers=headers)
                    self.assertEqual(response.status, 401)
                    self.assertIsNotNone(self.relay.sessions.get_session(target.token))

    async def test_expired_bearer_is_rejected(self) -> None:
        session = self.mint("expired")
        session.expires_at = time.time() - 1
        for method, path in (
            ("GET", "/relay/sessions"),
            ("PATCH", f"/relay/sessions/{session.token[:8]}"),
            ("DELETE", f"/relay/sessions/{session.token[:8]}"),
        ):
            response = await self.client.request(method, path, headers=self.headers(session.token))
            self.assertEqual(response.status, 401)

    async def test_listing_preserves_current_identity_and_redacts_tokens(self) -> None:
        current = self.mint("phone")
        other = self.mint("desktop", ttl_seconds=0)
        response = await self.client.get("/relay/sessions", headers=self.headers(current.token))
        self.assertEqual(response.status, 200)
        entries = {entry["device_name"]: entry for entry in (await response.json())["sessions"]}
        self.assertEqual(set(entries), {"phone", "desktop"})
        self.assertTrue(entries["phone"]["is_current"])
        self.assertFalse(entries["desktop"]["is_current"])
        self.assertIsNone(entries["desktop"]["expires_at"])
        for session in (current, other):
            self.assertNotIn("token", entries[session.device_name])
            self.assertEqual(entries[session.device_name]["token_prefix"], session.token[:8])

    async def test_shortening_clamps_grants_without_renewing_them(self) -> None:
        session = self.mint("phone")
        session.grants["bridge"] = time.time() + 60
        old_grants = dict(session.grants)
        response = await self.client.patch(
            f"/relay/sessions/{session.token[:8]}",
            headers=self.headers(session.token), json={"ttl_seconds": 300},
        )
        self.assertEqual(response.status, 200)
        body = await response.json()
        self.assertAlmostEqual(body["expires_at"], time.time() + 300, delta=5)
        for channel, expiry in body["grants"].items():
            self.assertEqual(expiry, min(old_grants[channel], body["expires_at"]))

    async def test_grant_reduction_preserves_other_channel_limits(self) -> None:
        session = self.mint("phone", ttl_seconds=30 * 86400)
        old_expiry, old_grants = session.expires_at, dict(session.grants)
        response = await self.client.patch(
            f"/relay/sessions/{session.token[:8]}",
            headers=self.headers(session.token), json={"grants": {"terminal": 60}},
        )
        self.assertEqual(response.status, 200)
        self.assertEqual(session.expires_at, old_expiry)
        self.assertAlmostEqual(session.grants["terminal"], time.time() + 60, delta=5)
        self.assertEqual(session.grants["bridge"], old_grants["bridge"])
        self.assertLessEqual(session.grants["bridge"], time.time() + 7 * 86400)
        self.assertEqual(session.grants["chat"], old_grants["chat"])

    async def test_expansions_fail_atomically(self) -> None:
        session = self.mint("phone")
        old_expiry, old_grants = session.expires_at, dict(session.grants)
        for body in (
            {"ttl_seconds": 7200}, {"ttl_seconds": 0},
            {"grants": {"terminal": 7200}}, {"grants": {"bridge": 0}},
            {"ttl_seconds": 60, "grants": {"bridge": 7200}},
        ):
            with self.subTest(body=body):
                response = await self.client.patch(
                    f"/relay/sessions/{session.token[:8]}",
                    headers=self.headers(session.token), json=body,
                )
                self.assertEqual(response.status, 403)
                self.assertIn("operator approval required", await response.text())
                self.assertEqual(session.expires_at, old_expiry)
                self.assertEqual(session.grants, old_grants)

    async def test_other_session_cannot_be_shortened(self) -> None:
        current, other = self.mint("phone"), self.mint("desktop")
        old_expiry, old_grants = other.expires_at, dict(other.grants)
        response = await self.client.patch(
            f"/relay/sessions/{other.token[:8]}",
            headers=self.headers(current.token), json={"ttl_seconds": 60},
        )
        self.assertEqual(response.status, 403)
        self.assertEqual(other.expires_at, old_expiry)
        self.assertEqual(other.grants, old_grants)

    async def test_revoke_other_session_closes_socket_and_removes_route_credential(self) -> None:
        current, target = self.mint("phone"), self.mint("desktop")
        socket = AsyncMock()
        socket.closed = False
        self.relay._clients[socket] = target.token
        credential_id = credential_id_for(target.token)
        self.relay.secure_link_route_credentials[credential_id] = {}
        response = await self.client.delete(
            f"/relay/sessions/{target.token[:8]}", headers=self.headers(current.token),
        )
        self.assertEqual(response.status, 200)
        self.assertFalse((await response.json())["revoked_self"])
        self.assertIsNone(self.relay.sessions.get_session(target.token))
        self.assertIsNotNone(self.relay.sessions.get_session(current.token))
        self.assertNotIn(credential_id, self.relay.secure_link_route_credentials)
        socket.close.assert_awaited_once_with(
            code=WSCloseCode.POLICY_VIOLATION, message=b"Relay session revoked",
        )
        response = await self.client.get("/relay/sessions", headers=self.headers(target.token))
        self.assertEqual(response.status, 401)
        self.relay._clients.clear()

    async def test_self_revocation_invalidates_bearer(self) -> None:
        session = self.mint("phone")
        response = await self.client.delete(
            f"/relay/sessions/{session.token[:8]}", headers=self.headers(session.token),
        )
        self.assertEqual(response.status, 200)
        self.assertTrue((await response.json())["revoked_self"])
        response = await self.client.get("/relay/sessions", headers=self.headers(session.token))
        self.assertEqual(response.status, 401)

    async def test_only_session_methods_are_mounted(self) -> None:
        for method, path in (
            ("POST", "/relay/sessions"), ("PUT", "/relay/sessions/prefix"),
            ("GET", "/sessions"), ("POST", "/relay/pairing/mint"),
        ):
            response = await self.client.request(method, path)
            self.assertIn(response.status, (404, 405))
