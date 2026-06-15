#!/usr/bin/env sh
# Manage the optional `hermes` alias for hermes-relay on macOS/Linux.
#
# Status:
#   curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/hermes-alias.sh | sh
#
# Enable:
#   HERMES_RELAY_HERMES_ALIAS=enable curl -fsSL .../hermes-alias.sh | sh
#
# Disable:
#   HERMES_RELAY_HERMES_ALIAS=disable curl -fsSL .../hermes-alias.sh | sh

set -eu

INSTALL_DIR="${HERMES_RELAY_INSTALL_DIR:-$HOME/.hermes/bin}"
ACTION="${1:-${HERMES_RELAY_HERMES_ALIAS:-status}}"
ACTION="$(printf '%s' "$ACTION" | tr '[:upper:]' '[:lower:]')"
TARGET="$INSTALL_DIR/hermes-relay"
ALIAS="$INSTALL_DIR/hermes"

say() { printf '  %s\n' "$*"; }
die() { printf 'hermes-alias.sh: %s\n' "$*" >&2; exit 1; }

is_relay_alias() {
  [ -L "$ALIAS" ] && [ "$(readlink "$ALIAS")" = "hermes-relay" ]
}

show_status() {
  say "install dir : $INSTALL_DIR"
  if [ -f "$TARGET" ] || [ -L "$TARGET" ]; then
    say "relay binary: $TARGET"
  else
    say "relay binary: missing"
  fi
  if is_relay_alias; then
    say "hermes alias: enabled ($ALIAS -> hermes-relay)"
  elif [ -e "$ALIAS" ] || [ -L "$ALIAS" ]; then
    say "hermes alias: present but not managed by hermes-relay ($ALIAS)"
  else
    say "hermes alias: disabled"
  fi
  if command -v hermes >/dev/null 2>&1; then
    say "shell resolves: $(command -v hermes)"
  else
    say "shell resolves: not found in this process PATH"
  fi
}

case "$ACTION" in
  status|check|show)
    show_status
    ;;
  enable|on|true|yes|1)
    [ -f "$TARGET" ] || [ -L "$TARGET" ] || die "cannot enable alias because $TARGET does not exist; install the CLI first"
    if [ -e "$ALIAS" ] || [ -L "$ALIAS" ]; then
      is_relay_alias || die "refusing to overwrite existing non-hermes-relay command at $ALIAS"
    fi
    mkdir -p "$INSTALL_DIR"
    ln -sf "hermes-relay" "$ALIAS"
    say "enabled hermes alias at $ALIAS"
    ;;
  disable|off|false|no|0|remove)
    if is_relay_alias; then
      rm -f "$ALIAS"
      say "disabled hermes alias by removing $ALIAS"
    elif [ -e "$ALIAS" ] || [ -L "$ALIAS" ]; then
      say "left existing non-hermes-relay command untouched at $ALIAS"
    else
      say "hermes alias already disabled"
    fi
    ;;
  *)
    die "unknown action '$ACTION' (use status, enable, or disable)"
    ;;
esac
