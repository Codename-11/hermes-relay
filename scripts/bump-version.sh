#!/usr/bin/env bash
# scripts/bump-version.sh — atomic version bump across all three sources.
#
# Hermes-Relay has three version strings that MUST stay in lockstep:
#
#   1. gradle/libs.versions.toml::appVersionName   (canonical, source of truth)
#   2. pyproject.toml::version                      (Python package metadata)
#   3. plugin/relay/__init__.py::__version__        (runtime — reported by /health)
#
# Drift between these is silent and confusing (sample: 2026-04-12 we spent
# debug time chasing "why does /health say 0.2.0 after a pull" because the
# constant in __init__.py never got bumped). This script exists to make
# drift impossible by updating all three atomically and failing loudly if
# any step goes wrong.
#
# It also bumps appVersionCode monotonically (required by Play Console).
#
# Usage:
#     scripts/bump-version.sh <new-version>
#     scripts/bump-version.sh 0.3.0
#     scripts/bump-version.sh 0.3.1-beta.1
#
# The script:
#   - validates the new version against SemVer
#   - reads the current appVersionCode and increments it by 1
#   - rewrites all three files
#   - runs a sanity grep to confirm all three now match
#   - prints a diff + the next steps (commit, tag, push)
#
# The script does NOT:
#   - commit or tag (you do that manually after reviewing the diff)
#   - touch CHANGELOG.md or RELEASE_NOTES.md (those need human prose)
#   - push to origin

set -euo pipefail

# ── Args ───────────────────────────────────────────────────────────────────
if [ $# -ne 1 ]; then
    echo "usage: $0 <new-version>" >&2
    echo "  e.g. $0 0.3.0" >&2
    echo "  e.g. $0 0.3.1-beta.1" >&2
    exit 2
fi

NEW_VERSION="$1"

# Loose SemVer check: MAJOR.MINOR.PATCH with optional -prerelease
if ! [[ "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]]; then
    echo "  [x] '$NEW_VERSION' is not valid SemVer (MAJOR.MINOR.PATCH[-prerelease])" >&2
    exit 2
fi

# ── Paths ──────────────────────────────────────────────────────────────────
# Script lives at scripts/bump-version.sh; repo root is one level up.
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

LIBS="gradle/libs.versions.toml"
PYPROJECT="pyproject.toml"
INITPY="plugin/relay/__init__.py"

for f in "$LIBS" "$PYPROJECT" "$INITPY"; do
    [ -f "$f" ] || { echo "  [x] missing $f — are you in the repo root?" >&2; exit 1; }
done

# ── Read current state ────────────────────────────────────────────────────
OLD_NAME="$(grep -E '^appVersionName *= *"' "$LIBS" | sed -E 's/.*"(.*)".*/\1/')"
OLD_CODE="$(grep -E '^appVersionCode *= *"' "$LIBS" | sed -E 's/.*"(.*)".*/\1/')"

if [ -z "$OLD_NAME" ] || [ -z "$OLD_CODE" ]; then
    echo "  [x] could not parse current version from $LIBS" >&2
    exit 1
fi

# Monotonic bump — appVersionCode must always increase, even across prereleases
NEW_CODE=$((OLD_CODE + 1))

echo ""
echo "  Bumping version:"
echo "    appVersionName:  $OLD_NAME  →  $NEW_VERSION"
echo "    appVersionCode:  $OLD_CODE  →  $NEW_CODE"
echo ""

# ── Rewrite the three files ───────────────────────────────────────────────
# Use perl for in-place replacement because sed -i is different on macOS vs Linux.
perl -pi -e 's/^appVersionName *= *"[^"]*"/appVersionName = "'"$NEW_VERSION"'"/' "$LIBS"
perl -pi -e 's/^appVersionCode *= *"[^"]*"/appVersionCode = "'"$NEW_CODE"'"/'     "$LIBS"
perl -pi -e 's/^version *= *"[^"]*"/version = "'"$NEW_VERSION"'"/'                "$PYPROJECT"
perl -pi -e 's/^__version__ *= *"[^"]*"/__version__ = "'"$NEW_VERSION"'"/'        "$INITPY"

# ── Sanity check — all three must match ───────────────────────────────────
GOT_LIBS="$(grep -E '^appVersionName *= *"' "$LIBS" | sed -E 's/.*"(.*)".*/\1/')"
GOT_PY="$(grep -E '^version *= *"' "$PYPROJECT" | head -1 | sed -E 's/.*"(.*)".*/\1/')"
GOT_INIT="$(grep -E '^__version__ *= *"' "$INITPY" | sed -E 's/.*"(.*)".*/\1/')"

if [ "$GOT_LIBS" != "$NEW_VERSION" ] || [ "$GOT_PY" != "$NEW_VERSION" ] || [ "$GOT_INIT" != "$NEW_VERSION" ]; then
    echo "  [x] post-bump sanity check failed — version strings don't match:" >&2
    echo "      libs.versions.toml:  $GOT_LIBS" >&2
    echo "      pyproject.toml:      $GOT_PY" >&2
    echo "      __init__.py:         $GOT_INIT" >&2
    echo "      expected:            $NEW_VERSION" >&2
    echo "  [x] revert your working tree with 'git checkout -- $LIBS $PYPROJECT $INITPY'" >&2
    exit 1
fi

echo "  [ok] All three version sources now match: $NEW_VERSION"
echo ""

# ── Diff summary ──────────────────────────────────────────────────────────
echo "  ─── diff ───────────────────────────────────────────────"
git diff --no-color "$LIBS" "$PYPROJECT" "$INITPY" || true
echo "  ────────────────────────────────────────────────────────"
echo ""

# ── Next steps ────────────────────────────────────────────────────────────
echo "  Next steps (main + dev branching model, see RELEASE.md):"
echo ""
echo "    1. Update CHANGELOG.md and RELEASE_NOTES.md for $NEW_VERSION"
echo "    2. Commit the bump + changelog on 'dev':"
echo "         git add $LIBS $PYPROJECT $INITPY CHANGELOG.md RELEASE_NOTES.md"
echo "         git commit -m \"release: v$NEW_VERSION\""
echo "         git push origin dev"
echo "    3. Open a release PR (dev -> main), merge with --no-ff."
echo "    4. Tag from the new 'main' tip and push (triggers CI release build):"
echo "         git checkout main && git pull --ff-only origin main"
echo "         git tag v$NEW_VERSION"
echo "         git push origin v$NEW_VERSION"
echo ""
echo "  See RELEASE.md for the full recipe."
