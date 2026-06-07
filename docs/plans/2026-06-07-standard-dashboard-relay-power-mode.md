# Standard Dashboard + Relay Power Mode Plan

**Status:** Ready for agent-team implementation
**Date:** 2026-06-07
**Owner surface:** Android app, relay compatibility layer, user docs
**Goal path:** `docs/plans/2026-06-07-standard-dashboard-relay-power-mode.md`
**Goal:** Make the default Android experience feel like a standard Hermes dashboard/client while keeping relay-only capabilities as power-user features that clearly require pairing.

---

## Bottom Line

The app should have a standard default path and a power-user relay path:

- Standard users can connect with the Hermes API server and dashboard auth, then use chat, skills, cron, MCP, profiles, models, and basic settings.
- Relay-specific features remain supported, but are no longer part of the default mental model.
- Terminal, Bridge, relay sessions, route/grant management, media file inspection, and profile memory file editing are power-user features.
- Power-user features must clearly show "Requires pairing" when the current connection is not paired.
- Pairing remains the source of truth for relay grants, terminal/bridge access, TUI/desktop bridge flows, and device-control features.
- Avoid visible tier labels such as Easy, Standard, or Advanced. Use plain product language: "Power tools", "Requires pairing", "Pair to unlock".

This is a UI/data-plane realignment. It is not a relay protocol removal.

---

## Verified Context

### Upstream Hermes Direction

Latest upstream Hermes has moved much of the admin surface into dashboard APIs:

- Dashboard/admin server on `:9119` includes `/api/status`, `/api/config`, `/api/skills`, `/api/cron/jobs`, `/api/mcp/servers`, `/api/profiles`, `/api/memory`, toolsets, env, and plugin routes.
- Dashboard auth supports remote gated sessions using cookies, plus loopback/session-token behavior for the web dashboard.
- API server on `:8642` exposes standard external surfaces such as `/v1/capabilities`, `/v1/skills`, and `/v1/toolsets`.
- The lightweight `rusty4444/hermes-android` model is API/dashboard oriented and does not cover relay pairing, grants, bridge, or terminal behavior.

### Local Current State

Relevant local files:

- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ConnectionsSettingsScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ConnectionWizard.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/data/ConnectionData.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/HermesApiClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/EndpointResolver.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayProfileInspectorClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ProfileInspectorScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ProfileInspectorViewModel.kt`
- `hermes_relay_bootstrap/_handlers.py`
- `hermes_relay_bootstrap/_patch.py`
- `docs/android-ui-design-reference.md`
- `user-docs/reference/configuration.md`

Current gaps:

- Bottom navigation currently makes Terminal and Bridge first-class default destinations.
- Standard dashboard/admin capabilities are not first-class native Android screens.
- Profile inspector and memory file editing are relay-session oriented.
- Skill toggle still has compatibility paths around relay/bootstrap behavior.
- Connection state does not separately model API auth, dashboard auth, and relay pairing.

---

## Product Rules

### Standard Default

Default visible areas should prioritize:

- Chat
- Sessions or conversation history
- Skills
- Cron jobs
- MCP servers/catalog
- Profiles and SOUL basics
- Models/config basics
- Connection health
- Appearance and normal app settings

MCP can appear in the standard management surface, but raw server command editing, env/key reveal, and risky install details belong behind power-user disclosure.

### Power Tools

Power tools include:

- Terminal
- Bridge
- Relay sessions
- Route/grant inspection
- Media token/file inspector
- Profile memory file editor
- Raw config editor
- Env reveal and secret management
- Diagnostics and developer options

These features stay supported. They should be quieter in default navigation and clearly gated when pairing is missing.

### Pairing Gate Pattern

Use one shared UI pattern for unpaired power features:

- Show the feature name and short capability summary.
- Show a stable status label: `Requires pairing`.
- Show a primary action: `Pair to unlock`.
- Provide one sentence of explanation: "This feature uses relay grants and requires a paired device session."
- Do not expose raw URL/token forms as the first recovery path.
- Do not present disabled empty screens without a clear pairing CTA.

If the device was previously paired but the relay session expired, use:

- Status: `Pairing expired`
- Primary action: `Pair again`
- Secondary action where appropriate: `View connection`

### Auth Model

Model three separate auth contexts:

| Context | Typical port | Purpose | User-facing language |
| --- | --- | --- | --- |
| API server | `8642` | Chat, runs, read-only standard capabilities | API connection |
| Dashboard | `9119` | Skills, cron, MCP, profiles, config, model/admin APIs | Dashboard sign-in |
| Relay | `8767` | Terminal, Bridge, relay sessions, device grants, media relay | Pairing |

Never imply that dashboard sign-in unlocks relay-grant features. Never imply that relay pairing replaces API/dashboard auth.

---

## Feature Placement Matrix

| Feature | Default placement | Required auth | Pairing required? |
| --- | --- | --- | --- |
| Chat | Main nav | API key/session | No |
| Conversation sessions | Main nav or Chat subview | API key/session | No |
| Skills browse/toggle | Standard management | Dashboard auth | No |
| Cron jobs | Standard management | Dashboard auth | No |
| MCP servers/catalog | Standard management | Dashboard auth | No |
| Profiles/SOUL basics | Standard management | Dashboard auth | No |
| Model/config basics | Standard settings | Dashboard auth | No |
| Memory provider status/reset | Standard settings | Dashboard auth | No |
| Profile memory file editor | Power tools | Relay session | Yes |
| Terminal | Power tools | Relay session/grants | Yes |
| Bridge | Power tools | Relay session/grants | Yes |
| Relay sessions/routes/grants | Power tools | Relay session | Yes |
| Media inspector | Power tools | Relay session | Yes |
| Raw config/env reveal | Power tools | Dashboard auth | No, but confirm/reveal required |
| Diagnostics/developer options | Power tools | Varies | Varies |

---

## Implementation Waves

## Wave 1 - Dashboard Client and Connection Model

**Summary.** Add first-class dashboard connectivity alongside existing API and relay connection state.

**Scope / Acceptance criteria.**

- Add `DashboardApiClient` under `app/src/main/kotlin/com/hermesandroid/relay/network/`.
- Support:
  - `GET /api/status`
  - `POST /auth/password-login`
  - `GET /api/auth/me`
  - Dashboard cookie persistence through the existing secure storage pattern or an explicit encrypted cookie store.
- Add typed models for dashboard auth status and feature capabilities.
- Extend `ConnectionData.kt` with:
  - `dashboardUrl`
  - `dashboardAuthRequired`
  - `dashboardAuthProviders`
  - `dashboardLastStatus`
  - migration/default derivation from API host to `:9119`.
- Keep API key, dashboard auth, and relay token storage separate.
- Unit tests cover URL derivation, auth state parsing, missing dashboard handling, and migration defaults.

**Files likely to touch.**

- `app/src/main/kotlin/com/hermesandroid/relay/data/ConnectionData.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/HermesApiClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/EndpointResolver.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/DashboardApiClient.kt` new
- `app/src/test/kotlin/com/hermesandroid/relay/network/DashboardApiClientTest.kt` new

**Agent brief.**

> Implement Wave 1 from `docs/plans/2026-06-07-standard-dashboard-relay-power-mode.md`. Add a dashboard client and connection metadata without changing relay pairing behavior. Keep API, dashboard, and relay auth as separate states. Add focused tests for dashboard status/auth parsing and connection migration.

---

## Wave 2 - Shared Feature Gate UX

**Summary.** Create reusable UI for power features that require pairing.

**Scope / Acceptance criteria.**

- Add a reusable component for gated power features, for example `RequiresPairingCard` or `PowerFeatureGate`.
- Component supports:
  - `Requires pairing`
  - `Pairing expired`
  - `Unavailable on this server`
  - `Dashboard sign-in required`
- Component always provides a clear primary action.
- Terminal, Bridge, relay sessions, and profile memory file editor routes use this gate when unpaired.
- No power feature falls through to an empty/error-heavy screen when pairing is missing.
- Compose previews or screenshot tests cover at least unpaired, expired, and paired states.

**Files likely to touch.**

- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ProfileInspectorScreen.kt`
- terminal and bridge screen files discovered during implementation

**Agent brief.**

> Implement Wave 2. Build the shared pairing gate component and wire it into every relay-only route. The UX copy must use "Requires pairing" and "Pair to unlock", not tier labels. Preserve existing paired behavior.

---

## Wave 3 - Standard Management Screens

**Summary.** Add native standard screens backed by upstream dashboard APIs.

**Scope / Acceptance criteria.**

- Add native screens/viewmodels for:
  - Skills list/toggle using dashboard `/api/skills` and `/api/skills/toggle`.
  - Cron jobs list/detail/actions using `/api/cron/jobs`, runs, pause/resume/trigger/delete.
  - MCP servers/catalog using `/api/mcp/servers` and catalog/install endpoints.
  - Profiles/SOUL basics using `/api/profiles` and profile SOUL/model/description endpoints.
  - Config/model basics using dashboard config/model endpoints where available.
- Each screen handles:
  - Dashboard unavailable
  - Dashboard sign-in required
  - API unsupported on older Hermes
  - Empty state
  - Loading/error/retry
- Do not expose raw env reveal by default.
- Keep existing chat command palette behavior working while moving management UX to dashboard APIs.

**Files likely to touch.**

- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/DashboardApiClient.kt`
- new screen/viewmodel/model files under `ui/screens`, `viewmodel`, `data`, and/or `network`

**Agent brief.**

> Implement Wave 3. Add standard native management screens backed by the dashboard APIs. Keep screens compact and operational, following `docs/android-ui-design-reference.md`. Use dashboard sign-in and unsupported-server states instead of raw stack traces or relay pairing prompts.

---

## Wave 4 - Navigation and Settings Realignment

**Summary.** Make standard management the default path and move relay capabilities behind power tools.

**Scope / Acceptance criteria.**

- Adjust bottom navigation so Terminal and Bridge are not default-first for new standard users.
- Add a standard management destination or settings group for Skills, Cron, MCP, Profiles, and Config.
- Add a Power Tools section in Settings for Terminal, Bridge, Relay sessions, Media inspector, raw config/env, diagnostics, and developer options.
- Preserve deep links/routes for existing paired users.
- Do not remove Terminal or Bridge.
- Remove or avoid visible Easy/Standard/Advanced tier copy.
- Existing Settings categories stay compact and use disclosure for advanced controls.

**Files likely to touch.**

- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ConnectionsSettingsScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/DeveloperSettingsScreen.kt`
- `user-docs/reference/configuration.md`

**Agent brief.**

> Implement Wave 4. Rebalance navigation toward standard Hermes management while keeping relay tools accessible under Power Tools. Existing paired users must not lose routes; unpaired users should see pairing gates for relay-only features.

---

## Wave 5 - Pairing Flow Integration

**Summary.** Keep pairing strong, but position it as the unlock path for relay power features.

**Scope / Acceptance criteria.**

- Pairing remains a first-class full-screen flow.
- After successful pairing, probe/store dashboard URL if derivable and not already set.
- From any gated power feature, `Pair to unlock` opens the existing pairing flow and returns to the requested feature after success when practical.
- Manual connection remains available, but under Advanced/manual setup.
- Pairing confirmation continues to show route, grants, API server, relay endpoint, TTL, and warnings.
- Expired pairing state is distinct from never paired.

**Files likely to touch.**

- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ConnectionWizard.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/PairScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ConnectionsSettingsScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/EndpointResolver.kt`

**Agent brief.**

> Implement Wave 5. Keep the existing pairing security model and use it as the unlock path for power tools. Do not flatten relay tokens into API/dashboard auth. Add return-to-feature behavior where the navigation model supports it cleanly.

---

## Wave 6 - Relay Compatibility and Bootstrap Cleanup

**Summary.** Keep relay/profile-memory power paths, but stop treating bootstrap compatibility endpoints as the primary standard admin API.

**Scope / Acceptance criteria.**

- Keep relay profile memory file editing available as a paired power feature.
- Replace standard skills/config management calls with dashboard APIs where supported.
- Keep compatibility fallback for older Hermes versions, but mark it as fallback in code comments/docs.
- Audit `hermes_relay_bootstrap/_handlers.py` and `_patch.py` for routes now covered by upstream dashboard/API server.
- Update install/docs to reflect latest `API_SERVER_KEY` requirement for networked API server use.
- Decide whether to propose an upstream profile-memory file API. If not, document that this remains a relay power feature.

**Files likely to touch.**

- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayProfileInspectorClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ProfileInspectorViewModel.kt`
- `hermes_relay_bootstrap/_handlers.py`
- `hermes_relay_bootstrap/_patch.py`
- `install.sh`
- `README.md`
- `user-docs/reference/configuration.md`

**Agent brief.**

> Implement Wave 6. Keep profile memory file editing and relay-specific surfaces as paired power features. Prefer upstream dashboard APIs for standard features and narrow bootstrap compatibility to older-server fallback behavior.

---

## Wave 7 - Tests, Docs, and Release Notes

**Summary.** Verify the user-facing split and document the new connection model.

**Scope / Acceptance criteria.**

- Android unit tests cover:
  - dashboard URL derivation
  - dashboard auth status
  - pairing gate state mapping
  - unpaired vs expired vs paired feature availability
  - fallback behavior for older servers
- Compose/UI tests or screenshot checks cover:
  - Standard default navigation
  - Power feature requiring pairing
  - Dashboard sign-in required
  - Paired power feature available
- Docs explain:
  - API connection
  - Dashboard sign-in
  - Pairing for relay power tools
  - Which features require pairing
- Release notes call out that Terminal/Bridge are still supported but now presented as power tools.

**Files likely to touch.**

- `app/src/test/`
- `app/src/androidTest/` if UI tests already exist
- `docs/android-ui-design-reference.md`
- `user-docs/reference/configuration.md`
- `user-docs/features/dashboard.md`
- `README.md`
- `RELEASE_NOTES.md`

**Agent brief.**

> Implement Wave 7. Add focused tests and docs for the standard-vs-power split. The docs must be explicit that pairing is required for terminal/bridge/relay power features but not for normal API/dashboard use.

---

## Agent Team Execution Model

Recommended parallelization:

| Wave | Parallelism | Notes |
| --- | --- | --- |
| Wave 1 | Serial first | Establishes shared connection/auth models. |
| Wave 2 | Parallel after Wave 1 model shape is known | UI component work can run while API clients progress if contracts are stable. |
| Wave 3 | Parallel by surface | Skills, Cron, MCP, Profiles can be split across agents once `DashboardApiClient` contracts exist. |
| Wave 4 | Serial | Navigation/settings touches shared files. |
| Wave 5 | Serial or paired with Wave 4 | Pairing return paths depend on navigation shape. |
| Wave 6 | Parallel with Wave 7 docs after API decisions | Bootstrap cleanup and docs can proceed once standard APIs are wired. |
| Wave 7 | Final serial verification | Runs after surfaces land. |

Shared-file hot spots:

- `RelayApp.kt`
- `SettingsScreen.kt`
- `ConnectionData.kt`
- `DashboardApiClient.kt`
- `ProfileInspectorScreen.kt`
- `ProfileInspectorViewModel.kt`

Assign only one active agent at a time to each hot-spot file.

---

## Definition Of Complete

The goal is complete when all of the following are true:

- A fresh standard user can connect with API/dashboard credentials and use chat plus standard management screens without relay pairing.
- Terminal, Bridge, relay sessions, media inspector, and profile memory file editor are still available.
- Every relay-only feature clearly shows `Requires pairing` with a `Pair to unlock` path when unpaired.
- Existing paired users keep their relay workflows.
- API auth, dashboard auth, and relay pairing are modeled and displayed as separate connection states.
- Standard screens use upstream dashboard/API server endpoints where available.
- Bootstrap compatibility routes are fallback only, not the primary standard path.
- Docs and release notes explain the new standard/power split.
- Tests cover dashboard connection, pairing gates, and compatibility fallback.

---

## Explicit Non-Goals

- Do not remove pairing.
- Do not remove Terminal or Bridge.
- Do not force every standard user through relay pairing.
- Do not use visible Easy/Standard/Advanced tier language in the app UI.
- Do not expose env/secret reveal in the default settings path.
- Do not replace the native Android app with a dashboard WebView.
- Do not break older paired sessions without a migration path.

