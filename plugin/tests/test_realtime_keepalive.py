"""Provider-facing keepalive tests for the realtime agent.

xAI closes a realtime conversation after 900s of inactivity (observed live
2026-07-08). The broker's `_provider_keepalive_loop` appends short silent PCM
chunks to the provider input buffer while the connection is quiet so an
open-but-silent voice session (background-run wait, or the user simply leaving
voice mode open) survives. These tests drive the loop directly with a fake
connection and a sub-second interval via `RELAY_VOICE_PROVIDER_KEEPALIVE_MS`.
"""

from __future__ import annotations

import asyncio
import os
import tempfile
import time
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import patch

from plugin.relay.config import RelayConfig
from plugin.relay.realtime_agent import broker as broker_module
from plugin.relay.realtime_agent.broker import (
    RealtimeAgentHandler,
    RealtimeAgentSession,
    _is_provider_idle_timeout,
    _provider_keepalive_seconds,
)


class FakeConnection:
    """Only what the keepalive loop touches: send_audio."""

    def __init__(self, fail: bool = False) -> None:
        self.audio_sends: list[tuple[bytes, int]] = []
        self.fail = fail

    async def send_audio(self, pcm: bytes, sample_rate: int) -> None:
        if self.fail:
            raise RuntimeError("socket closed")
        self.audio_sends.append((pcm, sample_rate))


def _make_session(tmpdir: str) -> RealtimeAgentSession:
    return RealtimeAgentSession(
        session_id="sess-keepalive",
        provider="xai_realtime",
        model="grok-voice-latest",
        voice="ember",
        sample_rate=24000,
        profile=None,
        chat_session_id=None,
        config_scope="test",
        config_path=None,
        auth_kind="test",
        bearer_token=None,
        created_at=time.time(),
        event_log_path=Path(tmpdir) / "events.jsonl",
        resume_token_hash="hash",
    )


class ProviderKeepaliveLoopTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.handler = RealtimeAgentHandler(RelayConfig())
        self.session = _make_session(self._tmp.name)

    async def _run_loop_briefly(
        self,
        connection: FakeConnection,
        interval_ms: int,
        run_for_s: float,
    ) -> None:
        self.session.native_connection = connection  # type: ignore[assignment]
        with patch.dict(
            os.environ, {"RELAY_VOICE_PROVIDER_KEEPALIVE_MS": str(interval_ms)}
        ):
            task = asyncio.create_task(
                self.handler._provider_keepalive_loop(self.session, connection)
            )
            await asyncio.sleep(run_for_s)
            self.session.closed = True
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

    async def test_pings_with_silence_after_quiet_interval(self) -> None:
        connection = FakeConnection()
        # Stale activity stamp → first ping should fire immediately.
        self.session.native_last_provider_activity = time.monotonic() - 999.0
        await self._run_loop_briefly(connection, interval_ms=40, run_for_s=0.15)

        self.assertGreaterEqual(len(connection.audio_sends), 2)
        pcm, sample_rate = connection.audio_sends[0]
        self.assertEqual(sample_rate, 24000)
        # 100ms of 16-bit mono at 24kHz.
        self.assertEqual(len(pcm), 4800)
        self.assertEqual(pcm, b"\x00" * len(pcm))
        # Each ping is recorded in the session event log.
        log_text = self.session.event_log_path.read_text(encoding="utf-8")
        self.assertIn("voice.realtime_agent.provider_keepalive", log_text)

    async def test_quiet_interval_not_elapsed_means_no_ping(self) -> None:
        connection = FakeConnection()
        # Fresh activity: loop should sleep out the whole run without sending.
        self.session.native_last_provider_activity = time.monotonic()
        await self._run_loop_briefly(connection, interval_ms=5_000, run_for_s=0.1)

        self.assertEqual(connection.audio_sends, [])

    async def test_recent_client_activity_defers_ping(self) -> None:
        connection = FakeConnection()
        self.session.native_last_provider_activity = time.monotonic() - 999.0
        self.session.native_connection = connection  # type: ignore[assignment]
        with patch.dict(os.environ, {"RELAY_VOICE_PROVIDER_KEEPALIVE_MS": "60"}):
            task = asyncio.create_task(
                self.handler._provider_keepalive_loop(self.session, connection)
            )
            await asyncio.sleep(0.02)
            first_burst = len(connection.audio_sends)
            # Simulate live traffic: keep the stamp fresh for a while.
            for _ in range(4):
                self.session.native_last_provider_activity = time.monotonic()
                await asyncio.sleep(0.02)
            during_activity = len(connection.audio_sends) - first_burst
            self.session.closed = True
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

        self.assertGreaterEqual(first_burst, 1)
        self.assertEqual(during_activity, 0)

    async def test_zero_interval_disables_keepalive(self) -> None:
        connection = FakeConnection()
        self.session.native_last_provider_activity = time.monotonic() - 999.0
        await self._run_loop_briefly(connection, interval_ms=0, run_for_s=0.05)

        self.assertEqual(connection.audio_sends, [])

    async def test_send_failure_stops_loop_and_logs(self) -> None:
        connection = FakeConnection(fail=True)
        self.session.native_last_provider_activity = time.monotonic() - 999.0
        self.session.native_connection = connection  # type: ignore[assignment]
        with patch.dict(os.environ, {"RELAY_VOICE_PROVIDER_KEEPALIVE_MS": "10"}):
            # Loop must return on its own (not rely on cancellation).
            await asyncio.wait_for(
                self.handler._provider_keepalive_loop(self.session, connection),
                timeout=2.0,
            )

        log_text = self.session.event_log_path.read_text(encoding="utf-8")
        self.assertIn("voice.realtime_agent.provider_keepalive_failed", log_text)

    async def test_connection_swap_stops_loop(self) -> None:
        connection = FakeConnection()
        self.session.native_last_provider_activity = time.monotonic() - 999.0
        self.session.native_connection = None  # replaced/torn down elsewhere
        with patch.dict(os.environ, {"RELAY_VOICE_PROVIDER_KEEPALIVE_MS": "10"}):
            await asyncio.wait_for(
                self.handler._provider_keepalive_loop(self.session, connection),
                timeout=2.0,
            )

        self.assertEqual(connection.audio_sends, [])


class KeepaliveConfigTest(unittest.TestCase):
    def test_default_interval(self) -> None:
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("RELAY_VOICE_PROVIDER_KEEPALIVE_MS", None)
            self.assertEqual(
                _provider_keepalive_seconds(),
                broker_module._PROVIDER_KEEPALIVE_SECONDS,
            )

    def test_env_override_and_disable(self) -> None:
        with patch.dict(os.environ, {"RELAY_VOICE_PROVIDER_KEEPALIVE_MS": "120000"}):
            self.assertEqual(_provider_keepalive_seconds(), 120.0)
        with patch.dict(os.environ, {"RELAY_VOICE_PROVIDER_KEEPALIVE_MS": "0"}):
            self.assertEqual(_provider_keepalive_seconds(), 0.0)


class IdleTimeoutClassifierTest(unittest.TestCase):
    def test_matches_observed_xai_idle_close(self) -> None:
        self.assertTrue(
            _is_provider_idle_timeout(
                "xAI Realtime error: Conversation timed out after 900.0 seconds "
                "due to inactivity"
            )
        )

    def test_case_insensitive(self) -> None:
        self.assertTrue(
            _is_provider_idle_timeout("Conversation TIMED OUT due to INACTIVITY")
        )

    def test_does_not_match_other_errors(self) -> None:
        for message in (
            "Cancellation failed: no active response found",
            "rate limit exceeded",
            "request timed out",  # timeout without inactivity
            "provider error",
        ):
            self.assertFalse(_is_provider_idle_timeout(message), message)


if __name__ == "__main__":
    unittest.main()
