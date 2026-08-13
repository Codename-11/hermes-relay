from __future__ import annotations

import json
import os
import shutil
import ssl
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

from aiohttp.test_utils import AioHTTPTestCase

from plugin.pair import build_pairing_qr_payload
from plugin.relay.config import RelayConfig
from plugin.relay.secure_proxy import (
    SECURE_LINK_NAME,
    advertised_candidate,
    create_secure_proxy_app,
    ensure_tls_identity,
    spki_pin_sha256,
    _forward_headers,
    _scope_dashboard_cookie,
    _rewrite_dashboard_location,
)
from plugin.relay.server import (
    RelayServer,
    _build_auth_ok_payload,
    _on_secure_proxy_startup,
    _route_credential_for_auth,
    create_app,
)


class SecureProxyRouteTests(AioHTTPTestCase):
    async def get_application(self):
        self.server_state = RelayServer(RelayConfig())
        return create_secure_proxy_app(self.server_state)

    async def asyncTearDown(self) -> None:
        await super().asyncTearDown()
        await self.server_state.close()

    async def test_health_describes_all_fixed_namespaces(self) -> None:
        response = await self.client.get("/relay/health")
        self.assertEqual(response.status, 200)
        body = await response.json()
        self.assertEqual(body["surface"], "hermes_secure_proxy")
        self.assertEqual(body["display_name"], SECURE_LINK_NAME)
        self.assertEqual(body["security"], "pinned_tls")
        self.assertEqual(body["capabilities"], ["relay", "api", "dashboard"])
        self.assertEqual(body["namespaces"], ["relay", "api", "dashboard"])
        self.assertEqual(body["services"]["relay"]["websocket_path"], "/relay/ws")
        self.assertEqual(body["services"]["api"]["base_path"], "/api")
        self.assertEqual(
            body["services"]["dashboard"]["base_path"],
            "/dashboard",
        )

        for path in (
            "/relay/sessions",
            "/relay/desktop/_ping", "/relay/pairing/register", "/health",
        ):
            response = await self.client.get(path)
            self.assertEqual(response.status, 404, path)

        self.assertEqual((await self.client.get("/api/health")).status, 502)
        self.assertEqual((await self.client.get("/dashboard/")).status, 503)

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
        self.assertEqual(qr["endpoints"][0]["role"], "plugin_proxy")
        self.assertFalse(qr["endpoints"][0]["recommended"])
        self.assertEqual(qr["endpoints"][1]["role"], "lan")
        self.assertEqual(qr["endpoints"][1]["priority"], 1)

    async def test_relay_health_exposes_secure_link_operator_status(self) -> None:
        response = await self.client.get("/health")
        self.assertEqual(response.status, 200)
        body = await response.json()
        self.assertEqual(body["secure_link"]["display_name"], "Hermes Secure Link")
        self.assertEqual(body["secure_link"]["status"], "available")
        self.assertEqual(
            body["secure_link"]["capabilities"],
            ["relay", "api", "dashboard"],
        )
        self.assertEqual(body["secure_proxy"], self.proxy)

    async def test_dashboard_mint_publishes_outbound_broker_bootstrap(self) -> None:
        connector = MagicMock()
        connector.status.return_value = {"connected": True}
        connector.publish_bootstrap = AsyncMock(return_value="t" * 43)
        connector.stop = AsyncMock()
        connector.connect_url = "wss://reach.example/v1/connect"
        connector.host_id = "h" * 22
        self.app["server"].secure_link_connector = connector

        response = await self.client.post("/pairing/mint", json={"ttl_seconds": 3600})
        self.assertEqual(response.status, 200, await response.text())
        body = await response.json()
        candidates = json.loads(body["qr_payload"])["endpoints"]
        candidate = next(c for c in candidates if c["role"] == "outbound_broker")
        self.assertEqual(candidate["role"], "outbound_broker")
        self.assertEqual(candidate["security"], "e2ee_pinned_tls")
        self.assertFalse(candidate["recommended"])
        self.assertTrue(candidate["experimental"])
        self.assertEqual(candidate["broker"]["protocol_version"], 1)
        self.assertEqual(candidate["broker"]["url"], connector.connect_url)
        self.assertEqual(candidate["broker"]["host_id"], connector.host_id)
        self.assertEqual(candidate["broker"]["credential_kind"], "bootstrap")
        self.assertEqual(candidate["broker"]["token"], "t" * 43)
        self.assertIsInstance(candidate["broker"]["expires_at"], int)
        self.assertEqual(candidate["proxy"], self.proxy["proxy"])
        direct = next(c for c in candidates if c["role"] == "plugin_proxy")
        self.assertEqual(direct["role"], "plugin_proxy")
        self.assertLess(direct["priority"], candidate["priority"])
        self.assertEqual(direct["proxy"], self.proxy["proxy"])
        connector.publish_bootstrap.assert_awaited_once()


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

    def test_candidate_declares_independently_authenticated_surfaces(self) -> None:
        candidate = advertised_candidate("192.0.2.10", 9443, "sha256/test")
        self.assertEqual(candidate["display_name"], "Hermes Secure Link")
        self.assertEqual(candidate["capabilities"], ["relay", "api", "dashboard"])
        self.assertEqual(candidate["proxy"]["surfaces"], ["relay", "api", "dashboard"])
        services = candidate["proxy"]["services"]
        self.assertEqual(services["relay"]["websocket_path"], "/relay/ws")
        self.assertEqual(services["api"]["authentication"], "api_bearer")
        self.assertEqual(
            services["dashboard"]["authentication"],
            "dashboard_session",
        )

    def test_api_header_policy_strips_dashboard_cookie_and_proxy_headers(self) -> None:
        request = unittest.mock.Mock()
        request.headers = {
            "Authorization": "Bearer api-key",
            "Cookie": "dashboard=session",
            "X-Hermes-Relay-Session": "relay-token",
            "X-Forwarded-For": "spoofed",
        }
        forwarded = _forward_headers(request)
        self.assertEqual(forwarded, {"Authorization": "Bearer api-key"})

    def test_dashboard_forwarded_host_ignores_hostile_request_host(self) -> None:
        request = unittest.mock.Mock()
        request.headers = {
            "Host": "attacker.example",
            "Cookie": "sid=abc",
        }
        request.host = "attacker.example"
        request.remote = "192.0.2.44"
        forwarded = _forward_headers(
            request,
            dashboard=True,
            forwarded_host="secure-link.example:9443",
        )
        self.assertEqual(
            forwarded["X-Forwarded-Host"],
            "secure-link.example:9443",
        )
        self.assertEqual(forwarded["Cookie"], "sid=abc")

    def test_dashboard_cookie_is_scoped_to_secure_link_namespace(self) -> None:
        self.assertEqual(
            _scope_dashboard_cookie(
                "sid=abc; Domain=127.0.0.1; Path=/; HttpOnly; Secure"
            ),
            "sid=abc; Path=/dashboard; HttpOnly; Secure",
        )
        self.assertEqual(
            _scope_dashboard_cookie("sid=abc; HttpOnly"),
            "sid=abc; HttpOnly; Path=/dashboard",
        )

    def test_dashboard_redirects_remain_in_scoped_namespace(self) -> None:
        upstream = "http://127.0.0.1:9119"
        self.assertEqual(
            _rewrite_dashboard_location("/auth/login?next=/", upstream),
            "/dashboard/auth/login?next=/",
        )
        self.assertEqual(
            _rewrite_dashboard_location(
                "http://127.0.0.1:9119/auth/callback?code=x",
                upstream,
            ),
            "/dashboard/auth/callback?code=x",
        )
        self.assertEqual(
            _rewrite_dashboard_location("https://idp.example/authorize", upstream),
            "https://idp.example/authorize",
        )
        self.assertIsNone(
            _rewrite_dashboard_location("http://attacker.example/", upstream)
        )

    def test_auth_ok_does_not_replace_operator_reviewed_endpoints(self) -> None:
        server = RelayServer(RelayConfig())
        server.secure_proxy_candidate = advertised_candidate(
            "192.0.2.10", 9443, "sha256/test"
        )
        session = server.sessions.create_session("phone", "id")
        payload = _build_auth_ok_payload(session, server)
        self.assertNotIn("endpoints", payload)
        self.assertNotIn("route_credential", payload)

    def test_env_default_is_opt_in(self) -> None:
        with patch.dict(os.environ, {"RELAY_SECURE_PROXY_ENABLED": "0"}):
            config = RelayConfig.from_env()
            self.assertFalse(config.secure_proxy_enabled)
            self.assertFalse(config.experimental_reach_enabled)

    def test_reach_requires_explicit_experimental_opt_in(self) -> None:
        server = RelayServer(RelayConfig(
            secure_link_broker_url="wss://reach.example/v1/connect",
            secure_link_broker_host_token="secret",
        ))
        self.assertIsNone(server.secure_link_connector)
        self.assertIn("RELAY_EXPERIMENTAL_REACH_ENABLED=1", server.secure_link_broker_error or "")

    def test_secure_link_env_names_override_legacy_aliases(self) -> None:
        with patch.dict(os.environ, {
            "RELAY_SECURE_LINK_ENABLED": "1",
            "RELAY_SECURE_PROXY_ENABLED": "0",
            "RELAY_SECURE_LINK_HOST": "secure-link.example",
            "RELAY_SECURE_PROXY_HOST": "legacy.example",
            "RELAY_SECURE_LINK_PORT": "10443",
            "RELAY_SECURE_PROXY_PORT": "9443",
        }):
            config = RelayConfig.from_env()
        self.assertTrue(config.secure_proxy_enabled)
        self.assertEqual(config.secure_proxy_host, "secure-link.example")
        self.assertEqual(config.secure_proxy_port, 10443)


class SecureLinkRouteCredentialTests(unittest.IsolatedAsyncioTestCase):
    async def test_auth_ok_route_credential_has_canonical_flat_shape(self) -> None:
        server = RelayServer(RelayConfig())
        session = server.sessions.create_session("phone", "téléphone-1")
        connector = MagicMock()
        connector.status.return_value = {"connected": True}
        connector.connect_url = "wss://reach.example/v1/connect"
        connector.host_id = "AAAAAAAAAAAAAAAAAAAAAA"
        connector.publish_route = AsyncMock(return_value="t" * 43)
        server.secure_link_connector = connector

        credential = await _route_credential_for_auth(session, server)

        self.assertIsNotNone(credential)
        assert credential is not None
        self.assertEqual(set(credential), {
            "kind", "broker_url", "host_id", "credential_id", "token", "expires_at",
        })
        self.assertEqual(credential["kind"], "broker_route")
        self.assertEqual(credential["broker_url"], connector.connect_url)
        self.assertEqual(credential["host_id"], connector.host_id)
        self.assertEqual(credential["token"], "t" * 43)
        self.assertRegex(credential["credential_id"], r"^[A-Za-z0-9_-]{22}$")
        payload = _build_auth_ok_payload(session, server, credential)
        self.assertEqual(payload["route_credential"], credential)

        publish = connector.publish_route.await_args.kwargs
        self.assertEqual(publish["credential_id"], credential["credential_id"])
        self.assertRegex(publish["device_id_hash"], r"^[A-Za-z0-9_-]{43}$")

    async def test_route_credential_is_reused_for_the_same_session(self) -> None:
        server = RelayServer(RelayConfig())
        session = server.sessions.create_session("phone", "device-1")
        connector = MagicMock()
        connector.status.return_value = {"connected": True}
        connector.connect_url = "wss://reach.example/v1/connect"
        connector.host_id = "AAAAAAAAAAAAAAAAAAAAAA"
        connector.publish_route = AsyncMock(return_value="t" * 43)
        server.secure_link_connector = connector

        first = await _route_credential_for_auth(session, server)
        second = await _route_credential_for_auth(session, server)

        self.assertEqual(first, second)
        connector.publish_route.assert_awaited_once()


class SecureLinkAuthOkBoundaryTests(AioHTTPTestCase):
    async def get_application(self):
        app = create_app(RelayConfig())
        connector = MagicMock()
        connector.status.return_value = {"connected": True}
        connector.connect_url = "wss://reach.example/v1/connect"
        connector.host_id = "AAAAAAAAAAAAAAAAAAAAAA"
        connector.publish_route = AsyncMock(return_value="t" * 43)
        connector.stop = AsyncMock()
        app["server"].secure_link_connector = connector
        app.on_startup.remove(_on_secure_proxy_startup)
        return app

    async def _auth(self, *, secure_link: bool) -> dict[str, object]:
        server = self.app["server"]
        session = server.sessions.create_session("phone", "device-1")
        headers = {}
        if secure_link:
            headers = {
                "X-Hermes-Proxy-Secret": server.secure_proxy_internal_secret,
                "X-Hermes-Proxy-Peer": "192.0.2.20",
            }
        ws = await self.client.ws_connect("/ws", headers=headers)
        await ws.send_json({
            "channel": "system", "type": "auth", "payload": {
                "session_token": session.token, "device_id": "device-1",
            },
        })
        envelope = await ws.receive_json()
        await ws.close()
        self.assertEqual(envelope["type"], "auth.ok")
        return envelope["payload"]

    async def test_plain_relay_auth_never_receives_route_bearer(self) -> None:
        payload = await self._auth(secure_link=False)
        self.assertNotIn("route_credential", payload)
        self.app["server"].secure_link_connector.publish_route.assert_not_awaited()

    async def test_trusted_secure_link_auth_receives_route_bearer(self) -> None:
        payload = await self._auth(secure_link=True)
        self.assertEqual(payload["route_credential"]["kind"], "broker_route")
        self.app["server"].secure_link_connector.publish_route.assert_awaited_once()
