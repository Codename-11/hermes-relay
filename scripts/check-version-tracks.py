#!/usr/bin/env python3
"""Report and validate Hermes-Relay monorepo release tracks.

The repo ships three independently versioned artifacts:

- Android app: ``android-v*`` tags, source in ``gradle/libs.versions.toml``.
- Plugin: ``plugin-v*`` tags, source in ``pyproject.toml``.
- CLI: ``cli-v*`` tags, source in ``desktop/package.json``.

This script gives release prep one command that checks the sources without
forcing unrelated artifacts to share the same SemVer.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import tomllib
from dataclasses import dataclass


REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$")


@dataclass(frozen=True)
class Track:
    name: str
    version: str
    source: str
    tag: str
    details: str = ""


def _read_text(rel_path: str) -> str:
    return (REPO_ROOT / rel_path).read_text(encoding="utf-8")


def _read_json(rel_path: str) -> object:
    return json.loads(_read_text(rel_path))


def _regex_version(rel_path: str, pattern: str) -> str:
    match = re.search(pattern, _read_text(rel_path), re.MULTILINE)
    if not match:
        raise ValueError(f"{rel_path} has no parseable version")
    return match.group(1)


def _json_version(rel_path: str) -> str:
    data = _read_json(rel_path)
    if not isinstance(data, dict) or not isinstance(data.get("version"), str):
        raise ValueError(f"{rel_path} has no parseable version")
    return data["version"]


def _package_lock_root_version() -> str:
    data = _read_json("plugin/dashboard/package-lock.json")
    if not isinstance(data, dict) or not isinstance(data.get("packages"), dict):
        raise ValueError("plugin/dashboard/package-lock.json has no packages object")
    root_pkg = data["packages"].get("")
    if not isinstance(root_pkg, dict) or not isinstance(root_pkg.get("version"), str):
        raise ValueError('plugin/dashboard/package-lock.json packages[""] has no version')
    return root_pkg["version"]


def _desktop_package_lock_versions() -> tuple[str, str]:
    data = _read_json("desktop/package-lock.json")
    if not isinstance(data, dict) or not isinstance(data.get("version"), str):
        raise ValueError("desktop/package-lock.json has no root version")
    packages = data.get("packages")
    if not isinstance(packages, dict):
        raise ValueError("desktop/package-lock.json has no packages object")
    root_pkg = packages.get("")
    if not isinstance(root_pkg, dict) or not isinstance(root_pkg.get("version"), str):
        raise ValueError('desktop/package-lock.json packages[""] has no version')
    return data["version"], root_pkg["version"]


def _android_track(errors: list[str]) -> Track:
    data = tomllib.loads(_read_text("gradle/libs.versions.toml"))
    versions = data.get("versions")
    if not isinstance(versions, dict):
        raise ValueError("gradle/libs.versions.toml has no [versions] table")
    version = str(versions.get("appVersionName", ""))
    code = str(versions.get("appVersionCode", ""))
    if not SEMVER_RE.match(version):
        errors.append(f"android version is not SemVer: {version!r}")
    if not code.isdigit():
        errors.append(f"android versionCode is not numeric: {code!r}")
    return Track(
        name="android",
        version=version,
        source="gradle/libs.versions.toml",
        tag=f"android-v{version}",
        details=f"versionCode {code}",
    )


def _plugin_track(errors: list[str]) -> Track:
    pyproject = tomllib.loads(_read_text("pyproject.toml"))
    project = pyproject.get("project")
    if not isinstance(project, dict):
        raise ValueError("pyproject.toml has no [project] table")
    version = str(project.get("version", ""))
    versions = [
        ("pyproject.toml", version),
        (
            "plugin/relay/__init__.py",
            _regex_version("plugin/relay/__init__.py", r'^__version__\s*=\s*"([^"]+)"'),
        ),
        ("plugin/plugin.yaml", _regex_version("plugin/plugin.yaml", r"^version:\s*([^\s#]+)")),
        ("plugin/dashboard/manifest.json", _json_version("plugin/dashboard/manifest.json")),
        ("plugin/dashboard/package.json", _json_version("plugin/dashboard/package.json")),
        ("plugin/dashboard/package-lock.json", _json_version("plugin/dashboard/package-lock.json")),
        ('plugin/dashboard/package-lock.json packages[""]', _package_lock_root_version()),
    ]
    if not SEMVER_RE.match(version):
        errors.append(f"plugin version is not SemVer: {version!r}")
    for source, found in versions:
        if found != version:
            errors.append(f"plugin version mismatch: {source} has {found}, expected {version}")
    return Track(
        name="plugin",
        version=version,
        source="pyproject.toml",
        tag=f"plugin-v{version}",
        details="plugin + dashboard metadata",
    )


def _cli_track(errors: list[str]) -> Track:
    version = _json_version("desktop/package.json")
    generated = _regex_version("desktop/src/version.ts", r'^export const VERSION = "([^"]+)" as const')
    package_lock, package_lock_root = _desktop_package_lock_versions()
    versions = [
        ("desktop/src/version.ts", generated),
        ("desktop/package-lock.json", package_lock),
        ('desktop/package-lock.json packages[""]', package_lock_root),
        (
            "desktop/tray/Cargo.toml",
            _regex_version(
                "desktop/tray/Cargo.toml",
                r'^version\s*=\s*"([^"]+)"',
            ),
        ),
        (
            "desktop/tray/Cargo.lock",
            _regex_version(
                "desktop/tray/Cargo.lock",
                r'\[\[package\]\]\s*\nname\s*=\s*"hermes-relay-tray"\s*\nversion\s*=\s*"([^"]+)"',
            ),
        ),
    ]
    if not SEMVER_RE.match(version):
        errors.append(f"desktop CLI version is not SemVer: {version!r}")
    for source, found in versions:
        if found != version:
            errors.append(
                f"desktop CLI version mismatch: {source} has {found}, expected {version}; "
                "run npm run sync:version in desktop/"
            )
    return Track(
        name="cli",
        version=version,
        source="desktop/package.json",
        tag=f"cli-v{version}",
        details="CLI + tray metadata",
    )


def collect_tracks() -> tuple[list[Track], list[str]]:
    errors: list[str] = []
    tracks = [
        _android_track(errors),
        _plugin_track(errors),
        _cli_track(errors),
    ]
    return tracks, errors


def _print_table(tracks: list[Track]) -> None:
    headers = ("track", "version", "source", "tag", "details")
    rows = [(t.name, t.version, t.source, t.tag, t.details) for t in tracks]
    widths = [
        max(len(headers[index]), *(len(row[index]) for row in rows))
        for index in range(len(headers))
    ]
    print("  ".join(header.ljust(widths[index]) for index, header in enumerate(headers)))
    print("  ".join("-" * width for width in widths))
    for row in rows:
        print("  ".join(value.ljust(widths[index]) for index, value in enumerate(row)))


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate monorepo release track versions.")
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON")
    args = parser.parse_args()

    try:
        tracks, errors = collect_tracks()
    except Exception as exc:
        print(f"version track check failed: {exc}", file=sys.stderr)
        return 1

    if args.json:
        print(
            json.dumps(
                {
                    "tracks": [track.__dict__ for track in tracks],
                    "ok": not errors,
                    "errors": errors,
                },
                indent=2,
            )
        )
    else:
        _print_table(tracks)
        if not errors:
            print("\nrelease track versions are internally consistent")

    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
