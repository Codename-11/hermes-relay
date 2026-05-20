"""Tests for the experimental /voice/realtime-agent relay route."""

from __future__ import annotations

import base64
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
from plugin.relay.realtime_agent.hermes_tool_broker import (
    HermesTaskRequest,
    _interface_system_message,
)
from plugin.relay.realtime_agent.models import ProviderEvent, ProviderEventKind, ToolCallEvent
from plugin.relay.server import create_app


class FakeHermesToolBroker:
    def __init__(self) -> None:
        self.requests: list[HermesTaskRequest] = []

    async def stream_task(self, request: HermesTaskRequest):
        self.requests.append(request)
        session_id = request.session_id or "created-hermes-session"
        if request.session_id is None:
            yield {
                "type": "hermes.session.bound",
                "session_id": session_id,
                "profile": request.profile,
            }
        yield {
            "type": "hermes.run.started",
            "session_id": session_id,
            "run_id": "run-1",
            "profile": request.profile,
            "tool_surface": [
                "hermes_run_task",
                "hermes_get_status",
                "hermes_cancel",
                "hermes_confirm",
            ],
        }
        yield {
            "type": "hermes.tool.started",
            "session_id": session_id,
            "tool_call_id": "tool-1",
            "tool_name": "desktop_search",
        }
        yield {
            "type": "voice.response.delta",
            "session_id": session_id,
            "run_id": "run-1",
            "delta": "Sure, I checked that.",
        }
        yield {
            "type": "hermes.tool.completed",
            "session_id": session_id,
            "tool_call_id": "tool-1",
            "tool_name": "desktop_search",
            "result_preview": "ok",
            "success": True,
        }
        yield {
            "type": "hermes.run.completed",
            "session_id": session_id,
            "run_id": "run-1",
            "profile": request.profile,
        }


class BlockingHermesToolBroker:
    def __init__(self) -> None:
        self.requests: list[HermesTaskRequest] = []
        self.started = asyncio.Event()
        self.cancelled = asyncio.Event()
        self.release = asyncio.Event()

    async def stream_task(self, request: HermesTaskRequest):
        self.requests.append(request)
        session_id = request.session_id or "created-hermes-session"
        yield {
            "type": "hermes.run.started",
            "session_id": session_id,
            "run_id": "run-blocked",
            "profile": request.profile,
        }
        self.started.set()
        try:
            await self.release.wait()
        finally:
            self.cancelled.set()


class FakeNativeConnection:
    def __init__(self) -> None:
        self.audio_chunks: list[tuple[bytes, int]] = []
        self.tool_results: list[tuple[str, dict[str, Any]]] = []
        self.request_response_count = 0
        self.clear_count = 0
        self.cancelled = False
        self.closed = False
        self._events: asyncio.Queue[ProviderEvent | None] = asyncio.Queue()

    async def send_audio(self, pcm: bytes, sample_rate: int) -> None:
        self.audio_chunks.append((pcm, sample_rate))

    async def commit_audio(self) -> None:
        pass

    async def clear_audio(self) -> None:
        self.clear_count += 1

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

    async def finish(self) -> None:
        await self._events.put(None)

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
        self.configs = []

    async def connect(self, config):
        self.configs.append(config)
        return self.connection


class HermesToolBrokerInterfaceContextTests(unittest.TestCase):
    def test_interface_context_system_message_describes_active_path(self) -> None:
        system_message = _interface_system_message(
            {
                "engine": "realtime_agent",
                "engine_label": "Realtime Agent",
                "stable_engine_label": "Hermes chat + voice output",
                "provider": "xai_realtime",
                "model": "grok-voice-latest",
                "voice": "leo",
                "profile": "victor",
                "path_summary": "Android mic PCM -> relay provider WebSocket -> Android PCM playback",
                "current_date": "2026-05-20",
                "current_time": "11:45:00",
                "current_timezone": "EDT (UTC-04:00)",
            }
        )

        self.assertIsNotNone(system_message)
        assert system_message is not None
        self.assertIn("Realtime Agent (realtime_agent)", system_message)
        self.assertIn("provider=xai_realtime", system_message)
        self.assertIn("not the stable Hermes chat + voice output", system_message)
        self.assertIn("Current relay date/time: 2026-05-20 11:45:00 EDT (UTC-04:00)", system_message)
        self.assertIn("today's date or current time", system_message)
        self.assertIn("research, current facts, news, external data", system_message)
        self.assertIn("latest/versioned info", system_message)
        self.assertIn("media/files/screenshots/attachments/artifacts", system_message)
        self.assertIn("speech-safe summaries", system_message)
        self.assertIn("paths, URLs, IDs, JSON, logs, tables", system_message)


class RealtimeAgentRoutesTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        self._tmpdir = tempfile.TemporaryDirectory()
        voice_auth._VALIDATION_CACHE.clear()
        provider_options.clear_provider_option_cache()
        config = RelayConfig(
            realtime_voice_enabled=True,
            realtime_voice_provider="stub",
            realtime_voice_model="local-tone",
            realtime_voice_voice="sine",
            realtime_voice_config_path=os.path.join(
                self._tmpdir.name,
                "relay-config.yaml",
            ),
            realtime_voice_run_dir=self._tmpdir.name,
        )
        return create_app(config)

    async def tearDownAsync(self) -> None:
        await super().tearDownAsync()
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

    async def test_realtime_agent_config_requires_auth(self) -> None:
        resp = await self.client.get("/voice/realtime-agent/config")
        self.assertEqual(resp.status, 401)

    async def test_realtime_agent_config_reports_experimental_broker(self) -> None:
        token = await self._make_session()

        resp = await self.client.get(
            "/voice/realtime-agent/config",
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertTrue(body["experimental"])
        self.assertEqual(body["mode"], "realtime_agent")
        self.assertEqual(body["stable_engine"], "hermes_voice_output")
        self.assertEqual(body["default_provider"], "stub")
        self.assertEqual(
            body["tool_surface"],
            ["hermes_run_task", "hermes_get_status", "hermes_cancel", "hermes_confirm"],
        )
        providers = {provider["id"]: provider for provider in body["providers"]}
        self.assertTrue(providers["xai_realtime"]["supports_realtime_agent_native"])
        self.assertTrue(providers["openai_realtime"]["supports_realtime_agent_native"])
        self.assertTrue(providers["openai_realtime"]["supports_tool_use"])
        self.assertFalse(providers["openai_tts"]["supports_realtime_agent_native"])

    async def test_realtime_agent_rejects_render_only_provider_sessions(self) -> None:
        token = await self._make_session()

        resp = await self.client.post(
            "/voice/realtime-agent/session",
            json={
                "provider": "openai_tts",
                "model": "gpt-4o-mini-tts",
                "voice": "alloy",
            },
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 400)
        self.assertIn(
            "not a native realtime-agent provider",
            await resp.text(),
        )

    async def test_realtime_agent_session_binds_profile_and_chat_session(self) -> None:
        token = await self._make_session()

        resp = await self.client.post(
            "/voice/realtime-agent/session",
            json={"profile": "mizuki", "chat_session_id": "chat-123"},
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertEqual(body["protocol"], "hermes.voice.realtime_agent.v0")
        self.assertEqual(body["websocket_path"].split("/")[1:3], ["voice", "realtime-agent"])
        self.assertEqual(body["profile"], "mizuki")
        self.assertEqual(body["chat_session_id"], "chat-123")
        self.assertTrue(body["experimental"])

    async def test_realtime_agent_relay_session_uses_server_hermes_key_for_broker(
        self,
    ) -> None:
        hermes_dir = os.path.join(self._tmpdir.name, "hermes")
        os.makedirs(hermes_dir, exist_ok=True)
        hermes_config_path = os.path.join(hermes_dir, "config.yaml")
        with open(hermes_config_path, "w", encoding="utf-8") as fh:
            fh.write(
                "platforms:\n"
                "  api_server:\n"
                "    extra:\n"
                "      key: server-api-key\n"
            )
        self._server().config.hermes_config_path = hermes_config_path
        token = await self._make_session()

        resp = await self.client.post(
            "/voice/realtime-agent/session",
            json={"provider": "stub", "model": "local-tone", "voice": "sine"},
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        session = self._server().realtime_agent.sessions[body["session_id"]]
        self.assertEqual(session.auth_kind, "relay_session")
        self.assertEqual(session.bearer_token, "server-api-key")

    async def test_realtime_agent_websocket_streams_hermes_tool_state_and_pcm(self) -> None:
        token = await self._make_session()
        fake_broker = FakeHermesToolBroker()
        self._server().realtime_agent.hermes = fake_broker
        resp = await self.client.post(
            "/voice/realtime-agent/session",
            json={"provider": "stub", "model": "local-tone", "voice": "sine"},
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()

        ws = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready = await self._next_ws_event(ws)
            self.assertEqual(ready["type"], "voice.session.ready")
            self.assertEqual(ready["interface"]["engine"], "realtime_agent")
            self.assertEqual(ready["interface"]["provider"], "stub")
            self.assertEqual(ready["mode"], "realtime_agent")

            await ws.send_json(
                {
                    "type": "input_audio.append",
                    "sample_rate": 16000,
                    "audio_base64": base64.b64encode(b"\0" * 320).decode("ascii"),
                }
            )
            audio_received = await self._next_ws_event(ws)
            self.assertEqual(audio_received["type"], "voice.input_audio.received")

            await ws.send_json(
                {
                    "type": "input_audio.commit",
                    "text": "Please check the current relay status.",
                }
            )

            events: list[dict[str, Any]] = []
            for _ in range(40):
                event = await self._next_ws_event(ws)
                events.append(event)
                if event["type"] == "voice.response.done":
                    break

            event_types = [event["type"] for event in events]
            self.assertIn("voice.input_transcript.final", event_types)
            self.assertIn("hermes.run.started", event_types)
            self.assertIn("hermes.tool.started", event_types)
            self.assertIn("voice.response.delta", event_types)
            self.assertIn("hermes.tool.completed", event_types)
            self.assertIn("voice.output_audio.delta", event_types)
            self.assertIn("voice.output_audio.done", event_types)
            self.assertIn("voice.response.done", event_types)
            self.assertEqual(fake_broker.requests[0].text, "Please check the current relay status.")
            self.assertEqual(fake_broker.requests[0].interface_context["engine"], "realtime_agent")
            self.assertEqual(fake_broker.requests[0].interface_context["provider"], "stub")
            done = next(event for event in events if event["type"] == "voice.response.done")
            self.assertEqual(done["provider"], "stub")
            self.assertTrue(os.path.isfile(done["audio_path"]))
            self.assertTrue(os.path.isfile(done["event_log_path"]))
        finally:
            await ws.close()

    async def test_provider_native_xai_streams_audio_without_client_transcript(self) -> None:
        token = await self._make_session()
        fake_provider = FakeNativeProvider()
        self._server().realtime_agent.native_providers["xai_realtime"] = fake_provider
        resp = await self.client.post(
            "/voice/realtime-agent/session",
            json={
                "provider": "xai_realtime",
                "model": "grok-voice-latest",
                "voice": "leo",
            },
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()

        ws = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready = await self._next_ws_event(ws)
            self.assertEqual(ready["type"], "voice.session.ready")
            self.assertEqual(fake_provider.configs[0].voice, "leo")
            self.assertEqual(fake_provider.configs[0].sample_rate, 24000)
            instructions = fake_provider.configs[0].instructions
            self.assertIn("Realtime Agent (realtime_agent)", instructions)
            self.assertIn("provider=xai_realtime", instructions)
            self.assertIn("not the stable Hermes chat + voice output", instructions)
            self.assertIn("active realtime provider owns speech recognition", instructions)
            self.assertIn("Current relay date/time:", instructions)
            self.assertIn("do not infer it from model training data", instructions)
            self.assertIn("research, checks, current facts, news, external data", instructions)
            self.assertIn("call hermes_run_task instead of answering directly", instructions)
            self.assertIn("latest/recent/versioned data", instructions)
            self.assertIn("device/desktop/app state", instructions)
            self.assertIn("explicit check/verify/look-up requests", instructions)
            self.assertIn("media, files, screenshots, attachments, or artifacts", instructions)
            self.assertIn("Answer directly only for small talk", instructions)
            self.assertIn("Format speech for listening", instructions)
            self.assertIn("dates, times, currency, percentages, versions", instructions)
            self.assertIn("Summarize long IDs, hashes, UUIDs, URLs, file paths", instructions)
            self.assertIn("plus a few IDs and raw values", instructions)
            self.assertNotIn("xAI owns", instructions)

            pcm = b"\1\0" * 80
            await ws.send_json(
                {
                    "type": "input_audio.append",
                    "sample_rate": 16000,
                    "audio_base64": base64.b64encode(pcm).decode("ascii"),
                }
            )
            audio_received = await self._next_ws_event(ws)
            self.assertEqual(audio_received["type"], "voice.input_audio.received")
            self.assertEqual(fake_provider.connection.audio_chunks, [(pcm, 16000)])

            await ws.send_json({"type": "input_audio.commit"})
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.INPUT_TRANSCRIPT_FINAL,
                    payload={"text": "check relay status"},
                )
            )
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.AUDIO_DELTA,
                    payload={"audio": b"\2\0" * 120},
                )
            )
            await fake_provider.connection.emit(
                ProviderEvent(ProviderEventKind.AUDIO_DONE)
            )
            await fake_provider.connection.emit(
                ProviderEvent(ProviderEventKind.RESPONSE_DONE, response_id="resp-1")
            )

            events: list[dict[str, Any]] = []
            for _ in range(10):
                event = await self._next_ws_event(ws)
                events.append(event)
                if event["type"] == "voice.response.done":
                    break

            event_types = [event["type"] for event in events]
            self.assertIn("voice.input_transcript.final", event_types)
            self.assertIn("voice.output_audio.delta", event_types)
            self.assertIn("voice.output_audio.done", event_types)
            self.assertIn("voice.response.done", event_types)
        finally:
            await ws.close()

    async def test_provider_native_openai_streams_audio_without_client_transcript(self) -> None:
        token = await self._make_session()
        fake_provider = FakeNativeProvider(provider_id="openai_realtime")
        self._server().realtime_agent.native_providers["openai_realtime"] = fake_provider
        resp = await self.client.post(
            "/voice/realtime-agent/session",
            json={
                "provider": "openai_realtime",
                "model": "gpt-realtime-2",
                "voice": "marin",
            },
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()

        ws = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready = await self._next_ws_event(ws)
            self.assertEqual(ready["type"], "voice.session.ready")
            self.assertEqual(ready["provider"], "openai_realtime")
            self.assertEqual(fake_provider.configs[0].provider, "openai_realtime")
            self.assertEqual(fake_provider.configs[0].model, "gpt-realtime-2")
            self.assertEqual(fake_provider.configs[0].voice, "marin")
            instructions = fake_provider.configs[0].instructions
            self.assertIn("provider=openai_realtime", instructions)
            self.assertIn("active realtime provider owns speech recognition", instructions)
            self.assertIn("call hermes_run_task instead of answering directly", instructions)

            pcm = b"\1\0" * 80
            await ws.send_json(
                {
                    "type": "input_audio.append",
                    "sample_rate": 16000,
                    "audio_base64": base64.b64encode(pcm).decode("ascii"),
                }
            )
            audio_received = await self._next_ws_event(ws)
            self.assertEqual(audio_received["type"], "voice.input_audio.received")
            self.assertEqual(fake_provider.connection.audio_chunks, [(pcm, 16000)])

            await ws.send_json({"type": "input_audio.commit"})
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.INPUT_TRANSCRIPT_FINAL,
                    payload={"text": "check current version"},
                )
            )
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.RESPONSE_DONE,
                    response_id="resp-openai-direct",
                )
            )

            events: list[dict[str, Any]] = []
            for _ in range(10):
                event = await self._next_ws_event(ws)
                events.append(event)
                if event["type"] == "voice.response.done":
                    break

            event_types = [event["type"] for event in events]
            self.assertIn("voice.input_transcript.final", event_types)
            self.assertIn("voice.response.done", event_types)
            self.assertEqual(events[-1]["provider"], "openai_realtime")
        finally:
            await ws.close()

    async def test_provider_native_tool_result_waits_for_playback_drain(self) -> None:
        token = await self._make_session()
        fake_broker = FakeHermesToolBroker()
        fake_provider = FakeNativeProvider()
        self._server().realtime_agent.hermes = fake_broker
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

        ws = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready = await self._next_ws_event(ws)
            self.assertEqual(ready["type"], "voice.session.ready")
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.FUNCTION_CALL_COMPLETED,
                    payload={
                        "call": ToolCallEvent(
                            call_id="call-1",
                            name="hermes_run_task",
                            arguments={
                                "text": "Please check the relay status.",
                                "session_id": "chat-123",
                                "profile": "victor",
                            },
                        )
                    },
                )
            )

            events: list[dict[str, Any]] = []
            for _ in range(20):
                event = await self._next_ws_event(ws)
                events.append(event)
                if event["type"] == "voice.playback_drain.requested":
                    break

            event_types = [event["type"] for event in events]
            self.assertIn("hermes.run.started", event_types)
            self.assertIn("voice.response.delta", event_types)
            self.assertIn("hermes.run.completed", event_types)
            self.assertEqual(fake_broker.requests[0].text, "Please check the relay status.")
            self.assertEqual(fake_broker.requests[0].profile, "victor")
            self.assertEqual(fake_broker.requests[0].interface_context["engine"], "realtime_agent")
            self.assertEqual(fake_broker.requests[0].interface_context["provider"], "xai_realtime")
            self.assertRegex(
                fake_broker.requests[0].interface_context["current_date"],
                r"^\d{4}-\d{2}-\d{2}$",
            )
            self.assertEqual(fake_provider.connection.tool_results[0][0], "call-1")
            self.assertTrue(fake_provider.connection.tool_results[0][1]["ok"])
            self.assertEqual(
                fake_provider.connection.tool_results[0][1]["interface"]["engine"],
                "realtime_agent",
            )

            await ws.send_json({"type": "playback.drained", "call_id": "call-1"})
            for _ in range(20):
                if fake_provider.connection.request_response_count:
                    break
                await asyncio.sleep(0.01)
            self.assertEqual(fake_provider.connection.request_response_count, 1)
        finally:
            await ws.close()

    async def test_provider_native_suppresses_tool_response_done_until_followup(
        self,
    ) -> None:
        token = await self._make_session()
        fake_broker = FakeHermesToolBroker()
        fake_provider = FakeNativeProvider()
        self._server().realtime_agent.hermes = fake_broker
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

        ws = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready = await self._next_ws_event(ws)
            self.assertEqual(ready["type"], "voice.session.ready")
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.FUNCTION_CALL_COMPLETED,
                    response_id="resp-initial",
                    payload={
                        "call": ToolCallEvent(
                            call_id="call-1",
                            name="hermes_run_task",
                            arguments={
                                "text": "Please check the relay status.",
                                "session_id": "chat-123",
                            },
                        )
                    },
                )
            )

            for _ in range(20):
                event = await self._next_ws_event(ws)
                if event["type"] == "voice.playback_drain.requested":
                    break
            else:
                self.fail("expected playback drain request")

            await fake_provider.connection.emit(
                ProviderEvent(ProviderEventKind.RESPONSE_DONE, response_id="resp-initial")
            )
            await ws.send_json({"type": "playback.drained", "call_id": "call-1"})
            for _ in range(20):
                if fake_provider.connection.request_response_count:
                    break
                await asyncio.sleep(0.01)
            self.assertEqual(fake_provider.connection.request_response_count, 1)

            await fake_provider.connection.emit(
                ProviderEvent(ProviderEventKind.RESPONSE_STARTED, response_id="resp-followup")
            )
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.AUDIO_DELTA,
                    response_id="resp-followup",
                    payload={"audio": b"\3\0" * 80},
                )
            )
            await fake_provider.connection.emit(
                ProviderEvent(ProviderEventKind.AUDIO_DONE, response_id="resp-followup")
            )
            await fake_provider.connection.emit(
                ProviderEvent(ProviderEventKind.RESPONSE_DONE, response_id="resp-followup")
            )

            events: list[dict[str, Any]] = []
            for _ in range(10):
                event = await self._next_ws_event(ws)
                events.append(event)
                if event["type"] == "voice.response.done":
                    break

            done_events = [
                event for event in events if event["type"] == "voice.response.done"
            ]
            self.assertEqual(len(done_events), 1)
            self.assertEqual(done_events[0]["response_id"], "resp-followup")
        finally:
            await ws.close()

    async def test_provider_native_suppresses_status_response_done_until_followup(
        self,
    ) -> None:
        token = await self._make_session()
        fake_provider = FakeNativeProvider()
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

        ws = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready = await self._next_ws_event(ws)
            self.assertEqual(ready["type"], "voice.session.ready")
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.FUNCTION_CALL_COMPLETED,
                    response_id="resp-status-call",
                    payload={
                        "call": ToolCallEvent(
                            call_id="status-1",
                            name="hermes_get_status",
                            arguments={"run_id": "run-1"},
                        )
                    },
                )
            )

            for _ in range(20):
                event = await self._next_ws_event(ws)
                if event["type"] == "voice.playback_drain.requested":
                    break
            else:
                self.fail("expected playback drain request")

            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.RESPONSE_DONE,
                    response_id="resp-status-call",
                )
            )
            await ws.send_json({"type": "playback.drained", "call_id": "status-1"})
            for _ in range(20):
                if fake_provider.connection.request_response_count:
                    break
                await asyncio.sleep(0.01)
            self.assertEqual(fake_provider.connection.request_response_count, 1)

            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.RESPONSE_STARTED,
                    response_id="resp-status-followup",
                )
            )
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.OUTPUT_TEXT_DELTA,
                    response_id="resp-status-followup",
                    payload={"delta": "Hermes is idle."},
                )
            )
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.RESPONSE_DONE,
                    response_id="resp-status-followup",
                )
            )

            events: list[dict[str, Any]] = []
            for _ in range(10):
                event = await self._next_ws_event(ws)
                events.append(event)
                if event["type"] == "voice.response.done":
                    break

            done_events = [
                event for event in events if event["type"] == "voice.response.done"
            ]
            self.assertEqual(len(done_events), 1)
            self.assertEqual(done_events[0]["response_id"], "resp-status-followup")
        finally:
            await ws.close()

    async def test_provider_native_cancel_cancels_xai_and_active_hermes_state(self) -> None:
        token = await self._make_session()
        fake_provider = FakeNativeProvider()
        self._server().realtime_agent.native_providers["xai_realtime"] = fake_provider
        resp = await self.client.post(
            "/voice/realtime-agent/session",
            json={
                "provider": "xai_realtime",
                "model": "grok-voice-latest",
                "voice": "leo",
            },
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()

        ws = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready = await self._next_ws_event(ws)
            self.assertEqual(ready["type"], "voice.session.ready")
            await ws.send_json({"type": "response.cancel"})
            cancelled = await self._next_ws_event(ws)
            done = await self._next_ws_event(ws)
            self.assertEqual(cancelled["type"], "hermes.run.cancelled")
            self.assertEqual(done["type"], "voice.response.done")
            self.assertTrue(done["cancelled"])
            self.assertTrue(fake_provider.connection.cancelled)
            self.assertEqual(fake_provider.connection.clear_count, 1)
        finally:
            await ws.close()

    async def test_provider_native_cancel_interrupts_blocked_hermes_tool_task(self) -> None:
        token = await self._make_session()
        fake_broker = BlockingHermesToolBroker()
        fake_provider = FakeNativeProvider()
        self._server().realtime_agent.hermes = fake_broker
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

        ws = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready = await self._next_ws_event(ws)
            self.assertEqual(ready["type"], "voice.session.ready")
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.FUNCTION_CALL_COMPLETED,
                    payload={
                        "call": ToolCallEvent(
                            call_id="call-blocked",
                            name="hermes_run_task",
                            arguments={
                                "text": "Keep working until I cancel.",
                                "session_id": "chat-123",
                            },
                        )
                    },
                )
            )
            started = await self._next_ws_event(ws)
            self.assertEqual(started["type"], "hermes.run.started")
            await asyncio.wait_for(fake_broker.started.wait(), timeout=1)

            await ws.send_json({"type": "response.cancel"})
            events: list[dict[str, Any]] = []
            for _ in range(10):
                event = await self._next_ws_event(ws)
                events.append(event)
                if event["type"] == "voice.response.done":
                    break

            self.assertIn("hermes.run.cancelled", [event["type"] for event in events])
            self.assertTrue(events[-1]["cancelled"])
            await asyncio.wait_for(fake_broker.cancelled.wait(), timeout=1)
            self.assertTrue(fake_provider.connection.cancelled)
            self.assertEqual(fake_provider.connection.clear_count, 1)
        finally:
            fake_broker.release.set()
            await ws.close()

    async def test_provider_native_status_cancel_and_confirm_tools_return_compact_results(self) -> None:
        token = await self._make_session()
        fake_provider = FakeNativeProvider()
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

        ws = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready = await self._next_ws_event(ws)
            self.assertEqual(ready["type"], "voice.session.ready")
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.FUNCTION_CALL_COMPLETED,
                    payload={
                        "call": ToolCallEvent(
                            call_id="status-1",
                            name="hermes_get_status",
                            arguments={"run_id": "run-1"},
                        )
                    },
                )
            )
            drain = None
            for _ in range(10):
                event = await self._next_ws_event(ws)
                if event["type"] == "voice.playback_drain.requested":
                    drain = event
                    break
            self.assertIsNotNone(drain)
            self.assertEqual(fake_provider.connection.tool_results[-1][0], "status-1")
            self.assertTrue(fake_provider.connection.tool_results[-1][1]["ok"])
            self.assertEqual(fake_provider.connection.tool_results[-1][1]["status"], "idle")
            self.assertEqual(
                fake_provider.connection.tool_results[-1][1]["interface"]["provider"],
                "xai_realtime",
            )
            await ws.send_json({"type": "playback.drained", "call_id": "status-1"})

            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.FUNCTION_CALL_COMPLETED,
                    payload={
                        "call": ToolCallEvent(
                            call_id="confirm-1",
                            name="hermes_confirm",
                            arguments={"confirmation_id": "conf-1", "answer": "allow"},
                        )
                    },
                )
            )
            forwarded = None
            for _ in range(10):
                event = await self._next_ws_event(ws)
                if event["type"] == "hermes.confirmation.forwarded":
                    forwarded = event
                    break
            self.assertIsNotNone(forwarded)
            self.assertEqual(fake_provider.connection.tool_results[-1][0], "confirm-1")
            self.assertTrue(fake_provider.connection.tool_results[-1][1]["ok"])
        finally:
            await ws.close()


if __name__ == "__main__":
    unittest.main()
