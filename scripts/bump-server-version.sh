#!/usr/bin/env bash
# Backward-compatible wrapper for the renamed plugin release helper.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
echo "NOTE: scripts/bump-server-version.sh is deprecated; use scripts/bump-plugin-version.sh."
exec bash "$REPO_ROOT/scripts/bump-plugin-version.sh" "$@"
