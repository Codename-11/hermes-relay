#!/usr/bin/env bash
# tui-smoke.sh — non-interactive setup for the desktop TUI smoke test.
#
# What this does:
#   1. Gracefully stops any running relay (systemd user unit first, then pkill).
#   2. Starts a dev relay (port 8767 by default, no SSL) in the background.
#   3. Waits up to 15s for GET /health to return 200.
#   4. Mints a one-time pairing code via POST /pairing/register (loopback-only).
#   5. Prints the exact command to run on the desktop to attach.
#   6. Exits 0 leaving the relay running — the user drives the TUI from a
#      separate terminal. Run scripts/tui-smoke-teardown.sh when done.
#
# This script intentionally does NOT spawn the Node TUI. Interactive TUI
# testing happens from the operator's own terminal (the relay-host shell
# is almost never the same terminal as the desktop being tested).

set -euo pipefail

PORT="${HERMES_RELAY_PORT:-8767}"
URL="ws://localhost:${PORT}"
HEALTH_URL="http://localhost:${PORT}/health"
PAIR_URL="http://localhost:${PORT}/pairing/register"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="${REPO_ROOT}/.smoke-relay.pid"
LOG_FILE="${REPO_ROOT}/.smoke-relay.log"

# Resolve a python that can import plugin.relay. Prefer the hermes-agent
# venv (where yaml + aiohttp are guaranteed installed by the installer).
PY="${HERMES_PYTHON:-}"
if [[ -z "${PY}" ]]; then
  if [[ -x "${HOME}/.hermes/hermes-agent/venv/bin/python3" ]]; then
    PY="${HOME}/.hermes/hermes-agent/venv/bin/python3"
  else
    PY="python3"
  fi
fi

echo "tui-smoke: using python at ${PY}"

# ── Step 1: stop any running relay ──────────────────────────────────────
if systemctl --user list-unit-files 2>/dev/null | grep -q '^hermes-relay\.service'; then
  echo "tui-smoke: stopping systemd user unit hermes-relay (if running)..."
  systemctl --user stop hermes-relay 2>/dev/null || true
fi
# Always try a pkill sweep — catches ad-hoc runs that aren't under systemd.
if pgrep -f "relay_server\|plugin\.relay" > /dev/null 2>&1; then
  echo "tui-smoke: killing stray relay processes..."
  pkill -f "relay_server\|plugin\.relay" 2>/dev/null || true
  sleep 1
fi
# Leftover PID file from a previous run — clean it up.
if [[ -f "${PID_FILE}" ]]; then
  OLD_PID="$(cat "${PID_FILE}" || true)"
  if [[ -n "${OLD_PID}" ]] && kill -0 "${OLD_PID}" 2>/dev/null; then
    kill "${OLD_PID}" 2>/dev/null || true
  fi
  rm -f "${PID_FILE}"
fi

# ── Step 2: start dev relay ────────────────────────────────────────────
echo "tui-smoke: starting dev relay on port ${PORT} (no SSL)..."
cd "${REPO_ROOT}"
nohup "${PY}" -m plugin.relay --port "${PORT}" --no-ssl --log-level INFO \
  > "${LOG_FILE}" 2>&1 &
RELAY_PID=$!
echo "${RELAY_PID}" > "${PID_FILE}"
echo "tui-smoke: relay pid ${RELAY_PID}, log ${LOG_FILE}"

# ── Step 3: wait for /health ────────────────────────────────────────────
echo -n "tui-smoke: waiting for /health..."
for i in $(seq 1 30); do
  if ! kill -0 "${RELAY_PID}" 2>/dev/null; then
    echo ""
    echo "tui-smoke: ERROR — relay process exited before becoming healthy"
    echo "tui-smoke: tail of log (${LOG_FILE}):"
    tail -n 40 "${LOG_FILE}" || true
    exit 1
  fi
  HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' "${HEALTH_URL}" 2>/dev/null || true)"
  if [[ "${HTTP_CODE}" == "200" ]]; then
    echo " ok"
    break
  fi
  echo -n "."
  sleep 0.5
done
if [[ "${HTTP_CODE:-}" != "200" ]]; then
  echo ""
  echo "tui-smoke: ERROR — /health never returned 200"
  echo "tui-smoke: tail of log (${LOG_FILE}):"
  tail -n 40 "${LOG_FILE}" || true
  exit 1
fi

# ── Step 4: mint a pairing code ─────────────────────────────────────────
# 6 chars, A-Z / 0-9, the same alphabet the relay uses internally. Use a
# pure-bash loop (not `tr | head`) so `set -o pipefail` doesn't misread
# SIGPIPE from the early-exiting head as a real failure.
_ALPHABET='ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
CODE=''
for _ in 1 2 3 4 5 6; do
  _idx=$(( RANDOM % ${#_ALPHABET} ))
  CODE="${CODE}${_ALPHABET:${_idx}:1}"
done
echo "tui-smoke: registering pairing code ${CODE}..."
REG_RESP="$(curl -s -X POST "${PAIR_URL}" \
  -H 'Content-Type: application/json' \
  -d "{\"code\":\"${CODE}\"}" || true)"
if ! echo "${REG_RESP}" | grep -q '"ok"[[:space:]]*:[[:space:]]*true'; then
  echo "tui-smoke: ERROR — /pairing/register failed"
  echo "tui-smoke: response: ${REG_RESP}"
  exit 1
fi

# ── Step 5: print the handoff command ───────────────────────────────────
HERMES_AGENT_DIR="${HERMES_AGENT_DIR:-${HOME}/.hermes/hermes-agent}"
cat <<EOF

========================================================================
Relay is up on ${URL} (pid ${RELAY_PID}, log: ${LOG_FILE}).
Pairing code (expires in 10m): ${CODE}

Run this in a SEPARATE terminal to attach the desktop TUI:

  cd ${HERMES_AGENT_DIR}/ui-tui && \\
    HERMES_RELAY_URL=${URL} \\
    HERMES_RELAY_CODE=${CODE} \\
    node --loader tsx src/entry.tsx

Or via the CLI (once this code is in argparse):

  hermes --remote ${URL} --pair ${CODE}

When done:
  bash scripts/tui-smoke-teardown.sh
========================================================================
EOF
