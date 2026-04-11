"""Tests for RateLimiter.clear_all_blocks + /pairing/register integration."""

from __future__ import annotations

import json
import unittest

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.auth import RateLimiter
from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class RateLimiterClearTests(unittest.TestCase):
    def test_clear_removes_blocks_and_failures(self) -> None:
        rl = RateLimiter(max_attempts=3, window_seconds=60, block_seconds=300)

        # Trip the limiter for one IP
        for _ in range(3):
            rl.record_failure("10.0.0.1")
        self.assertTrue(rl.is_blocked("10.0.0.1"))

        # Partial failure on another IP
        rl.record_failure("10.0.0.2")
        self.assertFalse(rl.is_blocked("10.0.0.2"))
        self.assertIn("10.0.0.2", rl._failures)

        rl.clear_all_blocks()

        self.assertFalse(rl.is_blocked("10.0.0.1"))
        self.assertNotIn("10.0.0.2", rl._failures)
        self.assertEqual(rl._blocked, {})
        self.assertEqual(rl._failures, {})

    def test_clear_is_idempotent_on_empty(self) -> None:
        rl = RateLimiter()
        rl.clear_all_blocks()
        rl.clear_all_blocks()
        self.assertEqual(rl._blocked, {})
        self.assertEqual(rl._failures, {})


class PairingRegisterClearsRateLimitTests(AioHTTPTestCase):
    """Successful /pairing/register must wipe the rate-limiter state.

    This reproduces the scenario Bailey hit after a relay restart: the
    phone rapidly trips 5 auth failures against a stale token, gets
    blocked, then the operator re-runs `hermes pair` — that successful
    pairing must reset the block so the phone can reconnect immediately.
    """

    async def get_application(self) -> web.Application:
        config = RelayConfig()
        return create_app(config)

    async def test_register_clears_blocks(self) -> None:
        server = self.app["server"]

        # Pre-populate rate limiter with a block for a distinct IP
        for _ in range(10):
            server.rate_limiter.record_failure("192.168.1.42")
        self.assertTrue(server.rate_limiter.is_blocked("192.168.1.42"))

        # Call /pairing/register (test client appears as 127.0.0.1)
        resp = await self.client.post(
            "/pairing/register",
            json={"code": "ABC123"},
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["ok"])

        # Block for unrelated IP should be gone
        self.assertFalse(server.rate_limiter.is_blocked("192.168.1.42"))
        self.assertEqual(server.rate_limiter._blocked, {})
        self.assertEqual(server.rate_limiter._failures, {})

    async def test_register_accepts_ttl_and_grants(self) -> None:
        resp = await self.client.post(
            "/pairing/register",
            json={
                "code": "ABC456",
                "ttl_seconds": 86400,
                "grants": {"terminal": 3600, "bridge": 1800},
                "transport_hint": "ws",
            },
        )
        self.assertEqual(resp.status, 200)

        # Metadata should flow through to consume_code
        server = self.app["server"]
        meta = server.pairing.consume_code("ABC456")
        self.assertIsNotNone(meta)
        assert meta is not None
        self.assertEqual(meta.ttl_seconds, 86400)
        self.assertEqual(meta.grants, {"terminal": 3600, "bridge": 1800})
        self.assertEqual(meta.transport_hint, "ws")

    async def test_register_rejects_invalid_ttl(self) -> None:
        resp = await self.client.post(
            "/pairing/register",
            json={"code": "ABC789", "ttl_seconds": -5},
        )
        self.assertEqual(resp.status, 400)

    async def test_register_rejects_bad_transport_hint(self) -> None:
        resp = await self.client.post(
            "/pairing/register",
            json={"code": "ABCDEF", "transport_hint": "bogus"},
        )
        self.assertEqual(resp.status, 400)


class PairingApproveTests(AioHTTPTestCase):
    """POST /pairing/approve — Phase 3 bidirectional pairing stub."""

    async def get_application(self) -> web.Application:
        config = RelayConfig()
        return create_app(config)

    async def test_approve_registers_code(self) -> None:
        resp = await self.client.post(
            "/pairing/approve",
            json={"code": "PHON01", "ttl_seconds": 0},
        )
        self.assertEqual(resp.status, 200)
        server = self.app["server"]
        meta = server.pairing.consume_code("PHON01")
        self.assertIsNotNone(meta)
        assert meta is not None
        self.assertEqual(meta.ttl_seconds, 0)

    async def test_approve_rejects_non_loopback(self) -> None:
        """Loopback gating mirrors /pairing/register."""
        # Forge a non-loopback peer via handler invocation, matching the
        # pattern in test_relay_media_routes.
        from plugin.relay.server import handle_pairing_approve

        class _FakeRequest:
            def __init__(self, app) -> None:
                self.remote = "10.1.2.3"
                self.app = app

            async def json(self) -> dict:  # pragma: no cover — never reached
                return {"code": "PHON01"}

        with self.assertRaises(web.HTTPForbidden):
            await handle_pairing_approve(_FakeRequest(self.app))  # type: ignore[arg-type]


if __name__ == "__main__":
    unittest.main()
