# Upstream Hermes Integration Sync

Last reviewed: 2026-05-06

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
| Plugin metadata and discovery | `plugin.yaml`, plugin directory discovery, `plugins.enabled`, and `register(ctx)` | `plugin/plugin.yaml`, `plugin/__init__.py` | Aligned | Keep `plugin/plugin.yaml`, `plugin/relay/__init__.py`, and `plugin/dashboard/manifest.json` versions in sync. |
| Agent tools | Tool Gateway tools registered through plugin context | `ctx.register_tool(...)` in `plugin/__init__.py`; schemas and handlers in `plugin/tools/*` | Aligned with custom transports | Tool registration should stay in `register(ctx)`; transport details stay behind handlers. |
| Dashboard tab and plugin API | Dashboard plugin manifest plus plugin API routes under the Hermes dashboard plugin mount | `plugin/dashboard/manifest.json`, `plugin/dashboard/plugin_api.py` | Aligned wrapper | Dashboard routes may proxy relay state, but discovery and mounting should stay upstream-native. |
| Chat and model API | OpenAI-compatible API server routes such as `/v1/chat/completions`, `/v1/models`, `/health`, and supported streaming routes | Android `HermesApiClient`, relay docs, Web API docs | Mixed | Prefer standard API routes first; use `/api/sessions` only when capability probes find it. |
| Sessions, config, skills, and memory APIs | Not documented as stable upstream API-server routes in the current public docs | `hermes_relay_bootstrap/*`, `docs/HERMES-WEBAPI-REFERENCE.md` | Compatibility layer | Bootstrap must feature-detect and no-op when upstream routes exist. Track upstream catch-up in `docs/upstream-contributions.md`. |
| Mobile, desktop, and terminal relay transport | No general upstream plugin WSS transport for persistent remote clients in current public docs | `plugin/relay/server.py`, `plugin/relay/channels/*` | Custom | Keep the relay protocol documented and avoid leaking relay-only assumptions into upstream API clients. |
| Pairing QR and relay session minting | No upstream pairing or device-registration method for remote mobile clients in current public docs | `plugin/pair.py`, relay `/pairing/*`, Android QR parser | Custom | QR payloads should keep API credentials (`key`) separate from relay credentials (`relay.code`). |
| Voice over HTTP | Upstream voice mode is documented as CLI/TUI voice tooling, not a public HTTP voice API | `plugin/relay/voice.py`, `plugin/relay/voice_auth.py`, Android voice client | Custom wrapper over upstream tools | Keep STT/TTS provider work delegated to upstream tools where possible; document any private helper imports. |
| TUI and desktop remote terminal | Upstream TUI gateway JSON-RPC and types | `plugin/relay/channels/tui.py`, `desktop/src/gatewayTypes.ts`, `desktop/src/transport/RelayTransport.ts` | Mixed | Keep JSON-RPC payloads in lockstep with upstream TUI; document relay lifecycle changes separately. |
| MCP tools | Configure MCP servers under upstream `mcp.servers` when the external tool already speaks MCP or should be a separate process | No core relay path currently depends on MCP | Aligned guardrail | Do not convert relay-local device bridges into MCP unless the integration is meant to run as an external tool server. |

## Deviation Ledger

| Deviation | Owner files | Why it exists | Guard or fallback | Retirement condition |
| --- | --- | --- | --- | --- |
| API bootstrap route and middleware injection | `hermes_relay_bootstrap/*` | Native installs need session/config/skills/memory endpoints and slash-command preprocessing before upstream exposes stable equivalents. | Route feature detection and upstream-module checks should make the bootstrap no-op when native routes exist. | Remove after upstream ships equivalent API routes and command preprocessing. |
| Plugin CLI shim fallback | `plugin/__init__.py`, `plugin/cli.py`, install scripts | Some Hermes versions do not wire third-party plugin CLI commands into the top-level parser. | `ctx.register_cli_command` is attempted first; standalone shims fill the gap. | Remove shims once upstream plugin CLI discovery is stable for native installs. |
| Relay HTTP and WSS server | `plugin/relay/server.py`, `plugin/relay/channels/*` | Mobile, desktop, terminal, media, push, and bridge features need persistent client channels and relay-owned session state. | Keep upstream API calls separate from relay session calls and document the protocol in `docs/relay-protocol.md`. | Replace pieces only when upstream provides equivalent remote-client transport or platform adapters. |
| Pairing schema with `relay.code` | `plugin/pair.py`, Android pairing parser, relay `/pairing/*` | An API bearer key authenticates Hermes API calls but does not create relay sessions or describe WSS endpoints. | QR payloads carry direct API credentials and relay credentials as separate families. | Remove custom pairing when upstream offers native remote-device registration and relay discovery. |
| Voice `/voice/*` endpoints | `plugin/relay/voice.py`, `plugin/relay/voice_auth.py`, Android voice client | Upstream voice support is provider tooling and CLI/TUI workflow, not a stable HTTP API for mobile voice mode. | Use upstream STT/TTS tools behind the relay endpoint and require relay session or valid Hermes API bearer auth. | Replace with upstream HTTP voice API or documented provider interface if one lands. |
| Relay dashboard proxy endpoints | `plugin/dashboard/plugin_api.py` | Dashboard plugin mounting is upstream-native, but the dashboard needs relay runtime data from the local relay process. | Keep dashboard API loopback-scoped and proxy only documented relay routes. | Keep as long as relay runtime state exists outside Hermes dashboard process. |
| Remote TUI relay transport | `desktop/src/transport/RelayTransport.ts`, `desktop/src/gatewayClient.ts`, `plugin/relay/channels/tui.py` | Desktop remote terminal uses upstream TUI JSON-RPC but needs reconnect, certificate pinning, and relay-hosted gateway lifecycle. | Keep `gatewayTypes.ts` close to upstream and document any transport-only edits in file comments. | Remove custom transport once upstream supports pluggable remote TUI transports with the needed lifecycle. |
| Rich card marker | Android `ChatHandler`, docs, any emitter that writes `CARD:{json}` | Upstream does not yet expose a cross-platform rich-card adapter format. | Treat `MEDIA:` as upstream marker and `CARD:` as local extension. | Replace with upstream card/event protocol when available. |
| Tailscale helper | Relay remote-access routes and docs | Remote relay access needs a guided, safer path while upstream Tailscale support is still pending or version-dependent. | Prefer upstream helper when detected; keep relay helper scoped to remote-access setup. | Remove or thin after upstream Tailscale support is released and available in supported installs. |

## Native Install Support Rules

- A vanilla Hermes install plus the Hermes-Relay plugin should be able to use
  standard chat/model/health API paths without a fork-only requirement.
- Enhanced management features may require the bootstrap compatibility package until
  upstream exposes equivalent routes. Those features must be probed before use.
- Relay-specific features must authenticate through relay sessions or explicitly
  documented Hermes API bearer checks; do not treat API bearer auth and relay pairing
  auth as the same thing.
- Documentation should label custom relay behavior as relay behavior, not upstream
  Hermes behavior.
- If a feature depends on a private upstream symbol, note the import path and the
  fallback behavior in this file before release.

## Sync Checklist

Run this checklist before releases that touch Hermes integration surfaces, and after
upgrading the supported Hermes baseline.

1. Re-read the upstream references at the top of this file, especially Plugins, API
   server, Tool Gateway, MCP, and Voice mode.
2. Inspect local integration points:
   - `plugin/plugin.yaml`
   - `plugin/__init__.py`
   - `plugin/dashboard/manifest.json`
   - `plugin/dashboard/plugin_api.py`
   - `hermes_relay_bootstrap/*`
   - `plugin/relay/server.py`
   - `plugin/relay/voice.py`
   - `plugin/pair.py`
   - Android `HermesApiClient` and pairing/voice clients
   - Desktop TUI transport files under `desktop/src`
3. Search for custom or upstream-pending hooks:

   ```powershell
   rg "hermes_relay_bootstrap|register_cli_command|api_server_slash|CARD:|_load_tts_config|_load_stt_config|PR #|TODO\\(upstream" plugin app desktop hermes_relay_bootstrap docs
   ```

4. Probe a native install with the plugin installed:
   - `GET /health`
   - `GET /v1/models`
   - `POST /v1/chat/completions` or the supported streaming route for the target version
   - `GET /api/sessions?limit=1` only as an enhanced-management capability probe
   - Relay health and info endpoints from `docs/relay-server.md`
   - Dashboard plugin overview under the Hermes plugin API mount
   - Voice config and transcription routes only with relay session auth or a valid Hermes API bearer
5. Update this file when upstream adds a supported replacement for a custom layer.
6. Update `docs/upstream-contributions.md` when a deviation becomes an upstream
   proposal, lands upstream, or can be removed locally.
7. Update user-facing docs when the install, auth, pairing, or endpoint story changes.

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
