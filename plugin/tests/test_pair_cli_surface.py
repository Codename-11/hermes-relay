"""Focused tests for the host-side ``hermes pair`` CLI surface."""

from __future__ import annotations

import argparse
import unittest
from pathlib import Path

from plugin import cli


class PairCliSurfaceTests(unittest.TestCase):
    def test_explicit_legacy_direct_relay_flag_is_registered(self) -> None:
        parser = argparse.ArgumentParser()
        cli.register_cli(parser)

        current = parser.parse_args(["--public-url", "https://hermes.example"])
        legacy = parser.parse_args(["--legacy-direct-relay"])

        self.assertFalse(current.legacy_direct_relay)
        self.assertTrue(legacy.legacy_direct_relay)

    def test_public_url_help_describes_dashboard_origin(self) -> None:
        parser = argparse.ArgumentParser()
        cli.register_cli(parser)

        help_text = parser.format_help()

        self.assertIn("Public Dashboard origin", help_text)
        self.assertIn("same-origin plugin transport path", help_text)
        self.assertIn("--legacy-direct-relay", help_text)

    def test_tui_pairing_skill_preserves_dashboard_and_desktop_boundaries(self) -> None:
        skill = (
            Path(__file__).resolve().parents[2]
            / "skills"
            / "devops"
            / "hermes-relay-pair"
            / "SKILL.md"
        ).read_text(encoding="utf-8")

        self.assertIn("--dashboard-url", skill)
        self.assertIn("Tailscale Serve normally exposes dedicated HTTPS `10443`", skill)
        self.assertIn("local Dashboard on `9119`", skill)
        self.assertIn("Listener `443` is an advanced explicit override", skill)
        self.assertIn("LAN/raw-tailnet Dashboard candidates on\n   `9119`", skill)
        self.assertIn("--legacy-direct-relay", skill)
        self.assertIn("does not imply or expose public port `8767`", skill)


if __name__ == "__main__":
    unittest.main()
