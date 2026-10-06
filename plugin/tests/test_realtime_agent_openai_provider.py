"""Tests for the OpenAI provider-native realtime-agent adapter."""

from __future__ import annotations

import base64
import os
import unittest
from typing import Any
from unittest.mock import AsyncMock, patch

import aiohttp

from plugin.relay.realtime_agent.models import (
    HERMES_TOOL_SURFACE,
    ProviderEventKind,
    RealtimeAgentSessionConfig,
)
from plugin.relay.realtime_agent.providers.openai import (
    AuthToken,
    OpenAIRealtimeAgentProvider,
    _resolve_auth_token,
    _session_update,
)
from plugin.voice_lab.providers.base import ProviderUnavailable


class FakeOpenAISocket:
    def __init__(self) -> None:
        self.sent: list[dict[str, Any]] = []
        self.incoming: list[dict[str, Any]] = []
        self.closed = False

    async def send_json(self, payload: dict[str, Any]) -> None:
        self.sent.append(payload)

    async def receive_json(self) -> dict[str, Any]:
        if not self.incoming:
            raise EOFError
        return self.incoming.pop(0)

    async def close(self) -> None:
        self.closed = True


class PCMContractSocket(FakeOpenAISocket):
    """Model the reported rejection and GA session.updated acknowledgement."""

    async def send_json(self, payload: dict[str, Any]) -> None:
        await super().send_json(payload)
        if payload["type"] != "session.update":
            return
        session = payload["session"]
        for direction in ("input", "output"):
            audio_format = session["audio"][direction]["format"]
            param = f"session.audio.{direction}.format.rate"
            if "rate" not in audio_format:
                self.incoming.append({
                    "type": "error",
                    "event_id": "evt-rejected",
                    "error": {
                        "type": "invalid_request_error",
                        "code": "missing_required_parameter",
                        "param": param,
                        "message": f"Missing required parameter: '{param}'.",
                    },
                })
                return
            if audio_format != {"type": "audio/pcm", "rate": 24000}:
                raise AssertionError("Expected mono PCM16 at 24 kHz")
        self.incoming.append({
            "type": "session.updated",
            "event_id": "evt-configured",
            "session": {"id": "sess-test", "object": "realtime.session", **session},
        })


class OpenAIRealtimeAgentProviderTests(unittest.IsolatedAsyncioTestCase):
    def test_codex_oauth_mode_ignores_metered_api_keys(self) -> None:
        with patch.dict(
            os.environ,
            {
                "OPENAI_API_KEY": "metered-key",
                "RELAY_OPENAI_REALTIME_AUTH": "codex_oauth",
            },
            clear=True,
        ), patch(
            "plugin.relay.realtime_agent.providers.openai._resolve_codex_oauth_token",
            return_value=AuthToken("subscription-token", "codex-cli:chatgpt"),
        ):
            auth = _resolve_auth_token({})

        self.assertEqual(auth, AuthToken("subscription-token", "codex-cli:chatgpt"))

    def test_codex_oauth_mode_fails_closed_without_subscription_login(self) -> None:
        with patch.dict(
            os.environ,
            {
                "OPENAI_API_KEY": "metered-key",
                "RELAY_OPENAI_REALTIME_AUTH": "codex_oauth",
            },
            clear=True,
        ), patch(
            "plugin.relay.realtime_agent.providers.openai._resolve_codex_oauth_token",
            return_value=None,
        ), self.assertRaisesRegex(ProviderUnavailable, "Metered API keys were not used"):
            _resolve_auth_token({})

    def test_api_key_mode_does_not_fall_back_to_codex_oauth(self) -> None:
        with patch.dict(
            os.environ,
            {"RELAY_OPENAI_REALTIME_AUTH": "api_key"},
            clear=True,
        ), patch(
            "plugin.relay.realtime_agent.providers.openai._resolve_codex_oauth_token"
        ) as oauth:
            self.assertIsNone(_resolve_auth_token({}))
        oauth.assert_not_called()

    def test_auto_mode_preserves_existing_api_key_precedence(self) -> None:
        with patch.dict(os.environ, {"OPENAI_API_KEY": "metered-key"}, clear=True), patch(
            "plugin.relay.realtime_agent.providers.openai._resolve_codex_oauth_token"
        ) as oauth:
            auth = _resolve_auth_token({})
        self.assertEqual(auth, AuthToken("metered-key", "env:OPENAI_API_KEY"))
        oauth.assert_not_called()

    def test_invalid_auth_mode_fails_closed(self) -> None:
        with patch.dict(
            os.environ,
            {"RELAY_OPENAI_REALTIME_AUTH": "maybe"},
            clear=True,
        ), self.assertRaisesRegex(ProviderUnavailable, "auth_mode must be"):
            _resolve_auth_token({})

    async def test_pcm_session_negotiation_for_ga_models(self) -> None:
        for model in (
            "gpt-realtime-2", "gpt-realtime-2.1", "gpt-realtime-2.1-mini",
            "gpt-realtime", "gpt-realtime-1.5", "gpt-realtime-mini",
        ):
            with self.subTest(model=model):
                socket = PCMContractSocket()
                factory = AsyncMock(return_value=socket)
                connection = await OpenAIRealtimeAgentProvider(factory).connect(
                    RealtimeAgentSessionConfig(
                        provider="openai_realtime", model=model, voice="cedar",
                        sample_rate=24000, profile=None, hermes_session_id=None,
                        provider_options={"api_key": "openai-test"},
                    )
                )
                try:
                    events = [event async for event in connection.events()]
                    self.assertEqual([e.kind for e in events], [ProviderEventKind.READY])
                    self.assertEqual(events[0].payload["provider_event_type"], "session.updated")
                    self.assertEqual(events[0].payload["resolved_model"], model)
                    self.assertEqual(socket.sent[0]["session"]["audio"], {
                        "input": {
                            "format": {"type": "audio/pcm", "rate": 24000},
                            "turn_detection": None,
                            "transcription": {"model": "gpt-realtime-whisper"},
                        },
                        "output": {
                            "format": {"type": "audio/pcm", "rate": 24000},
                            "voice": "cedar",
                        },
                    })
                    self.assertNotIn("OpenAI-Beta", factory.call_args.args[1])
                finally:
                    await connection.close()
                self.assertTrue(socket.closed)

    async def test_missing_output_rate_surfaces_provider_rejection(self) -> None:
        config = RealtimeAgentSessionConfig(
            provider="openai_realtime", model="gpt-realtime-2.1", voice="marin",
            sample_rate=24000, profile=None, hermes_session_id=None,
            provider_options={"api_key": "openai-test"},
        )
        rejected_update = _session_update(config)
        rejected_update["session"]["audio"]["output"]["format"].pop("rate", None)
        socket = PCMContractSocket()
        with patch(
            "plugin.relay.realtime_agent.providers.openai._session_update",
            return_value=rejected_update,
        ):
            connection = await OpenAIRealtimeAgentProvider(
                AsyncMock(return_value=socket)
            ).connect(config)
        try:
            events = [event async for event in connection.events()]
            self.assertEqual([e.kind for e in events], [ProviderEventKind.ERROR])
            self.assertEqual(events[0].payload["message"],
                "OpenAI Realtime error: Missing required parameter: "
                "'session.audio.output.format.rate'.")
            self.assertNotIn("openai-test", str(events[0].payload))
        finally:
            await connection.close()

    async def test_unsupported_session_rates_fail_before_opening_socket(self) -> None:
        factory = AsyncMock()
        for rate in (0, 8000, 16000, 44100, 48000):
            with self.subTest(rate=rate):
                with self.assertRaisesRegex(ProviderUnavailable, "sample_rate=24000"):
                    await OpenAIRealtimeAgentProvider(factory).connect(
                        RealtimeAgentSessionConfig(
                            provider="openai_realtime", model="gpt-realtime-2.1",
                            voice="marin", sample_rate=rate, profile=None,
                            hermes_session_id=None,
                        )
                    )
        factory.assert_not_awaited()

    async def test_transcription_options_preserve_ga_audio_configuration(self) -> None:
        for options, expected in (
            ({"input_transcription_enabled": False}, None),
            ({"transcription_prompt": "Project vocabulary"},
             {"model": "gpt-realtime-whisper"}),
            ({"transcription_model": "gpt-4o-transcribe",
              "transcription_language": "en", "transcription_prompt": "Project vocabulary"},
             {"model": "gpt-4o-transcribe", "language": "en", "prompt": "Project vocabulary"}),
        ):
            with self.subTest(options=options):
                socket = PCMContractSocket()
                connection = await OpenAIRealtimeAgentProvider(
                    AsyncMock(return_value=socket)
                ).connect(RealtimeAgentSessionConfig(
                    provider="openai_realtime", model="gpt-realtime-2.1", voice="marin",
                    sample_rate=24000, profile=None, hermes_session_id=None,
                    provider_options={"api_key": "openai-test", **options},
                ))
                try:
                    audio = socket.sent[0]["session"]["audio"]
                    self.assertEqual(audio["input"].get("transcription"), expected)
                    self.assertIsNone(audio["input"]["turn_detection"])
                    events = [event async for event in connection.events()]
                    self.assertEqual([e.kind for e in events], [ProviderEventKind.READY])
                finally:
                    await connection.close()

    async def test_ga_and_legacy_audio_events_remain_compatible(self) -> None:
        for prefix in ("response.output_audio", "response.audio"):
            with self.subTest(prefix=prefix):
                socket = FakeOpenAISocket()
                connection = await OpenAIRealtimeAgentProvider(
                    AsyncMock(return_value=socket)
                ).connect(RealtimeAgentSessionConfig(
                    provider="openai_realtime", model="gpt-realtime-2.1", voice="marin",
                    sample_rate=24000, profile=None, hermes_session_id=None,
                    provider_options={"api_key": "openai-test"},
                ))
                pcm = b"\x01\x00" * 10
                socket.incoming.extend([
                    {"type": f"{prefix}.delta", "delta": base64.b64encode(pcm).decode("ascii")},
                    {"type": f"{prefix}_transcript.delta", "delta": "Hello"},
                    {"type": f"{prefix}.done"},
                ])
                try:
                    events = [event async for event in connection.events()]
                    self.assertEqual([e.kind for e in events], [
                        ProviderEventKind.AUDIO_DELTA,
                        ProviderEventKind.OUTPUT_TEXT_DELTA,
                        ProviderEventKind.AUDIO_DONE,
                    ])
                    self.assertEqual(events[0].payload["audio"], pcm)
                    self.assertEqual(events[1].payload["delta"], "Hello")
                finally:
                    await connection.close()

    async def test_auth_handshake_failure_reports_reauth_action(self) -> None:
        async def factory(url: str, headers: dict[str, str], timeout: float):
            raise aiohttp.WSServerHandshakeError(
                None,
                (),
                status=403,
                message="Invalid response status",
            )

        provider = OpenAIRealtimeAgentProvider(socket_factory=factory)
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(
                ProviderUnavailable,
                "Update the relay-side OpenAI realtime provider credentials",
            ):
                await provider.connect(
                    RealtimeAgentSessionConfig(
                        provider="openai_realtime",
                        model="gpt-realtime-2",
                        voice="marin",
                        sample_rate=24000,
                        profile="victor",
                        hermes_session_id="chat-123",
                        provider_options={"api_key": "openai-test"},
                    )
                )

    async def test_default_instructions_allow_brief_hermes_acknowledgement(self) -> None:
        fake_socket = FakeOpenAISocket()

        async def factory(url: str, headers: dict[str, str], timeout: float):
            return fake_socket

        provider = OpenAIRealtimeAgentProvider(socket_factory=factory)
        with patch.dict(os.environ, {}, clear=True):
            await provider.connect(
                RealtimeAgentSessionConfig(
                    provider="openai_realtime",
                    model="gpt-realtime-2",
                    voice="marin",
                    sample_rate=24000,
                    profile="victor",
                    hermes_session_id="chat-123",
                    provider_options={"api_key": "openai-test"},
                )
            )

        instructions = fake_socket.sent[0]["session"]["instructions"]
        self.assertIn("You may speak one brief acknowledgement", instructions)
        self.assertNotIn("do not speak or emit acknowledgement text", instructions)

    async def test_connect_sends_session_update_with_pcm_manual_turns_and_hermes_tools(
        self,
    ) -> None:
        fake_socket = FakeOpenAISocket()
        captured: dict[str, Any] = {}

        async def factory(url: str, headers: dict[str, str], timeout: float):
            captured["url"] = url
            captured["headers"] = headers
            captured["timeout"] = timeout
            return fake_socket

        provider = OpenAIRealtimeAgentProvider(socket_factory=factory)
        with patch.dict(os.environ, {}, clear=True):
            connection = await provider.connect(
                RealtimeAgentSessionConfig(
                    provider="openai_realtime",
                    model="gpt-realtime-2",
                    voice="marin",
                    sample_rate=24000,
                    profile="victor",
                    hermes_session_id="chat-123",
                    instructions="Use Hermes for current data.",
                    provider_options={
                        "api_key": "openai-test",
                        "safety_identifier": "phone-hash",
                        "transcription_language": "en",
                        "extra_headers": {"X-Voice-Gateway": "relay-test"},
                    },
                )
            )

        self.assertIs(connection.socket, fake_socket)
        self.assertEqual(
            captured["url"],
            "wss://api.openai.com/v1/realtime?model=gpt-realtime-2",
        )
        self.assertEqual(captured["headers"]["Authorization"], "Bearer openai-test")
        self.assertEqual(captured["headers"]["OpenAI-Safety-Identifier"], "phone-hash")
        self.assertEqual(captured["headers"]["X-Voice-Gateway"], "relay-test")
        session_update = fake_socket.sent[0]
        self.assertEqual(session_update["type"], "session.update")
        session = session_update["session"]
        self.assertEqual(session["type"], "realtime")
        self.assertEqual(session["model"], "gpt-realtime-2")
        self.assertEqual(session["instructions"], "Use Hermes for current data.")
        self.assertEqual(session["output_modalities"], ["audio"])
        self.assertIsNone(session["audio"]["input"]["turn_detection"])
        self.assertEqual(session["audio"]["input"]["format"]["type"], "audio/pcm")
        self.assertEqual(session["audio"]["input"]["format"]["rate"], 24000)
        self.assertEqual(
            session["audio"]["input"]["transcription"]["model"],
            "gpt-realtime-whisper",
        )
        self.assertEqual(session["audio"]["input"]["transcription"]["language"], "en")
        self.assertEqual(
            session["audio"]["output"]["format"],
            {"type": "audio/pcm", "rate": 24000},
        )
        self.assertEqual(session["audio"]["output"]["voice"], "marin")
        self.assertEqual(session["tool_choice"], "auto")
        tool_names = [tool["name"] for tool in session["tools"]]
        self.assertEqual(tool_names, list(HERMES_TOOL_SURFACE))
        self.assertTrue(all(tool["type"] == "function" for tool in session["tools"]))
        run_tool = next(tool for tool in session["tools"] if tool["name"] == "hermes_run_task")
        self.assertIn("current checks", run_tool["description"])
        self.assertIn("live/external data", run_tool["description"])
        self.assertIn("speech-safe summary", run_tool["description"])

    async def test_ga_pcm_session_negotiation_for_configured_and_default_models(self) -> None:
        for model in ("gpt-realtime-2", "gpt-realtime-2.1", "gpt-realtime", ""):
            with self.subTest(model=model):
                socket = FakeOpenAISocket()

                async def factory(url: str, headers: dict[str, str], timeout: float):
                    self.assertNotIn("OpenAI-Beta", headers)
                    return socket

                connection = await OpenAIRealtimeAgentProvider(factory).connect(
                    RealtimeAgentSessionConfig(
                        provider="openai_realtime", model=model, voice="cedar",
                        sample_rate=24000, profile=None, hermes_session_id=None,
                        provider_options={"api_key": "test-only", "input_transcription_enabled": False},
                    )
                )
                session = socket.sent[0]["session"]
                expected_model = model or "gpt-realtime-2.1"
                self.assertEqual(session["model"], expected_model)
                self.assertEqual(session["audio"], {
                    "input": {"format": {"type": "audio/pcm", "rate": 24000}, "turn_detection": None},
                    "output": {"format": {"type": "audio/pcm", "rate": 24000}, "voice": "cedar"},
                })
                self.assertNotIn("test-only", str(socket.sent))
                # Simulate acknowledgement of the exact format sent on the wire.
                socket.incoming.append({"type": "session.updated", "session": session})
                events = [event async for event in connection.events()]
                self.assertEqual(events[0].kind, ProviderEventKind.READY)
                self.assertEqual(events[0].payload["resolved_model"], expected_model)
                await connection.close()
                self.assertTrue(socket.closed)

    async def test_unsupported_pcm_rate_fails_before_opening_socket(self) -> None:
        async def factory(url: str, headers: dict[str, str], timeout: float):
            self.fail("Invalid PCM rate must not open a provider connection")

        for rate in (0, 8000, 16000, 48000):
            with self.subTest(rate=rate), self.assertRaisesRegex(ProviderUnavailable, "sample_rate=24000"):
                await OpenAIRealtimeAgentProvider(factory).connect(
                    RealtimeAgentSessionConfig(
                        provider="openai_realtime", model="gpt-realtime-2.1", voice="marin",
                        sample_rate=rate, profile=None, hermes_session_id=None,
                        provider_options={"api_key": "test-only"},
                    )
                )

    async def test_schema_rejection_remains_an_error_not_a_ready_event(self) -> None:
        socket = FakeOpenAISocket()

        async def factory(url: str, headers: dict[str, str], timeout: float):
            return socket

        connection = await OpenAIRealtimeAgentProvider(factory).connect(
            RealtimeAgentSessionConfig(
                provider="openai_realtime", model="gpt-realtime-2.1", voice="marin",
                sample_rate=24000, profile=None, hermes_session_id=None,
                provider_options={"api_key": "test-only"},
            )
        )
        socket.incoming.append({"type": "error", "error": {
            "type": "invalid_request_error",
            "message": "Missing required parameter: 'session.audio.output.format.rate'.",
        }})
        events = [event async for event in connection.events()]
        self.assertEqual(events[0].kind, ProviderEventKind.ERROR)
        self.assertIn("session.audio.output.format.rate", events[0].payload["message"])

    async def test_audio_tool_and_response_events_normalize(self) -> None:
        fake_socket = FakeOpenAISocket()

        async def factory(url: str, headers: dict[str, str], timeout: float):
            return fake_socket

        provider = OpenAIRealtimeAgentProvider(socket_factory=factory)
        with patch.dict(os.environ, {}, clear=True):
            connection = await provider.connect(
                RealtimeAgentSessionConfig(
                    provider="openai_realtime",
                    model="gpt-realtime-2",
                    voice="marin",
                    sample_rate=24000,
                    profile="victor",
                    hermes_session_id=None,
                    provider_options={"api_key": "openai-test"},
                )
            )

        pcm = b"\1\0" * 10
        await connection.send_audio(pcm, 16000)
        self.assertEqual(fake_socket.sent[-1]["type"], "input_audio_buffer.append")
        forwarded = base64.b64decode(fake_socket.sent[-1]["audio"])
        self.assertEqual(len(forwarded), 30)

        fake_socket.incoming.extend(
            [
                {
                    "type": "conversation.item.input_audio_transcription.completed",
                    "transcript": "hello hermes",
                },
                {
                    "type": "response.output_audio.delta",
                    "delta": base64.b64encode(pcm).decode("ascii"),
                    "response_id": "resp-1",
                },
                {
                    "type": "response.function_call_arguments.done",
                    "call_id": "call-1",
                    "name": "hermes_run_task",
                    "arguments": '{"text":"check status"}',
                    "response_id": "resp-1",
                },
                {
                    "type": "response.done",
                    "response": {
                        "id": "resp-1",
                        "output": [
                            {
                                "type": "function_call",
                                "call_id": "call-1",
                                "name": "hermes_run_task",
                                "arguments": '{"text":"check status"}',
                            }
                        ],
                    },
                },
                {
                    "type": "response.done",
                    "response": {
                        "id": "resp-2",
                        "output": [
                            {
                                "type": "function_call",
                                "call_id": "call-2",
                                "name": "hermes_get_status",
                                "arguments": '{"run_id":"run-1"}',
                            }
                        ],
                    },
                },
            ]
        )
        events = []
        async for event in connection.events():
            events.append(event)

        self.assertEqual(events[0].kind, ProviderEventKind.INPUT_TRANSCRIPT_FINAL)
        self.assertEqual(events[0].payload["text"], "hello hermes")
        self.assertEqual(events[1].kind, ProviderEventKind.AUDIO_DELTA)
        self.assertEqual(events[1].payload["audio"], pcm)
        self.assertEqual(events[2].kind, ProviderEventKind.FUNCTION_CALL_COMPLETED)
        self.assertEqual(events[2].payload["call"].name, "hermes_run_task")
        self.assertEqual(events[2].payload["call"].arguments["text"], "check status")
        self.assertEqual(events[3].kind, ProviderEventKind.RESPONSE_DONE)
        self.assertEqual(events[3].response_id, "resp-1")
        self.assertEqual(events[4].kind, ProviderEventKind.FUNCTION_CALL_COMPLETED)
        self.assertEqual(events[4].response_id, "resp-2")
        self.assertEqual(events[4].payload["call"].name, "hermes_get_status")
        self.assertEqual(events[4].payload["call"].arguments["run_id"], "run-1")

        await connection.send_tool_result("call-1", {"ok": True})
        await connection.request_response()
        self.assertEqual(fake_socket.sent[-2]["type"], "conversation.item.create")
        self.assertEqual(fake_socket.sent[-2]["item"]["type"], "function_call_output")
        self.assertEqual(fake_socket.sent[-1], {"type": "response.create"})

        await connection.commit_audio()
        self.assertEqual(fake_socket.sent[-1], {"type": "input_audio_buffer.commit"})

        await connection.send_text("Say a short settings test.")
        self.assertEqual(fake_socket.sent[-2]["type"], "conversation.item.create")
        item = fake_socket.sent[-2]["item"]
        self.assertEqual(item["type"], "message")
        self.assertEqual(item["role"], "user")
        self.assertEqual(
            item["content"],
            [{"type": "input_text", "text": "Say a short settings test."}],
        )
        self.assertEqual(fake_socket.sent[-1], {"type": "response.create"})

        await connection.request_response(
            instructions="Speak this result.",
            exact_text="Background answer ready.",
        )
        self.assertEqual(
            fake_socket.sent[-1],
            {"type": "response.create", "response": {"instructions": "Speak this result."}},
        )

        await connection.request_response()
        self.assertEqual(fake_socket.sent[-1], {"type": "response.create"})

        await connection.cancel_response()
        await connection.clear_audio()
        self.assertEqual(fake_socket.sent[-2], {"type": "response.cancel"})
        self.assertEqual(fake_socket.sent[-1], {"type": "input_audio_buffer.clear"})


if __name__ == "__main__":
    unittest.main()
