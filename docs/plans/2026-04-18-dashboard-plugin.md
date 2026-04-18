# Plan: Hermes-Relay Dashboard Plugin

> **Purpose.** Ship a hermes-agent dashboard plugin that surfaces relay state in the gateway's web UI via four tabs (Relay Management, Bridge Activity, Push Console stub, Media Inspector). The plugin consumes the upstream Dashboard Plugin System (commit `01214a7f` on `axiom`) and is served by the already-existing symlink `~/.hermes/plugins/hermes-relay` → `<repo>/plugin` — meaning we only add a `plugin/dashboard/` subtree and two new relay HTTP routes, nothing else.
>
> **Scope model.** New worktree at `../hermes-android-dashboard-plugin` rooted at `feature/dashboard-plugin` (branched off `origin/main`). Single session, agent team. Independent of `feature/voice-barge-in`.
>
> **Origin.** 2026-04-18 triage of upstream `axiom` changes. Four enhancement candidates identified; #3 (push console) intentionally stubbed pending FCM work on the deferred list.
>
> **Branching.** From `origin/main` — no dependency on other in-flight branches.
>
> **Flavor.** N/A — this is a server-side plugin; phone app unchanged.

## Context

**Why now.** The upstream Dashboard Plugin System landed on `axiom` in three commits (`01214a7f` plugin system, `3f6c4346` theme, `247929b0` OAuth providers). Several deferred items from the MVP audit (cron manager, skills browser, memory viewer) were previously blocked on "needs relay extension"; with this plugin system those flip — most can be handled by the upstream dashboard directly, and the relay plugin only needs to surface **relay-specific** state that upstream can't see. This plan captures the four items that only the relay knows about: paired-device state, bridge command history, push delivery (future), and media-registry tokens.

**Intended outcome.** Operator opens the hermes-agent dashboard, sees a new "Relay" tab, and can:
- View paired devices + revoke/extend sessions without SSH
- Audit recent bridge commands + safety-rail decisions (blocked / confirmed / executed)
- See placeholder UI for future push console (clearly marked "FCM not configured")
- Inspect active MediaRegistry tokens (screenshots, attachments) for debugging

**Non-goals (explicit).**
- Full push notification delivery (blocked by FCM not-yet-started — stub only)
- Skills browser / cron manager / memory viewer (those belong in upstream dashboard, not this plugin)
- Mobile app UI changes (separate Phase M track)
- Any modification to hermes-agent upstream

## Verified upstream contract

All facts below pulled from `hermes-agent-upstream/` local clone + `axiom` branch — **not guessed**.

| Concern | Fact |
|---------|------|
| Discovery path | Gateway scans `~/.hermes/plugins/<name>/dashboard/manifest.json` at startup (`hermes_cli/web_server.py`) |
| Manifest keys | `name`, `label`, `description`, `icon`, `version`, `tab.{path,position}`, `entry` (default `dist/index.js`), `css`, `api` (e.g. `plugin_api.py`) |
| Icon whitelist | 20 Lucide names: Activity, BarChart3, Clock, FileText, KeyRound, MessageSquare, Package, Settings, Puzzle, Sparkles, Terminal, Globe, Database, Shield, Wrench, Zap, Heart, Star, Code, Eye |
| Frontend SDK access | Global `window.__HERMES_PLUGIN_SDK__` (React + shadcn subset + hooks) and `window.__HERMES_PLUGINS__.register(name, Component)` |
| Component props | None — plugins are routed components that pull data via `SDK.fetchJSON()` / `SDK.api.*` |
| Build tooling | None required — dashboard `<script src=...>` loads the IIFE bundle verbatim. We commit the pre-built `dist/index.js` |
| Backend mount | `dashboard/plugin_api.py` must export `router: APIRouter`; auto-mounted at `/api/plugins/<name>/` |
| Backend auth | Plugin routes bypass session-token auth (gateway enforces localhost-only at bind time) |
| Hot reload | `POST /api/dashboard/plugins/rescan` rediscovers manifests + reloads frontend bundles; Python `plugin_api.py` changes still need gateway restart |
| Example plugin | `plugins/example-dashboard/` in upstream — file tree: `dashboard/{manifest.json, plugin_api.py, dist/index.js}` |

## Verified relay contract

| Concern | Fact |
|---------|------|
| Route registration | `plugin/relay/server.py:1779-1887` — `create_app()` calls `app.router.add_get(...)` |
| Server access in handler | `server: RelayServer = request.app["server"]` |
| Loopback exemption | Per-handler, no middleware. Pattern: `if request.remote not in ("127.0.0.1", "::1"): raise HTTPForbidden()`. Used by `/pairing/register`, `/media/register`, `/bridge/status` |
| Bridge command log | **Does not exist.** `BridgeHandler.pending` is in-flight only; `latest_status` is a single snapshot. Need to add `recent_commands: deque(maxlen=N)` keyed in `handle_command()` + `handle_response()` |
| Media listing | `MediaRegistry` has `register/get/cleanup/size` but no `list_all()`. Add one, lock-guarded, filters expired |
| Test pattern | `aiohttp.test_utils.AioHTTPTestCase` + plain `unittest`. Model on `plugin/tests/test_sessions_routes.py` (bearer) and `plugin/tests/test_bridge_status.py` (loopback) |

## Architectural decisions

1. **Single plugin with internal tabs, not four plugins.** The manifest allows one `tab.path` per plugin. Four plugins would fragment the nav and confuse operators. One plugin at `/relay` with an internal shadcn `Tabs` component keeps relay concerns grouped. (ADR-worthy — recorded in `docs/decisions.md`.)

2. **Pre-built IIFE bundle committed to git.** The upstream example uses plain `React.createElement` (no build). For four non-trivial tabs that's painful to maintain. Decision: write source in JSX under `plugin/dashboard/src/`, bundle with `esbuild` to `plugin/dashboard/dist/index.js`, commit both. Dashboard never runs the build; operators get a ready-to-serve bundle. esbuild is the only dev dep.

3. **Loopback-only for new relay routes.** `/bridge/activity` and `/media/inspect` are loopback-gated like `/bridge/status`. Dashboard plugin backend runs inside the gateway process (also localhost) and calls relay at `http://127.0.0.1:8767/...` — no bearer minting needed. Media paths are sanitized (basename only) even so, so a future decision to expose externally won't leak FS layout.

4. **Dashboard backend is a thin proxy.** `plugin_api.py` exposes `/api/plugins/hermes-relay/{overview,sessions,bridge-activity,media,push}` and proxies to the relay HTTP server. No business logic in the plugin — relay stays source of truth.

5. **Push console is a real tab, stub data.** Tab renders "FCM integration not configured" banner + link to deferred-items doc. Keeps the nav layout correct for future work; swapping in real data is additive.

## Scope legend

| Tier | Meaning |
|------|---------|
| **R** | Relay server work (Python, in `plugin/relay/`) |
| **D** | Dashboard plugin work (Python backend + JS frontend under `plugin/dashboard/`) |
| **Doc** | Documentation / user-site change |

**Effort sizing.** S / M / L.

## Sequencing & parallelism

| Wave | Units | Mode | Why |
|------|-------|------|-----|
| **Wave 1** | R1, R2 | **Parallel** | Disjoint files: R1 touches `channels/bridge.py`, R2 touches `media.py`. Both needed before the new routes. |
| **Wave 2** | R3 | Serial | Route handlers + tests — depends on R1+R2 landing first. |
| **Wave 3** | D1, D2 | **Parallel** | D1 is backend proxy (Python), D2 is frontend bundle (JS). Disjoint trees. Both depend on R3's API shape. |
| **Wave 4** | D3 | Serial | Manifest + `dashboard/` layout wiring — stitches D1+D2, trivial once both exist. |
| **Wave 5** | Doc1 | Serial | Docs agent cites concrete file paths from Waves 1–4. |

**Shared-file hot-spots.** `plugin/relay/server.py` — only R3 touches it. `plugin/dashboard/src/index.jsx` — only D2 touches it.

**External dependency.** `esbuild` (dev-time only) — added via `plugin/dashboard/package.json`. Not a runtime dep.

---

# Wave 1 — Parallel: relay state plumbing

## R1. Bridge command ring buffer

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Effort:** Python

**Summary.** Add a bounded ring buffer of recent bridge commands to `BridgeHandler` so the dashboard can render an activity feed.

**Scope / Acceptance criteria.**
- New `@dataclass BridgeCommandRecord` in `plugin/relay/channels/bridge.py` with fields: `request_id`, `method`, `path`, `params` (redacted), `sent_at` (float ms), `response_status` (int | None), `result_summary` (str | None), `error` (str | None), `decision` (Literal["pending","executed","blocked","confirmed","timeout","error"]).
- `BridgeHandler.__init__` creates `self.recent_commands: deque[BridgeCommandRecord] = deque(maxlen=100)`.
- `handle_command()` appends a `pending` record before `await ws.send_str(...)`.
- `handle_response()` mutates the matching record (by `request_id`) with response status + decision.
- Timeout path in the existing `asyncio.wait_for(...)` block flips decision to `timeout`.
- Blocked path (safety-rail denial on phone side returning `bridge.denied`) flips to `blocked`.
- Method `get_recent(limit: int = 100) -> list[dict]` — returns JSON-serializable records newest-first.
- Redaction rule: params containing keys in `{"password","token","secret","otp","bearer"}` are replaced with `"[redacted]"`.
- Unit test `plugin/tests/test_bridge_activity.py` covers: record added on command, updated on response, timeout path, redaction, ring-buffer eviction at N+1.

**Files to touch.**
- `plugin/relay/channels/bridge.py` (modify)
- `plugin/tests/test_bridge_activity.py` (new)

**Agent brief.**
> Implement R1 per `docs/plans/<this file>.md`. Read `CLAUDE.md`, `plugin/relay/channels/bridge.py`, and `plugin/tests/test_bridge_channel.py`. Add `BridgeCommandRecord` dataclass + `recent_commands` deque + `get_recent()` method on `BridgeHandler`. Wire append/update into `handle_command()` and `handle_response()` without changing external behavior. Use `python -m unittest plugin.tests.test_bridge_activity`. Commit on `feature/dashboard-plugin` as `feat(relay): add bridge command ring buffer for dashboard activity feed`.

**Dependencies.** None.

---

## R2. MediaRegistry.list_all()

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Effort:** Python

**Summary.** Public method on `MediaRegistry` that returns a sanitized snapshot of all active tokens — enables the media-inspector tab without leaking absolute paths.

**Scope / Acceptance criteria.**
- New `async def list_all(self, *, include_expired: bool = False) -> list[dict]` on `MediaRegistry` in `plugin/relay/media.py`.
- Acquires the existing `self._lock` for the snapshot.
- Each dict contains: `token`, `file_name` (basename only), `content_type`, `size`, `created_at`, `expires_at`, `last_accessed`, `is_expired`. **Never** includes the absolute `path`.
- Skips entries with `is_expired=True` unless `include_expired=True`.
- Sorted newest-first by `created_at`.
- Unit test `plugin/tests/test_media_inspect.py` covers: empty registry → `[]`, registered entry appears with basename only (asserts `path` key absent), expired entries hidden by default, `include_expired=True` flag works, ordering by `created_at`.

**Files to touch.**
- `plugin/relay/media.py` (modify — add method)
- `plugin/tests/test_media_inspect.py` (new)

**Agent brief.**
> Implement R2 per the plan. Read `plugin/relay/media.py` and `plugin/tests/test_media_registry.py` for the existing patterns. Add `list_all()` as described. Do NOT expose `self._entries` directly; always return fresh dicts with absolute paths stripped. Test with `python -m unittest plugin.tests.test_media_inspect`. Commit as `feat(relay): add MediaRegistry.list_all for dashboard inspector`.

**Dependencies.** None.

---

# Wave 2 — Serial: relay HTTP routes

## R3. /bridge/activity + /media/inspect + /relay/info

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Effort:** Python

**Summary.** Three loopback-gated routes the dashboard backend proxies to. `/relay/info` is an aggregate status endpoint so the management tab makes one call instead of three.

**Scope / Acceptance criteria.**
- Add three handlers in `plugin/relay/server.py`:
  - `handle_bridge_activity(request)` — loopback check, `server.bridge.get_recent(limit)` where limit is query param `?limit=N` (default 100, max 500).
  - `handle_media_inspect(request)` — loopback check, `await server.media.list_all(include_expired=?)` where include_expired comes from `?include_expired=true` (default false).
  - `handle_relay_info(request)` — loopback check, returns `{version, uptime_seconds, session_count, paired_device_count, pending_commands, media_entry_count, health: "ok"}`.
- Register in `create_app()` adjacent to `/bridge/status`.
- Loopback guard factored into a tiny helper: `def _require_loopback(request): ...` to avoid copy-paste.
- Handlers return `web.json_response({...})` with keys `{"activity": [...]}`, `{"media": [...]}`, `{...}` respectively.
- Tests in `plugin/tests/test_bridge_activity.py`, `plugin/tests/test_media_inspect.py`, and new `plugin/tests/test_relay_info.py`: loopback-accepted, non-loopback → 403, smoke of response shape.

**Files to touch.**
- `plugin/relay/server.py` (add 3 handlers + helper + 3 route registrations)
- `plugin/tests/test_bridge_activity.py` (extend with route test)
- `plugin/tests/test_media_inspect.py` (extend with route test)
- `plugin/tests/test_relay_info.py` (new)

**Agent brief.**
> Implement R3. Model handlers on `handle_bridge_status()` (lines 1275-1312) in `plugin/relay/server.py` for the loopback-gate pattern. Add `_require_loopback()` helper near the other `_require_*` helpers. Register routes next to `/bridge/status`. Run `python -m unittest discover plugin.tests`. Commit as `feat(relay): add /bridge/activity /media/inspect /relay/info for dashboard`.

**Dependencies.** R1, R2.

---

# Wave 3 — Parallel: dashboard plugin body

## D1. Plugin backend (`plugin_api.py`)

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Effort:** Python

**Summary.** FastAPI router that proxies the four tab data calls to the relay over loopback HTTP.

**Scope / Acceptance criteria.**
- New `plugin/dashboard/plugin_api.py` exporting `router: APIRouter`.
- Five routes:
  - `GET /overview` → proxies `GET http://127.0.0.1:{RELAY_PORT}/relay/info`
  - `GET /sessions` → proxies `GET /sessions` **on relay** (needs a real bearer — or add loopback branch to `handle_sessions_list`; see decision note below)
  - `GET /bridge-activity` → proxies `/bridge/activity`
  - `GET /media` → proxies `/media/inspect`
  - `GET /push` → returns static `{"configured": false, "reason": "FCM not yet wired; see docs/plans/..."}`
- Uses `httpx.AsyncClient` (FastAPI stock) with a 5s timeout.
- Relay port read from env `HERMES_RELAY_PORT` (default 8767) at import time.
- All handlers convert relay errors to `HTTPException(502, detail=...)` so the UI can show "relay unreachable".
- Lightweight unit test `plugin/dashboard/test_plugin_api.py` (not in `plugin/tests/` since this lives with the plugin).

**Decision note — `/sessions` proxy.** Relay's `/sessions` currently requires bearer. Two options:
- **(A)** Extend `handle_sessions_list` in `plugin/relay/server.py` to accept loopback without bearer. Smaller change, keeps plugin proxy simple. **Chosen.**
- **(B)** Plugin mints its own bearer via a dedicated "dashboard key" in the relay config. More machinery; defer.

Add option (A) to R3's acceptance criteria: `handle_sessions_list` gains a loopback-exempt branch returning all sessions (no "current session" highlight since there's no caller).

**Files to touch.**
- `plugin/dashboard/plugin_api.py` (new)
- `plugin/dashboard/test_plugin_api.py` (new)
- `plugin/relay/server.py` (tiny extension to `handle_sessions_list`; amend R3's commit or separate)

**Agent brief.**
> Implement D1. Read upstream `plugins/example-dashboard/dashboard/plugin_api.py` in `hermes-agent-upstream/` as the structural reference. Use `httpx.AsyncClient`. All five routes listed in the plan. Add loopback branch to `handle_sessions_list` in `plugin/relay/server.py` as noted. Commit as `feat(dashboard): add plugin_api.py proxy to relay HTTP`.

**Dependencies.** R3 route contracts.

---

## D2. Plugin frontend (React + esbuild)

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** L · **Effort:** JS/JSX

**Summary.** Four-tab React UI bundled to a single IIFE that the dashboard loads verbatim.

**Scope / Acceptance criteria.**
- Source tree:
  ```
  plugin/dashboard/src/
  ├── index.jsx                    # registers plugin, renders Tabs shell
  ├── tabs/
  │   ├── RelayManagement.jsx      # sessions list + revoke/extend + version
  │   ├── BridgeActivity.jsx       # ring-buffer table, filter by decision
  │   ├── PushConsole.jsx          # FCM-not-configured banner
  │   └── MediaInspector.jsx       # token table with TTL countdown
  └── lib/
      ├── api.js                   # thin SDK.fetchJSON wrappers (typed)
      └── formatters.js            # relative time, byte sizes, etc.
  ```
- `package.json` with only dev deps: `esbuild`, nothing else. Scripts: `build` (one-shot), `watch` (dev).
- `build.sh` wrapper for cross-platform parity (`npm run build` works but script keeps it git-hook-friendly).
- Bundle output: `plugin/dashboard/dist/index.js` — single IIFE, committed.
- Uses only `window.__HERMES_PLUGIN_SDK__` — no `import react from "react"`, no external HTTP libs, no CSS framework (tailwind classes are already provided by the dashboard shell).
- Registers as `window.__HERMES_PLUGINS__.register("hermes-relay", RelayPluginRoot)`.
- Every tab handles: loading state, empty state, error state (shows the proxy's 502 message verbatim).
- Bridge Activity tab has a filter chip row: `All | Executed | Blocked | Confirmed | Timeout | Error`.
- Media Inspector shows a TTL countdown that decrements in real time (setInterval 1s, cleaned up on unmount).
- Relay Management shows a "Revoke" button per session → calls `DELETE /sessions/{prefix}` via `SDK.fetchJSON`. Confirms with native `confirm()` (not a modal — avoid dialog blocking per the session's chrome rules).
- Refresh cadence: Overview polls 10s, Bridge Activity polls 5s, Media polls 15s. Pausable via a header "Auto-refresh" toggle (persisted to localStorage as `hermes-relay-autorefresh`).
- `plugin/dashboard/README.md` documents: build steps, how to regenerate bundle, SDK touch points.

**Files to touch.** All under `plugin/dashboard/` (new subtree).

**Agent brief.**
> Implement D2. Read upstream `plugins/example-dashboard/dashboard/dist/index.js` in `hermes-agent-upstream/` for the IIFE shape and SDK call patterns. Set up esbuild with a single JSX entry, target `es2020`, format `iife`, minify on. Do NOT bundle React — it comes from the SDK global. Write all four tabs. Commit the source AND the built `dist/index.js`. Run the build once, verify bundle loads by grepping for `window.__HERMES_PLUGINS__.register("hermes-relay"`. Commit as `feat(dashboard): add React UI for four relay tabs`.

**Dependencies.** R3 route contracts (not code — just shape).

---

# Wave 4 — Serial: stitch manifest

## D3. Manifest + dashboard subtree wiring

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Effort:** JSON + glue

**Scope / Acceptance criteria.**
- `plugin/dashboard/manifest.json`:
  ```json
  {
    "name": "hermes-relay",
    "label": "Relay",
    "description": "Paired devices, bridge activity, push, and media for hermes-relay",
    "icon": "Activity",
    "version": "0.1.0",
    "tab": { "path": "/relay", "position": "after:skills" },
    "entry": "dist/index.js",
    "css": null,
    "api": "plugin_api.py"
  }
  ```
- Verify path: `~/.hermes/plugins/hermes-relay/dashboard/manifest.json` resolves via the existing symlink.
- Smoke test: gateway restart → `GET /api/dashboard/plugins` includes `hermes-relay`; `POST /api/dashboard/plugins/rescan` works; dashboard UI shows "Relay" tab.
- If icon string isn't in the 20-name whitelist upstream, swap to `"Puzzle"` (default).

**Files to touch.**
- `plugin/dashboard/manifest.json` (new)

**Agent brief.**
> Implement D3. Create the manifest per spec. Verify against the upstream whitelist in `hermes-agent-upstream/hermes_cli/web_server.py`. Commit as `feat(dashboard): add plugin manifest`. Then have Bailey restart the gateway and load the dashboard to smoke-test.

**Dependencies.** D1, D2.

---

# Wave 5 — Docs

## Doc1. Comprehensive docs update

- **Status:** `[ ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Effort:** Markdown

**Scope / Acceptance criteria.**

**Root-level:**
- `CHANGELOG.md` — new `[Unreleased]` entry under `### Added`: "Dashboard plugin with four tabs: Relay Management, Bridge Activity, Push Console (stub), Media Inspector" + brief bullet list of the three new relay routes.
- `DEVLOG.md` — session entry for 2026-04-18 dashboard plugin work: Context, Decision summary, Implementation notes (one-liner per wave), Deferred (push console needs FCM).
- `README.md` — add one line under Quick Start mentioning the dashboard tab becomes available after gateway restart.
- `CLAUDE.md` — add `plugin/dashboard/` to Repository Layout; add four Key Files entries for the dashboard plugin (manifest.json, plugin_api.py, src/index.jsx, dist/index.js one-liners).

**`docs/`:**
- `docs/spec.md` — new subsection under integration points: "Dashboard plugin" explaining the four tabs, the three new relay routes, and the loopback-only auth model.
- `docs/decisions.md` — new ADR: "Dashboard Plugin: single plugin with internal tabs + pre-built IIFE bundle" (captures Decisions 1–4 from this plan).
- `docs/relay-server.md` — new "HTTP Routes" subsection enumerating `/bridge/activity`, `/media/inspect`, `/relay/info` with params + response shape.
- `docs/plans/2026-04-18-dashboard-plugin.md` — final copy of this plan (rename from the active plan file).

**User-site (`user-docs/` VitePress):**
- `user-docs/features/dashboard.md` (new) — user-facing "What is the dashboard plugin" + screenshot placeholders + link to relay docs.
- `user-docs/.vitepress/config.ts` (or equivalent) — add `dashboard.md` to sidebar nav.
- Confirm build locally: `cd user-docs && npm run build`.

**Not updating (verified):**
- `docs/HERMES-WEBAPI-REFERENCE.md` — dashboard plugin doesn't add WebAPI endpoints; only uses existing ones.
- `docs/security.md` — auth model unchanged (loopback-only); add one-line cross-reference only if ADR warrants it.
- `docs/upstream-contributions.md` — no new upstream asks.
- `AGENTS.md` — no change.

**Files to touch.** Listed above. Expect ~9 updates + 2 new files + 1 plan file.

**Agent brief.**
> Execute Doc1. Read every file named in the plan. Match existing style exactly — CHANGELOG bullets terse, DEVLOG verbose with sections, ADR in the numbered format in `docs/decisions.md`. For `user-docs/features/dashboard.md` model on `user-docs/features/voice.md`. Verify user-docs builds (`npm run build` in `user-docs/`). Commit as `docs: dashboard plugin spec, ADR, relay routes, user-site`.

**Dependencies.** All prior waves — cites concrete paths.

---

# Agent team composition (for execution after plan approval)

| Agent | Subtype | Wave(s) | Workload |
|-------|---------|---------|----------|
| A-relay | general-purpose | 1, 2 | R1 → R2 → R3 (serial within agent; R1 and R2 could split into two agents if parallelism desired) |
| A-backend | general-purpose | 3 | D1 |
| A-frontend | general-purpose | 3 | D2 |
| A-glue | general-purpose | 4 | D3 + restart smoke test |
| A-docs | general-purpose | 5 | Doc1 |

Waves 1 and 3 run in parallel internally. A-backend and A-frontend launch in the same message. A-glue waits for both. A-docs waits for A-glue.

# Worktree + branch setup (pre-flight, after plan approval)

```
git worktree add -b feature/dashboard-plugin ../hermes-android-dashboard-plugin origin/main
```

All agents operate in the worktree. No changes in the primary checkout (which is still on `feature/voice-quality-pass`).

# Verification (end-to-end)

After all waves land:

1. **Relay unit tests** — `cd <worktree> && python -m unittest discover plugin.tests` all green.
2. **Dashboard backend tests** — `python -m unittest plugin.dashboard.test_plugin_api` green.
3. **Frontend bundle sanity** — `node -e "require('fs').readFileSync('plugin/dashboard/dist/index.js', 'utf8').match(/__HERMES_PLUGINS__.register..hermes-relay/)"` returns a match.
4. **Manifest discovery** — on server: `hermes-relay-update && systemctl --user restart hermes-relay hermes-gateway`, then `curl http://127.0.0.1:<dashboard_port>/api/dashboard/plugins | jq '.[] | select(.name=="hermes-relay")'` shows the manifest.
5. **UI smoke** — Bailey opens dashboard → "Relay" tab visible → four subtabs render → sessions tab shows his paired phone → kick off a bridge command from the phone, watch Bridge Activity tab update within 5s → take a screenshot from the phone, watch Media Inspector populate.
6. **User-site build** — `cd user-docs && npm run build` succeeds.
7. **Revocation smoke** — click "Revoke" on a session in the UI → phone reconnect prompt appears → re-pair works.

# Open items / flags

- **Icon choice**: `"Activity"` is on the whitelist. Fallback `"Puzzle"` if upstream changes the list.
- **Push console evolution**: when FCM lands, only D2's `PushConsole.jsx` + D1's `/push` handler need changes. Plan deliberately leaves the contract surface in place.
- **CI**: GitHub Actions currently runs `plugin/tests/`. Confirm it picks up new test files automatically (it should via discovery). Not adding a new workflow.
- **Bundle size**: expect `dist/index.js` ~30-60 KB minified. If it balloons past 200 KB, revisit (lazy-load tabs).
- **Versioning**: plugin ships `0.1.0` in manifest. When the repo version bumps (e.g., `bash scripts/bump-version.sh`), manually keep plugin SemVer in step with breaking API changes only — not every release.
