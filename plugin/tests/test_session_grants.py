"""Tests for SessionManager + PairingManager TTL / grants / transport hint.

Covers the v0.3.0 auth refactor: per-session TTL (including "never"),
per-channel grants with defaults + caps, and PairingManager metadata
plumbing.
"""

from __future__ import annotations

import math
import time
import unittest

from plugin.relay.auth import (
    DEFAULT_BRIDGE_CAP,
    DEFAULT_TERMINAL_CAP,
    DEFAULT_TTL_SECONDS,
    PairingManager,
    PairingMetadata,
    SessionManager,
)


class SessionTtlTests(unittest.TestCase):
    def test_default_ttl_and_grants(self) -> None:
        """Calling create_session with no args matches the legacy 30d default."""
        mgr = SessionManager()
        t0 = time.time()
        session = mgr.create_session("dev", "id-1")

        # Expires ~30 days from now
        self.assertAlmostEqual(
            session.expires_at - t0, DEFAULT_TTL_SECONDS, delta=5
        )
        # All three channels have default grants
        self.assertIn("chat", session.grants)
        self.assertIn("terminal", session.grants)
        self.assertIn("bridge", session.grants)
        # With a 30-day TTL, caps don't bite — all three should roughly align.
        self.assertAlmostEqual(
            session.grants["chat"], session.expires_at, delta=1
        )
        self.assertAlmostEqual(
            session.grants["terminal"], t0 + DEFAULT_TERMINAL_CAP, delta=5
        )
        self.assertAlmostEqual(
            session.grants["bridge"], t0 + DEFAULT_BRIDGE_CAP, delta=5
        )

    def test_short_ttl_caps_grants_at_session_expiry(self) -> None:
        """A 1-day session cannot have 7-day bridge grants — min(ttl, cap)."""
        mgr = SessionManager()
        one_day = 24 * 3600
        t0 = time.time()
        session = mgr.create_session("dev", "id-1", ttl_seconds=one_day)

        self.assertAlmostEqual(session.expires_at, t0 + one_day, delta=5)
        self.assertAlmostEqual(session.grants["chat"], t0 + one_day, delta=5)
        self.assertAlmostEqual(
            session.grants["terminal"], t0 + one_day, delta=5
        )
        self.assertAlmostEqual(session.grants["bridge"], t0 + one_day, delta=5)

    def test_never_expire(self) -> None:
        """ttl_seconds=0 means math.inf; is_expired stays False."""
        mgr = SessionManager()
        session = mgr.create_session("dev", "id-1", ttl_seconds=0)

        self.assertTrue(math.isinf(session.expires_at))
        self.assertFalse(session.is_expired)
        for channel in ("chat", "terminal", "bridge"):
            self.assertTrue(math.isinf(session.grants[channel]))
            self.assertFalse(session.channel_is_expired(channel))

    def test_explicit_grants_clamped_to_session_lifetime(self) -> None:
        """Grants may not outlive the session, even if the caller asks."""
        mgr = SessionManager()
        one_day = 24 * 3600
        week = 7 * 24 * 3600
        t0 = time.time()
        session = mgr.create_session(
            "dev",
            "id-1",
            ttl_seconds=one_day,
            grants={"terminal": week},  # caller asks for 7d
        )
        # Session lasts 1d, so terminal is clamped to 1d.
        self.assertAlmostEqual(
            session.grants["terminal"], t0 + one_day, delta=5
        )

    def test_explicit_grant_value_is_seconds_from_now(self) -> None:
        mgr = SessionManager()
        t0 = time.time()
        session = mgr.create_session(
            "dev",
            "id-1",
            ttl_seconds=10 * 24 * 3600,
            grants={"terminal": 3600},  # 1 hour
        )
        self.assertAlmostEqual(session.grants["terminal"], t0 + 3600, delta=5)

    def test_grant_value_zero_means_never_capped_to_session(self) -> None:
        """A never-grant on a finite session still gets clamped to the session."""
        mgr = SessionManager()
        one_day = 24 * 3600
        t0 = time.time()
        session = mgr.create_session(
            "dev",
            "id-1",
            ttl_seconds=one_day,
            grants={"terminal": 0},  # caller asks for never
        )
        self.assertAlmostEqual(
            session.grants["terminal"], t0 + one_day, delta=5
        )

    def test_grant_value_zero_means_never_on_never_session(self) -> None:
        """A never-grant on a never-session really is forever."""
        mgr = SessionManager()
        session = mgr.create_session(
            "dev",
            "id-1",
            ttl_seconds=0,
            grants={"terminal": 0},
        )
        self.assertTrue(math.isinf(session.grants["terminal"]))

    def test_transport_hint_recorded(self) -> None:
        mgr = SessionManager()
        session = mgr.create_session(
            "dev", "id-1", transport_hint="wss"
        )
        self.assertEqual(session.transport_hint, "wss")

    def test_channel_is_expired_for_unknown_channel(self) -> None:
        """Unknown channel names are reported as expired — safer default."""
        mgr = SessionManager()
        session = mgr.create_session("dev", "id-1")
        self.assertTrue(session.channel_is_expired("unknown-channel"))


class PairingMetadataTests(unittest.TestCase):
    def test_register_without_metadata(self) -> None:
        mgr = PairingManager()
        self.assertTrue(mgr.register_code("ABC123"))
        meta = mgr.consume_code("ABC123")
        self.assertIsInstance(meta, PairingMetadata)
        self.assertIsNone(meta.ttl_seconds)
        self.assertIsNone(meta.grants)
        self.assertIsNone(meta.transport_hint)

    def test_register_with_metadata(self) -> None:
        mgr = PairingManager()
        mgr.register_code(
            "ABC123",
            ttl_seconds=7 * 24 * 3600,
            grants={"terminal": 3600},
            transport_hint="ws",
        )
        meta = mgr.consume_code("abc123")  # case-insensitive
        self.assertIsNotNone(meta)
        assert meta is not None
        self.assertEqual(meta.ttl_seconds, 7 * 24 * 3600)
        self.assertEqual(meta.grants, {"terminal": 3600})
        self.assertEqual(meta.transport_hint, "ws")

    def test_consume_returns_none_for_unknown_code(self) -> None:
        mgr = PairingManager()
        self.assertIsNone(mgr.consume_code("ZZZZZZ"))

    def test_consume_is_one_shot(self) -> None:
        mgr = PairingManager()
        mgr.register_code("ABC123", ttl_seconds=0)
        self.assertIsNotNone(mgr.consume_code("ABC123"))
        self.assertIsNone(mgr.consume_code("ABC123"))

    def test_register_rejects_bad_format(self) -> None:
        mgr = PairingManager()
        self.assertFalse(mgr.register_code("short"))         # wrong length
        self.assertFalse(mgr.register_code("ABC@12"))        # invalid char

    def test_register_normalizes_case(self) -> None:
        """Lowercase codes are normalized to uppercase rather than rejected.

        Matches the user-friendly "type your code in either case" behavior
        the server has always had — ``register_code`` upper-cases before
        validating against ``PAIRING_ALPHABET``. Regression guard so a
        future well-intentioned refactor doesn't accidentally make this
        case-sensitive.
        """
        mgr = PairingManager()
        self.assertTrue(mgr.register_code("lowr12"))
        # The normalized (uppercase) form is what's actually stored and
        # consumable.
        self.assertIsNotNone(mgr.consume_code("LOWR12"))


class SessionListingTests(unittest.TestCase):
    def test_list_sessions_returns_all_active(self) -> None:
        mgr = SessionManager()
        a = mgr.create_session("a", "id-a")
        b = mgr.create_session("b", "id-b")
        sessions = mgr.list_sessions()
        self.assertEqual(len(sessions), 2)
        tokens = {s.token for s in sessions}
        self.assertEqual(tokens, {a.token, b.token})

    def test_find_by_prefix_unique(self) -> None:
        mgr = SessionManager()
        a = mgr.create_session("a", "id-a")
        mgr.create_session("b", "id-b")
        matches = mgr.find_by_prefix(a.token[:8])
        self.assertEqual(len(matches), 1)
        self.assertEqual(matches[0].token, a.token)

    def test_find_by_prefix_nomatch(self) -> None:
        mgr = SessionManager()
        mgr.create_session("a", "id-a")
        self.assertEqual(mgr.find_by_prefix("zzzzzzzz"), [])

    def test_revoke_removes_from_list(self) -> None:
        mgr = SessionManager()
        a = mgr.create_session("a", "id-a")
        self.assertTrue(mgr.revoke_session(a.token))
        self.assertEqual(mgr.list_sessions(), [])


if __name__ == "__main__":
    unittest.main()
