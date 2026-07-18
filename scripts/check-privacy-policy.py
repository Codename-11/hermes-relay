#!/usr/bin/env python3
"""Validate the canonical and legacy Hermes-Relay privacy policy surfaces."""

from __future__ import annotations

import argparse
import sys
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CANONICAL_URL = "https://hermes-relay.dev/privacy.html"
LEGACY_URL = "https://codename-11.github.io/hermes-relay/privacy.html"
REQUIRED_MARKERS = (
    "<h1>Privacy Policy</h1>",
    "Google Play build",
    "Data storage",
    "Network connections",
    "Data export and deletion",
    "Children's privacy",
    "Hermes-Relay issue tracker",
)


def validate_content(label: str, content: str) -> None:
    missing = [marker for marker in REQUIRED_MARKERS if marker not in content]
    if missing:
        raise ValueError(f"{label} is missing required markers: {', '.join(missing)}")


def validate_repository() -> None:
    policy = (ROOT / "website/public/privacy.html").read_text(encoding="utf-8")
    validate_content("website/public/privacy.html", policy)
    if f'<link rel="canonical" href="{CANONICAL_URL}"' not in policy:
        raise ValueError("canonical privacy URL is missing from website/public/privacy.html")

    about = (ROOT / "app/src/main/kotlin/com/hermesandroid/relay/ui/screens/AboutScreen.kt").read_text(
        encoding="utf-8"
    )
    if CANONICAL_URL not in about:
        raise ValueError("Android About screen does not use the canonical privacy URL")

    workflow = (ROOT / ".github/workflows/legacy-docs-redirect.yml").read_text(encoding="utf-8")
    for marker in ("website/public/privacy.html", "privacy.html", "privacy/index.html"):
        if marker not in workflow:
            raise ValueError(f"legacy Pages workflow is missing {marker}")


def fetch(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "Hermes-Relay-release-check/1"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status != 200:
                raise ValueError(f"{url} returned HTTP {response.status}")
            return response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        raise ValueError(f"{url} returned HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise ValueError(f"{url} could not be loaded: {error.reason}") from error


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--live", action="store_true", help="also validate deployed policy URLs")
    args = parser.parse_args()

    try:
        validate_repository()
        if args.live:
            for url in (CANONICAL_URL, LEGACY_URL):
                validate_content(url, fetch(url))
    except ValueError as error:
        print(f"privacy policy validation failed: {error}", file=sys.stderr)
        return 1

    suffix = " and live URLs" if args.live else ""
    print(f"privacy policy repository contract{suffix} validated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
