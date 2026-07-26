from __future__ import annotations

import importlib.util
import io
import json
import tempfile
import unittest
import zipfile
from argparse import Namespace
from contextlib import redirect_stdout
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("android-release-artifacts.py")
SPEC = importlib.util.spec_from_file_location("android_release_artifacts", SCRIPT_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AndroidReleaseArtifactsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.apk = self.root / "hermes-relay-1.5.2-sideload-release.apk"
        self.aab = self.root / "hermes-relay-1.5.2-googlePlay-release.aab"
        self.apk.write_bytes(b"apk payload")
        self.aab.write_bytes(b"aab payload")
        self.output = self.root / "release"
        self.package_args = Namespace(
            version="1.5.2",
            version_code="35",
            commit="a" * 40,
            tree="b" * 40,
            sideload_apk=self.apk,
            google_play_aab=self.aab,
            output=self.output,
        )
        self.verify_args = Namespace(
            version="1.5.2",
            version_code="35",
            tree="b" * 40,
            directory=self.output,
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_package_and_verify_round_trip(self) -> None:
        with redirect_stdout(io.StringIO()):
            MODULE.package_artifacts(self.package_args)
            MODULE.verify_artifacts(self.verify_args)

        manifest = json.loads(
            (self.output / MODULE.MANIFEST_NAME).read_text(encoding="utf-8")
        )
        self.assertEqual("1.5.2", manifest["version"])
        self.assertEqual("35", manifest["versionCode"])
        self.assertEqual(
            {"google-play-aab", "sideload-apk"},
            {artifact["role"] for artifact in manifest["artifacts"]},
        )

    def test_verify_rejects_modified_artifact(self) -> None:
        with redirect_stdout(io.StringIO()):
            MODULE.package_artifacts(self.package_args)
        packaged_apk = self.output / self.apk.name
        packaged_apk.write_bytes(b"tampered")

        with self.assertRaisesRegex(MODULE.ArtifactError, "SHA-256 mismatch"):
            MODULE.verify_artifacts(self.verify_args)

    def test_verify_rejects_wrong_release_tree(self) -> None:
        with redirect_stdout(io.StringIO()):
            MODULE.package_artifacts(self.package_args)
        self.verify_args.tree = "c" * 40

        with self.assertRaisesRegex(MODULE.ArtifactError, "tree mismatch"):
            MODULE.verify_artifacts(self.verify_args)

    def test_package_rejects_wrong_extension(self) -> None:
        self.package_args.sideload_apk = self.root / "not-an-apk.txt"
        self.package_args.sideload_apk.write_text("no", encoding="utf-8")

        with self.assertRaisesRegex(MODULE.ArtifactError, "must be named"):
            with redirect_stdout(io.StringIO()):
                MODULE.package_artifacts(self.package_args)

    def test_verify_rejects_unexpected_file(self) -> None:
        with redirect_stdout(io.StringIO()):
            MODULE.package_artifacts(self.package_args)
        (self.output / "extra.bin").write_bytes(b"unexpected")

        with self.assertRaisesRegex(MODULE.ArtifactError, "unexpected"):
            MODULE.verify_artifacts(self.verify_args)

    def test_extract_rejects_path_traversal(self) -> None:
        archive = self.root / "artifact.zip"
        with zipfile.ZipFile(archive, "w") as handle:
            handle.writestr("../outside.txt", "unsafe")

        with self.assertRaisesRegex(MODULE.ArtifactError, "Unsafe artifact filename"):
            MODULE.extract_archive(
                Namespace(archive=archive, output=self.root / "extracted")
            )
        self.assertFalse((self.root / "outside.txt").exists())


if __name__ == "__main__":
    unittest.main()
