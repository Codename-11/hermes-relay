"""Relay environment-resolution regression tests."""

from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from plugin.relay.config import RelayConfig


class RelayConfigEnvironmentTests(unittest.TestCase):
    def test_hermes_home_supplies_default_config_and_session_paths(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            (home / "config.yaml").write_text("{}\n", encoding="utf-8")

            with mock.patch.dict(os.environ, {"HERMES_HOME": str(home)}, clear=False):
                os.environ.pop("RELAY_HERMES_CONFIG", None)
                os.environ.pop("RELAY_SESSIONS_FILE", None)
                config = RelayConfig.from_env()

            self.assertEqual(str(home / "config.yaml"), config.hermes_config_path)
            self.assertEqual(
                str(home / "hermes-relay-sessions.json"),
                config.session_persistence_path,
            )

    def test_explicit_relay_config_override_wins_over_hermes_home(self) -> None:
        with tempfile.TemporaryDirectory() as home_tmp, tempfile.TemporaryDirectory() as override_tmp:
            home = Path(home_tmp)
            override = Path(override_tmp) / "relay-config.yaml"
            override.write_text("{}\n", encoding="utf-8")

            with mock.patch.dict(
                os.environ,
                {
                    "HERMES_HOME": str(home),
                    "RELAY_HERMES_CONFIG": str(override),
                },
                clear=False,
            ):
                os.environ.pop("RELAY_SESSIONS_FILE", None)
                config = RelayConfig.from_env()

            self.assertEqual(str(override), config.hermes_config_path)
            self.assertEqual(
                str(override.parent / "hermes-relay-sessions.json"),
                config.session_persistence_path,
            )


if __name__ == "__main__":
    unittest.main()
