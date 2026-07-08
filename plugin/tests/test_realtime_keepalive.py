"""Provider idle-close handling for the realtime agent.

xAI closes a realtime conversation after 900s of true inactivity. Live probe
runs on 2026-07-08 proved protocol no-ops do not reset that timer, so the relay
now treats the provider idle-close as routine: close the Android websocket
cleanly while idle and let the next user turn open a fresh provider session.
"""

from __future__ import annotations

import asyncio
import tempfile
import time
import unittest
from pathlib import Path
from typing import Any

from plugin.relay.config import RelayConfig
from plugin.relay.realtime_agent import broker as broker_module
from plugin.relay.realtime_agent.broker import (
    RealtimeAgentHandler,
    RealtimeAgentSession,
    _is_provider_idle_timeout,
)
from plugin.relay.realtime_agent.models import ProviderEvent, ProviderEventKind


class FakeWebSocket:
    def __init__(self) -> None:
        self.closed = False
        self.close_code: int | None = None
        self.close_message: bytes | None = None
        self.sent: list[dict[str, Any]] = []

    async def close(self, *, code: int = 1000, message: bytes = b"") -> None:
        self.closed = True
        self.close_code = code
        self.close_message = message

    async def send_json(self, payload: dict[str, Any]) -> None:
        self.sent.append(payload)


class FakeConnection:
    def __init__(self) -> None:
        self._events: asyncio.Queue[ProviderEvent | None] = asyncio.Queue()

    async def emit(self, event: ProviderEvent) -> None:
        await self._events.put(event)

    async def finish(self) -> None:
        await self._events.put(None)

    async def events(self):
        while True:
            event = await self._events.get()
            if event is None:
                return
            yield event


def _make_session(tmpdir: str) -> RealtimeAgentSession:
    return RealtimeAgentSession(
        session_id="sess-idle",
        provider="xai_realtime",
        model="grok-voice-latest",
        voice="ember",
        sample_rate=24000,
        profile=None,
        chat_session_id="chat-1",
        config_scope="test",
        config_path=None,
        auth_kind="test",
        bearer_token=None,
        created_at=time.time(),
        event_log_path=Path(tmpdir) / "events.jsonl",
        resume_token_hash="hash",
    )


class ProviderIdleCloseTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.handler = RealtimeAgentHandler(RelayConfig())
        self.session = _make_session(self._tmp.name)

    async def test_idle_timeout_closes_android_socket_without_voice_error(self) -> None:
        ws = FakeWebSocket()
        connection = FakeConnection()
        self.session.attached_ws = ws  # type: ignore[assignment]

        await connection.emit(
            ProviderEvent(
                ProviderEventKind.ERROR,
                payload={
                    "message": (
                        "xAI Realtime error: Conversation timed out after "
                        "900.0 seconds due to inactivity"
                    )
                },
            )
        )
        await self.handler._pump_provider_events(  # type: ignore[arg-type]
            ws,
            self.session,
            connection,  # type: ignore[arg-type]
            asyncio.Event(),
        )

        self.assertTrue(self.session.native_provider_idle_closed)
        self.assertTrue(ws.closed)
        self.assertEqual(ws.close_code, 1000)
        self.assertEqual(
            ws.close_message,
            broker_module._PROVIDER_IDLE_CLOSE_WS_REASON.encode("utf-8"),
        )
        self.assertEqual([], [event for event in ws.sent if event.get("type") == "voice.error"])
        log_text = self.session.event_log_path.read_text(encoding="utf-8")
        self.assertIn("voice.realtime_agent.provider_idle_close", log_text)


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
            "request timed out",
            "provider error",
        ):
            self.assertFalse(_is_provider_idle_timeout(message), message)


if __name__ == "__main__":
    unittest.main()
