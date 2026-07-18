#!/usr/bin/env python3
"""Validate marketing-site locale coverage and English-source freshness."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TRANSLATIONS = ROOT / "website" / "src" / "i18n.ts"
STATUS = ROOT / "docs" / "localization-status.json"
LOCALES = {
    "de": "de",
    "es": "es",
    "ja": "ja",
    "pt-BR": "pt-BR",
    "zh-Hans": "zh-CN",
}


def english_source_hash(source: str) -> str:
    translations_start = source.index("export const translations")
    start = source.index("  en: {", translations_start)
    end = source.index("\n  de: {", start)
    return hashlib.sha256(source[start:end].encode("utf-8")).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh", action="store_true", help="Record the current canonical English copy hash")
    args = parser.parse_args()

    source = TRANSLATIONS.read_text(encoding="utf-8")
    status = json.loads(STATUS.read_text(encoding="utf-8"))
    source_hash = english_source_hash(source)
    failures: list[str] = []

    for status_key, route_locale in LOCALES.items():
        entry = status.get("locales", {}).get(status_key)
        if not entry:
            failures.append(f"missing localization status entry: {status_key}")
            continue

        if f"  {route_locale!r}: {{" not in source and f"  {route_locale}: {{" not in source:
            failures.append(f"missing marketing translation dictionary: {route_locale}")

        if args.refresh:
            entry["website_source_sha256"] = source_hash
            entry.setdefault("surfaces", {})["website"] = "complete"
        else:
            if entry.get("website_source_sha256") != source_hash:
                failures.append(
                    f"{status_key}: marketing translation is stale; review it and run "
                    "scripts/check-website-locales.py --refresh"
                )
            if entry.get("surfaces", {}).get("website") != "complete":
                failures.append(f"{status_key}: surfaces.website must be complete")

    english = status.get("locales", {}).get("en")
    if english:
        english.setdefault("surfaces", {})["website"] = "canonical"

    if args.refresh:
        STATUS.write_text(json.dumps(status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if failures:
        print("Marketing locale validation failed:")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print(f"Marketing locale validation passed ({len(LOCALES)} translations; source {source_hash[:12]})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
