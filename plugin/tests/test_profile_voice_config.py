"""Tests for profile-scoped relay voice defaults."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from plugin.relay.config import RelayConfig
from plugin.relay.profile_voice import (
    realtime_voice_settings,
    save_profile_voice_section,
    voice_output_settings,
)
from plugin.voice_lab.providers.elevenlabs_tts import DEFAULT_MODEL, DEFAULT_VOICE_ID


class ProfileVoiceConfigTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.hermes = Path(self.tmp.name) / ".hermes"
        self.hermes.mkdir()
        (self.hermes / "config.yaml").write_text(
            "model:\n  default: root-model\n",
            encoding="utf-8",
        )
        self.config = RelayConfig(
            hermes_config_path=str(self.hermes / "config.yaml"),
            voice_output_provider="stub",
            voice_output_model="relay-tone",
            voice_output_voice="sine",
            realtime_voice_provider="stub",
            realtime_voice_model="relay-rt",
            realtime_voice_voice="sine",
        )

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def _write_profile(self, name: str, body: str) -> None:
        pdir = self.hermes / "profiles" / name
        pdir.mkdir(parents=True)
        (pdir / "config.yaml").write_text(body, encoding="utf-8")

    def test_voice_output_uses_profile_voice_output_section(self) -> None:
        self._write_profile(
            "mizu",
            "\n".join(
                [
                    "voice_output:",
                    "  enabled: true",
                    "  provider: openai",
                    "  model: gpt-4o-mini-tts",
                    "  voice: coral",
                    "  sample_rate: 24000",
                ]
            ),
        )

        settings = voice_output_settings(self.config, "mizu")

        self.assertEqual(settings["provider"], "openai_tts")
        self.assertEqual(settings["model"], "gpt-4o-mini-tts")
        self.assertEqual(settings["voice"], "coral")
        self.assertEqual(settings["profile"], "mizu")
        self.assertEqual(settings["config_scope"], "profile")
        self.assertFalse(settings["fallback_to_global"])

    def test_voice_output_falls_back_to_relay_when_profile_has_no_voice(self) -> None:
        self._write_profile("plain", "model:\n  default: grok\n")

        settings = voice_output_settings(self.config, "plain")

        self.assertEqual(settings["provider"], "stub")
        self.assertEqual(settings["config_scope"], "relay")
        self.assertTrue(settings["fallback_to_global"])

    def test_provider_switch_uses_renderer_defaults_for_omitted_model_and_voice(self) -> None:
        self.config.voice_output_provider = "xai_tts"
        self.config.voice_output_model = "xai-tts"
        self.config.voice_output_voice = "eve"
        for index, section in enumerate(("tts", "voice_output")):
            with self.subTest(section=section):
                profile = f"switch-{index}"
                self._write_profile(profile, f"{section}:\n  provider: elevenlabs\n")
                settings = voice_output_settings(self.config, profile)
                self.assertEqual(settings["provider"], "elevenlabs_tts")
                self.assertEqual(settings["model"], DEFAULT_MODEL)
                self.assertEqual(settings["voice"], DEFAULT_VOICE_ID)
                self.assertEqual(settings["config_scope"], "profile")

    def test_explicit_profile_model_and_voice_survive_provider_switch(self) -> None:
        for index, (model_key, voice_key) in enumerate((("model", "voice"), ("model_id", "voice_id"))):
            with self.subTest(model_key=model_key):
                profile = f"explicit-{index}"
                self._write_profile(profile,
                    f"tts:\n  provider: elevenlabs\n  elevenlabs:\n"
                    f"    {model_key}: custom-model\n    {voice_key}: custom-voice\n")
                settings = voice_output_settings(self.config, profile)
                self.assertEqual(settings["model"], "custom-model")
                self.assertEqual(settings["voice"], "custom-voice")

    def test_explicit_model_does_not_import_foreign_voice_and_vice_versa(self) -> None:
        self._write_profile("model-only", "voice_output:\n  provider: elevenlabs\n  model: custom-model\n")
        self._write_profile("voice-only", "tts:\n  provider: elevenlabs\n  elevenlabs:\n    voice_id: custom-voice\n")
        model_only = voice_output_settings(self.config, "model-only")
        voice_only = voice_output_settings(self.config, "voice-only")
        self.assertEqual((model_only["model"], model_only["voice"]), ("custom-model", DEFAULT_VOICE_ID))
        self.assertEqual((voice_only["model"], voice_only["voice"]), (DEFAULT_MODEL, "custom-voice"))

    def test_same_provider_alias_preserves_relay_model_and_voice(self) -> None:
        self.config.voice_output_provider = "elevenlabs_tts"
        self.config.voice_output_model = "custom-model"
        self.config.voice_output_voice = "custom-voice"
        for index, alias in enumerate(("elevenlabs", "ElevenLabs-TTS")):
            profile = f"alias-{index}"
            self._write_profile(profile, f"voice_output:\n  provider: {alias}\n")
            settings = voice_output_settings(self.config, profile)
            self.assertEqual((settings["model"], settings["voice"]), ("custom-model", "custom-voice"))

    def test_default_profile_and_missing_profile_use_root_without_mutating_relay(self) -> None:
        root = self.hermes / "config.yaml"
        root.write_text("tts:\n  provider: elevenlabs\n", encoding="utf-8")
        before = root.read_bytes()
        for profile, scope, fallback in (("default", "profile", False), ("missing", "global", True)):
            with self.subTest(profile=profile):
                settings = voice_output_settings(self.config, profile)
                self.assertEqual(settings["model"], DEFAULT_MODEL)
                self.assertEqual(settings["voice"], DEFAULT_VOICE_ID)
                self.assertEqual(settings["config_scope"], scope)
                self.assertEqual(settings["fallback_to_global"], fallback)
        self.assertEqual(root.read_bytes(), before)
        self.assertEqual(self.config.voice_output_model, "relay-tone")
        self.assertEqual(self.config.voice_output_voice, "sine")

    def test_no_profile_keeps_relay_defaults_even_when_root_tts_selects_elevenlabs(self) -> None:
        (self.hermes / "config.yaml").write_text("tts:\n  provider: elevenlabs\n", encoding="utf-8")
        for profile in (None, "", "  "):
            settings = voice_output_settings(self.config, profile)
            self.assertEqual((settings["provider"], settings["model"], settings["voice"]),
                             ("stub", "relay-tone", "sine"))
            self.assertEqual(settings["config_scope"], "relay")

    def test_profile_switches_do_not_leak_defaults_and_voice_output_takes_precedence(self) -> None:
        self._write_profile("eleven", "tts:\n  provider: elevenlabs\n")
        self._write_profile("other", "tts:\n  provider: elevenlabs\nvoice_output:\n  provider: openai\n")
        for profile, model, voice in (("eleven", DEFAULT_MODEL, DEFAULT_VOICE_ID),
                                       ("other", "gpt-4o-mini-tts", "coral"),
                                       ("eleven", DEFAULT_MODEL, DEFAULT_VOICE_ID)):
            settings = voice_output_settings(self.config, profile)
            self.assertEqual((settings["model"], settings["voice"]), (model, voice))

    def test_blank_overrides_use_defaults_but_model_only_override_keeps_provider(self) -> None:
        self._write_profile("blank", "voice_output:\n  provider: elevenlabs\n  model: '  '\n  voice: ''\n")
        self._write_profile("model", "voice_output:\n  model: custom-model\n")
        settings = voice_output_settings(self.config, "blank")
        self.assertEqual((settings["model"], settings["voice"]), (DEFAULT_MODEL, DEFAULT_VOICE_ID))
        settings = voice_output_settings(self.config, "model")
        self.assertEqual((settings["provider"], settings["model"], settings["voice"]),
                         ("stub", "custom-model", "sine"))

    def test_realtime_uses_profile_realtime_section(self) -> None:
        self._write_profile(
            "eve",
            "\n".join(
                [
                    "realtime_voice:",
                    "  provider: xai",
                    "  model: grok-voice-latest",
                    "  voice: eve",
                ]
            ),
        )

        settings = realtime_voice_settings(self.config, "eve")

        self.assertEqual(settings["provider"], "xai_realtime")
        self.assertEqual(settings["model"], "grok-voice-latest")
        self.assertEqual(settings["config_scope"], "profile")

    def test_save_profile_voice_section_updates_selected_profile_config(self) -> None:
        self._write_profile("writer", "model:\n  default: base\n")

        path = save_profile_voice_section(
            self.config,
            "writer",
            "voice_output",
            {"provider": "stub", "voice": "square"},
        )

        self.assertEqual(path, self.hermes / "profiles" / "writer" / "config.yaml")
        settings = voice_output_settings(self.config, "writer")
        self.assertEqual(settings["voice"], "square")
        self.assertEqual(settings["config_scope"], "profile")


if __name__ == "__main__":
    unittest.main()
