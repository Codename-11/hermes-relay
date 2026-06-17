"""Tests for /voice/transcribe, /voice/synthesize, /voice/config routes.

Mirrors the ``test_relay_media_routes.py`` style: spins the real
``create_app`` with a ``RelayConfig``, drives routes through
``AioHTTPTestCase.client``, and asserts status + body.

The upstream ``tools.tts_tool`` / ``tools.transcription_tools`` / ``tools.voice_mode``
modules normally live in the hermes-agent venv, which isn't available on
Claude-side Windows checkouts. We inject fake modules into ``sys.modules``
under those exact names before the handlers run — the handlers import
lazily inside the request, so the fake module is what they get.

Use ``python -m unittest plugin.tests.test_voice_routes`` to run — do NOT
use ``pytest`` (the pre-existing ``conftest.py`` imports the ``responses``
module which isn't in this venv).
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import types
import unittest

from aiohttp import web, FormData
from aiohttp.test_utils import AioHTTPTestCase


def _install_fake_tools_modules() -> None:
    """Register fake ``tools.*`` modules under their upstream names.

    Tests can then swap the function attributes on these modules per-test
    to control what the handler sees. We install empty stubs that raise if
    actually called — the per-test ``setUp`` replaces them with mocks.
    """
    # Parent package
    tools_mod = types.ModuleType("tools")
    tools_mod.__path__ = []  # type: ignore[attr-defined]
    sys.modules.setdefault("tools", tools_mod)

    tts_mod = types.ModuleType("tools.tts_tool")

    def _default_tts(text, output_path=None):  # pragma: no cover
        raise AssertionError("text_to_speech_tool not stubbed for this test")

    def _default_load_tts_config():  # pragma: no cover
        return {}

    tts_mod.text_to_speech_tool = _default_tts
    tts_mod._load_tts_config = _default_load_tts_config
    sys.modules["tools.tts_tool"] = tts_mod

    stt_mod = types.ModuleType("tools.transcription_tools")

    def _default_stt(file_path, model=None):  # pragma: no cover
        raise AssertionError("transcribe_audio not stubbed for this test")

    def _default_load_stt_config():  # pragma: no cover
        return {}

    stt_mod.transcribe_audio = _default_stt
    stt_mod._load_stt_config = _default_load_stt_config
    sys.modules["tools.transcription_tools"] = stt_mod

    vm_mod = types.ModuleType("tools.voice_mode")

    def _default_check():  # pragma: no cover
        return {"ok": True}

    vm_mod.check_voice_requirements = _default_check
    sys.modules["tools.voice_mode"] = vm_mod


_install_fake_tools_modules()

# Imports that touch the real relay must happen AFTER the fake-module
# install — even though relay imports don't trigger the tools imports,
# keeping order consistent avoids surprises for future readers.
from plugin.relay.config import RelayConfig  # noqa: E402
from plugin.relay import voice_auth  # noqa: E402
from plugin.relay.server import create_app  # noqa: E402


class VoiceRoutesTests(AioHTTPTestCase):
    """Exercise the /voice/* routes through a live test server."""

    async def get_application(self) -> web.Application:
        self._tmp_files: list[str] = []
        self._voice_auth_patches: list[tuple[str, object]] = []
        self._env_patches: list[tuple[str, str | None]] = []
        voice_auth._VALIDATION_CACHE.clear()
        config = RelayConfig()
        app = create_app(config)
        return app

    async def tearDownAsync(self) -> None:
        await super().tearDownAsync()
        for name, original in reversed(getattr(self, "_voice_auth_patches", [])):
            setattr(voice_auth, name, original)
        voice_auth._VALIDATION_CACHE.clear()
        for name, original in reversed(getattr(self, "_env_patches", [])):
            if original is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = original
        for path in getattr(self, "_tmp_files", []):
            try:
                os.unlink(path)
            except OSError:
                pass

    # ── helpers ─────────────────────────────────────────────────────────

    def _server(self):
        return self.app["server"]

    async def _make_session(self) -> str:
        session = self._server().sessions.create_session(
            "test-device", "test-id"
        )
        return session.token

    def _bearer(self, token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    def _patch_voice_auth(self, name: str, value) -> None:
        self._voice_auth_patches.append((name, getattr(voice_auth, name)))
        setattr(voice_auth, name, value)

    def _set_env(self, name: str, value: str) -> None:
        self._env_patches.append((name, os.environ.get(name)))
        os.environ[name] = value

    def _stub_api_token_validator(self, valid_tokens: set[str]):
        calls: list[tuple[str, str]] = []

        async def _fake_validate(webapi_url: str, token: str) -> bool:
            calls.append((webapi_url, token))
            return token in valid_tokens

        self._patch_voice_auth("_validate_hermes_api_token", _fake_validate)
        return calls

    def _write_tmp(self, suffix: str, content: bytes) -> str:
        fh = tempfile.NamedTemporaryFile(
            suffix=suffix, prefix="hermes_voice_test_", delete=False
        )
        try:
            fh.write(content)
        finally:
            fh.close()
        self._tmp_files.append(fh.name)
        return fh.name

    # ── /voice/transcribe ──────────────────────────────────────────────

    async def test_transcribe_requires_auth(self) -> None:
        form = FormData()
        form.add_field(
            "file",
            b"fake-wav-bytes",
            filename="clip.wav",
            content_type="audio/wav",
        )
        resp = await self.client.post("/voice/transcribe", data=form)
        self.assertEqual(resp.status, 401)

    async def test_transcribe_invalid_bearer_returns_401(self) -> None:
        self._stub_api_token_validator(set())
        form = FormData()
        form.add_field(
            "file",
            b"fake-wav",
            filename="clip.wav",
            content_type="audio/wav",
        )
        resp = await self.client.post(
            "/voice/transcribe",
            data=form,
            headers={"Authorization": "Bearer not-real"},
        )
        self.assertEqual(resp.status, 401)

    async def test_transcribe_success(self) -> None:
        captured = {}

        def _fake_stt(file_path, model=None):
            captured["file_path"] = file_path
            captured["exists"] = os.path.isfile(file_path)
            with open(file_path, "rb") as fh:
                captured["bytes"] = fh.read()
            return {
                "success": True,
                "transcript": "hello world",
                "provider": "openai",
            }

        sys.modules["tools.transcription_tools"].transcribe_audio = _fake_stt

        token = await self._make_session()
        form = FormData()
        form.add_field(
            "file",
            b"\x52\x49\x46\x46fake-wav-body",
            filename="clip.wav",
            content_type="audio/wav",
        )
        resp = await self.client.post(
            "/voice/transcribe", data=form, headers=self._bearer(token)
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertEqual(body["text"], "hello world")
        self.assertEqual(body["provider"], "openai")

        self.assertIn("file_path", captured)
        self.assertTrue(captured["exists"])
        self.assertEqual(captured["bytes"], b"\x52\x49\x46\x46fake-wav-body")
        # Handler should unlink the temp file after the call.
        self.assertFalse(os.path.exists(captured["file_path"]))

    async def test_transcribe_accepts_valid_hermes_api_bearer(self) -> None:
        calls = self._stub_api_token_validator({"api-token"})

        def _fake_stt(file_path, model=None):
            return {
                "success": True,
                "transcript": "api token transcript",
                "provider": "openai",
            }

        sys.modules["tools.transcription_tools"].transcribe_audio = _fake_stt

        form = FormData()
        form.add_field(
            "file",
            b"fake-wav-body",
            filename="clip.wav",
            content_type="audio/wav",
        )
        resp = await self.client.post(
            "/voice/transcribe", data=form, headers=self._bearer("api-token")
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertEqual(body["text"], "api token transcript")
        self.assertEqual(calls, [("http://localhost:8642", "api-token")])

    async def test_transcribe_backend_failure_returns_500(self) -> None:
        def _fake_stt(file_path, model=None):
            return {"success": False, "error": "whisper unreachable"}

        sys.modules["tools.transcription_tools"].transcribe_audio = _fake_stt

        token = await self._make_session()
        form = FormData()
        form.add_field(
            "file", b"bytes", filename="x.wav", content_type="audio/wav"
        )
        resp = await self.client.post(
            "/voice/transcribe", data=form, headers=self._bearer(token)
        )
        self.assertEqual(resp.status, 500)
        body = await resp.json()
        self.assertFalse(body["success"])
        self.assertIn("whisper", body["error"])

    async def test_transcribe_missing_file_field_returns_400(self) -> None:
        token = await self._make_session()
        form = FormData()
        form.add_field("other", "not-a-file")
        resp = await self.client.post(
            "/voice/transcribe", data=form, headers=self._bearer(token)
        )
        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertFalse(body["success"])

    # ── /voice/synthesize ──────────────────────────────────────────────

    async def test_synthesize_requires_auth(self) -> None:
        resp = await self.client.post(
            "/voice/synthesize", json={"text": "hi"}
        )
        self.assertEqual(resp.status, 401)

    async def test_synthesize_success(self) -> None:
        # Create a real mp3-ish temp file to stream back.
        payload = b"ID3\x03fake-mp3-body-zzzzzz"
        mp3_path = self._write_tmp(".mp3", payload)

        captured = {}

        def _fake_tts(text, output_path=None):
            captured["text"] = text
            captured["output_path"] = output_path
            return json.dumps({"success": True, "file_path": mp3_path})

        sys.modules["tools.tts_tool"].text_to_speech_tool = _fake_tts

        token = await self._make_session()
        resp = await self.client.post(
            "/voice/synthesize",
            json={"text": "hello there"},
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 200)
        self.assertEqual(resp.headers.get("Content-Type"), "audio/mpeg")
        body = await resp.read()
        self.assertEqual(body, payload)
        self.assertEqual(captured["text"], "hello there")
        # The relay now owns the output path and deletes the artifact after
        # streaming, so synthesis never leaks files into ~/voice-memos.
        self.assertIsNotNone(captured["output_path"])
        self.assertFalse(os.path.isfile(mp3_path))

    async def test_synthesize_applies_gemini_overrides(self) -> None:
        # When Gemini is the effective provider, per-request voice/model/
        # audio_tags/persona are merged into a config copy and the Gemini
        # generator is invoked directly — text_to_speech_tool is bypassed.
        unset = object()
        payload = b"ID3\x03gemini-mp3-body"
        captured: dict = {}
        tts_mod = sys.modules["tools.tts_tool"]
        saved = {
            name: getattr(tts_mod, name, unset)
            for name in (
                "text_to_speech_tool",
                "_load_tts_config",
                "_get_provider",
                "_generate_gemini_tts",
            )
        }

        def _fake_generate_gemini(text, output_path, tts_config):
            captured["text"] = text
            captured["gemini"] = dict(tts_config.get("gemini") or {})
            with open(output_path, "wb") as fh:
                fh.write(payload)
            return output_path

        def _no_tts_tool(*args, **kwargs):
            raise AssertionError("override path must bypass text_to_speech_tool")

        tts_mod._load_tts_config = lambda: {"provider": "gemini", "gemini": {"voice": "Kore"}}
        tts_mod._get_provider = lambda cfg: cfg.get("provider", "gemini")
        tts_mod._generate_gemini_tts = _fake_generate_gemini
        tts_mod.text_to_speech_tool = _no_tts_tool

        try:
            token = await self._make_session()
            resp = await self.client.post(
                "/voice/synthesize",
                json={
                    "text": "hello there",
                    "voice": "Puck",
                    "model": "gemini-3.1-flash-tts-preview",
                    "audio_tags": True,
                    "style": "Warm, calm narrator.",
                },
                headers=self._bearer(token),
            )
            self.assertEqual(resp.status, 200)
            self.assertEqual(await resp.read(), payload)
            self.assertEqual(captured["gemini"]["voice"], "Puck")
            self.assertEqual(captured["gemini"]["model"], "gemini-3.1-flash-tts-preview")
            self.assertTrue(captured["gemini"]["audio_tags"])
            # Inline persona/style is written to a temp persona_prompt_file.
            self.assertIn("persona_prompt_file", captured["gemini"])
        finally:
            for name, original in saved.items():
                if original is unset:
                    if hasattr(tts_mod, name):
                        delattr(tts_mod, name)
                else:
                    setattr(tts_mod, name, original)

    async def test_synthesize_applies_xai_overrides(self) -> None:
        # xAI maps the generic override vocabulary onto its config: voice→
        # voice_id, audio_tags→auto_speech_tags, plus language.
        unset = object()
        payload = b"ID3\x03xai-mp3-body"
        captured: dict = {}
        tts_mod = sys.modules["tools.tts_tool"]
        saved = {
            name: getattr(tts_mod, name, unset)
            for name in (
                "text_to_speech_tool",
                "_load_tts_config",
                "_get_provider",
                "_generate_xai_tts",
            )
        }

        def _fake_generate_xai(text, output_path, tts_config):
            captured["xai"] = dict(tts_config.get("xai") or {})
            with open(output_path, "wb") as fh:
                fh.write(payload)
            return output_path

        def _no_tts_tool(*args, **kwargs):
            raise AssertionError("override path must bypass text_to_speech_tool")

        tts_mod._load_tts_config = lambda: {"provider": "xai", "xai": {"voice_id": "eve"}}
        tts_mod._get_provider = lambda cfg: cfg.get("provider", "xai")
        tts_mod._generate_xai_tts = _fake_generate_xai
        tts_mod.text_to_speech_tool = _no_tts_tool

        try:
            token = await self._make_session()
            resp = await self.client.post(
                "/voice/synthesize",
                json={
                    "text": "hello",
                    "voice": "thomas",
                    "audio_tags": True,
                    "language": "en",
                },
                headers=self._bearer(token),
            )
            self.assertEqual(resp.status, 200)
            self.assertEqual(await resp.read(), payload)
            self.assertEqual(captured["xai"]["voice_id"], "thomas")
            self.assertTrue(captured["xai"]["auto_speech_tags"])
            self.assertEqual(captured["xai"]["language"], "en")
        finally:
            for name, original in saved.items():
                if original is unset:
                    if hasattr(tts_mod, name):
                        delattr(tts_mod, name)
                else:
                    setattr(tts_mod, name, original)

    async def test_synthesize_accepts_valid_hermes_api_bearer(self) -> None:
        calls = self._stub_api_token_validator({"api-token"})
        payload = b"ID3\x03api-token-mp3"
        mp3_path = self._write_tmp(".mp3", payload)

        def _fake_tts(text, output_path=None):
            return json.dumps({"success": True, "file_path": mp3_path})

        sys.modules["tools.tts_tool"].text_to_speech_tool = _fake_tts

        resp = await self.client.post(
            "/voice/synthesize",
            json={"text": "hello from api token"},
            headers=self._bearer("api-token"),
        )

        self.assertEqual(resp.status, 200)
        self.assertEqual(await resp.read(), payload)
        self.assertEqual(calls, [("http://localhost:8642", "api-token")])

    async def test_synthesize_rejects_expired_relay_voice_grant(self) -> None:
        token = await self._make_session()
        session = self._server().sessions.get_session(token)
        self.assertIsNotNone(session)
        assert session is not None
        session.grants["voice:tts"] = time.time() - 1

        resp = await self.client.post(
            "/voice/synthesize",
            json={"text": "should not synthesize"},
            headers=self._bearer(token),
        )

        self.assertEqual(resp.status, 403)

    async def test_synthesize_empty_text_rejected(self) -> None:
        token = await self._make_session()
        resp = await self.client.post(
            "/voice/synthesize",
            json={"text": "   "},
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 400)
        body = await resp.json()
        self.assertFalse(body["success"])

    async def test_synthesize_missing_text_rejected(self) -> None:
        token = await self._make_session()
        resp = await self.client.post(
            "/voice/synthesize",
            json={"not_text": "oops"},
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 400)

    async def test_synthesize_text_too_long_rejected(self) -> None:
        token = await self._make_session()
        resp = await self.client.post(
            "/voice/synthesize",
            json={"text": "x" * 5001},
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 400)

    async def test_synthesize_non_json_body_rejected(self) -> None:
        token = await self._make_session()
        resp = await self.client.post(
            "/voice/synthesize",
            data="not-json",
            headers={
                **self._bearer(token),
                "Content-Type": "application/json",
            },
        )
        self.assertEqual(resp.status, 400)

    async def test_synthesize_backend_failure_returns_500(self) -> None:
        def _fake_tts(text, output_path=None):
            return json.dumps({"success": False, "error": "elevenlabs 429"})

        sys.modules["tools.tts_tool"].text_to_speech_tool = _fake_tts

        token = await self._make_session()
        resp = await self.client.post(
            "/voice/synthesize",
            json={"text": "hi"},
            headers=self._bearer(token),
        )
        self.assertEqual(resp.status, 500)
        body = await resp.json()
        self.assertFalse(body["success"])
        self.assertIn("elevenlabs", body["error"])

    # ── /voice/config ──────────────────────────────────────────────────

    async def test_voice_config_requires_auth(self) -> None:
        resp = await self.client.get("/voice/config")
        self.assertEqual(resp.status, 401)

    async def test_voice_config_returns_providers(self) -> None:
        sys.modules["tools.tts_tool"]._load_tts_config = lambda: {
            "provider": "elevenlabs",
            "voice_id": "XZEfcFyBnzsNJrdvkWdI",
            "model": "eleven_turbo_v2_5",
        }
        sys.modules["tools.transcription_tools"]._load_stt_config = lambda: {
            "provider": "openai",
            "model": "whisper-1",
        }
        sys.modules["tools.voice_mode"].check_voice_requirements = lambda: {
            "tts": True,
            "stt": True,
        }

        token = await self._make_session()
        resp = await self.client.get(
            "/voice/config", headers=self._bearer(token)
        )
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertEqual(body["tts"]["provider"], "elevenlabs")
        self.assertEqual(body["tts"]["voice_id"], "XZEfcFyBnzsNJrdvkWdI")
        self.assertTrue(body["tts"]["enabled"])
        self.assertEqual(body["stt"]["provider"], "openai")
        self.assertEqual(body["stt"]["model"], "whisper-1")
        self.assertTrue(body["stt"]["enabled"])
        self.assertEqual(body["requirements"], {"tts": True, "stt": True})

    async def test_voice_config_fills_selected_provider_details_from_hermes_config(
        self,
    ) -> None:
        config_path = self._write_tmp(
            ".yaml",
            b"\n".join(
                [
                    b"tts:",
                    b"  provider: xai",
                    b"  xai:",
                    b"    voice_id: leo",
                    b"    sample_rate: 24000",
                    b"stt:",
                    b"  enabled: true",
                    b"  provider: openai",
                    b"  openai:",
                    b"    model: whisper-1",
                ]
            ),
        )
        self._server().config.hermes_config_path = config_path
        sys.modules["tools.tts_tool"]._load_tts_config = lambda: {
            "provider": "xai",
        }
        sys.modules["tools.transcription_tools"]._load_stt_config = lambda: {
            "provider": "openai",
        }
        sys.modules["tools.voice_mode"].check_voice_requirements = lambda: {
            "tts": True,
            "stt": True,
        }

        token = await self._make_session()
        resp = await self.client.get(
            "/voice/config", headers=self._bearer(token)
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(body["tts"]["provider"], "xai")
        self.assertEqual(body["tts"]["voice_id"], "leo")
        self.assertTrue(body["tts"]["enabled"])
        self.assertEqual(body["stt"]["provider"], "openai")
        self.assertEqual(body["stt"]["model"], "whisper-1")
        self.assertTrue(body["stt"]["enabled"])

    async def test_voice_config_accepts_valid_hermes_api_bearer(self) -> None:
        calls = self._stub_api_token_validator({"api-token"})
        sys.modules["tools.tts_tool"]._load_tts_config = lambda: {
            "provider": "elevenlabs",
            "voice_id": "voice-1",
            "model": "tts-model",
        }
        sys.modules["tools.transcription_tools"]._load_stt_config = lambda: {
            "provider": "openai",
            "model": "whisper-1",
        }
        sys.modules["tools.voice_mode"].check_voice_requirements = lambda: {
            "tts": True,
            "stt": True,
        }

        resp = await self.client.get(
            "/voice/config", headers=self._bearer("api-token")
        )

        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertTrue(body["success"])
        self.assertEqual(body["tts"]["voice_id"], "voice-1")
        self.assertEqual(calls, [("http://localhost:8642", "api-token")])

    async def test_voice_config_invalid_api_bearer_returns_401(self) -> None:
        self._stub_api_token_validator(set())
        resp = await self.client.get(
            "/voice/config", headers=self._bearer("not-a-session-or-api-token")
        )
        self.assertEqual(resp.status, 401)

    async def test_voice_config_rejects_non_loopback_plaintext_api_bearer(
        self,
    ) -> None:
        calls = self._stub_api_token_validator({"api-token"})
        self._patch_voice_auth("_is_loopback_remote", lambda request: False)

        resp = await self.client.get(
            "/voice/config", headers=self._bearer("api-token")
        )

        self.assertEqual(resp.status, 403)
        self.assertEqual(calls, [])
        body = await resp.text()
        self.assertIn("HTTPS", body)

    async def test_voice_config_accepts_tailnet_plaintext_api_bearer(
        self,
    ) -> None:
        calls = self._stub_api_token_validator({"api-token"})
        self._patch_voice_auth("_is_loopback_remote", lambda request: False)
        self._patch_voice_auth("_is_tailnet_remote", lambda request: True)
        sys.modules["tools.tts_tool"]._load_tts_config = lambda: {}
        sys.modules["tools.transcription_tools"]._load_stt_config = lambda: {}
        sys.modules["tools.voice_mode"].check_voice_requirements = lambda: {
            "ok": True,
        }

        resp = await self.client.get(
            "/voice/config", headers=self._bearer("api-token")
        )

        self.assertEqual(resp.status, 200)
        self.assertEqual(calls, [("http://localhost:8642", "api-token")])

    async def test_voice_config_accepts_trusted_proxy_https_api_bearer(
        self,
    ) -> None:
        self._stub_api_token_validator({"api-token"})
        self._patch_voice_auth("_is_loopback_remote", lambda request: False)
        self._set_env("RELAY_TRUST_PROXY_HEADERS", "1")
        sys.modules["tools.tts_tool"]._load_tts_config = lambda: {}
        sys.modules["tools.transcription_tools"]._load_stt_config = lambda: {}
        sys.modules["tools.voice_mode"].check_voice_requirements = lambda: {
            "ok": True,
        }

        resp = await self.client.get(
            "/voice/config",
            headers={
                **self._bearer("api-token"),
                "X-Forwarded-Proto": "https",
            },
        )

        self.assertEqual(resp.status, 200)

    async def test_voice_config_accepts_explicit_insecure_dev_escape_hatch(
        self,
    ) -> None:
        self._stub_api_token_validator({"api-token"})
        self._patch_voice_auth("_is_loopback_remote", lambda request: False)
        self._set_env("RELAY_ALLOW_INSECURE_API_BEARER", "1")
        sys.modules["tools.tts_tool"]._load_tts_config = lambda: {}
        sys.modules["tools.transcription_tools"]._load_stt_config = lambda: {}
        sys.modules["tools.voice_mode"].check_voice_requirements = lambda: {
            "ok": True,
        }

        resp = await self.client.get(
            "/voice/config", headers=self._bearer("api-token")
        )

        self.assertEqual(resp.status, 200)

    async def test_voice_config_accepts_runtime_insecure_toggle(
        self,
    ) -> None:
        self._stub_api_token_validator({"api-token"})
        self._patch_voice_auth("_is_loopback_remote", lambda request: False)
        self._server().config.allow_insecure_api_bearer = True
        sys.modules["tools.tts_tool"]._load_tts_config = lambda: {}
        sys.modules["tools.transcription_tools"]._load_stt_config = lambda: {}
        sys.modules["tools.voice_mode"].check_voice_requirements = lambda: {
            "ok": True,
        }

        resp = await self.client.get(
            "/voice/config", headers=self._bearer("api-token")
        )

        self.assertEqual(resp.status, 200)

    async def test_invalid_api_bearer_does_not_log_token_value(self) -> None:
        self._stub_api_token_validator(set())
        secret = "api-secret-do-not-log"

        with self.assertLogs("hermes_relay.voice_auth", level="INFO") as logs:
            resp = await self.client.get(
                "/voice/config", headers=self._bearer(secret)
            )

        self.assertEqual(resp.status, 401)
        rendered_logs = "\n".join(logs.output)
        self.assertNotIn(secret, rendered_logs)
        self.assertNotIn(secret, await resp.text())

    async def test_api_bearer_is_not_accepted_by_non_voice_routes(self) -> None:
        """A Hermes API token must not become a universal Relay credential."""
        self._stub_api_token_validator({"api-token"})
        media_path = self._write_tmp(".txt", b"media bytes")
        entry = await self._server().media.register(
            media_path,
            "text/plain",
            file_name="media.txt",
        )

        media_resp = await self.client.get(
            f"/media/{entry.token}",
            headers=self._bearer("api-token"),
        )
        sessions_resp = await self.client.get(
            "/sessions",
            headers=self._bearer("api-token"),
        )
        clipboard_resp = await self.client.post(
            "/clipboard/inbox",
            json={"format": "png", "bytes_base64": ""},
            headers=self._bearer("api-token"),
        )

        self.assertEqual(media_resp.status, 401)
        self.assertEqual(sessions_resp.status, 401)
        self.assertEqual(clipboard_resp.status, 401)


class EnhancedVoiceHelpersTests(unittest.TestCase):
    """Pure-function coverage for the provider-aware enhanced-voice plumbing."""

    def test_extract_overrides_top_level(self) -> None:
        from plugin.relay.voice import _extract_voice_overrides

        out = _extract_voice_overrides(
            {
                "text": "hi",
                "voice": "Puck",
                "model": "gemini-3.1-flash-tts-preview",
                "audio_tags": True,
                "style": "calm narrator",
            }
        )
        self.assertEqual(out["voice"], "Puck")
        self.assertEqual(out["model"], "gemini-3.1-flash-tts-preview")
        self.assertTrue(out["audio_tags"])
        self.assertEqual(out["persona_prompt"], "calm narrator")

    def test_extract_overrides_nested_block_and_language(self) -> None:
        from plugin.relay.voice import _extract_voice_overrides

        out = _extract_voice_overrides(
            {"text": "hi", "enhanced": {"voice": "eve", "audio_tags": False, "language": "en"}}
        )
        self.assertEqual(out["voice"], "eve")
        self.assertEqual(out["audio_tags"], False)
        self.assertEqual(out["language"], "en")

    def test_extract_overrides_empty_for_plain_body(self) -> None:
        from plugin.relay.voice import _extract_voice_overrides

        self.assertEqual(_extract_voice_overrides({"text": "hi"}), {})

    def test_enhanced_block_none_for_unsupported_provider(self) -> None:
        from plugin.relay.voice import _enhanced_voice_block

        self.assertIsNone(_enhanced_voice_block({"provider": "elevenlabs"}))

    def test_enhanced_block_for_gemini(self) -> None:
        from plugin.relay.voice import GEMINI_PREBUILT_VOICES, _enhanced_voice_block

        block = _enhanced_voice_block(
            {
                "provider": "gemini",
                "model": "gemini-3.1-flash-tts-preview",
                "gemini": {"audio_tags": True, "persona_prompt_file": "~/p.md"},
            }
        )
        self.assertIsNotNone(block)
        assert block is not None  # narrow for type-checkers
        self.assertEqual(block["provider"], "gemini")
        self.assertTrue(block["supported"])
        self.assertTrue(block["audio_tags_enabled"])
        self.assertTrue(block["supports_persona"])
        self.assertEqual(len(block["voices"]), len(GEMINI_PREBUILT_VOICES))
        self.assertIn("voice", block["overrides"])

    def test_enhanced_block_for_xai(self) -> None:
        from plugin.relay.voice import _enhanced_voice_block

        block = _enhanced_voice_block(
            {"provider": "xai", "xai": {"auto_speech_tags": True}}
        )
        self.assertIsNotNone(block)
        assert block is not None  # narrow for type-checkers
        self.assertEqual(block["provider"], "xai")
        self.assertTrue(block["supported"])
        self.assertTrue(block["audio_tags_enabled"])
        self.assertFalse(block["supports_persona"])
        self.assertTrue(block["supports_language"])
        self.assertEqual(block["voices"], [])  # free-text on the client
        self.assertIn("language", block["overrides"])

    def test_apply_xai_speech_tags_calls_through_and_fails_soft(self) -> None:
        # Used by the streaming /voice/output renderer to match the synthesize
        # path's xAI tone behavior; must call upstream when present, fail soft
        # (return the original text) when the symbol is unavailable.
        from plugin.relay import upstream_voice

        tts_mod = sys.modules["tools.tts_tool"]
        unset = object()
        original = getattr(tts_mod, "_apply_xai_auto_speech_tags", unset)
        try:
            tts_mod._apply_xai_auto_speech_tags = lambda t: f"[whisper]{t}"
            self.assertEqual(upstream_voice.apply_xai_speech_tags("hi"), "[whisper]hi")
        finally:
            if original is unset:
                if hasattr(tts_mod, "_apply_xai_auto_speech_tags"):
                    delattr(tts_mod, "_apply_xai_auto_speech_tags")
            else:
                tts_mod._apply_xai_auto_speech_tags = original
        # Symbol now absent → fail soft.
        self.assertEqual(upstream_voice.apply_xai_speech_tags("hi"), "hi")


if __name__ == "__main__":
    unittest.main()
