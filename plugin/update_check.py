"""Update discovery for the relay plugin.

Hermes already ships the update *mechanism* — ``hermes plugins update
hermes-relay`` for native plugin installs, ``hermes-relay-update`` for the
full-relay installer. What was missing is *discovery*: telling the operator a
newer release exists. This module compares the installed version against the
latest ``server-v*`` GitHub release and picks the right update command for how
this host was installed.

Pure helpers (version parsing/compare, tag selection, command detection) carry
no network or framework dependency so they unit-test in isolation; ``check()``
adds a synchronous GitHub fetch for the CLI. The dashboard reuses the same pure
helpers with its own async ``httpx`` fetch.
"""

from __future__ import annotations

import json
import os
import re
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

GITHUB_RELEASES_URL = "https://api.github.com/repos/Codename-11/hermes-relay/releases"
SERVER_TAG_PREFIX = "server-v"
LEGACY_PLUGIN_TAG_PREFIX = "plugin-v"

# Native-plugin update command vs. full-relay installer shim. Detected per host.
_NATIVE_UPDATE_CMD = "hermes plugins update hermes-relay"
_FULL_RELAY_UPDATE_CMD = "hermes-relay-update"


def current_version() -> str:
    """The installed relay/plugin version (``plugin.relay.__version__``)."""
    try:
        from .relay import __version__

        return str(__version__)
    except Exception:
        return "0.0.0"


_SEMVER_RE = re.compile(r"(\d+)\.(\d+)\.(\d+)")


def parse_semver(value: str) -> Optional[Tuple[int, int, int]]:
    """Extract ``(major, minor, patch)`` from a version-ish string.

    Tolerates release-tag prefixes and any pre-release suffix
    (``1.2.0-rc1`` → ``(1, 2, 0)``). Returns ``None`` when no ``X.Y.Z`` core is
    present.
    """
    if not value:
        return None
    m = _SEMVER_RE.search(value)
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)))


def compare_versions(current: str, latest: str) -> int:
    """Return ``-1`` if current < latest, ``0`` if equal/unknown, ``1`` if newer.

    Unparseable inputs compare as equal (``0``) so a bad tag never spuriously
    nags the user to "update".
    """
    cur = parse_semver(current)
    lat = parse_semver(latest)
    if cur is None or lat is None:
        return 0
    if cur < lat:
        return -1
    if cur > lat:
        return 1
    return 0


def pick_latest_plugin_tag(releases: Any) -> Optional[str]:
    """Pick the highest Server version from a GitHub releases payload.

    ``releases`` is the decoded JSON list from the GitHub releases API. Drafts
    are ignored; pre-releases are considered (the plugin ships ``-alpha``/``-rc``
    tags). Returns the bare version (``"1.3.0"``), or ``None`` when no plugin
    release is present. Canonical ``server-v*`` releases take precedence over
    historical ``plugin-v*`` releases.
    """
    if not isinstance(releases, list):
        return None
    canonical = [
        rel for rel in releases
        if isinstance(rel, dict)
        and not rel.get("draft")
        and isinstance(rel.get("tag_name"), str)
        and rel["tag_name"].startswith(SERVER_TAG_PREFIX)
    ]
    candidates = canonical or releases
    accepted_prefix = SERVER_TAG_PREFIX if canonical else LEGACY_PLUGIN_TAG_PREFIX
    best: Optional[Tuple[int, int, int]] = None
    best_name: Optional[str] = None
    for rel in candidates:
        if not isinstance(rel, dict) or rel.get("draft"):
            continue
        tag = rel.get("tag_name") or ""
        if not isinstance(tag, str) or not tag.startswith(accepted_prefix):
            continue
        ver = parse_semver(tag)
        if ver is None:
            continue
        if best is None or ver > best:
            best = ver
            best_name = ".".join(str(p) for p in ver)
    return best_name


def detect_update_command(home: Optional[str] = None) -> str:
    """Pick the update command for how this host installed the relay.

    Presence of the ``hermes-relay-update`` shim (dropped only by the
    full-relay ``install.sh``) means the installer path; otherwise the host
    used the native ``hermes plugins install`` path. Honors ``$HOME`` so the
    check works under test isolation.
    """
    home_dir = Path(home or os.environ.get("HOME") or Path.home())
    shim = home_dir / ".local" / "bin" / "hermes-relay-update"
    return _FULL_RELAY_UPDATE_CMD if shim.exists() else _NATIVE_UPDATE_CMD


def build_result(current: str, latest: Optional[str], *, home: Optional[str] = None) -> Dict[str, Any]:
    """Assemble the structured update-check result from resolved versions."""
    update_available = bool(latest) and compare_versions(current, latest) < 0
    return {
        "current": current,
        "latest": latest,
        "update_available": update_available,
        "update_command": detect_update_command(home) if update_available else None,
    }


def _fetch_releases_sync(timeout: float) -> List[Any]:
    """Fetch + decode the GitHub releases list synchronously (CLI path)."""
    req = urllib.request.Request(
        GITHUB_RELEASES_URL,
        headers={"Accept": "application/vnd.github+json", "User-Agent": "hermes-relay-update-check"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310 - fixed https URL
        return json.loads(resp.read().decode("utf-8"))


def check(timeout: float = 4.0, home: Optional[str] = None) -> Dict[str, Any]:
    """Synchronous update check for the CLI.

    Network/parse failures are returned as ``{"error": ...}`` alongside the
    known current version — never raised — so ``hermes relay update-check`` is
    safe to run offline.
    """
    cur = current_version()
    try:
        releases = _fetch_releases_sync(timeout)
        latest = pick_latest_plugin_tag(releases)
    except Exception as exc:  # network down, rate-limited, bad JSON, etc.
        return {"current": cur, "latest": None, "update_available": False,
                "update_command": None, "error": str(exc)}
    return build_result(cur, latest, home=home)
