"""Security regressions for Relay session-policy authorization."""

from __future__ import annotations

import math
import time

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class SessionPolicyAuthorizationTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        return create_app(RelayConfig(profile_discovery_enabled=False))

    def _create_session(self, name: str, *, ttl_seconds: int = 600):
        return self.app["server"].sessions.create_session(
            name,
            f"{name}-id",
            ttl_seconds=ttl_seconds,
            client_surface="phone",
        )

    @staticmethod
    def _headers(token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    async def test_bounded_bearer_cannot_upgrade_its_policy(self) -> None:
        session = self._create_session("bounded")
        original_expiry = session.expires_at
        original_grants = dict(session.grants)

        response = await self.client.patch(
            f"/sessions/{session.token[:8]}",
            headers=self._headers(session.token),
            json={
                "ttl_seconds": 0,
                "grants": {
                    "terminal": 0,
                    "bridge": 0,
                    "tui": 0,
                    "voice:tts": 0,
                    "attacker:custom": 0,
                },
            },
        )

        self.assertEqual(response.status, 403, await response.text())
        self.assertEqual(session.expires_at, original_expiry)
        self.assertEqual(session.grants, original_grants)
        self.assertFalse(math.isinf(session.expires_at))

    async def test_bearer_cannot_modify_another_session(self) -> None:
        caller = self._create_session("caller")
        target = self._create_session("target")
        original_expiry = target.expires_at

        response = await self.client.patch(
            f"/sessions/{target.token[:8]}",
            headers=self._headers(caller.token),
            json={"ttl_seconds": 60},
        )

        self.assertEqual(response.status, 403, await response.text())
        self.assertEqual(target.expires_at, original_expiry)

    async def test_bearer_cannot_add_or_lengthen_grants(self) -> None:
        session = self._create_session("bounded")

        add_response = await self.client.patch(
            f"/sessions/{session.token[:8]}",
            headers=self._headers(session.token),
            json={"grants": {"attacker:custom": 30}},
        )
        extend_response = await self.client.patch(
            f"/sessions/{session.token[:8]}",
            headers=self._headers(session.token),
            json={"grants": {"terminal": 601}},
        )

        self.assertEqual(add_response.status, 400, await add_response.text())
        self.assertEqual(extend_response.status, 403, await extend_response.text())
        self.assertNotIn("attacker:custom", session.grants)

    async def test_nan_grant_is_rejected(self) -> None:
        session = self._create_session("bounded")

        response = await self.client.patch(
            f"/sessions/{session.token[:8]}",
            headers=self._headers(session.token),
            json={"grants": {"terminal": float("nan")}},
        )

        self.assertEqual(response.status, 400, await response.text())
        self.assertFalse(math.isnan(session.grants["terminal"]))

    async def test_self_service_can_only_reduce_policy(self) -> None:
        session = self._create_session("bounded")
        original_expiry = session.expires_at
        original_bridge_expiry = session.grants["bridge"]

        response = await self.client.patch(
            f"/sessions/{session.token[:8]}",
            headers=self._headers(session.token),
            json={"ttl_seconds": 300, "grants": {"terminal": 60}},
        )
        body = await response.json()

        self.assertEqual(response.status, 200, body)
        self.assertLess(session.expires_at, original_expiry)
        self.assertLessEqual(session.expires_at, time.time() + 301)
        self.assertLessEqual(session.grants["terminal"], time.time() + 61)
        self.assertLessEqual(session.grants["bridge"], original_bridge_expiry)
        self.assertLessEqual(session.grants["bridge"], session.expires_at)

    async def test_grant_only_reduction_preserves_omitted_ceilings(self) -> None:
        session = self._create_session("bounded")
        original_expiry = session.expires_at
        original_grants = dict(session.grants)

        response = await self.client.patch(
            f"/sessions/{session.token[:8]}",
            headers=self._headers(session.token),
            json={"grants": {"terminal": 60}},
        )

        self.assertEqual(response.status, 200, await response.text())
        self.assertEqual(session.expires_at, original_expiry)
        self.assertLess(session.grants["terminal"], original_grants["terminal"])
        for channel, expiry in original_grants.items():
            if channel != "terminal":
                self.assertEqual(session.grants[channel], expiry)


if __name__ == "__main__":
    import unittest

    unittest.main()
