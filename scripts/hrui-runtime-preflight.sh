#!/usr/bin/env bash

# Read-only evidence collector for the HRUI-056/066/159/160/162 runtime gate.
# It does not stop services, run SQLite integrity checks, create backups, or
# modify either checkout. Redirect stdout to an evidence file when required.

set -uo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/hrui-runtime-preflight.sh [--strict]

Environment overrides:
  HERMES_HOME                 Hermes data root (default: ~/.hermes)
  HERMES_AGENT_DIR            hermes-agent checkout
  HERMES_RELAY_DIR            Hermes-Relay checkout
  HRUI_UNITS                  Space-separated systemd user units
  HRUI_HEALTH_URLS            Space-separated HTTP health URLs
  HRUI_EXPECTED_HERMES_REV    Exact hermes-agent revision required in strict mode
  HRUI_EXPECTED_RELAY_REV     Exact Hermes-Relay revision required in strict mode
  HRUI_EXPECTED_RELAY_VERSION Relay version required from /health in strict mode
  HRUI_CUTOVER_EPOCH          Earliest allowed service start epoch in strict mode
  HRUI_LOG_SINCE              journalctl --since value (default: -2 hours)
  HRUI_DB_MAX_DEPTH           Database inventory depth (default: 5)

Strict mode exits nonzero for a dirty checkout, inactive or pre-cutover unit,
revision mismatch, failed health request, or Relay version mismatch. The
script remains read-only in both modes.
EOF
}

strict=0
case "${1:-}" in
  "") ;;
  --strict) strict=1 ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

hermes_home="${HERMES_HOME:-$HOME/.hermes}"
agent_dir="${HERMES_AGENT_DIR:-$hermes_home/hermes-agent}"
relay_dir="${HERMES_RELAY_DIR:-$hermes_home/hermes-relay}"
health_urls="${HRUI_HEALTH_URLS:-http://127.0.0.1:9119/api/health http://127.0.0.1:8767/health http://127.0.0.1:8648/health}"
log_since="${HRUI_LOG_SINCE:--2 hours}"
db_max_depth="${HRUI_DB_MAX_DEPTH:-5}"
failures=0

section() {
  printf '\n== %s ==\n' "$1"
}

fail() {
  printf 'FAIL %s\n' "$1"
  failures=$((failures + 1))
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'ERROR required command not found: %s\n' "$1" >&2
    exit 2
  fi
}

for command_name in date find git journalctl sha256sum stat systemctl; do
  require_command "$command_name"
done

section "capture"
printf 'captured_at=%s\n' "$(date -Is)"
printf 'host=%s\n' "$(hostname)"
printf 'hermes_home=%s\n' "$hermes_home"
printf 'strict=%s\n' "$strict"

inspect_checkout() {
  local label="$1"
  local path="$2"
  local expected="$3"
  local head status

  printf '%s_path=%s\n' "$label" "$path"
  if ! git -C "$path" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    fail "$label checkout is unavailable"
    return
  fi

  head="$(git -C "$path" rev-parse HEAD)"
  status="$(git -C "$path" status --porcelain --untracked-files=normal)"
  printf '%s_head=%s\n' "$label" "$head"
  printf '%s_branch=%s\n' "$label" "$(git -C "$path" branch --show-current)"
  printf '%s_dirty=%s\n' "$label" "$([ -n "$status" ] && printf true || printf false)"
  if [ -n "$status" ]; then
    git -C "$path" status --short
    [ "$strict" -eq 1 ] && fail "$label checkout is dirty"
  fi
  if [ -n "$expected" ] && [ "$head" != "$expected" ]; then
    fail "$label revision $head does not match $expected"
  fi
}

section "checkouts"
inspect_checkout hermes "$agent_dir" "${HRUI_EXPECTED_HERMES_REV:-}"
inspect_checkout relay "$relay_dir" "${HRUI_EXPECTED_RELAY_REV:-}"

section "lockfile"
if [ -f "$agent_dir/uv.lock" ]; then
  stat -c 'uv_lock_mtime=%y uv_lock_size=%s' "$agent_dir/uv.lock"
  printf 'uv_lock_worktree_sha256='; sha256sum "$agent_dir/uv.lock" | awk '{print $1}'
  if git -C "$agent_dir" cat-file -e HEAD:uv.lock 2>/dev/null; then
    printf 'uv_lock_head_sha256='; git -C "$agent_dir" show HEAD:uv.lock | sha256sum | awk '{print $1}'
  fi
  sed -n '1,24p' "$agent_dir/uv.lock" | grep -E '^(version|revision|requires-python|\[options\]|exclude-newer)' || true
  git -C "$agent_dir" diff --numstat -- uv.lock || true
fi
if command -v uv >/dev/null 2>&1; then
  uv --version || true
fi

discover_units() {
  systemctl --user list-unit-files --type=service --no-legend --no-pager 2>/dev/null \
    | awk '$1 ~ /^hermes-.*\.service$/ {print $1}' \
    | sort -u
}

if [ -n "${HRUI_UNITS:-}" ]; then
  read -r -a units <<<"$HRUI_UNITS"
else
  mapfile -t units < <(discover_units)
fi

declare -a service_pids=()
add_pid() {
  local candidate="$1"
  [[ "$candidate" =~ ^[0-9]+$ ]] || return
  [ "$candidate" != "0" ] || return
  [ -d "/proc/$candidate" ] || return
  if [[ " ${service_pids[*]} " != *" $candidate "* ]]; then
    service_pids+=("$candidate")
  fi
}

collect_unit_pids() {
  local unit="$1"
  local control_group
  control_group="$(systemctl --user show "$unit" -p ControlGroup --value 2>/dev/null || true)"
  if [ -n "$control_group" ] && [ -d "/sys/fs/cgroup$control_group" ]; then
    while IFS= read -r proc_file; do
      while IFS= read -r child_pid; do add_pid "$child_pid"; done < "$proc_file"
    done < <(find "/sys/fs/cgroup$control_group" -type f -name cgroup.procs 2>/dev/null)
  fi
}
section "services"
for unit in "${units[@]}"; do
  active="$(systemctl --user show "$unit" -p ActiveState --value 2>/dev/null || true)"
  enabled="$(systemctl --user is-enabled "$unit" 2>/dev/null || true)"
  pid="$(systemctl --user show "$unit" -p MainPID --value 2>/dev/null || printf 0)"
  started="$(systemctl --user show "$unit" -p ExecMainStartTimestamp --value 2>/dev/null || true)"
  printf 'unit=%s enabled=%s active=%s pid=%s started=%s\n' "$unit" "$enabled" "$active" "$pid" "$started"

  if [ "$pid" != "0" ] && [ -d "/proc/$pid" ]; then
    add_pid "$pid"
    printf '  exe=%s\n' "$(readlink -f "/proc/$pid/exe")"
    printf '  cwd=%s\n' "$(readlink -f "/proc/$pid/cwd")"
  fi
  collect_unit_pids "$unit"

  if [ "$strict" -eq 1 ] && [ "$enabled" != "disabled" ] && [ "$active" != "active" ]; then
    fail "$unit is not active"
  fi
  if [ "$strict" -eq 1 ] && [ -n "${HRUI_CUTOVER_EPOCH:-}" ] && [ -n "$started" ]; then
    started_epoch="$(date -d "$started" +%s 2>/dev/null || printf 0)"
    if [ "$started_epoch" -lt "$HRUI_CUTOVER_EPOCH" ]; then
      fail "$unit predates cutover epoch $HRUI_CUTOVER_EPOCH"
    fi
  fi
done

section "service interpreter"
service_python="$agent_dir/venv/bin/python"
if [ -x "$service_python" ]; then
  "$service_python" - <<'PY'
import sqlite3
import sys

print(f"python_executable={sys.executable}")
print(f"python_version={sys.version.split()[0]}")
print(f"sqlite_version={sqlite3.sqlite_version}")
source_id = sqlite3.connect(":memory:").execute("select sqlite_source_id()").fetchone()[0]
print(f"sqlite_source_id={source_id}")
PY
else
  fail "service interpreter is unavailable at $service_python"
fi

section "database inventory"
printf 'db_path|db_bytes|wal_bytes|shm_bytes\n'
while IFS= read -r -d '' db; do
  wal_bytes=0
  shm_bytes=0
  [ -f "$db-wal" ] && wal_bytes="$(stat -c %s "$db-wal")"
  [ -f "$db-shm" ] && shm_bytes="$(stat -c %s "$db-shm")"
  printf '%s|%s|%s|%s\n' "$db" "$(stat -c %s "$db")" "$wal_bytes" "$shm_bytes"
done < <(
  find "$hermes_home" -maxdepth "$db_max_depth" -type f -name '*.db' \
    -not -path '*/archives/*' \
    -not -path '*/backups/*' \
    -not -path '*/state-snapshots/*' \
    -not -path '*/kanban/worktrees/*' \
    -not -path '*/hermes-agent/*' \
    -print0 | sort -z
)
df -Pk "$hermes_home" | tail -n 1 | awk '{printf "filesystem_available_kib=%s\n", $4}'

section "open database descriptors"
declare -a unscoped_db_pids=()
while IFS= read -r proc_dir; do
  candidate_pid="${proc_dir#/proc/}"
  while IFS= read -r target; do
    case "$target" in
      "$hermes_home"/*.db|"$hermes_home"/*.db-wal|"$hermes_home"/*.db-shm)
        if [[ " ${service_pids[*]} " != *" $candidate_pid "* ]]; then
          unscoped_db_pids+=("$candidate_pid")
        fi
        break
        ;;
    esac
  done < <(find "$proc_dir/fd" -maxdepth 1 -type l -printf '%l\n' 2>/dev/null)
done < <(find /proc -maxdepth 1 -type d -regex '/proc/[0-9]+' 2>/dev/null)

for pid in "${service_pids[@]}"; do
  find "/proc/$pid/fd" -maxdepth 1 -type l -printf '%l\n' 2>/dev/null \
    | grep -E '\.db(-wal|-shm)?$' \
    | sort -u \
    | sed "s|^|pid=$pid |" || true
done
if [ "${#unscoped_db_pids[@]}" -gt 0 ]; then
  printf 'unscoped_database_writer_pids=%s\n' "$(printf '%s\n' "${unscoped_db_pids[@]}" | sort -nu | paste -sd, -)"
  [ "$strict" -eq 1 ] && fail "database writers exist outside the discovered service cgroups"
else
  printf 'unscoped_database_writer_pids=none\n'
fi

section "listeners"
if command -v ss >/dev/null 2>&1 && [ "${#service_pids[@]}" -gt 0 ]; then
  pid_pattern="$(IFS='|'; printf '%s' "${service_pids[*]}")"
  ss -ltnp 2>/dev/null | grep -E "pid=($pid_pattern)," || true
else
  printf 'scoped listener inspection unavailable\n'
fi

section "health"
if command -v curl >/dev/null 2>&1; then
  for url in $health_urls; do
    response="$(curl -sS --max-time 8 -w $'\n%{http_code}' "$url" || true)"
    code="${response##*$'\n'}"
    body="${response%$'\n'*}"
    printf 'url=%s status=%s body=' "$url" "${code:-000}"
    tr '\n' ' ' <<<"$body" | cut -c1-512
    printf '\n'
    if [ "$strict" -eq 1 ] && [[ ! "$code" =~ ^2 ]]; then
      fail "$url health request returned ${code:-000}"
    fi
    if [ "$strict" -eq 1 ] && [ -n "${HRUI_EXPECTED_RELAY_VERSION:-}" ] && [[ "$url" == *":8767/"* ]]; then
      if ! grep -Eq '"version"[[:space:]]*:[[:space:]]*"'"$HRUI_EXPECTED_RELAY_VERSION"'"' <<<"$body"; then
        fail "Relay health does not report version $HRUI_EXPECTED_RELAY_VERSION"
      fi
    fi
  done
else
  printf 'curl unavailable; health checks skipped\n'
  [ "$strict" -eq 1 ] && fail "curl is required for strict health checks"
fi

section "scoped log counters"
if [ "${#units[@]}" -gt 0 ]; then
  journal_args=()
  for unit in "${units[@]}"; do journal_args+=(--unit "$unit"); done
  logs="$(journalctl --user --since "$log_since" "${journal_args[@]}" --no-pager 2>/dev/null || true)"
  printf 'wal_or_sqlite_warning_count=%s\n' "$(grep -Eic 'wal|sqlite|malformed|corrupt|repair lock' <<<"$logs" || true)"
  printf 'observability_timeout_count=%s\n' "$(grep -Eic 'scope\.(push|pop)|subscriber flush|abandoned span|relay lifecycle.*timeout' <<<"$logs" || true)"
  printf 'adapter_attention_count=%s\n' "$(grep -Eic 'needs_attention|retrying_since|non.?retryable|privileged intents|required.*token' <<<"$logs" || true)"
  printf 'warning_or_error_count=%s\n' "$(grep -Eic 'warning|error|exception|traceback|fatal' <<<"$logs" || true)"
fi

section "result"
printf 'failures=%s\n' "$failures"
if [ "$strict" -eq 1 ] && [ "$failures" -ne 0 ]; then
  exit 1
fi
