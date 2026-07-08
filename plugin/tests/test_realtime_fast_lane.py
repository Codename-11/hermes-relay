"""Fast-lane tests for a second hermes_run_task during a detached run.

Background-run v2 §1: while one durable run is detached, a second
``hermes_run_task`` is first tried INLINE on a separate ephemeral Hermes
session within the grace window. A quick request answers immediately; one
that would promote (grace elapsed / known-long tool started / explicit
background mode / promotion disabled) is abandoned and the caller falls back
to the existing busy answer. The fast lane must never disturb the in-flight
run's session state (hermes_task, run_id, status, chat_session_id).
"""

from __future__ import annotations

import asyncio
import tempfile
import time
import unittest
from pathlib import Path
from typing import Any

from plugin.relay.config import RelayConfig
from plugin.relay.realtime_agent.broker import (
    RealtimeAgentHandler,
    RealtimeAgentSession,
)
from plugin.relay.realtime_agent.hermes_tool_broker import HermesTaskRequest
from plugin.relay.realtime_agent.models import ToolCallEvent


class FastHermesBroker:
    """Completes immediately with a short answer on a fresh session."""

    def __init__(self) -> None:
        self.requests: list[HermesTaskRequest] = []

    async def stream_task(self, request: HermesTaskRequest):
        self.requests.append(request)
        yield {"type": "hermes.session.bound", "session_id": "ephemeral-1"}
        yield {"type": "hermes.run.started", "session_id": "ephemeral-1", "run_id": "fast-1"}
        yield {
            "type": "hermes.tool.started",
            "tool_call_id": "t-1",
            "tool_name": "web_search",
        }
        yield {
            "type": "hermes.tool.completed",
            "tool_call_id": "t-1",
            "tool_name": "web_search",
        }
        yield {"type": "voice.response.delta", "delta": "It is 9pm in Tokyo."}
        yield {"type": "hermes.run.completed", "session_id": "ephemeral-1", "run_id": "fast-1"}


class SlowHermesBroker:
    """Blocks past any test grace window (cancellation-friendly)."""

    def __init__(self) -> None:
        self.requests: list[HermesTaskRequest] = []
        self.cancelled = asyncio.Event()

    async def stream_task(self, request: HermesTaskRequest):
        self.requests.append(request)
        yield {"type": "hermes.run.started", "session_id": "ephemeral-1", "run_id": "slow-1"}
        try:
            await asyncio.sleep(30)
        except asyncio.CancelledError:
            self.cancelled.set()
            raise
        yield {"type": "voice.response.delta", "delta": "too late"}


class LongToolHermesBroker:
    """Starts a known-long tool (terminal) before answering."""

    async def stream_task(self, request: HermesTaskRequest):
        yield {"type": "hermes.run.started", "session_id": "ephemeral-1", "run_id": "lt-1"}
        yield {
            "type": "hermes.tool.started",
            "tool_call_id": "t-1",
            "tool_name": "terminal_execute",
        }
        yield {"type": "voice.response.delta", "delta": "should never be read"}


class ErrorHermesBroker:
    async def stream_task(self, request: HermesTaskRequest):
        yield {"type": "hermes.run.started", "session_id": "ephemeral-1", "run_id": "err-1"}
        yield {"type": "voice.error", "message": "gateway unreachable"}


def _make_session(tmpdir: str) -> RealtimeAgentSession:
    return RealtimeAgentSession(
        session_id="sess-fast-lane",
        provider="xai_realtime",
        model="grok-voice-latest",
        voice="ember",
        sample_rate=24000,
        profile=None,
        chat_session_id="main-chat-session",
        config_scope="test",
        config_path=None,
        auth_kind="test",
        bearer_token=None,
        created_at=time.time(),
        event_log_path=Path(tmpdir) / "events.jsonl",
        resume_token_hash="hash",
    )


def _run_task_call(mode: str | None = None) -> ToolCallEvent:
    arguments: dict[str, Any] = {"text": "What time is it in Tokyo?"}
    if mode:
        arguments["mode"] = mode
    return ToolCallEvent(call_id="call-2", name="hermes_run_task", arguments=arguments)


class FastLaneTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.handler = RealtimeAgentHandler(RelayConfig())
        self.session = _make_session(self._tmp.name)
        # A detached (promoted) run occupies the single background slot.
        self.session.promotion_enabled = True
        self.session.promote_after_ms = 500
        self.session.hermes_run_tier = "promoted"
        self.session.hermes_run_id = "run-main"
        self.session.hermes_run_status = "running"

    async def _with_inflight_run(self, coro):
        """Run *coro* while session.hermes_task simulates a detached run."""
        blocker = asyncio.create_task(asyncio.sleep(60))
        self.session.hermes_task = blocker
        try:
            return await coro
        finally:
            blocker.cancel()
            try:
                await blocker
            except asyncio.CancelledError:
                pass

    async def test_quick_second_task_answers_inline(self) -> None:
        broker = FastHermesBroker()
        self.handler.hermes = broker  # type: ignore[assignment]

        blocker = asyncio.create_task(asyncio.sleep(60))
        self.session.hermes_task = blocker
        try:
            result = await self.handler._run_brokered_tool(
                None, self.session, _run_task_call()
            )

            self.assertTrue(result["ok"])
            self.assertEqual("completed", result["status"])
            self.assertTrue(result["fast_lane"])
            self.assertEqual("It is 9pm in Tokyo.", result["answer"])
            self.assertEqual(1, result["tool_count"])
            # Separate ephemeral session: request carried session_id=None ...
            self.assertIsNone(broker.requests[0].session_id)
            # ... and NONE of the in-flight run's state was disturbed:
            # same task object, still running, run metadata untouched.
            self.assertIs(blocker, self.session.hermes_task)
            self.assertFalse(self.session.hermes_task.done())
            self.assertEqual("main-chat-session", self.session.chat_session_id)
            self.assertEqual("run-main", self.session.hermes_run_id)
            self.assertEqual("running", self.session.hermes_run_status)
            self.assertEqual("promoted", self.session.hermes_run_tier)
        finally:
            blocker.cancel()
            try:
                await blocker
            except asyncio.CancelledError:
                pass

    async def test_slow_second_task_falls_back_to_busy(self) -> None:
        broker = SlowHermesBroker()
        self.handler.hermes = broker  # type: ignore[assignment]
        self.session.promote_after_ms = 100  # tiny grace for the test

        result = await self._with_inflight_run(
            self.handler._run_brokered_tool(None, self.session, _run_task_call())
        )

        self.assertEqual("already_running", result["status"])
        self.assertEqual("run-main", result["run_id"])
        # The abandoned fast-lane stream was cancelled client-side.
        await asyncio.wait_for(broker.cancelled.wait(), timeout=2.0)
        log_text = self.session.event_log_path.read_text(encoding="utf-8")
        self.assertIn("voice.hermes_fast_lane.abandoned", log_text)
        self.assertIn("grace_elapsed", log_text)

    async def test_long_tool_start_falls_back_to_busy(self) -> None:
        self.handler.hermes = LongToolHermesBroker()  # type: ignore[assignment]

        result = await self._with_inflight_run(
            self.handler._run_brokered_tool(None, self.session, _run_task_call())
        )

        self.assertEqual("already_running", result["status"])
        log_text = self.session.event_log_path.read_text(encoding="utf-8")
        self.assertIn("long_tool_started", log_text)
        self.assertIn("terminal_execute", log_text)

    async def test_explicit_background_mode_skips_fast_lane(self) -> None:
        broker = FastHermesBroker()
        self.handler.hermes = broker  # type: ignore[assignment]

        result = await self._with_inflight_run(
            self.handler._run_brokered_tool(
                None, self.session, _run_task_call(mode="background")
            )
        )

        self.assertEqual("already_running", result["status"])
        self.assertEqual([], broker.requests)  # never even dispatched

    async def test_promotion_disabled_skips_fast_lane(self) -> None:
        broker = FastHermesBroker()
        self.handler.hermes = broker  # type: ignore[assignment]
        self.session.promotion_enabled = False

        result = await self._with_inflight_run(
            self.handler._run_brokered_tool(None, self.session, _run_task_call())
        )

        self.assertEqual("already_running", result["status"])
        self.assertEqual([], broker.requests)

    async def test_foreground_inflight_run_never_takes_fast_lane(self) -> None:
        # Tier "foreground" (not detached) — the guard requires a
        # promoted/durable run; anything else is the plain busy answer.
        broker = FastHermesBroker()
        self.handler.hermes = broker  # type: ignore[assignment]
        self.session.hermes_run_tier = "foreground"

        result = await self._with_inflight_run(
            self.handler._run_brokered_tool(None, self.session, _run_task_call())
        )

        self.assertEqual("already_running", result["status"])
        self.assertEqual([], broker.requests)

    async def test_fast_lane_hermes_error_is_reported_not_busy(self) -> None:
        self.handler.hermes = ErrorHermesBroker()  # type: ignore[assignment]

        result = await self._with_inflight_run(
            self.handler._run_brokered_tool(None, self.session, _run_task_call())
        )

        self.assertFalse(result["ok"])
        self.assertEqual("gateway unreachable", result["error"])


if __name__ == "__main__":
    unittest.main()
