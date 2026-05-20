"""Tests for the experimental /voice/realtime-agent relay route."""

from __future__ import annotations

import base64
import json
import os
import tempfile
import unittest
from typing import Any

from aiohttp import WSMsgType, web
from aiohttp.test_utils import AioHTTPTestCase

from plugin.relay import provider_options, voice_auth
from plugin.relay.config import RelayConfig
from plugin.relay.realtime_agent.hermes_tool_broker import HermesTaskRequest
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
            "profile": request.profile,
        }


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
            done = next(event for event in events if event["type"] == "voice.response.done")
            self.assertEqual(done["provider"], "stub")
            self.assertTrue(os.path.isfile(done["audio_path"]))
            self.assertTrue(os.path.isfile(done["event_log_path"]))
        finally:
            await ws.close()


if __name__ == "__main__":
    unittest.main()
