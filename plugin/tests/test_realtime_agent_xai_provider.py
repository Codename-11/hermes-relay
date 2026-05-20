"""Tests for the xAI provider-native realtime-agent adapter."""

from __future__ import annotations

import base64
import os
import unittest
from typing import Any
from unittest.mock import patch

from plugin.relay.realtime_agent.models import (
    HERMES_TOOL_SURFACE,
    ProviderEventKind,
    RealtimeAgentSessionConfig,
)
from plugin.relay.realtime_agent.providers.xai import XAIRealtimeAgentProvider


class FakeXAISocket:
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


class XAIRealtimeAgentProviderTests(unittest.IsolatedAsyncioTestCase):
    async def test_connect_sends_session_update_with_leo_pcm_manual_turns_and_hermes_tools(self) -> None:
        fake_socket = FakeXAISocket()
        captured: dict[str, Any] = {}

        async def factory(url: str, headers: dict[str, str], timeout: float):
            captured["url"] = url
            captured["headers"] = headers
            captured["timeout"] = timeout
            return fake_socket

        provider = XAIRealtimeAgentProvider(socket_factory=factory)
        with patch.dict(os.environ, {}, clear=True):
            connection = await provider.connect(
                RealtimeAgentSessionConfig(
                    provider="xai_realtime",
                    model="grok-voice-latest",
                    voice="leo",
                    sample_rate=24000,
                    profile="victor",
                    hermes_session_id="chat-123",
                    provider_options={"oauth_access_token": "xai-test"},
                )
            )

        self.assertIs(connection.socket, fake_socket)
        self.assertEqual(
            captured["url"],
            "wss://api.x.ai/v1/realtime?model=grok-voice-latest",
        )
        self.assertEqual(captured["headers"]["Authorization"], "Bearer xai-test")
        session_update = fake_socket.sent[0]
        self.assertEqual(session_update["type"], "session.update")
        session = session_update["session"]
        self.assertEqual(session["voice"], "leo")
        self.assertIsNone(session["turn_detection"])
        self.assertEqual(session["audio"]["input"]["format"]["type"], "audio/pcm")
        self.assertEqual(session["audio"]["input"]["format"]["rate"], 24000)
        self.assertEqual(session["audio"]["output"]["format"]["rate"], 24000)
        tool_names = [tool["name"] for tool in session["tools"]]
        self.assertEqual(tool_names, list(HERMES_TOOL_SURFACE))
        self.assertTrue(all(tool["type"] == "function" for tool in session["tools"]))
        run_tool = next(tool for tool in session["tools"] if tool["name"] == "hermes_run_task")
        self.assertIn("current checks", run_tool["description"])
        self.assertIn("live/external data", run_tool["description"])
        self.assertIn("latest/versioned info", run_tool["description"])
        self.assertIn("media/artifact handling", run_tool["description"])
        self.assertIn("speech-safe summary", run_tool["description"])

    async def test_audio_and_function_events_normalize(self) -> None:
        fake_socket = FakeXAISocket()

        async def factory(url: str, headers: dict[str, str], timeout: float):
            return fake_socket

        provider = XAIRealtimeAgentProvider(socket_factory=factory)
        with patch.dict(os.environ, {}, clear=True):
            connection = await provider.connect(
                RealtimeAgentSessionConfig(
                    provider="xai_realtime",
                    model="grok-voice-latest",
                    voice="leo",
                    sample_rate=24000,
                    profile="victor",
                    hermes_session_id=None,
                    provider_options={"oauth_access_token": "xai-test"},
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

        await connection.send_tool_result("call-1", {"ok": True})
        await connection.request_response()
        self.assertEqual(fake_socket.sent[-2]["item"]["type"], "function_call_output")
        self.assertEqual(fake_socket.sent[-1], {"type": "response.create"})

        await connection.commit_audio()
        self.assertEqual(fake_socket.sent[-2], {"type": "input_audio_buffer.commit"})
        self.assertEqual(fake_socket.sent[-1], {"type": "response.create"})


if __name__ == "__main__":
    unittest.main()
