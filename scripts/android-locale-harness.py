#!/usr/bin/env python3
"""Prepare and install deterministic Android locale drafts for AI translation."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_SRC = ROOT / "app" / "src"
STATUS = ROOT / "docs" / "localization-status.json"
PRINTF = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([A-Za-z%])")
SOURCE_SETS = ("main", "sideload")


def qualifier(tag: str) -> str:
    parts = tag.split("-")
    return f"values-{tag}" if len(parts) == 1 else "values-b+" + "+".join(parts)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def placeholders(value: str) -> list[tuple[int, str]]:
    result: list[tuple[int, str]] = []
    implicit = 1
    for match in PRINTF.finditer(value):
        if match.group(2) == "%":
            continue
        index = int(match.group(1)) if match.group(1) else implicit
        if match.group(1) is None:
            implicit += 1
        result.append((index, match.group(2).lower()))
    return sorted(result)


def catalog(path: Path) -> dict[tuple[str, str], list[list[tuple[int, str]]]]:
    root = ET.parse(path).getroot()
    result: dict[tuple[str, str], list[list[tuple[int, str]]]] = {}
    for node in root:
        name = node.attrib.get("name")
        if not name or node.tag not in {"string", "plurals", "string-array"}:
            continue
        if node.attrib.get("translatable", "true").lower() == "false":
            continue
        variants = node.findall("item") if node.tag != "string" else [node]
        result[(node.tag, name)] = [placeholders("".join(item.itertext())) for item in variants]
    return result


def validate_pair(source: Path, translated: Path) -> None:
    canonical = catalog(source)
    candidate = catalog(translated)
    if canonical.keys() != candidate.keys():
        missing = sorted(canonical.keys() - candidate.keys())
        extra = sorted(candidate.keys() - canonical.keys())
        raise ValueError(f"catalog keys differ; missing={missing[:5]} extra={extra[:5]}")
    for key, expected in canonical.items():
        actual = candidate[key]
        if expected != actual:
            raise ValueError(f"{key}: placeholder variants {actual} != {expected}")


def prepare(args: argparse.Namespace) -> None:
    target = ROOT / "build" / "i18n" / args.tag
    if target.exists() and not args.force:
        raise FileExistsError(f"draft already exists: {target}")
    target.mkdir(parents=True, exist_ok=True)
    sources: dict[str, str] = {}
    for source_set in SOURCE_SETS:
        source = APP_SRC / source_set / "res" / "values" / "strings.xml"
        destination = target / source_set / "strings.xml"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        sources[source_set] = digest(source)
    manifest = {
        "schema_version": 1,
        "tag": args.tag,
        "native_name": args.native_name,
        "verification": "ai-translated",
        "canonical_sha256": sources,
    }
    (target / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(target)


def install(args: argparse.Namespace) -> None:
    draft = ROOT / "build" / "i18n" / args.tag
    manifest = json.loads((draft / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("tag") != args.tag:
        raise ValueError("draft manifest tag mismatch")
    for source_set in SOURCE_SETS:
        source = APP_SRC / source_set / "res" / "values" / "strings.xml"
        if manifest["canonical_sha256"].get(source_set) != digest(source):
            raise ValueError(f"English {source_set} catalog changed; regenerate the draft")
        validate_pair(source, draft / source_set / "strings.xml")
    destination_name = qualifier(args.tag)
    for source_set in SOURCE_SETS:
        destination = APP_SRC / source_set / "res" / destination_name / "strings.xml"
        if destination.exists() and not args.force:
            raise FileExistsError(f"locale already installed: {destination}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(draft / source_set / "strings.xml", destination)
    status = json.loads(STATUS.read_text(encoding="utf-8"))
    status["locales"][args.tag] = {
        "native_name": manifest["native_name"],
        "verification": "ai-translated",
        "review_refs": [],
        "surfaces": {
            "android": "complete",
            "readme": "english-fallback",
            "user_docs": "english-fallback",
        },
    }
    status["locales"] = dict(sorted(status["locales"].items()))
    STATUS.write_text(json.dumps(status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"installed {args.tag} as {destination_name}")


def main() -> int:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("prepare", help="create a non-shipping draft from canonical English")
    create.add_argument("tag")
    create.add_argument("native_name")
    create.add_argument("--force", action="store_true")
    create.set_defaults(handler=prepare)
    add = commands.add_parser("install", help="validate and install a completed draft")
    add.add_argument("tag")
    add.add_argument("--force", action="store_true")
    add.set_defaults(handler=install)
    args = parser.parse_args()
    try:
        args.handler(args)
    except (FileNotFoundError, FileExistsError, ValueError, ET.ParseError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
