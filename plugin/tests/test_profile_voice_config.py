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
