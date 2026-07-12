#!/usr/bin/env python3
"""Validate Android translation catalogs against each source set's English resources."""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
APP_SRC = REPO_ROOT / "app" / "src"
STATUS_PATH = REPO_ROOT / "docs" / "localization-status.json"
LOCALE_DIR = re.compile(r"^values-(?:[a-z]{2,3}(?:-r[A-Z]{2})?|b\+[A-Za-z0-9+]+)$")
PRINTF = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([A-Za-z%])")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
VERIFICATION_STATES = {"canonical", "ai-translated", "community-reviewed", "verified"}


@dataclass(frozen=True)
class Entry:
    kind: str
    name: str
    values: dict[str, str]
    formatted: bool


def fail(message: str, errors: list[str]) -> None:
    errors.append(message)


def text(node: ET.Element) -> str:
    return "".join(node.itertext())


def load_catalog(path: Path, errors: list[str]) -> dict[tuple[str, str], Entry]:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        fail(f"{path.relative_to(REPO_ROOT)}: cannot parse XML: {exc}", errors)
        return {}

    catalog: dict[tuple[str, str], Entry] = {}
    seen: Counter[tuple[str, str]] = Counter()
    for node in root:
        name = node.attrib.get("name")
        if not name or node.tag not in {"string", "plurals", "string-array"}:
            continue
        if node.attrib.get("translatable", "true").lower() == "false":
            continue
        key = (node.tag, name)
        seen[key] += 1
        formatted = node.attrib.get("formatted", "true").lower() != "false"
        if node.tag == "string":
            values = {"value": text(node)}
        else:
            values = {
                item.attrib.get("quantity", str(index)): text(item)
                for index, item in enumerate(node.findall("item"))
            }
        catalog[key] = Entry(node.tag, name, values, formatted)

    for key, count in seen.items():
        if count > 1:
            fail(
                f"{path.relative_to(REPO_ROOT)}: duplicate <{key[0]}> name={key[1]!r}",
                errors,
            )
    return catalog


def placeholders(value: str) -> list[tuple[int, str]]:
    result: list[tuple[int, str]] = []
    implicit_index = 1
    for match in PRINTF.finditer(value):
        conversion = match.group(2)
        if conversion == "%":
            continue
        index = int(match.group(1)) if match.group(1) else implicit_index
        if match.group(1) is None:
            implicit_index += 1
        result.append((index, conversion.lower()))
    return sorted(result)


def compare_entry(
    source_label: str,
    locale_label: str,
    canonical: Entry,
    translated: Entry,
    errors: list[str],
) -> None:
    if not canonical.formatted:
        return
    for variant, translated_value in translated.values.items():
        canonical_value = canonical.values.get(variant)
        if canonical_value is None and canonical.kind == "plurals":
            canonical_value = canonical.values.get("other")
        if canonical_value is None:
            fail(
                f"{locale_label}: {canonical.kind}/{canonical.name} has unsupported variant {variant!r}",
                errors,
            )
            continue
        expected = placeholders(canonical_value)
        actual = placeholders(translated_value)
        if expected != actual:
            fail(
                f"{locale_label}: {canonical.kind}/{canonical.name}[{variant}] placeholders "
                f"{actual} do not match {source_label} {expected}",
                errors,
            )


def validate_source_set(source_set: Path, errors: list[str]) -> int:
    resource_root = source_set / "res"
    canonical_path = resource_root / "values" / "strings.xml"
    if not canonical_path.is_file():
        return 0

    canonical = load_catalog(canonical_path, errors)
    locale_paths = sorted(
        path / "strings.xml"
        for path in resource_root.iterdir()
        if path.is_dir() and LOCALE_DIR.match(path.name) and (path / "strings.xml").is_file()
    )
    for locale_path in locale_paths:
        translated = load_catalog(locale_path, errors)
        missing = sorted(set(canonical) - set(translated))
        extra = sorted(set(translated) - set(canonical))
        for kind, name in missing:
            fail(f"{locale_path.parent.name}: missing {kind}/{name}", errors)
        for kind, name in extra:
            fail(f"{locale_path.parent.name}: extra {kind}/{name}", errors)
        for key in sorted(set(canonical) & set(translated)):
            compare_entry(
                f"{source_set.name}/values",
                f"{source_set.name}/{locale_path.parent.name}",
                canonical[key],
                translated[key],
                errors,
            )
    return len(locale_paths)


def qualifier_to_tag(qualifier: str) -> str:
    value = qualifier.removeprefix("values-")
    if value.startswith("b+"):
        return value.removeprefix("b+").replace("+", "-")
    parts = value.split("-")
    return "-".join(part.removeprefix("r") for part in parts)


def validate_locale_config(errors: list[str]) -> None:
    config_path = APP_SRC / "main" / "res" / "xml" / "locales_config.xml"
    try:
        config = ET.parse(config_path).getroot()
    except (ET.ParseError, OSError) as exc:
        fail(f"{config_path.relative_to(REPO_ROOT)}: cannot parse XML: {exc}", errors)
        return
    configured = {
        node.attrib[f"{ANDROID_NS}name"]
        for node in config.findall("locale")
        if f"{ANDROID_NS}name" in node.attrib
    }
    discovered = {
        qualifier_to_tag(path.name)
        for path in (APP_SRC / "main" / "res").iterdir()
        if path.is_dir() and LOCALE_DIR.match(path.name) and (path / "strings.xml").is_file()
    }
    expected = {"en", *discovered}
    if configured != expected:
        fail(
            "locale_config.xml entries "
            f"{sorted(configured)} do not match Android catalogs {sorted(expected)}",
            errors,
        )


def validate_status_registry(errors: list[str]) -> None:
    try:
        data = json.loads(STATUS_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"{STATUS_PATH.relative_to(REPO_ROOT)}: cannot read status registry: {exc}", errors)
        return

    if data.get("schema_version") != 1:
        fail("localization status schema_version must be 1", errors)
    if data.get("canonical_locale") != "en":
        fail("localization status canonical_locale must be 'en'", errors)

    locales = data.get("locales")
    if not isinstance(locales, dict):
        fail("localization status locales must be an object", errors)
        return

    discovered = {
        qualifier_to_tag(path.name)
        for path in (APP_SRC / "main" / "res").iterdir()
        if path.is_dir() and LOCALE_DIR.match(path.name) and (path / "strings.xml").is_file()
    }
    expected = {"en", *discovered}
    if set(locales) != expected:
        fail(
            f"localization status locales {sorted(locales)} do not match shipped locales {sorted(expected)}",
            errors,
        )

    for tag, entry in locales.items():
        if not isinstance(entry, dict):
            fail(f"localization status {tag!r} must be an object", errors)
            continue
        verification = entry.get("verification")
        if verification not in VERIFICATION_STATES:
            fail(f"localization status {tag!r} has invalid verification {verification!r}", errors)
        if tag == "en" and verification != "canonical":
            fail("English localization status must be canonical", errors)
        if tag != "en" and verification == "canonical":
            fail(f"non-English locale {tag!r} cannot be canonical", errors)
        if not isinstance(entry.get("native_name"), str) or not entry["native_name"].strip():
            fail(f"localization status {tag!r} requires native_name", errors)
        review_refs = entry.get("review_refs")
        if not isinstance(review_refs, list) or any(not isinstance(ref, str) for ref in review_refs):
            fail(f"localization status {tag!r} review_refs must be a string array", errors)
        elif verification in {"community-reviewed", "verified"} and not review_refs:
            fail(f"localization status {tag!r} requires review_refs for {verification}", errors)
        elif any(not ref.startswith("https://github.com/") for ref in review_refs):
            fail(f"localization status {tag!r} review_refs must be GitHub URLs", errors)
        surfaces = entry.get("surfaces")
        if not isinstance(surfaces, dict) or surfaces.get("android") not in {"canonical", "complete"}:
            fail(f"localization status {tag!r} must track the Android surface", errors)


def main() -> int:
    errors: list[str] = []
    locale_count = sum(
        validate_source_set(source_set, errors)
        for source_set in sorted(APP_SRC.iterdir())
        if source_set.is_dir()
    )
    validate_locale_config(errors)
    validate_status_registry(errors)
    if locale_count == 0:
        errors.append("No Android locale catalogs were discovered")
    if errors:
        print("Android locale validation failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Android locale validation passed ({locale_count} catalog(s))")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
