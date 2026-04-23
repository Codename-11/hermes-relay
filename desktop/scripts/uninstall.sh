#!/usr/bin/env bash
# hermes-relay desktop CLI uninstaller — macOS / Linux.
#
#   curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/uninstall.sh | sh
#
# Reverses install.sh. Three tiers:
#
#   (default)    Remove the binary only. Preserves ~/.hermes/remote-sessions.json
#                so a future re-install pairs seamlessly.
#   --purge      Also delete ~/.hermes/remote-sessions.json (bearer tokens, cert
#                pins, tools-consent flag). This file is SHARED with the Ink TUI
#                and Android tooling — wiping it affects those surfaces too.
#   --service    Stub: daemon service installers aren't shipped yet. Prints the
#                paths a future install would use so you can remove a manually
#                crafted unit yourself.
#
# Tiers combine: `--purge --service` runs both.
#
# Override install dir (matches install.sh):
#   HERMES_RELAY_INSTALL_DIR=/opt/hermes curl -fsSL ... | sh -s -- --purge
#
# install.sh never modifies your shell rc; neither does this. If you added
# $INSTALL_DIR to your PATH manually, remove that line from your shell rc.

set -eu

INSTALL_DIR="${HERMES_RELAY_INSTALL_DIR:-$HOME/.hermes/bin}"
SESSIONS_FILE="$HOME/.hermes/remote-sessions.json"

PURGE=0
SERVICE=0
BINARY_ONLY=0

for arg in "$@"; do
  case "$arg" in
    --purge)        PURGE=1 ;;
    --service)      SERVICE=1 ;;
    --binary-only)  BINARY_ONLY=1 ;;
    -h|--help)
      sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      printf 'uninstall.sh: unknown argument: %s\n' "$arg" >&2
      exit 1
      ;;
  esac
done

say() { printf '  %s\n' "$*"; }
die() { printf 'uninstall.sh: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

say "Hermes-Relay desktop CLI uninstaller"
say "  install  : $INSTALL_DIR"
if [ "$PURGE" -eq 1 ]; then
  say "  mode     : --purge (binary + session data)"
elif [ "$SERVICE" -eq 1 ] && [ "$BINARY_ONLY" -eq 0 ]; then
  say "  mode     : --service (binary + service stub)"
else
  say "  mode     : binary-only (preserves ~/.hermes/remote-sessions.json)"
fi
say ""

# -- Tier 1: binary ---------------------------------------------------------

target="$INSTALL_DIR/hermes-relay"
if [ -f "$target" ] || [ -L "$target" ]; then
  rm -f "$target" || die "failed to remove $target (permission denied?)"
  say "-> removed $target"
else
  say "-> no binary at $target (already removed?)"
fi

# Clean up an empty install dir we created, but never touch user-created content.
if [ -d "$INSTALL_DIR" ]; then
  if [ -z "$(ls -A "$INSTALL_DIR" 2>/dev/null)" ]; then
    rmdir "$INSTALL_DIR" 2>/dev/null && say "-> removed empty $INSTALL_DIR" || true
  fi
fi

# PATH note — install.sh never mutated shell rc, so neither do we.
case ":$PATH:" in
  *":$INSTALL_DIR:"*)
    say ""
    say "   Note: $INSTALL_DIR is still on your PATH for this shell session."
    say "   If you added it manually to your shell rc, remove that line from:"
    case "${SHELL:-}" in
      */zsh)  say "     ~/.zshrc" ;;
      */bash) say "     ~/.bashrc" ;;
      */fish) say "     ~/.config/fish/config.fish" ;;
      *)      say "     your shell rc file" ;;
    esac
    ;;
esac

# -- Tier 2: purge session data --------------------------------------------

if [ "$PURGE" -eq 1 ]; then
  say ""
  if [ -f "$SESSIONS_FILE" ]; then
    size=""
    if have wc; then
      size=" ($(wc -c < "$SESSIONS_FILE" | tr -d ' ') bytes)"
    fi
    say "!! --purge: wiping $SESSIONS_FILE$size"
    say "   This file is SHARED with:"
    say "     - the Ink TUI (hermes-agent-tui-smoke)"
    say "     - the Hermes Android desktop tooling"
    say "   Those surfaces will lose their stored session tokens + cert pins too."
    rm -f "$SESSIONS_FILE" || die "failed to remove $SESSIONS_FILE"
    say "-> removed $SESSIONS_FILE"
  else
    say "-> --purge: no session file at $SESSIONS_FILE (already clean)"
  fi
fi

# -- Tier 3: service stub ---------------------------------------------------

if [ "$SERVICE" -eq 1 ]; then
  say ""
  say "   --service: daemon service installers are not yet shipped."
  say "   If you manually installed a service unit, remove it yourself:"
  say ""
  case "$(uname -s)" in
    Linux)
      say "     systemctl --user stop hermes-relay-daemon.service 2>/dev/null || true"
      say "     systemctl --user disable hermes-relay-daemon.service 2>/dev/null || true"
      say "     rm -f ~/.config/systemd/user/hermes-relay-daemon.service"
      say "     systemctl --user daemon-reload"
      ;;
    Darwin)
      say "     launchctl unload ~/Library/LaunchAgents/com.hermes.relay.daemon.plist 2>/dev/null || true"
      say "     rm -f ~/Library/LaunchAgents/com.hermes.relay.daemon.plist"
      ;;
    *)
      say "     ~/.config/systemd/user/hermes-relay-daemon.service  (Linux)"
      say "     ~/Library/LaunchAgents/com.hermes.relay.daemon.plist  (macOS)"
      ;;
  esac
fi

say ""
say "Removed. hermes-relay is gone. To reinstall:"
say "  curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh"
say ""
