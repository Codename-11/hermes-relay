#!/usr/bin/env python3
"""Run fast Android checks before pushing a PR update.

The command checks the primary Play debug variant and focused CI unit tests in
one Gradle invocation. Hosted CI remains the exhaustive all-variant gate.
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
    "com.hermesandroid.relay.ui.components.MarkdownStreamingParserTest",
    "com.hermesandroid.relay.ui.screens.ChatUnreadStateTest",
)
REPOSITORY_CHECKS = (
    "check-android-locales.py",
    "check-user-docs-locales.py",
    "check-android-collection-apis.py",
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
    args = parser.parse_args()

    env = android_environment()
    for script in REPOSITORY_CHECKS:
        run(script, [sys.executable, str(REPO_ROOT / "scripts" / script)], env)

    tasks: list[str] = []
    if not args.skip_lint:
        tasks.append(":app:lintGooglePlayDebug")
    if not args.skip_tests:
        tasks.append(":app:testSideloadDebugUnitTest")
    if not tasks:
        print("\nAndroid repository checks passed.")
        return 0

    wrapper = REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    gradle = [
        str(wrapper),
        "--console=plain",
        "--configuration-cache",
        *tasks,
    ]
    if not args.skip_tests:
        for test_name in FOCUSED_TESTS:
            gradle.extend(("--tests", test_name))
    run("Google Play lint and focused tests", gradle, env)
    print("\nAndroid pre-push checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
