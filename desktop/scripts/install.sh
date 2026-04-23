#!/usr/bin/env bash
# hermes-relay desktop CLI installer — macOS / Linux.
#
#   curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/desktop/scripts/install.sh | sh
#
# Downloads a prebuilt binary from GitHub Releases — no Node.js required.
# Pin a specific release:
#   HERMES_RELAY_VERSION=desktop-v0.3.0-alpha.1 curl -fsSL ... | sh
# Override install dir:
#   HERMES_RELAY_INSTALL_DIR=/opt/hermes curl -fsSL ... | sh
#
# **Experimental phase** — binaries are unsigned. macOS will quarantine;
# a `xattr -dr com.apple.quarantine <path>` one-liner is the escape hatch
# (printed by this script on completion when OS=darwin).

set -eu

REPO="${HERMES_RELAY_REPO:-Codename-11/hermes-relay}"
VERSION="${HERMES_RELAY_VERSION:-latest}"
INSTALL_DIR="${HERMES_RELAY_INSTALL_DIR:-$HOME/.hermes/bin}"

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

# Strip the `desktop-v` tag prefix and any `-alpha.N` / `-beta.N` / `-rc.N`
# suffix so we can compare against the post-install --version output (which
# reports the bare package.json semver).
normalize_pinned_version() {
  local v="$1"
  # Empty or "latest" → unknown; caller decides.
  [ -z "$v" ] || [ "$v" = "latest" ] && { printf ''; return 0; }
  # Strip leading `desktop-v` (our release tag convention).
  v="${v#desktop-v}"
  # Strip leading `v` just in case someone pinned `v0.3.0`.
  v="${v#v}"
  # Strip -alpha.N / -beta.N / -rc.N / any other pre-release tail.
  v="${v%%-*}"
  printf '%s' "$v"
}

have curl || die "curl is required"
have uname || die "uname is required"
have install || die "install(1) is required"

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
# Walk the releases API (newest first) and pick the first `desktop-v*` tag,
# prerelease or not. Pinned versions skip this and use the tag directly.
resolved_version="$VERSION"
if [ "$VERSION" = "latest" ]; then
  say "-> resolving latest desktop-v* release..."
  api_body=$(curl -fsSL "https://api.github.com/repos/$REPO/releases" 2>/dev/null) \
    || die "could not query GitHub Releases API"
  # Extract the first desktop-v* tag_name. Each "tag_name": entry is on its
  # own line in GitHub's JSON output, so line-oriented tooling is sufficient
  # and avoids a jq dependency.
  resolved_version=$(printf '%s\n' "$api_body" \
    | grep -E '"tag_name": *"desktop-v' \
    | head -1 \
    | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')
  [ -n "$resolved_version" ] || die "no desktop-v* releases found on $REPO"
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

# Create a `hermes` alias next to `hermes-relay` so muscle memory from the
# upstream hermes-agent CLI (also called `hermes`) just works. Don't clobber
# a local hermes install: only create the symlink if nothing is there yet,
# or if an existing symlink already points at our binary. `-e` returns false
# for dangling symlinks — we want to overwrite those, so the `[ ! -e ]`
# branch will recreate when readlink resolves to a missing target.
hermes_target="$INSTALL_DIR/hermes"
if [ ! -e "$hermes_target" ]; then
  ln -sf "$(basename "$target")" "$hermes_target"
  say "-> created hermes -> hermes-relay alias"
elif [ -L "$hermes_target" ] && [ "$(readlink "$hermes_target")" = "hermes-relay" ]; then
  : # already points at us, no-op
else
  say "-> hermes already exists at $hermes_target (skipped alias creation)"
fi

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
say "  hermes-relay --help       # (or the short alias: hermes --help)"
say "  hermes-relay pair --remote ws://<host>:8767"
say ""
