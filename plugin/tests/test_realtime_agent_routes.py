"""Tests for the experimental /voice/realtime-agent relay route."""

from __future__ import annotations

import base64
import asyncio
import json
import os
import tempfile
import unittest
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Any
from unittest.mock import patch

from aiohttp import WSMsgType, web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay import provider_options, realtime_voice, voice_auth
from plugin.relay.config import RelayConfig
from plugin.relay.realtime_agent import broker as broker_module
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


class CompletedOnlyHermesToolBroker:
    def __init__(self) -> None:
        self.requests: list[HermesTaskRequest] = []

    async def stream_task(self, request: HermesTaskRequest):
        self.requests.append(request)
        session_id = request.session_id or "created-hermes-session"
        yield {
            "type": "hermes.run.started",
            "session_id": session_id,
            "run_id": "run-completed-only",
            "profile": request.profile,
        }
        yield {
            "type": "hermes.tool.completed",
            "session_id": session_id,
            "run_id": "run-completed-only",
            "tool_call_id": "tool-late",
            "tool_name": "terminal",
            "result_preview": "command output",
            "success": True,
        }
        yield {
            "type": "voice.response.turn_completed",
            "session_id": session_id,
            "run_id": "run-completed-only",
            "content": "Terminal says everything is current.",
        }
        yield {
            "type": "hermes.run.completed",
            "session_id": session_id,
            "run_id": "run-completed-only",
            "profile": request.profile,
        }


class DeltaOnlyHermesToolBroker:
    def __init__(self) -> None:
        self.requests: list[HermesTaskRequest] = []

    async def stream_task(self, request: HermesTaskRequest):
        self.requests.append(request)
        session_id = request.session_id or "created-hermes-session"
        yield {
            "type": "hermes.run.started",
            "session_id": session_id,
            "run_id": "run-delta-only",
            "profile": request.profile,
        }
        yield {
            "type": "hermes.tool.delta",
            "session_id": session_id,
            "run_id": "run-delta-only",
            "tool_call_id": "tool-progress",
            "tool_name": "desktop_search",
            "delta": "Searching desktop.",
        }
        yield {
            "type": "hermes.tool.completed",
            "session_id": session_id,
            "run_id": "run-delta-only",
            "tool_call_id": "tool-progress",
            "tool_name": "desktop_search",
            "result_preview": "desktop result",
            "success": True,
        }
        yield {
            "type": "voice.response.turn_completed",
            "session_id": session_id,
            "run_id": "run-delta-only",
            "content": "Desktop search found the relevant result.",
        }
        yield {
            "type": "hermes.run.completed",
            "session_id": session_id,
            "run_id": "run-delta-only",
            "profile": request.profile,
        }


class RealtimeAgentBrokerHelperTests(unittest.TestCase):
    def test_relay_xai_oauth_skips_expired_hermes_provider_token(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            auth_path = os.path.join(tmpdir, "auth.json")
            with open(auth_path, "w", encoding="utf-8") as handle:
                json.dump(
                    {
                        "providers": {
                            "xai-oauth": {
                                "last_refresh": "2000-01-01T00:00:00Z",
                                "tokens": {
                                    "access_token": "expired-token",
                                    "expires_in": 21600,
                                },
                            }
                        }
                    },
                    handle,
                )
            config = RelayConfig(realtime_voice_xai_oauth_path=auth_path)

            self.assertIsNone(realtime_voice._read_relay_xai_oauth_token(config))

    def test_relay_xai_oauth_accepts_fresh_hermes_provider_token(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            auth_path = os.path.join(tmpdir, "auth.json")
            with open(auth_path, "w", encoding="utf-8") as handle:
                json.dump(
                    {
                        "providers": {
                            "xai-oauth": {
                                "last_refresh": datetime.now(timezone.utc).isoformat(),
                                "tokens": {
                                    "access_token": "fresh-token",
                                    "expires_in": 21600,
                                },
                            }
                        }
                    },
                    handle,
                )
            config = RelayConfig(realtime_voice_xai_oauth_path=auth_path)

            token = realtime_voice._read_relay_xai_oauth_token(config)

        self.assertIsNotNone(token)
        assert token is not None
        self.assertEqual(token.access_token, "fresh-token")
        self.assertIn("Hermes auth providers.xai-oauth", token.source)

    def test_relay_xai_oauth_falls_back_to_fresh_hermes_credential_pool_token(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            relay_home = os.path.join(tmpdir, "relay")
            hermes_home = os.path.join(tmpdir, "hermes")
            os.makedirs(os.path.join(relay_home, "auth"))
            os.makedirs(hermes_home)
            with open(
                os.path.join(relay_home, "auth", "xai-oauth.json"),
                "w",
                encoding="utf-8",
            ) as handle:
                json.dump(
                    {
                        "tokens": {
                            "access_token": "expired-relay-token",
                            "refresh_token": "expired-refresh-token",
                            "expires_at_ms": 1,
                        }
                    },
                    handle,
                )
            with open(os.path.join(hermes_home, "auth.json"), "w", encoding="utf-8") as handle:
                json.dump(
                    {
                        "credential_pool": {
                            "xai-oauth": [
                                {
                                    "access_token": "fresh-pool-token",
                                    "refresh_token": "fresh-refresh-token",
                                    "last_refresh": datetime.now(timezone.utc).isoformat(),
                                }
                            ]
                        }
                    },
                    handle,
                )

            with patch.dict(
                os.environ,
                {"HERMES_RELAY_HOME": relay_home, "HERMES_HOME": hermes_home},
            ):
                token = realtime_voice._read_relay_xai_oauth_token(RelayConfig())

        self.assertIsNotNone(token)
        assert token is not None
        self.assertEqual(token.access_token, "fresh-pool-token")
        self.assertIn("Hermes auth credential_pool.xai-oauth", token.source)

    def test_provider_safe_answer_summarizes_raw_tool_json(self) -> None:
        payload = {
            "output": "\n".join(f"line {idx}: raw command output" for idx in range(80)),
        }

        text = broker_module._provider_safe_answer_for_speech(json.dumps(payload))

        self.assertIn("Hermes returned command output", text)
        self.assertLess(len(text), 900)
        self.assertNotIn('"output"', text)

    def test_force_hermes_for_previous_tool_result_followup(self) -> None:
        self.assertTrue(
            broker_module._should_force_hermes_for_transcript(
                "Hey, didn't get any data back from that last call?"
            )
        )


class FakeNativeConnection:
    def __init__(self) -> None:
        self.audio_chunks: list[tuple[bytes, int]] = []
        self.text_inputs: list[str] = []
        self.tool_results: list[tuple[str, dict[str, Any]]] = []
        self.request_response_count = 0
        self.clear_count = 0
        self.cancelled = False
        self.closed = False
        self.fail_tool_result = False
        self.fail_request_response = False
        self._events: asyncio.Queue[ProviderEvent | None] = asyncio.Queue()

    async def send_audio(self, pcm: bytes, sample_rate: int) -> None:
        self.audio_chunks.append((pcm, sample_rate))

    async def commit_audio(self) -> None:
        pass

    async def send_text(self, text: str) -> None:
        self.text_inputs.append(text)

    async def clear_audio(self) -> None:
        self.clear_count += 1

    async def cancel_response(self) -> None:
        self.cancelled = True

    async def send_tool_result(self, call_id: str, output: dict[str, Any]) -> None:
        if self.fail_tool_result:
            raise ConnectionError("Cannot write to closing transport")
        self.tool_results.append((call_id, output))

    async def request_response(self) -> None:
        if self.fail_request_response:
            raise ConnectionError("Cannot write to closing transport")
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


class FakeRenderAdapter:
    def __init__(self) -> None:
        self.requests: list[str] = []

    async def render_text(self, text, config, audio_sink):
        self.requests.append(text)
        audio_sink(
            b"\4\0" * 80,
            {
                "sample_rate": config.sample_rate,
                "channels": 1,
                "sample_width": 2,
                "label": "forced-summary-fallback",
            },
        )
        return SimpleNamespace(
            provider=config.provider,
            model=config.model,
            voice=config.voice,
            audio_path=config.output_path,
            metrics=SimpleNamespace(to_dict=lambda: {"fake": True}),
            metadata={"fake": True},
        )


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
        self._previous_pre_hermes_lead = broker_module._PRE_HERMES_STATUS_LEAD_SECONDS
        broker_module._PRE_HERMES_STATUS_LEAD_SECONDS = 0.0
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
        previous_pre_hermes_lead = getattr(self, "_previous_pre_hermes_lead", None)
        if previous_pre_hermes_lead is not None:
            broker_module._PRE_HERMES_STATUS_LEAD_SECONDS = previous_pre_hermes_lead
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

    async def _complete_forced_hermes_preamble(
        self,
        ws,
        fake_provider: FakeNativeProvider,
        *,
        response_id: str = "resp-forced-preamble",
    ) -> list[dict[str, Any]]:
        await fake_provider.connection.emit(
            ProviderEvent(ProviderEventKind.RESPONSE_STARTED, response_id=response_id)
        )
        await fake_provider.connection.emit(
            ProviderEvent(
                ProviderEventKind.OUTPUT_TEXT_DELTA,
                response_id=response_id,
                payload={"delta": "I'll check Hermes."},
            )
        )
        await fake_provider.connection.emit(
            ProviderEvent(
                ProviderEventKind.AUDIO_DELTA,
                response_id=response_id,
                payload={"audio": b"\2\0" * 80},
            )
        )
        await fake_provider.connection.emit(
            ProviderEvent(ProviderEventKind.AUDIO_DONE, response_id=response_id)
        )
        await fake_provider.connection.emit(
            ProviderEvent(ProviderEventKind.RESPONSE_DONE, response_id=response_id)
        )

        events: list[dict[str, Any]] = []
        for _ in range(20):
            event = await self._next_ws_event(ws)
            events.append(event)
            if event["type"] == "voice.output_audio.done":
                break
        else:
            self.fail("expected forced Hermes preamble audio")
        return events

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
            json={
                "profile": "mizuki",
                "chat_session_id": "chat-123",
                "context_messages": [
                    {"role": "user", "content": "What did we decide?"},
                    {
                        "role": "assistant",
                        "content": "We decided realtime voice shares chat context.",
                        "source": "hermes_chat",
                    },
                ],
            },
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertEqual(body["protocol"], "hermes.voice.realtime_agent.v0")
        self.assertEqual(body["websocket_path"].split("/")[1:3], ["voice", "realtime-agent"])
        self.assertEqual(body["profile"], "mizuki")
        self.assertEqual(body["chat_session_id"], "chat-123")
        self.assertEqual(body["context_message_count"], 2)
        self.assertIsInstance(body["resume_token"], str)
        self.assertTrue(body["resume_supported"])
        self.assertGreaterEqual(body["resume_ttl_ms"], 1000)
        self.assertTrue(body["experimental"])

    async def test_provider_native_instructions_include_recent_context(self) -> None:
        token = await self._make_session()
        fake_provider = FakeNativeProvider()
        handler = self._server().realtime_agent
        handler.native_providers["xai_realtime"] = fake_provider

        resp = await self.client.post(
            "/voice/realtime-agent/session",
            json={
                "provider": "xai_realtime",
                "model": "grok-voice-latest",
                "voice": "leo",
                "chat_session_id": "chat-123",
                "context_messages": [
                    {
                        "role": "user",
                        "content": "Tell me about the Bitwarden integration.",
                        "source": "hermes_chat",
                    },
                    {
                        "role": "assistant",
                        "content": "It syncs vault metadata through Hermes.",
                        "source": "realtime_agent",
                    },
                ],
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
            self.assertTrue(fake_provider.configs)
            instructions = fake_provider.configs[-1].instructions
            self.assertIn("Recent shared chat context", instructions)
            self.assertIn("User (hermes_chat): Tell me about the Bitwarden integration.", instructions)
            self.assertIn("Assistant (realtime_agent): It syncs vault metadata through Hermes.", instructions)
            self.assertIn("Use that seeded context for follow-up references", instructions)
        finally:
            await ws.close()
            await handler._close_native_session(handler.sessions[body["session_id"]], "test cleanup")

    async def test_provider_native_instructions_include_profile_identity_context(self) -> None:
        hermes_dir = os.path.join(self._tmpdir.name, "hermes")
        profile_dir = os.path.join(hermes_dir, "profiles", "mizuki")
        memories_dir = os.path.join(profile_dir, "memories")
        os.makedirs(memories_dir, exist_ok=True)
        with open(os.path.join(hermes_dir, "config.yaml"), "w", encoding="utf-8") as fh:
            fh.write("model:\n  default: root-model\n")
        with open(os.path.join(memories_dir, "MEMORY.md"), "w", encoding="utf-8") as fh:
            fh.write("Mizuki prefers concise voice replies with calm status updates.\n")
        self._server().config.hermes_config_path = os.path.join(hermes_dir, "config.yaml")
        self._server().config.profiles = [
            {
                "name": "mizuki",
                "description": "Profile for Mizuki.",
                "system_message": "Mizuki SOUL says to be direct and warm.",
            }
        ]

        token = await self._make_session()
        fake_provider = FakeNativeProvider()
        handler = self._server().realtime_agent
        handler.native_providers["xai_realtime"] = fake_provider

        resp = await self.client.post(
            "/voice/realtime-agent/session",
            json={
                "provider": "xai_realtime",
                "model": "grok-voice-latest",
                "voice": "leo",
                "profile": "mizuki",
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
            self.assertTrue(fake_provider.configs)
            instructions = fake_provider.configs[-1].instructions
            self.assertIn("Hermes profile identity context follows", instructions)
            self.assertIn("Profile: mizuki.", instructions)
            self.assertIn("Mizuki SOUL says to be direct and warm.", instructions)
            self.assertIn("[MEMORY.md]", instructions)
            self.assertIn(
                "Mizuki prefers concise voice replies with calm status updates.",
                instructions,
            )
            self.assertIn("call Hermes for current facts", instructions)
        finally:
            await ws.close()
            await handler._close_native_session(handler.sessions[body["session_id"]], "test cleanup")

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
            self.assertGreater(ready["event_id"], 0)
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
            self.assertIn("You may speak one brief acknowledgement", instructions)
            self.assertIn("Do not give a substantive answer until Hermes returns", instructions)
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
            self.assertGreater(audio_received["event_id"], ready["event_id"])
            self.assertEqual(audio_received["input_chunk_id"], 1)
            self.assertEqual(fake_provider.connection.audio_chunks, [(pcm, 16000)])

            await ws.send_json({"type": "input_audio.commit"})
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.INPUT_TRANSCRIPT_FINAL,
                    payload={"text": "tell me a short timeless fact"},
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
            self.assertEqual(fake_provider.connection.request_response_count, 1)
        finally:
            await ws.close()

    async def test_provider_native_required_transcript_uses_provider_tool_loop(self) -> None:
        token = await self._make_session()
        fake_provider = FakeNativeProvider()
        fake_broker = FakeHermesToolBroker()
        self._server().realtime_agent.native_providers["xai_realtime"] = fake_provider
        self._server().realtime_agent.hermes = fake_broker
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

            transcript = "Can you check the current Hermes status?"
            await ws.send_json({"type": "input_audio.commit"})
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.INPUT_TRANSCRIPT_FINAL,
                    payload={"text": transcript},
                )
            )

            for _ in range(50):
                if fake_provider.connection.request_response_count:
                    break
                await asyncio.sleep(0.01)
            self.assertEqual(fake_provider.connection.request_response_count, 1)
            self.assertEqual(fake_provider.connection.text_inputs, [])
            self.assertEqual(fake_broker.requests, [])

            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.RESPONSE_STARTED,
                    response_id="resp-provider-ack",
                )
            )
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.OUTPUT_TEXT_DELTA,
                    response_id="resp-provider-ack",
                    payload={"delta": "I'll check Hermes."},
                )
            )
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.AUDIO_DELTA,
                    response_id="resp-provider-ack",
                    payload={"audio": b"\3\0" * 80},
                )
            )
            await fake_provider.connection.emit(
                ProviderEvent(ProviderEventKind.AUDIO_DONE, response_id="resp-provider-ack")
            )
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.FUNCTION_CALL_COMPLETED,
                    response_id="resp-provider-ack",
                    payload={
                        "call": ToolCallEvent(
                            call_id="call-1",
                            name="hermes_run_task",
                            arguments={
                                "text": transcript,
                                "session_id": "chat-123",
                                "profile": "victor",
                            },
                        )
                    },
                )
            )

            events: list[dict[str, Any]] = []
            for _ in range(40):
                event = await self._next_ws_event(ws)
                events.append(event)
                if event["type"] == "voice.playback_drain.requested":
                    break
            else:
                self.fail("expected pre-Hermes playback drain request")

            pre_drain = events[-1]
            self.assertEqual(pre_drain["reason"], "pre_hermes_ack")
            self.assertEqual(pre_drain["call_id"], "call-1")
            self.assertEqual(fake_broker.requests, [])
            self.assertEqual(fake_provider.connection.tool_results, [])
            await ws.send_json(
                {
                    "type": "playback.drained",
                    "call_id": "call-1",
                    "played_audio_event_id": 1,
                }
            )

            for _ in range(40):
                event = await self._next_ws_event(ws)
                events.append(event)
                if (
                    event["type"] == "voice.playback_drain.requested"
                    and event.get("reason") == "tool_result_summary"
                ):
                    await ws.send_json({"type": "playback.drained", "call_id": "call-1"})
                    break
            else:
                self.fail("expected playback drain request after Hermes tool result")

            event_types = [event["type"] for event in events]
            self.assertIn("voice.input_transcript.final", event_types)
            self.assertIn("voice.response.started", event_types)
            self.assertIn("voice.response.delta", event_types)
            self.assertIn("voice.output_audio.delta", event_types)
            self.assertIn("voice.output_audio.done", event_types)
            self.assertIn("hermes.run.progress", event_types)
            self.assertIn("hermes.run.started", event_types)
            self.assertIn("hermes.tool.started", event_types)
            self.assertIn("hermes.tool.completed", event_types)
            self.assertIn("hermes.run.completed", event_types)
            pre_status = next(
                event
                for event in events
                if event["type"] == "hermes.run.progress"
                and event["status_key"] == "run:checking_hermes"
            )
            self.assertFalse(pre_status["should_speak"])
            self.assertEqual(fake_broker.requests[0].text, transcript)
            self.assertEqual(fake_provider.connection.text_inputs, [])
            self.assertEqual(fake_provider.connection.tool_results[0][0], "call-1")

            for _ in range(50):
                if fake_provider.connection.request_response_count >= 2:
                    break
                await asyncio.sleep(0.01)
            self.assertEqual(fake_provider.connection.request_response_count, 2)
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
            self.assertIn("You may speak one brief acknowledgement", instructions)
            self.assertIn("Do not give a substantive answer until Hermes returns", instructions)

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
                    payload={"text": "say hello"},
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

    async def test_provider_native_session_resume_replays_missed_events(self) -> None:
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
        resume_token = body["resume_token"]

        ws1 = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        ready = await self._next_ws_event(ws1)
        self.assertEqual(ready["type"], "voice.session.ready")
        self.assertTrue(ready["resume_supported"])
        await ws1.close(code=1001, message=b"network changed")

        session = self._server().realtime_agent.sessions[body["session_id"]]
        for _ in range(40):
            if session.detached_at is not None:
                break
            await asyncio.sleep(0.01)
        self.assertIsNotNone(session.detached_at)
        self.assertFalse(fake_provider.connection.closed)

        await fake_provider.connection.emit(
            ProviderEvent(
                ProviderEventKind.AUDIO_DELTA,
                response_id="resp-detached",
                payload={"audio": b"\4\0" * 80},
            )
        )
        await fake_provider.connection.emit(
            ProviderEvent(ProviderEventKind.AUDIO_DONE, response_id="resp-detached")
        )

        ws2 = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            await ws2.send_json(
                {
                    "type": "session.resume",
                    "resume_token": resume_token,
                    "last_event_id": ready["event_id"],
                    "last_audio_event_id": 0,
                    "last_played_audio_event_id": 0,
                    "last_input_chunk_id": 0,
                }
            )
            events: list[dict[str, Any]] = []
            for _ in range(10):
                event = await self._next_ws_event(ws2)
                events.append(event)
                if event["type"] == "voice.replay.done":
                    break

            event_types = [event["type"] for event in events]
            self.assertIn("voice.session.resumed", event_types)
            self.assertIn("voice.replay.started", event_types)
            self.assertIn("voice.session.detached", event_types)
            self.assertIn("voice.output_audio.delta", event_types)
            self.assertIn("voice.output_audio.done", event_types)
            self.assertIn("voice.replay.done", event_types)
            audio = next(event for event in events if event["type"] == "voice.output_audio.delta")
            self.assertTrue(audio["replayed"])
            self.assertEqual(audio["audio_event_id"], 1)
            self.assertIsNone(session.detached_at)
        finally:
            await ws2.close()

    async def test_provider_native_clean_socket_close_keeps_resume_window(self) -> None:
        token = await self._make_session()
        fake_provider = FakeNativeProvider()
        handler = self._server().realtime_agent
        handler.native_providers["xai_realtime"] = fake_provider
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
        await self._next_ws_event(ws)
        await ws.close(code=1000, message=b"route changed")

        session = handler.sessions[body["session_id"]]
        try:
            for _ in range(40):
                if session.detached_at is not None:
                    break
                await asyncio.sleep(0.01)
            self.assertIsNotNone(session.detached_at)
            self.assertFalse(session.closed)
            self.assertFalse(fake_provider.connection.closed)
        finally:
            await handler._close_native_session(session, "test cleanup")

    async def test_provider_native_superseded_socket_close_keeps_active_resume_attached(self) -> None:
        token = await self._make_session()
        fake_provider = FakeNativeProvider()
        handler = self._server().realtime_agent
        handler.native_providers["xai_realtime"] = fake_provider
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
        resume_token = body["resume_token"]
        session = handler.sessions[body["session_id"]]

        ws1 = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        ws2 = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready1 = await self._next_ws_event(ws1)
            ready2 = await self._next_ws_event(ws2)
            self.assertEqual(ready1["type"], "voice.session.ready")
            self.assertEqual(ready2["type"], "voice.session.ready")
            await ws2.send_json(
                {
                    "type": "session.resume",
                    "resume_token": resume_token,
                    "last_event_id": ready1["event_id"],
                    "last_audio_event_id": 0,
                    "last_played_audio_event_id": 0,
                    "last_input_chunk_id": 0,
                }
            )
            for _ in range(10):
                event = await self._next_ws_event(ws2)
                if event["type"] == "voice.replay.done":
                    break

            await ws1.close(code=1000, message=b"superseded route")
            for _ in range(40):
                if session.detached_at is not None:
                    break
                await asyncio.sleep(0.01)
            self.assertIsNone(session.detached_at)
            self.assertFalse(session.closed)
            self.assertFalse(fake_provider.connection.closed)

            await fake_provider.connection.emit(
                ProviderEvent(ProviderEventKind.RESPONSE_DONE, response_id="resp-after-resume")
            )
            events: list[dict[str, Any]] = []
            for _ in range(10):
                event = await self._next_ws_event(ws2)
                events.append(event)
                if event["type"] == "voice.response.done":
                    break

            event_types = [event["type"] for event in events]
            self.assertNotIn("voice.session.detached", event_types)
            self.assertIn("voice.response.done", event_types)
        finally:
            await ws1.close()
            await ws2.close()
            await handler._close_native_session(session, "test cleanup")

    async def test_provider_native_send_records_and_skips_lost_websocket(self) -> None:
        token = await self._make_session()
        handler = self._server().realtime_agent
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
        session = handler.sessions[body["session_id"]]

        class BrokenWebSocket:
            closed = False

            async def send_json(self, payload: dict[str, Any]) -> None:
                raise ConnectionError("Connection lost")

        session.attached_ws = BrokenWebSocket()  # type: ignore[assignment]
        await handler._send(
            None,
            session,
            {
                "type": "voice.output_audio.done",
                "response_id": "resp-lost-socket",
            },
        )

        self.assertFalse(session.closed)
        self.assertEqual(session.event_ring[-1]["type"], "voice.output_audio.done")
        self.assertEqual(session.event_ring[-1]["response_id"], "resp-lost-socket")

    async def test_provider_native_response_create_runs_text_audio_test(self) -> None:
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

            await ws.send_json(
                {
                    "type": "response.create",
                    "text": "Say a short realtime agent test confirmation.",
                }
            )
            for _ in range(10):
                if fake_provider.connection.text_inputs:
                    break
                await asyncio.sleep(0)
            self.assertEqual(
                fake_provider.connection.text_inputs,
                ["Say a short realtime agent test confirmation."],
            )

            await fake_provider.connection.emit(
                ProviderEvent(ProviderEventKind.RESPONSE_STARTED, response_id="resp-text-test")
            )
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.OUTPUT_TEXT_DELTA,
                    response_id="resp-text-test",
                    payload={"delta": "Realtime Agent is working."},
                )
            )
            await fake_provider.connection.emit(
                ProviderEvent(
                    ProviderEventKind.AUDIO_DELTA,
                    response_id="resp-text-test",
                    payload={"audio": b"\2\0" * 120},
                )
            )
            await fake_provider.connection.emit(
                ProviderEvent(ProviderEventKind.AUDIO_DONE, response_id="resp-text-test")
            )
            await fake_provider.connection.emit(
                ProviderEvent(ProviderEventKind.RESPONSE_DONE, response_id="resp-text-test")
            )

            events: list[dict[str, Any]] = []
            for _ in range(10):
                event = await self._next_ws_event(ws)
                events.append(event)
                if event["type"] == "voice.response.done":
                    break

            event_types = [event["type"] for event in events]
            self.assertIn("voice.input_transcript.final", event_types)
            self.assertIn("voice.response.started", event_types)
            self.assertIn("voice.response.delta", event_types)
            self.assertIn("voice.output_audio.delta", event_types)
            self.assertIn("voice.output_audio.done", event_types)
            self.assertIn("voice.response.done", event_types)
            transcript = next(
                event for event in events if event["type"] == "voice.input_transcript.final"
            )
            self.assertEqual(
                transcript["text"],
                "Say a short realtime agent test confirmation.",
            )
            self.assertEqual(transcript["source"], "client_text")
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
            self.assertIn("hermes.run.completed", event_types)
            self.assertNotIn("voice.response.delta", event_types)
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
            pre_status = await self._next_ws_event(ws)
            self.assertEqual(pre_status["type"], "hermes.run.progress")
            self.assertEqual(pre_status["status_key"], "run:checking_hermes")
            self.assertFalse(pre_status["should_speak"])
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

    async def test_provider_native_emits_progress_while_hermes_tool_is_blocked(self) -> None:
        previous_interval = broker_module._HERMES_PROGRESS_INTERVAL_SECONDS
        broker_module._HERMES_PROGRESS_INTERVAL_SECONDS = 0.01
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
                                "text": "Keep working long enough to report progress.",
                                "session_id": "chat-123",
                            },
                        )
                    },
                )
            )
            pre_status = await self._next_ws_event(ws)
            self.assertEqual(pre_status["type"], "hermes.run.progress")
            self.assertEqual(pre_status["status_key"], "run:checking_hermes")
            self.assertFalse(pre_status["should_speak"])
            started = await self._next_ws_event(ws)
            self.assertEqual(started["type"], "hermes.run.started")
            await asyncio.wait_for(fake_broker.started.wait(), timeout=1)

            progress = await asyncio.wait_for(self._next_ws_event(ws), timeout=1)
            self.assertEqual(progress["type"], "hermes.run.progress")
            self.assertEqual(progress["source"], "hermes")
            self.assertEqual(progress["status"], "running")
            self.assertEqual(progress["message"], "Waiting for Hermes response.")
            self.assertEqual(progress["run_id"], "run-blocked")
            self.assertEqual(progress["chat_session_id"], "chat-123")
            self.assertFalse(progress["should_speak"])
            self.assertGreaterEqual(progress["elapsed_ms"], 0)
        finally:
            broker_module._HERMES_PROGRESS_INTERVAL_SECONDS = previous_interval
            fake_broker.release.set()
            await ws.close()

    async def test_provider_native_progress_can_request_spoken_status_after_delay(self) -> None:
        previous_interval = broker_module._HERMES_PROGRESS_INTERVAL_SECONDS
        previous_after = broker_module._HERMES_SPOKEN_PROGRESS_AFTER_SECONDS
        broker_module._HERMES_PROGRESS_INTERVAL_SECONDS = 0.01
        broker_module._HERMES_SPOKEN_PROGRESS_AFTER_SECONDS = 0.0
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
                                "text": "Keep working long enough to report progress.",
                                "session_id": "chat-123",
                            },
                        )
                    },
                )
            )
            pre_status = await self._next_ws_event(ws)
            self.assertEqual(pre_status["type"], "hermes.run.progress")
            self.assertEqual(pre_status["status_key"], "run:checking_hermes")
            self.assertFalse(pre_status["should_speak"])
            started = await self._next_ws_event(ws)
            self.assertEqual(started["type"], "hermes.run.started")
            await asyncio.wait_for(fake_broker.started.wait(), timeout=1)

            progress = await asyncio.wait_for(self._next_ws_event(ws), timeout=1)
            self.assertEqual(progress["type"], "hermes.run.progress")
            self.assertTrue(progress["should_speak"])
            self.assertEqual(progress["status_key"], "run:waiting_hermes")
        finally:
            broker_module._HERMES_PROGRESS_INTERVAL_SECONDS = previous_interval
            broker_module._HERMES_SPOKEN_PROGRESS_AFTER_SECONDS = previous_after
            fake_broker.release.set()
            await ws.close()

    async def test_provider_native_synthesizes_tool_start_for_late_completed_events(self) -> None:
        token = await self._make_session()
        fake_broker = CompletedOnlyHermesToolBroker()
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
                                "text": "Run a terminal check.",
                                "session_id": "chat-123",
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
            self.assertIn("hermes.tool.started", event_types)
            self.assertIn("hermes.tool.completed", event_types)
            started = next(event for event in events if event["type"] == "hermes.tool.started")
            self.assertTrue(started["synthetic"])
            self.assertEqual(started["tool_name"], "terminal")
            self.assertEqual(fake_provider.connection.tool_results[-1][1]["answer"], "Terminal says everything is current.")
            self.assertEqual(fake_provider.connection.tool_results[-1][1]["tool_count"], 1)
            self.assertIn("provider_instruction", fake_provider.connection.tool_results[-1][1])
        finally:
            await ws.close()

    async def test_provider_native_synthesizes_tool_start_for_delta_progress_events(self) -> None:
        token = await self._make_session()
        fake_broker = DeltaOnlyHermesToolBroker()
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
                                "text": "Search the desktop.",
                                "session_id": "chat-123",
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
            self.assertIn("hermes.tool.started", event_types)
            self.assertIn("hermes.tool.delta", event_types)
            started = next(event for event in events if event["type"] == "hermes.tool.started")
            self.assertTrue(started["synthetic"])
            self.assertEqual(started["tool_name"], "desktop_search")
            delta = next(event for event in events if event["type"] == "hermes.tool.delta")
            self.assertEqual(delta["message"], "Searching desktop.")
            self.assertEqual(
                fake_provider.connection.tool_results[-1][1]["answer"],
                "Desktop search found the relevant result.",
            )
        finally:
            await ws.close()

    async def test_provider_native_reports_provider_close_during_tool_result_send(self) -> None:
        token = await self._make_session()
        fake_broker = FakeHermesToolBroker()
        fake_provider = FakeNativeProvider()
        fake_provider.connection.fail_tool_result = True
        handler = self._server().realtime_agent
        handler.hermes = fake_broker
        handler.native_providers["xai_realtime"] = fake_provider
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
                            call_id="call-closing",
                            name="hermes_run_task",
                            arguments={
                                "text": "Check something before the provider closes.",
                                "session_id": "chat-123",
                            },
                        )
                    },
                )
            )

            events: list[dict[str, Any]] = []
            for _ in range(20):
                event = await self._next_ws_event(ws)
                events.append(event)
                if event["type"] == "voice.error":
                    break

            error = next(event for event in events if event["type"] == "voice.error")
            self.assertEqual(error["error_code"], "provider_tool_result_send_failed")
            self.assertEqual(error["call_id"], "call-closing")
            self.assertTrue(handler.sessions[body["session_id"]].closed)
        finally:
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
