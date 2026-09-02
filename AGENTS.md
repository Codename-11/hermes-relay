# AGENTS.md

Universal agent instructions for **Hermes-Relay**. This is the entry point for any
coding agent (Claude Code, Codex, Cursor, etc.).

## Read this first

This file is the provider-neutral canonical agent context. Read it before
touching code, then `docs/spec.md` and `docs/decisions.md`. Provider adapters
such as **[CLAUDE.md](CLAUDE.md)** import this file instead of duplicating
policy. They do not redefine the branch, release, hotfix, or verification
contract here and in `RELEASE.md`.

- Release process → **[RELEASE.md](RELEASE.md)**
- Contributor setup → **[CONTRIBUTING.md](CONTRIBUTING.md)**
- Gateway/session/reconnect testing → **[docs/gateway-contract-testing.md](docs/gateway-contract-testing.md)**
- Android local/cloud verification → **[docs/android-build-lane.md](docs/android-build-lane.md)**
- Android emulator lanes → **[docs/android-emulator-testing.md](docs/android-emulator-testing.md)** — suggest the smallest relevant API 36 lanes; never run the full matrix automatically
- `android_*` toolset + MCP → **[docs/mcp-tooling.md](docs/mcp-tooling.md)**
- Follow-ups / deferred work / known gaps → **[docs/project/TODO.md](docs/project/TODO.md)** (the single home for "what's next" — never DEVLOG, never scattered code comments)

## Branch contract

| Contract item | Canonical source or target |
|---|---|
| Integration branch | `dev`; normal feature, fix, docs, and chore PRs target `dev` |
| Integration authority | `origin/dev`; local `dev` is a fast-forward-only mirror, never a private staging queue |
| Release branch | `main`; release history and hotfix integration only |
| Production tag source | The new `main` tip after an approved `dev` → `main` release PR, or after an approved hotfix PR to `main` |
| Candidate tag source | An exact release-prepared and tested `dev` SHA; prerelease suffix required (`-alpha`, `-beta`, or `-rc.N`) |
| Staging source | An exact tested `dev` SHA or release-candidate tag; staging is an environment, never a branch |
| Production source | Immutable `android-v*`, `server-v*`, or `desktop-v*` tags, selected by surface |
| Hotfix base | The immutable production tag for the affected surface |
| Back-merge target | `dev`; stable hotfixes reconcile automatically when the exact tested merge is conflict-free, otherwise through a PR |

Feature completion means merged and verified on `dev`; it does not mean
released. A release train is separate work owned by a Forge release
issue/session: reconcile only the affected surface version and notes on `dev`,
open the `dev` → `main` release PR, tag the resulting `main` tip, publish the
surface artifacts, deploy or roll out, and verify the live result. Never create
a staging branch.

A normal `dev` → `main` release needs no back-merge: the released integration
parent is already in `dev`. A production-tag hotfix is different. After its
stable release succeeds, `Release Backmerge` prepares a `dev`-first merge
commit, runs the same path-aware required checks on that exact SHA, verifies
that `dev` has not moved, and fast-forwards `dev`. Conflicts, failed checks,
stale refs, or denied branch updates fail closed and require a reconciliation
PR; never resolve those cases by choosing a side automatically.

### Local integration discipline

- Fetch `origin/dev` before creating a task branch or worktree; do not base new
  work on a stale local `dev` ref.
- Keep the primary local `dev` checkout tracked-clean and update it only with
  `git merge --ff-only origin/dev`. Feature, fix, docs, release-prep, and
  integration commits belong on their own branches and reach `dev` through PRs.
- When several reviewed branches must move together, combine them on a named
  `integration/<batch>` branch in its own worktree, then open one PR to `dev`.
  An integration branch is not a second `dev` and must not become a hidden queue.
- One coordinator owns final base refresh, required checks, and merges while
  concurrent worktrees continue independently.

## Non-negotiables (the short list)

- **Vanilla Hermes path = upstream-only.** The standard (no-plugin) connection
  uses the upstream Dashboard/Gateway for chat, authentication, Manage, sessions,
  and Vanilla Hermes voice. The API server is an explicit API-only/headless
  compatibility surface; Relay adds optional extensions. A Gateway-owned
  conversation never changes transport because Gateway auth or reachability
  changes. This
  path must work against unmodified upstream hermes-agent. Server-side needs go
  through upstream PRs or the optional relay plugin, never fork patches.
- **Verify endpoints against upstream** (`gateway/platforms/api_server.py` /
  `tui_gateway/server.py` in hermes-agent) before assuming a route exists.
- **Use the Gateway contract lab when its boundary changes.** Changes to
  Gateway chat events, session identity/resume/activation, streaming completion,
  queue ownership, reconnect/lifecycle recovery, or authoritative history must
  reuse or extend the declarative fixture scenarios, run the relevant Android
  instrumentation when rendered/lifecycle behavior is affected, and run the
  scenario manifest through current-upstream conformance. Physical ADB
  certification is required only when device/runtime behavior is claimed. All
  of these lanes are on demand; do not add scheduled execution without explicit
  approval.
- **Conventional Commits + `main`/`dev` branching.** Normal branches start at
  current `origin/dev` and PR back to `dev`; merge commits/no-ff are the
  repository policy.
  Version bumps happen only on a release-prep branch targeting `dev`, and
  production tags are cut only from `main`.
- **Android:** Jetpack Compose only (no XML), kotlinx.serialization (no Gson),
  OkHttp (no Ktor), `wss://` only. While editing, use only the narrow local
  compile or focused test needed for feedback, through `scripts/android-lane.ps1`
  on Windows. Once an exact commit is already pushed, prefer the `Android
  On-Demand` workflow for lint, the focused shards, both-flavor assemblies, and
  release smoke; isolated cloud jobs may run concurrently. Do not push solely
  to obtain cloud compute without push authorization, and do not duplicate a
  preset already running for the same SHA. Full local verification remains
  available through `scripts/dev.bat prepush` (or `./scripts/dev.sh prepush`)
  when explicitly wanted or when cloud execution is unavailable.
  Physical-device checks and APK installation remain separately owned local
  evidence.
- **Plugin (Python 3.11+):** aiohttp + asyncio (no threading), type hints
  everywhere, structured `logging` (no `print`). **Desktop CLI (Node ≥21):**
  zero runtime deps, strict TS + ES modules, ship compiled `dist/`. Contributor
  commands and the development loop live in `CONTRIBUTING.md`.

## Review guidelines

- Report only actionable correctness, security, compatibility, or release-risk
  findings; avoid stylistic preferences unless they violate a documented rule.
- Treat the vanilla Hermes upstream boundary as release-critical. Flag any
  default-path dependency on relay-only or fork-only server behavior.
- Check that changes preserve public-repo writing hygiene and do not expose
  secrets, private infrastructure, or personal information.
- Use the affected surface's CI result as evidence, but do not imply Android UI
  or device behavior was proven without an explicit on-device verification.
- Prioritize findings that warrant holding the merge. State the impacted path
  and the concrete failure mode.

## Automated public issue triage

New public issues may receive one clearly labeled **Hermes-Relay automated
triage** reply. That first response may classify the report with existing
type/area labels, point to related issues or current code/docs, ask for safe
sanitized diagnostics, and flag the thread for maintainer review.

GitHub attributes that reply to the repository-scoped
`hermes-relay-triage[bot]` App, never to a maintainer's personal account.

The automated lane may assign only the fixed maintainer account `Codename-11`
as follow-up ownership; that assignment does not imply acceptance, priority,
implementation, or a release commitment. It never closes, milestones,
prioritizes, promises a fix/release/timeline, chooses another assignee, or
continues replying after its first response. A related issue is not
automatically a duplicate. Human maintainer comments and decisions remain
authoritative; read the complete live thread before acting on an issue.

## Automated public PR intake

New external-contributor, non-draft pull requests may receive one clearly
labeled **Hermes-Relay automated PR intake** reply from
`hermes-relay-triage[bot]`. Owner-authored `Codename-11` PRs and bot PRs are
dropped before model dispatch. For eligible PRs, the intake compares the live PR
metadata/body and changed-path list with trusted `origin/dev` policy and
`.github/pull_request_template.md` without checking out or executing contributor
code. It may add genuine area labels plus `documentation`, `ci`, or
`needs-maintainer-review` and point out missing intake evidence.

The automated lane never approves, requests changes, merges, closes, assigns,
requests reviewers, milestones, prioritizes, pushes commits, edits PR text,
reruns workflows, applies `review-candidate`, or claims code correctness. Human
maintainer review and CI remain authoritative.

## Public-repo writing hygiene

Everything committed is public. In CHANGELOG, DEVLOG, README, docs, and release
notes:

- **No personal names** — attribute impersonally; identity lives in git + the
  signing cert.
- **No private infrastructure** — real hostnames/IPs, internal deployment names,
  `~/SYSTEM.md`. (Generic example IPs in setup docs are fine.)
- **No AI/assistant process self-narration** ("I should have…", course
  corrections) — state the technical conclusion only.
- **No internal jargon or fork/branch plumbing** in user-facing notes.
- **CHANGELOG** uses Keep-a-Changelog grouping; condense the version block to
  crisp public bullets at release-prep (see RELEASE.md §2 "Scrub for public
  distribution"). **DEVLOG** is a depersonalized, factual engineering log.
