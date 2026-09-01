from __future__ import annotations

import importlib.util
import io
import json
import tempfile
import unittest
from argparse import Namespace
from contextlib import redirect_stdout
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "android_release_artifacts.py"
SPEC = importlib.util.spec_from_file_location("android_release_artifacts", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AndroidReleaseArtifactsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.apk = self.root / "hermes-relay-1.15.1-sideload-release.apk"
        self.aab = self.root / "hermes-relay-1.15.1-googlePlay-release.aab"
        self.sideload_mapping = self.root / "sideload-mapping.txt"
        self.play_mapping = self.root / "play-mapping.txt"
        for path, payload in (
            (self.apk, b"apk"),
            (self.aab, b"aab"),
            (self.sideload_mapping, b"sideload mapping"),
            (self.play_mapping, b"play mapping"),
        ):
            path.write_bytes(payload)
        self.output = self.root / "output"
        self.package_args = Namespace(
            version="1.15.1",
            version_code="54",
            commit="a" * 40,
            tree="b" * 40,
            sideload_apk=self.apk,
            google_play_aab=self.aab,
            sideload_mapping=self.sideload_mapping,
            google_play_mapping=self.play_mapping,
            output=self.output,
        )
        self.verify_args = Namespace(
            version="1.15.1",
            version_code="54",
            tree="b" * 40,
            directory=self.output,
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def package(self) -> None:
        with redirect_stdout(io.StringIO()):
            MODULE.package_artifacts(self.package_args)

    def test_package_and_verify_round_trip(self) -> None:
        self.package()
        with redirect_stdout(io.StringIO()):
            MODULE.verify_artifacts(self.verify_args)
        manifest = json.loads(
            (self.output / MODULE.MANIFEST_NAME).read_text(encoding="utf-8")
        )
        self.assertEqual(set(MODULE.EXPECTED_ROLES), {item["role"] for item in manifest["artifacts"]})
        checksums = (self.output / MODULE.CHECKSUMS_NAME).read_text(encoding="utf-8")
        self.assertIn(self.apk.name, checksums)
        self.assertIn(self.aab.name, checksums)
        self.assertNotIn("mapping", checksums)

    def test_verify_rejects_tampered_binary(self) -> None:
        self.package()
        (self.output / self.apk.name).write_bytes(b"tampered")
        with self.assertRaisesRegex(MODULE.ArtifactError, "SHA-256 mismatch"):
            MODULE.verify_artifacts(self.verify_args)

    def test_verify_rejects_wrong_tree(self) -> None:
        self.package()
        self.verify_args.tree = "c" * 40
        with self.assertRaisesRegex(MODULE.ArtifactError, "tree mismatch"):
            MODULE.verify_artifacts(self.verify_args)

    def test_verify_rejects_extra_file(self) -> None:
        self.package()
        (self.output / "unexpected.bin").write_bytes(b"unexpected")
        with self.assertRaisesRegex(MODULE.ArtifactError, "artifact set mismatch"):
            MODULE.verify_artifacts(self.verify_args)

    def test_package_requires_canonical_public_names(self) -> None:
        self.package_args.sideload_apk = self.root / "wrong.apk"
        self.package_args.sideload_apk.write_bytes(b"wrong")
        with self.assertRaisesRegex(MODULE.ArtifactError, "must be named"):
            MODULE.package_artifacts(self.package_args)


if __name__ == "__main__":
    unittest.main()
