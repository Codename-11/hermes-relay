#!/usr/bin/env python3
"""Validate localized VitePress entry pages and their canonical source hashes."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[1]
DOCS_ROOT = ROOT / "user-docs"
STATUS_PATH = ROOT / "docs" / "localization-status.json"

CANONICAL_PAGES = {
    "index.md": "/",
    "guide/quick-start.md": "/guide/quick-start",
    "guide/getting-started.md": "/guide/getting-started",
    "guide/release-tracks.md": "/guide/release-tracks",
    "guide/troubleshooting.md": "/guide/troubleshooting",
}

# Status registry key -> VitePress locale directory.
LOCALES = {
    "de": "de",
    "es": "es",
    "ja": "ja",
    "pt-BR": "pt-BR",
    "zh-Hans": "zh-CN",
}

LINK_RE = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
FENCE_RE = re.compile(r"```[^\n]*\n(.*?)\n```", re.DOTALL)


def normalized_text(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def digest(path: Path) -> str:
    return hashlib.sha256(normalized_text(path).encode("utf-8")).hexdigest()


def resolve_doc_link(page: Path, raw_target: str) -> Path | None:
    target = unquote(raw_target.strip().split(maxsplit=1)[0])
    target = target.split("#", 1)[0].split("?", 1)[0]
    if not target or target.startswith(("#", "http://", "https://", "mailto:", "tel:")):
        return None

    if target.startswith("/docs/"):
        target = target[len("/docs/") :]
        candidate = DOCS_ROOT / target
    elif target.startswith("/"):
        candidate = DOCS_ROOT / target.lstrip("/")
    else:
        candidate = page.parent / target

    if candidate.suffix in {".png", ".jpg", ".jpeg", ".svg", ".webp", ".json", ".txt"}:
        return None
    if candidate.suffix == ".html":
        candidate = candidate.with_suffix("")
    if candidate.suffix == ".md":
        return candidate.resolve()

    direct = candidate.with_suffix(".md")
    if direct.exists():
        return direct.resolve()
    return (candidate / "index.md").resolve()


def validate_page(locale_dir: str, relative: str, canonical_route: str) -> list[str]:
    errors: list[str] = []
    source = DOCS_ROOT / relative
    page = DOCS_ROOT / locale_dir / relative
    label = f"{locale_dir}/{relative}"

    if not page.exists():
        return [f"{label}: missing localized page"]

    text = normalized_text(page)
    source_text = normalized_text(source)
    if text == source_text:
        errors.append(f"{label}: localized page is identical to English")
    if "translation_status: ai-translated" not in text[:500]:
        errors.append(f"{label}: missing translation_status: ai-translated frontmatter")
    if f"canonical_source: {canonical_route}" not in text[:500]:
        errors.append(f"{label}: canonical_source must be {canonical_route}")
    if text.count("```") % 2:
        errors.append(f"{label}: unbalanced fenced code block")

    # Commands and configuration lines inside localized code blocks must remain
    # byte-for-byte present in the canonical English source. Localized prose and
    # headings are free to differ; executable material is not.
    for block in FENCE_RE.findall(text):
        for line in block.splitlines():
            command = line.strip()
            if command and not command.startswith("#") and command not in source_text:
                errors.append(f"{label}: translated or invented code line: {command}")

    for raw_target in LINK_RE.findall(text):
        resolved = resolve_doc_link(page, raw_target)
        if resolved is not None and not resolved.exists():
            errors.append(f"{label}: broken internal link {raw_target} -> {resolved.relative_to(ROOT)}")

    return errors


def refresh(status: dict) -> None:
    source_hashes = {relative: digest(DOCS_ROOT / relative) for relative in CANONICAL_PAGES}
    for status_key, locale_dir in LOCALES.items():
        entry = status["locales"][status_key]
        entry["docs_locale"] = locale_dir
        entry["docs_source_sha256"] = source_hashes
        entry["surfaces"]["user_docs"] = "core-pages"
    STATUS_PATH.write_text(json.dumps(status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="Record current English page hashes after translations have been refreshed.",
    )
    args = parser.parse_args()

    status = json.loads(STATUS_PATH.read_text(encoding="utf-8"))
    if args.refresh:
        refresh(status)
        status = json.loads(STATUS_PATH.read_text(encoding="utf-8"))

    errors: list[str] = []
    expected_hashes = {relative: digest(DOCS_ROOT / relative) for relative in CANONICAL_PAGES}

    for status_key, locale_dir in LOCALES.items():
        entry = status.get("locales", {}).get(status_key)
        if entry is None:
            errors.append(f"{status_key}: missing localization status entry")
            continue
        if entry.get("docs_locale") != locale_dir:
            errors.append(f"{status_key}: docs_locale must be {locale_dir}")
        if entry.get("surfaces", {}).get("user_docs") != "core-pages":
            errors.append(f"{status_key}: user_docs surface must be core-pages")
        if entry.get("docs_source_sha256") != expected_hashes:
            errors.append(f"{status_key}: localized docs source hashes are stale; refresh translations, then run --refresh")

        for relative, canonical_route in CANONICAL_PAGES.items():
            errors.extend(validate_page(locale_dir, relative, canonical_route))

    if errors:
        print("Localized docs validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print(
        f"Localized docs validation passed "
        f"({len(LOCALES)} locales × {len(CANONICAL_PAGES)} core pages)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
