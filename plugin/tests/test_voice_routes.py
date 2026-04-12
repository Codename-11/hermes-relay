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
from plugin.relay.server import create_app  # noqa: E402


class VoiceRoutesTests(AioHTTPTestCase):
    """Exercise the /voice/* routes through a live test server."""

    async def get_application(self) -> web.Application:
        self._tmp_files: list[str] = []
        config = RelayConfig()
        app = create_app(config)
        return app

    async def tearDownAsync(self) -> None:
        await super().tearDownAsync()
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
        # The handler should NOT delete the TTS file.
        self.assertTrue(os.path.isfile(mp3_path))

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


if __name__ == "__main__":
    unittest.main()
