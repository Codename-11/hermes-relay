# Native Secure Routes And Split Connection Model Plan

**Status:** Ready for implementation
**Date:** 2026-06-18
**Owner surface:** Android app, Relay plugin, pairing payloads, security docs, user docs
**Reference mockup:** `docs/mockups/native-encryption/connections-tab-split-model.png`
**Goal:** Make connection setup and management split cleanly between what a Hermes connection can do and how the phone reaches it, while adding a plugin-assisted native secure route that does not require Tailscale.

---

## Bottom Line

Hermes-Relay should model two separate questions:

- **Features:** what this connection can do.
- **Routes:** how this phone reaches Hermes.

This lets users enable Relay features without forcing one transport path. A user can use Relay tools over an existing Tailscale route, a public HTTPS route, a WireGuard/VPN route, LAN, or the plugin-provided secure proxy.

The plugin secure proxy is the recommended zero-effort native path when the plugin is present, but it is not mandatory. Standard Hermes remains first-class and still works against unmodified upstream Hermes without a plugin.

---

## Product Decisions

### 1. Standard Routes Are First Class

Standard Hermes connections must expose and manage the routes needed for normal app use:

| Surface | Typical port | Purpose |
| --- | --- | --- |
| API server | `8642` | Chat, sessions, runs, standard API health |
| Dashboard | `9119` | Manage, dashboard auth, standard voice/audio |
| Relay | `8767` | Optional Relay tools, terminal, bridge, media, Relay voice |

The standard path remains direct-to-upstream. It must not require the Relay plugin, custom server patches, or app-layer encryption that upstream does not understand.

### 2. Plugin Secure Proxy Is Recommended, Not Forced

When the Relay plugin advertises a secure proxy, Android should recommend it because it gives a zero-effort encrypted route. Users must still be able to choose another route, including Tailscale, HTTPS, WireGuard/VPN, LAN, or a custom route.

Recommended copy:

- Route title: `Plugin secure proxy`
- Badge: `Recommended`
- Security badge: `Pinned TLS` or `Encrypted`
- Detail: `Full Hermes host access`

### 3. First Run Asks For Route Choice Without Friction

First-run setup should show the recommended route and the alternatives in one short review screen. The default can be preselected, but users should be able to change it before saving.

Good first-run behavior:

- Show features advertised by the connection.
- Show the recommended route.
- Show alternative routes.
- Explain transport in one short line: `How this phone reaches Hermes`.
- Avoid a long security tutorial.
- Do not block progress unless the selected route is invalid or requires a plain-route acknowledgement.

### 4. Plain Routes Require Lightweight Acknowledgement

Plain LAN routes remain supported, but they need a low-friction acknowledgement matching the existing insecure-connection pattern.

Rules:

- Do not scare users away from normal LAN testing.
- Do not silently use a plain public route.
- Show the acknowledgement only when the selected route is plain.
- Persist the acknowledgement for that connection/route context where possible.
- Continue to label plain routes clearly: `Plain LAN`, `Plain route`, or `Plain (on Tailscale)` when applicable.

### 5. Plugin Proxy Covers All App-Supported Hermes Routes

The plugin secure proxy should cover all Hermes routes the app needs, not only Relay-only endpoints:

- API server routes used by Android.
- Dashboard routes used by Manage and standard voice.
- Dashboard websocket/audio paths used by standard voice where applicable.
- Relay routes used by terminal, bridge, sessions, media, Relay voice, and Relay diagnostics.

Because the app already offers a terminal interface in paired Relay mode, this is a high-trust host access mode. The UI and docs should state that clearly without presenting it as an extra scary prompt.

### 6. Tailscale Is Supported, Not Owned

Hermes-Relay should not become Tailscale documentation or own the user's tailnet lifecycle. Tailscale remains a first-class route option and a good recommendation for users who already use it.

The plugin can detect and advertise Tailscale routes when available. It should not require Tailscale for native secure proxy mode.

### 7. WireGuard/VPN Is A Manual Route Class

Raw WireGuard or other VPN routes should be supported through custom/manual routes. Do not make Hermes-Relay own WireGuard key exchange, peer lifecycle, NAT traversal, ACLs, or revocation in this implementation.

---

## Target UX Model

### Connections Screen

Use the mockup as the intended mental model:

- `Active connection summary`
- `Features`
- `Route`
- `Available routes`
- `Security`
- other connections in the list

The active connection summary stays compact. Deep route and security state belongs below it, not hidden behind a generic Advanced section.

### Features Section

This answers: "What can this connection do?"

Show compact capability rows/chips such as:

- `Standard API`
- `Dashboard`
- `Standard voice`
- `Relay tools`
- `Terminal`
- `Secure proxy available`

Do not make this section pick the transport. Enabling Relay tools must not force the plugin secure proxy route.

### Route Section

This answers: "How does this phone reach Hermes?"

Show the selected route with:

- route name
- endpoint or base URL
- security badges
- verified surfaces
- `Change route`
- `Test`

Example selected route:

```text
Plugin secure proxy
https://192.168.1.10:9443 - full Hermes host access
Recommended | Pinned TLS | Encrypted
API, dashboard, voice, relay, and terminal verified
```

### Available Routes Section

Each route row should show:

- icon
- route role/name
- URL
- active/preferred/fallback state
- security badge
- overflow menu

Initial route classes:

- `Plugin proxy`
- `Tailscale`
- `HTTPS`
- `LAN`
- `Custom`

Future route classes can include `WireGuard`, `Cloudflare Tunnel`, or named custom VPN routes without changing the model.

### Security Section

Security should summarize the selected route and stored credentials:

- selected-route encryption state
- pinned TLS or TOFU state
- hardware keystore / StrongBox state
- Relay sessions count/link
- plain-route acknowledgement state when relevant

Do not limit the security section to Relay WSS. Standard API and dashboard transport state must be visible too.

---

## Pairing Payload Direction

The current endpoint model already has `lan`, `tailscale`, `public`, and custom roles. Extend the next pairing payload shape so features and routes are not conflated.

Target conceptual shape:

```json
{
  "capabilities": {
    "standard_api": true,
    "dashboard": true,
    "standard_voice": true,
    "relay_features": true,
    "terminal": true,
    "secure_proxy": true
  },
  "routes": [
    {
      "id": "plugin-proxy",
      "role": "plugin_proxy",
      "base": "https://192.168.1.10:9443",
      "security": "pinned_tls",
      "recommended": true
    },
    {
      "id": "tailscale",
      "role": "tailscale",
      "api": "https://home-hermes.ts.net:8642",
      "dashboard": "https://home-hermes.ts.net:9119",
      "relay": "wss://home-hermes.ts.net:8767",
      "security": "private_vpn"
    },
    {
      "id": "lan",
      "role": "lan",
      "api": "http://192.168.1.10:8642",
      "dashboard": "http://192.168.1.10:9119",
      "relay": "ws://192.168.1.10:8767",
      "security": "plain"
    }
  ],
  "default_route": "plugin-proxy"
}
```

This shape is illustrative, not final wire format. The implementation should preserve backwards compatibility with existing v1/v2/v3 QR payloads.

---

## Plugin Secure Proxy Direction

### Purpose

Provide a native encrypted route that does not require the user to install or configure Tailscale.

### Basic Architecture

```text
Android app
  HTTPS/WSS with pinned trust
  -> Relay plugin secure proxy
      -> local Hermes API server
      -> local Hermes dashboard
      -> local Relay routes
```

The app-facing side is encrypted and session-authenticated. The host-local side can use upstream local HTTP/dashboard routes.

### Trust And Auth

Minimum target:

- Plugin generates or loads a TLS identity.
- Pairing QR carries the proxy route and pin/trust material.
- Android stores the pin/trust material for the route.
- Android authenticates with the Relay session token or a proxy-scoped token.
- Plugin enforces the same grants already used by Relay routes where applicable.

Implementation note: OkHttp `CertificatePinner` does not by itself make a self-signed certificate trusted on first connection. If the plugin uses self-signed TLS, Android needs a custom trust path or pinned trust manager for that connection.

### Scope Boundary

The secure proxy covers app-supported Hermes traffic. It should not silently become a general-purpose network proxy for unrelated host services.

Terminal already gives high-trust host access when the user enables Relay tools. The product language should describe this as host access for Hermes-Relay features, not as a low-risk standard route.

---

## Implementation Phases

### Phase 0 - Branch, Baseline, And Current-State Audit

**Summary.** Start from the right branch and verify current route/security behavior before editing.

**Scope / acceptance criteria.**

- Fetch current refs.
- Rebase `Codename-11/native-encryption` on `dev` if it is behind and the rebase is clean enough to proceed.
- Preserve unrelated user work.
- Confirm current code paths for:
  - `ConnectionData`
  - `EndpointCandidate`
  - `EndpointResolver`
  - `ConnectionViewModel`
  - `ConnectionWizard`
  - `ConnectionsSettingsScreen`
  - `EndpointsCard`
  - `TransportSecurityBadge`
  - `RelayHttpClient`
  - `RelayVoiceClient`
  - plugin pairing and relay server entry points
- Document any rebase conflicts or upstream drift before continuing.

### Phase 1 - Data Model: Features Separate From Routes

**Summary.** Add or normalize model fields so feature capability and route selection are independent.

**Scope / acceptance criteria.**

- Introduce a connection capability summary if the existing model cannot represent it clearly.
- Route model supports API, dashboard, relay, and optional proxy base.
- Route model can represent:
  - active route
  - preferred route
  - recommended route
  - fallback route
  - plain route acknowledgement state
  - security posture across all app-supported surfaces
- Existing standard-only connections migrate without losing API/dashboard behavior.
- Existing paired Relay connections keep their session and route behavior.

**Likely files.**

- `app/src/main/kotlin/com/hermesandroid/relay/data/ConnectionData.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/data/Endpoint.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/EndpointResolver.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt`

### Phase 2 - Android Connections UI Split

**Summary.** Reshape the Connections screen around the split model.

**Scope / acceptance criteria.**

- Active connection summary remains compact.
- Add or refactor sections:
  - `Features`
  - `Route`
  - `Available routes`
  - `Security`
- Feature rows do not select transport.
- Route rows show API/dashboard/relay/proxy coverage where available.
- `Plugin secure proxy` can be recommended without being forced.
- `Tailscale`, `HTTPS`, `LAN`, and custom routes remain selectable.
- Plain route acknowledgement appears only when needed and stays lightweight.
- No broad app theme rewrite.

**Likely files.**

- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ConnectionsSettingsScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ActiveConnectionSections.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/EndpointsCard.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/TransportSecurityBadge.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/InsecureConnectionAckDialog.kt`

### Phase 3 - First-Run And Pairing Review Flow

**Summary.** Let first-run users choose a route without adding setup friction.

**Scope / acceptance criteria.**

- QR scan or manual setup lands on a short review before saving when multiple routes exist.
- Review shows:
  - available features
  - recommended route
  - alternate routes
  - selected route security state
  - plain-route acknowledgement only if the selected route is plain
- Default route can be preselected.
- Users can choose Tailscale or another route even when plugin proxy is available.
- Existing API-only QR payloads still follow the standard connect path.
- Existing Relay QR payloads keep TTL, grants, session token, and route handling.

**Likely files.**

- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ConnectionWizard.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/QrPairingScanner.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/PairScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt`

### Phase 4 - Plugin Secure Proxy

**Summary.** Add the plugin-owned encrypted reverse proxy for app-supported Hermes routes.

**Scope / acceptance criteria.**

- Plugin can start a secure proxy endpoint.
- Proxy routes to local Hermes API, dashboard, and Relay surfaces needed by Android.
- Pairing advertises the secure proxy as a route candidate.
- Proxy route includes trust material needed by Android.
- Android can verify and store pinned trust for the proxy route.
- Proxy route can be selected, tested, and shown as active.
- Users can choose a non-proxy route even when proxy exists.
- Proxy failure falls back to other selected/available routes according to user preference.

**Likely files.**

- `plugin/relay/server.py`
- `plugin/relay/auth.py`
- `plugin/pair.py`
- `plugin/relay/config.py`
- new plugin secure proxy module if needed
- `app/src/main/kotlin/com/hermesandroid/relay/auth/CertPinStore.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/ConnectionManager.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayHttpClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/DashboardApiClient.kt`

### Phase 5 - Route Security Enforcement And Diagnostics

**Summary.** Make selected-route security visible and consistently applied across API, dashboard, Relay WSS, and Relay HTTP routes.

**Scope / acceptance criteria.**

- Security badge reflects selected route, not only Relay WSS.
- Relay HTTP helper routes follow the same pinned/trusted transport expectations as Relay WSS where applicable.
- Standard dashboard route verification uses the active/effective route, not stale persisted LAN-only data.
- Route tests cover:
  - API health
  - dashboard auth/status
  - standard voice route
  - Relay health/session
  - terminal route where paired
- Diagnostics distinguish route failures from auth failures.

**Likely files.**

- `app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/RelayHttpClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/DashboardApiClient.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/EndpointResolver.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/StatsForNerds.kt`

### Phase 6 - Docs And Security Plan Updates

**Summary.** Bring public docs and internal docs into alignment with the split model.

**Scope / acceptance criteria.**

- Update security docs to remove stale storage/encryption wording.
- Document:
  - standard no-plugin route security
  - plugin secure proxy
  - Tailscale/HTTPS/WireGuard/custom routes
  - plain-route acknowledgement
  - capabilities versus routes
  - full host access implications for plugin/terminal mode
- Remote-access docs explain that Tailscale is supported, not required.
- Getting-started docs keep the standard path first.
- Release/dev notes stay public-safe.

**Likely files.**

- `docs/security.md`
- `docs/remote-access.md`
- `docs/relay-server.md`
- `docs/decisions.md`
- `docs/android-ui-design-reference.md`
- `user-docs/guide/getting-started.md`
- `README.md` if the route model needs a concise public mention

### Phase 7 - Tests And Verification

**Summary.** Verify behavior with focused Android and plugin tests.

**Scope / acceptance criteria.**

- Unit tests cover:
  - route/capability separation
  - route priority and recommendation behavior
  - plain-route acknowledgement gating
  - dashboard route derivation from active endpoint
  - secure proxy route parsing
  - backwards compatibility with older QR payloads
- Plugin tests cover:
  - secure proxy route auth
  - proxy route registration in pairing payload
  - denied access without valid session/trust
  - no accidental proxying of unrelated services
- Run narrow Android verification first.
- Run full assemble only if packaging/release verification becomes necessary.

Suggested Android checks:

```powershell
./gradlew :app:compileGooglePlayDebugKotlin
./gradlew :app:testGooglePlayDebugUnitTest
./gradlew :app:lint
```

Suggested plugin checks depend on touched files, but likely include focused `pytest`/`unittest` modules under `plugin/tests`.

---

## Acceptance Criteria

The goal is complete when:

- Standard Hermes connections expose first-class API, dashboard, voice, and route state.
- Relay features and transport route choice are separate in the UI and data model.
- Plugin secure proxy is available as a recommended route when advertised.
- Users can use Relay features over Tailscale/HTTPS/LAN/custom routes without being forced onto plugin proxy.
- First-run setup offers route choice without high-friction setup.
- Plain routes require lightweight acknowledgement before use.
- Plugin secure proxy covers app-supported Hermes routes and is documented as high-trust host access.
- Route security covers API, dashboard, Relay WSS, and Relay HTTP where applicable.
- Existing standard-only and paired Relay connections migrate cleanly.
- Docs explain the split model and remote access options.
- Focused tests and lint/compile verification pass.

---

## Explicit Non-Goals

- Do not require Tailscale.
- Do not own the user's Tailscale account, tailnet, ACLs, or device lifecycle.
- Do not build raw WireGuard management in this slice.
- Do not require the Relay plugin for standard upstream Hermes.
- Do not proxy unrelated host services through the plugin secure proxy.
- Do not remove LAN or plain local development routes.
- Do not hide Relay terminal/bridge features.
- Do not replace native Android screens with the web dashboard.

---

## Agent Handoff Prompt

Use this as the implementation goal:

> Implement `docs/plans/2026-06-18-native-secure-routes.md`. Rebase `Codename-11/native-encryption` on `dev` if needed, preserving unrelated user work. Build the split connection model shown in `docs/mockups/native-encryption/connections-tab-split-model.png`: Features and Routes are separate first-class sections, Plugin secure proxy is recommended but not forced, standard API/dashboard/voice routes remain first-class, and Tailscale/HTTPS/LAN/custom routes stay selectable. Add the plugin-owned secure proxy for app-supported Hermes routes, pinned/trusted Android transport, lightweight plain-route acknowledgement, docs, and focused tests. Keep standard upstream Hermes working without plugin.

