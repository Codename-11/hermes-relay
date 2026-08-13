from __future__ import annotations

import json
import os
import shutil
import ssl
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from aiohttp.test_utils import AioHTTPTestCase

from plugin.pair import build_pairing_qr_payload
from plugin.relay.config import RelayConfig
from plugin.relay.secure_proxy import (
    advertised_candidate,
    create_secure_proxy_app,
    ensure_tls_identity,
    spki_pin_sha256,
)
from plugin.relay.server import (
    RelayServer,
    _build_auth_ok_payload,
    _on_secure_proxy_startup,
    create_app,
)


class SecureProxyRouteTests(AioHTTPTestCase):
    async def get_application(self):
        self.server_state = RelayServer(RelayConfig())
        return create_secure_proxy_app(self.server_state)

    async def asyncTearDown(self) -> None:
        await super().asyncTearDown()
        await self.server_state.close()

    async def test_health_is_the_only_http_surface(self) -> None:
        response = await self.client.get("/relay/health")
        self.assertEqual(response.status, 200)
        self.assertEqual((await response.json())["surface"], "relay_secure_proxy")

        for path in (
            "/api/health", "/dashboard/", "/relay/sessions",
            "/relay/desktop/_ping", "/relay/pairing/register", "/health",
        ):
            response = await self.client.get(path)
            self.assertEqual(response.status, 404, path)

    async def test_mutating_health_is_rejected(self) -> None:
        response = await self.client.post("/relay/health")
        self.assertEqual(response.status, 405)


class SecureProxyMintTests(AioHTTPTestCase):
    async def get_application(self):
        app = create_app(RelayConfig(webapi_url="http://192.0.2.20:8642"))
        self.proxy = advertised_candidate("192.0.2.10", 9443, "sha256/test")
        app["server"].secure_proxy_candidate = self.proxy
        app.on_startup.remove(_on_secure_proxy_startup)
        return app

    async def test_dashboard_mint_signs_proxy_before_fallback_routes(self) -> None:
        lan = {
            "role": "lan", "priority": 0,
            "relay": {"url": "ws://192.0.2.20:8767", "transport_hint": "ws"},
        }
        response = await self.client.post(
            "/pairing/mint",
            json={"endpoints": [lan], "ttl_seconds": 3600},
        )
        self.assertEqual(response.status, 200, await response.text())
        body = await response.json()
        qr = json.loads(body["qr_payload"])
        self.assertEqual(qr["endpoints"][0], self.proxy)
        self.assertEqual(qr["endpoints"][1]["role"], "lan")
        self.assertEqual(qr["endpoints"][1]["priority"], 1)


@unittest.skipUnless(shutil.which("openssl"), "openssl is required")
class SecureProxyIdentityTests(unittest.TestCase):
    def test_private_identity_has_advertised_ip_san_and_spki_pin(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cert = Path(directory) / "identity" / "cert.pem"
            key = Path(directory) / "identity" / "key.pem"
            ensure_tls_identity(cert, key, "192.0.2.10")
            if os.name != "nt":
                self.assertEqual(os.stat(key).st_mode & 0o777, 0o600)
            decoded = ssl._ssl._test_decode_cert(str(cert))
            self.assertIn(("IP Address", "192.0.2.10"), decoded["subjectAltName"])
            self.assertRegex(spki_pin_sha256(cert), r"^sha256/[A-Za-z0-9+/]{43}=$")

    def test_changed_advertised_host_requires_explicit_rotation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cert = Path(directory) / "cert.pem"
            key = Path(directory) / "key.pem"
            ensure_tls_identity(cert, key, "192.0.2.10")
            first = spki_pin_sha256(cert)
            with self.assertRaisesRegex(ValueError, "explicitly remove/replace"):
                ensure_tls_identity(cert, key, "192.0.2.11")
            self.assertEqual(spki_pin_sha256(cert), first)


class SecureProxyAdvertisementTests(unittest.TestCase):
    def test_pairing_payload_carries_operator_reviewed_pin(self) -> None:
        candidate = advertised_candidate("192.0.2.10", 9443, "sha256/test")
        payload = json.loads(build_pairing_qr_payload(
            host="192.0.2.10", port=8642, key="key", tls=False,
            endpoints=[candidate], sign=False,
        ))
        self.assertEqual(payload["endpoints"][0], candidate)

    def test_auth_ok_does_not_replace_operator_reviewed_endpoints(self) -> None:
        server = RelayServer(RelayConfig())
        server.secure_proxy_candidate = advertised_candidate(
            "192.0.2.10", 9443, "sha256/test"
        )
        session = server.sessions.create_session("phone", "id")
        payload = _build_auth_ok_payload(session, server)
        self.assertNotIn("endpoints", payload)

    def test_env_default_is_opt_in(self) -> None:
        with patch.dict(os.environ, {"RELAY_SECURE_PROXY_ENABLED": "0"}):
            self.assertFalse(RelayConfig.from_env().secure_proxy_enabled)
