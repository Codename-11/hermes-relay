# Issue → fix dev-loop

How an incoming issue becomes a worktree you (or a local agent) can start working in,
with Claude's triage already done. This documents the automation in
`.github/workflows/claude-triage.yml`, the PR-review changes in
`.github/workflows/claude-code-review.yml`, and the local bridge
`scripts/start-issue.sh`.

It complements — does not replace — `RELEASE.md` (how a fix ships) and `CLAUDE.md`
(the non-negotiables: vanilla-Hermes-path-upstream-only, commit conventions,
public-repo writing hygiene).

## The loop at a glance

```
issue opened ──▶ auto-label (keywords)           ┐
             └─▶ triage-ai (classify + opinion)   │  CI (claude-triage.yml)
add triage:deep ─▶ deep-dive (root cause + plan)  │
reporter replies ─▶ triage-followup (next step /  ┘
                    escalate to a maintainer)
                                │
                                ▼
        scripts/start-issue.sh <N>   ── local bridge
                                │
                                ▼
   ../hr-issue-<N>  worktree on  fix/issue-<N>-<slug>  off origin/dev
   + ISSUE-BRIEF.md  (body + bot triage notes + verification plan)
                                │
                                ▼
        work it (claude / codex / hand-edit) ─▶ PR to dev ─▶ Claude Code Review
```

## CI: the four triage jobs

All run on Sonnet, are read-only against the repo (`Bash(gh:*),Read,Grep,Glob`),
treat the issue text as untrusted input, and never close issues or edit bodies.

| Job | Fires on | What it does |
|-----|----------|--------------|
| `auto-label` | issue opened | Free, deterministic keyword labeler — TYPE from the title prefix, `area:*` from keywords. No LLM, no cost. |
| `triage-ai` | issue opened / manual dispatch | Always-on. Dedupes, sets exactly one TYPE + one `area:*` label, and posts ONE hedged note: probable cause, likely files, suggested direction. |
| `deep-dive` | `triage:deep` label added | Opt-in. Investigates the codebase and posts a root-cause hypothesis, a concrete fix plan, a surface-specific **verification plan**, and a maintainer quick-start (the worktree command). |
| `triage-followup` | reporter comments on an open `bug` issue | Re-reads the thread; gives the next diagnostic step, or escalates (`needs-maintainer-review` + @maintainer) after ~2 rounds. NOT gated on commenter write-access, so external crash reporters get follow-up. |

To deep-dive an old issue, just add the `triage:deep` label — that fires the
`issues: labeled` trigger. To re-run the basic triage, use the workflow's
`workflow_dispatch` with the issue number (Actions tab, or
`gh workflow run claude-triage.yml -f issue_number=NNN`).

## PR review

`claude-code-review.yml` runs the `/code-review` plugin on every non-release,
non-bot PR (release `dev → main` PRs and bot PRs are skipped; so is a PR that
edits the review workflow itself — the action needs the workflow to match the
default branch first). It now also:

- adds a short constructive **"🔭 Maintainer's-eye verdict"** (quality / biggest
  risk / ship-or-hold) above the findings, and
- uses `use_sticky_comment: true` so re-pushes update one comment instead of
  stacking a fresh review per `synchronize`.

## Verification matrix (surface → how a fix is proven)

The `area:*` label decides whether a fix can be proven by CI or needs a human.
`deep-dive` and `start-issue.sh` both bake this in.

| Surface (`area:*`) | Paths | Verify | CI-gateable? |
|--------------------|-------|--------|--------------|
| `area:plugin` | `plugin/` | `python -m unittest plugin.tests.test_<name>` | ✅ ci-plugin.yml |
| `area:cli` | `desktop/` | `cd desktop && npm run build && npm run smoke` + unit | ✅ ci-desktop.yml |
| `area:android` (logic) | `app/` VM/mapper/pure Kotlin | `./gradlew :app:testGooglePlayDebugUnitTest` + `:app:lint` | ✅ ci-android.yml |
| `area:android` (UI/behavior) | `app/` Compose / device behavior | Android Studio ▶ on a real device | ❌ **human gate** |
| `area:dashboard` | `plugin/dashboard/` | dashboard bundle build | ✅ ci-dashboard.yml |
| `area:docs` | `docs/`, `user-docs/` | docs build | ✅ docs.yml |

Rule of thumb: where a surface is CI-gateable, write the **failing test first**
(TDD, per the global workflow) so the fix is self-verifying. Android UI/behavior
is the deliberate exception — CI only covers lint + unit there, so on-device
verification stays a manual maintainer step and a fix is never "done" from CI alone.

## Local bridge: `scripts/start-issue.sh`

```bash
scripts/start-issue.sh <issue-number> [base-branch]   # base defaults to dev
```

Creates `../hr-issue-<N>`, a git worktree on `fix|feature|docs/issue-<N>-<slug>`
(prefix chosen from the TYPE label) off `origin/<base>`, and writes
`ISSUE-BRIEF.md` into it: the issue body, the bot's triage/deep-dive comments, and
the verification plan for the issue's surface. Open your agent session there and it
starts pre-briefed. Worktrees share the main checkout's object store, so several
issue branches can run concurrently. `ISSUE-BRIEF.md` is git-ignored. Tear down with
`git worktree remove ../hr-issue-<N>`.

## Setup (one-time)

The workflows can only apply labels that already exist. Create them once:

```bash
gh label create "triage:deep"             -c "5319e7" -d "Request a deep code-level triage pass"
gh label create "needs-maintainer-review" -c "d93f0b" -d "Automated triage exhausted; needs a human"
gh label create "area:android"            -c "1d76db" -d "Kotlin app"
gh label create "area:cli"                -c "0e8a16" -d "desktop/ Node CLI"
gh label create "area:plugin"             -c "fbca04" -d "plugin/ Python relay + tools"
gh label create "area:dashboard"          -c "c5def5" -d "plugin/dashboard React UI"
gh label create "area:docs"               -c "bfd4f2" -d "docs/ or user-docs/"
```

Secret: `CLAUDE_CODE_OAUTH_TOKEN` (already configured for the existing Claude
workflows).

## Operational notes

- **Activation lag.** Issue-triggered workflows run the copy on the **default
  branch (`main`)**. Changes here stay dormant on `dev` until a release-merge lands
  them on `main`. Test by triaging a throwaway issue after the merge.
- **Cost control.** `auto-label` is free; `triage-ai` is one cheap Sonnet pass per
  new issue; `deep-dive` only runs when you opt in with the label; `triage-followup`
  self-limits to ~2 rounds then escalates. Bump `--model` to a current Opus in the
  `deep-dive` step if you want deeper reasoning (cost tradeoff).
- **No write-access escalation here.** None of these jobs push code or open PRs —
  triage is read-only by design. Auto-attempting a fix from issue content (a
  `contents: write` job triggered by untrusted text) was intentionally left out;
  revisit only behind a hard maintainer-gated label if ever wanted.
