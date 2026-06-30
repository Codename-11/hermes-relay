"""Tests for update-discovery helpers (``plugin/update_check.py``).

Pure helpers only — no network. Run with::

    python -m unittest plugin.tests.test_update_check
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from plugin import update_check as uc


class SemverTests(unittest.TestCase):
    def test_parse(self) -> None:
        self.assertEqual(uc.parse_semver("1.2.3"), (1, 2, 3))
        self.assertEqual(uc.parse_semver("plugin-v1.2.0"), (1, 2, 0))
        self.assertEqual(uc.parse_semver("v2.0.1-rc1"), (2, 0, 1))
        self.assertIsNone(uc.parse_semver("nope"))
        self.assertIsNone(uc.parse_semver(""))

    def test_compare(self) -> None:
        self.assertEqual(uc.compare_versions("1.2.1", "1.3.0"), -1)
        self.assertEqual(uc.compare_versions("1.3.0", "1.2.1"), 1)
        self.assertEqual(uc.compare_versions("1.2.1", "1.2.1"), 0)
        # Unparseable → 0 so a bad tag never spuriously nags.
        self.assertEqual(uc.compare_versions("bad", "1.2.1"), 0)


class TagPickTests(unittest.TestCase):
    def test_picks_highest_plugin_tag(self) -> None:
        releases = [
            {"tag_name": "plugin-v1.2.0"},
            {"tag_name": "android-v1.5.0"},  # different track — ignored
            {"tag_name": "plugin-v1.3.0"},
            {"tag_name": "cli-v0.4.0"},
            {"tag_name": "plugin-v1.9.0", "draft": True},  # draft — ignored
        ]
        self.assertEqual(uc.pick_latest_plugin_tag(releases), "1.3.0")

    def test_no_plugin_releases(self) -> None:
        self.assertIsNone(uc.pick_latest_plugin_tag([{"tag_name": "android-v1.0.0"}]))
        self.assertIsNone(uc.pick_latest_plugin_tag("not-a-list"))
        self.assertIsNone(uc.pick_latest_plugin_tag([]))


class CommandDetectTests(unittest.TestCase):
    def test_full_relay_shim_present(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            shim = Path(d) / ".local" / "bin" / "hermes-relay-update"
            shim.parent.mkdir(parents=True)
            shim.write_text("#!/bin/sh\n")
            self.assertEqual(uc.detect_update_command(home=d), "hermes-relay-update")

    def test_native_when_no_shim(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(
                uc.detect_update_command(home=d), "hermes plugins update hermes-relay"
            )


class BuildResultTests(unittest.TestCase):
    def test_update_available(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            r = uc.build_result("1.2.1", "1.3.0", home=d)
            self.assertTrue(r["update_available"])
            self.assertEqual(r["update_command"], "hermes plugins update hermes-relay")

    def test_up_to_date(self) -> None:
        r = uc.build_result("1.2.1", "1.2.1")
        self.assertFalse(r["update_available"])
        self.assertIsNone(r["update_command"])

    def test_no_latest(self) -> None:
        r = uc.build_result("1.2.1", None)
        self.assertFalse(r["update_available"])
        self.assertIsNone(r["update_command"])


if __name__ == "__main__":
    unittest.main()
