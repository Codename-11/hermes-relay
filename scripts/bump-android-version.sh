#!/usr/bin/env bash
# scripts/bump-android-version.sh - bump Android app release version.
#
# Updates only the Android app version source:
#   gradle/libs.versions.toml::appVersionName
#   gradle/libs.versions.toml::appVersionCode
#
# Relay/Python releases are intentionally split; use
# scripts/bump-relay-version.sh for relay-owned plugin/package metadata.

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

LIBS="gradle/libs.versions.toml"
[ -f "$LIBS" ] || { echo "  [x] missing $LIBS - are you in the repo root?" >&2; exit 1; }

OLD_NAME="$(grep -E '^appVersionName *= *"' "$LIBS" | sed -E 's/.*"(.*)".*/\1/')"
OLD_CODE="$(grep -E '^appVersionCode *= *"' "$LIBS" | sed -E 's/.*"(.*)".*/\1/')"

if [ -z "$OLD_NAME" ] || [ -z "$OLD_CODE" ]; then
    echo "  [x] could not parse current Android version from $LIBS" >&2
    exit 1
fi

NEW_CODE=$((OLD_CODE + 1))

echo ""
echo "  Bumping Android app version:"
echo "    appVersionName:  $OLD_NAME -> $NEW_VERSION"
echo "    appVersionCode:  $OLD_CODE -> $NEW_CODE"
echo ""

perl -pi -e 's/^appVersionName *= *"[^"]*"/appVersionName = "'"$NEW_VERSION"'"/' "$LIBS"
perl -pi -e 's/^appVersionCode *= *"[^"]*"/appVersionCode = "'"$NEW_CODE"'"/' "$LIBS"

GOT_LIBS="$(grep -E '^appVersionName *= *"' "$LIBS" | sed -E 's/.*"(.*)".*/\1/')"
GOT_CODE="$(grep -E '^appVersionCode *= *"' "$LIBS" | sed -E 's/.*"(.*)".*/\1/')"

if [ "$GOT_LIBS" != "$NEW_VERSION" ] || [ "$GOT_CODE" != "$NEW_CODE" ]; then
    echo "  [x] post-bump sanity check failed:" >&2
    echo "      appVersionName: $GOT_LIBS" >&2
    echo "      appVersionCode: $GOT_CODE" >&2
    exit 1
fi

echo "  [ok] Android app version is now $NEW_VERSION (code $NEW_CODE)"
echo ""
echo "  --- diff ------------------------------------------------"
git diff --no-color "$LIBS" || true
echo "  --------------------------------------------------------"
echo ""
echo "  Next steps:"
echo "    1. Update CHANGELOG.md, RELEASE_NOTES.md, and app/src/main/assets/whats_new.txt"
echo "    2. Commit on dev:"
echo "         git add $LIBS CHANGELOG.md RELEASE_NOTES.md app/src/main/assets/whats_new.txt"
echo "         git commit -m \"release: v$NEW_VERSION\""
echo "    3. Merge dev -> main, then tag main:"
echo "         git tag v$NEW_VERSION"
echo "         git push origin v$NEW_VERSION"
