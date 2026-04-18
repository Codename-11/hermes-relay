#!/usr/bin/env bash
# Hermes-Relay — canonical uninstaller.
#
# Reverses every step of install.sh in the opposite order. Idempotent: safe
# to run twice; missing artifacts produce a warning, not an error. Never
# touches state shared with other tools (~/.hermes/.env, the gateway's
# state.db, the hermes-agent venv core, etc.).
#
# Usage:
#   bash uninstall.sh                    # Standard uninstall (keeps QR secret)
#   bash uninstall.sh --remove-secret    # Also wipe ~/.hermes/hermes-relay-qr-secret
#   bash uninstall.sh --keep-clone       # Don't remove ~/.hermes/hermes-relay
#   bash uninstall.sh --dry-run          # Print what would be removed, don't touch anything
#
# What it removes (in reverse install order):
#   [6] systemd user service             — `systemctl --user disable --now`,
#                                            unit file deletion, daemon-reload
#   [5] hermes-pair + hermes-status     — ~/.local/bin/hermes-pair,
#       + hermes-relay-update shims         ~/.local/bin/hermes-status,
#                                           ~/.local/bin/hermes-relay-update
#   [4] skills external_dirs entry       — removes the relay's path from
#                                            ~/.hermes/config.yaml (other
#                                            entries preserved)
#   [3] plugin symlink + legacy stales   — ~/.hermes/plugins/hermes-relay
#                                            and any deprecated names
#   [2] bootstrap .pth + pip package     — venv site-packages drop and
#                                            `pip uninstall hermes-relay`
#   [1] git clone                        — ~/.hermes/hermes-relay (skipped
#                                            with --keep-clone)
#
# What it does NOT touch (ever):
#   - ~/.hermes/.env                     (other tools authenticate against this)
#   - ~/.hermes/state.db                 (sessions DB shared with the gateway)
#   - ~/.hermes/hermes-agent/            (the agent itself)
#   - ~/.hermes/hermes-agent/venv/       (venv core; we only remove our own .pth)
#   - ~/.hermes/hermes-relay-qr-secret   (kept by default — wipe with --remove-secret)
#   - The phone's stored session token   (becomes stale on relay restart anyway)
#
# Overrides (mirror install.sh):
#   HERMES_HOME       Hermes config home (default: ~/.hermes)
#   HERMES_RELAY_HOME Target directory   (default: $HERMES_HOME/hermes-relay)
#   HERMES_VENV_PY    Path to hermes-agent venv python
#                     (default: $HERMES_HOME/hermes-agent/venv/bin/python)

set -euo pipefail

# ── Argument parsing ───────────────────────────────────────────────────────
KEEP_CLONE=""
REMOVE_SECRET=""
DRY_RUN=""

while [ $# -gt 0 ]; do
    case "$1" in
        --keep-clone)    KEEP_CLONE=1 ;;
        --remove-secret) REMOVE_SECRET=1 ;;
        --dry-run)       DRY_RUN=1 ;;
        -h|--help)
            sed -n '2,40p' "$0"
            exit 0
            ;;
        *) echo "Unknown flag: $1" >&2; exit 1 ;;
    esac
    shift
done

# ── Config ─────────────────────────────────────────────────────────────────
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
RELAY_HOME="${HERMES_RELAY_HOME:-$HERMES_HOME/hermes-relay}"
VENV_PY="${HERMES_VENV_PY:-$HERMES_HOME/hermes-agent/venv/bin/python}"
PLUGIN_LINK="$HERMES_HOME/plugins/hermes-relay"
HERMES_CONFIG="$HERMES_HOME/config.yaml"
QR_SECRET="$HERMES_HOME/hermes-relay-qr-secret"
SHIM_PATH="$HOME/.local/bin/hermes-pair"
STATUS_SHIM_PATH="$HOME/.local/bin/hermes-status"
UPDATE_SHIM_PATH="$HOME/.local/bin/hermes-relay-update"
DOCTOR_SHIM_PATH="$HOME/.local/bin/hermes-relay-doctor"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
SERVICE_DST="$SYSTEMD_USER_DIR/hermes-relay.service"
PTH_NAME="hermes_relay_bootstrap.pth"

# ── Helpers ────────────────────────────────────────────────────────────────
info() { echo "  $*"; }
ok()   { echo "  [ok] $*"; }
warn() { echo "  [skip] $*"; }
note() { echo "  [note] $*"; }

run() {
    if [ -n "$DRY_RUN" ]; then
        echo "  [dry-run] $*"
    else
        eval "$@"
    fi
}

# ── Banner ─────────────────────────────────────────────────────────────────
echo ""
echo "  Hermes-Relay Uninstaller"
echo "  ------------------------"
echo "  Hermes home: $HERMES_HOME"
echo "  Clone:       $RELAY_HOME"
echo "  Venv python: $VENV_PY"
[ -n "$KEEP_CLONE" ]    && echo "  --keep-clone:    yes (clone will be left in place)"
[ -n "$REMOVE_SECRET" ] && echo "  --remove-secret: yes (QR secret will be wiped)"
[ -n "$DRY_RUN" ]       && echo "  --dry-run:       yes (no filesystem changes)"
echo ""

# ── 6/6  Stop + remove systemd user service ────────────────────────────────
info "[6/6] Removing systemd user service..."
if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
    if systemctl --user list-unit-files hermes-relay.service >/dev/null 2>&1 && \
       systemctl --user list-unit-files hermes-relay.service 2>/dev/null | grep -q hermes-relay.service; then
        run "systemctl --user disable --now hermes-relay.service >/dev/null 2>&1 || true"
        ok "Stopped + disabled hermes-relay.service"
    else
        warn "hermes-relay.service was not registered with systemd — nothing to stop"
    fi

    if [ -f "$SERVICE_DST" ]; then
        run "rm -f \"$SERVICE_DST\""
        run "systemctl --user daemon-reload >/dev/null 2>&1 || true"
        ok "Removed $SERVICE_DST"
    else
        warn "$SERVICE_DST does not exist"
    fi
else
    warn "systemd user session not available — nothing to stop"
    # Catch a stray manually-launched relay if there is one
    if pgrep -f "python -m plugin.relay" >/dev/null 2>&1; then
        note "A manual 'python -m plugin.relay' is still running."
        note "Stop it yourself with: pkill -f 'python -m plugin.relay'"
    fi
fi

# ── 5/6  Remove hermes-pair + hermes-status + hermes-relay-update shims ───
info "[5/6] Removing hermes-pair + hermes-status + hermes-relay-update + hermes-relay-doctor shims..."
if [ -f "$SHIM_PATH" ] || [ -L "$SHIM_PATH" ]; then
    run "rm -f \"$SHIM_PATH\""
    ok "Removed $SHIM_PATH"
else
    warn "$SHIM_PATH does not exist"
fi
if [ -f "$STATUS_SHIM_PATH" ] || [ -L "$STATUS_SHIM_PATH" ]; then
    run "rm -f \"$STATUS_SHIM_PATH\""
    ok "Removed $STATUS_SHIM_PATH"
else
    warn "$STATUS_SHIM_PATH does not exist"
fi
if [ -f "$UPDATE_SHIM_PATH" ] || [ -L "$UPDATE_SHIM_PATH" ]; then
    run "rm -f \"$UPDATE_SHIM_PATH\""
    ok "Removed $UPDATE_SHIM_PATH"
else
    warn "$UPDATE_SHIM_PATH does not exist"
fi
if [ -f "$DOCTOR_SHIM_PATH" ] || [ -L "$DOCTOR_SHIM_PATH" ]; then
    run "rm -f \"$DOCTOR_SHIM_PATH\""
    ok "Removed $DOCTOR_SHIM_PATH"
else
    warn "$DOCTOR_SHIM_PATH does not exist"
fi

# ── 4/6  Remove skills external_dirs entry from config.yaml ────────────────
info "[4/6] Removing skills external_dirs entry..."
if [ -f "$HERMES_CONFIG" ] && [ -x "$VENV_PY" ]; then
    if [ -n "$DRY_RUN" ]; then
        echo "  [dry-run] would scrub skills.external_dirs entry pointing at $RELAY_HOME/skills"
    else
        "$VENV_PY" - <<PY
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("  [skip] pyyaml not available — config.yaml left untouched")
    sys.exit(0)

cfg_path = Path("$HERMES_CONFIG")
target_str = "$RELAY_HOME/skills"
target = str(Path(target_str).expanduser().resolve()) if Path(target_str).expanduser().exists() else target_str

if not cfg_path.is_file():
    print("  [skip] $HERMES_CONFIG not present")
    sys.exit(0)

try:
    data = yaml.safe_load(cfg_path.read_text(encoding="utf-8")) or {}
except Exception as exc:
    print(f"  [skip] Could not parse {cfg_path}: {exc}")
    sys.exit(0)

if not isinstance(data, dict):
    print("  [skip] config.yaml is not a mapping — nothing to remove")
    sys.exit(0)

skills_section = data.get("skills")
if not isinstance(skills_section, dict):
    print("  [skip] no skills section in config.yaml")
    sys.exit(0)

external_dirs = skills_section.get("external_dirs")
if not isinstance(external_dirs, list):
    print("  [skip] no external_dirs entry to clean")
    sys.exit(0)

# Match by both raw string AND resolved path so we catch both forms
remaining = []
removed = 0
for entry in external_dirs:
    if not isinstance(entry, str):
        remaining.append(entry)
        continue
    try:
        resolved = str(Path(entry).expanduser().resolve())
    except Exception:
        resolved = entry
    if entry == target_str or resolved == target or entry.endswith("/hermes-relay/skills"):
        removed += 1
    else:
        remaining.append(entry)

if removed == 0:
    print("  [skip] no relay skills entry was registered")
    sys.exit(0)

if remaining:
    skills_section["external_dirs"] = remaining
else:
    skills_section.pop("external_dirs", None)
    if not skills_section:
        data.pop("skills", None)

backup = cfg_path.with_suffix(cfg_path.suffix + ".bak")
backup.write_text(cfg_path.read_text(encoding="utf-8"), encoding="utf-8")
print(f"  [ok] Backed up existing config to {backup}")
cfg_path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
print(f"  [ok] Removed {removed} relay skills entry from {cfg_path}")
PY
    fi
else
    warn "$HERMES_CONFIG missing or venv python unavailable — skipped"
fi

# ── 3/6  Remove plugin symlink (and legacy stales) ─────────────────────────
info "[3/6] Removing plugin symlink..."
removed_any=""
for path in "$PLUGIN_LINK" \
            "$HERMES_HOME/plugins/hermes-android" \
            "$HERMES_HOME/hermes-agent/plugins/hermes-android" \
            "$HERMES_HOME/hermes-agent/plugins/hermes-relay"; do
    if [ -L "$path" ] || [ -e "$path" ]; then
        run "rm -rf \"$path\""
        ok "Removed $path"
        removed_any=1
    fi
done
[ -z "$removed_any" ] && warn "No plugin symlinks were registered"

# Best-effort hermes-agent dashboard rescan so the relay tab disappears
# without requiring a dashboard restart. Walks the systemd unit's
# ExecStart for the real --host / --port when available, otherwise
# falls back to common bind addresses. Silent on failure.
if command -v curl >/dev/null 2>&1; then
    _dash_hosts=""
    _dash_port=""
    if command -v systemctl >/dev/null 2>&1; then
        _exec_line="$(systemctl --user cat hermes-dashboard 2>/dev/null | grep -m1 '^ExecStart=' || true)"
        if [ -n "$_exec_line" ]; then
            _dash_hosts="$(echo "$_exec_line" | sed -nE 's/.*--host[[:space:]=]+([^[:space:]]+).*/\1/p')"
            _dash_port="$(echo "$_exec_line" | sed -nE 's/.*--port[[:space:]=]+([0-9]+).*/\1/p')"
        fi
    fi
    [ -z "$_dash_port" ] && _dash_port="9119"
    for host in 127.0.0.1 localhost ${_dash_hosts:-} 0.0.0.0; do
        [ -z "$host" ] && continue
        for port in "$_dash_port" 9119 9100 9000; do
            if curl -sf -m 2 -X GET "http://${host}:${port}/api/dashboard/plugins/rescan" >/dev/null 2>&1; then
                ok "Triggered dashboard rescan at ${host}:${port}"
                break 2
            fi
        done
    done
fi

# ── 2/6  Remove bootstrap .pth + pip package ───────────────────────────────
info "[2/6] Removing bootstrap .pth + pip package..."

if [ -x "$VENV_PY" ]; then
    SITE_PKGS="$("$VENV_PY" -c 'import site; print(site.getsitepackages()[0])' 2>/dev/null || true)"
    if [ -n "$SITE_PKGS" ] && [ -f "$SITE_PKGS/$PTH_NAME" ]; then
        run "rm -f \"$SITE_PKGS/$PTH_NAME\""
        ok "Removed $SITE_PKGS/$PTH_NAME"
    else
        warn "$PTH_NAME not present in venv site-packages"
    fi

    if "$VENV_PY" -m pip show hermes-relay >/dev/null 2>&1; then
        run "\"$VENV_PY\" -m pip uninstall --quiet --yes hermes-relay >/dev/null 2>&1 || true"
        ok "pip uninstall hermes-relay"
    else
        warn "hermes-relay pip package was not installed"
    fi
else
    warn "Venv python not found at $VENV_PY — skipped pip uninstall + .pth removal"
fi

# ── 1/6  Remove the git clone ──────────────────────────────────────────────
info "[1/6] Removing git clone..."
if [ -n "$KEEP_CLONE" ]; then
    note "--keep-clone set — leaving $RELAY_HOME in place"
elif [ -d "$RELAY_HOME" ]; then
    # Sanity guard: refuse to remove a directory that doesn't look like our clone
    if [ -d "$RELAY_HOME/.git" ] && [ -f "$RELAY_HOME/install.sh" ]; then
        run "rm -rf \"$RELAY_HOME\""
        ok "Removed $RELAY_HOME"
    else
        warn "$RELAY_HOME exists but doesn't look like a hermes-relay clone — left untouched"
    fi
else
    warn "$RELAY_HOME does not exist"
fi

# ── Optional: QR signing secret ────────────────────────────────────────────
info "[opt] QR signing secret..."
if [ -f "$QR_SECRET" ]; then
    if [ -n "$REMOVE_SECRET" ]; then
        run "rm -f \"$QR_SECRET\""
        ok "Removed $QR_SECRET (--remove-secret)"
        note "All paired phones will need to re-trust the next QR code on their next pair."
    else
        note "$QR_SECRET preserved (use --remove-secret to wipe)."
        note "Without removing it, re-installing keeps the same QR signing identity"
        note "and any phones still holding their session tokens stay valid."
    fi
else
    warn "$QR_SECRET does not exist"
fi

# ── Done ───────────────────────────────────────────────────────────────────
echo ""
if [ -n "$DRY_RUN" ]; then
    echo "  [dry-run complete] No changes made."
else
    echo "  [OK] Hermes-Relay uninstalled."
fi
echo ""
echo "  Preserved (other tools depend on these):"
echo "    - $HERMES_HOME/.env"
echo "    - $HERMES_HOME/state.db (sessions database)"
echo "    - $HERMES_HOME/config.yaml (only the relay's skills entry was removed)"
echo "    - $HERMES_HOME/hermes-agent/ (the agent itself)"
[ -z "$REMOVE_SECRET" ] && [ -f "$QR_SECRET" ] && echo "    - $QR_SECRET (QR signing secret)"
echo ""
echo "  To reinstall:"
echo "    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash"
echo ""
