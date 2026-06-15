#!/usr/bin/env bash
# Backward-compatible wrapper for the renamed server release helper.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
echo "NOTE: scripts/bump-relay-version.sh is deprecated; use scripts/bump-server-version.sh."
exec bash "$REPO_ROOT/scripts/bump-server-version.sh" "$@"
