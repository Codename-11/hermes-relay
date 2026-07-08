"""Tests for the OpenAI provider-native realtime-agent adapter."""

from __future__ import annotations

import base64
import os
import unittest
from typing import Any
from unittest.mock import patch

import aiohttp

from plugin.relay.realtime_agent.models import (
    HERMES_TOOL_SURFACE,
    ProviderEventKind,
    RealtimeAgentSessionConfig,
)
from plugin.relay.realtime_agent.providers.openai import OpenAIRealtimeAgentProvider
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


class OpenAIRealtimeAgentProviderTests(unittest.IsolatedAsyncioTestCase):
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
        self.assertEqual(session["audio"]["output"]["format"], {"type": "audio/pcm"})
        self.assertEqual(session["audio"]["output"]["voice"], "marin")
        self.assertEqual(session["tool_choice"], "auto")
        tool_names = [tool["name"] for tool in session["tools"]]
        self.assertEqual(tool_names, list(HERMES_TOOL_SURFACE))
        self.assertTrue(all(tool["type"] == "function" for tool in session["tools"]))
        run_tool = next(tool for tool in session["tools"] if tool["name"] == "hermes_run_task")
        self.assertIn("current checks", run_tool["description"])
        self.assertIn("live/external data", run_tool["description"])
        self.assertIn("speech-safe summary", run_tool["description"])

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

        await connection.request_response(instructions="Speak this result.")
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
