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

    async def test_slow_second_task_falls_back_to_queued(self) -> None:
        broker = SlowHermesBroker()
        self.handler.hermes = broker  # type: ignore[assignment]
        self.session.promote_after_ms = 100  # tiny grace for the test

        result = await self._with_inflight_run(
            self.handler._run_brokered_tool(None, self.session, _run_task_call())
        )

        self.assertEqual("queued", result["status"])
        self.assertEqual(1, result["queue_position"])
        self.assertEqual(1, len(self.session.background_queue))
        self.assertEqual(
            "What time is it in Tokyo?",
            self.session.background_queue[0]["text"],
        )
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

        self.assertEqual("queued", result["status"])
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

        self.assertEqual("queued", result["status"])
        self.assertEqual([], broker.requests)  # fast lane never dispatched

    async def test_promotion_disabled_skips_fast_lane(self) -> None:
        broker = FastHermesBroker()
        self.handler.hermes = broker  # type: ignore[assignment]
        self.session.promotion_enabled = False

        result = await self._with_inflight_run(
            self.handler._run_brokered_tool(None, self.session, _run_task_call())
        )

        self.assertEqual("queued", result["status"])
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

        self.assertEqual("queued", result["status"])
        self.assertEqual([], broker.requests)

    async def test_fast_lane_hermes_error_is_reported_not_busy(self) -> None:
        self.handler.hermes = ErrorHermesBroker()  # type: ignore[assignment]

        result = await self._with_inflight_run(
            self.handler._run_brokered_tool(None, self.session, _run_task_call())
        )

        self.assertFalse(result["ok"])
        self.assertEqual("gateway unreachable", result["error"])

    # --- Queue (background-run v2 §2) ---

    async def test_full_queue_answers_busy(self) -> None:
        broker = FastHermesBroker()
        self.handler.hermes = broker  # type: ignore[assignment]
        self.session.hermes_run_tier = "foreground"  # skip fast lane
        for i in range(3):  # _BACKGROUND_QUEUE_MAX
            self.session.background_queue.append({"text": f"task {i}", "profile": None})

        result = await self._with_inflight_run(
            self.handler._run_brokered_tool(None, self.session, _run_task_call())
        )

        self.assertEqual("already_running", result["status"])
        self.assertEqual(3, len(self.session.background_queue))

    async def test_cancel_clears_queue(self) -> None:
        self.session.background_queue.append({"text": "queued thing", "profile": None})
        self.handler._cancel_active_hermes(self.session)
        self.assertEqual(0, len(self.session.background_queue))

    async def test_fast_lane_reuses_side_session(self) -> None:
        broker = FastHermesBroker()
        self.handler.hermes = broker  # type: ignore[assignment]

        await self._with_inflight_run(
            self.handler._run_brokered_tool(None, self.session, _run_task_call())
        )
        # First ask created + bound the side session ...
        self.assertEqual("ephemeral-1", self.session.fast_lane_session_id)

        await self._with_inflight_run(
            self.handler._run_brokered_tool(None, self.session, _run_task_call())
        )
        # ... and the second ask reused it instead of creating another.
        self.assertIsNone(broker.requests[0].session_id)
        self.assertEqual("ephemeral-1", broker.requests[1].session_id)

    async def test_start_next_queued_run_starts_and_delivers(self) -> None:
        broker = FastHermesBroker()
        self.handler.hermes = broker  # type: ignore[assignment]
        self.session.background_queue.append(
            {"text": "queued follow-up", "profile": None}
        )
        # Slot free, no forced summary in flight.
        self.session.hermes_task = None
        self.session.native_forced_summary_active = False

        class NoopConnection:
            async def request_response(self, *, instructions=None):
                self.instructions = instructions

        connection = NoopConnection()
        await self.handler._start_next_queued_run(None, self.session, connection)  # type: ignore[arg-type]

        self.assertEqual(0, len(self.session.background_queue))
        self.assertEqual("durable", self.session.hermes_run_tier)
        self.assertIsNotNone(self.session.hermes_task)
        # Let the spawned run settle, then assert what it dispatched.
        await asyncio.wait_for(self.session.hermes_task, timeout=5.0)
        self.assertEqual("queued follow-up", broker.requests[0].text)
        log_text = self.session.event_log_path.read_text(encoding="utf-8")
        self.assertIn("voice.hermes_run.queued_started", log_text)
        if self.session.background_delivery_task is not None:
            self.session.background_delivery_task.cancel()
            try:
                await self.session.background_delivery_task
            except asyncio.CancelledError:
                pass


class DeliveryInputQuietGateTest(unittest.IsolatedAsyncioTestCase):
    """Result delivery must hold while the user is mid-utterance."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.handler = RealtimeAgentHandler(RelayConfig())
        self.session = _make_session(self._tmp.name)

    async def test_holds_while_input_audio_is_fresh(self) -> None:
        self.session.floor.note_result_ready()
        self.session.native_last_input_audio_at = time.monotonic()  # speaking NOW
        ok = await self.handler._await_floor_idle_for_result(
            self.session, timeout=0.3
        )
        # Bounded wait expires without consuming the floor — delivery
        # proceeds only via the timed-out path, never over fresh speech.
        self.assertFalse(ok)

    async def test_proceeds_when_input_quiet(self) -> None:
        self.session.floor.note_result_ready()
        self.session.native_last_input_audio_at = time.monotonic() - 10.0
        ok = await self.handler._await_floor_idle_for_result(
            self.session, timeout=1.0
        )
        self.assertTrue(ok)


if __name__ == "__main__":
    unittest.main()
