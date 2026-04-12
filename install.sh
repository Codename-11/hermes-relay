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
#   5. A shell shim at ~/.local/bin/hermes-pair that execs
#      `<venv>/python -m plugin.pair "$@"` — the canonical shell-side entry
#      point while the upstream `hermes pair` plugin CLI path is blocked
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
# Overrides:
#   HERMES_RELAY_HOME       Target directory (default: ~/.hermes/hermes-relay)
#   HERMES_RELAY_BRANCH     Git branch to install (default: main)
#   HERMES_VENV_PY          Path to hermes-agent venv python
#                           (default: ~/.hermes/hermes-agent/venv/bin/python)
#   HERMES_HOME             Hermes config home (default: ~/.hermes)
#   HERMES_RELAY_NO_SYSTEMD Skip step [6/6] even if systemd is available
#                           (set to any non-empty value)

set -euo pipefail

# ── Config ─────────────────────────────────────────────────────────────────
REPO_URL="https://github.com/Codename-11/hermes-relay.git"
BRANCH="${HERMES_RELAY_BRANCH:-main}"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
RELAY_HOME="${HERMES_RELAY_HOME:-$HERMES_HOME/hermes-relay}"
VENV_PY="${HERMES_VENV_PY:-$HERMES_HOME/hermes-agent/venv/bin/python}"
PLUGIN_LINK="$HERMES_HOME/plugins/hermes-relay"
SKILLS_DIR_IN_REPO="$RELAY_HOME/skills"
HERMES_CONFIG="$HERMES_HOME/config.yaml"
SHIM_PATH="$HOME/.local/bin/hermes-pair"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
SERVICE_SRC="$RELAY_HOME/relay_server/hermes-relay.service"
SERVICE_DST="$SYSTEMD_USER_DIR/hermes-relay.service"

# ── Helpers ────────────────────────────────────────────────────────────────
die()  { echo "  [x] $*" >&2; exit 1; }
info() { echo "  $*"; }
ok()   { echo "  [ok] $*"; }

require() {
    command -v "$1" >/dev/null 2>&1 || die "$1 is required but not installed"
}

# ── Preflight ──────────────────────────────────────────────────────────────
echo ""
echo "  Hermes-Relay Installer"
echo "  ----------------------"
echo "  Repo:       $REPO_URL ($BRANCH)"
echo "  Target:     $RELAY_HOME"
echo "  Venv:       $VENV_PY"
echo "  Hermes cfg: $HERMES_CONFIG"
echo ""

require git
require python3

if [ ! -d "$HERMES_HOME/hermes-agent" ]; then
    die "hermes-agent not found at $HERMES_HOME/hermes-agent — install Hermes first"
fi

if [ ! -x "$VENV_PY" ]; then
    die "hermes-agent venv Python not found at $VENV_PY — reinstall hermes-agent or set HERMES_VENV_PY"
fi

# ── 1/6  Clone or update the repo ──────────────────────────────────────────
info "[1/6] Syncing repo..."
if [ -d "$RELAY_HOME/.git" ]; then
    (cd "$RELAY_HOME" && git fetch --quiet origin "$BRANCH" && git checkout --quiet "$BRANCH" && git pull --ff-only --quiet) \
        || die "Failed to update existing clone at $RELAY_HOME"
    ok "Updated existing clone at $RELAY_HOME"
elif [ -e "$RELAY_HOME" ]; then
    die "$RELAY_HOME exists but is not a git clone — remove it or set HERMES_RELAY_HOME to a different path"
else
    mkdir -p "$(dirname "$RELAY_HOME")"
    git clone --quiet --branch "$BRANCH" --single-branch "$REPO_URL" "$RELAY_HOME" \
        || die "Failed to clone $REPO_URL into $RELAY_HOME"
    ok "Cloned $REPO_URL to $RELAY_HOME"
fi

# ── 2/6  Install Python package editable into the hermes venv ──────────────
info "[2/6] Installing plugin into hermes venv (editable)..."
"$VENV_PY" -m pip install --quiet --upgrade pip >/dev/null 2>&1 || true
"$VENV_PY" -m pip install --quiet -e "$RELAY_HOME" \
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
info "[3/6] Registering plugin with Hermes..."
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

# ── 4/6  Register skills dir in Hermes config (external_dirs) ──────────────
info "[4/6] Registering skills directory..."
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

# ── 5/6  Install the hermes-pair shell shim ────────────────────────────────
info "[5/6] Installing hermes-pair shell shim..."
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
info "[6/6] Installing systemd user service..."

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
        if systemctl --user enable --now hermes-relay.service >/dev/null 2>&1; then
            ok "Enabled + started hermes-relay.service"
            # Give aiohttp a second to bind before checking state.
            sleep 1
            if systemctl --user is-active hermes-relay.service >/dev/null 2>&1; then
                ok "hermes-relay is running"
            else
                info "  hermes-relay failed to start"
                info "  Check:  journalctl --user -u hermes-relay -n 30 --no-pager"
            fi
        else
            info "  Could not enable hermes-relay.service"
            info "  Check:  systemctl --user status hermes-relay.service"
        fi
    fi

    info "  Linger tip: 'loginctl enable-linger $USER' keeps it running after logout"
fi

# ── Done ───────────────────────────────────────────────────────────────────
echo ""
echo "  [OK] Hermes-Relay installed."
echo ""
echo "  Three ways to pair your phone:"
echo ""
echo "    1. From a Hermes chat session:"
echo "         /hermes-relay-pair"
echo ""
echo "    2. From any shell:"
echo "         hermes-pair"
echo ""
echo "    3. Directly via the venv Python:"
echo "         $VENV_PY -m plugin.pair"
echo ""
echo "  To update later:"
echo "    cd $RELAY_HOME && git pull"
echo "    bash $RELAY_HOME/install.sh           # re-runs all steps idempotently"
echo "    systemctl --user restart hermes-relay   # if installed as a service"
echo "    systemctl --user restart hermes-gateway # if you changed bootstrap code"
echo ""
echo "  To uninstall:"
echo "    bash $RELAY_HOME/uninstall.sh"
echo "    bash $RELAY_HOME/uninstall.sh --dry-run  # preview without changing anything"
echo ""
echo "  Manage the relay service (if installed):"
echo "    systemctl --user status hermes-relay"
echo "    systemctl --user restart hermes-relay"
echo "    journalctl --user -u hermes-relay -f"
echo ""
echo "  Scan the resulting QR from the Hermes-Relay app's Settings screen"
echo "  (Connection → Scan Pairing QR) or during first-run onboarding."
echo ""
