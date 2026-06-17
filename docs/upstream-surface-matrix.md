# Hermes-Relay Surface Matrix

Updated: 2026-06-17

This matrix records the v1.0.0 route ownership contract. It is meant to keep
future app, plugin, and agent work honest about what is standard upstream
Hermes, what belongs to the Relay plugin, and what is only legacy compatibility.

Verified upstream source snapshot:

- Repository: `NousResearch/hermes-agent`
- Commit: `55cb4103beba5822303c06b662635e1491ae72f5`
- Primary files checked: `gateway/platforms/api_server.py`,
  `hermes_cli/web_server.py`, `hermes_cli/dashboard_auth/routes.py`,
  `tui_gateway/server.py`, `hermes_cli/plugins.py`,
  `hermes_cli/plugins_cmd.py`

## Ownership

| Surface | Owner | Requires Relay | Android usage | Notes |
|---------|-------|----------------|---------------|-------|
| `/v1/capabilities` | Upstream API server | No | Capability probe | Source of truth for API-server features; current upstream advertises no audio API. |
| `/v1/chat/completions` | Upstream API server | No | Chat fallback | OpenAI-compatible streaming. Tool events may degrade to inline annotations. |
| `/v1/runs`, `/v1/runs/{id}/events` | Upstream API server | No | Chat fallback | Structured run events and stop/approval support. |
| `/api/sessions/*` | Upstream API server | No | Session CRUD and SSE chat | Native upstream session list/create/read/update/delete/messages/fork/chat/chat-stream. Bootstrap is old-build fallback only. |
| `/v1/skills`, `/v1/toolsets` | Upstream API server | No | Discovery | Read-only API-server skill/toolset inventory. |
| Dashboard `/api/status`, `/api/auth/me` | Upstream dashboard | No | Manage auth | Dashboard cookie/session path; separate from API bearer. |
| Dashboard `/api/auth/ws-ticket`, `/api/ws` | Upstream dashboard/tui_gateway | No | Preferred chat transport | Standard gateway chat path with live reasoning/thinking events. |
| Dashboard `/api/audio/transcribe`, `/api/audio/speak` | Upstream dashboard | No | Standard voice | Manage sign-in unlocks standard voice. API server has no `/v1/audio/*` route today. |
| Dashboard `/api/config`, `/api/profiles/*`, `/api/env`, `/api/model/*`, `/api/mcp/*` | Upstream dashboard | No | Manage | Do not proxy through Relay. |
| `/pairing/*`, `/sessions`, `/voice/*`, `/desktop/*`, `/media/*`, `/notifications/*` on Relay | Hermes-Relay plugin/server | Yes | Relay pairing, terminal, bridge, relay voice, desktop tools | Owned by `plugin/relay/server.py`; Android must gate behind Relay readiness/session grants. |
| Dashboard `/api/plugins/hermes-relay/*` | Hermes-Relay dashboard plugin | Yes for live data | Relay dashboard tab | FastAPI plugin backend proxies loopback requests to the Relay server. |
| `hermes relay doctor` | Hermes-Relay plugin CLI | No for diagnostics | Operator/agent diagnostics | Reports standard route reachability, plugin layout, Relay loopback state, and legacy bootstrap presence. |
| `hermes_relay_bootstrap` routes | Legacy compatibility monkeypatch | No, but non-standard | Fallback only | Installed via `.pth` by legacy installer. Keep only for older Hermes builds or compatibility-only gaps. |

## Voice Surfaces (standard vs. relay)

Voice splits the same way as everything else: standard rides vanilla upstream,
relay is the additive power path.

### Route ownership

Be explicit, because the names look similar:

| Route | Owner | Requires Relay | Notes |
|-------|-------|----------------|-------|
| Dashboard `POST /api/audio/transcribe` | **Upstream** (dashboard web_server) | No | Standard STT. Cookie/session auth. |
| Dashboard `POST /api/audio/speak` | **Upstream** (dashboard web_server) | No | Standard TTS. Accepts ONLY `{text}` — one-shot, config-driven. |
| Dashboard `GET /api/audio/elevenlabs/voices` | **Upstream** (dashboard web_server) | No | Voice-list helper. |
| `POST /voice/transcribe`, `POST /voice/synthesize` | **Relay** (`plugin/relay/voice.py`) | Yes | Basic STT/TTS. `/voice/synthesize` wraps upstream `text_to_speech_tool` + adds per-request enhanced overrides. |
| `GET /voice/config` | **Relay** | Yes | Reports `tts:`/`stt:` + a provider-aware `tts.enhanced` capability block. |
| `GET/PATCH /voice/output/config`, `POST /voice/output/session`, `GET /voice/output/{id}` (WS) | **Relay** (`plugin/relay/voice_output.py` + `plugin/voice_lab/`) | Yes | Streaming TTS renderer. **No upstream equivalent** — the API server advertises `audio_api: false` and there is no upstream streaming/WS audio route (`/v1/audio/*` PR is unmerged). |
| `GET/PATCH /voice/realtime/*`, `GET/PATCH /voice/realtime-agent/*` | **Relay** | Yes | Provider playground + the experimental Realtime Agent engine. Not upstream. |

**Bottom line: every `/voice/*` route is relay-owned. The only upstream audio surface is the dashboard `/api/audio/*` set.**

### Enhanced voice (provider-native voice/tone control)

| Capability | Standard path (vanilla upstream) | Relay path (plugin) |
|------------|----------------------------------|---------------------|
| Where it applies | Dashboard `/api/audio/speak`. | Relay `/voice/synthesize` (basic) **and** `/voice/output/*` (streaming renderer, the default when `voice_output_enabled`). |
| Control model | **Config-only.** Read from `~/.hermes/config.yaml` `tts.<provider>.*`. `/api/audio/speak` takes only `{text}`. The phone changes enhanced behavior only via Manage `PUT /api/config` (global), never per utterance. | **Synthesize:** per-request override (`voice`/`model`/`audio_tags`/`persona_prompt`/`language`), advertised via `/voice/config` `tts.enhanced`. **Streaming:** per-profile `voice_output:` config (incl. `auto_speech_tags` for xAI), set via `PATCH /voice/output/config`. Neither mutates the basic `tts:` config. |
| Feature coverage | Whatever the server config enables (any provider). | **Synthesize:** Gemini (`voice`/`model`/`audio_tags` tone-tag rewrite/`persona_prompt`) + xAI (`voice`→`voice_id`/`audio_tags`→`auto_speech_tags`/`language`). **Streaming:** xAI `auto_speech_tags` (the `voice_lab` renderer has xai/openai/elevenlabs but **no Gemini provider**). Others config-only. |
| How it's implemented | Upstream `text_to_speech_tool` reads config; relay/app do nothing special. | **Synthesize:** relay merges overrides into a config copy and calls the upstream generator (`_generate_gemini_tts`/`_generate_xai_tts`) directly. **Streaming:** the relay applies `upstream_voice.apply_xai_speech_tags()` to the chunk text before the `voice_lab` `xai_tts` renderer. No fork patch; all upstream imports isolated in `plugin/relay/upstream_voice.py`. |

Upstream parity notes (verify against `tools/tts_tool.py`): Gemini `audio_tags` needs a
`gemini-3.1*tts` model and runs a hidden auxiliary-LLM rewrite that **fails soft**
(unavailable aux client → untagged text). OpenAI TTS exposes only `voice` + `speed`
upstream — the `gpt-4o-mini-tts` `instructions` tone param is **not** wired — so it is
intentionally not offered as a relay enhanced provider. The standard path stays vanilla
upstream regardless; the relay enhanced surface is purely additive.

## Plugin Lifecycle Contract

The upstream Hermes plugin manager installs, updates, enables, disables, and
removes a plugin tree under `~/.hermes/plugins/<name>`.

Hermes-Relay's plugin tree is currently `plugin/`, so the plugin-manager install
identifier is:

```bash
hermes plugins install Codename-11/hermes-relay/plugin
```

This manages the plugin code, CLI command registration, dashboard manifest, and
agent tools. The optional compatibility startup hook is plugin-owned through:

```bash
hermes relay compat status
hermes relay compat install
hermes relay compat remove
```

Those commands install or remove only `hermes_relay_bootstrap.pth` in the target
Python environment. New hooks are path-pinned to
`plugin/hermes_relay_bootstrap/` so plugin-manager installs do not depend on a
repo-root Python package. Existing legacy hooks that import the top-level
`hermes_relay_bootstrap` shim are still detected and removable.
`hermes relay doctor --json` includes the same status.

The upstream plugin manager still does not clean legacy installer artifacts such
as editable/root package installs, systemd user units, shell shims, or external
skill-path entries. Clean removal of those full legacy installs remains
`uninstall.sh` until upstream plugin lifecycle hooks or plugin-owned service
commands exist. For a bounded cleanup handoff, see
`docs/legacy-cleanup-agent-prompt.md`.

## Legacy Artifact Ownership

| Artifact | Created by | Owner now | Cleanup command | Remaining risk |
|----------|------------|-----------|-----------------|----------------|
| Plugin tree at `~/.hermes/plugins/hermes-relay` | `hermes plugins install ...` or legacy `install.sh` symlink | Upstream plugin manager for plugin-manager installs; `uninstall.sh` for legacy symlinks | `hermes plugins remove hermes-relay` or `bash ~/.hermes/hermes-relay/uninstall.sh` | Plugin manager removal does not remove the legacy clone or host service. |
| `hermes_relay_bootstrap.pth` in Hermes venv `site-packages` | `hermes relay compat install` or legacy `install.sh` | Plugin compat CLI | `hermes relay compat remove --all`; legacy `uninstall.sh` delegates to this when available | Keep only for older Hermes builds or compatibility-only API gaps. |
| Editable/root Python package install `hermes-relay` | Legacy `install.sh` | Legacy installer | `bash ~/.hermes/hermes-relay/uninstall.sh` | Needed only for old clone/update workflow and shell shim entry points. |
| Systemd user unit `~/.config/systemd/user/hermes-relay.service` | Legacy `install.sh` | Legacy installer | `bash ~/.hermes/hermes-relay/uninstall.sh` | Plugin manager has no service lifecycle hook yet; manual `hermes relay start` remains the plugin-native foreground path. |
| Shell shims `hermes-pair`, `hermes-status`, `hermes-relay`, `hermes-relay-update`, `hermes-relay-tailscale` | Legacy `install.sh` | Legacy installer | `bash ~/.hermes/hermes-relay/uninstall.sh` | Current upstream CLI prefers `hermes pair` and `hermes relay`; shims are older-build/script compatibility. |
| `skills.external_dirs` entry pointing at the Relay clone | Legacy `install.sh` | Legacy installer | `bash ~/.hermes/hermes-relay/uninstall.sh` | Plugin tools do not require this; retained only for legacy slash-skill discovery. |
| Relay clone at `~/.hermes/hermes-relay` | Legacy `install.sh` | Legacy installer | `bash ~/.hermes/hermes-relay/uninstall.sh` unless `--keep-clone` | Removing the clone also removes local scripts and legacy skill files; shared Hermes state is preserved. |
| QR signing secret `~/.hermes/hermes-relay-qr-secret` | Pairing CLI/dashboard mint | Operator identity state | `bash ~/.hermes/hermes-relay/uninstall.sh --remove-secret` | Kept by default so reinstall preserves QR signing identity. |

## App Flow Rule

The app should present Standard as the default path:

1. Connect API server and dashboard.
2. Sign in to Manage when dashboard auth is required.
3. Use gateway chat when `/api/ws` is ready; otherwise fall back to API-server
   SSE.
4. Use standard dashboard voice when audio routes are present.
5. Offer Relay pairing only for Relay-owned power features.

When Auto voice selects Relay because a paired Relay is healthy, the UI should
make that active route visible and continue to fall back to Standard voice when
Relay fails but dashboard audio is ready.

Setup QR payloads may include top-level `dashboard_url`. Android uses that value
for Manage and standard dashboard voice; when absent it derives the conventional
same-host `:9119` dashboard URL from the API server.
