#!/usr/bin/env python3
"""Package and verify the immutable Android Play-preflight artifact set."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
MANIFEST_NAME = "play-preflight.json"
CHECKSUMS_NAME = "SHA256SUMS.txt"
EXPECTED_ROLES = {
    "sideload-apk": "hermes-relay-{version}-sideload-release.apk",
    "google-play-aab": "hermes-relay-{version}-googlePlay-release.aab",
}
GIT_OBJECT_PATTERN = re.compile(r"^[0-9a-f]{40}$")


class ArtifactError(ValueError):
    """Raised when a release artifact set violates its contract."""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_artifact_name(name: str) -> str:
    candidate = Path(name)
    if candidate.name != name or name in {"", ".", ".."}:
        raise ArtifactError(f"Unsafe artifact filename: {name!r}")
    return name


def expected_checksum_text(artifacts: list[dict[str, Any]]) -> str:
    lines = [
        f"{artifact['sha256']}  {artifact['name']}"
        for artifact in sorted(artifacts, key=lambda item: item["name"])
    ]
    return "\n".join(lines) + "\n"


def package_artifacts(args: argparse.Namespace) -> None:
    if not GIT_OBJECT_PATTERN.fullmatch(args.commit):
        raise ArtifactError(f"Invalid commit id: {args.commit!r}")
    if not GIT_OBJECT_PATTERN.fullmatch(args.tree):
        raise ArtifactError(f"Invalid tree id: {args.tree!r}")
    if not str(args.version_code).isdigit():
        raise ArtifactError(f"Invalid versionCode: {args.version_code!r}")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ArtifactError(f"Output directory must be empty: {output}")

    sources = {
        "sideload-apk": args.sideload_apk.resolve(),
        "google-play-aab": args.google_play_aab.resolve(),
    }
    artifacts: list[dict[str, Any]] = []
    for role, source in sources.items():
        if not source.is_file():
            raise ArtifactError(f"Missing {role}: {source}")
        expected_name = EXPECTED_ROLES[role].format(version=args.version)
        if source.name != expected_name:
            raise ArtifactError(
                f"{role} must be named {expected_name}, got {source.name}"
            )

        destination = output / safe_artifact_name(source.name)
        shutil.copy2(source, destination)
        artifacts.append(
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
        "artifacts": sorted(artifacts, key=lambda item: item["role"]),
    }
    (output / MANIFEST_NAME).write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (output / CHECKSUMS_NAME).write_text(
        expected_checksum_text(artifacts),
        encoding="utf-8",
        newline="\n",
    )
    print(json.dumps(manifest, sort_keys=True))


def load_manifest(directory: Path) -> dict[str, Any]:
    manifest_path = directory / MANIFEST_NAME
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ArtifactError(f"Missing {MANIFEST_NAME} in {directory}") from exc
    except json.JSONDecodeError as exc:
        raise ArtifactError(f"Invalid {MANIFEST_NAME}: {exc}") from exc
    if not isinstance(manifest, dict):
        raise ArtifactError(f"{MANIFEST_NAME} must contain a JSON object")
    return manifest


def extract_archive(args: argparse.Namespace) -> None:
    archive_path = args.archive.resolve()
    if not archive_path.is_file():
        raise ArtifactError(f"Artifact archive does not exist: {archive_path}")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ArtifactError(f"Output directory must be empty: {output}")

    seen: set[str] = set()
    try:
        with zipfile.ZipFile(archive_path) as archive:
            for info in archive.infolist():
                if info.is_dir():
                    raise ArtifactError(
                        f"Artifact archive contains a directory: {info.filename!r}"
                    )
                name = safe_artifact_name(info.filename)
                if name in seen:
                    raise ArtifactError(f"Duplicate archive entry: {name}")
                unix_type = (info.external_attr >> 16) & 0o170000
                if unix_type == 0o120000:
                    raise ArtifactError(f"Artifact archive contains a symlink: {name}")
                seen.add(name)
                with archive.open(info) as source, (output / name).open("wb") as target:
                    shutil.copyfileobj(source, target)
    except zipfile.BadZipFile as exc:
        raise ArtifactError(f"Invalid artifact ZIP: {exc}") from exc


def verify_artifacts(args: argparse.Namespace) -> None:
    if not GIT_OBJECT_PATTERN.fullmatch(args.tree):
        raise ArtifactError(f"Invalid expected tree id: {args.tree!r}")
    if not str(args.version_code).isdigit():
        raise ArtifactError(f"Invalid expected versionCode: {args.version_code!r}")
    directory = args.directory.resolve()
    if not directory.is_dir():
        raise ArtifactError(f"Artifact directory does not exist: {directory}")
    manifest = load_manifest(directory)
    commit = manifest.get("commit")
    if not isinstance(commit, str) or not GIT_OBJECT_PATTERN.fullmatch(commit):
        raise ArtifactError(f"Manifest commit is invalid: {commit!r}")

    expected_metadata = {
        "schemaVersion": SCHEMA_VERSION,
        "version": args.version,
        "versionCode": str(args.version_code),
        "tree": args.tree,
        "track": "production",
        "status": "draft",
    }
    for key, expected in expected_metadata.items():
        actual = manifest.get(key)
        if str(actual) != str(expected):
            raise ArtifactError(
                f"Manifest {key} mismatch: expected {expected!r}, got {actual!r}"
            )

    entries = manifest.get("artifacts")
    if not isinstance(entries, list):
        raise ArtifactError("Manifest artifacts must be a list")
    by_role: dict[str, dict[str, Any]] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise ArtifactError("Every manifest artifact must be an object")
        role = entry.get("role")
        if role not in EXPECTED_ROLES:
            raise ArtifactError(f"Unexpected artifact role: {role!r}")
        if role in by_role:
            raise ArtifactError(f"Duplicate artifact role: {role}")
        by_role[role] = entry
    if set(by_role) != set(EXPECTED_ROLES):
        missing = sorted(set(EXPECTED_ROLES) - set(by_role))
        raise ArtifactError(f"Missing artifact roles: {', '.join(missing)}")

    verified: list[dict[str, Any]] = []
    for role, expected_pattern in EXPECTED_ROLES.items():
        entry = by_role[role]
        name = safe_artifact_name(str(entry.get("name", "")))
        expected_name = expected_pattern.format(version=args.version)
        if name != expected_name:
            raise ArtifactError(f"{role} must be named {expected_name}, got {name}")
        path = directory / name
        if not path.is_file():
            raise ArtifactError(f"Missing artifact file: {name}")
        actual_hash = sha256(path)
        if actual_hash != entry.get("sha256"):
            raise ArtifactError(f"SHA-256 mismatch for {name}")
        if path.stat().st_size != entry.get("size"):
            raise ArtifactError(f"Size mismatch for {name}")
        verified.append(entry)

    checksum_path = directory / CHECKSUMS_NAME
    try:
        checksum_text = checksum_path.read_text(encoding="utf-8")
    except FileNotFoundError as exc:
        raise ArtifactError(f"Missing {CHECKSUMS_NAME}") from exc
    if checksum_text != expected_checksum_text(verified):
        raise ArtifactError(f"{CHECKSUMS_NAME} does not match the manifest")

    allowed_names = {
        MANIFEST_NAME,
        CHECKSUMS_NAME,
        *(entry["name"] for entry in verified),
    }
    directory_entries = list(directory.iterdir())
    non_files = sorted(path.name for path in directory_entries if not path.is_file())
    if non_files:
        raise ArtifactError(
            "Artifact directory contains non-file entries: " + ", ".join(non_files)
        )
    actual_names = {path.name for path in directory_entries}
    if actual_names != allowed_names:
        unexpected = sorted(actual_names - allowed_names)
        missing = sorted(allowed_names - actual_names)
        details = []
        if unexpected:
            details.append(f"unexpected: {', '.join(unexpected)}")
        if missing:
            details.append(f"missing: {', '.join(missing)}")
        raise ArtifactError("Artifact set mismatch (" + "; ".join(details) + ")")

    print(json.dumps(manifest, sort_keys=True))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    package = subparsers.add_parser("package", help="Create a preflight artifact set")
    package.add_argument("--version", required=True)
    package.add_argument("--version-code", required=True)
    package.add_argument("--commit", required=True)
    package.add_argument("--tree", required=True)
    package.add_argument("--sideload-apk", required=True, type=Path)
    package.add_argument("--google-play-aab", required=True, type=Path)
    package.add_argument("--output", required=True, type=Path)
    package.set_defaults(func=package_artifacts)

    extract = subparsers.add_parser(
        "extract", help="Safely extract a downloaded Actions artifact"
    )
    extract.add_argument("--archive", required=True, type=Path)
    extract.add_argument("--output", required=True, type=Path)
    extract.set_defaults(func=extract_archive)

    verify = subparsers.add_parser("verify", help="Verify a preflight artifact set")
    verify.add_argument("--version", required=True)
    verify.add_argument("--version-code", required=True)
    verify.add_argument("--tree", required=True)
    verify.add_argument("--directory", required=True, type=Path)
    verify.set_defaults(func=verify_artifacts)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        args.func(args)
    except ArtifactError as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    sys.exit(main())
