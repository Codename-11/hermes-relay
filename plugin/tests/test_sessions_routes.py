"""Tests for GET /sessions and DELETE /sessions/{token_prefix}."""

from __future__ import annotations

import unittest

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import create_app


class SessionsRoutesTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        config = RelayConfig()
        return create_app(config)

    def _server(self):
        return self.app["server"]

    async def _mint(self, name: str, **kwargs) -> str:
        session = self._server().sessions.create_session(name, name + "-id", **kwargs)
        return session.token

    # ── GET /sessions ────────────────────────────────────────────────────

    async def test_list_requires_bearer(self) -> None:
        resp = await self.client.get("/sessions")
        self.assertEqual(resp.status, 401)

    async def test_list_rejects_invalid_bearer(self) -> None:
        resp = await self.client.get(
            "/sessions", headers={"Authorization": "Bearer nope"}
        )
        self.assertEqual(resp.status, 401)

    async def test_list_returns_all_sessions(self) -> None:
        token_a = await self._mint("dev-a")
        token_b = await self._mint("dev-b", ttl_seconds=0)  # never-expire

        resp = await self.client.get(
            "/sessions", headers={"Authorization": f"Bearer {token_a}"}
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        sessions = body["sessions"]
        self.assertEqual(len(sessions), 2)

        # No full tokens leaked
        for entry in sessions:
            self.assertNotIn("token", entry)
            self.assertIn("token_prefix", entry)
            self.assertEqual(len(entry["token_prefix"]), 8)

        # Find the "never" session — expires_at must serialize as None
        by_name = {s["device_name"]: s for s in sessions}
        self.assertIsNone(by_name["dev-b"]["expires_at"])
        # And its grants should all be null (never)
        for channel in ("chat", "terminal", "bridge"):
            self.assertIsNone(by_name["dev-b"]["grants"][channel])

        # dev-a (finite) should have numeric expires_at
        self.assertIsInstance(by_name["dev-a"]["expires_at"], (int, float))

        # is_current must be true for the caller's session only
        self.assertTrue(by_name["dev-a"]["is_current"])
        self.assertFalse(by_name["dev-b"]["is_current"])

    async def test_list_has_transport_hint(self) -> None:
        token = await self._mint("dev-a", transport_hint="wss")
        resp = await self.client.get(
            "/sessions", headers={"Authorization": f"Bearer {token}"}
        )
        body = await resp.json()
        self.assertEqual(body["sessions"][0]["transport_hint"], "wss")

    # ── DELETE /sessions/{prefix} ────────────────────────────────────────

    async def test_revoke_requires_bearer(self) -> None:
        resp = await self.client.delete("/sessions/abcd1234")
        self.assertEqual(resp.status, 401)

    async def test_revoke_404_on_no_match(self) -> None:
        token = await self._mint("dev-a")
        resp = await self.client.delete(
            "/sessions/zzzzzzzz",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(resp.status, 404)

    async def test_revoke_200_on_unique_match(self) -> None:
        token_a = await self._mint("dev-a")
        token_b = await self._mint("dev-b")

        resp = await self.client.delete(
            f"/sessions/{token_b[:8]}",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["ok"])
        self.assertEqual(body["revoked"], token_b[:8])
        self.assertFalse(body["revoked_self"])

        # token_b should no longer exist
        self.assertIsNone(self._server().sessions.get_session(token_b))
        # token_a still valid
        self.assertIsNotNone(self._server().sessions.get_session(token_a))

    async def test_revoke_409_on_multiple_matches(self) -> None:
        """Forge two sessions sharing the same 1-char prefix so the
        ambiguous-match branch fires."""
        token_a = await self._mint("dev-a")
        # Insert a hand-crafted second session with a token sharing the
        # first character of token_a so prefix=token_a[:1] matches both.
        from plugin.relay.auth import Session

        import time as _time

        forged = Session(
            token=token_a[:1] + "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",
            device_name="forged",
            device_id="forged-id",
            created_at=_time.time(),
            last_seen=_time.time(),
            expires_at=_time.time() + 86400,
        )
        self._server().sessions._sessions[forged.token] = forged

        resp = await self.client.delete(
            f"/sessions/{token_a[:1]}",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        self.assertEqual(resp.status, 409)

    async def test_revoke_self(self) -> None:
        """A caller may revoke their own session."""
        token_a = await self._mint("dev-a")
        resp = await self.client.delete(
            f"/sessions/{token_a[:8]}",
            headers={"Authorization": f"Bearer {token_a}"},
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["revoked_self"])
        # And it's gone
        self.assertIsNone(self._server().sessions.get_session(token_a))


if __name__ == "__main__":
    unittest.main()
