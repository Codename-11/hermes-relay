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
scripts/dev.bat build      # Build debug APK
scripts/dev.bat release    # Build signed release APK
scripts/dev.bat bundle     # Build release AAB for Google Play
scripts/dev.bat run        # Build + install + launch + logcat
scripts/dev.bat test       # Run unit tests
scripts/dev.bat version    # Show current version
scripts/dev.bat relay      # Start relay server (dev, no TLS)
```

Linux/macOS equivalent lives at `scripts/dev.sh`.

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
| **Android App** | Kotlin 2.0, Jetpack Compose, Material 3, OkHttp |
| **Relay Server** | Python 3.11+, aiohttp |
| **Serialization** | kotlinx.serialization |
| **Build** | AGP 9, Gradle 8.13, JVM toolchain 17 |
| **CI/CD** | GitHub Actions (lint, build, test, signed APK artifacts) |
| **Min SDK** | 26 (Android 8.0) / Target SDK 35 |

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

**Branching model (as of 2026-04-19): `main` + `dev`.** Feature branches — `feature/<name>`, `fix/<name>`, `docs/<name>`, `chore/<name>` — branch off `dev` and merge back into `dev` via `--no-ff` PRs. `main` is released state only; it receives release merges from `dev` and nothing else. There is no straight-to-main exemption — even single-file typos go through `dev`.

Release-prep commits (version bump, changelog promotion) land on `dev` first, then a `release: vX.Y.Z` PR merges `dev` → `main` with `--no-ff`. The tag is cut from `main` after the merge. See [RELEASE.md](RELEASE.md) for the full release process.

## Testing

- **Android unit tests:** `scripts/dev.bat test` (runs JUnit + MockK + Compose testing)
- **Python tests:** `python -m unittest plugin.tests.test_<name>` from the repo root with the hermes-agent venv active. `pytest` works too but the pre-existing `conftest.py` imports a module that isn't always installed — `unittest` avoids that entirely.

CI is split into two path-filtered workflows: `.github/workflows/ci-android.yml` (lint + build + test on app/Gradle changes) and `.github/workflows/ci-relay.yml` (syntax check + unittest discover on plugin/Python changes). Both run on pushes to `main` and `dev` and on PRs targeting either.

## Questions?

- **Architecture context?** [docs/spec.md](docs/spec.md) covers protocols, UI layouts, and the channel model. [docs/decisions.md](docs/decisions.md) covers the forks in the road and why we picked what we did.
- **Something unclear?** [Open an issue](https://github.com/Codename-11/hermes-relay/issues/new) — we read every one, and "this contributing guide is confusing" is a completely fair bug report.
