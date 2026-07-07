"""Tests for per-profile plugin-enablement helpers (``plugin/profiles.py``).

Runs under plain ``unittest``. Skips cleanly when pyyaml is absent from the
interpreter (the live hermes venv always has it).

    python -m unittest plugin.tests.test_profiles
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - guarded by skip
    yaml = None

from plugin import profiles


def _write(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")


@unittest.skipIf(yaml is None, "pyyaml not installed")
class DiscoverTests(unittest.TestCase):
    def test_discovers_root_and_profiles(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            home = Path(d)
            _write(home / "config.yaml", {"plugins": {"enabled": ["hermes-relay"]}})
            _write(home / "profiles" / "gary" / "config.yaml", {"plugins": {"enabled": []}})
            _write(home / "profiles" / "lucy" / "config.yaml", {"plugins": {}})
            configs = profiles.discover_profile_configs(str(home))
            labels = [label for label, _ in configs]
            self.assertEqual(labels[0], "(default)")  # default first
            self.assertIn("gary", labels)
            self.assertIn("lucy", labels)

    def test_missing_home_is_empty(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(profiles.discover_profile_configs(str(Path(d) / "nope")), [])


@unittest.skipIf(yaml is None, "pyyaml not installed")
class StateTests(unittest.TestCase):
    def test_states(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            home = Path(d)
            en = home / "a.yaml"
            _write(en, {"plugins": {"enabled": ["hermes-relay"]}})
            dis = home / "b.yaml"
            _write(dis, {"plugins": {"disabled": ["hermes-relay"]}})
            ab = home / "c.yaml"
            _write(ab, {"plugins": {"enabled": ["other"]}})
            none = home / "d.yaml"
            _write(none, {"model": "x"})
            self.assertEqual(profiles.relay_state(en), "enabled")
            self.assertEqual(profiles.relay_state(dis), "disabled")
            self.assertEqual(profiles.relay_state(ab), "absent")
            self.assertEqual(profiles.relay_state(none), "absent")


@unittest.skipIf(yaml is None, "pyyaml not installed")
class EnableTests(unittest.TestCase):
    def test_enable_adds_and_backs_up(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "config.yaml"
            _write(p, {"plugins": {"enabled": ["other"], "disabled": ["hermes-relay"]}})
            self.assertTrue(profiles.enable_relay(p))
            data = yaml.safe_load(p.read_text(encoding="utf-8"))
            self.assertIn("hermes-relay", data["plugins"]["enabled"])
            self.assertNotIn("hermes-relay", data["plugins"].get("disabled", []))
            self.assertTrue(p.with_suffix(".yaml.bak").exists())  # backed up

    def test_enable_idempotent_preserves_file(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "config.yaml"
            original = "plugins:\n  enabled:\n  - hermes-relay  # keep me\n"
            p.write_text(original, encoding="utf-8")
            self.assertFalse(profiles.enable_relay(p))  # already enabled → no change
            self.assertEqual(p.read_text(encoding="utf-8"), original)  # comment intact
            self.assertFalse(p.with_suffix(".yaml.bak").exists())  # no needless rewrite

    def test_dry_run_does_not_write(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "config.yaml"
            _write(p, {"plugins": {"enabled": []}})
            before = p.read_text(encoding="utf-8")
            self.assertTrue(profiles.enable_relay(p, dry_run=True))  # would change
            self.assertEqual(p.read_text(encoding="utf-8"), before)  # but didn't

    def test_creates_plugins_section_when_absent(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "config.yaml"
            _write(p, {"model": "x"})
            self.assertTrue(profiles.enable_relay(p))
            data = yaml.safe_load(p.read_text(encoding="utf-8"))
            self.assertIn("hermes-relay", data["plugins"]["enabled"])


if __name__ == "__main__":
    unittest.main()
