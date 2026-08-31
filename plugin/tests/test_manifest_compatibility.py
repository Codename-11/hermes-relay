"""Regression coverage for the temporary Hermes installer compatibility manifest."""

from pathlib import Path
import unittest

import yaml


PLUGIN_ROOT = Path(__file__).resolve().parents[1]


class ManifestCompatibilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest = yaml.safe_load(
            (PLUGIN_ROOT / "plugin.yaml").read_text(encoding="utf-8")
        )

    def test_declares_installer_compatible_manifest_version(self) -> None:
        self.assertEqual(self.manifest["manifest_version"], 1)

    def test_retains_additive_metadata_for_current_hosts(self) -> None:
        self.assertEqual(self.manifest["api_version"], 1)
        self.assertEqual(
            self.manifest["python_dependencies"],
            [
                "requests>=2.28.0,<3",
                "aiohttp>=3.14.1,<4",
                "segno>=1.6.0,<2",
                "pyyaml>=6.0,<7",
                "httpx>=0.25.0,<1",
                "websocket-client>=1.8.0,<2",
            ],
        )
        self.assertEqual(self.manifest["license"], "MIT")
        self.assertEqual(
            self.manifest["homepage"],
            "https://github.com/Codename-11/hermes-relay",
        )
        self.assertEqual(
            self.manifest["tags"],
            ["android", "dashboard", "gateway", "relay", "remote-access", "voice"],
        )

    def test_retains_v1_hook_declarations(self) -> None:
        self.assertEqual(
            self.manifest["provides_hooks"],
            ["on_session_start", "pre_llm_call", "post_llm_call"],
        )


if __name__ == "__main__":
    unittest.main()
