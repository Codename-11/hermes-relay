# Relay Enhancement Layer + Agent-Context Injection

> Design doc + build contract. Branch `Codename-11/relay-enhancement-layer` (off `dev`).
> Coordinator-owned. Workers MUST follow the **Contracts** section verbatim so the
> surfaces integrate without rework.

## Goal

Give the relay plugin a single, clean, **removable** way to enhance the live agent —
starting with **agent-context injection** so the model learns conventions it can't
otherwise know (first consumer: **media-sensitivity classification**). Plus surface the
real chat transport tier in the UI, and plan a structured media channel to replace the
fragile `MEDIA:` text markers.

Hard rules (non-negotiable):
- **Vanilla/standard path stays upstream-only.** Every enhancement is OFF by default,
  config-gated, **fail-open** (never breaks a turn), and **no-op when the plugin is
  absent** (uninstall ⇒ gone). No fork patches.
- **Never touch the user's soul/memory.** Do NOT register a memory provider.
- Everything **retirement-tracked** — drop the monkey-patch the moment upstream ships a
  real plugin context hook (same way we retired bootstrap routes for `/api/sessions`).

## Why a monkey-patch (and why it's OK here)

Investigation (live host, 2026-06-20): hermes-agent has **no plugin system-prompt hook**.
`system_prompt_block()` is collected only from memory providers
(`agent/memory_manager.py:build_system_prompt`); lifecycle hooks (`on_session_start`,
`agent:start`, …) are observers (`emit_collect` is wired only to `command:*` decision
hooks). The one transport-agnostic seam is **`AIAgent._build_system_prompt()`**
(`agent/system_prompt.py`, calls `agent/prompt_builder.py`) — runs in the agent core, so it
covers **both** gateway (`/api/ws`) and SSE. We wrap it, fail-open. This is the same
accepted pattern as `hermes_relay_bootstrap` (route patches retired per-surface), extended
to a new seam.

---

## Contracts (FROZEN — all workers depend on these)

### C1. Config keys (env; dashboard toggles write these)
- `RELAY_AGENT_CONTEXT_ENABLED` — master gate for the injection layer. Default **off**
  (absent/`0`/`false` ⇒ off). `1`/`true`/`yes` ⇒ on.
- `RELAY_CONTEXT_MEDIA_SENSITIVITY` — per-block gate for the sensitivity instruction.
  Default **off**. Only consulted when the master is on.
- Read via `plugin/config.py` helpers (add `agent_context_enabled()` /
  `context_media_sensitivity_enabled()` — strict bool coercion mirroring existing helpers).

### C2. Relay audit endpoint (Worker P provides, Worker S consumes)
`GET /context/injected` on the relay (`:8767`).
- **Loopback callers skip bearer; remote callers require the session bearer** (mirror the
  `/notifications/recent` auth pattern in `plugin/relay/server.py`).
- 200 JSON body:
  ```json
  {
    "enabled": true,
    "blocks": [
      { "name": "media-sensitivity", "text": "<exact text injected into the system prompt>" }
    ]
  }
  ```
- `enabled` = master gate state. `blocks` = the blocks that WOULD be injected on the next
  turn given current config (empty list when disabled or no block active). This is the
  single source of truth the app audits — it must equal what the wrap actually injects.

### C3. Injected block format (Worker P)
Each block the wrap appends to the system prompt is wrapped in a labeled fence so it's
auditable and clearly attributed (NOT the user's soul):
```
<!-- hermes-relay:media-sensitivity -->
<the instruction text>
<!-- /hermes-relay:media-sensitivity -->
```
The `/context/injected` `text` field returns the inner instruction (no fence).

### C4. Media-sensitivity instruction (Worker P — the actual prompt text)
A concise instruction telling the agent: when it references media (inline `![alt](path)`,
`MEDIA:/path`, or relay media) that may be private/NSFW, mark it sensitive using the
markdown spoiler convention the client already honors: wrap as `||![alt](path)||` OR put a
sensitivity sentinel in the alt text (`nsfw`/`sensitive`/`spoiler`). Keep it < ~120 words,
declarative, no persona. (Client already blurs `spoilerWrapped || isSensitiveAltText(alt)`
→ `ChatImageContent.kt`.)

### C5. Client sensitivity re-thread (Worker S)
`ServerImageResult.Success` currently drops the `sensitive` bit. Re-add it:
`ServerImageResult.Success(val bytes: ByteArray, val sensitive: Boolean = false)`.
`ChatViewModel.resolveServerImage` already has `FetchedMedia.sensitive` — pass it through.
`RelayServerImage` blur must OR the markdown-parsed `image.sensitive` with the fetched bit.

---

## Work breakdown (strict file ownership — do NOT edit files outside your set)

### Worker P — Plugin / server (Python)
Owns: `plugin/enhancements/**` (new), `plugin/__init__.py`, `plugin/relay/server.py`,
`plugin/config.py`, `plugin/tests/test_enhancements.py` (new).
1. `plugin/enhancements/` package: a registry where each enhancement declares
   `name · phase('startup'|'plugin_load') · enabled() · apply() (fail-open) · retirement note`.
2. `context_injection` enhancement (phase `plugin_load`): fail-open wrap of
   `AIAgent._build_system_prompt`. Probe the seam; if the method/shape is absent, log at
   DEBUG and no-op. Append enabled blocks per C3. NEVER raise into a turn.
3. `media_sensitivity` block per C4, gated by C1.
4. `/context/injected` endpoint per C2 in `plugin/relay/server.py`.
5. Wire `register(ctx)` (`plugin/__init__.py`) to apply `plugin_load` enhancements
   (guarded; older hosts without the seam must still load tools/CLI fine).
6. `plugin/config.py` helpers per C1.
7. Tests: registry gating, fail-open when seam absent, block text present/absent by config,
   `/context/injected` shape. `python -m unittest plugin.tests.test_enhancements`.

### Worker S — Client: sensitivity + injected-context audit (Kotlin)
Owns: `app/.../ui/components/ChatImageContent.kt`,
`app/.../viewmodel/ChatViewModel.kt`, `app/.../network/relay/RelayHttpClient.kt`,
`app/.../ui/components/InjectedContextSheet.kt`.
1. C5 re-thread (`ServerImageResult.Success` + `resolveServerImage` + `RelayServerImage`).
2. `RelayHttpClient.fetchInjectedContext(): Result<InjectedContextAudit>` hitting C2.
3. `ChatViewModel.InjectedContext` gains a `relayServerBlocks: List<Pair<String,String>>`
   field (name→text), populated in `previewInjectedContext()` (fetch C2, best-effort).
4. `InjectedContextSheet` renders a **"Relay context (server-side)"** section listing the
   blocks (or a clear "none / disabled" note). Reuse the existing `ContextSection` style.

### Worker T — Client: transport-path UI (Kotlin)
Owns: `app/.../ui/components/ChatTransportStatusBadge.kt` (new),
`app/.../ui/RelayApp.kt`, `app/.../ui/screens/ChatSettingsScreen.kt`,
`app/.../ui/components/RelayStatusStrip.kt`.
1. `ChatTransportStatusBadge` — resolves the active tier from `streamingEndpoint` +
   `gatewayAvailability` + `serverCapabilities` and renders it: `⚡ Gateway` / `📡 Sessions`
   / `Completions` / `Runs` / `offline`, with color (green active, amber fallback, gray
   unavailable). Long-press/tooltip explains the endpoint.
2. Status strip (`RelayApp.kt` ~1326 / `RelayStatusStrip.kt`): replace the bare
   "api online" with the badge + route label (e.g. `⚡ Gateway · LAN`). Keep reachability
   separate (offline still reads offline).
3. `ChatSettingsScreen`: add a compact **basic→best tier ladder** above the existing
   segmented picker showing the order (Completions → Runs → Sessions → Gateway), the active
   one highlighted, and why ("auto → Gateway (best)" or "gateway unavailable → Sessions").
   Promote the gateway `SignInRequired` hint to a clear callout.

### Worker D — Dashboard toggles (JS/React + Python proxy)
Owns: `plugin/dashboard/**`.
1. Add a "Agent context" section to the Relay management tab with a **master toggle**
   (`RELAY_AGENT_CONTEXT_ENABLED`) + a **per-block toggle** (`RELAY_CONTEXT_MEDIA_SENSITIVITY`),
   written via the existing env/config proxy path. Off by default; clearly labeled
   "Experimental — injects an instruction into the agent's system prompt (server-side).
   Removable by uninstalling the relay plugin."
2. Rebuild the committed `plugin/dashboard/dist/index.js`.

### Coordinator — docs (this doc + the rest)
`docs/plans/2026-06-20-structured-media-channel.md` (Q3 plan — design only, not built here),
`docs/decisions.md` entry, `TODO.md` follow-ups + retirement note, `DEVLOG.md`, `CHANGELOG`.

---

## Quality bar / done
- Android: `./gradlew lint` + `:app:assembleSideloadDebug` green.
- Plugin: `python -m py_compile` + `python -m unittest plugin.tests.test_enhancements`.
- Verify **fail-open**: with `RELAY_AGENT_CONTEXT_ENABLED` unset, a chat turn is byte-for-byte
  unchanged; with the seam patched out / absent, no exception reaches a turn.
- Clean UI/UX: the transport badge + ladder read clearly; the injected-context audit shows the
  exact server-side block.
- Docs updated; retirement tracked in `TODO.md`.
