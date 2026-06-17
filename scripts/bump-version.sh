#!/usr/bin/env bash
# Backward-compatible Android app version bump wrapper.
#
# Hermes-Relay now has split release tracks:
#   - Android app: android-vX.Y.Z, version in gradle/libs.versions.toml
#   - Plugin/Python package: plugin-vX.Y.Z, version in pyproject.toml
#     and plugin/relay/__init__.py
#   - CLI: cli-vX.Y.Z, version in desktop/package.json
#
# Keep this legacy script as an alias for the Android app bump so older release
# notes and muscle memory still work, but prefer the explicit script names:
#   scripts/bump-android-version.sh X.Y.Z
#   scripts/bump-plugin-version.sh X.Y.Z

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
echo "NOTE: scripts/bump-version.sh is now an Android app alias."
echo "      Use scripts/bump-plugin-version.sh for plugin-v* releases."
exec bash "$REPO_ROOT/scripts/bump-android-version.sh" "$@"
