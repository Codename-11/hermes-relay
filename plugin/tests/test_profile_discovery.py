"""Unit tests for ``plugin.relay.config._load_profiles``.

Upstream Hermes stores profiles as isolated directories under
``~/.hermes/profiles/<name>/``. These tests build a fake ``~/.hermes/``
layout in a tempdir and assert the discovery output shape.
"""

from __future__ import annotations

import logging
import tempfile
import textwrap
import unittest
from pathlib import Path

from plugin.relay.config import _load_profiles


class ProfileDiscoveryTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.hermes_dir = Path(self._tmp.name)
        self.root_config = self.hermes_dir / "config.yaml"
        self.profiles_dir = self.hermes_dir / "profiles"
        self.profiles_dir.mkdir(parents=True, exist_ok=True)

    # ── helpers ─────────────────────────────────────────────────────────

    def _write_root_config(
        self,
        *,
        model: str = "gpt-4o-mini",
        description: str | None = None,
    ) -> None:
        body = f"model:\n  default: {model}\n"
        if description is not None:
            body += f"description: {description}\n"
        self.root_config.write_text(body, encoding="utf-8")

    def _write_profile(
        self,
        name: str,
        *,
        config_body: str,
        soul: str | None = None,
    ) -> Path:
        pdir = self.profiles_dir / name
        pdir.mkdir(parents=True, exist_ok=True)
        (pdir / "config.yaml").write_text(config_body, encoding="utf-8")
        if soul is not None:
            (pdir / "SOUL.md").write_text(soul, encoding="utf-8")
        return pdir

    def _load(self, *, enabled: bool = True) -> list[dict]:
        return _load_profiles(str(self.root_config), enabled=enabled)

    # ── cases ───────────────────────────────────────────────────────────

    def test_empty_profiles_dir_returns_only_default(self) -> None:
        self._write_root_config(model="claude-opus-4", description="Root desc")
        out = self._load()
        self.assertEqual(len(out), 1)
        entry = out[0]
        self.assertEqual(entry["name"], "default")
        self.assertEqual(entry["model"], "claude-opus-4")
        self.assertEqual(entry["description"], "Root desc")
        self.assertIsNone(entry["system_message"])

    def test_full_profile_populates_all_fields(self) -> None:
        self._write_root_config()
        config_body = textwrap.dedent(
            """\
            model:
              default: anthropic/claude-sonnet
            description: Mizu the researcher
            """
        )
        soul = "# Mizu\n\nYou are Mizu, a researcher.\n\n"
        self._write_profile("mizu", config_body=config_body, soul=soul)

        out = self._load()
        names = [p["name"] for p in out]
        self.assertIn("default", names)
        self.assertIn("mizu", names)

        mizu = next(p for p in out if p["name"] == "mizu")
        self.assertEqual(mizu["model"], "anthropic/claude-sonnet")
        self.assertEqual(mizu["description"], "Mizu the researcher")
        # system_message preserves body, trailing whitespace trimmed.
        self.assertIsNotNone(mizu["system_message"])
        self.assertTrue(mizu["system_message"].startswith("# Mizu"))
        self.assertFalse(mizu["system_message"].endswith("\n"))
        self.assertIn("You are Mizu, a researcher.", mizu["system_message"])

    def test_profile_without_soul_has_null_system_message(self) -> None:
        self._write_root_config()
        self._write_profile(
            "coder",
            config_body="model:\n  default: openai/gpt-5\ndescription: Coder bot\n",
            soul=None,
        )
        out = self._load()
        coder = next(p for p in out if p["name"] == "coder")
        self.assertIsNone(coder["system_message"])
        self.assertEqual(coder["description"], "Coder bot")
        self.assertEqual(coder["model"], "openai/gpt-5")

    def test_description_falls_back_to_soul_first_line(self) -> None:
        """When config.yaml has no ``description``, use the first non-blank
        line of SOUL.md (stripped of leading ``#`` markers)."""
        self._write_root_config()
        soul = "\n\n## Research Agent\n\nDoes research.\n"
        self._write_profile(
            "researcher",
            config_body="model:\n  default: m/x\n",
            soul=soul,
        )
        out = self._load()
        entry = next(p for p in out if p["name"] == "researcher")
        self.assertEqual(entry["description"], "Research Agent")

    def test_malformed_yaml_is_skipped_with_warning(self) -> None:
        self._write_root_config()
        # Good profile + bad profile — good must still appear.
        self._write_profile(
            "good",
            config_body="model:\n  default: ok-model\n",
            soul=None,
        )
        bad_dir = self.profiles_dir / "bad"
        bad_dir.mkdir()
        (bad_dir / "config.yaml").write_text(
            "model:\n  default: [unterminated\n", encoding="utf-8"
        )

        with self.assertLogs("plugin.relay.config", level="WARNING") as cm:
            out = self._load()

        names = [p["name"] for p in out]
        self.assertIn("good", names)
        self.assertNotIn("bad", names)
        # Some warning mentioning the bad profile was emitted.
        self.assertTrue(
            any("bad" in message for message in cm.output),
            f"expected a warning mentioning 'bad' profile, got: {cm.output}",
        )

    def test_discovery_disabled_returns_empty_list(self) -> None:
        self._write_root_config()
        self._write_profile(
            "ignored",
            config_body="model:\n  default: x\n",
            soul="# Ignored\n",
        )
        with self.assertLogs("plugin.relay.config", level="INFO") as cm:
            out = _load_profiles(str(self.root_config), enabled=False)
        self.assertEqual(out, [])
        self.assertTrue(
            any("disabled" in m.lower() for m in cm.output),
            f"expected a log about disabled discovery, got: {cm.output}",
        )

    def test_default_entry_picks_up_root_config(self) -> None:
        self._write_root_config(
            model="meta/llama-4", description="House default assistant"
        )
        # Root SOUL.md should also feed into default's system_message.
        (self.hermes_dir / "SOUL.md").write_text(
            "# House\n\nYou are the house default.\n", encoding="utf-8"
        )
        out = self._load()
        default = next(p for p in out if p["name"] == "default")
        self.assertEqual(default["model"], "meta/llama-4")
        self.assertEqual(default["description"], "House default assistant")
        self.assertIsNotNone(default["system_message"])
        self.assertIn("You are the house default.", default["system_message"])

    def test_missing_root_config_still_lists_directory_profiles(self) -> None:
        """If the root ``config.yaml`` is absent but ``profiles/`` has
        entries, we return the directory profiles and no default."""
        # Intentionally do NOT write root config.
        self._write_profile(
            "solo",
            config_body="model:\n  default: solo-model\n",
            soul="Solo profile\n",
        )
        out = self._load()
        names = [p["name"] for p in out]
        self.assertEqual(names, ["solo"])
        self.assertEqual(out[0]["model"], "solo-model")

    def test_model_default_missing_falls_back_to_unknown(self) -> None:
        self._write_root_config()
        self._write_profile(
            "no-model",
            config_body="description: Profile without model\n",
            soul=None,
        )
        out = self._load()
        entry = next(p for p in out if p["name"] == "no-model")
        self.assertEqual(entry["model"], "unknown")


if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG)
    unittest.main()
