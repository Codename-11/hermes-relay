#!/usr/bin/env bash
# scripts/bump-relay-version.sh - bump Relay server/Python package version.
#
# Updates the Relay version sources:
#   pyproject.toml::project.version
#   plugin/relay/__init__.py::__version__
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

for f in "$PYPROJECT" "$INITPY"; do
    [ -f "$f" ] || { echo "  [x] missing $f - are you in the repo root?" >&2; exit 1; }
done

OLD_PY="$(grep -E '^version *= *"' "$PYPROJECT" | head -1 | sed -E 's/.*"(.*)".*/\1/')"
OLD_INIT="$(grep -E '^__version__ *= *"' "$INITPY" | sed -E 's/.*"(.*)".*/\1/')"

if [ -z "$OLD_PY" ] || [ -z "$OLD_INIT" ]; then
    echo "  [x] could not parse current Relay version sources" >&2
    exit 1
fi

if [ "$OLD_PY" != "$OLD_INIT" ]; then
    echo "  [x] Relay version sources are already out of sync:" >&2
    echo "      pyproject.toml:      $OLD_PY" >&2
    echo "      plugin/relay init:   $OLD_INIT" >&2
    echo "      Fix that drift before bumping." >&2
    exit 1
fi

echo ""
echo "  Bumping Relay version:"
echo "    pyproject.toml:        $OLD_PY -> $NEW_VERSION"
echo "    plugin/relay version:  $OLD_INIT -> $NEW_VERSION"
echo ""

perl -pi -e 's/^version *= *"[^"]*"/version = "'"$NEW_VERSION"'"/' "$PYPROJECT"
perl -pi -e 's/^__version__ *= *"[^"]*"/__version__ = "'"$NEW_VERSION"'"/' "$INITPY"

GOT_PY="$(grep -E '^version *= *"' "$PYPROJECT" | head -1 | sed -E 's/.*"(.*)".*/\1/')"
GOT_INIT="$(grep -E '^__version__ *= *"' "$INITPY" | sed -E 's/.*"(.*)".*/\1/')"

if [ "$GOT_PY" != "$NEW_VERSION" ] || [ "$GOT_INIT" != "$NEW_VERSION" ]; then
    echo "  [x] post-bump sanity check failed:" >&2
    echo "      pyproject.toml:      $GOT_PY" >&2
    echo "      plugin/relay init:   $GOT_INIT" >&2
    exit 1
fi

echo "  [ok] Relay version is now $NEW_VERSION"
echo ""
echo "  --- diff ------------------------------------------------"
git diff --no-color "$PYPROJECT" "$INITPY" || true
echo "  --------------------------------------------------------"
echo ""
echo "  Next steps:"
echo "    1. Update CHANGELOG.md / relay release notes if needed"
echo "    2. Commit on dev:"
echo "         git add $PYPROJECT $INITPY CHANGELOG.md"
echo "         git commit -m \"release(relay): relay-v$NEW_VERSION\""
echo "    3. Merge dev -> main, then tag main:"
echo "         git tag relay-v$NEW_VERSION"
echo "         git push origin relay-v$NEW_VERSION"
