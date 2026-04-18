#!/usr/bin/env bash
# Build the Hermes-Relay dashboard plugin frontend bundle.
#
# Thin wrapper around esbuild. Intended for git-hook or CI use; day-to-day
# dev is happier with `npm run build` / `npm run watch`.
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v node >/dev/null 2>&1; then
  echo "error: node is required" >&2
  exit 1
fi

if [ ! -d node_modules ]; then
  echo "installing deps..."
  npm install --no-audit --no-fund
fi

npm run build "$@"
