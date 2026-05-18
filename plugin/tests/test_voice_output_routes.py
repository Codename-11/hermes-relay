"""Tests for the provider-neutral /voice/output relay route."""

from __future__ import annotations

import base64
import json
import os
import tempfile
import time
import unittest

from aiohttp import WSMsgType, web
from aiohttp.test_utils import AioHTTPTestCase
import yaml

from plugin.relay.config import RelayConfig
from plugin.relay import voice_auth
from plugin.relay.server import create_app


class VoiceOutputRoutesTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        self._tmpdir = tempfile.TemporaryDirectory()
        self._voice_auth_patches: list[tuple[str, object]] = []
        voice_auth._VALIDATION_CACHE.clear()
        config = RelayConfig(
            voice_output_enabled=True,
            voice_output_provider="stub",
            voice_output_model="local-tone",
            voice_output_voice="sine",
            voice_output_config_path=os.path.join(
                self._tmpdir.name,
                "relay-config.yaml",
            ),
            voice_output_run_dir=self._tmpdir.name,
        )
        return create_app(config)

    async def tearDownAsync(self) -> None:
        await super().tearDownAsync()
        for name, original in reversed(getattr(self, "_voice_auth_patches", [])):
            setattr(voice_auth, name, original)
        voice_auth._VALIDATION_CACHE.clear()
        tmpdir = getattr(self, "_tmpdir", None)
        if tmpdir is not None:
            tmpdir.cleanup()

    def _server(self):
        return self.app["server"]

    async def _make_session(self) -> str:
        session = self._server().sessions.create_session("test-phone", "test-id")
        return session.token

    def _bearer(self, token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    async def _next_ws_event(self, ws) -> dict:
        msg = await ws.receive(timeout=5)
        self.assertEqual(msg.type, WSMsgType.TEXT, msg)
        payload = json.loads(msg.data)
        self.assertIsInstance(payload, dict)
        return payload

    async def test_voice_output_config_requires_auth(self) -> None:
        resp = await self.client.get("/voice/output/config")
        self.assertEqual(resp.status, 401)

    async def test_voice_output_config_returns_renderer_defaults(self) -> None:
        token = await self._make_session()

        resp = await self.client.get(
            "/voice/output/config",
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertTrue(body["enabled"])
        self.assertEqual(body["protocol"], "hermes.voice.output.v0")
        self.assertEqual(body["default_provider"], "stub")
        self.assertEqual(body["default_model"], "local-tone")
        self.assertEqual(body["default_voice"], "sine")
        self.assertEqual(body["fallback_provider"], "legacy_hermes_tts")
        provider_ids = {item["id"] for item in body["providers"]}
        self.assertIn("xai_tts", provider_ids)
        self.assertIn("openai_tts", provider_ids)
        self.assertIn("stub", provider_ids)
        self.assertNotIn("xai_realtime", provider_ids)

    async def test_voice_output_config_patch_persists_relay_owned_defaults(self) -> None:
        token = await self._make_session()

        resp = await self.client.patch(
            "/voice/output/config",
            json={
                "enabled": True,
                "provider": "stub",
                "model": "patched-tone",
                "voice": "square",
                "sample_rate": 16000,
                "language": "en",
                "codec": "pcm",
                "optimize_streaming_latency": 0,
                "text_normalization": True,
                "fallback_enabled": False,
            },
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["default_provider"], "stub")
        self.assertEqual(body["default_model"], "patched-tone")
        self.assertEqual(body["default_voice"], "square")
        self.assertEqual(body["sample_rate"], 16000)
        self.assertFalse(body["fallback_enabled"])
        self.assertEqual(self._server().config.voice_output_model, "patched-tone")

        with open(
            self._server().config.voice_output_config_path,
            "r",
            encoding="utf-8",
        ) as fh:
            saved = yaml.safe_load(fh)
        self.assertEqual(saved["voice_output"]["voice"], "square")
        self.assertEqual(saved["voice_output"]["sample_rate"], 16000)
        self.assertFalse(saved["voice_output"]["fallback_enabled"])

    async def test_voice_output_config_patch_rejects_realtime_provider(self) -> None:
        token = await self._make_session()

        resp = await self.client.patch(
            "/voice/output/config",
            json={"provider": "xai_realtime"},
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 400)
        self.assertIn("not a streaming TTS renderer", await resp.text())

    async def test_voice_output_session_disabled_returns_404(self) -> None:
        self._server().config.voice_output_enabled = False
        token = await self._make_session()

        resp = await self.client.post(
            "/voice/output/session",
            json={},
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 404)

    async def test_voice_output_websocket_streams_stub_pcm(self) -> None:
        token = await self._make_session()
        resp = await self.client.post(
            "/voice/output/session",
            json={"provider": "stub", "model": "local-tone", "voice": "sine"},
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertEqual(body["protocol"], "hermes.voice.output.v0")

        ws = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready = await self._next_ws_event(ws)
            self.assertEqual(ready["type"], "voice.session.ready")
            self.assertEqual(ready["output_mode"], "streaming_tts_renderer")

            await ws.send_json(
                {
                    "type": "response.create",
                    "text": "Testing provider neutral output.",
                    "render_mode": "verbatim",
                }
            )

            events: list[dict] = []
            for _ in range(32):
                event = await self._next_ws_event(ws)
                events.append(event)
                if event["type"] == "voice.response.done":
                    break

            event_types = [event["type"] for event in events]
            self.assertIn("voice.response.started", event_types)
            self.assertIn("voice.audio.delta", event_types)
            self.assertIn("voice.audio.done", event_types)
            self.assertIn("voice.response.done", event_types)

            first_audio = next(event for event in events if event["type"] == "voice.audio.delta")
            self.assertGreater(len(base64.b64decode(first_audio["audio_base64"])), 0)
            done = next(event for event in events if event["type"] == "voice.response.done")
            self.assertEqual(done["provider"], "stub")
            self.assertEqual(done["output_mode"], "streaming_tts_renderer")
            self.assertGreater(done["metrics"]["first_audio_ms"], 0)
            self.assertTrue(os.path.isfile(done["audio_path"]))
            self.assertTrue(os.path.isfile(done["event_log_path"]))
        finally:
            await ws.close()

    async def test_voice_output_rejects_expired_tts_grant(self) -> None:
        token = await self._make_session()
        session = self._server().sessions.get_session(token)
        self.assertIsNotNone(session)
        assert session is not None
        session.grants["voice:tts"] = time.time() - 1

        resp = await self.client.post(
            "/voice/output/session",
            json={},
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 403)


class VoiceOutputConfigTests(unittest.TestCase):
    def setUp(self) -> None:
        self._env_patches: list[tuple[str, str | None]] = []

    def tearDown(self) -> None:
        for name, original in reversed(self._env_patches):
            if original is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = original

    def _set_env(self, name: str, value: str | None) -> None:
        self._env_patches.append((name, os.environ.get(name)))
        if value is None:
            os.environ.pop(name, None)
        else:
            os.environ[name] = value

    def _clear_voice_output_env(self) -> None:
        for name in (
            "RELAY_VOICE_OUTPUT_ENABLED",
            "RELAY_VOICE_OUTPUT_PROVIDER",
            "RELAY_VOICE_OUTPUT_MODEL",
            "RELAY_VOICE_OUTPUT_VOICE",
            "RELAY_VOICE_OUTPUT_SAMPLE_RATE",
            "RELAY_VOICE_OUTPUT_LANGUAGE",
            "RELAY_VOICE_OUTPUT_CODEC",
            "RELAY_VOICE_OUTPUT_OPTIMIZE_LATENCY",
            "RELAY_VOICE_OUTPUT_TEXT_NORMALIZATION",
            "RELAY_VOICE_OUTPUT_FALLBACK_ENABLED",
            "RELAY_VOICE_OUTPUT_CONFIG",
            "RELAY_VOICE_OUTPUT_RUN_DIR",
        ):
            self._set_env(name, None)

    def test_from_env_reads_relay_voice_output_config(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            config_path = os.path.join(tmpdir, "relay-config.yaml")
            with open(config_path, "w", encoding="utf-8") as fh:
                fh.write(
                    "\n".join(
                        [
                            "voice_output:",
                            "  enabled: true",
                            "  provider: openai_tts",
                            "  model: gpt-4o-mini-tts",
                            "  voice: coral",
                            "  sample_rate: 24000",
                            "  language: en",
                            "  codec: pcm",
                            "  optimize_streaming_latency: 1",
                            "  text_normalization: false",
                            "  fallback_enabled: true",
                        ]
                    )
                )

            self._clear_voice_output_env()
            self._set_env("RELAY_VOICE_OUTPUT_CONFIG", config_path)

            config = RelayConfig.from_env()

        self.assertTrue(config.voice_output_enabled)
        self.assertEqual(config.voice_output_provider, "openai_tts")
        self.assertEqual(config.voice_output_model, "gpt-4o-mini-tts")
        self.assertEqual(config.voice_output_voice, "coral")
        self.assertEqual(config.voice_output_sample_rate, 24000)
        self.assertEqual(config.voice_output_config_path, config_path)

    def test_voice_output_env_overrides_relay_config_defaults(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            config_path = os.path.join(tmpdir, "relay-config.yaml")
            with open(config_path, "w", encoding="utf-8") as fh:
                fh.write(
                    "\n".join(
                        [
                            "voice_output:",
                            "  provider: xai_tts",
                            "  model: xai-tts",
                            "  voice: eve",
                        ]
                    )
                )

            self._clear_voice_output_env()
            self._set_env("RELAY_VOICE_OUTPUT_CONFIG", config_path)
            self._set_env("RELAY_VOICE_OUTPUT_PROVIDER", "stub")
            self._set_env("RELAY_VOICE_OUTPUT_MODEL", "local-tone")
            self._set_env("RELAY_VOICE_OUTPUT_VOICE", "sine")

            config = RelayConfig.from_env()

        self.assertEqual(config.voice_output_provider, "stub")
        self.assertEqual(config.voice_output_model, "local-tone")
        self.assertEqual(config.voice_output_voice, "sine")


if __name__ == "__main__":
    unittest.main()
