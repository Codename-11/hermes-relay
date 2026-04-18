#!/usr/bin/env bash
# Hermes-Relay — canonical one-line installer.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
#
# Installs:
#   1. The hermes-relay repo to ~/.hermes/hermes-relay (editable, git-backed)
#   2. The Python package (plugin + relay server + bootstrap injection) via
#      `pip install -e` into the hermes-agent venv so `python -m plugin.pair`
#      works from anywhere AND the hermes_relay_bootstrap package is on the
#      Python path. The bootstrap is also wired up via a `.pth` file dropped
#      directly into the venv's site-packages so Python's `site` module
#      auto-loads it at every interpreter startup. The bootstrap monkey-
#      patches `aiohttp.web.Application` so when the gateway builds its app,
#      our extra `/api/sessions/*`, `/api/memory`, `/api/skills`, `/api/config`
#      and `/api/available-models` routes get injected onto the same router
#      the gateway is in the middle of populating. Feature-detected by route
#      path — if your hermes-agent build already has these endpoints natively,
#      the bootstrap no-ops cleanly so it's safe to ship across all versions.
#   3. A symlink at ~/.hermes/plugins/hermes-relay → the clone's plugin/ dir
#      so Hermes's plugin loader discovers + enables the plugin
#   4. The skill(s) under skills/ into ~/.hermes/config.yaml as a scanned
#      external_dirs entry — Hermes follows that path at runtime, so
#      `git pull` on the clone auto-updates the SKILL.md files
#   5. Shell shims at:
#      - ~/.local/bin/hermes-pair          → `<venv>/python -m plugin.pair "$@"`
#      - ~/.local/bin/hermes-status        → `<venv>/python -m plugin.status "$@"`
#      - ~/.local/bin/hermes-relay-update  → curl-pipe re-runs install.sh
#      The pair/status shims exist because the upstream `hermes pair` plugin
#      CLI path is blocked. The update shim is just a discoverable name for
#      "re-run the canonical curl-pipe installer" — convenience UX, not a
#      separate code path.
#   6. A systemd user unit at ~/.config/systemd/user/hermes-relay.service
#      (optional — only on hosts with a systemd user session; skipped on
#      macOS, WSL-without-systemd, bare chroots, etc.). When installed,
#      the relay auto-starts on login, restarts on failure, and picks up
#      API keys from ~/.hermes/.env via the Python-side env bootstrap.
#
# Updates:
#   cd ~/.hermes/hermes-relay && git pull
#   (No reinstall needed — editable pip install + external_dirs scan mean
#    changes go live on the next invocation. The bootstrap .pth file is
#    overwritten on every install.sh re-run, so changes to the bootstrap
#    package itself need a fresh `bash install.sh` to land in site-packages.)
#
# Uninstall:
#   bash ~/.hermes/hermes-relay/uninstall.sh
#   (Or, if you don't have the clone any more:
#    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/uninstall.sh | bash)
#
#   The uninstaller reverses every step here in the opposite order. It is
#   idempotent and never touches shared state (~/.hermes/.env, the gateway's
#   state.db, the hermes-agent venv core). Use --keep-clone to leave the git
#   tree in place, --remove-secret to also wipe the QR signing identity,
#   --dry-run to preview without changing anything.
#
# Flags:
#   --branch <name>         Git branch to install (default: main). Same effect
#                           as HERMES_RELAY_BRANCH; flag wins over env var.
#                           Useful for testing feature branches before merging
#                           to main, e.g. `hermes-relay-update --branch
#                           feature/bridge-feature-expansion`.
#   --dashboard-plugin=yes  Enable (default) the hermes-agent dashboard plugin.
#   --dashboard-plugin=no   Disable it — useful on hosts that don't run the
#                           hermes-agent dashboard or want a minimal relay
#                           install. Toggle at any time by re-running with
#                           the opposite flag.
#   --help                  Print this header and exit.
#
# Overrides:
#   HERMES_RELAY_HOME       Target directory (default: ~/.hermes/hermes-relay)
#   HERMES_RELAY_BRANCH     Git branch to install (default: main). Superseded
#                           by --branch when both are provided.
#   HERMES_VENV_PY          Path to hermes-agent venv python
#                           (default: ~/.hermes/hermes-agent/venv/bin/python)
#   HERMES_HOME             Hermes config home (default: ~/.hermes)
#   HERMES_RELAY_NO_SYSTEMD Skip step [6/6] even if systemd is available
#                           (set to any non-empty value)

set -euo pipefail

# ── Argument parsing ──────────────────────────────────────────────────────
# Parse CLI flags BEFORE setting config defaults so flags can override env
# vars. Currently only --branch is supported; everything else still goes
# through env vars (HERMES_RELAY_HOME, HERMES_VENV_PY, etc). The flag is
# pass-through-friendly for `hermes-relay-update --branch <name>` since
# the shim forwards args via `bash -s --`.
_BRANCH_FLAG=""
_DASHBOARD_PLUGIN_FLAG=""  # "", "yes", or "no"
while [ $# -gt 0 ]; do
    case "$1" in
        --branch)
            if [ $# -lt 2 ]; then
                echo "install.sh: --branch needs an argument (e.g. --branch feature/foo)" >&2
                exit 2
            fi
            _BRANCH_FLAG="$2"
            shift 2
            ;;
        --branch=*)
            _BRANCH_FLAG="${1#--branch=}"
            shift
            ;;
        --dashboard-plugin=*)
            _val="${1#--dashboard-plugin=}"
            case "$_val" in
                yes|on|true|1) _DASHBOARD_PLUGIN_FLAG="yes" ;;
                no|off|false|0) _DASHBOARD_PLUGIN_FLAG="no" ;;
                *)
                    echo "install.sh: --dashboard-plugin expects yes|no (got $_val)" >&2
                    exit 2
                    ;;
            esac
            shift
            ;;
        --dashboard-plugin)
            if [ $# -lt 2 ]; then
                echo "install.sh: --dashboard-plugin needs yes|no" >&2
                exit 2
            fi
            case "$2" in
                yes|on|true|1) _DASHBOARD_PLUGIN_FLAG="yes" ;;
                no|off|false|0) _DASHBOARD_PLUGIN_FLAG="no" ;;
                *)
                    echo "install.sh: --dashboard-plugin expects yes|no (got $2)" >&2
                    exit 2
                    ;;
            esac
            shift 2
            ;;
        -h|--help)
            sed -n '3,70p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            # Unknown args silently passed through to the rest of the
            # script — currently nothing else consumes them, but keeping
            # this lenient avoids breaking on future shim extensions.
            shift
            ;;
    esac
done

# ── Config ─────────────────────────────────────────────────────────────────
REPO_URL="https://github.com/Codename-11/hermes-relay.git"
# Branch precedence: --branch flag wins over HERMES_RELAY_BRANCH env var
# wins over the default ("main"). The env var is kept for backwards
# compat with anyone scripting against the pre-flag interface.
BRANCH="${_BRANCH_FLAG:-${HERMES_RELAY_BRANCH:-main}}"
# Dashboard plugin: flag > env var > default ("yes"). Passing "no" stashes
# the dashboard manifest to manifest.json.disabled so the hermes-agent
# dashboard loader ignores the plugin entirely. Re-running with the
# opposite flag flips it back without touching any other state.
DASHBOARD_PLUGIN="${_DASHBOARD_PLUGIN_FLAG:-${HERMES_RELAY_DASHBOARD_PLUGIN:-yes}}"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
RELAY_HOME="${HERMES_RELAY_HOME:-$HERMES_HOME/hermes-relay}"
VENV_PY="${HERMES_VENV_PY:-$HERMES_HOME/hermes-agent/venv/bin/python}"
PLUGIN_LINK="$HERMES_HOME/plugins/hermes-relay"
SKILLS_DIR_IN_REPO="$RELAY_HOME/skills"
HERMES_CONFIG="$HERMES_HOME/config.yaml"
SHIM_PATH="$HOME/.local/bin/hermes-pair"
STATUS_SHIM_PATH="$HOME/.local/bin/hermes-status"
UPDATE_SHIM_PATH="$HOME/.local/bin/hermes-relay-update"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
SERVICE_SRC="$RELAY_HOME/relay_server/hermes-relay.service"
SERVICE_DST="$SYSTEMD_USER_DIR/hermes-relay.service"

# ── TUI: colors + symbols (TTY-aware) ──────────────────────────────────────
# All ANSI escapes resolve to empty strings when stdout is not a TTY
# (curl | bash, CI logs, redirected output) so the script stays grep-friendly.
# Override with NO_COLOR=1 to disable even on a TTY.
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && command -v tput >/dev/null 2>&1; then
    C_RESET="$(tput sgr0)"
    C_BOLD="$(tput bold)"
    C_DIM="$(tput dim 2>/dev/null || printf '\033[2m')"
    C_RED="$(tput setaf 1)"
    C_GREEN="$(tput setaf 2)"
    C_YELLOW="$(tput setaf 3)"
    C_BLUE="$(tput setaf 4)"
    C_MAGENTA="$(tput setaf 5)"
    C_CYAN="$(tput setaf 6)"
    SYM_OK="✓"
    SYM_ERR="✗"
    SYM_WARN="⚠"
    SYM_INFO="→"
    SYM_STEP="▶"
    SYM_PEND="◯"
else
    C_RESET=""; C_BOLD=""; C_DIM=""; C_RED=""; C_GREEN=""
    C_YELLOW=""; C_BLUE=""; C_MAGENTA=""; C_CYAN=""
    SYM_OK="[ok]"; SYM_ERR="[x]"; SYM_WARN="[!]"
    SYM_INFO="->"; SYM_STEP=">"; SYM_PEND="o"
fi

# ── Helpers ────────────────────────────────────────────────────────────────
die()  { printf "  ${C_RED}${C_BOLD}%s${C_RESET} %s\n" "$SYM_ERR" "$*" >&2; exit 1; }
info() { printf "    ${C_DIM}%s${C_RESET} %s\n" "$SYM_INFO" "$*"; }
ok()   { printf "    ${C_GREEN}%s${C_RESET} %s\n" "$SYM_OK" "$*"; }
warn() { printf "    ${C_YELLOW}%s${C_RESET} %s\n" "$SYM_WARN" "$*"; }

# Section header — used by [N/6] step lines.
step() {
    local num="$1" total="$2" title="$3"
    printf "\n  ${C_BOLD}${C_CYAN}%s${C_RESET} ${C_BOLD}[%s/%s]${C_RESET} ${C_BOLD}%s${C_RESET}\n" \
        "$SYM_STEP" "$num" "$total" "$title"
}

# Bash spinner — runs while a backgrounded PID is alive. No-op (waits silently)
# when stdout isn't a TTY so curl | bash logs don't fill with spinner cruft.
# Usage:
#     long_command &
#     spin $! "Doing the thing"
spin() {
    local pid=$1 msg=$2
    if [ ! -t 1 ]; then
        wait "$pid"
        return $?
    fi
    local frames='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'
    local i=0
    # Hide cursor for the duration of the spin.
    printf '\033[?25l'
    while kill -0 "$pid" 2>/dev/null; do
        printf "\r    ${C_CYAN}%s${C_RESET} %s " "${frames:$((i % ${#frames})):1}" "$msg"
        i=$((i+1))
        sleep 0.08
    done
    # Reap and capture exit code.
    wait "$pid"
    local rc=$?
    # Show cursor + clear the spinner line.
    printf '\r\033[K\033[?25h'
    return $rc
}

require() {
    command -v "$1" >/dev/null 2>&1 || die "$1 is required but not installed"
}

# ── Banner ─────────────────────────────────────────────────────────────────
banner() {
    printf "\n"
    printf "  ${C_BOLD}${C_CYAN}╭─────────────────────────────────────────╮${C_RESET}\n"
    printf "  ${C_BOLD}${C_CYAN}│${C_RESET}  ${C_BOLD}Hermes-Relay Installer${C_RESET}                 ${C_BOLD}${C_CYAN}│${C_RESET}\n"
    printf "  ${C_BOLD}${C_CYAN}│${C_RESET}  ${C_DIM}Phase 3 — Bridge channel + status tool${C_RESET}  ${C_BOLD}${C_CYAN}│${C_RESET}\n"
    printf "  ${C_BOLD}${C_CYAN}╰─────────────────────────────────────────╯${C_RESET}\n"
    printf "\n"
    printf "    ${C_DIM}%-12s${C_RESET} %s\n" "Repo:"     "$REPO_URL ${C_DIM}($BRANCH)${C_RESET}"
    printf "    ${C_DIM}%-12s${C_RESET} %s\n" "Target:"   "$RELAY_HOME"
    printf "    ${C_DIM}%-12s${C_RESET} %s\n" "Venv:"     "$VENV_PY"
    printf "    ${C_DIM}%-12s${C_RESET} %s\n" "Hermes:"   "$HERMES_CONFIG"
}

banner

require git
require python3

if [ ! -d "$HERMES_HOME/hermes-agent" ]; then
    die "hermes-agent not found at $HERMES_HOME/hermes-agent — install Hermes first"
fi

if [ ! -x "$VENV_PY" ]; then
    die "hermes-agent venv Python not found at $VENV_PY — reinstall hermes-agent or set HERMES_VENV_PY"
fi

# ── 1/6  Clone or update the repo ──────────────────────────────────────────
step 1 6 "Syncing repo"
if [ -d "$RELAY_HOME/.git" ]; then
    # Widen the remote refspec to the standard "all branches" form BEFORE
    # fetching. Pre-v0.4 installs used `git clone --single-branch` which
    # pinned the refspec to `+refs/heads/main:refs/remotes/origin/main` —
    # that prevents `git checkout feature/...` from working even after an
    # explicit `git fetch origin feature/...`, because no remote-tracking
    # ref gets created. Widening the refspec is idempotent: sets it to
    # the standard form if narrow, no-op if already wide. This lets
    # `--branch <name>` and `HERMES_RELAY_BRANCH=<name> hermes-relay-update`
    # work cleanly on clones that pre-date v0.4.
    (cd "$RELAY_HOME" \
        && git config remote.origin.fetch "+refs/heads/*:refs/remotes/origin/*" \
        && git fetch --quiet origin "$BRANCH" \
        && git checkout --quiet "$BRANCH" \
        && git pull --ff-only --quiet) \
        || die "Failed to update existing clone at $RELAY_HOME"
    ok "Updated existing clone at $RELAY_HOME"
elif [ -e "$RELAY_HOME" ]; then
    die "$RELAY_HOME exists but is not a git clone — remove it or set HERMES_RELAY_HOME to a different path"
else
    mkdir -p "$(dirname "$RELAY_HOME")"
    # Deliberately NOT passing --single-branch here: we want future branch
    # switches (`--branch feature/foo`) to work without a retroactive
    # refspec fix. The clone is a few KB larger but the ergonomic win is
    # much bigger than the disk cost.
    git clone --quiet --branch "$BRANCH" "$REPO_URL" "$RELAY_HOME" \
        || die "Failed to clone $REPO_URL into $RELAY_HOME"
    ok "Cloned $REPO_URL to $RELAY_HOME"
fi

# ── 2/6  Install Python package editable into the hermes venv ──────────────
step 2 6 "Installing plugin into hermes venv (editable)"
"$VENV_PY" -m pip install --quiet --upgrade pip >/dev/null 2>&1 || true
# Run the long pip install in the background and spin while we wait — keeps
# the user oriented during the 5-30s install. spin() falls back to a silent
# wait when stdout isn't a TTY (curl | bash) so log capture stays clean.
(
    "$VENV_PY" -m pip install --quiet -e "$RELAY_HOME" >/dev/null 2>&1
) &
spin $! "pip install -e $(basename "$RELAY_HOME")" \
    || die "pip install -e $RELAY_HOME failed"
ok "Installed $("$VENV_PY" -m pip show hermes-relay 2>/dev/null | awk '/^Name:/{n=$2}/^Version:/{print n" "$2}')"

# Drop the bootstrap .pth into the venv's site-packages so Python loads
# `hermes_relay_bootstrap` at interpreter startup. This is what allows the
# plugin to inject `/api/sessions/*` (and friends) onto the gateway's
# aiohttp app at startup. The .pth has to live directly in site-packages —
# setuptools' editable install does NOT ship data-files there, so we drop
# it manually here. Idempotent: a second run overwrites the same file.
#
# Removal: the bootstrap package and its .pth come out together via
# `bash uninstall.sh`. The bootstrap also feature-detects on route paths,
# so it cleanly no-ops on hermes-agent builds that already serve the same
# routes natively — leaving it installed is safe across all versions.
SITE_PKGS="$("$VENV_PY" -c 'import site; print(site.getsitepackages()[0])' 2>/dev/null || true)"
PTH_SRC="$RELAY_HOME/hermes_relay_bootstrap.pth"
if [ -n "$SITE_PKGS" ] && [ -d "$SITE_PKGS" ] && [ -f "$PTH_SRC" ]; then
    cp "$PTH_SRC" "$SITE_PKGS/hermes_relay_bootstrap.pth"
    ok "Installed bootstrap .pth → $SITE_PKGS/hermes_relay_bootstrap.pth"
else
    info "  Could not determine venv site-packages — bootstrap .pth NOT installed"
    info "  This means /api/sessions/* won't be injected onto vanilla upstream"
    info "  hermes-agent. Manually copy $PTH_SRC into your venv's site-packages."
fi

# ── 3/6  Symlink plugin into Hermes plugin dir ─────────────────────────────
step 3 6 "Registering plugin with Hermes"
mkdir -p "$(dirname "$PLUGIN_LINK")"
# Remove an old install (dir, symlink, or mismatched target)
if [ -L "$PLUGIN_LINK" ] || [ -e "$PLUGIN_LINK" ]; then
    rm -rf "$PLUGIN_LINK"
fi
ln -s "$RELAY_HOME/plugin" "$PLUGIN_LINK"
ok "Symlinked $PLUGIN_LINK → $RELAY_HOME/plugin"

# Also remove any deprecated hand-installs
for stale in "$HERMES_HOME/plugins/hermes-android" "$HERMES_HOME/hermes-agent/plugins/hermes-android" "$HERMES_HOME/hermes-agent/plugins/hermes-relay"; do
    if [ -L "$stale" ] || [ -e "$stale" ]; then
        rm -rf "$stale"
        ok "Removed stale $stale"
    fi
done

# Dashboard plugin toggle. The hermes-agent dashboard auto-discovers plugins
# via `dashboard/manifest.json`. We flip visibility by renaming the manifest
# file — no separate config lives anywhere else, and the same state is
# observable by `ls plugin/dashboard/` so uninstall reasoning is trivial.
DASHBOARD_MANIFEST_ACTIVE="$RELAY_HOME/plugin/dashboard/manifest.json"
DASHBOARD_MANIFEST_DISABLED="$RELAY_HOME/plugin/dashboard/manifest.json.disabled"
if [ -d "$RELAY_HOME/plugin/dashboard" ]; then
    case "$DASHBOARD_PLUGIN" in
        yes)
            if [ ! -f "$DASHBOARD_MANIFEST_ACTIVE" ] && [ -f "$DASHBOARD_MANIFEST_DISABLED" ]; then
                mv "$DASHBOARD_MANIFEST_DISABLED" "$DASHBOARD_MANIFEST_ACTIVE"
            fi
            if [ -f "$DASHBOARD_MANIFEST_ACTIVE" ]; then
                ok "Dashboard plugin enabled — relay tab will appear in the hermes-agent dashboard"
            else
                info "  Dashboard plugin manifest missing — skipped (expected when installing an older branch)"
            fi
            ;;
        no)
            if [ -f "$DASHBOARD_MANIFEST_ACTIVE" ]; then
                mv "$DASHBOARD_MANIFEST_ACTIVE" "$DASHBOARD_MANIFEST_DISABLED"
            fi
            ok "Dashboard plugin disabled — hermes-agent dashboard will not load the relay tab"
            ;;
    esac
    # Best-effort rescan so the toggle takes effect without a dashboard
    # restart. Silent if the dashboard isn't running or isn't local.
    if command -v curl >/dev/null 2>&1; then
        for port in 9119 9100 9000; do
            if curl -sf -m 2 -X GET "http://127.0.0.1:${port}/api/dashboard/plugins/rescan" >/dev/null 2>&1; then
                info "  Triggered dashboard rescan on :${port}"
                break
            fi
        done
    fi
fi

# ── 4/6  Register skills dir in Hermes config (external_dirs) ──────────────
step 4 6 "Registering skills directory"
"$VENV_PY" - <<PY
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("  [x] pyyaml not installed in hermes venv — this should have come with the plugin")
    sys.exit(1)

cfg_path = Path("$HERMES_CONFIG")
target = "$SKILLS_DIR_IN_REPO"

data: dict = {}
if cfg_path.is_file():
    try:
        data = yaml.safe_load(cfg_path.read_text(encoding="utf-8")) or {}
    except Exception as exc:
        print(f"  [x] Failed to parse {cfg_path}: {exc}")
        sys.exit(1)

if not isinstance(data, dict):
    data = {}

skills_section = data.get("skills")
if not isinstance(skills_section, dict):
    skills_section = {}
    data["skills"] = skills_section

external_dirs = skills_section.get("external_dirs")
if not isinstance(external_dirs, list):
    external_dirs = []
    skills_section["external_dirs"] = external_dirs

# Idempotent — skip if already present (match by expanded path)
expanded_targets = {str(Path(p).expanduser().resolve()) for p in external_dirs if isinstance(p, str)}
if str(Path(target).expanduser().resolve()) in expanded_targets:
    print(f"  [ok] external_dirs already contains {target}")
else:
    external_dirs.append(target)
    cfg_path.parent.mkdir(parents=True, exist_ok=True)
    # Save a .bak next to the original — yaml.safe_dump round-trips lose
    # inline comments and key ordering, so users with hand-edited configs
    # can restore from the backup if the new layout isn't what they want.
    if cfg_path.is_file():
        backup = cfg_path.with_suffix(cfg_path.suffix + ".bak")
        backup.write_text(cfg_path.read_text(encoding="utf-8"), encoding="utf-8")
        print(f"  [ok] Backed up existing config to {backup}")
    cfg_path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    print(f"  [ok] Added {target} to skills.external_dirs in {cfg_path}")
PY

# ── 5/6  Install hermes-pair + hermes-status + hermes-relay-update shims ──
step 5 6 "Installing hermes-pair + hermes-status + hermes-relay-update shims"
mkdir -p "$(dirname "$SHIM_PATH")"
cat > "$SHIM_PATH" <<SHIM
#!/usr/bin/env bash
# Hermes-Relay pairing shim — routes to \`python -m plugin.pair\` inside the
# hermes-agent venv where the hermes-relay plugin is installed.
#
# Also available: /hermes-relay-pair slash command in any Hermes chat session.
#
# Override the venv python path with \$HERMES_VENV_PY if needed.

HERMES_VENV_PY="\${HERMES_VENV_PY:-\$HOME/.hermes/hermes-agent/venv/bin/python}"
if [ ! -x "\$HERMES_VENV_PY" ]; then
    echo "hermes-pair: cannot find hermes venv python at \$HERMES_VENV_PY" >&2
    echo "hermes-pair: set HERMES_VENV_PY or reinstall hermes-agent" >&2
    exit 1
fi
exec "\$HERMES_VENV_PY" -m plugin.pair "\$@"
SHIM
chmod +x "$SHIM_PATH"
ok "Installed $SHIM_PATH"

cat > "$STATUS_SHIM_PATH" <<SHIM
#!/usr/bin/env bash
# Hermes-Relay phone-status shim — routes to \`python -m plugin.status\`
# inside the hermes-agent venv where the hermes-relay plugin is installed.
#
# Also available: /hermes-relay-status slash command in any Hermes chat session.
#
# Override the venv python path with \$HERMES_VENV_PY if needed.

HERMES_VENV_PY="\${HERMES_VENV_PY:-\$HOME/.hermes/hermes-agent/venv/bin/python}"
if [ ! -x "\$HERMES_VENV_PY" ]; then
    echo "hermes-status: cannot find hermes venv python at \$HERMES_VENV_PY" >&2
    echo "hermes-status: set HERMES_VENV_PY or reinstall hermes-agent" >&2
    exit 1
fi
exec "\$HERMES_VENV_PY" -m plugin.status "\$@"
SHIM
chmod +x "$STATUS_SHIM_PATH"
ok "Installed $STATUS_SHIM_PATH"

# hermes-relay-update — discoverable name for "update Hermes-Relay". Just
# re-runs the canonical curl-pipe installer (which is idempotent and does
# all the work). Forwards any args to install.sh via `bash -s --` so e.g.
# \`hermes-relay-update --restart-gateway\` works once we ever add that flag.
# Honors the existing HERMES_RELAY_RESTART_GATEWAY / HERMES_RELAY_NO_RESTART_GATEWAY
# env vars without any wrapping logic — they pass through naturally.
cat > "$UPDATE_SHIM_PATH" <<'SHIM'
#!/usr/bin/env bash
# Hermes-Relay updater shim — re-runs the canonical one-line installer.
#
# Common usage:
#   hermes-relay-update                                    # update to latest main
#   hermes-relay-update --branch feature/bridge-feature-expansion  # switch to a branch
#   hermes-relay-update --branch main                      # return to main
#   HERMES_RELAY_RESTART_GATEWAY=1 hermes-relay-update     # also restart gateway
#
# Bootstrap caveat (only relevant before --branch lands on main):
#   The shim normally fetches install.sh from main, so --branch only works
#   if main's install.sh already understands the flag (it does as of v0.4.0).
#   To install a feature branch BEFORE it has been merged to main, override
#   the install.sh URL with HERMES_RELAY_INSTALL_URL:
#
#     HERMES_RELAY_INSTALL_URL=https://raw.githubusercontent.com/Codename-11/hermes-relay/feature/foo/install.sh \
#       hermes-relay-update --branch feature/foo
#
#   This is a one-shot escape hatch — after the install lands the host has
#   the new install.sh on disk + the regular shim works for all subsequent
#   updates, including switching back to main.
#
# install.sh is fully idempotent — it pulls the requested branch (default
# main), refreshes the editable pip install, recreates both shims, restarts
# hermes-relay, and prompts before restarting hermes-gateway. Set
# HERMES_RELAY_RESTART_GATEWAY=1 to opt into the gateway restart non-
# interactively.
INSTALL_URL="${HERMES_RELAY_INSTALL_URL:-https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh}"
exec curl -fsSL "$INSTALL_URL" | bash -s -- "$@"
SHIM
chmod +x "$UPDATE_SHIM_PATH"
ok "Installed $UPDATE_SHIM_PATH"

# ── 6/6  Install systemd user service (optional) ───────────────────────────
# Idempotent: safe to re-run. Skipped gracefully on hosts without a systemd
# user session (macOS, bare chroots, WSL without systemd, containers, etc).
# The relay still runs fine on those — users just start it manually or via
# their preferred process supervisor.
#
# The unit has NO EnvironmentFile= — plugin/relay/_env_bootstrap.py loads
# ~/.hermes/.env into os.environ on startup, mirroring how the gateway
# handles API keys. Any future `systemctl --user restart hermes-relay`
# picks up fresh values from .env automatically.
step 6 6 "Installing systemd user service"

if [ -n "${HERMES_RELAY_NO_SYSTEMD:-}" ]; then
    info "  HERMES_RELAY_NO_SYSTEMD set — skipping"
elif ! command -v systemctl >/dev/null 2>&1; then
    info "  systemctl not found — skipping (run manually: $VENV_PY -m plugin.relay --no-ssl)"
elif ! systemctl --user show-environment >/dev/null 2>&1; then
    info "  systemd user session not available — skipping"
    info "  Run manually: $VENV_PY -m plugin.relay --no-ssl"
elif [ ! -f "$SERVICE_SRC" ]; then
    info "  Service template not found at $SERVICE_SRC — skipping"
else
    mkdir -p "$SYSTEMD_USER_DIR"
    cp "$SERVICE_SRC" "$SERVICE_DST"
    ok "Wrote $SERVICE_DST"

    systemctl --user daemon-reload >/dev/null 2>&1 || true

    # If a nohup-launched relay is holding :8767, stop+disable will fail
    # to bind on start. Warn the user to kill it first rather than racing
    # the installer against their manual process.
    if pgrep -f "python -m plugin.relay" >/dev/null 2>&1 && \
       ! systemctl --user is-active hermes-relay.service >/dev/null 2>&1; then
        info "  A manual 'python -m plugin.relay' is already running."
        info "  Stop it first:  pkill -f 'python -m plugin.relay'"
        info "  Then:           systemctl --user enable --now hermes-relay.service"
    else
        # Was the service already running? If yes we MUST `restart` it
        # explicitly — `enable --now` is a no-op on already-active services
        # and the editable-install code refresh would never reach the live
        # process. (Spent way too long debugging this on Docker-Server
        # 2026-04-12 — every install.sh run looked successful but the live
        # relay kept serving stale code from before the last git pull.)
        if systemctl --user is-active hermes-relay.service >/dev/null 2>&1; then
            ( systemctl --user restart hermes-relay.service >/dev/null 2>&1 ) &
            if spin $! "Restarting hermes-relay (already active — picking up new code)"; then
                ok "hermes-relay restarted — new code is live"
            else
                warn "Could not restart hermes-relay automatically"
                info "Check:  journalctl --user -u hermes-relay -n 30 --no-pager"
            fi
        elif systemctl --user enable --now hermes-relay.service >/dev/null 2>&1; then
            ok "Enabled + started hermes-relay.service"
            # Give aiohttp a second to bind before checking state.
            sleep 1
            if systemctl --user is-active hermes-relay.service >/dev/null 2>&1; then
                ok "hermes-relay is running"
            else
                warn "hermes-relay failed to start"
                info "Check:  journalctl --user -u hermes-relay -n 30 --no-pager"
            fi
        else
            warn "Could not enable hermes-relay.service"
            info "Check:  systemctl --user status hermes-relay.service"
        fi
    fi

    info "  Linger tip: 'loginctl enable-linger $USER' keeps it running after logout"
fi

# ── 6b/6  Offer (don't force) hermes-gateway restart ──────────────────────
# The hermes-agent gateway caches plugin tools (android_*, etc.) and skills
# at import time, so a fresh `git pull` of the plugin does NOT take effect
# until the gateway re-imports. We could auto-restart it, but that interrupts
# any active chat sessions and feels presumptuous since we don't own the
# gateway process. Instead:
#
#   - If --restart-gateway was passed, restart unconditionally (scripted use)
#   - If --no-restart-gateway was passed, skip silently
#   - If stdin is a TTY (interactive), prompt with default "no"
#   - If stdin is NOT a TTY (curl | bash), print a clear hint and skip
#
# Either way, the user is in control. No surprise restarts.
if [ -z "${HERMES_RELAY_NO_SYSTEMD:-}" ] \
   && [ -z "${HERMES_RELAY_NO_RESTART_GATEWAY:-}" ] \
   && command -v systemctl >/dev/null 2>&1 \
   && systemctl --user is-active hermes-gateway.service >/dev/null 2>&1; then

    do_restart=""
    if [ -n "${HERMES_RELAY_RESTART_GATEWAY:-}" ]; then
        do_restart="yes"
    elif [ -t 0 ]; then
        printf "\n"
        printf "    ${C_YELLOW}%s${C_RESET} %s\n" "$SYM_WARN" "${C_BOLD}hermes-gateway is running${C_RESET}"
        printf "    ${C_DIM}%s${C_RESET}\n" "Restarting it lets the gateway re-import the updated plugin code"
        printf "    ${C_DIM}%s${C_RESET}\n" "(new tools, skills, etc). Active chat sessions are interrupted for ~2s."
        printf "\n    ${C_BOLD}Restart hermes-gateway now?${C_RESET} ${C_DIM}[y/N]${C_RESET} "
        read -r reply </dev/tty || reply=""
        case "$reply" in
            [Yy]*) do_restart="yes" ;;
            *)     do_restart="" ;;
        esac
    else
        warn "hermes-gateway is running with stale plugin imports"
        info "To re-import: ${C_BOLD}systemctl --user restart hermes-gateway${C_RESET}"
        info "Or re-run with HERMES_RELAY_RESTART_GATEWAY=1 to do it automatically"
    fi

    if [ -n "$do_restart" ]; then
        ( systemctl --user restart hermes-gateway.service >/dev/null 2>&1 ) &
        if spin $! "Restarting hermes-gateway"; then
            ok "hermes-gateway restarted — new plugin tools are live"
        else
            warn "Could not restart hermes-gateway automatically"
            info "Run manually: ${C_BOLD}systemctl --user restart hermes-gateway${C_RESET}"
        fi
    fi
fi

# ── Done ───────────────────────────────────────────────────────────────────
printf "\n"
printf "  ${C_BOLD}${C_GREEN}╭─────────────────────────────────────────╮${C_RESET}\n"
printf "  ${C_BOLD}${C_GREEN}│${C_RESET}  ${C_BOLD}${SYM_OK} Hermes-Relay installed${C_RESET}                ${C_BOLD}${C_GREEN}│${C_RESET}\n"
printf "  ${C_BOLD}${C_GREEN}╰─────────────────────────────────────────╯${C_RESET}\n"
printf "\n"
printf "  ${C_BOLD}${C_CYAN}Pair your phone${C_RESET}\n"
printf "    ${C_DIM}%s${C_RESET} ${C_BOLD}/hermes-relay-pair${C_RESET}        ${C_DIM}# from any Hermes chat${C_RESET}\n" "$SYM_INFO"
printf "    ${C_DIM}%s${C_RESET} ${C_BOLD}hermes-pair${C_RESET}               ${C_DIM}# from any shell${C_RESET}\n" "$SYM_INFO"
printf "    ${C_DIM}%s${C_RESET} ${C_BOLD}hermes-status${C_RESET}             ${C_DIM}# show live phone state${C_RESET}\n" "$SYM_INFO"
printf "\n"
printf "  ${C_BOLD}${C_CYAN}Self-setup / troubleshoot${C_RESET}\n"
printf "    ${C_DIM}%s${C_RESET} ${C_BOLD}/hermes-relay-self-setup${C_RESET}  ${C_DIM}# agent walks you through verify, re-pair, fix${C_RESET}\n" "$SYM_INFO"
printf "\n"
printf "  ${C_BOLD}${C_CYAN}Update later${C_RESET} ${C_DIM}(idempotent — pick whichever you remember)${C_RESET}\n"
printf "    ${C_DIM}%s${C_RESET} ${C_BOLD}hermes-relay-update${C_RESET}             ${C_DIM}# shortest path${C_RESET}\n" "$SYM_INFO"
printf "    ${C_DIM}%s${C_RESET} ${C_BOLD}curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash${C_RESET}\n" "$SYM_INFO"
printf "\n"
printf "  ${C_BOLD}${C_CYAN}Manage the relay service${C_RESET}\n"
printf "    ${C_DIM}%s${C_RESET} ${C_BOLD}systemctl --user status hermes-relay${C_RESET}\n" "$SYM_INFO"
printf "    ${C_DIM}%s${C_RESET} ${C_BOLD}systemctl --user restart hermes-relay${C_RESET}\n" "$SYM_INFO"
printf "    ${C_DIM}%s${C_RESET} ${C_BOLD}journalctl --user -u hermes-relay -f${C_RESET}\n" "$SYM_INFO"
printf "\n"
printf "  ${C_BOLD}${C_CYAN}Uninstall${C_RESET}\n"
printf "    ${C_DIM}%s${C_RESET} ${C_BOLD}bash $RELAY_HOME/uninstall.sh${C_RESET}\n" "$SYM_INFO"
printf "    ${C_DIM}%s${C_RESET} ${C_BOLD}bash $RELAY_HOME/uninstall.sh --dry-run${C_RESET}  ${C_DIM}# preview only${C_RESET}\n" "$SYM_INFO"
printf "\n"
printf "  ${C_DIM}Scan the QR from the Hermes-Relay app: Settings → Connection → Scan Pairing QR${C_RESET}\n"
printf "\n"
