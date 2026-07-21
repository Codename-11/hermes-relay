"""Tests for relay voice provider option discovery."""

from __future__ import annotations

import os
import unittest
from unittest import mock

from plugin.relay import provider_options
from plugin.relay.provider_options import XAIOptionAuth


class ProviderOptionsTests(unittest.TestCase):
    def setUp(self) -> None:
        provider_options.clear_provider_option_cache()

    def tearDown(self) -> None:
        provider_options.clear_provider_option_cache()

    def test_xai_provider_options_merge_builtin_and_custom_voices(self) -> None:
        def fake_get_json(url, **kwargs):
            if url.endswith("/tts/voices"):
                return (
                    {
                        "voices": [
                            {"voice_id": "eve", "name": "Eve"},
                            {"voice_id": "ara", "name": "Ara"},
                        ]
                    },
                    None,
                )
            if "/custom-voices?" in url:
                return (
                    {
                        "custom_voices": [
                            {"voice_id": "custom123", "name": "Bailey Test"}
                        ]
                    },
                    None,
                )
            raise AssertionError(url)

        with mock.patch.object(provider_options, "_get_json_result", fake_get_json):
            result = provider_options.fetch_voice_output_provider_options(
                "xai_tts",
                xai_auth=XAIOptionAuth(
                    access_token="test-token",
                    base_url="https://api.x.ai/v1",
                    source="test",
                ),
            )

        self.assertEqual(result["dynamic"]["status"], "ok")
        self.assertEqual(result["dynamic"]["voice_count"], 3)
        self.assertEqual(result["dynamic"]["custom_voice_count"], 1)
        self.assertEqual(result["provider"]["voices"], ["eve", "ara", "custom123"])
        self.assertEqual(result["provider"]["voice_labels"]["ara"], "Ara")
        self.assertEqual(result["provider"]["voice_labels"]["custom123"], "Bailey Test (custom)")
        self.assertEqual(result["provider"]["voice_groups"][0]["id"], "xai_builtin")
        self.assertEqual(result["provider"]["voice_groups"][1]["id"], "xai_custom")
        self.assertTrue(result["provider"]["voice_metadata"]["custom123"]["custom"])

    def test_xai_provider_options_without_auth_keeps_static_labels(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=True):
            result = provider_options.fetch_realtime_provider_options("xai_realtime")

        self.assertEqual(result["dynamic"]["status"], "auth_missing")
        self.assertFalse(result["dynamic"]["attempted"])
        self.assertEqual(len(result["provider"]["voices"]), 26)
        self.assertEqual(
            set(result["provider"]["voices"]),
            {
                "carina",
                "zagan",
                "helix",
                "orion",
                "luna",
                "iris",
                "altair",
                "zenith",
                "perseus",
                "helios",
                "lux",
                "kepler",
                "rigel",
                "cosmo",
                "celeste",
                "ursa",
                "sirius",
                "lumen",
                "castor",
                "naksh",
                "atlas",
                "ara",
                "eve",
                "leo",
                "rex",
                "sal",
            },
        )
        self.assertIn("eve", result["provider"]["voices"])
        self.assertEqual(
            result["provider"]["voice_labels"]["carina"],
            "Carina - soft, empathetic, soothing",
        )
        self.assertEqual(result["provider"]["voice_labels"]["rex"], "Rex - confident, clear")
        self.assertEqual(result["provider"]["voice_groups"][0]["id"], "xai_builtin")
        self.assertEqual(
            result["provider"]["voice_metadata"]["atlas"]["label"],
            "Atlas - confident, commanding, reassuring",
        )

    def test_elevenlabs_provider_options_fetches_voices_models_and_languages(self) -> None:
        def fake_get_json(url, **kwargs):
            if url.endswith("/v1/voices"):
                return (
                    {
                        "voices": [
                            {"voice_id": "voice_a", "name": "Voice A"},
                            {"voice_id": "voice_b", "name": "Voice B"},
                        ]
                    },
                    None,
                )
            if url.endswith("/v1/models"):
                return (
                    [
                        {
                            "model_id": "eleven_flash_v2_5",
                            "name": "Flash v2.5",
                            "can_do_text_to_speech": True,
                            "languages": [
                                {"language_id": "en", "name": "English"},
                                {"language_id": "es", "name": "Spanish"},
                            ],
                        },
                        {
                            "model_id": "eleven_music",
                            "name": "Music",
                            "can_do_text_to_speech": False,
                        },
                    ],
                    None,
                )
            raise AssertionError(url)

        with (
            mock.patch.dict(os.environ, {"ELEVENLABS_API_KEY": "test-key"}, clear=True),
            mock.patch.object(provider_options, "_get_json_result", fake_get_json),
        ):
            result = provider_options.fetch_voice_output_provider_options("elevenlabs_tts")

        self.assertEqual(result["dynamic"]["status"], "ok")
        self.assertEqual(result["provider"]["voices"], ["voice_a", "voice_b"])
        self.assertEqual(result["provider"]["models"], ["eleven_flash_v2_5"])
        self.assertEqual(result["provider"]["languages"], ["en", "es"])
        self.assertEqual(result["provider"]["model_labels"]["eleven_flash_v2_5"], "Flash v2.5")
        self.assertEqual(result["provider"]["language_labels"]["es"], "Spanish")
        self.assertEqual(result["provider"]["voice_groups"][0]["id"], "elevenlabs_account")

    def test_xai_custom_voice_pagination_and_cache(self) -> None:
        calls: list[str] = []

        def fake_get_json(url, **kwargs):
            calls.append(url)
            if url.endswith("/tts/voices"):
                return ({"voices": [{"voice_id": "eve", "name": "Eve"}]}, None)
            if "/custom-voices?" in url and "pagination_token=page2" not in url:
                return (
                    {
                        "custom_voices": [{"voice_id": "custom_a", "name": "Custom A"}],
                        "pagination_token": "page2",
                    },
                    None,
                )
            if "/custom-voices?" in url and "pagination_token=page2" in url:
                return (
                    {"custom_voices": [{"voice_id": "custom_b", "name": "Custom B"}]},
                    None,
                )
            raise AssertionError(url)

        auth = XAIOptionAuth(
            access_token="test-token",
            base_url="https://api.x.ai/v1",
            source="test",
        )
        with mock.patch.object(provider_options, "_get_json_result", fake_get_json):
            first = provider_options.fetch_voice_output_provider_options("xai_tts", xai_auth=auth)
            second = provider_options.fetch_voice_output_provider_options("xai_tts", xai_auth=auth)

        self.assertEqual(first["provider"]["voices"], ["eve", "custom_a", "custom_b"])
        self.assertEqual(second["dynamic"]["cache"], "hit")
        self.assertEqual(len([url for url in calls if "/custom-voices?" in url]), 2)

    def test_openai_model_voice_validation_blocks_incompatible_voice(self) -> None:
        provider = provider_options.openai_static_provider_options()["provider"]

        invalid = provider_options.validate_provider_selection(
            {
                **provider,
                "models": ["gpt-4o-mini-tts", "tts-1"],
                "voices": list(provider_options.OPENAI_TTS_VOICES),
                "sample_rates": [24000],
            },
            model="tts-1",
            voice="marin",
            sample_rate=24000,
            language="en",
        )
        valid = provider_options.validate_provider_selection(
            {
                **provider,
                "models": ["gpt-4o-mini-tts", "tts-1"],
                "voices": list(provider_options.OPENAI_TTS_VOICES),
                "sample_rates": [24000],
            },
            model="gpt-4o-mini-tts",
            voice="marin",
            sample_rate=24000,
            language="en",
        )

        self.assertFalse(invalid["valid"])
        self.assertIn("voice_compatible", {check["id"] for check in invalid["checks"]})
        self.assertTrue(valid["valid"])


if __name__ == "__main__":
    unittest.main()
