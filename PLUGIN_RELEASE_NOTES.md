# Hermes-Relay-Plugin v__VERSION__

**Release Date:** June 20, 2026
**Since the previous plugin release:** A new, removable **enhancement layer** that lets the relay teach the agent things only the relay knows — starting with sensitive-media classification — plus provider-aware enhanced voice and an isolated, TUI-tuned tmux for relay terminals.

This release adds a clean way for the relay to extend the agent without forking or touching the user's soul/memory. The first use is **sensitive-media classification**: the relay appends a small, auditable system-prompt block teaching the agent to mark private/NSFW media so the paired phone can blur it — with sensitivity staying model-emitted. It's on by default for relay installs (installing the relay is the opt-in), reversible from the dashboard or an env flag, fully visible over a new audit route, and a complete no-op on vanilla upstream. Voice gains provider-aware controls for Gemini and xAI, and relay terminals now run on a dedicated, correctly-configured tmux.

## What's changed

### Added
- **Relay enhancement layer + agent-context injection.** A reusable, removable layer that injects auditable, fenced blocks into the agent's system prompt at plugin-load. Fail-open at every step (seam absent / block build throws ⇒ base prompt unchanged), config-gated, and a byte-for-byte no-op on vanilla upstream. Built to be retired per-surface as upstream adds a context hook — the same pattern as the bootstrap route shims. See `docs/plans/2026-06-20-relay-enhancement-layer.md`.
- **Sensitive-media classification (first block).** Teaches the agent to mark private/NSFW media with the client's spoiler convention so the phone blurs it per the user's setting. **On by default for relay installs**; opt out with `RELAY_AGENT_CONTEXT_ENABLED=0` or the dashboard toggle. Sensitivity stays model-emitted — no relay-side or on-device classifier. No soul/memory is touched.
- **`GET /context/injected` audit route.** The relay exposes exactly what it would inject (loopback-open, bearer-gated remotely), so the injection is never hidden — surfaced in the Android chat "What the agent sees" sheet as "Relay context (server-side)".
- **Dashboard Agent-context controls.** The Relay management tab gained a master toggle and per-block toggles (labeled experimental / server-side / removable), shown on-by-default for relay installs.
- **Provider-aware enhanced voice (Gemini + xAI).** `/voice/synthesize` accepts per-request overrides so a paired client can steer a Gemini voice/model with expressive tone tags, or an xAI voice with expressive speech tags, without changing the server's global voice config.

### Changed
- **Relay terminals run on an isolated, TUI-tuned tmux.** Sessions spawn on a dedicated tmux server/socket with a generated config — `escape-time 0`, truecolor `tmux-256color`, `mouse`/`focus-events` on, `status off` — so editors and full-screen tools behave correctly without touching the user's personal tmux.

### Fixed
- **Relay voice synthesis no longer leaves temporary audio files behind** on the server.
- **Clearer voice errors.** Standard voice rejects an over-long recording before uploading and returns a helpful message for audio the server can't read, instead of a generic HTTP error.

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
