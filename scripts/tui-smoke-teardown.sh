#!/usr/bin/env bash
# tui-smoke-teardown.sh — stop the dev relay started by tui-smoke.sh.
#
# Idempotent. Safe to run after an abnormal exit / reboot — it only kills
# the PID recorded in .smoke-relay.pid, which is a no-op if the process
# is already gone.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="${REPO_ROOT}/.smoke-relay.pid"

if [[ -f "${PID_FILE}" ]]; then
  PID="$(cat "${PID_FILE}" || true)"
  if [[ -n "${PID}" ]] && kill -0 "${PID}" 2>/dev/null; then
    echo "tui-smoke-teardown: stopping relay pid ${PID}"
    kill "${PID}" 2>/dev/null || true
    for _ in $(seq 1 10); do
      kill -0 "${PID}" 2>/dev/null || break
      sleep 0.3
    done
    if kill -0 "${PID}" 2>/dev/null; then
      echo "tui-smoke-teardown: SIGTERM did not land, escalating to SIGKILL"
      kill -9 "${PID}" 2>/dev/null || true
    fi
  else
    echo "tui-smoke-teardown: no live process at pid ${PID}"
  fi
  rm -f "${PID_FILE}"
else
  echo "tui-smoke-teardown: no PID file at ${PID_FILE} — nothing to do"
fi

# Also sweep stray python -m plugin.relay processes — belt and suspenders.
if pgrep -f "plugin\.relay --port" > /dev/null 2>&1; then
  echo "tui-smoke-teardown: killing stray plugin.relay processes"
  pkill -f "plugin\.relay --port" 2>/dev/null || true
fi
