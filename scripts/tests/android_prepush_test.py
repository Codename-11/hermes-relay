from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path
from unittest import mock


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "android-prepush.py"
SPEC = importlib.util.spec_from_file_location("android_prepush", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT_PATH}")
android_prepush = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(android_prepush)


class AndroidPrepushTest(unittest.TestCase):
    def run_main(self, *arguments: str) -> list[mock._Call]:
        with (
            mock.patch.object(sys, "argv", ["android-prepush.py", *arguments]),
            mock.patch.object(android_prepush, "android_environment", return_value={}),
            mock.patch.object(android_prepush, "run") as run,
        ):
            self.assertEqual(android_prepush.main(), 0)
        return run.call_args_list

    def test_default_local_gate_runs_sideload_tests_only(self) -> None:
        calls = self.run_main()
        gradle = calls[-1].args[1]

        self.assertIn(":app:lintGooglePlayDebug", gradle)
        self.assertIn(":app:testSideloadDebugUnitTest", gradle)
        self.assertNotIn(":app:testGooglePlayDebugUnitTest", gradle)

    def test_both_flavors_adds_google_play_focused_tests(self) -> None:
        calls = self.run_main("--both-flavors")
        gradle = calls[-1].args[1]

        self.assertIn("lint", gradle)
        self.assertNotIn(":app:lintGooglePlayDebug", gradle)
        self.assertIn(":app:testSideloadDebugUnitTest", gradle)
        self.assertIn(":app:testGooglePlayDebugUnitTest", gradle)

    def test_release_prep_runs_only_release_presentation_tests(self) -> None:
        calls = self.run_main("--release-prep")
        gradle = calls[-1].args[1]

        self.assertNotIn(":app:lintGooglePlayDebug", gradle)
        self.assertNotIn("lint", gradle)
        self.assertIn(":app:testSideloadDebugUnitTest", gradle)
        for test_name in android_prepush.RELEASE_PREP_TESTS:
            self.assertIn(test_name, gradle)
        self.assertNotIn(android_prepush.FOCUSED_TESTS[0], gradle)

    def test_release_prep_rejects_full_lane_flags(self) -> None:
        with (
            mock.patch.object(
                sys,
                "argv",
                ["android-prepush.py", "--release-prep", "--both-flavors"],
            ),
            self.assertRaises(SystemExit),
        ):
            android_prepush.main()


if __name__ == "__main__":
    unittest.main()
