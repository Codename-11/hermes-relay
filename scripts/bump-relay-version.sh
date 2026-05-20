#!/usr/bin/env bash
# scripts/bump-relay-version.sh - bump Relay server/Python package version.
#
# Updates the Relay version sources:
#   pyproject.toml::project.version
#   plugin/relay/__init__.py::__version__
#   plugin/plugin.yaml::version
#   plugin/dashboard/manifest.json::version
#   plugin/dashboard/package.json::version
#   plugin/dashboard/package-lock.json::version
#
# Android app releases are intentionally split; use
# scripts/bump-android-version.sh for gradle/libs.versions.toml.

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "usage: $0 <new-version>" >&2
    echo "  e.g. $0 0.6.2" >&2
    echo "  e.g. $0 0.7.0-rc.1" >&2
    exit 2
fi

NEW_VERSION="$1"

if ! [[ "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]]; then
    echo "  [x] '$NEW_VERSION' is not valid SemVer (MAJOR.MINOR.PATCH[-prerelease])" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

PYPROJECT="pyproject.toml"
INITPY="plugin/relay/__init__.py"
PLUGIN_YAML="plugin/plugin.yaml"
DASH_MANIFEST="plugin/dashboard/manifest.json"
DASH_PACKAGE="plugin/dashboard/package.json"
DASH_LOCK="plugin/dashboard/package-lock.json"
VERSION_CHECK="scripts/check-relay-version-sync.py"

for f in "$PYPROJECT" "$INITPY" "$PLUGIN_YAML" "$DASH_MANIFEST" "$DASH_PACKAGE" "$DASH_LOCK" "$VERSION_CHECK"; do
    [ -f "$f" ] || { echo "  [x] missing $f - are you in the repo root?" >&2; exit 1; }
done

OLD_PY="$(grep -E '^version *= *"' "$PYPROJECT" | head -1 | sed -E 's/.*"(.*)".*/\1/')"
OLD_INIT="$(grep -E '^__version__ *= *"' "$INITPY" | sed -E 's/.*"(.*)".*/\1/')"

if [ -z "$OLD_PY" ] || [ -z "$OLD_INIT" ]; then
    echo "  [x] could not parse current Relay version sources" >&2
    exit 1
fi

python "$VERSION_CHECK"

echo ""
echo "  Bumping Relay version:"
echo "    pyproject.toml:        $OLD_PY -> $NEW_VERSION"
echo "    plugin/relay version:  $OLD_INIT -> $NEW_VERSION"
echo "    plugin + dashboard:    $OLD_PY -> $NEW_VERSION"
echo ""

python - "$NEW_VERSION" <<'PY'
from __future__ import annotations

import json
import pathlib
import re
import sys

new_version = sys.argv[1]
root = pathlib.Path(".")


def replace_regex(path: str, pattern: str, replacement: str) -> None:
    file_path = root / path
    text = file_path.read_text(encoding="utf-8")
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"failed to update version in {path}")
    file_path.write_text(updated, encoding="utf-8")


def update_json_version(path: str, *, package_lock: bool = False) -> None:
    file_path = root / path
    data = json.loads(file_path.read_text(encoding="utf-8"))
    data["version"] = new_version
    if package_lock:
        data["packages"][""]["version"] = new_version
    file_path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


replace_regex("pyproject.toml", r'^version *= *"[^"]*"', f'version = "{new_version}"')
replace_regex("plugin/relay/__init__.py", r'^__version__ *= *"[^"]*"', f'__version__ = "{new_version}"')
replace_regex("plugin/plugin.yaml", r"^version:\s*[^\s#]+", f"version: {new_version}")
update_json_version("plugin/dashboard/manifest.json")
update_json_version("plugin/dashboard/package.json")
update_json_version("plugin/dashboard/package-lock.json", package_lock=True)
PY

python "$VERSION_CHECK" --expect "$NEW_VERSION"

echo "  [ok] Relay version is now $NEW_VERSION"
echo ""
echo "  --- diff ------------------------------------------------"
git diff --no-color "$PYPROJECT" "$INITPY" "$PLUGIN_YAML" "$DASH_MANIFEST" "$DASH_PACKAGE" "$DASH_LOCK" || true
echo "  --------------------------------------------------------"
echo ""
echo "  Next steps:"
echo "    1. Update CHANGELOG.md / relay release notes if needed"
echo "    2. Commit on dev:"
echo "         git add $PYPROJECT $INITPY $PLUGIN_YAML $DASH_MANIFEST $DASH_PACKAGE $DASH_LOCK CHANGELOG.md"
echo "         git commit -m \"release(relay): relay-v$NEW_VERSION\""
echo "    3. Merge dev -> main, then tag main:"
echo "         git tag relay-v$NEW_VERSION"
echo "         git push origin relay-v$NEW_VERSION"
