# Hermes-Relay Surface Matrix

Updated: 2026-08-31

This matrix records the v1.0.0 route ownership contract. It is meant to keep
future app, plugin, and agent work honest about what is vanilla upstream
Hermes, what belongs to the Relay plugin, and what is only legacy compatibility.

Verified upstream source snapshot:

- Repository: `NousResearch/hermes-agent`
- Commit: `fcbd1076a93841fa88855acce810e342a5b78101`
- Primary files checked: `gateway/platforms/api_server.py`,
  `hermes_cli/web_routers/sessions.py`, `apps/desktop/src/store/session-pin-sync.ts`,
  `apps/desktop/src/api/sessions.ts`,
  `apps/desktop/src/app/session/hooks/use-session-list-actions.ts`,
  `apps/desktop/src/store/layout.ts`,
  `hermes_cli/web_server.py`, `hermes_cli/dashboard_auth/routes.py`,
  `tui_gateway/server.py`, `tui_gateway/methods_session.py`,
  `tui_gateway/ws.py`,
  `apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/session-info.ts`,
  `apps/desktop/src/app/session/hooks/use-route-resume.ts`,
  `ui-tui/src/app/createGatewayEventHandler.ts`,
  `ui-tui/src/app/useSessionLifecycle.ts`,
  `apps/desktop/src/store/pet.ts`, `agent/pet/`, `hermes_cli/plugins.py`,
  `hermes_cli/plugins_cmd.py`
- Additive Manage contracts were rechecked at upstream MCP hosted-OAuth commits
  through `4dc2b7be0` and custom-endpoint commit `3d9789357`.
- Session activity contracts were rechecked against upstream `main` at
  `d736f5d53f1d33fabad5a17cb070eb138b618fb8` in `tui_gateway/server.py`,
  `tui_gateway/methods_session.py`, and `hermes_cli/web_routers/sessions.py`.
- Desktop media delivery was rechecked at upstream commit
  `b20cc5f787ea816ea8645603b7b2ac8234dcb8b4` in
  `apps/desktop/src/lib/media.ts`,
  `apps/desktop/src/components/assistant-ui/markdown-text.tsx`, and
  `hermes_cli/web_server.py`.

## Ownership

| Surface | Owner | Requires Relay | Android usage | Notes |
|---------|-------|----------------|---------------|-------|
| `/v1/capabilities` | Upstream API server | No | Optional fallback capability probe | Source of truth for API-server features; current upstream advertises no audio API. |
| `/v1/chat/completions` | Upstream API server | No | Chat fallback | OpenAI-compatible streaming. Tool events may degrade to inline annotations. |
| `/v1/runs`, `/v1/runs/{id}/events` | Upstream API server | No | Chat fallback | Structured run events and stop/approval support. |
| Dashboard `/api/health` | Upstream dashboard | No | Route/process readiness | Lightweight canonical readiness probe used by official Desktop. Android falls back to `/api/status` only for confirmed legacy hosts without this route; transient failures never trigger the heavyweight fallback. |
| `/api/sessions/*` | Upstream Dashboard/Gateway and API server | No | Primary profile-scoped session directory/history or optional SSE fallback | Native upstream session list/create/read/update/delete/messages/fork/chat/chat-stream. The standard Android drawer and stored-history reader use authenticated Dashboard REST independently of `/api/ws` readiness; the Gateway socket owns live chat and activity, not whether persisted rows may be read. Dashboard lists expose profile-stamped `pinned`/`archived`, accept `archived=exclude\|only\|include`, and PATCH either durable flag in the owning profile DB. The API-server resource also exposes and patches both fields, but its current list omits archived rows and has no archive filter; Android therefore offers restart-safe archive/restore only on the Dashboard path while API-only pinning remains valid. Newer Dashboard hosts also expose single-session JSON export and guarded bulk cleanup; Android must dry-run prune first. The bootstrap no longer injects session CRUD/messages/fork routes; only `/api/sessions/search` remains a compatibility route. |
| `/v1/skills`, `/v1/toolsets` | Upstream API server | No | Discovery | Authenticated read-only API-server skill/toolset inventory; Android Diagnostics summarizes enabled toolsets and Relay tool visibility. |
| Dashboard `/api/status`, `/api/auth/me` | Upstream dashboard | No | Manage auth and post-selection diagnostics | Dashboard cookie/session path; separate from API bearer. Optional status diagnostics include Nous bootstrap validity, resource pressure, and profile/gateway topology; these do not gate transport selection. |
| Dashboard `/api/auth/ws-ticket`, `/api/ws` | Upstream dashboard/tui_gateway | No | Preferred chat transport | Vanilla Hermes gateway chat path with live reasoning/thinking events. `message.complete` is the ordinary terminal event; `session.info {running:false}` is the authoritative settle backstop when a replacement socket missed that terminal frame. A reconnect reactivates the exact live runtime with `session.activate`; durable `session.resume` remains the cold-open path and an explicit rejection never creates a replacement context. |
| Gateway `session.active_list` | Upstream tui_gateway | No | Authoritative process-wide live activity | Returns attachable runtimes across the Gateway process, with live `id`, durable `session_key`, and `starting`, `working`, `waiting`, or `idle`. The only optional selector is `current_session_id`; rows normally carry no profile metadata. Android attributes a row only from exact foreground/detached ownership already held by that client, or from explicit profile metadata if a future upstream sends it. A bounded REST directory never proves global uniqueness. Unresolved rows remain unattributed, and absence settles a scope only after a complete, unambiguously resolved successful snapshot. Method-not-found or refresh failure is Unavailable, not Idle. Pending input outranks running work. |
| Dashboard `model.options` / `/api/model/*` | Upstream dashboard/tui_gateway | No | Provider/model inventory and selection | Source of truth for coherent provider/model identities. A reasoning boolean or exact effort list is consumed when present; clients do not infer provider identity from a model string alone. |
| Gateway `pet.info`, `pet.gallery`, `pet.select`, `pet.disable` | Upstream tui_gateway | No | Profile-scoped animated companion | `pet.info` supplies bounded PNG/WebP sheet bytes, revision, geometry, real frame counts, loop timing, scale, and row taxonomy. Android passes `knownRevision` to avoid duplicate sheet transfer, renders the active pet through its native activity-aware companion, and keeps phone-local pet packs separate. All four RPCs carry the effective profile. |
| Dashboard `/api/audio/transcribe`, `/api/audio/speak-stream`, `/api/audio/speak` | Upstream dashboard | No | Vanilla Hermes voice | Manage sign-in unlocks Vanilla Hermes voice. Assistant text streams into upstream speech when available; older hosts fall back to whole-request speech before audio starts. API server has no `/v1/audio/*` route today. |
| Dashboard `/api/files/download`, `/api/files/stream`, `/api/fs/read-data-url` | Upstream dashboard | No | Primary inbound files and official Desktop media/preview support | Android currently downloads host-local `MEDIA:` paths and file links through authenticated `/api/files/download`, then plays or previews the bounded local cache. Official Desktop additionally uses `/api/files/stream` for Range playback and `/api/fs/read-data-url` for bounded previews; Android does not claim those two routes yet. Direct HTTP/data URLs remain direct. |
| Dashboard `/api/config`, `/api/profiles/*`, `/api/env`, `/api/model/*`, `/api/mcp/*`, `/api/providers/custom-endpoints*` | Upstream dashboard | No | Manage | Do not proxy through Relay. MCP list/actions/OAuth carry Android's effective profile explicitly; Android detects hosted-OAuth support with a read-only missing-flow status GET and caches that capability per dashboard/profile. Hosted OAuth itself stays server-owned, opens only the returned HTTPS URL, and persists only the opaque flow id/server/profile plus a normalized non-secret dashboard/connection identity; polling is held whenever the active connection does not own that flow. Custom-endpoint routes are process-scoped in the current public contract, so Android does not claim or append profile scoping; credentials are write-only and blank edits preserve an existing key. |
| `/pairing/*`, `/sessions`, `/voice/*`, `/desktop/*`, `/media/*`, `/notifications/*` on Relay | Hermes-Relay plugin/server | Yes | Relay pairing, terminal, bridge, relay voice, desktop tools, and additive media compatibility | Owned by `plugin/relay/server.py`; Android must gate these routes behind Relay readiness/session grants. Relay media tokens, sensitivity metadata, and older-host compatibility enhance the standard Dashboard media path; they do not make ordinary inbound files Relay-owned. |
| `POST /relay/model-capabilities` | Hermes-Relay plugin/server | Optional | Refine reasoning-effort choices for exact upstream provider/model pairs | Never supplies model inventory or gates chat. Missing, old, unpaired, malformed, or unreachable Relay falls back to standard advisory choices. Remote calls require a paired bearer with an active `chat` grant. |
| Dashboard `/api/plugins/hermes-relay/*` | Hermes-Relay dashboard plugin | Yes for live data | Relay dashboard tab and same-origin Relay ingress | FastAPI plugin backend proxies an explicit allowlist to the independently supervised loopback Relay. HTTP requires Dashboard auth plus `X-Hermes-Relay-Session`; WebSockets require a fresh Dashboard ticket before Relay's own pairing/session frame. Loopback administration routes are never exposed. |
| `hermes relay doctor` | Hermes-Relay plugin CLI | No for diagnostics | Operator/agent diagnostics | Reports vanilla upstream Hermes route reachability (including `/v1/toolsets`), dashboard Nous/topology state, sanitized gateway event-loop heartbeat state, plugin layout, Relay loopback state, and legacy bootstrap presence. |
| `hermes_relay_bootstrap` routes | Legacy compatibility monkeypatch | No, but non-upstream | Fallback only | Installed via `.pth` by legacy installer. Injects only compatibility-only gaps: session search, memory, legacy skill detail/toggle, config, available-models, slash middleware. Sessions CRUD and skill/toolset lists are native upstream and retired from the bootstrap. Retained session-database work is offloaded (`AsyncSessionDB` when available, `asyncio.to_thread` fallback), and memory mutations reset newer upstream's request-local consolidation-failure budget. |

## Client capability gate (build flavor)

Route ownership above is a *server-side* contract. The Android client adds a
second, orthogonal axis: the **build flavor**, a capability ceiling compiled
into the APK regardless of which routes the server exposes.

- `googlePlay` ("Bridge Core") ships the full Vanilla Hermes path plus the Relay
  client (terminal, relay voice, notification companion, media enhancements, session
  grants), but **no** AccessibilityService Device Control.
- `sideload` additionally compiles in phone Device Control — screen reading,
  taps, typing, screenshots, overlays, unattended control — and the Tier-C
  `android_*` tools (call, SMS, contacts, location). On `googlePlay` those
  routes fail closed with a structured `403` — `error_code:
  device_control_sideload_only` for Device Control commands, `sideload_only`
  for the Tier-C tools.

Bridge is therefore two-layered: relay pairing unlocks the bridge *channel*;
the `sideload` flavor unlocks the Device Control *hands* within it. A paired
relay on a `googlePlay` build still cannot tap or type. See `BuildFlavor`
(`data/FeatureFlags.kt`) and the full capability matrix in
`docs/path-architecture.html`.

## Voice Surfaces (Vanilla Hermes vs. relay)

Voice splits the same way as everything else: Vanilla Hermes rides vanilla upstream,
relay is the additive power path.

### Route ownership

Be explicit, because the names look similar:

| Route | Owner | Requires Relay | Notes |
|-------|-------|----------------|-------|
| Dashboard `POST /api/audio/transcribe` | **Upstream** (dashboard web_server) | No | Vanilla Hermes STT. Cookie/session auth. |
| Dashboard `POST /api/audio/speak` | **Upstream** (dashboard web_server) | No | Vanilla Hermes TTS. Accepts ONLY `{text}` — one-shot, config-driven. |
| Dashboard `GET /api/audio/elevenlabs/voices` | **Upstream** (dashboard web_server) | No | Voice-list helper. |
| `POST /voice/transcribe`, `POST /voice/synthesize` | **Relay** (`plugin/relay/voice.py`) | Yes | Basic STT/TTS. `/voice/synthesize` wraps upstream `text_to_speech_tool` + adds per-request enhanced overrides. |
| `GET /voice/config` | **Relay** | Yes | Reports `tts:`/`stt:` + a provider-aware `tts.enhanced` capability block. |
| `GET/PATCH /voice/output/config`, `POST /voice/output/session`, `GET /voice/output/{id}` (WS) | **Relay** (`plugin/relay/voice_output.py` + `plugin/voice_lab/`) | Yes | Streaming TTS renderer. **No upstream equivalent** — the API server advertises `audio_api: false` and there is no upstream streaming/WS audio route (`/v1/audio/*` PR is unmerged). |
| `GET/PATCH /voice/realtime/*`, `GET/PATCH /voice/realtime-agent/*` | **Relay** | Yes | Provider playground + the experimental Realtime Agent engine. Not upstream. |

**Bottom line: every `/voice/*` route is relay-owned. The only upstream audio surface is the dashboard `/api/audio/*` set.**

### Enhanced voice (provider-native voice/tone control)

| Capability | Vanilla Hermes path (vanilla upstream) | Relay path (plugin) |
|------------|----------------------------------|---------------------|
| Where it applies | Dashboard `/api/audio/speak`. | Relay `/voice/synthesize` (basic) **and** `/voice/output/*` (streaming renderer, the default when `voice_output_enabled`). |
| Control model | **Config-only.** Read from `~/.hermes/config.yaml` `tts.<provider>.*`. `/api/audio/speak` takes only `{text}`. The phone changes enhanced behavior only via Manage `PUT /api/config` (global), never per utterance. | **Synthesize:** per-request override (`voice`/`model`/`audio_tags`/`persona_prompt`/`language`), advertised via `/voice/config` `tts.enhanced`. **Streaming:** per-profile `voice_output:` config (incl. `auto_speech_tags` for xAI), set via `PATCH /voice/output/config`. Neither mutates the basic `tts:` config. |
| Feature coverage | Whatever the server config enables (any provider). | **Synthesize:** Gemini (`voice`/`model`/`audio_tags` tone-tag rewrite/`persona_prompt`) + xAI (`voice`→`voice_id`/`audio_tags`→`auto_speech_tags`/`language`). **Streaming:** xAI `auto_speech_tags` (the `voice_lab` renderer has xai/openai/elevenlabs but **no Gemini provider**). Others config-only. |
| How it's implemented | Upstream `text_to_speech_tool` reads config; relay/app do nothing special. | **Synthesize:** relay merges overrides into a config copy and calls the upstream generator (`_generate_gemini_tts`/`_generate_xai_tts`) directly. **Streaming:** the relay applies `upstream_voice.apply_xai_speech_tags()` to the chunk text before the `voice_lab` `xai_tts` renderer. No fork patch; all upstream imports isolated in `plugin/relay/upstream_voice.py`. |

Upstream parity notes (verify against `tools/tts_tool.py`): Gemini `audio_tags` needs a
`gemini-3.1*tts` model and runs a hidden auxiliary-LLM rewrite that **fails soft**
(unavailable aux client → untagged text). OpenAI TTS exposes only `voice` + `speed`
upstream — the `gpt-4o-mini-tts` `instructions` tone param is **not** wired — so it is
intentionally not offered as a relay enhanced provider. The Vanilla Hermes path stays vanilla
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

## Connection Contract

One saved connection represents one Hermes installation. Endpoint URLs are
capabilities, not identity:

| Surface | Product role | Required for the standard path |
|---------|--------------|--------------------------------|
| Dashboard/Gateway | Primary chat, auth, sessions, Manage, Vanilla Hermes voice, and inbound files | Yes |
| API server | Automatic chat fallback and advanced headless compatibility | No |
| Relay | Pairing, terminal, bridge/device control, media enhancements/legacy fallback, and enhanced voice; normally reached through the Dashboard plugin ingress | No |

Existing API-only records and headless deployments remain supported compatibility
configurations. They do not redefine normal onboarding or make an API key a
prerequisite for a Dashboard/Gateway connection.

## App Flow Rule

The app should present Vanilla Hermes as the default path:

1. Connect to and authenticate with the Dashboard/Gateway.
2. Use gateway chat when `/api/ws` is ready.
3. Discover or accept an API server as an optional automatic fallback; otherwise
   keep the connection healthy with API fallback marked unavailable.
4. When needed, fall back to API-server
   SSE.
5. Use Vanilla Hermes dashboard voice when audio routes are present.
6. Resolve inbound files through direct sources or authenticated upstream
   Dashboard file routes first. Use Relay only for Relay tokens, additive
   metadata, or compatibility with older hosts.
7. Offer Relay pairing only for Relay-owned tools and enhancements. Prefer the
   Dashboard-origin plugin ingress advertised by pairing; retain a direct Relay
   listener only as an advanced/headless/Desktop compatibility route.

When Auto voice selects Relay because a paired Relay is healthy, the UI should
make that active route visible and continue to fall back to Vanilla Hermes voice when
Relay fails but dashboard audio is ready.

Setup payloads should carry an explicit Dashboard/Gateway URL for new
connections. Legacy API-first QRs remain importable; when their optional
`dashboard_url` is absent Android may derive the conventional same-host `:9119`
URL for compatibility.

New pairing payloads may omit API host/key fields entirely. When the Dashboard
plugin ingress is available, the priority candidate carries the Dashboard URL
and a Relay base at `/api/plugins/hermes-relay/transport`; direct Relay and API
routes, when configured, remain lower-priority compatibility candidates.

### Session directory read policy

The official Desktop client requests a bounded recent page and fences
publication by the current profile, request id, and Gateway activation epoch.
Android follows the same small-window and stale-publication principles while
keeping route ownership explicit:

- The authenticated Dashboard REST route owns profile-scoped session browsing
  and stored history. Gateway WebSocket readiness must not gate those reads.
- Profile selection does not put `model.options` or other agent-dependent
  Gateway calls ahead of the directory read. Model inventory is refreshed by
  the model surface, while `session.info` confirms an opened session's model.
- Gateway prewarm/resume sends `defer_history` plus `omit_messages`, because the
  visible transcript is already loaded through Dashboard REST. This keeps the
  resume acknowledgement bounded and avoids a duplicate synchronous history
  payload while Gateway prepares model state in the background.
- Initial Android recents are limited to 50 visible-source rows. The persisted
  hidden-source set is sent as `exclude_sources`, and near-end scrolling appends
  subsequent 50-row `offset` pages rather than making drawer open scan a large
  profile store.
- Cached rows for the exact connection/profile remain visible during a quiet
  refresh. An uncached owner may show loading, but an error must not be rendered
  as an authoritative empty list.
- A timeout ends that read and exposes a retryable **Unavailable** state; it does
  not automatically start another long request. Short route-readiness failures
  may use a bounded retry before becoming Unavailable.
- Connection/profile ownership plus request generation are rechecked before
  publication, so a late response cannot populate a newer profile selection.

## API Fallback Compatibility Details

- Dashboard/API session-list `is_active` is a persistence-recency hint: an
  unended row whose `last_active` is less than five minutes old. It is not a
  running-turn signal and must never produce Working, Waiting, or Starting.
- Gateway `process.list` describes separately running background processes. A
  process may outlive its parent model turn, so clients present that as
  Background work without keeping the conversation in Working.
- Upstream can make multi-profile clients safer and simpler by adding profile
  metadata or a profile filter to `session.active_list`, or by publishing an
  aggregate activity route with explicit profile ownership. Until then,
  clients must fail closed on duplicate or unresolved durable keys.

- Android accepts the API server's final-response image data URLs for PNG,
  JPEG, GIF, WebP, and BMP. Decoding is strict: MIME and file signatures must
  agree, encoded and decoded bytes are capped at the upstream 5 MiB limit, and
  oversized pixel dimensions are rejected before bitmap allocation. SVG and
  unknown image types remain unsupported. Spoiler/alt sensitivity flags use the
  same reveal gate as remote and Relay-fetched images.
- `/v1/models` rows retain `id`, `root`, and `parent`. The picker sends the
  alias `id` unchanged on sessions, completions, and runs requests, while a
  differing `root` is shown as secondary route information.
- A `503` OpenAI error with code `gateway_draining` retains its structured code
  and bounded `Retry-After` hint through every SSE listener. Android identifies
  this as an intentional Hermes restart/drain rather than a provider outage.
  Before any event arrives, it retries once through a composite EventSource
  owner; Stop/session-switch cancels the initial request, pending delay, and
  replacement together. A second drain or any post-event failure remains an
  explicit retry so the client cannot duplicate an admitted turn.
- Android currently preserves its explicit `source: webui` session field for
  wire and history compatibility only. Current official Desktop does not treat
  `webui` as a rich-chat prompt hint; upstream removed that unused path at the
  verified `b20cc5f` snapshot. Hermes has no stable `android` or mobile platform
  hint, so Android must not claim Desktop parity or invent one. The upstream
  platform-hint follow-up is tracked in `TODO.md`.
