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
        self.request_response_count = 0
        self.cancelled = False
        self.closed = False
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

    async def request_response(self) -> None:
        self.request_response_count += 1

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

    async def stream_task(self, request: HermesTaskRequest):
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


if __name__ == "__main__":
    unittest.main()
