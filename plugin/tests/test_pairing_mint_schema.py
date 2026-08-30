"""Tests for POST /pairing/mint — the dashboard's QR generation endpoint.

Asserts the ``qr_payload`` shape matches what the Android app's
``QrPairingScanner.kt`` parses: top-level ``host/port/key/tls`` describe
the Hermes **API** server; the nested ``relay`` block carries the WSS
URL + the freshly minted pairing code.

Regression guard for the 2026-04-18 silent-fail: the endpoint used to
put the minted code in top-level ``key`` and emit the relay's own port
at the top level, so phones parsed the relay port as the API server and
found an empty relay block, then bailed during auth.
"""

from __future__ import annotations

import json
import os
import base64
import tempfile
import unittest
from urllib.parse import parse_qs, urlparse
from unittest import mock

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class PairingMintSchemaTests(AioHTTPTestCase):
    async def asyncSetUp(self) -> None:
        self._hermes_home = tempfile.TemporaryDirectory()
        self._env_patch = mock.patch.dict(
            os.environ,
            {
                "HERMES_HOME": self._hermes_home.name,
                "API_SERVER_KEY": "",
            },
        )
        self._env_patch.start()
        await super().asyncSetUp()

    async def asyncTearDown(self) -> None:
        await super().asyncTearDown()
        self._env_patch.stop()
        self._hermes_home.cleanup()

    async def get_application(self) -> web.Application:
        config = RelayConfig(
            host="0.0.0.0",
            port=8767,
            webapi_url="http://10.0.0.42:8642",
        )
        return create_app(config)

    async def _mint(self, body: dict | None = None) -> dict:
        resp = await self.client.post("/pairing/mint", json=body or {})
        self.assertEqual(resp.status, 200, await resp.text())
        return await resp.json()

    async def test_qr_payload_uses_api_server_at_top_level(self) -> None:
        """Top-level host/port must be the API server, not the relay."""
        result = await self._mint()
        qr = json.loads(result["qr_payload"])

        self.assertEqual(qr["port"], 8642, "top-level port must be API, not relay")
        self.assertNotEqual(qr["port"], 8767)
        self.assertFalse(qr["tls"])
        self.assertIn(qr["host"], ("10.0.0.42",))

    async def test_relay_block_carries_url_and_code(self) -> None:
        """The minted code belongs in relay.code — not top-level key."""
        result = await self._mint()
        qr = json.loads(result["qr_payload"])

        self.assertIn("relay", qr, "relay block is required")
        relay = qr["relay"]
        self.assertIn("url", relay, "relay.url is required for WSS connect")
        self.assertIn("code", relay, "relay.code is required — app bails on empty")
        self.assertTrue(relay["url"].startswith("ws://"))
        self.assertEqual(relay["code"], result["code"])
        self.assertEqual(len(relay["code"]), 6)

    async def test_pairing_url_wraps_same_qr_payload(self) -> None:
        result = await self._mint()

        self.assertTrue(result["pairing_url"].startswith("hermes-relay://pair?payload="))
        query = parse_qs(urlparse(result["pairing_url"]).query)
        encoded = query["payload"][0]
        padded = encoded + ("=" * (-len(encoded) % 4))
        decoded = base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8")
        self.assertEqual(json.loads(decoded), json.loads(result["qr_payload"]))

    async def test_top_level_key_is_api_key_not_pair_code(self) -> None:
        """Top-level ``key`` is the API bearer token — not the pair code."""
        result = await self._mint()
        qr = json.loads(result["qr_payload"])

        self.assertNotEqual(
            qr.get("key"),
            result["code"],
            "regression: minted code must not land at top-level key",
        )

    async def test_api_key_defaults_from_server_config(self) -> None:
        """Dashboard-minted QRs must include the chat API bearer by default."""
        with mock.patch(
            "plugin.pair.read_server_config",
            return_value={"host": "10.0.0.42", "port": 8642, "key": "sk-config", "tls": False},
        ):
            result = await self._mint()
        qr = json.loads(result["qr_payload"])

        self.assertEqual(qr["key"], "sk-config")
        self.assertNotEqual(qr["key"], result["code"])

    async def test_api_key_override_lands_at_top_level_key(self) -> None:
        result = await self._mint({"api_key": "sk-test-12345"})
        qr = json.loads(result["qr_payload"])

        self.assertEqual(qr["key"], "sk-test-12345")
        self.assertNotEqual(qr["key"], result["code"])

    async def test_empty_api_key_override_allows_open_access_qr(self) -> None:
        with mock.patch(
            "plugin.pair.read_server_config",
            return_value={"host": "10.0.0.42", "port": 8642, "key": "sk-config", "tls": False},
        ):
            result = await self._mint({"api_key": ""})
        qr = json.loads(result["qr_payload"])

        self.assertEqual(qr["key"], "")

    async def test_explicit_api_disabled_omits_api_fields_and_candidate_records(self) -> None:
        result = await self._mint({
            "api_enabled": False,
            "dashboard_url": "http://10.0.0.42:9119",
            "endpoints": [{
                "role": "lan",
                "priority": 0,
                "api": {"host": "10.0.0.42", "port": 8642, "tls": False},
                "relay": {"url": "ws://10.0.0.42:8767"},
            }],
        })
        qr = json.loads(result["qr_payload"])

        for field in ("host", "port", "key", "tls"):
            self.assertNotIn(field, qr)
            self.assertNotIn(field, result)
        self.assertTrue(qr["endpoints"])
        self.assertTrue(all("api" not in item for item in qr["endpoints"]))

    async def test_api_enabled_must_be_boolean(self) -> None:
        response = await self.client.post(
            "/pairing/mint", json={"api_enabled": "false"}
        )
        self.assertEqual(response.status, 400)
        self.assertIn("boolean", (await response.json())["error"])

    async def test_body_overrides_api_host_port_tls(self) -> None:
        result = await self._mint({
            "host": "relay.example.com",
            "port": 443,
            "tls": True,
        })
        qr = json.loads(result["qr_payload"])

        self.assertEqual(qr["host"], "relay.example.com")
        self.assertEqual(qr["port"], 443)
        self.assertTrue(qr["tls"])

    async def test_public_api_override_rejects_plaintext(self) -> None:
        response = await self.client.post(
            "/pairing/mint",
            json={"host": "api.public.example", "port": 8642, "tls": False},
        )

        self.assertEqual(response.status, 400)
        self.assertIn("TLS", (await response.json())["error"])

    async def test_dashboard_url_flows_into_response_and_qr_payload(self) -> None:
        result = await self._mint({
            "dashboard_url": "https://dash.example.com/hermes/",
        })
        qr = json.loads(result["qr_payload"])

        self.assertEqual(result["dashboard_url"], "https://dash.example.com/hermes")
        self.assertEqual(qr["dashboard_url"], "https://dash.example.com/hermes")
        self.assertNotIn(":8767", json.dumps(qr))
        self.assertEqual(
            qr["relay"]["url"],
            "wss://dash.example.com/hermes/api/plugins/hermes-relay/transport",
        )

    async def test_dashboard_mint_binds_top_level_relay_to_public_origin_not_tailscale_priority(self) -> None:
        endpoints = [
            {
                "role": "tailscale",
                "priority": 0,
                "dashboard": {"url": "https://host.tailnet.ts.net"},
                "relay": {
                    "url": "wss://host.tailnet.ts.net/api/plugins/hermes-relay/transport",
                    "transport_hint": "wss",
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
        ]
        with mock.patch("plugin.pair._tailscale_status", return_value=None):
            result = await self._mint({
                "dashboard_url": "https://public.example",
                "endpoints": endpoints,
            })
        qr = json.loads(result["qr_payload"])

        self.assertEqual([item["role"] for item in qr["endpoints"]], ["tailscale", "public"])
        self.assertEqual([item["priority"] for item in qr["endpoints"]], [0, 1])
        self.assertEqual(
            qr["relay"]["url"],
            "wss://public.example/api/plugins/hermes-relay/transport",
        )
        self.assertEqual(result["relay_url"], qr["relay"]["url"])

    async def test_dashboard_mint_rejects_cross_origin_relay_for_selected_dashboard(self) -> None:
        response = await self.client.post(
            "/pairing/mint",
            json={
                "dashboard_url": "https://public.example",
                "endpoints": [{
                    "role": "public",
                    "priority": 0,
                    "dashboard": {"url": "https://public.example"},
                    "relay": {
                        "url": "wss://other.example/api/plugins/hermes-relay/transport",
                        "transport_hint": "wss",
                    },
                }],
            },
        )

        self.assertEqual(response.status, 400)
        self.assertIn("same-origin Relay ingress", (await response.json())["error"])

    async def test_dashboard_mint_rejects_duplicate_selected_origin_ingresses(self) -> None:
        endpoints = [
            {
                "role": "public",
                "priority": 0,
                "dashboard": {"url": "https://public.example"},
                "relay": {
                    "url": "wss://public.example/api/plugins/hermes-relay/transport",
                    "transport_hint": "wss",
                },
            },
            {
                "role": "public",
                "priority": 1,
                "dashboard": {"url": "https://PUBLIC.EXAMPLE:443/"},
                "relay": {
                    "url": "wss://public.example:443/api/plugins/hermes-relay/transport/",
                    "transport_hint": "wss",
                },
            },
        ]
        response = await self.client.post(
            "/pairing/mint",
            json={
                "dashboard_url": "https://public.example",
                "endpoints": endpoints,
            },
        )

        self.assertEqual(response.status, 400)
        self.assertIn("exactly one", (await response.json())["error"])

    async def test_dashboard_url_camel_alias_is_accepted(self) -> None:
        result = await self._mint({
            "dashboardUrl": "https://dash.example.com",
        })
        qr = json.loads(result["qr_payload"])

        self.assertEqual(result["dashboard_url"], "https://dash.example.com")
        self.assertEqual(qr["dashboard_url"], "https://dash.example.com")

    async def test_dashboard_url_rejects_credentials_and_fragments(self) -> None:
        for dashboard_url in (
            "https://user:secret@dash.example.com",
            "https://dash.example.com/#token",
        ):
            with self.subTest(dashboard_url=dashboard_url):
                response = await self.client.post(
                    "/pairing/mint", json={"dashboard_url": dashboard_url}
                )
                self.assertEqual(response.status, 400)

    async def test_public_dashboard_url_rejects_plaintext_but_private_http_remains_valid(self) -> None:
        rejected = await self.client.post(
            "/pairing/mint", json={"dashboard_url": "http://public.example"}
        )
        self.assertEqual(rejected.status, 400)
        self.assertIn("https", (await rejected.json())["error"])

        accepted = await self.client.post(
            "/pairing/mint", json={"dashboard_url": "http://192.168.1.20:9119"}
        )
        self.assertEqual(accepted.status, 200, await accepted.text())

    async def test_public_endpoint_candidates_reject_every_plaintext_surface(self) -> None:
        candidates = (
            {
                "role": "public",
                "priority": 0,
                "dashboard": {"url": "http://public.example"},
                "relay": {"url": "wss://public.example/relay"},
            },
            {
                "role": "public",
                "priority": 0,
                "dashboard": {"url": "https://public.example"},
                "relay": {"url": "ws://public.example:8767"},
            },
            {
                "role": "public",
                "priority": 0,
                "api": {"host": "public.example", "port": 8642, "tls": False},
                "relay": {"url": "wss://public.example/relay"},
            },
            {
                "role": "public_legacy",
                "priority": 0,
                "legacy": True,
                "relay": {"url": "ws://public.example:8767"},
            },
        )
        for candidate in candidates:
            with self.subTest(candidate=candidate):
                response = await self.client.post(
                    "/pairing/mint", json={"endpoints": [candidate]}
                )
                self.assertEqual(response.status, 400, await response.text())

    async def test_legacy_direct_relay_requires_explicit_boolean_opt_in(self) -> None:
        invalid = await self.client.post(
            "/pairing/mint", json={"legacy_direct_relay": "true"}
        )
        self.assertEqual(invalid.status, 400)

        result = await self._mint({
            "dashboard_url": "https://dash.example.com",
            "legacy_direct_relay": True,
        })
        qr = json.loads(result["qr_payload"])
        self.assertEqual(qr["relay"]["url"], result["legacy_direct_relay_url"])
        self.assertTrue(qr["relay"]["url"].endswith(":8767"))
        self.assertTrue(qr["endpoints"][-1]["legacy"])

    async def test_ttl_and_transport_hint_flow_through_to_relay_block(self) -> None:
        result = await self._mint({
            "ttl_seconds": 3600.0,
            "grants": {"terminal": 1800.0},
            "transport_hint": "ws",
        })
        qr = json.loads(result["qr_payload"])

        relay = qr["relay"]
        self.assertEqual(relay["ttl_seconds"], 3600)
        self.assertIs(type(relay["ttl_seconds"]), int)
        self.assertEqual(relay["grants"]["terminal"], 1800)
        self.assertIs(type(relay["grants"]["terminal"]), int)
        self.assertEqual(relay["transport_hint"], "ws")

    async def test_fractional_pairing_durations_are_rejected(self) -> None:
        response = await self.client.post("/pairing/mint", json={"ttl_seconds": 1.5})
        self.assertEqual(response.status, 400)
        self.assertIn("whole number", (await response.json())["error"])

    async def test_hermes_version_is_v2_when_metadata_present(self) -> None:
        result = await self._mint({"ttl_seconds": 3600})
        qr = json.loads(result["qr_payload"])
        self.assertEqual(qr["hermes"], 2)

    async def test_mint_without_endpoints_stays_v2_shape(self) -> None:
        """Regression: mint bodies without ``endpoints`` must not bump to v3.

        Preserves backward compat so phones parsing v2 (pre-ADR 24)
        don't see an unexpected version bump whenever the dashboard
        hits /pairing/mint without explicitly opting into multi-endpoint.
        """
        result = await self._mint({"ttl_seconds": 3600})
        qr = json.loads(result["qr_payload"])
        self.assertEqual(qr["hermes"], 2)
        self.assertNotIn("endpoints", qr)
        self.assertNotIn("endpoints", result)

    async def test_mint_normalizes_standard_routes_to_dashboard_ingress(self) -> None:
        """Server refuses to sign implicit direct 8767 standard routes."""
        endpoints = [
            {
                "role": "lan",
                "priority": 0,
                "api": {"host": "192.168.1.100", "port": 8642, "tls": False},
                "relay": {
                    "url": "ws://192.168.1.100:8767",
                    "transport_hint": "ws",
                },
            },
            {
                "role": "tailscale",
                "priority": 1,
                "api": {"host": "hermes.tail-scale.ts.net", "port": 8642, "tls": True},
                "relay": {
                    "url": "wss://hermes.tail-scale.ts.net:8767",
                    "transport_hint": "wss",
                },
            },
        ]
        with mock.patch("plugin.pair._tailscale_status", return_value=None):
            result = await self._mint({"endpoints": endpoints})
        qr = json.loads(result["qr_payload"])

        self.assertEqual(qr["hermes"], 3, "endpoints present → version 3")
        self.assertIn("endpoints", qr)
        self.assertEqual(qr["endpoints"][0]["dashboard"]["url"], "http://192.168.1.100:9119")
        self.assertEqual(
            qr["endpoints"][0]["relay"]["url"],
            "ws://192.168.1.100:9119/api/plugins/hermes-relay/transport",
        )
        self.assertNotIn(":8767", json.dumps(qr["endpoints"]))
        self.assertEqual(result.get("endpoints"), qr["endpoints"])

    async def test_mint_normalizes_tailscale_magic_dns_when_serve_off(self) -> None:
        """Stale dashboard processes cannot sign a broken .ts.net HTTPS route."""
        endpoints = [
            {
                "role": "lan",
                "priority": 0,
                "api": {"host": "192.168.1.100", "port": 8642, "tls": False},
                "relay": {
                    "url": "ws://192.168.1.100:8767",
                    "transport_hint": "ws",
                },
            },
            {
                "role": "tailscale",
                "priority": 1,
                "api": {
                    "host": "hermes-host.tailnet.ts.net",
                    "port": 8642,
                    "tls": True,
                },
                "relay": {
                    "url": "wss://hermes-host.tailnet.ts.net:8767",
                    "transport_hint": "wss",
                },
            },
        ]

        with mock.patch(
            "plugin.pair._tailscale_status",
            return_value={
                "available": True,
                "hostname": "hermes-host.tailnet.ts.net",
                "tailscale_ip": "100.64.0.1",
                "serve_ports": [],
            },
        ):
            result = await self._mint({"endpoints": endpoints})
        qr = json.loads(result["qr_payload"])
        tailscale = qr["endpoints"][1]

        self.assertEqual(qr["hermes"], 3)
        self.assertEqual(tailscale["api"]["host"], "100.64.0.1")
        self.assertFalse(tailscale["api"]["tls"])
        self.assertEqual(tailscale["dashboard"]["url"], "http://100.64.0.1:9119")
        self.assertEqual(
            tailscale["relay"]["url"],
            "ws://100.64.0.1:9119/api/plugins/hermes-relay/transport",
        )
        self.assertEqual(tailscale["relay"]["transport_hint"], "ws")
        self.assertEqual(result.get("endpoints"), qr["endpoints"])

    async def test_mint_with_endpoints_signature_verifies(self) -> None:
        """ADR 24: the HMAC over a v3 payload must verify unchanged."""
        from plugin.relay.qr_sign import load_or_create_secret, verify_payload

        endpoints = [
            {
                "role": "lan",
                "priority": 0,
                "api": {"host": "192.168.1.100", "port": 8642, "tls": False},
                "relay": {
                    "url": "ws://192.168.1.100:8767",
                    "transport_hint": "ws",
                },
            },
            {
                "role": "public",
                "priority": 1,
                "api": {"host": "hermes.example.com", "port": 443, "tls": True},
                "relay": {
                    "url": "wss://hermes.example.com/relay",
                    "transport_hint": "wss",
                },
            },
        ]
        result = await self._mint({"endpoints": endpoints})
        qr = json.loads(result["qr_payload"])

        self.assertIn("sig", qr)
        secret = load_or_create_secret()
        self.assertTrue(
            verify_payload(qr, qr["sig"], secret),
            "v3 payload with endpoints must verify against host QR secret",
        )

    async def test_mint_rejects_non_array_endpoints(self) -> None:
        resp = await self.client.post(
            "/pairing/mint", json={"endpoints": {"role": "lan"}}
        )
        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertIn("endpoints", body["error"])

    async def test_unresolvable_api_host_returns_400(self) -> None:
        """If webapi_url is 0.0.0.0 and no override, we must 400."""
        # Rebuild the app with a broken default so the error branch fires.
        config = RelayConfig(
            host="0.0.0.0",
            port=8767,
            webapi_url="http://0.0.0.0:8642",
        )
        app = create_app(config)
        from aiohttp.test_utils import TestClient, TestServer

        async with TestClient(TestServer(app)) as client:
            resp = await client.post("/pairing/mint", json={})
            self.assertEqual(resp.status, 400)
            body = await resp.json()
            self.assertIn("host", body["error"].lower())


class BuildEndpointCandidatesPreferTests(unittest.TestCase):
    """Direct tests for the `prefer` reorder path (ADR 24, 2026-04-19)."""

    def _build(
        self,
        mode: str = "auto",
        prefer: str | None = None,
        public_url: str | None = "https://example.com",
        tailscale_status: dict | None = None,
        legacy_direct_relay: bool = False,
    ) -> list[dict]:
        from plugin.pair import build_endpoint_candidates

        # Inject a synthetic Tailscale status so the test doesn't depend on
        # the host machine's actual Tailscale state.
        from plugin.relay import tailscale as ts_mod
        import unittest.mock as mock

        fake_status = tailscale_status or {
            "available": True,
            "hostname": "test.tail-xyz.ts.net",
            "tailscale_ip": "100.64.0.1",
            "serve_ports": [],
        }
        with mock.patch.object(ts_mod, "status", return_value=fake_status):
            return build_endpoint_candidates(
                mode=mode,
                api_host="10.0.0.42",
                api_port=8642,
                api_tls=False,
                relay_host="10.0.0.42",
                relay_port=8767,
                relay_tls=False,
                public_url=public_url,
                prefer=prefer,
                legacy_direct_relay=legacy_direct_relay,
            )

    def test_prefer_none_keeps_natural_order(self) -> None:
        endpoints = self._build(prefer=None)
        roles = [c["role"] for c in endpoints]
        self.assertEqual(roles, ["tailscale", "public", "lan"])
        self.assertEqual([c["priority"] for c in endpoints], [0, 1, 2])

    def test_prefer_tailscale_promotes_to_priority_0(self) -> None:
        endpoints = self._build(prefer="tailscale")
        roles = [c["role"] for c in endpoints]
        self.assertEqual(roles, ["tailscale", "public", "lan"])
        self.assertEqual([c["priority"] for c in endpoints], [0, 1, 2])

    def test_prefer_public_promotes_a_secure_alternative(self) -> None:
        endpoints = self._build(prefer="public")
        self.assertEqual([c["role"] for c in endpoints], ["public", "tailscale", "lan"])
        self.assertEqual([c["priority"] for c in endpoints], [0, 1, 2])

    def test_prefer_is_case_insensitive(self) -> None:
        endpoints = self._build(prefer="TAILSCALE")
        self.assertEqual(endpoints[0]["role"], "tailscale")  # role preserved verbatim

    def test_prefer_unknown_role_is_soft_warn(self) -> None:
        # Unknown role → unchanged natural order, no exception.
        endpoints = self._build(prefer="wireguard-eu")
        self.assertEqual([c["role"] for c in endpoints], ["tailscale", "public", "lan"])
        self.assertEqual([c["priority"] for c in endpoints], [0, 1, 2])

    def test_prefer_role_already_at_zero_is_noop(self) -> None:
        endpoints = self._build(prefer="tailscale")
        self.assertEqual([c["role"] for c in endpoints], ["tailscale", "public", "lan"])
        self.assertEqual([c["priority"] for c in endpoints], [0, 1, 2])

    def test_public_dashboard_origin_never_infers_direct_8767(self) -> None:
        endpoints = self._build(
            mode="public",
            public_url="https://public.example/base",
            tailscale_status=None,
        )

        self.assertEqual(endpoints, [{
            "role": "public",
            "priority": 0,
            "recommended": False,
            "dashboard": {"url": "https://public.example/base"},
            "relay": {
                "url": "wss://public.example/base/api/plugins/hermes-relay/transport",
                "transport_hint": "wss",
            },
        }])
        self.assertNotIn(":8767", json.dumps(endpoints))

    def test_public_mode_rejects_plaintext_even_for_private_or_tailnet_hosts(self) -> None:
        for public_url in (
            "http://public.example",
            "http://192.168.1.20:9119",
            "http://100.64.0.20:9119",
        ):
            with self.subTest(public_url=public_url):
                with self.assertRaisesRegex(ValueError, "https"):
                    self._build(mode="public", public_url=public_url)

    def test_explicit_public_relay_path_requires_legacy_opt_in(self) -> None:
        with self.assertRaisesRegex(ValueError, "legacy-direct-relay"):
            self._build(mode="public", public_url="https://public.example/relay")

    def test_explicit_legacy_mode_keeps_public_and_lan_direct_last(self) -> None:
        endpoints = self._build(legacy_direct_relay=True)

        self.assertEqual(
            [candidate["role"] for candidate in endpoints],
            ["tailscale", "public", "lan", "public_legacy", "legacy_direct"],
        )
        self.assertEqual(endpoints[-2]["relay"]["url"], "wss://example.com:8767")
        self.assertEqual(endpoints[-1]["relay"]["url"], "ws://10.0.0.42:8767")
        self.assertTrue(all(candidate.get("recommended") is False for candidate in endpoints[-2:]))

    def test_tailscale_uses_raw_ip_for_direct_tailnet_ports(self) -> None:
        endpoints = self._build(mode="tailscale", public_url=None)
        tailscale = next(c for c in endpoints if c["role"] == "tailscale")

        self.assertEqual(tailscale["api"]["host"], "100.64.0.1")
        self.assertFalse(tailscale["api"]["tls"])
        self.assertEqual(tailscale["dashboard"]["url"], "http://100.64.0.1:9119")
        self.assertEqual(
            tailscale["relay"]["url"],
            "ws://100.64.0.1:9119/api/plugins/hermes-relay/transport",
        )
        self.assertEqual(tailscale["relay"]["transport_hint"], "ws")

    def test_tailscale_uses_magic_dns_tls_when_dashboard_serve_is_active(self) -> None:
        endpoints = self._build(
            mode="tailscale",
            public_url=None,
            tailscale_status={
                "available": True,
                "hostname": "test.tail-xyz.ts.net",
                "tailscale_ip": "100.64.0.1",
                "serve_ports": [9119, 8642, 8767],
            },
        )
        tailscale = next(c for c in endpoints if c["role"] == "tailscale")

        self.assertEqual(tailscale["api"]["host"], "test.tail-xyz.ts.net")
        self.assertTrue(tailscale["api"]["tls"])
        self.assertEqual(
            tailscale["dashboard"]["url"],
            "https://test.tail-xyz.ts.net:9119",
        )
        self.assertEqual(
            tailscale["relay"]["url"],
            "wss://test.tail-xyz.ts.net:9119/api/plugins/hermes-relay/transport",
        )
        self.assertEqual(tailscale["relay"]["transport_hint"], "wss")

    def test_tailscale_uses_classified_dashboard_listener_not_proxy_target(self) -> None:
        endpoints = self._build(
            mode="tailscale",
            public_url=None,
            tailscale_status={
                "available": True,
                "hostname": "test.tail-xyz.ts.net",
                "tailscale_ip": "100.64.0.1",
                "serve_ports": [443, 8642, 8767],
                "serve_services": {
                    "dashboard": {
                        "active": True,
                        "listen_ports": [443],
                        "serve_routes": [{"proxy_target": "http://127.0.0.1:9119"}],
                    },
                    "api": {"active": True, "listen_ports": [8642]},
                    "legacy_relay": {"active": True, "listen_ports": [8767]},
                },
            },
        )
        tailscale = endpoints[0]

        self.assertEqual(tailscale["dashboard"]["url"], "https://test.tail-xyz.ts.net")
        self.assertEqual(
            tailscale["relay"]["url"],
            "wss://test.tail-xyz.ts.net/api/plugins/hermes-relay/transport",
        )
        self.assertEqual(tailscale["api"], {
            "host": "test.tail-xyz.ts.net",
            "port": 8642,
            "tls": True,
        })

    def test_tailscale_prefers_recommended_443_during_mixed_listener_migration(self) -> None:
        endpoints = self._build(
            mode="tailscale",
            public_url=None,
            tailscale_status={
                "available": True,
                "hostname": "test.tail-xyz.ts.net",
                "tailscale_ip": "100.64.0.1",
                "serve_ports": [443, 9119],
                "serve_services": {
                    "dashboard": {
                        "active": True,
                        "listen_ports": [443, 9119],
                        "serve_routes": [{"proxy_target": "http://127.0.0.1:9119"}],
                    },
                },
            },
        )

        tailscale = endpoints[0]
        self.assertEqual(tailscale["dashboard"]["url"], "https://test.tail-xyz.ts.net")
        self.assertEqual(
            tailscale["relay"]["url"],
            "wss://test.tail-xyz.ts.net/api/plugins/hermes-relay/transport",
        )

    def test_tailscale_retains_9119_when_it_is_the_only_dashboard_listener(self) -> None:
        endpoints = self._build(
            mode="tailscale",
            public_url=None,
            tailscale_status={
                "available": True,
                "hostname": "test.tail-xyz.ts.net",
                "tailscale_ip": "100.64.0.1",
                "serve_ports": [9119],
                "serve_services": {
                    "dashboard": {
                        "active": True,
                        "listen_ports": [9119],
                        "serve_routes": [{"proxy_target": "http://127.0.0.1:9119"}],
                    },
                },
            },
        )

        self.assertEqual(
            endpoints[0]["dashboard"]["url"],
            "https://test.tail-xyz.ts.net:9119",
        )

    def test_funnel_auto_detection_prefers_recommended_443(self) -> None:
        from plugin.pair import build_endpoint_candidates
        from plugin.relay import tailscale as tailscale_helper

        with mock.patch("plugin.pair._tailscale_status", return_value=None), mock.patch.object(
            tailscale_helper,
            "funnel_url",
            return_value="https://public.tail-xyz.ts.net/",
        ) as funnel_url:
            endpoints = build_endpoint_candidates(
                mode="auto",
                api_host="10.0.0.42",
                api_port=8642,
                api_tls=False,
                relay_host="10.0.0.42",
                relay_port=8767,
                relay_tls=False,
            )

        funnel_url.assert_called_once_with(port=443)
        public = next(candidate for candidate in endpoints if candidate["role"] == "public")
        self.assertEqual(public["dashboard"]["url"], "https://public.tail-xyz.ts.net")

    def test_funnel_auto_detection_falls_back_to_old_9119_listener(self) -> None:
        from plugin.pair import build_endpoint_candidates
        from plugin.relay import tailscale as tailscale_helper

        def funnel_url(port: int) -> str | None:
            return "https://legacy.tail-xyz.ts.net/" if port == 9119 else None

        with mock.patch("plugin.pair._tailscale_status", return_value=None), mock.patch.object(
            tailscale_helper,
            "funnel_url",
            side_effect=funnel_url,
        ) as funnel_probe:
            endpoints = build_endpoint_candidates(
                mode="auto",
                api_host="10.0.0.42",
                api_port=8642,
                api_tls=False,
                relay_host="10.0.0.42",
                relay_port=8767,
                relay_tls=False,
            )

        self.assertEqual(
            [call.kwargs["port"] for call in funnel_probe.call_args_list],
            [443, 9119],
        )
        public = next(candidate for candidate in endpoints if candidate["role"] == "public")
        self.assertEqual(public["dashboard"]["url"], "https://legacy.tail-xyz.ts.net")

    def test_tailscale_uses_raw_ip_when_only_legacy_relay_serve_is_active(self) -> None:
        endpoints = self._build(
            mode="tailscale",
            public_url=None,
            tailscale_status={
                "available": True,
                "hostname": "test.tail-xyz.ts.net",
                "tailscale_ip": "100.64.0.1",
                "serve_ports": [8767],
            },
        )
        tailscale = next(c for c in endpoints if c["role"] == "tailscale")

        self.assertEqual(tailscale["api"]["host"], "100.64.0.1")
        self.assertFalse(tailscale["api"]["tls"])
        self.assertEqual(tailscale["dashboard"]["url"], "http://100.64.0.1:9119")
        self.assertEqual(
            tailscale["relay"]["url"],
            "ws://100.64.0.1:9119/api/plugins/hermes-relay/transport",
        )
        self.assertEqual(tailscale["relay"]["transport_hint"], "ws")


if __name__ == "__main__":
    unittest.main()
