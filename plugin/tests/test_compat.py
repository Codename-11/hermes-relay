from __future__ import annotations

import argparse
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from plugin import cli, compat


class CompatTests(unittest.TestCase):
    def test_status_detects_managed_pth(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            (root / compat.PTH_NAME).write_text(compat.PTH_CONTENT, encoding="utf-8")

            with mock.patch(
                "importlib.util.find_spec",
                return_value=SimpleNamespace(origin="/tmp/bootstrap/__init__.py"),
            ):
                status = compat.collect_compat_status(site_dirs=[root])

        self.assertTrue(status["installed"])
        self.assertTrue(status["package"]["importable"])
        self.assertTrue(status["pth_files"][0]["managed_content"])

    def test_install_writes_hook_when_package_importable(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)

            with mock.patch(
                "importlib.util.find_spec",
                return_value=SimpleNamespace(origin="/tmp/bootstrap/__init__.py"),
            ):
                result = compat.install_compat(site_packages=root)

            self.assertTrue(result["changed"])
            self.assertEqual((root / compat.PTH_NAME).read_text(encoding="utf-8"), compat.PTH_CONTENT)

    def test_install_refuses_missing_package(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            plugin_dir = root / "missing-plugin"

            with mock.patch("importlib.util.find_spec", return_value=None):
                with self.assertRaises(RuntimeError):
                    compat.install_compat(site_packages=root, plugin_dir=plugin_dir)

            self.assertFalse((root / compat.PTH_NAME).exists())

    def test_install_replaces_legacy_managed_hook(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            target = root / compat.PTH_NAME
            target.write_text(compat.LEGACY_PTH_CONTENT, encoding="utf-8")

            result = compat.install_compat(site_packages=root)

            self.assertTrue(result["changed"])
            self.assertIn("hermes_relay_plugin_bootstrap", target.read_text(encoding="utf-8"))

    def test_remove_deletes_hook(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            target = root / compat.PTH_NAME
            target.write_text(compat.PTH_CONTENT, encoding="utf-8")

            result = compat.remove_compat(site_packages=root)

            self.assertTrue(result["changed"])
            self.assertFalse(target.exists())

    def test_relay_cli_registers_compat_command(self) -> None:
        parser = argparse.ArgumentParser()
        cli.register_relay_cli(parser)

        parsed = parser.parse_args(["compat", "status", "--json"])

        self.assertTrue(parsed.json)
        self.assertEqual(parsed.compat_cmd, "status")
        self.assertIs(parsed.func, cli.relay_compat_command)


if __name__ == "__main__":
    unittest.main()
