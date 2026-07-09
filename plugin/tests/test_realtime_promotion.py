"""Tier B grace-period promotion tests for the realtime agent (ADR 33 Phase 2).

These drive the provider-native realtime-agent route with a fake provider
connection and fake Hermes brokers of controllable latency, asserting:

- a run that exceeds promote_after_ms emits hermes.run.promoted, the pump keeps
  processing provider events while the run is still in flight, and the result is
  spoken exactly once after completion;
- a run that completes within the grace window is NOT promoted (Tier A);
- cancelling a promoted run stops the background task and emits run.cancelled;
- a background run that completes while detached replays
  hermes.run.background_completed on resume.
"""

from __future__ import annotations

import asyncio
import json
import os
import tempfile
import unittest
from typing import Any

from aiohttp import WSMsgType, web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay import provider_options, voice_auth
from plugin.relay.config import RelayConfig
from plugin.relay.realtime_agent import broker as broker_module
from plugin.relay.realtime_agent.hermes_tool_broker import HermesTaskRequest
from plugin.relay.realtime_agent.models import (
    ProviderEvent,
    ProviderEventKind,
    ToolCallEvent,
)
from plugin.relay.server import create_app


class FakeNativeConnection:
    def __init__(self) -> None:
        self.text_inputs: list[str] = []
        self.tool_results: list[tuple[str, dict[str, Any]]] = []
        self.context_items: list[tuple[str, str]] = []
        self.exact_response_texts: list[str] = []
        self.request_response_count = 0
        self.cancelled = False
        self.closed = False
        # When set, summary/exact delivery injections (recognizable by their
        # "final spoken answer" instructions) raise — simulates the provider
        # socket dying between run completion and result delivery.
        self.fail_summary_requests = False
        self._events: asyncio.Queue[ProviderEvent | None] = asyncio.Queue()

    async def send_audio(self, pcm: bytes, sample_rate: int) -> None:
        pass

    async def commit_audio(self) -> None:
        pass

    async def send_text(self, text: str) -> None:
        self.text_inputs.append(text)

    async def clear_audio(self) -> None:
        pass

    async def cancel_response(self) -> None:
        self.cancelled = True

    async def send_tool_result(self, call_id: str, output: dict[str, Any]) -> None:
        self.tool_results.append((call_id, output))

    async def append_context_item(self, *, role: str, text: str) -> None:
        self.context_items.append((role, text))

    async def request_response(
        self,
        *,
        instructions: str | None = None,
        exact_text: str | None = None,
    ) -> None:
        if (
            self.fail_summary_requests
            and instructions
            and "final spoken answer" in instructions
        ):
            raise RuntimeError("provider socket dead")
        self.request_response_count += 1
        if exact_text:
            self.exact_response_texts.append(exact_text)
        if instructions:
            # Per-response instructions replace the old fake-user-message
            # injection (send_text) as the summary-delivery mechanism; keep
            # them in text_inputs so existing "what got spoken" assertions
            # don't need to know which transport carried it.
            self.text_inputs.append(instructions)

    async def close(self) -> None:
        self.closed = True
        await self._events.put(None)

    async def emit(self, event: ProviderEvent) -> None:
        await self._events.put(event)

    async def events(self):
        while True:
            event = await self._events.get()
            if event is None:
                return
            yield event


class FakeNativeProvider:
    def __init__(self, provider_id: str = "xai_realtime") -> None:
        self.provider_id = provider_id
        self.connection = FakeNativeConnection()
        self.configs: list[Any] = []

    async def connect(self, config):
        self.configs.append(config)
        return self.connection


class GatedHermesToolBroker:
    """Blocks inside the run until released, then yields a final answer."""

    def __init__(self) -> None:
        self.requests: list[HermesTaskRequest] = []
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.cancelled = asyncio.Event()
        # Which stream_task call (0-based) was cancelled. The fast lane
        # (background-run v2) legitimately opens-and-abandons a SECOND stream
        # while the first run blocks, so orphaning assertions must check the
        # first stream specifically, not "any stream cancelled".
        self.cancelled_indices: list[int] = []

    async def stream_task(self, request: HermesTaskRequest):
        index = len(self.requests)
        self.requests.append(request)
        session_id = request.session_id or "created-hermes-session"
        yield {
            "type": "hermes.run.started",
            "session_id": session_id,
            "run_id": "run-gated",
            "profile": request.profile,
        }
        self.started.set()
        try:
            await self.release.wait()
        except asyncio.CancelledError:
            self.cancelled.set()
            self.cancelled_indices.append(index)
            raise
        yield {
            "type": "voice.response.delta",
            "session_id": session_id,
            "run_id": "run-gated",
            "delta": "Background answer ready.",
        }
        yield {
            "type": "hermes.run.completed",
            "session_id": session_id,
            "run_id": "run-gated",
            "profile": request.profile,
        }


class LongToolHermesBroker(GatedHermesToolBroker):
    """Emits a known-long tool start (cronjob), then blocks until released."""

    async def stream_task(self, request: HermesTaskRequest):
        self.requests.append(request)
        session_id = request.session_id or "created-hermes-session"
        yield {
            "type": "hermes.run.started",
            "session_id": session_id,
            "run_id": "run-longtool",
            "profile": request.profile,
        }
        yield {
            "type": "hermes.tool.started",
            "session_id": session_id,
            "run_id": "run-longtool",
            "tool_call_id": "t-1",
            "tool_name": "cronjob",
        }
        self.started.set()
        try:
            await self.release.wait()
        except asyncio.CancelledError:
            self.cancelled.set()
            raise
        yield {
            "type": "hermes.run.completed",
            "session_id": session_id,
            "run_id": "run-longtool",
            "profile": request.profile,
        }


class FastHermesToolBroker:
    """Completes immediately (Tier A)."""

    def __init__(self) -> None:
        self.requests: list[HermesTaskRequest] = []

    async def stream_task(self, request: HermesTaskRequest):
        self.requests.append(request)
        session_id = request.session_id or "created-hermes-session"
        yield {
            "type": "hermes.run.started",
            "session_id": session_id,
            "run_id": "run-fast",
            "profile": request.profile,
        }
        yield {
            "type": "voice.response.delta",
            "session_id": session_id,
            "run_id": "run-fast",
            "delta": "Quick answer.",
        }
        yield {
            "type": "hermes.run.completed",
            "session_id": session_id,
            "run_id": "run-fast",
            "profile": request.profile,
        }


class RealtimePromotionTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        self._tmpdir = tempfile.TemporaryDirectory()
        self._previous_lead = broker_module._PRE_HERMES_STATUS_LEAD_SECONDS
        broker_module._PRE_HERMES_STATUS_LEAD_SECONDS = 0.0
        voice_auth._VALIDATION_CACHE.clear()
        provider_options.clear_provider_option_cache()
        config = RelayConfig(
            realtime_voice_enabled=True,
            realtime_voice_provider="stub",
            realtime_voice_model="local-tone",
            realtime_voice_voice="sine",
            realtime_voice_promotion_enabled=True,
            realtime_voice_promote_after_ms=50,
            realtime_voice_result_delivery="speak_when_idle",
            realtime_voice_config_path=os.path.join(self._tmpdir.name, "relay-config.yaml"),
            realtime_voice_run_dir=self._tmpdir.name,
        )
        return create_app(config)

    async def tearDownAsync(self) -> None:
        await super().tearDownAsync()
        broker_module._PRE_HERMES_STATUS_LEAD_SECONDS = getattr(
            self, "_previous_lead", broker_module._PRE_HERMES_STATUS_LEAD_SECONDS
        )
        voice_auth._VALIDATION_CACHE.clear()
        provider_options.clear_provider_option_cache()
        tmpdir = getattr(self, "_tmpdir", None)
        if tmpdir is not None:
            tmpdir.cleanup()

    def _server(self):
        return self.app["server"]

    async def _make_session(self) -> str:
        session = self._server().sessions.create_session("test-phone", "test-id")
        return session.token

    @staticmethod
    def _bearer(token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    async def _next_ws_event(self, ws) -> dict[str, Any]:
        msg = await ws.receive(timeout=5)
        self.assertEqual(msg.type, WSMsgType.TEXT, msg)
        payload = json.loads(msg.data)
        self.assertIsInstance(payload, dict)
        return payload

    async def _open(self, *, broker) -> tuple[Any, FakeNativeProvider, dict[str, Any]]:
        token = await self._make_session()
        fake_provider = FakeNativeProvider()
        self._server().realtime_agent.hermes = broker
        self._server().realtime_agent.native_providers["xai_realtime"] = fake_provider
        resp = await self.client.post(
            "/voice/realtime-agent/session",
            json={
                "provider": "xai_realtime",
                "model": "grok-voice-latest",
                "voice": "leo",
                "chat_session_id": "chat-123",
            },
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        ws = await self.client.ws_connect(body["websocket_path"], headers=self._bearer(token))
        ready = await self._next_ws_event(ws)
        self.assertEqual(ready["type"], "voice.session.ready")
        body["_token"] = token
        body["_ready"] = ready
        return ws, fake_provider, body

    async def _emit_tool_call(
        self,
        provider: FakeNativeProvider,
        *,
        call_id="call-1",
        mode: str | None = None,
    ) -> None:
        arguments: dict[str, Any] = {"text": "Research the thing.", "session_id": "chat-123"}
        if mode is not None:
            arguments["mode"] = mode
        await provider.connection.emit(
            ProviderEvent(
                ProviderEventKind.FUNCTION_CALL_COMPLETED,
                response_id="resp-1",
                payload={
                    "call": ToolCallEvent(
                        call_id=call_id,
                        name="hermes_run_task",
                        arguments=arguments,
                    )
                },
            )
        )

    async def _read_until(self, ws, target: str, *, limit: int = 40) -> list[dict[str, Any]]:
        events: list[dict[str, Any]] = []
        for _ in range(limit):
            event = await self._next_ws_event(ws)
            events.append(event)
            if event["type"] == target:
                return events
        raise AssertionError(f"did not see {target}; saw {[e['type'] for e in events]}")

    async def test_long_run_promotes_and_pump_stays_responsive(self) -> None:
        broker = GatedHermesToolBroker()
        ws, provider, _ = await self._open(broker=broker)
        try:
            await self._emit_tool_call(provider)
            events = await self._read_until(ws, "hermes.run.promoted")
            promoted = next(e for e in events if e["type"] == "hermes.run.promoted")
            self.assertEqual(promoted["tier"], "promoted")
            self.assertEqual(promoted["promote_after_ms"], 50)

            # The pending provider call was closed with an interim background ack.
            self.assertTrue(provider.connection.tool_results)
            self.assertEqual(
                provider.connection.tool_results[0][1].get("status"),
                "running_in_background",
            )

            # Prove the pump is NOT blocked: a provider event sent while the run
            # is still gated is processed and forwarded.
            self.assertFalse(broker.release.is_set())
            await provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.INPUT_TRANSCRIPT_DELTA,
                    payload={"delta": "still talking"},
                )
            )
            live = await self._read_until(ws, "voice.input_transcript.delta")
            self.assertTrue(any(e["type"] == "voice.input_transcript.delta" for e in live))

            # Release the run -> it completes in the background and is spoken once.
            broker.release.set()
            done = await self._read_until(ws, "hermes.run.background_completed")
            completed = next(e for e in done if e["type"] == "hermes.run.background_completed")
            self.assertTrue(completed["ok"])

            # The result is injected for the provider to summarize exactly once.
            for _ in range(50):
                summaries = [t for t in provider.connection.text_inputs if "final spoken answer" in t]
                if summaries:
                    break
                await asyncio.sleep(0.02)
            self.assertEqual(len(summaries), 1)
        finally:
            broker.release.set()
            await ws.close()

    async def test_verbatim_delivery_injects_exact_provider_reading(self) -> None:
        """speak_verbatim supplies the provider an exact text delivery hint."""
        broker = GatedHermesToolBroker()
        ws, provider, body = await self._open(broker=broker)
        self._server().realtime_agent.sessions[body["session_id"]].result_delivery = (
            "speak_verbatim"
        )
        try:
            await self._emit_tool_call(provider)
            await self._read_until(ws, "hermes.run.promoted")
            broker.release.set()
            await self._read_until(ws, "hermes.run.background_completed")
            exact: list[str] = []
            for _ in range(50):
                exact = [
                    t
                    for t in provider.connection.text_inputs
                    if "word for word as written" in t
                ]
                if exact:
                    break
                await asyncio.sleep(0.02)
            self.assertEqual(len(exact), 1)
            self.assertIn("Background answer ready.", exact[0])
            self.assertEqual(
                provider.connection.exact_response_texts,
                ["Background answer ready."],
            )
        finally:
            broker.release.set()
            await ws.close()

    async def test_injection_failure_falls_back_to_relay_tts(self) -> None:
        """Provider socket dies between run completion and delivery: the
        answer must still land as spoken relay-TTS audio, not vanish until
        the confirm alarm's text emit."""
        broker = GatedHermesToolBroker()
        ws, provider, body = await self._open(broker=broker)
        session = self._server().realtime_agent.sessions[body["session_id"]]
        provider.connection.fail_summary_requests = True
        try:
            await self._emit_tool_call(provider, call_id="call-1")
            await self._read_until(ws, "hermes.run.promoted")
            await broker.started.wait()
            broker.release.set()
            await self._read_until(ws, "hermes.run.background_completed")
            events = await self._read_until(ws, "voice.response.delta")
            fallback_delta = next(
                e for e in events if e["type"] == "voice.response.delta"
            )
            self.assertEqual("hermes", fallback_delta.get("source"))
            # The delivery tag is what lets the phone's voice overlay render
            # hermes-sourced delivery text (plain hermes run chatter stays
            # suppressed client-side).
            self.assertEqual("fallback", fallback_delta.get("delivery"))
            self.assertIn(
                "Background answer ready", str(fallback_delta.get("delta"))
            )
            log_text = session.event_log_path.read_text(encoding="utf-8")
            self.assertIn("voice.realtime_agent.summary_send_failed", log_text)
            self.assertIn("voice.response.delivery_fallback", log_text)
        finally:
            broker.release.set()
            await ws.close()

    async def test_barge_in_preempts_pending_delivery_as_text(self) -> None:
        """A new user utterance while a delivery is injected-but-unspoken must
        cancel the stale response and land the answer as text — never drop it
        silently (the pre-fix behavior wiped the state with no record)."""
        broker = GatedHermesToolBroker()
        ws, provider, body = await self._open(broker=broker)
        session = self._server().realtime_agent.sessions[body["session_id"]]
        try:
            await self._emit_tool_call(provider, call_id="call-1")
            await self._read_until(ws, "hermes.run.promoted")
            await broker.started.wait()
            broker.release.set()
            await self._read_until(ws, "hermes.run.background_completed")
            for _ in range(100):
                if any(
                    "final spoken answer step" in t
                    for t in provider.connection.text_inputs
                ):
                    break
                await asyncio.sleep(0.02)
            else:
                self.fail("forced-summary injection never reached the provider")

            # User talks over the pending (never-started) delivery.
            await provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.INPUT_TRANSCRIPT_FINAL,
                    payload={"text": "actually tell me a joke instead"},
                )
            )
            events = await self._read_until(ws, "voice.response.done")
            visual = [e for e in events if e.get("delivery") == "visual_only"]
            self.assertTrue(visual, [e["type"] for e in events])
            delta = next(e for e in events if e["type"] == "voice.response.delta")
            self.assertIn("Background answer ready", str(delta.get("delta")))
            log_text = session.event_log_path.read_text(encoding="utf-8")
            self.assertIn("voice.realtime_agent.delivery_preempted", log_text)
        finally:
            broker.release.set()
            await ws.close()

    async def test_short_run_is_not_promoted(self) -> None:
        broker = FastHermesToolBroker()
        ws, provider, body = await self._open(broker=broker)
        # Widen the grace window so the fast run finishes well within it.
        self._server().realtime_agent.sessions[body["session_id"]].promote_after_ms = 5000
        try:
            await self._emit_tool_call(provider)
            events = await self._read_until(ws, "voice.playback_drain.requested")
            types = [e["type"] for e in events]
            self.assertNotIn("hermes.run.promoted", types)
            # Real result delivered to the provider (not an interim background ack).
            self.assertTrue(provider.connection.tool_results)
            self.assertNotEqual(
                provider.connection.tool_results[0][1].get("status"),
                "running_in_background",
            )
        finally:
            await ws.close()

    async def test_explicit_background_mode_promotes_immediately(self) -> None:
        # Tier C: mode="background" detaches immediately, even with grace-period
        # promotion turned off and a long grace window.
        broker = GatedHermesToolBroker()
        ws, provider, body = await self._open(broker=broker)
        session = self._server().realtime_agent.sessions[body["session_id"]]
        session.promotion_enabled = False
        session.promote_after_ms = 60000
        try:
            await self._emit_tool_call(provider, mode="background")
            events = await self._read_until(ws, "hermes.run.promoted")
            promoted = next(e for e in events if e["type"] == "hermes.run.promoted")
            self.assertEqual(promoted["tier"], "durable")

            broker.release.set()
            done = await self._read_until(ws, "hermes.run.background_completed")
            self.assertTrue(
                any(e["type"] == "hermes.run.background_completed" for e in done)
            )
        finally:
            broker.release.set()
            await ws.close()

    async def test_cancel_during_promoted_run(self) -> None:
        broker = GatedHermesToolBroker()
        ws, provider, _ = await self._open(broker=broker)
        try:
            await self._emit_tool_call(provider)
            await self._read_until(ws, "hermes.run.promoted")
            await broker.started.wait()

            await ws.send_json({"type": "response.cancel"})
            cancelled = await self._read_until(ws, "hermes.run.cancelled")
            self.assertTrue(any(e["type"] == "hermes.run.cancelled" for e in cancelled))
            for _ in range(50):
                if broker.cancelled.is_set():
                    break
                await asyncio.sleep(0.02)
            self.assertTrue(broker.cancelled.is_set())
        finally:
            broker.release.set()
            await ws.close()

    async def test_detach_resume_replays_background_completed(self) -> None:
        broker = GatedHermesToolBroker()
        ws, provider, body = await self._open(broker=broker)
        await self._emit_tool_call(provider)
        await self._read_until(ws, "hermes.run.promoted")
        ready = body["_ready"]

        # Detach: close the websocket while the run is still in the background.
        await ws.close(code=1001, message=b"network changed")
        session = self._server().realtime_agent.sessions[body["session_id"]]
        for _ in range(50):
            if session.detached_at is not None:
                break
            await asyncio.sleep(0.01)
        self.assertIsNotNone(session.detached_at)

        # Complete the run while detached -> events recorded to the ring.
        broker.release.set()
        for _ in range(100):
            if any(
                e.get("type") == "hermes.run.background_completed"
                for e in session.event_ring
            ):
                break
            await asyncio.sleep(0.02)

        ws2 = await self.client.ws_connect(
            body["websocket_path"], headers=self._bearer(body["_token"])
        )
        try:
            await ws2.send_json(
                {
                    "type": "session.resume",
                    "resume_token": body["resume_token"],
                    "last_event_id": ready["event_id"],
                    "last_audio_event_id": 0,
                    "last_played_audio_event_id": 0,
                    "last_input_chunk_id": 0,
                }
            )
            events = await self._read_until(ws2, "voice.replay.done", limit=60)
            types = [e["type"] for e in events]
            self.assertIn("voice.session.resumed", types)
            self.assertIn("hermes.run.background_completed", types)
            replayed = next(
                e for e in events if e["type"] == "hermes.run.background_completed"
            )
            self.assertTrue(replayed.get("replayed"))
        finally:
            await ws2.close()

    async def test_detached_background_session_survives_base_ttl(self) -> None:
        # Regression: a durable background run must keep a *detached* session
        # alive well past the base 30s resume window, so a transient drop mid-run
        # doesn't orphan the run and lose the result. Here the base TTL is shrunk
        # to 0.2s; without the background-aware extension the session would be torn
        # down almost immediately and the result never recorded for replay.
        broker = GatedHermesToolBroker()
        ws, provider, body = await self._open(broker=broker)
        session = self._server().realtime_agent.sessions[body["session_id"]]
        session.resume_ttl_seconds = 0.2
        await self._emit_tool_call(provider)
        await self._read_until(ws, "hermes.run.promoted")
        await broker.started.wait()

        # Detach while the run is still gated (in flight).
        await ws.close(code=1001, message=b"network changed")
        for _ in range(50):
            if session.detached_at is not None:
                break
            await asyncio.sleep(0.01)
        self.assertIsNotNone(session.detached_at)

        # Well past the 0.2s base TTL, the session is STILL alive because the run
        # is active, and the resume window was stretched to the background cap.
        await asyncio.sleep(0.6)
        self.assertFalse(session.closed)
        self.assertIsNotNone(session.detached_at)
        self.assertGreater(session.resume_deadline, session.detached_at + 60)

        # Release -> completes while detached -> recorded to the ring for replay.
        broker.release.set()
        recorded = False
        for _ in range(100):
            if any(
                e.get("type") == "hermes.run.background_completed"
                for e in session.event_ring
            ):
                recorded = True
                break
            await asyncio.sleep(0.02)
        self.assertTrue(recorded)
        await ws.close()

    async def test_background_run_times_out(self) -> None:
        # A hung background run (tool never returns) is cancelled and surfaced as a
        # background_completed error instead of pinning the delivery task forever.
        os.environ["RELAY_VOICE_BACKGROUND_RUN_MAX_MS"] = "200"
        broker = GatedHermesToolBroker()  # never released -> hangs
        ws = None
        try:
            ws, provider, body = await self._open(broker=broker)
            await self._emit_tool_call(provider)
            await self._read_until(ws, "hermes.run.promoted")
            done = await self._read_until(ws, "hermes.run.background_completed", limit=60)
            completed = next(
                e for e in done if e["type"] == "hermes.run.background_completed"
            )
            self.assertFalse(completed["ok"])
            self.assertEqual(completed.get("error"), "background run timed out")
            # The hung run was actually cancelled server-side.
            for _ in range(50):
                if broker.cancelled.is_set():
                    break
                await asyncio.sleep(0.02)
            self.assertTrue(broker.cancelled.is_set())
        finally:
            os.environ.pop("RELAY_VOICE_BACKGROUND_RUN_MAX_MS", None)
            broker.release.set()
            if ws is not None:
                await ws.close()

    async def test_detached_result_deferred_and_injected_on_resume(self) -> None:
        # A result that completes while the phone is detached must NOT be spoken
        # into the void (the summary audio would only land in the bounded replay
        # ring). It is held as pending and injected after a successful resume.
        broker = GatedHermesToolBroker()
        ws, provider, body = await self._open(broker=broker)
        await self._emit_tool_call(provider)
        await self._read_until(ws, "hermes.run.promoted")
        ready = body["_ready"]

        await ws.close(code=1001, message=b"network changed")
        session = self._server().realtime_agent.sessions[body["session_id"]]
        for _ in range(50):
            if session.detached_at is not None:
                break
            await asyncio.sleep(0.01)
        self.assertIsNotNone(session.detached_at)

        summaries_before = len(
            [t for t in provider.connection.text_inputs if "final spoken answer" in t]
        )
        broker.release.set()
        for _ in range(100):
            if session.pending_background_result is not None:
                break
            await asyncio.sleep(0.02)
        self.assertIsNotNone(session.pending_background_result)
        # No summary was injected while detached.
        summaries_detached = len(
            [t for t in provider.connection.text_inputs if "final spoken answer" in t]
        )
        self.assertEqual(summaries_before, summaries_detached)

        ws2 = await self.client.ws_connect(
            body["websocket_path"], headers=self._bearer(body["_token"])
        )
        try:
            await ws2.send_json(
                {
                    "type": "session.resume",
                    "resume_token": body["resume_token"],
                    "last_event_id": ready["event_id"],
                    "last_audio_event_id": 0,
                    "last_played_audio_event_id": 0,
                    "last_input_chunk_id": 0,
                }
            )
            await self._read_until(ws2, "voice.replay.done", limit=60)
            # The deferred result is injected after resume.
            for _ in range(100):
                summaries = [
                    t
                    for t in provider.connection.text_inputs
                    if "final spoken answer" in t
                ]
                if len(summaries) > summaries_before:
                    break
                await asyncio.sleep(0.02)
            self.assertEqual(len(summaries), summaries_before + 1)
            self.assertIsNone(session.pending_background_result)
        finally:
            await ws2.close()

    async def test_second_run_task_answers_busy_without_orphaning_first(self) -> None:
        # max_background_runs=1: a second hermes_run_task must return a speakable
        # already_running result — NOT overwrite hermes_task (which would cancel
        # the first run's delivery and orphan it).
        broker = GatedHermesToolBroker()
        ws, provider, _ = await self._open(broker=broker)
        try:
            await self._emit_tool_call(provider, call_id="call-1")
            await self._read_until(ws, "hermes.run.promoted")
            await broker.started.wait()

            await self._emit_tool_call(provider, call_id="call-2")
            for _ in range(100):
                busy = [
                    (cid, result)
                    for cid, result in provider.connection.tool_results
                    if cid == "call-2"
                ]
                if busy:
                    break
                await asyncio.sleep(0.02)
            self.assertTrue(busy)
            # Queue contract (background-run v2 §2): the long second ask is
            # QUEUED behind the running task instead of refused.
            self.assertEqual(busy[0][1].get("status"), "queued")
            self.assertEqual(busy[0][1].get("queue_position"), 1)
            # First run untouched: ITS stream (call index 0) was never
            # cancelled, so it stays deliverable. The fast-lane attempt for
            # call-2 (index 1) is expected to be opened and abandoned —
            # that cancellation is the designed fall-through to the queue.
            self.assertNotIn(0, broker.cancelled_indices)

            broker.release.set()
            done = await self._read_until(ws, "hermes.run.background_completed")
            self.assertTrue(
                any(e["type"] == "hermes.run.background_completed" for e in done)
            )
        finally:
            broker.release.set()
            await ws.close()

    async def test_filler_summary_triggers_fallback_delivery(self) -> None:
        # E2E misbehaving provider: the background run completes with a real
        # answer, but the provider responds to the forced-summary request with
        # deferral filler instead of delivering it. The validator must catch
        # it and the fallback must carry the actual answer — the class of
        # failure where a completed result was silently lost behind "one
        # moment while I look that up".
        broker = GatedHermesToolBroker()
        ws, provider, body = await self._open(broker=broker)
        session = self._server().realtime_agent.sessions[body["session_id"]]
        try:
            await self._emit_tool_call(provider, call_id="call-1")
            await self._read_until(ws, "hermes.run.promoted")
            await broker.started.wait()
            broker.release.set()
            await self._read_until(ws, "hermes.run.background_completed")

            # Wait for the delivery to inject the forced-summary request.
            for _ in range(100):
                if any(
                    "final spoken answer step" in t
                    for t in provider.connection.text_inputs
                ):
                    break
                await asyncio.sleep(0.02)
            else:
                self.fail("forced-summary injection never reached the provider")

            # Provider misbehaves: speaks filler instead of the answer.
            await provider.connection.emit(
                ProviderEvent(ProviderEventKind.RESPONSE_STARTED, response_id="sum-1")
            )
            await provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.OUTPUT_TEXT_DELTA,
                    response_id="sum-1",
                    payload={"delta": "One moment while I look that up."},
                )
            )
            await provider.connection.emit(
                ProviderEvent(ProviderEventKind.RESPONSE_DONE, response_id="sum-1")
            )

            # The validator flags the filler and the fallback delta carries
            # the real answer over the ws (the fallback marker itself is a
            # session-log event, not a ws event).
            events = await self._read_until(ws, "voice.response.delta")
            fallback_delta = next(
                e for e in events if e["type"] == "voice.response.delta"
            )
            self.assertEqual("hermes", fallback_delta.get("source"))
            self.assertEqual("fallback", fallback_delta.get("delivery"))
            self.assertIn("Background answer ready", str(fallback_delta.get("delta")))
            # The buffered filler was dropped — it never reached the client.
            filler_deltas = [
                e for e in events
                if "One moment" in str(e.get("delta") or "")
            ]
            self.assertEqual([], filler_deltas)
            log_text = session.event_log_path.read_text(encoding="utf-8")
            self.assertIn("voice.response.forced_summary_fallback", log_text)
            self.assertIn("acknowledgement_not_summary", log_text)
            # The provider never saw the fallback delivery — a next-turn
            # correction note must be pending so the model can't claim the
            # task is still running (observed live).
            self.assertIsNotNone(session.native_pending_delivery_note)
            self.assertIn(
                "Background answer ready",
                session.native_pending_delivery_note,
            )
            self.assertIn("ALREADY COMPLETED", session.native_pending_delivery_note)
            # Durable seed: the delivered answer is now in the provider's own
            # conversation history as an assistant turn, so a follow-up
            # ("what did that say?") is answerable from context without a
            # re-run — unlike the one-shot pending note above, which only lands
            # on the next Hermes-routed turn.
            seeded = [
                text
                for role, text in provider.connection.context_items
                if role == "assistant"
            ]
            self.assertTrue(
                any("Background answer ready" in t for t in seeded),
                f"expected a seeded assistant history item; got "
                f"{provider.connection.context_items}",
            )
            self.assertIn("voice.realtime_agent.result_seeded", log_text)
        finally:
            broker.release.set()
            await ws.close()

    async def test_long_tool_start_promotes_before_grace_window(self) -> None:
        # Adaptive promotion: a known-long tool (cronjob) starting mid-grace
        # promotes immediately instead of waiting out the full window.
        broker = LongToolHermesBroker()
        ws, provider, body = await self._open(broker=broker)
        session = self._server().realtime_agent.sessions[body["session_id"]]
        session.promote_after_ms = 8000  # would exceed the 5s event-read timeout
        try:
            started = asyncio.get_event_loop().time()
            await self._emit_tool_call(provider)
            events = await self._read_until(ws, "hermes.run.promoted")
            elapsed = asyncio.get_event_loop().time() - started
            promoted = next(e for e in events if e["type"] == "hermes.run.promoted")
            self.assertEqual(promoted["tier"], "promoted")
            # Promoted well before the 8s grace window could have elapsed.
            self.assertLess(elapsed, 5.0)
        finally:
            broker.release.set()
            await ws.close()

    async def test_config_defaults_timer_spoken_progress_off(self) -> None:
        # Milestone speech: periodic timer-driven spoken filler defaults OFF.
        token = await self._make_session()
        resp = await self.client.get(
            "/voice/realtime-agent/config", headers=self._bearer(token)
        )
        self.assertEqual(resp.status, 200)
        payload = await resp.json()
        self.assertEqual(payload["promotion"]["progress_spoken_after_ms"], 0)

    async def test_benign_provider_error_is_not_forwarded_as_voice_error(self) -> None:
        # A benign provider notice (cancelling with no active response) must not
        # reach the client as a fatal voice.error — the client closes the session
        # on voice.error, which killed a live turn right as the reply arrived. A
        # genuinely fatal error still surfaces.
        broker = GatedHermesToolBroker()
        ws, provider, _ = await self._open(broker=broker)
        try:
            await provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.ERROR,
                    payload={
                        "message": "xAI Realtime error: Cancellation failed: no active response found"
                    },
                )
            )
            await provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.ERROR,
                    payload={"message": "xAI Realtime error: provider exploded"},
                )
            )
            events = await self._read_until(ws, "voice.error", limit=20)
            errors = [e for e in events if e["type"] == "voice.error"]
            # Only the fatal error is delivered; the benign cancel notice was
            # filtered (and the client never sees a session-killing voice.error).
            self.assertEqual(len(errors), 1)
            self.assertIn("provider exploded", errors[0]["message"])
            self.assertNotIn("no active response", errors[0]["message"])
        finally:
            broker.release.set()
            await ws.close()


if __name__ == "__main__":
    unittest.main()
