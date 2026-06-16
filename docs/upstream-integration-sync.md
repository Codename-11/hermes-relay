# Upstream Hermes Integration Sync

Last reviewed: 2026-06-07

This document tracks how Hermes-Relay integrates with Hermes upstream surfaces, which
parts use supported extension points, and which parts are compatibility layers that
should be removed once upstream has a stable equivalent. Use it before changing the
relay, dashboard, Android app, desktop app, bootstrap package, or user docs.

## Upstream References

- Plugins: https://hermes-agent.nousresearch.com/docs/user-guide/features/plugins
- Build a Hermes plugin: https://hermes-agent.nousresearch.com/docs/guides/build-a-hermes-plugin
- API server: https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- Tool Gateway: https://hermes-agent.nousresearch.com/docs/user-guide/features/tool-gateway
- MCP with Hermes: https://hermes-agent.nousresearch.com/docs/guides/use-mcp-with-hermes
- Voice mode: https://hermes-agent.nousresearch.com/docs/guides/use-voice-mode-with-hermes
- Local upstream gap tracker: `docs/upstream-contributions.md`
- Local relay reference: `docs/relay-server.md`
- Local wire protocol reference: `docs/relay-protocol.md`
- Source check: `gateway/platforms/api_server.py` on NousResearch/hermes-agent `main`
- Merged session API: https://github.com/NousResearch/hermes-agent/pull/33134
- Merged skills/toolsets API: https://github.com/NousResearch/hermes-agent/pull/33016

## Supported-First Policy

1. Prefer upstream Hermes extension points before adding relay-specific behavior.
2. Feature-detect optional or fork-only endpoints before using them.
3. Keep custom relay endpoints narrow and document why they exist.
4. When upstream catches up, prefer deleting compatibility code over adding another
   abstraction layer.
5. Any new deviation must document its owner files, reason, guard or fallback, and
   retirement condition in this file and in the user-facing docs if users can touch it.

## Interface Map

| Surface | Upstream-supported method | Local implementation | Status | Sync check |
| --- | --- | --- | --- | --- |
| Plugin metadata and discovery | `plugin.yaml`, plugin directory discovery, `plugins.enabled`, and `register(ctx)` | `plugin/plugin.yaml`, `plugin/__init__.py` | Aligned | Keep plugin-owned version metadata in sync with `python scripts/check-plugin-version-sync.py`. |
| Agent tools | Tool Gateway tools registered through plugin context | `ctx.register_tool(...)` in `plugin/__init__.py`; schemas and handlers in `plugin/tools/*` | Aligned with custom transports | Tool registration should stay in `register(ctx)`; transport details stay behind handlers. |
| Dashboard tab and plugin API | Dashboard plugin manifest plus plugin API routes under the Hermes dashboard plugin mount | `plugin/dashboard/manifest.json`, `plugin/dashboard/plugin_api.py` | Aligned wrapper | Dashboard routes may proxy relay state, but discovery and mounting should stay upstream-native. |
| Chat and model API | OpenAI-compatible API server routes such as `/v1/chat/completions`, `/v1/models`, `/v1/capabilities`, `/health`, and supported streaming routes | Android `HermesApiClient`, relay docs, Web API docs | Mixed | Prefer `/v1/capabilities` when present, then targeted probes for mixed-version fallback. |
| Sessions API | Native API-server session controls merged in NousResearch/hermes-agent PR #33134 (`/api/sessions`, messages, fork, chat, chat stream) | Android `HermesApiClient`; older-build compatibility overlay in `plugin/hermes_relay_bootstrap/*` | Native upstream with fallback | Prefer native `/api/sessions/*`. Bootstrap must skip native routes per method/path and only inject missing compatibility routes for old core builds. |
| Skills and toolsets discovery | Native read-only `/v1/skills` and `/v1/toolsets` merged in NousResearch/hermes-agent PR #33016 | Android `HermesApiClient.getSkills()` prefers `/v1/skills`; desktop/CLI tool surfaces should prefer `/v1/toolsets` where applicable | Native upstream with legacy fallback | Retire `/api/skills` list dependence from clients; keep legacy detail/toggle only where no native equivalent exists. |
| Config, memory, legacy skills, available-models APIs | Not stable current upstream API-server routes as of the 2026-06-16 source check against `55cb4103` | `plugin/hermes_relay_bootstrap/*`, `docs/HERMES-WEBAPI-REFERENCE.md` | Compatibility layer | Keep separate from the sessions/skills retirement path. Do not keep the bootstrap solely for sessions or read-only skill lists once supported baselines include #33134/#33016. |
| Mobile, desktop, and terminal relay transport | No general upstream plugin WSS transport for persistent remote clients in current public docs | `plugin/relay/server.py`, `plugin/relay/channels/*` | Custom | Keep the relay protocol documented and avoid leaking relay-only assumptions into upstream API clients. |
| Pairing QR and relay session minting | No upstream pairing or device-registration method for remote mobile clients in current public docs | `plugin/pair.py`, relay `/pairing/*`, Android QR parser | Custom | QR payloads should keep API credentials (`key`), dashboard URL (`dashboard_url`), and relay credentials (`relay.code`) as separate fields. |
| Basic STT/TTS over HTTP | Proposed upstream API-server audio endpoints in PR #8199 (`/v1/audio/transcriptions`, `/v1/audio/speech`) | Relay `/voice/config`, `/voice/transcribe`, `/voice/synthesize`; Android `RelayVoiceClient`; `plugin/relay/upstream_voice.py` | Custom wrapper pending upstream replacement | Keep `/voice/*` as the relay auth/session compatibility facade. Once core audio endpoints land, prefer proxying to native `/v1/audio/*` for STT/TTS work before falling back to private helper imports. |
| Realtime and streaming voice provider routes | No stable upstream mobile realtime voice provider interface in current public docs | `plugin/relay/voice_output.py`, `plugin/relay/realtime_voice.py`, `plugin.voice_lab`, Android voice mode, Android realtime voice dev screen | Custom relay-mediated voice output path | Keep `/voice/config`, `/voice/transcribe`, and `/voice/synthesize` available as fallback utilities; keep provider secrets server-side; route deterministic assistant speech through `/voice/output/*`, not provider-agent reasoning. |
| TUI and desktop remote terminal | Upstream TUI gateway JSON-RPC and types | `plugin/relay/channels/tui.py`, `desktop/src/gatewayTypes.ts`, `desktop/src/transport/RelayTransport.ts` | Mixed | Keep JSON-RPC payloads in lockstep with upstream TUI; document relay lifecycle changes separately. |
| MCP tools | Configure MCP servers under upstream `mcp.servers` when the external tool already speaks MCP or should be a separate process | No core relay path currently depends on MCP | Aligned guardrail | Do not convert relay-local device bridges into MCP unless the integration is meant to run as an external tool server. |

## Deviation Ledger

| Deviation | Owner files | Why it exists | Guard or fallback | Retirement condition |
| --- | --- | --- | --- | --- |
| API bootstrap route and middleware injection | `plugin/hermes_relay_bootstrap/*`; repo-root `hermes_relay_bootstrap/*` is a legacy import shim | Older native installs need session/config/skills/memory endpoints and slash-command preprocessing before upstream exposes stable equivalents. Current upstream already covers sessions plus read-only skills/toolsets. | Method/path feature detection skips native upstream routes and injects only missing compatibility gaps; upstream-module checks skip middleware when native slash preprocessing exists. | Retire per surface: sessions once the supported Hermes baseline includes #33134; read-only skill lists once clients use `/v1/skills`; config/memory/legacy skill detail/toggle/available-models after stable core replacements or local UX removal; slash middleware after native preprocessing exists. |
| Plugin CLI shim fallback | `plugin/__init__.py`, `plugin/cli.py`, install scripts | Current upstream wires third-party plugin CLI commands into the top-level parser, but older supported Hermes builds and scripts may still call the dashed shims. | Prefer `ctx.register_cli_command` / plugin-provided `hermes pair` on current upstream after Hermes-Relay is installed and enabled; standalone shims stay as compatibility wrappers. | Remove shims only after the supported Hermes baseline includes the upstream CLI discovery fix and release/install docs have switched away from the dashed names. |
| Relay HTTP and WSS server | `plugin/relay/server.py`, `plugin/relay/channels/*` | Mobile, desktop, terminal, media, push, and bridge features need persistent client channels and relay-owned session state. | Keep upstream API calls separate from relay session calls and document the protocol in `docs/relay-protocol.md`. | Replace pieces only when upstream provides equivalent remote-client transport or platform adapters. |
| Pairing schema with `relay.code` | `plugin/pair.py`, Android pairing parser, relay `/pairing/*` | An API bearer key authenticates Hermes API calls but does not create relay sessions or describe WSS endpoints. | QR payloads carry direct API credentials, optional dashboard URL, and relay credentials as separate families. | Remove custom pairing when upstream offers native remote-device registration and relay discovery. |
| Voice `/voice/*` endpoints | `plugin/relay/voice.py`, `plugin/relay/upstream_voice.py`, `plugin/relay/voice_auth.py`, Android voice client | Relay clients need paired-session auth, profile labels, transport guards, and stable `/voice/*` shapes even while core audio APIs evolve. | Use native `/v1/audio/*` once available for STT/TTS execution, with helper imports as fallback; pass selected Hermes profile context; require relay session or valid Hermes API bearer auth. | Keep `/voice/*` as a compatibility facade until mobile clients can safely target core audio directly without losing relay auth/grants/profile behavior. |
| Voice output and realtime endpoints | `plugin/relay/voice_output.py`, `plugin/relay/realtime_voice.py`, `plugin/relay/realtime_agent/*`, `plugin/relay/profile_voice.py`, `plugin/relay/provider_options.py`, `plugin/voice_lab/*`, `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/VoiceViewModel.kt`, `app/src/main/kotlin/com/hermesandroid/relay/audio/VoiceRecorder.kt`, `app/src/main/kotlin/com/hermesandroid/relay/audio/RealtimePcmPlayer.kt`, `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/VoiceSettingsScreen.kt`, `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/RealtimeVoiceTestScreen.kt`, `RelayVoiceClient.kt` | Android conversational voice now prefers `/voice/output/*` streaming TTS for exact assistant speech while keeping the Hermes chat/tool loop server-owned. Realtime providers are retained for lab experiments, and `/voice/realtime-agent/*` adds an experimental Hermes-brokered engine for provider speech with mirrored tool/timeline state. | Voice output is enabled by default but can be disabled with `RELAY_VOICE_OUTPUT_ENABLED=0`; realtime-agent mode can be disabled with `RELAY_REALTIME_VOICE_ENABLED=0`; basic `/voice/synthesize` fallback remains; provider secrets stay server-side; selected profiles can supply experimental `voice_output` / `realtime_voice` defaults with explicit fallback metadata; provider option discovery stays relay-scoped with static fallbacks, cached/paginated xAI custom voices, dynamic ElevenLabs fetches, grouped picker metadata, and pre-save validation where provider APIs support it. | Replace or thin after upstream ships a stable mobile voice output API with server-side auth, deterministic TTS rendering, and tool-loop integration. |
| Relay dashboard proxy endpoints | `plugin/dashboard/plugin_api.py` | Dashboard plugin mounting is upstream-native, but the dashboard needs relay runtime data from the local relay process. | Keep dashboard API loopback-scoped and proxy only documented relay routes. | Keep as long as relay runtime state exists outside Hermes dashboard process. |
| Remote TUI relay transport | `desktop/src/transport/RelayTransport.ts`, `desktop/src/gatewayClient.ts`, `plugin/relay/channels/tui.py` | Desktop remote terminal uses upstream TUI JSON-RPC but needs reconnect, certificate pinning, and relay-hosted gateway lifecycle. | Keep `gatewayTypes.ts` close to upstream and document any transport-only edits in file comments. | Remove custom transport once upstream supports pluggable remote TUI transports with the needed lifecycle. |
| Rich card marker | Android `ChatHandler`, docs, any emitter that writes `CARD:{json}` | Upstream does not yet expose a cross-platform rich-card adapter format. | Treat `MEDIA:` as upstream marker and `CARD:` as local extension. | Replace with upstream card/event protocol when available. |
| Tailscale helper | Relay remote-access routes and docs | Remote relay access needs a guided, safer path while upstream Tailscale support is still pending or version-dependent. | Prefer upstream helper when detected; keep relay helper scoped to remote-access setup. | Remove or thin after upstream Tailscale support is released and available in supported installs. |

## Native Install Support Rules

- A vanilla Hermes install plus the Hermes-Relay plugin should be able to use
  standard chat/model/health API paths without a fork-only requirement.
- Enhanced management features may require the bootstrap compatibility package only
  for surfaces that still lack upstream equivalents. Sessions and read-only skill
  lists should be treated as native-upstream-first.
- The bootstrap must compose with partially-upgraded Hermes core builds. Native
  routes win per method/path; missing compatibility routes may still be injected.
- Relay-specific features must authenticate through relay sessions or explicitly
  documented Hermes API bearer checks; do not treat API bearer auth and relay pairing
  auth as the same thing.
- Basic STT/TTS should converge on core `/v1/audio/*` endpoints when available.
  Relay `/voice/*` remains the mobile/pairing facade, not a competing core API.
- Documentation should label custom relay behavior as relay behavior, not upstream
  Hermes behavior.
- If a feature depends on a private upstream symbol, note the import path and the
  fallback behavior in this file before release.

## Sync Checklist

Run this checklist before releases that touch Hermes integration surfaces, and after
upgrading the supported Hermes baseline.

1. Re-read the upstream references at the top of this file, especially Plugins, API
   server, Tool Gateway, MCP, and Voice mode.
2. Verify plugin-owned version metadata:

   ```powershell
   python scripts/check-plugin-version-sync.py
   ```

   When Android, plugin, or CLI release surfaces are touched together,
   also run:

   ```powershell
   python scripts/check-version-tracks.py
   ```

3. Inspect local integration points:
   - `plugin/plugin.yaml`
   - `plugin/__init__.py`
   - `plugin/dashboard/manifest.json`
   - `plugin/dashboard/plugin_api.py`
   - `plugin/hermes_relay_bootstrap/*`
   - `plugin/relay/server.py`
   - `plugin/relay/voice.py`
   - `plugin/relay/realtime_voice.py`
   - `plugin/relay/upstream_voice.py`
   - `plugin/pair.py`
   - Android `HermesApiClient` and pairing/voice clients
   - Desktop TUI transport files under `desktop/src`
4. Search for custom or upstream-pending hooks:

   ```powershell
   rg "hermes_relay_bootstrap|register_cli_command|api_server_slash|CARD:|_load_tts_config|_load_stt_config|PR #|TODO\\(upstream" plugin app desktop hermes_relay_bootstrap docs
   ```

5. Probe a native install with the plugin installed:
   - `GET /health`
   - `GET /v1/models`
   - `POST /v1/chat/completions` or the supported streaming route for the target version
   - `GET /v1/capabilities` and confirm `features.session_chat_streaming`, `features.skills_api`, `endpoints.session_chat_stream`, `endpoints.skills`, and `endpoints.toolsets`
   - `GET /api/sessions?limit=1` and `GET /api/sessions/{id}/messages` using the upstream `{"object":"list","data":[...]}` envelope
   - `GET /v1/skills` and `GET /v1/toolsets` using the upstream `{"object":"list","data":[...]}` envelope
   - Relay health and info endpoints from `docs/relay-server.md`
   - Dashboard plugin overview under the Hermes plugin API mount
   - Native `/api/sessions/*` route set when testing a core build with PR #33134 or equivalent
   - `POST /v1/audio/transcriptions` and `POST /v1/audio/speech` when testing a core build with PR #8199 or equivalent
   - Voice config, transcription, synthesis, and realtime routes only with relay session auth or a valid Hermes API bearer
6. Update this file when upstream adds a supported replacement for a custom layer.
7. Update `docs/upstream-contributions.md` when a deviation becomes an upstream
   proposal, lands upstream, or can be removed locally.
8. Update user-facing docs when the install, auth, pairing, or endpoint story changes.

## Documentation Rules For New Work

- If adding a custom endpoint, add it to `docs/relay-server.md` and to the
  Deviation Ledger above.
- If adding or changing a wire payload, update `docs/relay-protocol.md`.
- If adding a user-visible command or setup requirement, update user-facing docs and
  install docs.
- If the work is only an upstream alignment cleanup, state which custom code was
  removed and which upstream method replaced it.
- Keep `docs/upstream-contributions.md` focused on proposals to upstream; keep this
  file focused on local integration policy and drift control.
