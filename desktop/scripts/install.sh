#!/usr/bin/env bash
# hermes-relay desktop CLI installer — macOS / Linux.
#
#   curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
#
# Downloads a prebuilt binary from GitHub Releases — no Node.js required.
# Pin a specific release:
#   HERMES_RELAY_VERSION=cli-v0.3.0-alpha.18 curl -fsSL ... | sh
# Override install dir:
#   HERMES_RELAY_INSTALL_DIR=/opt/hermes curl -fsSL ... | sh
# Optional `hermes` alias for Orca/upstream-style workflows:
#   HERMES_RELAY_HERMES_ALIAS=enable curl -fsSL ... | sh
#
# **Experimental phase** — binaries are unsigned. macOS will quarantine;
# a `xattr -dr com.apple.quarantine <path>` one-liner is the escape hatch
# (printed by this script on completion when OS=darwin).

set -eu

REPO="${HERMES_RELAY_REPO:-Codename-11/hermes-relay}"
VERSION="${HERMES_RELAY_VERSION:-latest}"
INSTALL_DIR="${HERMES_RELAY_INSTALL_DIR:-$HOME/.hermes/bin}"
HERMES_ALIAS_MODE="$(printf '%s' "${HERMES_RELAY_HERMES_ALIAS:-skip}" | tr '[:upper:]' '[:lower:]')"

say() { printf '  %s\n' "$*"; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# Best-effort read of an installed binary's version. Prints the bare
# version string (e.g. "0.3.0") on stdout and returns 0 on success; returns
# non-zero on any failure (not executable, --version errors, timeout).
# The binary prints `hermes-relay X.Y.Z\n` — we strip the prefix.
read_installed_version() {
  local path="$1"
  local line=""
  [ -x "$path" ] || return 1
  if have timeout; then
    line="$(timeout 5 "$path" --version 2>/dev/null | head -1)" || return 1
  else
    line="$("$path" --version 2>/dev/null | head -1)" || return 1
  fi
  [ -n "$line" ] || return 1
  # Expected form: "hermes-relay X.Y.Z" — third field by whitespace.
  printf '%s' "$line" | awk '{print $2}'
}

# Strip the `cli-v` tag prefix to get the bare semver (KEEPS the
# prerelease suffix — `0.3.0-alpha.11`, not `0.3.0`). The binary's
# `--version` output has reported the full semver since alpha.4 (when
# `gen:version` started embedding the full string from package.json), so
# the comparison is full-semver vs full-semver. Stripping the prerelease
# here used to be defensive when the binary reported bare `0.3.0`, but
# now it just produces `upgrading to 0.3.0` instead of `upgrading to
# 0.3.0-alpha.11` in the user-facing message.
normalize_pinned_version() {
  local v="$1"
  # Empty or "latest" → unknown; caller decides.
  [ -z "$v" ] || [ "$v" = "latest" ] && { printf ''; return 0; }
  # Strip leading `cli-v` (current convention) or historical `desktop-v`.
  v="${v#cli-v}"
  v="${v#desktop-v}"
  # Strip leading `v` just in case someone pinned `v0.3.0`.
  v="${v#v}"
  printf '%s' "$v"
}

have curl || die "curl is required"
have uname || die "uname is required"
have install || die "install(1) is required"

case "$HERMES_ALIAS_MODE" in
  skip|''|enable|on|true|yes|1|disable|off|false|no|0|remove) ;;
  *) die "HERMES_RELAY_HERMES_ALIAS must be 'enable', 'disable', or unset" ;;
esac

# sha256sum on Linux, shasum on macOS — provide a shim.
if have sha256sum; then
  sha_check() { grep " $1\$" SHA256SUMS.txt | sha256sum -c -; }
elif have shasum; then
  sha_check() {
    expected=$(grep " $1\$" SHA256SUMS.txt | awk '{print $1}')
    actual=$(shasum -a 256 "$1" | awk '{print $1}')
    [ "$expected" = "$actual" ]
  }
else
  die "need sha256sum or shasum for checksum verification"
fi

os="$(uname -s | tr '[:upper:]' '[:lower:]')"
arch="$(uname -m)"
case "$os-$arch" in
  linux-x86_64)  asset="hermes-relay-linux-x64" ;;
  linux-aarch64) asset="hermes-relay-linux-arm64" ;;
  linux-arm64)   asset="hermes-relay-linux-arm64" ;;
  darwin-x86_64) asset="hermes-relay-darwin-x64" ;;
  darwin-arm64)  asset="hermes-relay-darwin-arm64" ;;
  *) die "unsupported platform: $os/$arch (supported: linux-x64/arm64, darwin-x64/arm64)" ;;
esac

# Resolve "latest" to a concrete tag. GitHub's /releases/latest/download/ URL
# always skips prereleases, which breaks install during any all-alpha window.
# Walk the releases API and prefer the SemVer-max `cli-v*` tag. Historical
# public prereleases used `desktop-v*`, so fall back to that track when no new
# CLI tag exists. Pinned versions skip this and use the tag directly.
resolved_version="$VERSION"
if [ "$VERSION" = "latest" ]; then
  say "-> resolving latest cli-v* release..."
  api_body=$(curl -fsSL "https://api.github.com/repos/$REPO/releases" 2>/dev/null) \
    || die "could not query GitHub Releases API"
  # Extract every CLI-track tag_name and pick the SemVer-max. Don't trust the
  # API's first-element ordering — GitHub orders by the release row's created_at
  # which shifts when the row is edited or re-tagged. Each "tag_name": entry is
  # on its own line in GitHub's JSON output, so line-oriented tooling is
  # sufficient and avoids a jq dependency.
  release_tags=$(printf '%s\n' "$api_body" \
    | grep -E '"tag_name": *"(cli-v|desktop-v)' \
    | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/' || true)
  resolved_version=$(printf '%s\n' "$release_tags" \
    | awk '/^cli-v/ { v=$0; sub(/^cli-v/, "", v); print v "\t" $0 }' \
    | sort -V \
    | tail -1 \
    | cut -f2)
  if [ -z "$resolved_version" ]; then
    say "   no cli-v* releases yet; checking historical desktop-v* prereleases..."
    resolved_version=$(printf '%s\n' "$release_tags" \
      | awk '/^desktop-v/ { v=$0; sub(/^desktop-v/, "", v); print v "\t" $0 }' \
      | sort -V \
      | tail -1 \
      | cut -f2)
  fi
  [ -n "$resolved_version" ] || die "no cli-v* or historical desktop-v* releases found on $REPO"
  say "   $resolved_version"
fi
base="https://github.com/$REPO/releases/download/$resolved_version"

say "Hermes-Relay desktop CLI installer"
say "  platform : $os/$arch"
say "  asset    : $asset"
say "  version  : $resolved_version"
say "  install  : $INSTALL_DIR"
say ""

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"

# Pre-install: detect an existing binary so the user can see upgrade vs
# reinstall vs overwrite-after-corruption, instead of the install going
# silent and requiring a follow-up `hermes-relay --version` to find out.
target="$INSTALL_DIR/hermes-relay"
# Normalize the RESOLVED tag (same as VERSION for pins, or the API-resolved
# tag when VERSION=latest) so the pre- and post-install version compares
# are accurate even when `latest` resolved to a prerelease tag.
expected_new_version="$(normalize_pinned_version "$resolved_version")"
existing_version=""
if [ -e "$target" ]; then
  if existing_version="$(read_installed_version "$target")" && [ -n "$existing_version" ]; then
    if [ -n "$expected_new_version" ]; then
      if [ "$existing_version" = "$expected_new_version" ]; then
        say "-> existing install detected: $existing_version — reinstalling same version"
      else
        say "-> existing install detected: $existing_version — upgrading to $expected_new_version"
      fi
    else
      # VERSION=latest — we don't know the target version yet.
      say "-> existing install detected: $existing_version — will replace with latest"
    fi
  else
    existing_version=""
    say "-> existing install detected (could not read version) — overwriting"
  fi
fi

say "-> downloading binary..."
curl -fsSL -o "$asset" "$base/$asset" \
  || die "download failed: $base/$asset (maybe no release for this platform yet?)"

say "-> downloading checksums..."
curl -fsSL -o SHA256SUMS.txt "$base/SHA256SUMS.txt" \
  || die "could not fetch SHA256SUMS.txt (release incomplete?)"

say "-> verifying SHA256..."
sha_check "$asset" || die "checksum mismatch — refusing to install"
say "   ok"

mkdir -p "$INSTALL_DIR"
install -m 0755 "$asset" "$target"

# Optional `hermes` alias for Orca/upstream-style workflows. It is not created
# by default because it can shadow a real local hermes-agent install.
hermes_target="$INSTALL_DIR/hermes"
case "$HERMES_ALIAS_MODE" in
  enable|on|true|yes|1)
    if [ ! -e "$hermes_target" ] && [ ! -L "$hermes_target" ]; then
      ln -sf "hermes-relay" "$hermes_target"
      say "-> created hermes -> hermes-relay alias"
    elif [ -L "$hermes_target" ] && [ "$(readlink "$hermes_target")" = "hermes-relay" ]; then
      say "-> hermes alias already points at hermes-relay"
    else
      say "-> hermes already exists at $hermes_target (left untouched)"
    fi
    ;;
  disable|off|false|no|0|remove)
    if [ -L "$hermes_target" ] && [ "$(readlink "$hermes_target")" = "hermes-relay" ]; then
      rm -f "$hermes_target"
      say "-> removed hermes alias"
    elif [ -e "$hermes_target" ] || [ -L "$hermes_target" ]; then
      say "-> hermes exists but is not managed by hermes-relay (left untouched)"
    else
      say "-> hermes alias already disabled"
    fi
    ;;
  *)
    if [ -L "$hermes_target" ] && [ "$(readlink "$hermes_target")" = "hermes-relay" ]; then
      say "-> existing hermes alias left enabled (disable with HERMES_RELAY_HERMES_ALIAS=disable and hermes-alias.sh)"
    else
      say "-> skipped optional hermes alias (enable with HERMES_RELAY_HERMES_ALIAS=enable)"
    fi
    ;;
esac

# Post-install: confirm the NEW binary reports a sensible version. Don't
# fail the install on mismatch — the user may have pinned to a pre-release
# whose version_name differs slightly from the tag.
installed_version="$(read_installed_version "$target" || true)"
if [ -n "$installed_version" ]; then
  say "-> installed $installed_version at $target"
  if [ -n "$expected_new_version" ] && [ "$installed_version" != "$expected_new_version" ]; then
    say "   WARN: expected version $expected_new_version from tag ($resolved_version), got $installed_version"
    say "   (pre-release tags can diverge from package version — this is usually fine)"
  fi
else
  say "-> installed $target (could not read post-install version)"
fi

# PATH hint — don't mutate the user's rc silently without a prompt; just
# tell them clearly what to do if the binary isn't reachable yet.
case ":$PATH:" in
  *":$INSTALL_DIR:"*)
    say "-> $INSTALL_DIR is already in PATH"
    ;;
  *)
    shell_rc=""
    case "${SHELL:-}" in
      */zsh) shell_rc="$HOME/.zshrc" ;;
      */bash) shell_rc="$HOME/.bashrc" ;;
      */fish) shell_rc="$HOME/.config/fish/config.fish" ;;
    esac
    say ""
    say "!! $INSTALL_DIR is not in your PATH."
    say "   Add this line to ${shell_rc:-your shell rc file}:"
    say ""
    say "     export PATH=\"$INSTALL_DIR:\$PATH\""
    say ""
    say "   Then restart your shell (or \`source ${shell_rc:-~/.bashrc}\`)."
    ;;
esac

if [ "$os" = "darwin" ]; then
  say ""
  say "   macOS note: this binary is unsigned (experimental phase)."
  say "   If macOS blocks first launch, clear the quarantine flag:"
  say ""
  say "     xattr -dr com.apple.quarantine $target"
  say ""
fi

say ""
if [ -n "$installed_version" ]; then
  say "Installed hermes-relay $installed_version. Try:"
else
  say "Installed. Try:"
fi
say "  hermes-relay --help"
say "  hermes-relay pair --remote ws://<host>:8767"
say ""
say "Optional Orca/upstream-style alias:"
say "  HERMES_RELAY_HERMES_ALIAS=enable curl -fsSL https://raw.githubusercontent.com/$REPO/main/desktop/scripts/hermes-alias.sh | sh"
say "  HERMES_RELAY_HERMES_ALIAS=disable curl -fsSL https://raw.githubusercontent.com/$REPO/main/desktop/scripts/hermes-alias.sh | sh"
say ""
