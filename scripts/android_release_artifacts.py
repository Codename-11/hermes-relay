#!/usr/bin/env python3
"""Package and verify immutable Android Play-preflight release artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
MANIFEST_NAME = "play-preflight.json"
CHECKSUMS_NAME = "SHA256SUMS.txt"
GIT_OBJECT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
EXPECTED_ROLES = {
    "sideload-apk": "hermes-relay-{version}-sideload-release.apk",
    "google-play-aab": "hermes-relay-{version}-googlePlay-release.aab",
    "sideload-mapping": "mapping-sideloadRelease.txt",
    "google-play-mapping": "mapping-googlePlayRelease.txt",
}
PUBLIC_ROLES = {"sideload-apk", "google-play-aab"}


class ArtifactError(ValueError):
    """Raised when a release artifact set violates its contract."""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_name(name: str) -> str:
    candidate = Path(name)
    if candidate.name != name or name in {"", ".", ".."}:
        raise ArtifactError(f"unsafe artifact filename: {name!r}")
    return name


def checksum_text(entries: list[dict[str, Any]]) -> str:
    public = [entry for entry in entries if entry["role"] in PUBLIC_ROLES]
    return "".join(
        f"{entry['sha256']}  {entry['name']}\n"
        for entry in sorted(public, key=lambda item: item["name"])
    )


def package_artifacts(args: argparse.Namespace) -> None:
    if not GIT_OBJECT_PATTERN.fullmatch(args.commit):
        raise ArtifactError(f"invalid commit id: {args.commit!r}")
    if not GIT_OBJECT_PATTERN.fullmatch(args.tree):
        raise ArtifactError(f"invalid tree id: {args.tree!r}")
    if not str(args.version_code).isdigit():
        raise ArtifactError(f"invalid versionCode: {args.version_code!r}")

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ArtifactError(f"output directory must be empty: {output}")

    sources = {
        "sideload-apk": args.sideload_apk.resolve(),
        "google-play-aab": args.google_play_aab.resolve(),
        "sideload-mapping": args.sideload_mapping.resolve(),
        "google-play-mapping": args.google_play_mapping.resolve(),
    }
    entries: list[dict[str, Any]] = []
    for role, source in sources.items():
        if not source.is_file():
            raise ArtifactError(f"missing {role}: {source}")
        destination_name = safe_name(EXPECTED_ROLES[role].format(version=args.version))
        if role in PUBLIC_ROLES and source.name != destination_name:
            raise ArtifactError(
                f"{role} must be named {destination_name}, got {source.name}"
            )
        destination = output / destination_name
        shutil.copy2(source, destination)
        entries.append(
            {
                "role": role,
                "name": destination.name,
                "sha256": sha256(destination),
                "size": destination.stat().st_size,
            }
        )

    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "version": args.version,
        "versionCode": str(args.version_code),
        "commit": args.commit,
        "tree": args.tree,
        "track": "production",
        "status": "draft",
        "artifacts": sorted(entries, key=lambda item: item["role"]),
    }
    (output / MANIFEST_NAME).write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (output / CHECKSUMS_NAME).write_text(
        checksum_text(entries),
        encoding="utf-8",
        newline="\n",
    )
    print(json.dumps(manifest, sort_keys=True))


def verify_artifacts(args: argparse.Namespace) -> None:
    if not GIT_OBJECT_PATTERN.fullmatch(args.tree):
        raise ArtifactError(f"invalid expected tree id: {args.tree!r}")
    if not str(args.version_code).isdigit():
        raise ArtifactError(f"invalid expected versionCode: {args.version_code!r}")

    directory = args.directory.resolve()
    try:
        manifest = json.loads((directory / MANIFEST_NAME).read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ArtifactError(f"missing {MANIFEST_NAME}") from exc
    except json.JSONDecodeError as exc:
        raise ArtifactError(f"invalid {MANIFEST_NAME}: {exc}") from exc
    if not isinstance(manifest, dict):
        raise ArtifactError(f"{MANIFEST_NAME} must contain an object")

    expected_metadata = {
        "schemaVersion": SCHEMA_VERSION,
        "version": args.version,
        "versionCode": str(args.version_code),
        "tree": args.tree,
        "track": "production",
        "status": "draft",
    }
    for key, expected in expected_metadata.items():
        if str(manifest.get(key)) != str(expected):
            raise ArtifactError(
                f"manifest {key} mismatch: expected {expected!r}, got {manifest.get(key)!r}"
            )
    commit = manifest.get("commit")
    if not isinstance(commit, str) or not GIT_OBJECT_PATTERN.fullmatch(commit):
        raise ArtifactError(f"manifest commit is invalid: {commit!r}")

    raw_entries = manifest.get("artifacts")
    if not isinstance(raw_entries, list):
        raise ArtifactError("manifest artifacts must be a list")
    entries: dict[str, dict[str, Any]] = {}
    for raw_entry in raw_entries:
        if not isinstance(raw_entry, dict):
            raise ArtifactError("every artifact entry must be an object")
        role = raw_entry.get("role")
        if role not in EXPECTED_ROLES:
            raise ArtifactError(f"unexpected artifact role: {role!r}")
        if role in entries:
            raise ArtifactError(f"duplicate artifact role: {role}")
        entries[str(role)] = raw_entry
    if set(entries) != set(EXPECTED_ROLES):
        missing = sorted(set(EXPECTED_ROLES) - set(entries))
        raise ArtifactError("missing artifact roles: " + ", ".join(missing))

    verified: list[dict[str, Any]] = []
    for role, expected_pattern in EXPECTED_ROLES.items():
        entry = entries[role]
        name = safe_name(str(entry.get("name", "")))
        expected_name = expected_pattern.format(version=args.version)
        if name != expected_name:
            raise ArtifactError(f"{role} must be named {expected_name}, got {name}")
        path = directory / name
        if not path.is_file():
            raise ArtifactError(f"missing artifact file: {name}")
        if sha256(path) != entry.get("sha256"):
            raise ArtifactError(f"SHA-256 mismatch for {name}")
        if path.stat().st_size != entry.get("size"):
            raise ArtifactError(f"size mismatch for {name}")
        verified.append(entry)

    try:
        actual_checksums = (directory / CHECKSUMS_NAME).read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        raise ArtifactError(f"missing {CHECKSUMS_NAME}") from exc
    if actual_checksums != checksum_text(verified):
        raise ArtifactError(f"{CHECKSUMS_NAME} does not match the manifest")

    allowed = {
        MANIFEST_NAME,
        CHECKSUMS_NAME,
        *(entry["name"] for entry in verified),
    }
    actual = {path.name for path in directory.iterdir() if path.is_file()}
    if actual != allowed:
        raise ArtifactError(
            "artifact set mismatch: expected "
            + ", ".join(sorted(allowed))
            + "; got "
            + ", ".join(sorted(actual))
        )
    if any(not path.is_file() for path in directory.iterdir()):
        raise ArtifactError("artifact directory contains a non-file entry")
    print(json.dumps(manifest, sort_keys=True))


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)

    package = commands.add_parser("package")
    package.add_argument("--version", required=True)
    package.add_argument("--version-code", required=True)
    package.add_argument("--commit", required=True)
    package.add_argument("--tree", required=True)
    package.add_argument("--sideload-apk", required=True, type=Path)
    package.add_argument("--google-play-aab", required=True, type=Path)
    package.add_argument("--sideload-mapping", required=True, type=Path)
    package.add_argument("--google-play-mapping", required=True, type=Path)
    package.add_argument("--output", required=True, type=Path)
    package.set_defaults(func=package_artifacts)

    verify = commands.add_parser("verify")
    verify.add_argument("--version", required=True)
    verify.add_argument("--version-code", required=True)
    verify.add_argument("--tree", required=True)
    verify.add_argument("--directory", required=True, type=Path)
    verify.set_defaults(func=verify_artifacts)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        args.func(args)
    except ArtifactError as exc:
        print(f"artifact contract failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
