# Hermes-Relay-Plugin v__VERSION__

**Release Date:** June 16, 2026
**Since the previous plugin release:** Easier setup and a fixed dashboard panel — plus mid-conversation `/relay` controls and a relay-status widget.

This release makes the relay plugin easier to install and live with. Setup now prompts for the optional voice-provider keys instead of asking you to hand-edit `.env`, tools-only hosts can install through the native `hermes plugins install` path, and the installer no longer breaks on `uv`-managed Hermes cores. The dashboard panel — which previously rendered as blank boxes on the host's design system — now displays correctly, and a header widget plus `/relay` slash commands surface relay state from anywhere. The standard no-plugin path needs none of this.

## What's changed

### Added
- **Guided env-key setup.** The plugin declares its optional voice-provider keys (`XAI_API_KEY`, `OPENAI_API_KEY`, `ELEVENLABS_API_KEY`) in its manifest, so `hermes plugins install` prompts for them (masked, with a "get yours" link) instead of requiring a hand-edited `.env`. The standard no-plugin path needs none.
- **Native install path.** Tools-only setups can install via `hermes plugins install Codename-11/hermes-relay/plugin`; the full relay still uses the curl `install.sh`.
- **`/relay` slash commands.** `relay status · devices · pair` are usable mid-conversation from any platform (CLI / Discord / TUI).
- **Dashboard relay-status widget.** A `Relay · connected / offline / unpaired` badge in the dashboard header, visible on every page.
- **Session-start relay health check.** A minimal, fully-guarded `on_session_start` hook records relay reachability without slowing the gateway.

### Fixed
- **Installer failed on uv-managed Hermes hosts.** `install.sh` assumed `pip` lived in the hermes-agent virtualenv, but environments created by `uv` (the upstream default) ship no `pip` module, so the editable install aborted at step 2. The installer now bootstraps `pip` via `ensurepip`, or falls back to `uv pip`, so the plugin installs cleanly on uv-managed cores.
- **Dashboard buttons rendered as blank boxes.** The host dashboard's Nous design-system `Button` / `Badge` use boolean variant flags (`outlined` / `ghost` / `invert`) and a `tone` prop — not the shadcn-style `variant` prop the plugin passed — so every button collapsed to a solid near-white fill with an invisible label. The plugin now translates its props to the design-system contract via an adapter and drops a label-hiding CSS reset.
- **Unreadable button labels.** Solid buttons in the relay dashboard panel inherited the container text colour, which matched their background; solid button variants now keep their proper contrast colour.

## Install

```bash
pip install hermes-relay==__VERSION__
```

## Verify

```bash
python -m relay_server --help
```

---

Tag prefixes: Android releases use `android-v*`, CLI releases use `cli-v*`. Historical
relay/plugin releases used `relay-v*` tags.
