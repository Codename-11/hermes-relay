#!/usr/bin/env bash
# scripts/start-issue.sh — pull a GitHub issue into a ready-to-work git worktree,
# pre-briefed with Claude's automated triage.
#
# This is the local end of the dev-loop (see docs/dev-loop.md): CI triages an
# issue (classification + probable cause, and a deeper analysis if you add the
# `triage:deep` label), then this script bootstraps an isolated worktree off
# `dev` named to our branch convention and drops an ISSUE-BRIEF.md inside it —
# the issue body, the bot's triage/deep-dive comments, and the surface-specific
# verification plan — so the agent session you open there (claude / codex / etc.)
# starts already briefed instead of re-deriving context.
#
# Worktrees share this checkout's object store, so they're cheap and you can run
# several issue branches concurrently (see the shared-worktree workflow notes).
#
# Requires: gh (authenticated) + git. Run from anywhere inside the repo.
#
# Usage:
#   scripts/start-issue.sh <issue-number> [base-branch]   # base defaults to dev
#
# Examples:
#   scripts/start-issue.sh 142            # worktree ../hr-issue-142 off origin/dev
#   scripts/start-issue.sh 142 main       # base off origin/main instead
set -euo pipefail

die() { printf 'start-issue: %s\n' "$*" >&2; exit 1; }

# --- args -------------------------------------------------------------------
N="${1:-}"
BASE="${2:-dev}"
[ -n "$N" ] || die "usage: scripts/start-issue.sh <issue-number> [base-branch]"
case "$N" in (*[!0-9]*|'') die "issue number must be numeric, got '$N'";; esac

command -v gh  >/dev/null 2>&1 || die "gh CLI not found on PATH"
command -v git >/dev/null 2>&1 || die "git not found on PATH"
gh auth status >/dev/null 2>&1 || die "gh is not authenticated — run 'gh auth login'"

# Anchor everything to the repo root so the sibling worktree path is stable.
ROOT="$(git rev-parse --show-toplevel)" || die "not inside a git repository"
cd "$ROOT"

# --- read the issue ---------------------------------------------------------
echo "→ reading issue #$N …"
TITLE="$(gh issue view "$N" --json title  --jq .title)"        || die "could not read issue #$N (does it exist?)"
URL="$(gh issue view "$N" --json url      --jq .url)"
LABELS="$(gh issue view "$N" --json labels --jq '[.labels[].name] | join(", ")')"

# Branch prefix from the TYPE label (matches our convention: fix/ feature/ docs/).
case ",$LABELS," in
  *,enhancement,*)   PREFIX="feature" ;;
  *,documentation,*) PREFIX="docs" ;;
  *)                 PREFIX="fix" ;;   # bug / question / unlabeled → fix
esac

# Surface area from the area:* label, for the verification plan.
AREA=""
case ",$LABELS," in
  *,area:android,*)   AREA="android" ;;
  *,area:cli,*)       AREA="cli" ;;
  *,area:plugin,*)    AREA="plugin" ;;
  *,area:dashboard,*) AREA="dashboard" ;;
  *,area:docs,*)      AREA="docs" ;;
esac

# Slug from the title: drop a leading "[bug]"-style tag, lowercase, kebab-case,
# collapse repeats, trim, and cap length so branch names stay sane.
SLUG="$(printf '%s' "$TITLE" \
  | sed -E 's/^\[[^]]*\][[:space:]]*//' \
  | tr '[:upper:]' '[:lower:]' \
  | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//' \
  | cut -c1-40 | sed -E 's/-+$//')"
[ -n "$SLUG" ] || SLUG="issue"

BRANCH="${PREFIX}/issue-${N}-${SLUG}"
WTDIR="../hr-issue-${N}"

# --- guard rails ------------------------------------------------------------
if git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
  die "branch '${BRANCH}' already exists — check it out, or delete it first."
fi
if [ -e "$WTDIR" ]; then
  die "worktree path '${WTDIR}' already exists — remove it (git worktree remove '${WTDIR}') first."
fi

# --- create the worktree ----------------------------------------------------
echo "→ fetching origin/${BASE} …"
git fetch --quiet origin "$BASE" || die "could not fetch origin/${BASE}"
echo "→ creating worktree ${WTDIR} on branch ${BRANCH} (off origin/${BASE}) …"
git worktree add "$WTDIR" -b "$BRANCH" "origin/${BASE}" >/dev/null || die "git worktree add failed"

# --- verification plan text -------------------------------------------------
verification_plan() {
  case "$AREA" in
    android)
      cat <<'EOF'
**Surface: Android (Kotlin app).**
- Logic (ViewModels / mappers / pure Kotlin): `./gradlew :app:testGooglePlayDebugUnitTest` + `./gradlew :app:lint` — **CI-gateable** (ci-android.yml).
- UI / on-device behavior: build in Android Studio (▶) and verify on a real device. **NOT CI-gateable — this is a human gate.** Do not mark such a fix "done" from CI alone.
- Prefer TDD for logic fixes: add the failing unit test first, then fix.
EOF
      ;;
    cli)
      cat <<'EOF'
**Surface: Desktop CLI (Node/TypeScript).**
- `cd desktop && npm run build && npm run smoke` plus the unit tests — **CI-gateable** (ci-desktop.yml).
- Prefer TDD: reproduce as a failing test first, then fix.
EOF
      ;;
    plugin)
      cat <<'EOF'
**Surface: Python relay / plugin.**
- `python -m unittest plugin.tests.test_<name>` (avoid bare `pytest` — conftest imports `responses`, which may be absent in the venv) — **CI-gateable** (ci-plugin.yml).
- Syntax check while iterating: `python -m py_compile plugin/<file>.py`.
- Prefer TDD: add the failing test first, then fix.
EOF
      ;;
    dashboard)
      cat <<'EOF'
**Surface: Dashboard (React).**
- Build the dashboard bundle — **CI-gateable** (ci-dashboard.yml).
- Verify the committed IIFE bundle is regenerated if you touch `plugin/dashboard/src/`.
EOF
      ;;
    docs)
      cat <<'EOF'
**Surface: Docs.**
- Build the docs site — **CI-gateable** (docs.yml).
EOF
      ;;
    *)
      cat <<'EOF'
**Surface: not yet classified** (no `area:*` label). Pick the row that matches the files you touch:
- plugin/ (Python)   → `python -m unittest plugin.tests.test_<name>` — CI-gateable.
- desktop/ (CLI)     → `cd desktop && npm run build && npm run smoke` — CI-gateable.
- app/ logic         → `./gradlew :app:testGooglePlayDebugUnitTest` + `:app:lint` — CI-gateable.
- app/ UI/behavior   → Android Studio + device — **human gate, NOT CI-gateable**.
- plugin/dashboard/  → dashboard bundle build — CI-gateable.
- docs/, user-docs/  → docs build — CI-gateable.
EOF
      ;;
  esac
}

# --- write the brief --------------------------------------------------------
BRIEF="${WTDIR}/ISSUE-BRIEF.md"
{
  echo "# Issue #${N}: ${TITLE}"
  echo
  echo "- **Link:** ${URL}"
  echo "- **Labels:** ${LABELS:-none}"
  echo "- **Branch:** \`${BRANCH}\` (off \`origin/${BASE}\`)"
  echo "- **Worktree:** \`${WTDIR}\`"
  echo
  echo "> Auto-generated by scripts/start-issue.sh. Not committed — it's in .gitignore."
  echo
  echo "## Verification plan"
  echo
  verification_plan
  echo
  echo "## Definition of done"
  echo
  echo "- The fix passes the verification above (write the failing test first where the surface is CI-gateable)."
  echo "- Update CHANGELOG \`[Unreleased]\`, and DEVLOG.md at end of session; park any follow-ups in TODO.md."
  echo "- Feature branches target \`dev\` (never straight to \`main\`); merge with --no-ff."
  echo
  echo "## Issue body"
  echo
  gh issue view "$N" --json body --jq '.body // "_(no description)_"'
  echo
  echo "## Automated triage / deep-dive notes"
  echo
  # Triage/deep-dive/follow-up comments are posted by the Claude GitHub App
  # (author "claude"), NOT "github-actions" — so match on our comment signatures
  # (identity-proof), with the known bot logins as a fallback.
  BOTNOTES="$(gh issue view "$N" --json comments \
    --jq '.comments[] | select(((.body // "") | test("automated triage|Deep-dive analysis|automated follow-up")) or ((.author.login // "") | test("^(claude|github-actions)"))) | "_" + .createdAt + "_\n\n" + .body + "\n\n---\n"')"
  if [ -n "$BOTNOTES" ]; then
    printf '%s\n' "$BOTNOTES"
  else
    echo "_No automated triage comments yet. Add the \`triage:deep\` label on the issue for a code-level analysis, or run the triage workflow._"
  fi
} > "$BRIEF"

# --- done -------------------------------------------------------------------
echo
echo "✓ ready. Worktree:  ${WTDIR}"
echo "         Branch:    ${BRANCH}"
echo "         Brief:     ${BRIEF}"
echo
echo "Next:"
echo "  cd ${WTDIR}"
echo "  \$EDITOR ISSUE-BRIEF.md      # or open an agent session here (claude / codex)"
echo
echo "When done, from the main checkout:  git worktree remove ${WTDIR}"
