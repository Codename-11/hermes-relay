# Hermes-Relay-Server v__VERSION__

**Release Date:** August 2, 2026

This release adds Realtime Agent final-answer-only speech and Relay-hosted declarative plugin pages for Android.

Android clients can keep voice progress visual until the settled answer and review agent-created native plugin pages before keeping them. Standard chat and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Added

- **Realtime Agent final-answer-only speech.** Session creation accepts an optional `final_answer_only` flag. When enabled, the provider skips routine acknowledgements, spoken progress, service updates, and intermediate commentary, then speaks the settled Hermes answer. Approval and confirmation prompts, along with blocking failures, remain audible so required user action is not hidden.
- **Agent-created declarative Android plugin pages.** New Relay tools create bounded JSON-only drafts and expose them through authenticated plugin routes. Android owns enablement, write grants, exact-revision approval, publication, and persistent removal. Generated pages reject executable code, arbitrary network requests, Android intents, traversal, symlink entries, oversized documents, and backend action requests.

## Install / update

    # Native upstream plugin path:
    hermes plugins install Codename-11/hermes-relay/plugin --enable

    # Classic install / update on a systemd host:
    curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/server-v__VERSION__/install.sh | bash
    # or, if already installed:
    hermes-relay-update

## Verify

    hermes relay doctor
    python scripts/check-plugin-version-sync.py --expect __VERSION__

---

Tag prefixes: Android releases use android-v*, Server releases use server-v*, and Desktop releases use desktop-v*.
