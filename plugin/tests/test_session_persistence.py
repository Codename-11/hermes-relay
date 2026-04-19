"""Tests for :class:`SessionManager` file-backed persistence.

Covers the Commit-3 feature: sessions serialize to
``hermes-relay-sessions.json`` on every mutation and reload on
``__init__``, expired sessions drop at load time, corrupt files degrade
to empty in-memory state.
"""

from __future__ import annotations

import json
import math
import os
import stat
import sys
import tempfile
import time
import unittest
from pathlib import Path

from plugin.relay.auth import (
    DEFAULT_SESSIONS_FILENAME,
    Session,
    SessionManager,
    default_sessions_path,
)


class SessionPersistenceRoundtripTests(unittest.TestCase):
    """Create → destroy → recreate cycle: surviving sessions must be
    readable from the freshly-loaded manager."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.path = Path(self._tmp.name) / "sessions.json"

    def test_session_survives_reinstantiation(self) -> None:
        mgr = SessionManager(persistence_path=self.path)
        session = mgr.create_session(
            device_name="Phone-A",
            device_id="dev-a",
            ttl_seconds=3600,
            transport_hint="wss",
        )
        token = session.token

        # Drop the manager entirely (no graceful shutdown) and re-read.
        del mgr
        mgr2 = SessionManager(persistence_path=self.path)
        reloaded = mgr2.get_session(token)
        self.assertIsNotNone(reloaded)
        assert reloaded is not None
        self.assertEqual(reloaded.device_name, "Phone-A")
        self.assertEqual(reloaded.device_id, "dev-a")
        self.assertEqual(reloaded.transport_hint, "wss")

    def test_never_expire_roundtrips(self) -> None:
        """``math.inf`` expiries must survive a save/load cycle —
        json.dumps refuses inf, so we serialize the sentinel ``"never"``."""
        mgr = SessionManager(persistence_path=self.path)
        session = mgr.create_session(
            device_name="Phone-Never",
            device_id="dev-never",
            ttl_seconds=0,  # never-expire
        )
        token = session.token
        self.assertTrue(math.isinf(session.expires_at))

        mgr2 = SessionManager(persistence_path=self.path)
        reloaded = mgr2.get_session(token)
        self.assertIsNotNone(reloaded)
        assert reloaded is not None
        self.assertTrue(math.isinf(reloaded.expires_at))
        # All channel grants also come back as never.
        for ch in ("chat", "terminal", "bridge"):
            self.assertTrue(math.isinf(reloaded.grants[ch]))

    def test_revoke_persists(self) -> None:
        mgr = SessionManager(persistence_path=self.path)
        token_keep = mgr.create_session("Keep", "keep-id").token
        token_drop = mgr.create_session("Drop", "drop-id").token
        mgr.revoke_session(token_drop)

        mgr2 = SessionManager(persistence_path=self.path)
        self.assertIsNotNone(mgr2.get_session(token_keep))
        self.assertIsNone(mgr2.get_session(token_drop))

    def test_update_persists(self) -> None:
        """PATCH /sessions → SessionManager.update_session must flush
        the extended expiry to disk."""
        mgr = SessionManager(persistence_path=self.path)
        original = mgr.create_session(
            "Phone-X",
            "dev-x",
            ttl_seconds=60,  # short TTL
        )
        updated = mgr.update_session(original.token, ttl_seconds=0)  # now never
        self.assertIsNotNone(updated)
        assert updated is not None
        self.assertTrue(math.isinf(updated.expires_at))

        mgr2 = SessionManager(persistence_path=self.path)
        reloaded = mgr2.get_session(original.token)
        self.assertIsNotNone(reloaded)
        assert reloaded is not None
        self.assertTrue(math.isinf(reloaded.expires_at))


class SessionPersistenceExpiryTests(unittest.TestCase):
    """Expired sessions drop at load time — phone sees a clean list
    after relay restart."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.path = Path(self._tmp.name) / "sessions.json"

    def test_expired_session_is_dropped_on_load(self) -> None:
        """Hand-roll a persistence file containing one expired and one
        live session. After reinstantiation only the live one survives."""
        now = time.time()
        payload = {
            "version": 1,
            "sessions": [
                {
                    "token": "expired-token",
                    "device_name": "Expired",
                    "device_id": "exp",
                    "created_at": now - 3600,
                    "last_seen": now - 3600,
                    "expires_at": now - 60,  # 60 seconds ago
                    "grants": {"chat": now - 60},
                    "transport_hint": "wss",
                    "first_seen": now - 3600,
                },
                {
                    "token": "live-token",
                    "device_name": "Live",
                    "device_id": "live",
                    "created_at": now,
                    "last_seen": now,
                    "expires_at": now + 3600,
                    "grants": {"chat": now + 3600},
                    "transport_hint": "wss",
                    "first_seen": now,
                },
            ],
        }
        self.path.write_text(json.dumps(payload), encoding="utf-8")

        mgr = SessionManager(persistence_path=self.path)
        self.assertEqual(mgr.active_count(), 1)
        self.assertIsNotNone(mgr.get_session("live-token"))
        self.assertIsNone(mgr.get_session("expired-token"))


class SessionPersistenceFileLayoutTests(unittest.TestCase):
    """File mode (0o600), atomic-write semantics, and directory
    creation."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.path = Path(self._tmp.name) / "subdir" / "sessions.json"

    def test_missing_parent_dir_gets_created(self) -> None:
        mgr = SessionManager(persistence_path=self.path)
        mgr.create_session("P", "dev")
        self.assertTrue(self.path.is_file())
        self.assertTrue(self.path.parent.is_dir())

    @unittest.skipIf(
        sys.platform == "win32",
        "file permissions use Windows ACLs, not POSIX 0o600",
    )
    def test_file_mode_is_0600(self) -> None:
        mgr = SessionManager(persistence_path=self.path)
        mgr.create_session("P", "dev")
        mode = stat.S_IMODE(self.path.stat().st_mode)
        # Owner rw (0o600). Group/world bits must be clear.
        self.assertEqual(mode & 0o077, 0)

    def test_file_content_is_well_formed_json(self) -> None:
        mgr = SessionManager(persistence_path=self.path)
        mgr.create_session("P", "dev")
        with open(self.path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        self.assertIn("version", data)
        self.assertIn("sessions", data)
        self.assertEqual(data["version"], 1)
        self.assertEqual(len(data["sessions"]), 1)


class SessionPersistenceCorruptionTests(unittest.TestCase):
    """Corrupt persistence files must degrade to empty state rather
    than crash the relay."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.path = Path(self._tmp.name) / "sessions.json"

    def test_unparseable_json_starts_empty(self) -> None:
        self.path.write_text("this is not json {{{", encoding="utf-8")
        mgr = SessionManager(persistence_path=self.path)
        self.assertEqual(mgr.active_count(), 0)

    def test_non_object_root_starts_empty(self) -> None:
        self.path.write_text("[1, 2, 3]", encoding="utf-8")
        mgr = SessionManager(persistence_path=self.path)
        self.assertEqual(mgr.active_count(), 0)

    def test_missing_sessions_array_starts_empty(self) -> None:
        self.path.write_text('{"version": 1}', encoding="utf-8")
        mgr = SessionManager(persistence_path=self.path)
        self.assertEqual(mgr.active_count(), 0)

    def test_malformed_entry_is_skipped(self) -> None:
        """One good entry + one nonsense entry → good entry survives."""
        now = time.time()
        payload = {
            "version": 1,
            "sessions": [
                {
                    "token": "good-token",
                    "device_name": "Good",
                    "device_id": "g",
                    "created_at": now,
                    "last_seen": now,
                    "expires_at": now + 3600,
                    "grants": {},
                    "transport_hint": "wss",
                    "first_seen": now,
                },
                # Missing required "token" field.
                {"device_name": "Bad"},
            ],
        }
        self.path.write_text(json.dumps(payload), encoding="utf-8")
        mgr = SessionManager(persistence_path=self.path)
        self.assertEqual(mgr.active_count(), 1)
        self.assertIsNotNone(mgr.get_session("good-token"))


class SessionPersistenceDisabledTests(unittest.TestCase):
    """``persistence_path=None`` (the default) must stay fully in-memory."""

    def test_default_is_in_memory(self) -> None:
        mgr = SessionManager()
        session = mgr.create_session("P", "dev")
        # A sibling manager cannot find this token — there's no disk
        # state to share.
        other = SessionManager()
        self.assertIsNone(other.get_session(session.token))

    def test_explicit_none_is_in_memory(self) -> None:
        mgr = SessionManager(persistence_path=None)
        mgr.create_session("P", "dev")
        # No file should appear under HERMES_HOME.
        # We don't assert on the filesystem here — the strong assertion
        # is that construction + create_session didn't raise when no
        # path was provided.
        self.assertEqual(mgr.active_count(), 1)


class DefaultSessionsPathTests(unittest.TestCase):
    """``default_sessions_path`` resolves to ``$HERMES_HOME`` or
    ``~/.hermes`` — mirrors qr_sign."""

    def test_respects_hermes_home_env(self) -> None:
        prior = os.environ.get("HERMES_HOME")
        try:
            with tempfile.TemporaryDirectory() as td:
                os.environ["HERMES_HOME"] = td
                p = default_sessions_path()
                self.assertEqual(p.name, DEFAULT_SESSIONS_FILENAME)
                # Parent should resolve back to the override dir.
                self.assertEqual(p.parent.resolve(), Path(td).resolve())
        finally:
            if prior is None:
                os.environ.pop("HERMES_HOME", None)
            else:
                os.environ["HERMES_HOME"] = prior

    def test_falls_back_to_home_dot_hermes(self) -> None:
        prior = os.environ.get("HERMES_HOME")
        try:
            os.environ.pop("HERMES_HOME", None)
            p = default_sessions_path()
            self.assertEqual(p.name, DEFAULT_SESSIONS_FILENAME)
            self.assertIn(".hermes", str(p))
        finally:
            if prior is not None:
                os.environ["HERMES_HOME"] = prior


if __name__ == "__main__":
    unittest.main()
