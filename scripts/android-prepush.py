#!/usr/bin/env python3
"""Run the optional full local Android pre-push gate.

By default the command checks the primary Play debug variant and focused CI
unit tests in one Gradle invocation. `--both-flavors` expands that to full lint
and both focused flavor shards. Agents should prefer Android On-Demand after an
exact commit is already pushed; this remains available when full local proof is
explicitly wanted or cloud execution is unavailable.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import subprocess
import sys


REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
FOCUSED_TESTS = (
    "com.hermesandroid.relay.network.ArchitectureBoundaryTest",
    "com.hermesandroid.relay.network.relay.RelayUrlDeriverTest",
    "com.hermesandroid.relay.viewmodel.ConnectionSwitchTest",
    "com.hermesandroid.relay.util.ServerAddressTest",
    "com.hermesandroid.relay.util.IssueReportAndDiagnosticsTest",
    "com.hermesandroid.relay.data.AppLanguageTest",
    "com.hermesandroid.relay.viewmodel.ChatStreamRecoveryTest",
    "com.hermesandroid.relay.viewmodel.ChatViewModelRealtimeTurnTest",
    "com.hermesandroid.relay.network.relay.RealtimeVoiceEventParsingTest",
    "com.hermesandroid.relay.voice.VoiceCommandInterpreterTest",
    "com.hermesandroid.relay.data.VoiceModePresetTest",
    "com.hermesandroid.relay.ui.components.BackgroundTaskCardTest",
    "com.hermesandroid.relay.ui.components.DotMatrixIndicatorTest",
    "com.hermesandroid.relay.ui.components.AttachmentGalleryLayoutTest",
    "com.hermesandroid.relay.ui.components.ChangelogParserTest",
    "com.hermesandroid.relay.ui.components.MarkdownStreamingParserTest",
    "com.hermesandroid.relay.ui.screens.ChangelogScreenTest",
    "com.hermesandroid.relay.ui.screens.ChatUnreadStateTest",
)
REPOSITORY_CHECKS = (
    "check-android-locales.py",
    "check-user-docs-locales.py",
    "check-android-collection-apis.py",
    "check-android-release-notes.py",
    "check-version-tracks.py",
)


def run(label: str, command: list[str], env: dict[str, str]) -> None:
    print(f"\n==> {label}", flush=True)
    completed = subprocess.run(command, cwd=REPO_ROOT, env=env, check=False)
    if completed.returncode:
        raise SystemExit(f"{label} failed with exit code {completed.returncode}")


def android_environment() -> dict[str, str]:
    env = os.environ.copy()
    if env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT"):
        return env

    if sys.platform == "win32":
        local_app_data = env.get("LOCALAPPDATA")
        if local_app_data:
            sdk = pathlib.Path(local_app_data) / "Android" / "Sdk"
            if sdk.is_dir():
                env["ANDROID_HOME"] = str(sdk)
                env["ANDROID_SDK_ROOT"] = str(sdk)
                print(f"Using Android SDK at {sdk}")
    return env


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-lint", action="store_true", help="Skip Android lint")
    parser.add_argument("--skip-tests", action="store_true", help="Skip the focused unit-test shard")
    parser.add_argument(
        "--both-flavors",
        action="store_true",
        help="Run full lint and focused tests for sideload and Google Play",
    )
    args = parser.parse_args()

    env = android_environment()
    for script in REPOSITORY_CHECKS:
        run(script, [sys.executable, str(REPO_ROOT / "scripts" / script)], env)

    tasks: list[str] = []
    if not args.skip_lint:
        tasks.append("lint" if args.both_flavors else ":app:lintGooglePlayDebug")
    if not args.skip_tests:
        tasks.append(":app:testSideloadDebugUnitTest")
        if args.both_flavors:
            tasks.append(":app:testGooglePlayDebugUnitTest")
    if not tasks:
        print("\nAndroid repository checks passed.")
        return 0

    if sys.platform == "win32":
        gradle = [
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(REPO_ROOT / "scripts" / "android-lane.ps1"),
            "gradle",
        ]
    else:
        gradle = [str(REPO_ROOT / "gradlew")]
    gradle.extend([
        "--console=plain",
        "--configuration-cache",
        *tasks,
    ])
    if not args.skip_tests:
        for test_name in FOCUSED_TESTS:
            gradle.extend(("--tests", test_name))
    label = (
        "Full local Android lint and focused tests"
        if args.both_flavors
        else "Google Play lint and focused tests"
    )
    run(label, gradle, env)
    print("\nAndroid pre-push checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
