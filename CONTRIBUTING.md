# Contributing to Hermes-Relay

Thanks for your interest in contributing! Hermes-Relay is an indie, open-source project and every contribution — code, bug reports, docs tweaks, feature ideas — genuinely shapes where it goes next.

This guide covers the developer setup. For the release recipe see [RELEASE.md](RELEASE.md); for architecture context see [docs/spec.md](docs/spec.md) and [docs/decisions.md](docs/decisions.md).

## Quick Start (Android)

1. **File > Open** the repo root in Android Studio
2. Wait for Gradle sync
3. **Run** (Shift+F10) to deploy to emulator or device

That's it — no extra setup or credentials required for a debug build.

## Dev Scripts

Helper scripts for common development tasks:

```bash
scripts/dev.bat build      # Build the sideload debug APK
scripts/dev.bat compile    # Compile sideload Kotlin only
scripts/dev.bat test-one "com.hermesandroid.relay.SomeTest"  # Run one test class
scripts/dev.bat install-fast  # Build arm64 only + install + launch
scripts/dev.bat release    # Build signed release APK
scripts/dev.bat bundle     # Build release AAB for Google Play
scripts/dev.bat run        # Build sideload + install + launch + logcat
scripts/dev.bat test       # Run sideload debug unit tests
scripts/dev.bat version    # Show current version
scripts/dev.bat relay      # Start relay server (dev, no TLS)
```

### Review bundles

Maintainers can produce a matched Android + Relay handoff for one pull request
without cutting a release. Apply the `review-candidate` label to an open PR
targeting `dev`. The short-lived artifact contains a side-by-side
**HR Candidate** APK, Relay packages/source from the same exact PR commit,
provenance, checksums, and install/rollback guidance. While the label remains
applied, a new PR head commit automatically replaces any in-progress build with
a bundle for the new head.
For a first-time fork contributor, GitHub may hold the first run for explicit
maintainer approval before any untrusted code executes.
When an opted-in candidate run completes, a separate trusted reporter creates or
updates one PR comment with the exact source SHA, artifact link, expiry, and
concise install and rollback guidance. Skipped workflow shells for unlabeled PRs
do not create comments.

Review bundles never bump versions, create tags, upload to Play, or replace the
stable Android app. Relay review still requires a staging Hermes instance or an
explicit immutable snapshot/rollback window because two Relay plugins cannot
own the same tools and hooks in one Hermes process. See
[Review builds and release candidates](docs/review-candidates.md).

Linux/macOS equivalent lives at `scripts/dev.sh`.

### Fast Android iteration

Gradle's daemon, local build cache, configuration cache, and parallel task
execution are enabled for repeat local builds. Keep the same Gradle JVM
configuration between invocations and do not add `--no-daemon` to normal dev
commands; a different heap or Java home starts a separate daemon and discards
the warm-process benefit.

Use the narrowest command that proves the change:

1. `scripts/dev.bat compile` for a Kotlin compile check.
2. `scripts/dev.bat test-one "<fully-qualified-class-or-pattern>"` for a focused regression.
3. `scripts/dev.bat install-fast` when the result must run on the connected
   arm64 phone. This passes `-Phermes.devAbi=arm64-v8a`, avoiding the x86,
   x86_64, and armeabi-v7a native libraries in the local APK.
4. `scripts/dev.bat prepush` before pushing Android work.

`install-fast` is intentionally phone-specific. Use `install` for a universal
sideload debug APK or when the target ABI is not arm64. Release builds remain
universal and are unaffected unless `-Phermes.devAbi` is explicitly supplied.

## Repository Structure

```
hermes-relay/
├── app/                       # Android app (Kotlin + Jetpack Compose)
├── plugin/                    # Hermes agent plugin + relay server (Python + aiohttp)
│   ├── relay/                 # Canonical relay server (channels, auth, media, voice)
│   ├── tools/                 # android_* tool implementations
│   └── pair.py                # QR pairing CLI
├── skills/                    # Hermes agent skills (pair, self-setup)
├── user-docs/                 # VitePress documentation site
├── docs/                      # Spec, architecture decisions, security notes
├── scripts/                   # Dev helper scripts
├── .github/workflows/         # CI + release pipelines
└── gradle/                    # Wrapper + version catalog
```

The legacy `relay_server/` directory is a thin compatibility shim around `plugin.relay` that keeps the `python -m relay_server` entry point working.

## Tech Stack

| Component | Stack |
|-----------|-------|
| **Android App** | Kotlin 2.4, Jetpack Compose, Material 3, OkHttp |
| **Relay Server** | Python 3.11+, aiohttp |
| **Serialization** | kotlinx.serialization |
| **Build** | AGP 9.3.1, Gradle 9.6.1, JVM toolchain 17 |
| **CI/CD** | GitHub Actions (lint, build, test, signed APK artifacts) |
| **Min SDK** | 26 (Android 8.0) / Target SDK 36 |

## Issues and automated triage

New issues may receive one first response headed **Hermes-Relay automated
triage**. It reads the live report against current code, documentation, related
issues, and public release state; it may add existing type/area labels and ask
for a focused, safe diagnostic such as the app version, interaction mode, or a
sanitized log excerpt.

GitHub displays the response as authored by `hermes-relay-triage[bot]`, a
repository-scoped App rather than a maintainer's personal account.

That reply is an acknowledgement and initial analysis, not a maintainer
decision. The automated path does not close issues, assign work, set milestones
or priority, promise a fix or release, or continue the conversation. It will
say when a maintainer needs to review the remaining question, and a maintainer
will follow up on the thread.

## Running the Relay Locally

Only needed if you're working on the bridge, voice, notifications, or media features. Chat alone doesn't need the relay.

```bash
# From the hermes-agent venv (if you installed via the one-liner):
hermes relay start --no-ssl

# Or from a repo checkout:
python -m plugin.relay --no-ssl
```

See [docs/relay-server.md](docs/relay-server.md) for TLS, systemd, Docker, and full configuration.

## Plugin Development

End users should install via the one-liner in the README. For local development from a clone:

```bash
# One-shot copy:
cp -r plugin ~/.hermes/plugins/hermes-relay

# Or symlink for live edits:
ln -s "$PWD/plugin" ~/.hermes/plugins/hermes-relay
```

After the plugin is in place, restart hermes and verify pairing with `hermes-pair` (shell shim) or `/hermes-relay-pair` in any Hermes chat surface. The 18 `android_*` tools register regardless of hermes-agent version.

> **Note:** A top-level `hermes pair` CLI sub-command is not currently exposed — hermes-agent v0.8.0's top-level argparser doesn't yet forward to third-party plugins' `register_cli_command()` dict. Use the slash command or the dashed shim instead.

## Commit Conventions

We follow [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`.

**Branching model: `main` + `dev`.** Feature branches — `feature/<name>`,
`fix/<name>`, `docs/<name>`, `chore/<name>` — branch from current `origin/dev`
and merge back into `dev` via merge-commit/no-ff PRs. This includes small
documentation fixes.
`main` is release history, not the normal contribution target; it receives
approved release PRs from `dev` and focused hotfix PRs based on production tags.

Pull requests use [the repository template](.github/pull_request_template.md).
Keep the body grounded: describe the outcome and focused changes, list exact
verification, include visual evidence when applicable, state compatibility or
risk, and preserve contributor lineage when replacing or salvaging prior work.
Check an item when it is satisfied or when its N/A rationale is written in the
body; do not use checked boxes as a substitute for evidence.

`origin/dev` is the canonical integration ref. Keep local `dev` as a clean,
fast-forward-only mirror and create each task in its own branch/worktree from the
current `origin/dev`. Do not accumulate unpublished commits on local `dev`. If a
maintainer needs to combine several reviewed branches, use a temporary
`integration/<batch>` branch and merge that branch through a normal PR to `dev`.
See [docs/worktree-workflow.md](docs/worktree-workflow.md) for the concurrent
worktree procedure.

Feature completion means merged and verified on `dev`; it does not mean the
change has been released. A separate Forge release issue/session owns release
preparation, the `dev` → `main` release PR, tagging, artifacts, rollout or
deployment, and live verification. Release-prep commits use a dedicated branch
and PR into `dev`; tags are cut from the resulting `main` tip as
`android-vX.Y.Z`, `server-vX.Y.Z`, or `desktop-vX.Y.Z`. See
[RELEASE.md](RELEASE.md) for the full release and hotfix procedures.

## Stale PR salvage and contributor credit

A valuable pull request can become unsafe to merge when `dev` has materially
changed around it. Maintainers may create a replacement **salvage PR** from the
current `dev` instead of resolving a stale branch by choosing whole conflict
sides.

A salvage PR must:

- Link the original PR and contributor in its title or opening summary.
- Recover only the intended feature; unrelated fork, release, signing, and
  generated migration changes stay out.
- Preserve the original commit author when a substantive commit can be safely
  cherry-picked.
- Use a verified `Co-authored-by: Name <email>` trailer when the implementation
  must be reconstructed or substantially rewritten.
- Include a `Lineage` section listing source and superseded PRs, plus a concise
  explanation of integration changes made for current `dev`.
- Run current verification rather than relying on checks from the stale branch.
- Leave a comment linking the replacement before the source PR is closed.

The maintainer remains the committer for integration commits. The original
contributor remains the author or co-author of the recovered work. Do not guess
an email address: use the source commit's verified address or ask the
contributor.

## Localization contributions

English resources are canonical and Android locale catalogs must retain exact
resource and format-argument parity. Read [docs/localization.md](docs/localization.md)
before changing user-facing strings or adding a language.

Translation PRs should cover one locale or one clear catalog refresh. They must
not include custom APK publishing, signing configuration, version bumps, or
fork-specific branding. Run:

```bash
python scripts/check-android-locales.py
./gradlew lint
```

Update `docs/localization-status.json` with the actual review level. AI-assisted
translations may ship as `ai-translated`; do not claim fluent review unless a
review reference is recorded. Focused correction PRs from fluent contributors
are the canonical way to improve wording and can advance a locale to
`community-reviewed` or `verified` under `docs/translation-playbook.md`.
Translated READMEs use separate `README.<locale>.md` files; `README.md` remains
the canonical project description. User docs may be added incrementally under
`user-docs/<locale>/`, with links back to canonical English reference material.

## Changelog & writing conventions

This is a **public repo** — `CHANGELOG.md`, `DEVLOG.md`, the README, and everything under `docs/` ship publicly. Keep them clean:

- **`CHANGELOG.md`** follows [Keep a Changelog](https://keepachangelog.com/) (Added / Changed / Fixed). Append your change to the `## [Unreleased]` block in the PR. Entries can carry detail while they accumulate, but at release-prep the version block is **condensed to crisp public bullets** (1–2 lines each) — the deep "how we debugged it" narrative belongs in commit messages and `DEVLOG.md`, not the public changelog.
- **`DEVLOG.md`** is a factual engineering log — what changed, why, and how it was verified. Keep it depersonalized and third-person; it's a record, not a diary.
- **No non-public wording anywhere committed:** no personal names (attribute impersonally — identity lives in git history), no real server hostnames/IPs or internal deployment names, no AI/assistant process self-narration, no fork/branch plumbing in user-facing notes. Generic example IPs in setup docs are fine.

Release notes (`RELEASE_NOTES.md`, `app/src/main/assets/whats_new.txt`, `docs/play-store-listing.md`) are theme-framed and user-facing; see [RELEASE.md](RELEASE.md) §2 "Scrub for public distribution" for the full checklist.

## Testing

- **Android pre-push gate:** `scripts\dev.bat prepush` on Windows or
  `./scripts/dev.sh prepush` on macOS/Linux. This runs the Android repository
  checks, Google Play debug lint, and the same focused unit-test shard used by
  CI in one cached Gradle invocation. Run it before pushing Android PR updates
  to catch common hosted failures without waiting for another full Actions
  cycle; hosted CI remains the exhaustive all-variant gate.
- **Focused Android unit test:** `scripts/dev.bat test-one "<fully-qualified-class-or-pattern>"`
- **Android unit tests:** `scripts/dev.bat test` (runs the sideload debug JUnit + MockK + Compose suite)
- **Gateway contract lab:** [`docs/gateway-contract-testing.md`](docs/gateway-contract-testing.md)
  covers the on-demand vanilla-Gateway fixture, Android instrumentation,
  upstream conformance, and physical-device ADB certification. No contract or
  device lane is scheduled automatically.
- **Python tests:** `python -m unittest plugin.tests.test_<name>` from the repo root with the hermes-agent venv active. `pytest` works too but the pre-existing `conftest.py` imports a module that isn't always installed — `unittest` avoids that entirely.

CI is split into path-filtered workflows: `.github/workflows/ci-android.yml` (lint + build + test on app/Gradle changes), `.github/workflows/ci-server.yml` (syntax check + focused server tests on plugin/Python changes), and `.github/workflows/ci-desktop.yml` (desktop type/build/smoke checks). They run on pushes to `main` and `dev` and on PRs targeting either when their paths are touched.
Superseded Android runs on `dev` and PR refs are canceled automatically; `main`
runs are never canceled because each release-branch commit must complete its
independent validation.

## Questions?

- **Architecture context?** [docs/spec.md](docs/spec.md) covers protocols, UI layouts, and the channel model. [docs/decisions.md](docs/decisions.md) covers the forks in the road and why we picked what we did.
- Need help or want to explore an early idea? Start a [GitHub Discussion](https://github.com/Codename-11/hermes-relay/discussions).
- Found a reproducible bug or have a specific, actionable feature request? [Open an issue](https://github.com/Codename-11/hermes-relay/issues/new).
