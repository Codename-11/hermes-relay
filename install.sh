#!/usr/bin/env bash
# Hermes-Relay — one-line installer for the server-side plugin.
#
# Installs the `hermes-relay` plugin (14 android_* device tools + the
# `hermes pair` QR pairing CLI) into your local hermes-agent plugin directory.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
#
# After install, restart hermes and run:
#   hermes pair         # generate a QR code for the Hermes-Relay Android app
#   /plugins            # verify 14 android_* tools are registered
#
set -euo pipefail

# ── Config ──────────────────────────────────────────────────────────────
REPO="https://github.com/Codename-11/hermes-relay.git"
BRANCH="${HERMES_RELAY_BRANCH:-main}"
PLUGIN_NAME="hermes-relay"
PLUGIN_DIR="${HERMES_HOME:-$HOME/.hermes}/plugins/$PLUGIN_NAME"
TMP_DIR="$(mktemp -d)"

# ── Cleanup on exit ─────────────────────────────────────────────────────
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

# ── Preflight ───────────────────────────────────────────────────────────
die() { echo "  [x] $*" >&2; exit 1; }

command -v git >/dev/null 2>&1 || die "git is required but not installed"
command -v python3 >/dev/null 2>&1 || die "python3 is required but not installed"

echo ""
echo "  Hermes-Relay — Server Plugin Installer"
echo "  ----------------------------------------"
echo "  Target: $PLUGIN_DIR"
echo ""

# ── Clone repo (shallow) ────────────────────────────────────────────────
echo "  [1/3] Fetching plugin source..."
git clone --depth 1 --single-branch --branch "$BRANCH" "$REPO" "$TMP_DIR" 2>/dev/null \
    || die "Failed to clone $REPO"

# ── Copy plugin into place ──────────────────────────────────────────────
echo "  [2/3] Installing plugin..."
mkdir -p "$(dirname "$PLUGIN_DIR")"
rm -rf "$PLUGIN_DIR"
cp -r "$TMP_DIR/plugin" "$PLUGIN_DIR"

# ── Install Python dependencies ─────────────────────────────────────────
echo "  [3/3] Installing Python dependencies..."

install_pkg() {
    local pkg="$1"
    local module="${2:-$1}"
    if python3 -c "import $module" 2>/dev/null; then
        echo "        - $pkg already installed"
        return 0
    fi
    if pip install "$pkg" >/dev/null 2>&1 \
        || pip3 install "$pkg" >/dev/null 2>&1 \
        || python3 -m pip install "$pkg" >/dev/null 2>&1; then
        echo "        + $pkg installed"
    else
        echo "        ! $pkg install failed — run: pip install $pkg" >&2
    fi
}

install_pkg "requests"
install_pkg "aiohttp"
install_pkg "segno"

# ── Done ────────────────────────────────────────────────────────────────
echo ""
echo "  [OK] Hermes-Relay plugin installed."
echo ""
echo "  Next steps:"
echo "    1. Restart hermes"
echo "    2. Run:  hermes pair"
echo "    3. Scan the QR code with the Hermes-Relay Android app"
echo ""
echo "  Requires hermes-agent v0.8.0+ for the 'hermes pair' CLI command."
echo "  On older versions the 14 android_* tools still register."
echo ""
