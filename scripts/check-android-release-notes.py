#!/usr/bin/env python3
"""Validate and synchronize Android release-note surfaces.

The newest ``changelog.json`` entry is the complete user-visible source for the
in-app What's New experience. ``--write`` refreshes the legacy text fallback,
Google Play notes, and the matching operator copy in
``docs/play-store-listing.md``. The technical CHANGELOG and GitHub release body
remain separate records, but their version headings are checked so release
prep cannot silently drift.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import tomllib
from typing import Any


REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
CHANGELOG_JSON = pathlib.Path("app/src/main/assets/changelog.json")
WHATS_NEW = pathlib.Path("app/src/main/assets/whats_new.txt")
PLAY_NOTES = pathlib.Path("app/src/googlePlay/play/release-notes/en-US/default.txt")
PLAY_LISTING = pathlib.Path("docs/play-store-listing.md")
PLAY_SECTION_RE = re.compile(
    r"(## Release Notes\s+Paste into Play Console → \*\*What's new\*\* "
    r"\(≤500 characters\):\s+```\s*\n)(.*?)(\n```\s*\n## Category)",
    re.DOTALL,
)
CHANGE_KINDS = ("added", "improved", "fixed")
CHANGE_KIND_LABELS = {
    "added": "Added",
    "improved": "Improved",
    "fixed": "Fixed",
}


def _read_text(relative: pathlib.Path) -> str:
    return (REPO_ROOT / relative).read_text(encoding="utf-8")


def _android_version() -> str:
    versions = tomllib.loads(_read_text(pathlib.Path("gradle/libs.versions.toml"))).get(
        "versions", {}
    )
    return str(versions.get("appVersionName", ""))


def _latest_entry() -> dict[str, Any]:
    data = json.loads(_read_text(CHANGELOG_JSON))
    if not isinstance(data, dict) or data.get("schema") != 3:
        raise ValueError("changelog.json must use schema 3")
    versions = data.get("versions")
    if not isinstance(versions, list) or not versions or not isinstance(versions[0], dict):
        raise ValueError("changelog.json must contain at least one version")
    return versions[0]


def validate_curated_entry(entry: dict[str, Any], expected_version: str) -> list[str]:
    errors: list[str] = []
    version = entry.get("version")
    if version != expected_version:
        errors.append(
            f"latest changelog version is {version!r}, expected Android {expected_version!r}"
        )

    for field in ("title", "date", "summary"):
        if not isinstance(entry.get(field), str) or not entry[field].strip():
            errors.append(f"latest changelog entry requires non-empty {field}")

    changes = entry.get("changes")
    if not isinstance(changes, list) or not changes:
        errors.append("latest changelog entry requires a non-empty changes array")
        changes = []

    seen_ids: set[str] = set()
    highlight_count = 0
    for index, change in enumerate(changes):
        prefix = f"latest changelog change {index + 1}"
        if not isinstance(change, dict):
            errors.append(f"{prefix} must be an object")
            continue
        change_id = change.get("id")
        if not isinstance(change_id, str) or not change_id.strip():
            errors.append(f"{prefix} requires a non-empty id")
        elif change_id in seen_ids:
            errors.append(f"latest changelog change id {change_id!r} is duplicated")
        else:
            seen_ids.add(change_id)
        if change.get("kind") not in CHANGE_KINDS:
            errors.append(f"{prefix} kind must be one of {', '.join(CHANGE_KINDS)}")
        for field in ("title", "summary"):
            if not isinstance(change.get(field), str) or not change[field].strip():
                errors.append(f"{prefix} requires non-empty {field}")
        is_highlight = change.get("highlight", False)
        if not isinstance(is_highlight, bool):
            errors.append(f"{prefix} highlight must be a boolean")
        elif is_highlight:
            highlight_count += 1
    if not 1 <= highlight_count <= 4:
        errors.append("latest changelog must select 1-4 highlighted changes")

    compatibility = entry.get("compatibility", [])
    if not isinstance(compatibility, list):
        errors.append("latest changelog compatibility must be an array")
    elif any(not isinstance(item, str) or not item.strip() for item in compatibility):
        errors.append("latest changelog compatibility items must be non-empty strings")

    for legacy_field in ("highlight", "improvements", "toastDigest"):
        if legacy_field in entry:
            errors.append(f"latest changelog must derive presentation instead of defining {legacy_field}")

    play_notes = entry.get("playNotes")
    if not isinstance(play_notes, str) or not play_notes.strip():
        errors.append("latest changelog entry requires non-empty playNotes")
    elif len(render_play_notes(entry)) > 500:
        errors.append("rendered Google Play notes exceed 500 characters")
    return errors


def render_whats_new(entry: dict[str, Any]) -> str:
    lines = [
        f"v{entry['version']} - {entry['title']}",
        "",
        "Summary",
        f"* {entry['summary']}",
    ]
    changes = entry["changes"]
    highlights = [change for change in changes if change.get("highlight", False)]
    if highlights:
        lines.extend(("", "Highlights", *(_render_change(change) for change in highlights)))
    remaining = [change for change in changes if not change.get("highlight", False)]
    for kind in CHANGE_KINDS:
        items = [change for change in remaining if change["kind"] == kind]
        if items:
            lines.extend(("", CHANGE_KIND_LABELS[kind], *(_render_change(change) for change in items)))
    compatibility = entry.get("compatibility", [])
    if compatibility:
        lines.extend(("", "Compatibility", *(f"* {item}" for item in compatibility)))
    return "\n".join(lines) + "\n"


def _render_change(change: dict[str, Any]) -> str:
    return f"* {change['title']} — {change['summary']}"


def render_play_notes(entry: dict[str, Any]) -> str:
    return f"v{entry['version']} - {entry['title']}\n\n{entry['playNotes'].strip()}\n"


def _replace_play_listing(current: str, rendered: str) -> str:
    match = PLAY_SECTION_RE.search(current)
    if not match:
        raise ValueError("docs/play-store-listing.md has no recognized Release Notes block")
    return current[: match.start()] + match.group(1) + rendered.rstrip() + match.group(3) + current[match.end() :]


def _check_exact(relative: pathlib.Path, expected: str, errors: list[str]) -> None:
    actual = _read_text(relative).replace("\r\n", "\n")
    if actual != expected:
        errors.append(f"{relative.as_posix()} is stale; run this script with --write")


def _validate_record_versions(version: str, errors: list[str]) -> None:
    changelog = _read_text(pathlib.Path("CHANGELOG.md"))
    if f"## [Android {version}]" not in changelog:
        errors.append(f"CHANGELOG.md has no [Android {version}] release heading")
    release_notes = _read_text(pathlib.Path("RELEASE_NOTES.md"))
    if not release_notes.startswith(f"# Hermes-Relay Android v{version}\n"):
        errors.append(f"RELEASE_NOTES.md does not describe Android v{version}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--write",
        action="store_true",
        help="Refresh derived in-app fallback and Play release-note files",
    )
    args = parser.parse_args()

    try:
        version = _android_version()
        entry = _latest_entry()
        errors = validate_curated_entry(entry, version)
        if errors:
            raise ValueError("\n".join(errors))
        whats_new = render_whats_new(entry)
        play_notes = render_play_notes(entry)
        listing = _replace_play_listing(_read_text(PLAY_LISTING), play_notes)
    except (OSError, ValueError, json.JSONDecodeError, tomllib.TOMLDecodeError) as exc:
        print(f"Android release-note check failed: {exc}", file=sys.stderr)
        return 1

    if args.write:
        (REPO_ROOT / WHATS_NEW).write_text(whats_new, encoding="utf-8", newline="\n")
        (REPO_ROOT / PLAY_NOTES).write_text(play_notes, encoding="utf-8", newline="\n")
        (REPO_ROOT / PLAY_LISTING).write_text(listing, encoding="utf-8", newline="\n")
        print("Android release-note derivatives refreshed")
        return 0

    errors = []
    _check_exact(WHATS_NEW, whats_new, errors)
    _check_exact(PLAY_NOTES, play_notes, errors)
    if _read_text(PLAY_LISTING).replace("\r\n", "\n") != listing:
        errors.append("docs/play-store-listing.md release notes are stale; run this script with --write")
    _validate_record_versions(version, errors)
    if errors:
        print("Android release-note validation failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Android release notes are aligned for v{version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
