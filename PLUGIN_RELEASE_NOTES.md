# Hermes-Relay-Server v__VERSION__

**Release Date:** August 6, 2026

This patch adds an optional Relay capability overlay so clients can present the reasoning effort levels supported by an exact provider and model without changing upstream model selection or chat behavior.

Standard chat, session history, and Vanilla Hermes voice remain upstream-owned and do not require this plugin.

## What's changed

### Added

- **Profile-aware reasoning capability resolution.** `POST /relay/model-capabilities` accepts exact provider/model pairs and returns ordered effort choices with explicit exactness and source metadata.
- **Bounded live discovery for local and authenticated providers.** Relay can query OpenAI Codex, GitHub Copilot, LM Studio, and Ollama Cloud capability surfaces with short timeouts, concurrency limits, isolated caches, and an explicit refresh option.
- **Capability advertisement.** `/relay/info` now advertises `model_reasoning_capabilities_v1` so clients can detect the optional overlay before using it.

### Security

- **Provider credentials remain host-local.** Remote requests require a paired session with the chat grant, and responses never serialize provider credentials.

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
