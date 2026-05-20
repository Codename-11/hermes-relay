#!/usr/bin/env python3
"""Verify relay-owned version metadata stays in sync.

Relay server releases use the `relay-v*` track. The canonical version is
`pyproject.toml`'s `[project].version`; plugin and dashboard metadata should
match because they ship as one Hermes plugin package.

Android and desktop versions are intentionally separate release tracks and are
not checked here.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import tomllib
from collections.abc import Callable


REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]


def _read_text(rel_path: str) -> str:
    return (REPO_ROOT / rel_path).read_text(encoding="utf-8")


def _read_json(rel_path: str) -> object:
    return json.loads(_read_text(rel_path))


def _pyproject_version() -> str:
    data = tomllib.loads(_read_text("pyproject.toml"))
    return str(data["project"]["version"])


def _regex_version(rel_path: str, pattern: str) -> str:
    text = _read_text(rel_path)
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        raise ValueError(f"{rel_path} has no parseable version")
    return match.group(1)


def _json_version(rel_path: str, accessor: Callable[[object], object]) -> str:
    value = accessor(_read_json(rel_path))
    if not isinstance(value, str) or not value:
        raise ValueError(f"{rel_path} has no parseable version")
    return value


def _root_package_lock_version(data: object) -> object:
    if not isinstance(data, dict):
        raise ValueError("package-lock root is not an object")
    packages = data.get("packages")
    if not isinstance(packages, dict):
        raise ValueError("package-lock has no packages object")
    root_pkg = packages.get("")
    if not isinstance(root_pkg, dict):
        raise ValueError("package-lock has no root package entry")
    return root_pkg.get("version")


def collect_versions() -> list[tuple[str, str]]:
    return [
        ("pyproject.toml", _pyproject_version()),
        (
            "plugin/relay/__init__.py",
            _regex_version(
                "plugin/relay/__init__.py",
                r'^__version__\s*=\s*"([^"]+)"',
            ),
        ),
        (
            "plugin/plugin.yaml",
            _regex_version("plugin/plugin.yaml", r"^version:\s*([^\s#]+)"),
        ),
        (
            "plugin/dashboard/manifest.json",
            _json_version(
                "plugin/dashboard/manifest.json",
                lambda data: data["version"],  # type: ignore[index]
            ),
        ),
        (
            "plugin/dashboard/package.json",
            _json_version(
                "plugin/dashboard/package.json",
                lambda data: data["version"],  # type: ignore[index]
            ),
        ),
        (
            "plugin/dashboard/package-lock.json",
            _json_version(
                "plugin/dashboard/package-lock.json",
                lambda data: data["version"],  # type: ignore[index]
            ),
        ),
        (
            'plugin/dashboard/package-lock.json packages[""]',
            _json_version(
                "plugin/dashboard/package-lock.json",
                _root_package_lock_version,
            ),
        ),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate relay/plugin/dashboard version metadata."
    )
    parser.add_argument(
        "--expect",
        help="Require all relay-owned metadata to match this exact version.",
    )
    args = parser.parse_args()

    try:
        versions = collect_versions()
    except Exception as exc:
        print(f"version sync check failed: {exc}", file=sys.stderr)
        return 1

    canonical = versions[0][1]
    if args.expect and canonical != args.expect:
        print(
            f"pyproject.toml version {canonical!r} does not match expected "
            f"{args.expect!r}",
            file=sys.stderr,
        )
        return 1

    width = max(len(label) for label, _ in versions)
    for label, version in versions:
        print(f"{label:<{width}}  {version}")

    mismatches = [(label, version) for label, version in versions if version != canonical]
    if mismatches:
        print(
            f"relay version metadata is out of sync; expected {canonical}",
            file=sys.stderr,
        )
        for label, version in mismatches:
            print(f"  {label}: {version}", file=sys.stderr)
        return 1

    print(f"relay version metadata is in sync: {canonical}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
