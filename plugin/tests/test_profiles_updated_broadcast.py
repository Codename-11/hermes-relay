"""Tests for the profiles.updated envelope broadcast.

Covers the Commit-5 follow-up to profile writes:

* _notify_profiles_changed re-runs _load_profiles and compares
  against the cached snapshot; a diff triggers
  _broadcast_profiles_updated on the running loop.
* _broadcast_profiles_updated sends a
  ``{"channel": "pairing", "type": "profiles.updated", ...}``
  envelope to every authenticated client.
* The background rescan task is registered as an on_startup hook and
  gets torn down via on_cleanup on shutdown.
"""

from __future__ import annotations

import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

from aiohttp import web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay.config import RelayConfig
from plugin.relay.server import (
    _broadcast_profiles_updated,
    _notify_profiles_changed,
    create_app,
)


def _make_ws(closed: bool = False, send_side_effect: Exception | None = None):
    """Construct a minimal WebSocketResponse stand-in — only the
    attributes _broadcast_profiles_updated touches (``closed``,
    ``send_str``)."""
    ws = MagicMock()
    ws.closed = closed
    if send_side_effect is not None:
        ws.send_str = AsyncMock(side_effect=send_side_effect)
    else:
        ws.send_str = AsyncMock()
    return ws


class BroadcastEnvelopeShapeTests(unittest.IsolatedAsyncioTestCase):
    """Direct test of _broadcast_profiles_updated — mock WebSocket
    clients, assert envelope shape. Server is a MagicMock (no real
    aiohttp app), so no teardown concerns."""

    async def test_envelope_shape_matches_spec(self) -> None:
        server = MagicMock()
        server._clients = {}
        ws = _make_ws()
        server._clients[ws] = "session-token"

        profiles = [
            {"name": "default", "model": "x"},
            {"name": "mizu", "model": "claude-opus"},
        ]
        await _broadcast_profiles_updated(server, profiles)

        self.assertEqual(ws.send_str.call_count, 1)
        sent = ws.send_str.call_args[0][0]
        envelope = json.loads(sent)
        self.assertEqual(envelope["channel"], "pairing")
        self.assertEqual(envelope["type"], "profiles.updated")
        self.assertIn("id", envelope)
        self.assertIn("payload", envelope)
        self.assertEqual(envelope["payload"]["profiles"], profiles)

    async def test_broadcasts_to_every_client(self) -> None:
        server = MagicMock()
        server._clients = {}

        clients = [_make_ws() for _ in range(3)]
        for ws in clients:
            server._clients[ws] = "tok"

        await _broadcast_profiles_updated(server, [{"name": "p"}])
        for ws in clients:
            self.assertEqual(ws.send_str.call_count, 1)

    async def test_closed_clients_skipped(self) -> None:
        server = MagicMock()
        server._clients = {}

        ws_closed = _make_ws(closed=True)
        ws_open = _make_ws()
        server._clients[ws_closed] = "tok1"
        server._clients[ws_open] = "tok2"

        await _broadcast_profiles_updated(server, [{"name": "p"}])

        ws_closed.send_str.assert_not_awaited()
        ws_open.send_str.assert_awaited_once()

    async def test_send_failure_does_not_block_others(self) -> None:
        """One client blowing up during send must not prevent the rest
        from receiving the envelope."""
        server = MagicMock()
        server._clients = {}

        ws_bad = _make_ws(send_side_effect=ConnectionResetError())
        ws_good = _make_ws()
        server._clients[ws_bad] = "tok1"
        server._clients[ws_good] = "tok2"

        await _broadcast_profiles_updated(server, [{"name": "p"}])
        self.assertEqual(ws_good.send_str.call_count, 1)


class NotifyProfilesChangedTests(unittest.IsolatedAsyncioTestCase):
    """_notify_profiles_changed re-runs _load_profiles + diffs.

    Uses a fake server and patches _broadcast_profiles_updated so we
    don't need to spin up the real aiohttp app (which the shape tests
    cover separately). This keeps the test isolated from the
    background rescan task and teardown complications.
    """

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.hermes_dir = Path(self._tmp.name)
        self.profiles_dir = self.hermes_dir / "profiles"
        self.profiles_dir.mkdir(parents=True, exist_ok=True)
        (self.hermes_dir / "config.yaml").write_text(
            "model:\n  default: test\n", encoding="utf-8"
        )

    def _build_server(self) -> MagicMock:
        """Fake RelayServer with only the attributes the hook reads."""
        server = MagicMock()
        server.config = RelayConfig(
            hermes_config_path=str(self.hermes_dir / "config.yaml"),
        )
        from plugin.relay.config import _load_profiles
        server.config.profiles = _load_profiles(
            server.config.hermes_config_path, enabled=True
        )
        server._clients = {}
        return server

    def _write_profile(self, name: str) -> Path:
        pdir = self.profiles_dir / name
        pdir.mkdir(parents=True, exist_ok=True)
        (pdir / "config.yaml").write_text(
            "model:\n  default: x\n", encoding="utf-8"
        )
        return pdir

    async def test_no_diff_does_not_broadcast(self) -> None:
        """Calling the hook with an unchanged tree should not schedule
        any broadcasts or mutate the cached snapshot."""
        server = self._build_server()
        snapshot_before = list(server.config.profiles)

        with patch(
            "plugin.relay.server._broadcast_profiles_updated",
            new_callable=AsyncMock,
        ) as mock_bcast:
            _notify_profiles_changed(server, reason="test")
            await asyncio.sleep(0.05)

        mock_bcast.assert_not_awaited()
        self.assertEqual(server.config.profiles, snapshot_before)

    async def test_diff_triggers_broadcast(self) -> None:
        """Adding a profile to the tree should produce a
        profiles.updated broadcast and update the cached snapshot."""
        server = self._build_server()
        self._write_profile("fresh")  # shape change on disk

        with patch(
            "plugin.relay.server._broadcast_profiles_updated",
            new_callable=AsyncMock,
        ) as mock_bcast:
            _notify_profiles_changed(server, reason="test", profile="fresh")
            # Hook uses loop.create_task; yield to let it run.
            await asyncio.sleep(0.05)

        mock_bcast.assert_awaited()
        names = [p["name"] for p in server.config.profiles]
        self.assertIn("fresh", names)


class PutEndpointBroadcastIntegrationTests(AioHTTPTestCase):
    """Integration — calling PUT /soul on a profile with changed
    description (SOUL first line changes) triggers a broadcast.

    Patches ``_broadcast_profiles_updated`` at the module level so the
    real send_str loop never runs on MagicMock clients (which would
    blow up during tearDown)."""

    async def get_application(self) -> web.Application:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.hermes_dir = Path(self._tmp.name)
        self.profiles_dir = self.hermes_dir / "profiles"
        self.profiles_dir.mkdir(parents=True, exist_ok=True)
        (self.hermes_dir / "config.yaml").write_text(
            "model:\n  default: test\n", encoding="utf-8"
        )
        config = RelayConfig(
            hermes_config_path=str(self.hermes_dir / "config.yaml")
        )
        return create_app(config)

    def _write_profile(self, name: str, soul: str | None = None) -> Path:
        pdir = self.profiles_dir / name
        pdir.mkdir(parents=True, exist_ok=True)
        (pdir / "config.yaml").write_text(
            "model:\n  default: x\n", encoding="utf-8"
        )
        if soul is not None:
            (pdir / "SOUL.md").write_text(soul, encoding="utf-8")
        return pdir

    async def test_put_soul_schedules_broadcast_on_reshape(self) -> None:
        server = self.app["server"]
        self._write_profile("phoenix", soul="# OldHead\n")
        # Refresh cached snapshot to include phoenix.
        from plugin.relay.config import _load_profiles
        server.config.profiles = _load_profiles(
            server.config.hermes_config_path, enabled=True
        )

        with patch(
            "plugin.relay.server._broadcast_profiles_updated",
            new_callable=AsyncMock,
        ) as mock_bcast:
            resp = await self.client.put(
                "/api/profiles/phoenix/soul",
                json={
                    # Changes the fallback description (first line of
                    # SOUL) → _load_profiles output diffs.
                    "content": "# NewHead\n\nBody.\n",
                },
            )
            self.assertEqual(resp.status, 200)
            # Fire-and-forget scheduled task; let it run.
            await asyncio.sleep(0.1)

        mock_bcast.assert_awaited()


if __name__ == "__main__":
    unittest.main()
