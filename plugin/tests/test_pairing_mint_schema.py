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
import unittest

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class PairingMintSchemaTests(AioHTTPTestCase):
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

    async def test_top_level_key_is_api_key_not_pair_code(self) -> None:
        """Top-level ``key`` is the API bearer token — not the pair code."""
        result = await self._mint()
        qr = json.loads(result["qr_payload"])

        self.assertNotEqual(
            qr.get("key"),
            result["code"],
            "regression: minted code must not land at top-level key",
        )

    async def test_api_key_override_lands_at_top_level_key(self) -> None:
        result = await self._mint({"api_key": "sk-test-12345"})
        qr = json.loads(result["qr_payload"])

        self.assertEqual(qr["key"], "sk-test-12345")
        self.assertNotEqual(qr["key"], result["code"])

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

    async def test_ttl_and_transport_hint_flow_through_to_relay_block(self) -> None:
        result = await self._mint({
            "ttl_seconds": 3600,
            "transport_hint": "ws",
        })
        qr = json.loads(result["qr_payload"])

        relay = qr["relay"]
        self.assertEqual(relay["ttl_seconds"], 3600)
        self.assertEqual(relay["transport_hint"], "ws")

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

    async def test_mint_with_endpoints_round_trips_verbatim(self) -> None:
        """ADR 24: mint echoes ``endpoints`` array byte-for-byte.

        The server must not reorder, normalize, or drop any entries —
        the phone needs list order preserved for strict priority
        semantics, and role strings must round-trip for the HMAC to
        verify.
        """
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
        result = await self._mint({"endpoints": endpoints})
        qr = json.loads(result["qr_payload"])

        self.assertEqual(qr["hermes"], 3, "endpoints present → version 3")
        self.assertIn("endpoints", qr)
        self.assertEqual(qr["endpoints"], endpoints)
        # Mint body also mirrors it for the dashboard round-trip.
        self.assertEqual(result.get("endpoints"), endpoints)

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


if __name__ == "__main__":
    unittest.main()
