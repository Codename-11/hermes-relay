"""Credential-free connector archive and atomic publication contracts."""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "package-desktop-connector.py"
spec = importlib.util.spec_from_file_location("package_desktop_connector", SCRIPT)
assert spec is not None and spec.loader is not None
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)
SOURCE_SHA = "a" * 40


class ConnectorPackageTests(unittest.TestCase):
    def test_flat_template_contains_only_allowlist_and_matches_archived_binary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "test.exe"
            payload = b"MZ" + bytes(range(256)) * 8193
            binary.write_bytes(payload)
            (root / "setup.json").write_text('{"invite":"must-not-enter-the-template"}')
            output = root / "template.zip"
            manifest = packager.package_template(binary, output, SOURCE_SHA.upper(), "1.2.3-rc.1")
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(set(archive.namelist()), {"Install.cmd", "install.ps1", "Status.cmd", "Stop.cmd", "hermes-relay.exe", "manifest.json"})
                self.assertEqual(archive.read("hermes-relay.exe"), payload)
                self.assertEqual(json.loads(archive.read("manifest.json")), manifest)
                self.assertEqual(manifest, {"sha256": hashlib.sha256(payload).hexdigest(), "source_sha": SOURCE_SHA, "version": "1.2.3-rc.1"})
                script = archive.read("install.ps1")
                self.assertTrue(script.startswith(b"\xef\xbb\xbf"))
                for name in packager.INSTALLER_FILES:
                    data = archive.read(name)
                    self.assertNotIn(b"\r\r\n", data)
                    self.assertNotIn(b"\n", data.replace(b"\r\n", b""))
                self.assertEqual(archive.testzip(), None)
            self.assertEqual(list(root.glob(".template.zip.*.tmp")), [])

    def test_existing_output_and_symlink_are_never_replaced(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "test.exe"
            binary.write_bytes(b"MZtest")
            output = root / "template.zip"
            output.write_bytes(b"existing artifact")
            with self.assertRaises(FileExistsError):
                packager.package_template(binary, output, SOURCE_SHA, "1.2.3")
            self.assertEqual(output.read_bytes(), b"existing artifact")
            output.unlink()
            output.symlink_to(root / "missing-target")
            with self.assertRaises(FileExistsError):
                packager.package_template(binary, output, SOURCE_SHA, "1.2.3")
            self.assertTrue(output.is_symlink())

    def test_concurrent_output_creation_is_not_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "test.exe"
            binary.write_bytes(b"MZtest")
            output = root / "template.zip"
            original_link = packager.os.link

            def racing_link(source: Path, destination: Path) -> None:
                destination.write_bytes(b"other packager won")
                original_link(source, destination)

            with patch.object(packager.os, "link", side_effect=racing_link):
                with self.assertRaises(FileExistsError):
                    packager.package_template(binary, output, SOURCE_SHA, "1.2.3")
            self.assertEqual(output.read_bytes(), b"other packager won")
            self.assertEqual(list(root.glob(".template.zip.*.tmp")), [])

    def test_invalid_metadata_and_failed_reads_do_not_publish_partial_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "test.exe"
            binary.write_bytes(b"MZtest")
            output = root / "template.zip"
            for source_sha, version in [("short", "1.2.3"), (SOURCE_SHA, "not a version")]:
                with self.assertRaises(ValueError):
                    packager.package_template(binary, output, source_sha, version)
                self.assertFalse(output.exists())
            with patch.object(packager, "copy_binary", side_effect=OSError("input read failed")):
                with self.assertRaises(OSError):
                    packager.package_template(binary, output, SOURCE_SHA, "1.2.3")
            self.assertFalse(output.exists())
            self.assertEqual(list(root.glob(".template.zip.*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
