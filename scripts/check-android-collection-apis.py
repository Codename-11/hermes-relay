#!/usr/bin/env python3
"""Reject collection endpoint APIs that can crash on pre-Android 15 devices.

Kotlin calls such as ``MutableList.removeFirst()`` and ``removeLast()`` can be
compiled against Java 21's ``SequencedCollection`` methods when newer Android
SDKs are on the compile classpath. Those methods do not exist before API 35.

The source scan keeps first-party Kotlin explicit. The optional APK scan checks
the final minified DEX as well, which catches unsafe calls introduced by
transitive dependencies or compiler/R8 changes.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE_CALL = re.compile(r"\.remove(?:First|Last)\s*\(")
DEX_CALL = re.compile(
    r"invoke-(?:interface|virtual)(?:/range)? .*"
    r"Ljava/util/(?:List|SequencedCollection);\.remove(?:First|Last):"
)
IGNORED_PARTS = {".git", ".gradle", ".idea", "build", "node_modules"}


def scan_sources() -> list[str]:
    failures: list[str] = []
    for path in ROOT.rglob("*.kt"):
        if any(part in IGNORED_PARTS for part in path.parts):
            continue
        for line_number, line in enumerate(
            path.read_text(encoding="utf-8").splitlines(),
            start=1,
        ):
            if SOURCE_CALL.search(line):
                relative = path.relative_to(ROOT)
                failures.append(f"{relative}:{line_number}: {line.strip()}")
    return failures


def find_dexdump() -> Path:
    executable = shutil.which("dexdump")
    if executable:
        return Path(executable)

    sdk_roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        str(Path.home() / "Android" / "Sdk"),
    ]
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        sdk_roots.append(str(Path(local_app_data) / "Android" / "Sdk"))

    for sdk_root in filter(None, sdk_roots):
        candidates = sorted(
            Path(sdk_root).glob("build-tools/*/dexdump*"),
            reverse=True,
        )
        if candidates:
            return candidates[0]

    raise RuntimeError(
        "dexdump was not found; install Android SDK build-tools or add dexdump to PATH"
    )


def scan_dex(dexdump: Path, dex_path: Path, apk_path: Path) -> list[str]:
    failures: list[str] = []
    process = subprocess.Popen(
        [str(dexdump), "-d", str(dex_path)],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    assert process.stdout is not None
    for line in process.stdout:
        if DEX_CALL.search(line):
            failures.append(f"{apk_path.name}/{dex_path.name}: {line.strip()}")
    return_code = process.wait()
    if return_code != 0:
        raise RuntimeError(f"dexdump failed for {dex_path} with exit code {return_code}")
    return failures


def scan_apk(apk_path: Path, dexdump: Path) -> list[str]:
    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="hermes-dex-scan-") as directory:
        temp_dir = Path(directory)
        with zipfile.ZipFile(apk_path) as archive:
            dex_names = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
            if not dex_names:
                raise RuntimeError(f"no classes*.dex files found in {apk_path}")
            for dex_name in dex_names:
                dex_path = temp_dir / Path(dex_name).name
                dex_path.write_bytes(archive.read(dex_name))
                failures.extend(scan_dex(dexdump, dex_path, apk_path))
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--apk",
        action="append",
        type=Path,
        default=[],
        help="Release APK to inspect; repeat for multiple product flavors.",
    )
    args = parser.parse_args()

    failures = scan_sources()
    if args.apk:
        dexdump = find_dexdump()
        for apk_path in args.apk:
            if not apk_path.is_file():
                raise FileNotFoundError(apk_path)
            failures.extend(scan_apk(apk_path, dexdump))

    if failures:
        print("Unsafe Android collection endpoint API calls found:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        print(
            "Use removeAt(0) or removeAt(list.lastIndex) for Kotlin List values.",
            file=sys.stderr,
        )
        return 1

    scope = "Kotlin sources and release DEX" if args.apk else "Kotlin sources"
    print(f"Android collection API compatibility check passed ({scope})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
