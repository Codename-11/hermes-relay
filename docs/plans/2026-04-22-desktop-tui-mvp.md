# Desktop TUI MVP — Implementation Plan

**Date:** 2026-04-22
**Branch:** `feature/desktop-tui-mvp` (hermes-relay), `feat/tui-transport-pluggable` (hermes-agent fork, off `axiom`)
**Owner:** Victor + agent team
**Target release:** hermes-relay v0.8.0-alpha

## Goal

`hermes --remote wss://docker-server.ts.net:8767` on Windows (or any machine with Node ≥ 20) opens the Hermes TUI with full feature parity — approvals, tool cards, image paste, voice, session resume — against a remote `hermes-agent` brain. No SSH, no X11 forwarding, no `mosh`. WSS transport, bearer auth, TOFU cert pinning, reconnect-safe.

This is the **v0.1 / Option C (hybrid)** shipping target from the high-level design. All tools still execute **server-side**. Per-tool client-side routing (Option B) is explicitly v2.

## Architectural Insight Driving This Shape

The `ui-tui/` audit (2026-04-22) showed:

1. The Node TUI's transport is isolated in **one file**: `ui-tui/src/gatewayClient.ts` (293 lines).
2. The wire format between Node and Python is already **line-delimited JSON-RPC 2.0 over stdio** — a clean, standard protocol.
3. Python's `tui_gateway` (`~/.hermes/hermes-agent/tui_gateway/server.py`, ~3,176 lines) is **wire-protocol agnostic** — it just reads/writes JSON-RPC on its stdin/stdout.
4. The TUI is **already stateless**: all session state, config, SQLite, image decoding lives on the Python side. Node handles only clipboard native tools, rendering, and input.

So "remote TUI" collapses to one idea: **pipe the Node TUI ↔ `tui_gateway` over WSS instead of stdio.** No agent-loop rewiring, no dispatch rewiring. The relay becomes a transparent envelope-pump between Node WSS and a spawned `tui_gateway` subprocess.

## Scope

**In scope (MVP):**
- New `tui` channel on the relay that spawns + pumps a `tui_gateway` subprocess.
- Transport abstraction in `ui-tui/` with two impls: `LocalSubprocessTransport` (current behavior) and `RelayTransport` (WSS).
- `hermes` CLI flag: `--remote <url>` / `HERMES_RELAY_URL` env.
- Desktop session-token storage (`~/.hermes/remote-sessions.json`, mode 0600).
- Desktop TOFU cert-pin storage.
- End-to-end smoke: connect → prompt → streamed response → image paste → approval modal → voice (if relay voice channel accessible from desktop — nice-to-have).

**Out of scope (v2+):**
- Per-tool client-side execution routing (`model_tools.py::handle_function_call()` insertion).
- Single-binary packaging (`pkg`/`esbuild`). MVP ships as `npm install -g` + Node 20 runtime.
- Android client changes. The new `tui` channel is additive; Android doesn't use it.
- iOS / web clients.

## Phases

### Phase 0 — Scaffolding *(this session)*
- Commit `docs/relay-protocol.md` (spec extracted from audit).
- Commit this plan.
- Create feature branches on both repos.

### Phase 1 — Python: `tui` channel on relay
**Repo:** hermes-relay, branch `feature/desktop-tui-mvp`.
**Files:**
- **New:** `plugin/relay/channels/tui.py` — `TuiHandler` class, spawn `tui_gateway` subprocess, bidirectional stdio ↔ envelope pump, per-connection subprocess lifecycle.
- **Edit:** `plugin/relay/server.py` — register handler on `RelayServer` (~L97), add `tui` branch in `ChannelMultiplexer` router (~L2939).
- **Edit:** `plugin/relay/auth.py` — add `tui` to `_default_grants` with 30-day cap.
- **New:** `plugin/tests/test_tui_channel.py` — unittest-style; mock subprocess, assert envelope pump correctness, test timeout/detach/error paths.

**Subprocess invocation (reuse what CLI does):**
```python
proc = await asyncio.create_subprocess_exec(
    sys.executable, "-m", "tui_gateway.entry",
    stdin=asyncio.subprocess.PIPE,
    stdout=asyncio.subprocess.PIPE,
    stderr=asyncio.subprocess.PIPE,
    env={**os.environ, "HERMES_PROFILE": payload.get("profile", "default")},
)
```
Confirm exact invocation by reading `hermes_cli/main.py:1034` area (agent will do this).

**Success criteria:** `curl`-able smoke — connect WSS, send `tui.attach`, send `tui.rpc.request {method: "ping"}`, receive `tui.rpc.response`. Subprocess cleanly killed on client disconnect.

**Agent:** `general-purpose`, worktree-isolated against `feature/desktop-tui-mvp`.

### Phase 2 — Node: transport refactor
**Repo:** hermes-agent (Codename-11 fork), branch `feat/tui-transport-pluggable`.
**Files:**
- **New:** `ui-tui/src/transport/Transport.ts` — interface (`request<T>`, `on('event'|'exit', …)`, `start`, `kill`).
- **New:** `ui-tui/src/transport/LocalSubprocessTransport.ts` — extract existing logic from `gatewayClient.ts`.
- **New:** `ui-tui/src/transport/RelayTransport.ts` — WebSocket client, envelope-wraps JSON-RPC, handles `tui.rpc.request/response/event`, reconnect loop.
- **Edit:** `ui-tui/src/gatewayClient.ts` — reduce to coordinator that holds a `Transport`; preserve current public API so app code is unchanged.
- **Edit:** `ui-tui/src/entry.tsx` — factory picks transport based on `--remote <url>` CLI arg or `HERMES_RELAY_URL` env.
- **Edit:** `ui-tui/src/app/gatewayContext.tsx`, `interfaces.ts` — widen types from concrete `GatewayClient` to `Transport` where needed.

**No Python-side changes in this phase.** `tui_gateway/server.py` stays byte-identical.

**Success criteria:**
- Local mode (no env var) behaves exactly as before — no regression.
- `HERMES_RELAY_URL=ws://localhost:9999 HERMES_RELAY_TOKEN=xxx tsx src/entry.tsx` attempts WSS connect (can be mocked for this phase).
- Unit tests for envelope wrap/unwrap.

**Agent:** `general-purpose`, worktree-isolated against `feat/tui-transport-pluggable`.

### Phase 3 — Glue: CLI flag + auth flow + smoke *(after Phase 1 + 2 both land)*
**Repos:** both.
- Flag parsing in `hermes_cli/main.py` (hermes-agent).
- Pairing flow: desktop calls `/pairing/register` on relay (loopback-initiated by Bailey OR operator-minted code), user pastes code into `hermes --remote` prompt, client sends `auth` envelope with `pairing_code`, stores returned `session_token`.
- `~/.hermes/remote-sessions.json` storage (mirror Android `SessionTokenStore` semantics — fail closed, atomic write).
- Desktop cert-pin store (JSON file, SHA-256 SPKI per `host:port`, first-seen TOFU).
- Reconnect/backoff logic in `RelayTransport`.
- **End-to-end test:** from this Docker-Server → start relay → paste code → from Windows → `hermes --remote wss://docker-server.ts.net:8767` → full session works (prompt, tool use, image paste, approval modal).

### Phase 4 — Polish + docs *(post-smoke)*
- Update `~/.hermes/hermes-relay/README.md` with desktop install section.
- Update hermes-relay `CHANGELOG.md` `[Unreleased]`.
- Update hermes-relay `DEVLOG.md` with session summary.
- Bump version via `scripts/bump-version.sh 0.8.0-alpha`.
- User-docs page in `user-docs/guide/desktop.md`.

## Branch / PR Strategy

**hermes-relay:**
- Feature branch `feature/desktop-tui-mvp` off `dev`.
- Phase 1 PR: `feat(relay): add tui channel for remote desktop TUI`.
- Phase 3 + 4: additional commits on same branch, one release PR at the end merging to `dev`.

**hermes-agent (fork):**
- Feature branch `feat/tui-transport-pluggable` off `axiom`.
- Phase 2 PR (internal): `refactor(ui-tui): pluggable transport abstraction`.
- This is a clean architectural win on its own — candidate for upstream PR to NousResearch once validated locally (follows the same pattern as PR #8556).
- Phase 3 adds the `--remote` flag on the same branch.

## Open Decisions (locked by user 2026-04-22)

1. **Branch strategy:** hermes-relay `feature/desktop-tui-mvp` off `dev`; hermes-agent `feat/tui-transport-pluggable` off `axiom`. ✅
2. **Channel name:** `tui`. ✅
3. **Auth flow:** paste-code (not QR — QR is phone-primary). User runs pairing command on server, pastes code into desktop prompt. ✅
4. **MVP scope:** server-side tool execution only; client-side routing is v2. ✅

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| `tui_gateway` subprocess invocation assumes specific env vars / working directory | Relay can't spawn it cleanly | Phase 1 agent reads `hermes_cli/main.py` subprocess call verbatim; mirrors exactly |
| Approval prompts rely on synchronous stdin read the gateway might bypass under WSS | Approval modal stuck open | JSON-RPC request/response is already fully async on the Node side — no change |
| Image paste on Windows clipboard differs from macOS/Linux | Broken paste on Windows | Image handling is Node-side (clipboard.ts uses platform native tools: pbpaste/xclip/powershell); no protocol change needed |
| Reconnect loses in-flight tool calls | User sees orphaned tool cards | Phase 3 reconnect sends `tui.attach` with `resume_session_id`; relay re-attaches to existing subprocess if still alive |
| TUI rendering assumes local terminal TTY capabilities that WSS round-trip can't preserve | Garbled output | TUI already uses alternate-screen diff rendering; terminal capabilities are local to Node, not proxied. No risk. |

## Success Metric

From a fresh Windows machine: install Node 20, `npm install -g @codename-11/hermes-tui-remote` (or equivalent), `hermes --remote wss://docker-server.ts.net:8767 --pair ABC123`, and do a full interactive session (prompt → tool use → image paste → approval) without a single "feature missing" or "protocol mismatch" error.

## Follow-up Ideas (explicit non-MVP)

- **Per-tool client-side routing** — insert at `model_tools.py::handle_function_call()` per the Obsidian `Desktop Client.md` plan. Adds `terminal`, `read_file`, `write_file`, `patch`, `search_files` running on the Windows client against local repos.
- **Single-binary distribution** — `pkg` or `esbuild --bundle` + bundled Node. Ships as `hermes-tui.exe`.
- **Desktop-only features** — IDE integration (VS Code extension), local notification bridge, native drag-drop file attach.
- **Upstream PR to Nous** — the transport-pluggable refactor is defensible on its own merits (decouples frontend from runtime). Good candidate for PR after Phase 2 validation.
