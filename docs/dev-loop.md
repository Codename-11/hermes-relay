# Issue → fix dev-loop

How an incoming issue becomes a worktree you (or a local agent) can start working
in. This documents deterministic issue labeling, subscription-backed Codex review,
path-aware required CI, and the local bridge `scripts/start-issue.sh`.

It complements — does not replace — `RELEASE.md` (how a fix ships) and `CLAUDE.md`
(the non-negotiables: vanilla-Hermes-path-upstream-only, commit conventions,
public-repo writing hygiene).

## The loop at a glance

```
issue opened ──▶ deterministic type + area labels ──▶ maintainer triage
                                                        │
                                                        ▼
                         scripts/start-issue.sh <N>   ── local bridge
                                                        │
                                                        ▼
                 ../hr-issue-<N> worktree on fix/issue-<N>-<slug>
                 off origin/dev + ISSUE-BRIEF.md
                                                        │
                                                        ▼
                 implement + verify ─▶ PR to dev ─┬─▶ Required checks
                                                  └─▶ Codex review
```

## Deterministic issue triage

`.github/workflows/issue-triage.yml` runs a no-LLM keyword classifier when an
issue opens. It applies a title-derived type label and, when the issue text is
clear, one `area:*` label. It never closes an issue, edits its body, diagnoses a
root cause, or posts an automated opinion.

To label an older issue again, use the workflow's `workflow_dispatch` input or
run `gh workflow run issue-triage.yml -f issue_number=NNN`.

## PR review

Codex automatic review is configured through the repository's Codex cloud
integration, not a GitHub Actions secret. It reviews every PR and follows the
top-level `AGENTS.md` plus the closest nested `AGENTS.md`. Request another pass
or a narrower focus with `@codex review` in a PR comment.

Codex review is advisory. Merge availability is controlled by deterministic CI
and normal maintainer review, so a provider outage cannot strand a release PR.

## Required CI

`.github/workflows/ci-required.yml` classifies the PR's changed files and calls
the relevant Android, CLI, plugin, dashboard, upstream-contract, and public-docs
checks. Its final `Required checks` job succeeds only when every selected check
passes. Unaffected toolchains are skipped rather than started.

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
`ISSUE-BRIEF.md` with the issue body, existing discussion, and the verification
plan for the issue's surface. Open your agent session there and it starts
pre-briefed. Worktrees share the main checkout's object store, so several issue
branches can run concurrently. `ISSUE-BRIEF.md` is git-ignored. Tear down with
`git worktree remove ../hr-issue-<N>`.

## Setup (one-time)

The workflows can only apply labels that already exist. Create them once:

```bash
gh label create "area:android"            -c "1d76db" -d "Kotlin app"
gh label create "area:cli"                -c "0e8a16" -d "desktop/ Node CLI"
gh label create "area:plugin"             -c "fbca04" -d "plugin/ Python relay + tools"
gh label create "area:dashboard"          -c "c5def5" -d "plugin/dashboard React UI"
gh label create "area:docs"               -c "bfd4f2" -d "docs/ or user-docs/"
```

## Operational notes

- **Activation lag.** Issue-triggered workflows run the copy on the default branch
  (`main`). Changes stay dormant on `dev` until a release merge lands them on main.
- **No model cost for issue labeling.** The issue workflow is deterministic
  `github-script`; Codex review usage is accounted through the connected Codex plan.
- **No write-access escalation here.** Issue triage cannot push code or open PRs.
  Auto-attempting a fix from untrusted issue text remains intentionally out of scope.
