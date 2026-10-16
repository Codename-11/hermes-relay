from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import os
import shutil
import ssl
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch
from urllib.parse import parse_qs, urlsplit

import aiohttp
from aiohttp.test_utils import AioHTTPTestCase, TestClient, TestServer

from plugin.pair import build_pairing_qr_payload
from plugin.relay.config import RelayConfig
from plugin.relay.secure_proxy import (
    SECURE_LINK_NAME,
    advertised_candidate,
    certificate_der_base64,
    create_secure_proxy_app,
    ensure_tls_identity,
    spki_pin_sha256,
    _forward_headers,
    _scope_dashboard_cookie,
    _rewrite_dashboard_body,
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
        self.enterContext(patch("plugin.relay.secure_proxy._api_available", new=AsyncMock(return_value=False)))
        self.enterContext(patch("plugin.relay.secure_proxy._dashboard_gate_enabled", new=AsyncMock(return_value=False)))
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
        self.assertIn("version", body)
        self.assertTrue(str(body["version"]).strip())
        self.assertEqual(body["capabilities"], ["relay", "api", "dashboard"])
        self.assertEqual(body["namespaces"], ["relay", "api", "dashboard"])
        self.assertEqual(body["services"]["relay"]["websocket_path"], "/relay/ws")
        self.assertEqual(body["services"]["api"]["base_path"], "/api")
        self.assertEqual(
            body["services"]["dashboard"]["base_path"],
            "/dashboard",
        )

        for path in (
            "/relay/voice/config",
            "/relay/desktop/_ping", "/relay/pairing/register", "/health",
        ):
            response = await self.client.get(path)
            self.assertEqual(response.status, 404, path)

        # Deterministic failures; never depend on a developer's local services.
        from aiohttp import ClientConnectionError
        with patch("plugin.relay.secure_proxy._proxy_http", new=AsyncMock(side_effect=ClientConnectionError)):
            self.assertEqual((await self.client.get("/api/health")).status, 502)
        self.assertEqual((await self.client.get("/dashboard")).status, 503)
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

    async def test_cli_tailnet_route_is_signed_once_before_secure_link(self) -> None:
        tailnet = {"role": "tailscale", "priority": 0, "recommended": True, "legacy": True,
            "relay": {"url": "ws://100.64.0.1:8767", "transport_hint": "ws"}}
        response = await self.client.post("/pairing/mint", json={
            "legacy_direct_relay": True, "endpoints": [tailnet],
        })
        self.assertEqual(response.status, 200, await response.text())
        candidates = json.loads((await response.json())["qr_payload"])["endpoints"]
        matches = [c for c in candidates if c.get("relay", {}).get("url") == "ws://100.64.0.1:8767"]
        self.assertEqual(len(matches), 1)
        self.assertEqual(candidates[0]["relay"]["url"], "ws://100.64.0.1:8767")
        self.assertEqual([c["priority"] for c in candidates], list(range(len(candidates))))

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

    async def test_secure_link_keeps_explicit_legacy_routes_after_lan_and_reach_last(self) -> None:
        connector = MagicMock()
        connector.status.return_value = {"connected": True}
        connector.publish_bootstrap = AsyncMock(return_value="t" * 43)
        connector.stop = AsyncMock()
        connector.connect_url = "wss://reach.example/v1/connect"
        connector.host_id = "h" * 22
        self.app["server"].secure_link_connector = connector
        endpoints = [
            {
                "role": "tailscale",
                "priority": 0,
                "dashboard": {"url": "http://100.64.0.20:9119"},
                "relay": {
                    "url": "ws://100.64.0.20:9119/api/plugins/hermes-relay/transport",
                    "transport_hint": "ws",
                },
            },
            {
                "role": "public",
                "priority": 1,
                "dashboard": {"url": "https://public.example"},
                "relay": {
                    "url": "wss://public.example/api/plugins/hermes-relay/transport",
                    "transport_hint": "wss",
                },
            },
            {
                "role": "lan",
                "priority": 2,
                "dashboard": {"url": "http://192.168.1.20:9119"},
                "relay": {
                    "url": "ws://192.168.1.20:9119/api/plugins/hermes-relay/transport",
                    "transport_hint": "ws",
                },
            },
            {
                "role": "public_legacy",
                "priority": 3,
                "legacy": True,
                "recommended": False,
                "relay": {"url": "wss://public.example:8767", "transport_hint": "wss"},
            },
            {
                "role": "legacy_direct",
                "priority": 4,
                "legacy": True,
                "recommended": False,
                "relay": {"url": "ws://192.168.1.20:8767", "transport_hint": "ws"},
            },
        ]

        with patch("plugin.pair._tailscale_status", return_value=None):
            response = await self.client.post(
                "/pairing/mint",
                json={
                    "endpoints": endpoints,
                    "legacy_direct_relay": True,
                    "ttl_seconds": 3600,
                },
            )

        self.assertEqual(response.status, 200, await response.text())
        candidates = json.loads((await response.json())["qr_payload"])["endpoints"]
        roles = [candidate["role"] for candidate in candidates]
        self.assertEqual(roles, [
            "tailscale",
            "public",
            "plugin_proxy",
            "lan",
            "public_legacy",
            "legacy_direct",
            "outbound_broker",
        ])
        self.assertEqual(
            [candidate["priority"] for candidate in candidates],
            list(range(len(candidates))),
        )
        self.assertEqual(candidates[-1]["role"], "outbound_broker")


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


@unittest.skipUnless(shutil.which("openssl"), "openssl is required")
class SecureProxyCertificateChainTests(unittest.IsolatedAsyncioTestCase):
    """Use disposable CA/leaf identities; never read an operator's certificates."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture_dir = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.fixture_dir.cleanup)
        root = Path(cls.fixture_dir.name)

        def openssl(*args: str) -> bytes:
            return subprocess.run(
                ["openssl", *args], cwd=root, check=True,
                capture_output=True, timeout=30,
            ).stdout

        openssl(
            "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
            "-subj", "/CN=Test Root", "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign",
            "-keyout", "root.key", "-out", "root.pem",
        )
        for name, issuer, serial, extensions in (
            ("intermediate", "root", "2", "basicConstraints=critical,CA:TRUE\n"
             "keyUsage=critical,keyCertSign,cRLSign\n"),
            ("leaf", "intermediate", "3", "basicConstraints=critical,CA:FALSE\n"
             "subjectAltName=IP:127.0.0.1\n"
             "extendedKeyUsage=serverAuth\n"),
        ):
            openssl(
                "req", "-new", "-newkey", "rsa:2048", "-nodes",
                "-subj", f"/CN=Test {name}", "-keyout", f"{name}.key",
                "-out", f"{name}.csr",
            )
            (root / "extensions.cnf").write_text(extensions, encoding="ascii")
            openssl(
                "x509", "-req", "-in", f"{name}.csr", "-CA", f"{issuer}.pem",
                "-CAkey", f"{issuer}.key", "-set_serial", serial, "-days", "2",
                "-extfile", "extensions.cnf", "-out", f"{name}.pem",
            )
        # An unpadded leaf forces the old whole-file base64 decoder to consume
        # the following PEM boundary instead of accidentally stopping at '='.
        leaf_der = ssl.PEM_cert_to_DER_cert((root / "leaf.pem").read_text())
        if len(leaf_der) % 3:
            serial = "128" if len(leaf_der) % 3 == 2 else "32768"
            openssl(
                "x509", "-req", "-in", "leaf.csr", "-CA", "intermediate.pem",
                "-CAkey", "intermediate.key", "-set_serial", serial, "-days", "2",
                "-extfile", "extensions.cnf", "-out", "leaf.pem",
            )
        cls.leaf = (root / "leaf.pem").read_bytes()
        cls.intermediate = (root / "intermediate.pem").read_bytes()
        cls.root_cert = (root / "root.pem").read_bytes()
        cls.leaf_key = (root / "leaf.key").read_bytes()
        cls.leaf_der = ssl.PEM_cert_to_DER_cert(cls.leaf.decode("ascii"))
        cls.chain = cls.leaf + cls.intermediate + cls.root_cert

    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.cert = self.directory / "private-cert.pem"
        self.key = self.directory / "private-key.pem"
        self.cert.write_bytes(self.chain)
        self.key.write_bytes(self.leaf_key)
        self.enterContext(patch.dict(os.environ, {"HERMES_HOME": str(self.directory)}))
        self.enterContext(patch("plugin.relay.secure_proxy._api_available",
                                new=AsyncMock(return_value=False)))
        self.enterContext(patch("plugin.relay.secure_proxy._dashboard_gate_enabled",
                                new=AsyncMock(return_value=False)))
        self.enterContext(patch("plugin.pair._tailscale_status", return_value=None))

    def config(self) -> RelayConfig:
        return RelayConfig(
            host="127.0.0.1",
            secure_proxy_enabled=True, secure_proxy_host="127.0.0.1",
            secure_proxy_port=0, secure_proxy_cert=str(self.cert),
            secure_proxy_key=str(self.key),
            hermes_config_path=str(self.directory / "config.yaml"),
        )

    def test_single_and_multiple_certificates_export_only_leaf(self) -> None:
        self.assertEqual(len(self.leaf_der) % 3, 0, "exercise an unpadded PEM leaf")
        for pem in (self.leaf, self.leaf + self.intermediate, self.chain):
            with self.subTest(certificates=pem.count(b"BEGIN CERTIFICATE")):
                self.cert.write_bytes(pem)
                encoded = certificate_der_base64(self.cert)
                self.assertEqual(base64.b64decode(encoded, validate=True), self.leaf_der)
                # Derive SPKI from the exported DER, independently of the PEM path.
                public_key = subprocess.run(
                    ["openssl", "x509", "-inform", "DER", "-pubkey", "-noout"],
                    input=base64.b64decode(encoded), check=True,
                    capture_output=True, timeout=10,
                ).stdout
                spki = subprocess.run(
                    ["openssl", "pkey", "-pubin", "-outform", "DER"],
                    input=public_key, check=True, capture_output=True, timeout=10,
                ).stdout
                expected_pin = "sha256/" + base64.b64encode(
                    hashlib.sha256(spki).digest()
                ).decode("ascii")
                self.assertEqual(spki_pin_sha256(self.cert), expected_pin)
                self.assertEqual(self.cert.read_bytes(), pem)

    def test_empty_and_malformed_leaf_fail_with_safe_diagnostic(self) -> None:
        for pem in (b"", b"not a certificate", b"\xff",
                    b"-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n",
                    b"-----BEGIN CERTIFICATE-----\ntruncated"):
            with self.subTest(pem=pem):
                self.cert.write_bytes(pem)
                with self.assertRaisesRegex(ValueError, "leaf certificate.*PEM") as caught:
                    certificate_der_base64(self.cert)
                self.assertNotIn(str(self.directory), str(caught.exception))

    def test_identity_tool_failures_do_not_log_private_details(self) -> None:
        for error in (
            OSError(f"cannot open {self.key}"),
            subprocess.CalledProcessError(
                1, ["openssl", str(self.key)], stderr=b"private diagnostic contents",
            ),
        ):
            with self.subTest(error=type(error).__name__):
                with patch("plugin.relay.server.ensure_tls_identity", side_effect=error):
                    with self.assertLogs("hermes_relay", level="ERROR") as logs:
                        server = RelayServer(self.config())
                self.assertIsNone(server.secure_proxy_candidate)
                diagnostic = "\n".join(logs.output)
                self.assertIn("file access and OpenSSL availability", diagnostic)
                self.assertNotIn(str(self.directory), diagnostic)
                self.assertNotIn("private diagnostic contents", diagnostic)

    async def test_invalid_chain_or_key_is_not_advertised_and_relay_stays_healthy(self) -> None:
        for pem, key in (
            (b"", self.leaf_key),
            (self.intermediate + self.leaf, self.leaf_key),
            (self.chain, (Path(self.fixture_dir.name) / "root.key").read_bytes()),
            (self.leaf + b"-----BEGIN CERTIFICATE-----\ninvalid\n"
             b"-----END CERTIFICATE-----\n", self.leaf_key),
        ):
            with self.subTest(pem_length=len(pem), key_length=len(key)):
                self.cert.write_bytes(pem)
                self.key.write_bytes(key)
                with self.assertLogs("hermes_relay", level="ERROR") as logs:
                    app = create_app(self.config())
                diagnostic = "\n".join(logs.output)
                self.assertIn("leaf certificate first", diagnostic)
                self.assertIn("matching private key", diagnostic)
                self.assertNotIn(str(self.directory), diagnostic)
                self.assertNotIn("BEGIN", diagnostic)
                async with TestClient(TestServer(app)) as client:
                    self.assertIsNone(app["server"].secure_proxy_candidate)
                    self.assertIsNone(app["server"].secure_proxy_runner)
                    response = await client.get("/health")
                    self.assertEqual(response.status, 200)
                    self.assertNotIn("secure_proxy", await response.json())
                self.assertEqual(self.cert.read_bytes(), pem)
                self.assertEqual(self.key.read_bytes(), key)

    async def test_configured_chain_is_served_and_pairing_exports_leaf(self) -> None:
        app = create_app(self.config())
        async with TestClient(TestServer(app)) as client:
            server = app["server"]
            self.assertIsNotNone(server.secure_proxy_runner)
            candidate = server.secure_proxy_candidate
            self.assertIsNotNone(candidate)
            self.assertEqual(
                base64.b64decode(candidate["proxy"]["cert_der"]), self.leaf_der,
            )
            # Trust only the root: success requires the listener to send the intermediate.
            context = ssl.create_default_context(cadata=self.root_cert.decode("ascii"))
            port = server.secure_proxy_runner.addresses[0][1]
            _, writer = await asyncio.open_connection("127.0.0.1", port, ssl=context)
            try:
                self.assertEqual(
                    writer.get_extra_info("ssl_object").getpeercert(binary_form=True),
                    base64.b64decode(candidate["proxy"]["cert_der"]),
                )
            finally:
                writer.close()
                await writer.wait_closed()
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"https://127.0.0.1:{port}/relay/health", ssl=context,
                ) as response:
                    self.assertEqual(response.status, 200)
            response = await client.post("/pairing/mint", json={"ttl_seconds": 3600})
            self.assertEqual(response.status, 200)
            receipt = await response.json()
            payload = json.loads(receipt["qr_payload"])
            invite = urlsplit(receipt["pairing_url"])
            self.assertEqual((invite.scheme, invite.netloc), ("hermes-relay", "pair"))
            encoded = parse_qs(invite.query)["payload"][0]
            self.assertEqual(
                json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))),
                payload,
            )
            proxy = next(item["proxy"] for item in payload["endpoints"]
                         if item["role"] == "plugin_proxy")
            self.assertEqual(proxy, candidate["proxy"])
            from plugin.relay.qr_sign import load_or_create_secret, verify_payload
            self.assertTrue(verify_payload(payload, payload["sig"], load_or_create_secret()))
        self.assertEqual(self.cert.read_bytes(), self.chain)

    async def test_generated_identity_exports_the_served_certificate(self) -> None:
        config = self.config()
        config.secure_proxy_cert = None
        config.secure_proxy_key = None
        app = create_app(config)
        async with TestClient(TestServer(app)):
            server = app["server"]
            cert = Path(config.secure_proxy_cert)
            exported = server.secure_proxy_candidate["proxy"]["cert_der"]
            self.assertEqual(base64.b64decode(exported),
                             ssl.PEM_cert_to_DER_cert(cert.read_text(encoding="ascii")))
            self.assertEqual(server.secure_proxy_candidate["proxy"]["pin_sha256"],
                             spki_pin_sha256(cert))
            context = ssl.create_default_context(cafile=str(cert))
            port = server.secure_proxy_runner.addresses[0][1]
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"https://127.0.0.1:{port}/relay/health", ssl=context,
                ) as response:
                    self.assertEqual(response.status, 200)


class SecureProxyAdvertisementTests(unittest.TestCase):
    def test_pairing_payload_carries_operator_reviewed_pin(self) -> None:
        candidate = advertised_candidate(
            "192.0.2.10", 9443, "sha256/test", "dGVzdC1jZXJ0"
        )
        payload = json.loads(build_pairing_qr_payload(
            host="192.0.2.10", port=8642, key="key", tls=False,
            endpoints=[candidate], sign=False,
        ))
        self.assertEqual(payload["endpoints"][0], candidate)
        self.assertEqual(
            payload["endpoints"][0]["proxy"]["cert_der"], "dGVzdC1jZXJ0"
        )

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
        # Upstream that already honored X-Forwarded-Prefix must not double-prefix.
        self.assertEqual(
            _rewrite_dashboard_location("/dashboard/login", upstream),
            "/dashboard/login",
        )
        self.assertEqual(
            _rewrite_dashboard_location(
                "http://127.0.0.1:9119/dashboard/auth/callback?code=x",
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

    def test_dashboard_html_and_json_auth_paths_are_scoped(self) -> None:
        html = (
            b"<script>fetch('/auth/password-login');"
            b"window.location.assign((data && data.next) || '/');</script>"
        )
        rewritten = _rewrite_dashboard_body("text/html; charset=utf-8", html)
        self.assertIn(b"fetch('/dashboard/auth/password-login')", rewritten)
        self.assertIn(
            b"window.location.assign((data && data.next) || '/dashboard/');",
            rewritten,
        )
        payload = json.dumps({"ok": True, "next": "/"}).encode("utf-8")
        out = _rewrite_dashboard_body("application/json", payload)
        self.assertEqual(json.loads(out.decode("utf-8"))["next"], "/dashboard/")
        already = json.dumps({"ok": True, "next": "/dashboard/sessions"}).encode(
            "utf-8"
        )
        self.assertEqual(
            _rewrite_dashboard_body("application/json", already),
            already,
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
