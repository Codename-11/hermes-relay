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
        # AioHTTPTestCase's client connects via 127.0.0.1, which now
        # exercises the loopback-without-bearer dashboard branch →
        # returns 200 with an empty session list. A non-loopback caller
        # with no bearer would still be rejected, but the test client
        # can't produce a non-loopback request.
        resp = await self.client.get("/sessions")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["sessions"], [])

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

    async def test_loopback_without_bearer_returns_full_list(self) -> None:
        # Dashboard-plugin branch: loopback callers with no Authorization
        # header get the full session list. No session highlight is
        # computed — every entry's is_current is False.
        await self._mint("dev-a")
        await self._mint("dev-b")

        resp = await self.client.get("/sessions")
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        sessions = body["sessions"]
        self.assertEqual(len(sessions), 2)
        for entry in sessions:
            self.assertFalse(entry["is_current"])
            self.assertIn("token_prefix", entry)
            self.assertNotIn("token", entry)

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

    # ── PATCH /sessions/{prefix} ────────────────────────────────────────

    async def test_extend_requires_bearer(self) -> None:
        resp = await self.client.patch(
            "/sessions/abcd1234", json={"ttl_seconds": 86400}
        )
        self.assertEqual(resp.status, 401)

    async def test_extend_rejects_invalid_bearer(self) -> None:
        resp = await self.client.patch(
            "/sessions/abcd1234",
            json={"ttl_seconds": 86400},
            headers={"Authorization": "Bearer nope"},
        )
        self.assertEqual(resp.status, 401)

    async def test_extend_404_on_no_match(self) -> None:
        token = await self._mint("dev-a")
        resp = await self.client.patch(
            "/sessions/zzzzzzzz",
            json={"ttl_seconds": 86400},
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(resp.status, 404)

    async def test_extend_400_on_empty_body(self) -> None:
        token = await self._mint("dev-a")
        resp = await self.client.patch(
            f"/sessions/{token[:8]}",
            json={},
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertIn("ttl_seconds", body["error"])

    async def test_extend_400_on_negative_ttl(self) -> None:
        token = await self._mint("dev-a")
        resp = await self.client.patch(
            f"/sessions/{token[:8]}",
            json={"ttl_seconds": -1},
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(resp.status, 400)

    async def test_extend_400_on_bad_grants(self) -> None:
        token = await self._mint("dev-a")
        resp = await self.client.patch(
            f"/sessions/{token[:8]}",
            json={"grants": {"terminal": -5}},
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(resp.status, 400)

    async def test_extend_ttl_only_restarts_clock(self) -> None:
        """ttl_seconds=N sets expires_at to now+N, not old_expiry+N."""
        import time as _time

        token = await self._mint("dev-a", ttl_seconds=3600)  # 1h session
        old = self._server().sessions.get_session(token)
        old_expiry = old.expires_at

        # Wait a hair to make sure the new expiry is measurably different
        # from the old one, even on fast clocks.
        await self._settle()

        resp = await self.client.patch(
            f"/sessions/{token[:8]}",
            json={"ttl_seconds": 7 * 86400},  # 7 days
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        new_expiry = body["expires_at"]
        self.assertIsNotNone(new_expiry)
        # New expiry should be roughly now + 7d, not old_expiry + 7d
        now = _time.time()
        self.assertAlmostEqual(new_expiry, now + 7 * 86400, delta=5)
        # And the persisted session matches
        updated = self._server().sessions.get_session(token)
        self.assertAlmostEqual(updated.expires_at, new_expiry, delta=0.01)
        # And it's definitely different from the old expiry
        self.assertNotAlmostEqual(updated.expires_at, old_expiry, delta=60)

    async def test_extend_ttl_zero_means_never(self) -> None:
        token = await self._mint("dev-a", ttl_seconds=3600)
        resp = await self.client.patch(
            f"/sessions/{token[:8]}",
            json={"ttl_seconds": 0},
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertIsNone(body["expires_at"])  # null = never
        # All grants should also be None (never) since session is infinite
        for channel in ("chat", "terminal", "bridge"):
            self.assertIsNone(body["grants"][channel])

    async def test_extend_grants_only(self) -> None:
        token = await self._mint("dev-a", ttl_seconds=30 * 86400)  # 30d
        resp = await self.client.patch(
            f"/sessions/{token[:8]}",
            json={"grants": {"terminal": 3600}},  # 1h terminal
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        # Session expiry unchanged — still ~30 days
        self.assertIsNotNone(body["expires_at"])
        # Terminal is now ~1h from now
        import time as _time
        self.assertAlmostEqual(
            body["grants"]["terminal"], _time.time() + 3600, delta=5
        )

    async def test_extend_shorter_ttl_clips_grants(self) -> None:
        """Shortening the session must clip grants that would outlive it."""
        import time as _time

        # Start with a 30-day session + default grants (terminal 30d cap).
        token = await self._mint("dev-a", ttl_seconds=30 * 86400)
        # Shorten to 1h.
        resp = await self.client.patch(
            f"/sessions/{token[:8]}",
            json={"ttl_seconds": 3600},
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        # expires_at ≈ now + 1h
        new_session_expiry = body["expires_at"]
        self.assertAlmostEqual(new_session_expiry, _time.time() + 3600, delta=5)
        # All grants must be ≤ new_session_expiry (not exceeding 1h)
        for channel, grant_expiry in body["grants"].items():
            self.assertIsNotNone(grant_expiry, f"{channel} should not be null")
            self.assertLessEqual(grant_expiry, new_session_expiry + 0.001)

    async def test_extend_self(self) -> None:
        """Caller can extend their own session."""
        token = await self._mint("dev-a", ttl_seconds=3600)
        resp = await self.client.patch(
            f"/sessions/{token[:8]}",
            json={"ttl_seconds": 2 * 86400},
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(resp.status, 200)
        # Caller's session still valid after the extend
        self.assertIsNotNone(self._server().sessions.get_session(token))

    async def _settle(self) -> None:
        """Small async delay so assertions sensitive to time.time() changes
        don't flap on fast machines. ~1 ms is plenty."""
        import asyncio
        await asyncio.sleep(0.001)


if __name__ == "__main__":
    unittest.main()
