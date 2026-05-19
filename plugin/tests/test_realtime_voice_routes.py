"""Tests for the /voice/realtime relay route."""

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
from plugin.relay import provider_options
from plugin.relay import voice_auth
from plugin.relay.realtime_voice import _read_relay_xai_oauth_token
from plugin.relay.server import create_app


class RealtimeVoiceRoutesTests(AioHTTPTestCase):
    async def get_application(self) -> web.Application:
        self._tmpdir = tempfile.TemporaryDirectory()
        self._voice_auth_patches: list[tuple[str, object]] = []
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
        for name, original in reversed(getattr(self, "_voice_auth_patches", [])):
            setattr(voice_auth, name, original)
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

    def _bearer(self, token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    def _patch_voice_auth(self, name: str, value) -> None:
        self._voice_auth_patches.append((name, getattr(voice_auth, name)))
        setattr(voice_auth, name, value)

    def _stub_api_token_validator(self, valid_tokens: set[str]):
        calls: list[tuple[str, str]] = []

        async def _fake_validate(webapi_url: str, token: str) -> bool:
            calls.append((webapi_url, token))
            return token in valid_tokens

        self._patch_voice_auth("_validate_hermes_api_token", _fake_validate)
        return calls

    async def _next_ws_event(self, ws) -> dict:
        msg = await ws.receive(timeout=5)
        self.assertEqual(msg.type, WSMsgType.TEXT, msg)
        payload = json.loads(msg.data)
        self.assertIsInstance(payload, dict)
        return payload

    async def test_realtime_session_disabled_returns_404(self) -> None:
        self._server().config.realtime_voice_enabled = False
        token = await self._make_session()

        resp = await self.client.post(
            "/voice/realtime/session",
            json={},
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 404)

    async def test_realtime_config_requires_auth(self) -> None:
        resp = await self.client.get("/voice/realtime/config")
        self.assertEqual(resp.status, 401)

    async def test_realtime_config_returns_provider_info(self) -> None:
        token = await self._make_session()

        resp = await self.client.get(
            "/voice/realtime/config",
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertTrue(body["enabled"])
        self.assertEqual(body["default_provider"], "stub")
        self.assertEqual(
            body["config_path"],
            os.path.join(self._tmpdir.name, "relay-config.yaml"),
        )
        self.assertIn("providers", body)
        self.assertIn("auth", body)
        providers_by_id = {item["id"]: item for item in body["providers"]}
        self.assertIn("grok-voice-latest", providers_by_id["xai_realtime"]["models"])
        self.assertIn("eve", providers_by_id["xai_realtime"]["voices"])
        self.assertIn("ara", providers_by_id["xai_realtime"]["voices"])
        self.assertIn("rex", providers_by_id["xai_realtime"]["voices"])
        self.assertIn("sal", providers_by_id["xai_realtime"]["voices"])
        self.assertIn("leo", providers_by_id["xai_realtime"]["voices"])
        self.assertIn(24000, providers_by_id["xai_realtime"]["sample_rates"])
        self.assertIn("gpt-realtime-2", providers_by_id["openai_realtime"]["models"])
        self.assertIn("marin", providers_by_id["openai_realtime"]["voices"])
        self.assertIn("cedar", providers_by_id["openai_realtime"]["voices"])

    async def test_realtime_provider_options_returns_selected_provider_metadata(self) -> None:
        token = await self._make_session()
        original = provider_options._env_xai_option_auth
        provider_options._env_xai_option_auth = lambda: None

        try:
            resp = await self.client.get(
                "/voice/realtime/providers/xai_realtime/options",
                headers=self._bearer(token),
            )
        finally:
            provider_options._env_xai_option_auth = original

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertEqual(body["mode"], "realtime_voice")
        self.assertEqual(body["protocol"], "hermes.voice.realtime.options.v0")
        self.assertEqual(body["schema_version"], 1)
        self.assertEqual(body["provider_id"], "xai_realtime")
        self.assertEqual(body["default_provider"], "stub")
        self.assertEqual(body["provider"]["id"], "xai_realtime")
        self.assertIn("grok-voice-latest", body["provider"]["models"])
        self.assertIn("eve", body["provider"]["voices"])
        self.assertIn("ara", body["provider"]["voices"])
        self.assertIn("rex", body["provider"]["voices"])
        self.assertIn("sal", body["provider"]["voices"])
        self.assertIn("leo", body["provider"]["voices"])
        self.assertEqual(body["provider"]["voice_labels"]["sal"], "Sal - smooth, balanced")
        self.assertEqual(body["provider"]["voice_groups"][0]["id"], "xai_builtin")
        self.assertEqual(body["dynamic"]["status"], "auth_missing")

    async def test_realtime_provider_validate_reports_valid_selection(self) -> None:
        token = await self._make_session()

        resp = await self.client.post(
            "/voice/realtime/providers/openai_realtime/validate",
            json={"model": "gpt-realtime-2", "voice": "cedar", "sample_rate": 24000},
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertTrue(body["valid"])
        self.assertEqual(body["protocol"], "hermes.voice.realtime.validate.v0")
        self.assertEqual(body["summary"], "ok")

    async def test_realtime_provider_options_rejects_tts_only_provider(self) -> None:
        token = await self._make_session()

        resp = await self.client.get(
            "/voice/realtime/providers/xai_tts/options",
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 400)
        self.assertIn("not a realtime voice provider", await resp.text())

    async def test_realtime_config_patch_persists_relay_owned_defaults(self) -> None:
        token = await self._make_session()

        resp = await self.client.patch(
            "/voice/realtime/config",
            json={
                "enabled": True,
                "provider": "stub",
                "model": "patched-tone",
                "voice": "square",
                "sample_rate": 16000,
            },
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["default_provider"], "stub")
        self.assertEqual(body["default_model"], "patched-tone")
        self.assertEqual(body["default_voice"], "square")
        self.assertEqual(body["sample_rate"], 16000)
        self.assertEqual(
            body["updated"],
            ["enabled", "model", "provider", "sample_rate", "voice"],
        )
        self.assertEqual(self._server().config.realtime_voice_model, "patched-tone")

        with open(
            self._server().config.realtime_voice_config_path,
            "r",
            encoding="utf-8",
        ) as fh:
            saved = yaml.safe_load(fh)
        self.assertEqual(saved["realtime_voice"]["voice"], "square")
        self.assertEqual(saved["realtime_voice"]["sample_rate"], 16000)

    async def test_realtime_config_patch_rejects_unsupported_fields(self) -> None:
        token = await self._make_session()

        resp = await self.client.patch(
            "/voice/realtime/config",
            json={"xai_oauth_path": "/tmp/auth.json"},
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 400)
        self.assertIn("unsupported", await resp.text())

    async def test_realtime_config_patch_rejects_unknown_provider(self) -> None:
        token = await self._make_session()

        resp = await self.client.patch(
            "/voice/realtime/config",
            json={"provider": "missing_provider"},
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 400)

    async def test_realtime_session_rejects_expired_grant(self) -> None:
        token = await self._make_session()
        session = self._server().sessions.get_session(token)
        self.assertIsNotNone(session)
        assert session is not None
        session.grants["voice:realtime"] = time.time() - 1

        resp = await self.client.post(
            "/voice/realtime/session",
            json={},
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 403)

    async def test_realtime_accepts_valid_hermes_api_bearer(self) -> None:
        calls = self._stub_api_token_validator({"api-token"})

        resp = await self.client.get(
            "/voice/realtime/config",
            headers=self._bearer("api-token"),
        )

        self.assertEqual(resp.status, 200)
        self.assertEqual(calls, [("http://localhost:8642", "api-token")])

    async def test_realtime_websocket_streams_stub_pcm(self) -> None:
        token = await self._make_session()
        resp = await self.client.post(
            "/voice/realtime/session",
            json={"provider": "stub", "model": "local-tone", "voice": "sine"},
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])

        ws = await self.client.ws_connect(
            body["websocket_path"],
            headers=self._bearer(token),
        )
        try:
            ready = await self._next_ws_event(ws)
            self.assertEqual(ready["type"], "voice.session.ready")

            await ws.send_json(
                {
                    "type": "input_audio.append",
                    "sample_rate": 16000,
                    "audio_base64": base64.b64encode(b"\0" * 320).decode("ascii"),
                }
            )
            audio_received = await self._next_ws_event(ws)
            self.assertEqual(audio_received["type"], "voice.input_audio.received")
            self.assertEqual(audio_received["byte_count"], 320)
            self.assertEqual(audio_received["total_bytes"], 320)

            await ws.send_json(
                {
                    "type": "input_audio.append",
                    "sample_rate": 16000,
                    "audio_base64": base64.b64encode(b"\1" * 160).decode("ascii"),
                }
            )
            audio_received = await self._next_ws_event(ws)
            self.assertEqual(audio_received["type"], "voice.input_audio.received")
            self.assertEqual(audio_received["byte_count"], 160)
            self.assertEqual(audio_received["total_bytes"], 480)

            await ws.send_json(
                {
                    "type": "response.create",
                    "text": "Testing realtime relay voice.",
                    "tool_scaffold": True,
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
            self.assertIn("voice.tool.requested", event_types)
            self.assertIn("voice.tool.completed", event_types)
            self.assertIn("voice.audio.delta", event_types)
            self.assertIn("voice.audio.done", event_types)
            self.assertIn("voice.response.done", event_types)

            started = next(event for event in events if event["type"] == "voice.response.started")
            self.assertEqual(started["render_mode"], "verbatim")
            done = next(event for event in events if event["type"] == "voice.response.done")
            self.assertEqual(done["provider"], "stub")
            self.assertGreater(done["metrics"]["first_audio_ms"], 0)
            self.assertTrue(os.path.isfile(done["audio_path"]))
            self.assertTrue(os.path.isfile(done["event_log_path"]))
        finally:
            await ws.close()


class RealtimeVoiceAuthStoreTests(unittest.TestCase):
    def test_reads_hermes_xai_oauth_credential_pool(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            auth_path = os.path.join(tmpdir, "auth.json")
            with open(auth_path, "w", encoding="utf-8") as fh:
                json.dump(
                    {
                        "version": 1,
                        "providers": {
                            "xai-oauth": {
                                "tokens": {
                                    "access_token": "provider-token",
                                },
                            },
                        },
                        "credential_pool": {
                            "xai-oauth": [
                                {
                                    "access_token": "pool-token",
                                    "base_url": "https://api.x.ai/v1",
                                }
                            ]
                        },
                    },
                    fh,
                )

            token = _read_relay_xai_oauth_token(
                RelayConfig(realtime_voice_xai_oauth_path=auth_path)
            )

        self.assertIsNotNone(token)
        assert token is not None
        self.assertEqual(token.access_token, "pool-token")
        self.assertEqual(token.base_url, "https://api.x.ai/v1")
        self.assertIn("Hermes auth credential_pool.xai-oauth", token.source)


class RealtimeVoiceConfigTests(unittest.TestCase):
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

    def _clear_realtime_env(self) -> None:
        for name in (
            "RELAY_REALTIME_VOICE_ENABLED",
            "RELAY_REALTIME_VOICE_PROVIDER",
            "RELAY_REALTIME_VOICE_MODEL",
            "RELAY_REALTIME_VOICE_VOICE",
            "RELAY_REALTIME_VOICE_SAMPLE_RATE",
            "RELAY_REALTIME_VOICE_CONFIG",
            "RELAY_REALTIME_VOICE_RUN_DIR",
            "RELAY_REALTIME_VOICE_XAI_OAUTH_PATH",
        ):
            self._set_env(name, None)

    def test_from_env_reads_relay_realtime_voice_config(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            config_path = os.path.join(tmpdir, "relay-config.yaml")
            with open(config_path, "w", encoding="utf-8") as fh:
                fh.write(
                    "\n".join(
                        [
                            "realtime_voice:",
                            "  enabled: true",
                            "  provider: xai_realtime",
                            "  model: grok-voice-latest",
                            "  voice: eve",
                            "  sample_rate: 24000",
                            "  xai_oauth_path: ~/.hermes/auth.json",
                        ]
                    )
                )

            self._clear_realtime_env()
            self._set_env("RELAY_REALTIME_VOICE_CONFIG", config_path)

            config = RelayConfig.from_env()

        self.assertTrue(config.realtime_voice_enabled)
        self.assertEqual(config.realtime_voice_provider, "xai_realtime")
        self.assertEqual(config.realtime_voice_model, "grok-voice-latest")
        self.assertEqual(config.realtime_voice_voice, "eve")
        self.assertEqual(config.realtime_voice_sample_rate, 24000)
        self.assertEqual(config.realtime_voice_config_path, config_path)
        self.assertEqual(config.realtime_voice_xai_oauth_path, "~/.hermes/auth.json")

    def test_from_env_ignores_realtime_voice_in_hermes_config(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            hermes_config_path = os.path.join(tmpdir, "hermes-config.yaml")
            relay_config_path = os.path.join(tmpdir, "missing-relay-config.yaml")
            with open(hermes_config_path, "w", encoding="utf-8") as fh:
                fh.write(
                    "\n".join(
                        [
                            "realtime_voice:",
                            "  provider: stub",
                            "  model: should-not-load",
                            "  voice: should-not-load",
                        ]
                    )
                )

            self._clear_realtime_env()
            self._set_env("RELAY_HERMES_CONFIG", hermes_config_path)
            self._set_env("RELAY_REALTIME_VOICE_CONFIG", relay_config_path)

            config = RelayConfig.from_env()

        self.assertEqual(config.realtime_voice_provider, "xai_realtime")
        self.assertEqual(config.realtime_voice_model, "grok-voice-latest")
        self.assertEqual(config.realtime_voice_voice, "eve")

    def test_realtime_env_overrides_relay_config_defaults(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            config_path = os.path.join(tmpdir, "relay-config.yaml")
            with open(config_path, "w", encoding="utf-8") as fh:
                fh.write(
                    "\n".join(
                        [
                            "realtime_voice:",
                            "  provider: xai_realtime",
                            "  model: grok-voice-latest",
                            "  voice: eve",
                        ]
                    )
                )

            self._clear_realtime_env()
            self._set_env("RELAY_REALTIME_VOICE_CONFIG", config_path)
            self._set_env("RELAY_REALTIME_VOICE_PROVIDER", "stub")
            self._set_env("RELAY_REALTIME_VOICE_MODEL", "local-tone")
            self._set_env("RELAY_REALTIME_VOICE_VOICE", "sine")

            config = RelayConfig.from_env()

        self.assertEqual(config.realtime_voice_provider, "stub")
        self.assertEqual(config.realtime_voice_model, "local-tone")
        self.assertEqual(config.realtime_voice_voice, "sine")


if __name__ == "__main__":
    unittest.main()
