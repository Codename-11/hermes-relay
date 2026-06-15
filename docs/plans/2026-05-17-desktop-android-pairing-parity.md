# Desktop Android Pairing Parity Plan

**Status:** Ready for implementation planning
**Date:** 2026-05-17
**Owner surface:** Windows Tauri tray app and desktop CLI sidecar
**Reference:** `docs/android-ui-design-reference.md`
**Goal:** Bring the desktop tray pairing, route selection, settings hierarchy, and default theme into parity with the Android app's clean connection setup model while keeping the desktop UI limited to one active paired relay instance for now.

---

## Bottom Line

Desktop should feel like the Android connection experience adapted to Windows:

- Dark mode by default.
- One active Hermes relay instance in the desktop UI.
- Pairing is a first-class flow, not a settings form.
- LAN, Tailscale, and manual routes are explicit endpoint candidates.
- Advanced controls are collapsed by default.
- Visible "tier" language is removed from the tray app.
- CLI and JSON configuration remain available for operators.

This is a product/UI parity pass, not a relay protocol rewrite.

---

## Android References To Match

Use these as source material, not as code to copy:

- `docs/android-ui-design-reference.md`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/theme/Theme.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/PairScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ConnectionWizard.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/QrPairingScanner.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/network/EndpointResolver.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ConnectionsSettingsScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/EndpointsCard.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/SettingsExpandableCard.kt`

The matching patterns are:

- `PairScreen` makes pairing a full route with a clear title and completion path.
- `ConnectionWizard` supports method choice, scan/paste/manual paths, duplicate prompts, bounded verification, and separate API/relay auth failure language.
- `EndpointResolver` uses strict priority endpoint probing with short timeouts and a cache.
- `EndpointsCard` makes LAN/Tailscale/public routes visible and lets users choose a route.
- `SettingsExpandableCard` keeps advanced controls available without making the default settings screen feel like a control panel dump.

---

## Current Desktop Gap

Current desktop tray code is already useful, but the Pair and Settings views still show early desktop scaffolding:

- Pairing is a compact form in `desktop/tray/ui/index.html`, not a guided flow.
- The Pair form accepts a single relay URL and code; it does not expose invite paste or endpoint candidate probing.
- Settings includes visible `Tier` selection.
- Advanced options are all inline: computer-use flag, hotkey, overlay visibility, and blocklist.
- The dashboard currently reports session count and can list multiple stored sessions, even though the next desktop product direction should expose one active instance for now.
- CSS currently has light-mode roots; the next pass should make the tray app dark by default and align with the Android graphite/brand-purple direction.

Do not delete CLI capabilities to solve these. The desktop app can be intentionally simpler while the CLI remains operator-grade.

---

## Scope

### In Scope

- Desktop tray pairing UX.
- Desktop config/session selection rules.
- Desktop endpoint candidate display and probing.
- Default dark desktop theme.
- Removal of visible "tier" settings and "Easy tier" labels in the tray UI.
- Collapsed Advanced settings disclosure.
- Documentation updates for desktop parity behavior.
- Tests and smoke checks for the desktop tray.

### Out Of Scope

- Reworking Android.
- Changing relay pairing payload shape unless an implementation audit proves the desktop CLI cannot consume existing v3 payloads.
- Supporting multiple active desktop relay instances in the UI.
- Building a full desktop camera QR scanner in the first slice. Pasting the pairing invite text and manual code are enough for parity in the Windows tray flow; a camera scanner can be a later enhancement using the same state model.
- Removing CLI flags, daemon mode, or JSON policy.

---

## Product Decisions

### One Active Desktop Instance

For now the tray app exposes exactly one active relay instance:

- Fresh desktop profile starts unpaired.
- Successful pairing creates or replaces the active desktop pairing.
- If an active pairing exists, pairing another relay shows an explicit "Replace current relay?" confirmation.
- The UI should not show a multi-server switcher in this pass.
- Existing `~/.hermes/remote-sessions.json` may still contain more than one session for CLI compatibility, but the tray app chooses one active relay and presents that as the desktop connection.
- Replacement should update `desktop-control.json` or the desktop config state so startup, daemon start, Devices, grants, and overlay all target the same active relay.

Implementation note: keep the Rust/serde config backward-compatible with existing `tier` and session files. Hide/remove visible tier controls first. Only remove persisted fields after a separate compatibility pass.

### LAN vs Tailscale vs Manual

Desktop pairing should render route candidates as user-facing choices:

- LAN: best for same-network local testing; usually plain `ws://`.
- Tailscale: best remote path; prefer `.ts.net` or Tailscale-advertised endpoint when available.
- Manual: explicit relay URL entered by the user.

When a pairing invite carries endpoint candidates, desktop should:

- Validate the invite locally.
- Display candidates with role, host, port, and transport.
- Probe candidates using the same strict-priority behavior as ADR 24.
- Prefer the highest-priority reachable endpoint.
- Show the selected route in the Pair flow, Overview, and Devices context.
- Keep failures understandable: "LAN unreachable", "Tailscale endpoint timed out", "API auth failed", "Relay auth failed".

### Advanced Settings

Default settings should stay compact. Move these behind a collapsed `Advanced` disclosure:

- Experimental computer-use.
- Emergency hotkey.
- Overlay visibility and future overlay position.
- Blocklist editor.
- Raw relay URL override.
- Any future auto-start or sidecar path diagnostics.

Keep normal settings focused on current relay status, re-pair/replace, daemon auto-start, and visible overlay state.

### Dark Mode Default

Desktop tray should default to a dark graphite theme even if Windows is light:

- Near-black graphite app background.
- Slightly raised panels.
- Subtle borders.
- Hermes purple as accent, not the dominant background.
- Green/amber/red reserved for state.
- Short labels and compact controls.

If theme selection is added, it belongs in Advanced or Appearance and defaults to `dark`.

---

## Target User Flow

1. User opens desktop tray dashboard.
2. If no active relay exists, dashboard lands on Pair.
3. Pair shows three methods:
   - Paste invite.
   - Enter code and endpoint manually.
   - Use existing local CLI/session if exactly one valid session exists.
4. Pair validates input and shows a connection log.
5. Desktop probes endpoint candidates:
   - Checking LAN endpoint.
   - Checking Tailscale endpoint.
   - Checking manual endpoint.
6. Desktop verifies API health and relay session auth separately.
7. Desktop saves one active pairing.
8. Daemon can start immediately if enabled.
9. Overview shows route, daemon state, grants, and overlay state without requiring scroll.
10. Re-pairing later requires explicit replacement confirmation.

---

## UI Shape

### Pair View

Replace the single form with a compact guided surface:

- Header: "Pair with your server".
- Active method segmented control or three compact method buttons.
- Primary area changes by method:
  - Pairing invite textarea with "Pair" primary action.
  - Manual relay/API URL plus six-character code.
  - Existing local session import when available.
- Connection log card below the current step.
- Endpoint candidate list appears after invite parse.
- Success returns to Overview.

### Overview

Show one active connection:

- Relay name or host.
- Route badge: LAN, Tailscale, Manual, Public, or Unknown.
- Daemon state.
- Overlay state.
- Grants pending.
- Recent activity.

Do not show "N paired" as the main mental model. If multiple stored sessions exist, show a quiet warning or Advanced note: "CLI has additional stored sessions; desktop is using this one."

### Settings

Settings should have:

- Current relay summary.
- Replace pairing action.
- Daemon auto-start toggle.
- Overlay visible toggle if we decide it belongs in normal settings.
- Collapsed `Advanced` disclosure for raw controls.

Remove visible:

- `Tier` select.
- `Easy tier` badge.
- Easy/Standard/Advanced copy from tray UI.

Keep docs clear that CLI/JSON remains advanced-capable without presenting that as a user-selectable app tier.

---

## Implementation Phases

### Phase 0 - Audit And Baseline

- Map current desktop config and session files:
  - `desktop/tray/src-tauri/src/main.rs`
  - `desktop/tray/ui/index.html`
  - `desktop/tray/ui/app.js`
  - `desktop/tray/ui/styles.css`
  - `desktop/src/commands/pair.ts` or current pair command location.
- Confirm how CLI currently handles `--pair-qr`, endpoint role, and selected URL.
- Confirm whether `remote-sessions.json` has enough metadata to identify the active desktop relay.
- Document any protocol gaps before editing UI.

### Phase 1 - Data Model And Backend Commands

- Add desktop concept of `active_relay_url` or equivalent selected relay state if current `relay_url` is insufficient.
- Keep backward compatibility with existing config:
  - Continue deserializing `tier`.
  - Stop exposing or writing `tier` from UI.
- Add Tauri command for parsing/probing a pasted pairing invite if CLI output is not enough for a good UI.
- Add Tauri command for "replace active pairing" that:
  - Confirms replacement in UI.
  - Runs pair.
  - Saves the selected active relay.
  - Emits dashboard refresh.

### Phase 2 - Pair Flow UI

- Replace `Pair New Relay` form with the guided Pair route.
- Support manual code plus endpoint URL.
- Support pasted pairing invite.
- Show endpoint candidates and selected winner.
- Show connection log events with elapsed time.
- On success, navigate to Overview and optionally start daemon.

### Phase 3 - Endpoint Parity

- Mirror ADR 24 semantics:
  - Strict priority.
  - Reachability only breaks ties within priority.
  - 4-second per-candidate timeout.
  - 60-second cache where appropriate.
- Show route badges consistently:
  - LAN.
  - Tailscale.
  - Public.
  - Manual.
  - Unknown.
- Keep API auth and relay auth failure states separate.

### Phase 4 - Settings Cleanup

- Remove `settingsTier` from HTML and JS.
- Remove "Easy tier" badge from Pair.
- Keep `tier` serde compatibility in Rust until a separate migration decides otherwise.
- Add collapsed `Advanced` disclosure.
- Move raw/low-frequency controls into Advanced.
- Default the desktop theme to dark.

### Phase 5 - Polish And Verification

- Tune layout at short desktop window heights so Start/Pause/Emergency remain visible.
- Verify no scroll trap in Pair or Settings.
- Verify overlay and dashboard refresh after pair, replace, start, pause, emergency stop, settings save, and grant resolution.
- Update docs and screenshots if applicable.

---

## Tests And Local Verification

Run at minimum:

```powershell
cd C:\Users\Bailey\Desktop\Open-Projects\hermes-relay\desktop
node --check tray\ui\app.js
node --check tray\ui\overlay.js
npm run tray:check
npm run tray:test
npm run tray:dev
```

Manual smoke:

- Fresh temporary `USERPROFILE` starts dark and unpaired.
- Pair route is visible without scrolling.
- Manual pair path can connect to a local relay.
- Pasted invite path shows LAN/Tailscale candidates.
- Tailscale route appears when payload contains a Tailscale candidate.
- Replacement prompt appears when an active pairing already exists.
- Replacing active pairing updates Overview, daemon target, Devices, and overlay.
- Settings has no visible Tier select.
- Advanced is collapsed by default and contains raw/low-frequency controls.
- Start, Pause, and Emergency Stop are reachable at a small window height.
- Right-click tray menu still works.
- Left-click tray opens dashboard only.
- Overlay remains click-through and status-only.

---

## Acceptance Criteria

- Desktop tray defaults to dark mode.
- Pairing is a first-class flow, not a raw settings form.
- Desktop supports one visible active relay instance.
- Pairing a second relay requires explicit replacement.
- LAN, Tailscale, and manual endpoints are visible when available.
- Endpoint probing follows Android/ADR 24 priority semantics.
- Pairing logs show API health and relay auth as separate phases.
- Visible Tier settings/copy is removed from the tray UI.
- Advanced settings are collapsed by default.
- CLI and daemon remain fully usable for advanced/operator workflows.
- Docs link this plan from the Android UI reference and desktop control plan.

### Follow-up Completed

- Terminal / CLI tab added after the pairing parity pass to open the remote TUI in a real terminal and expose shell, chat, daemon, status, tools, and doctor commands for the saved active desktop relay without reintroducing user-facing tiers. Copied commands use `hermes-relay` without `--remote` by default; an explicit override command is shown only for one-off route overrides.
- Diagnostics tab added to show local active relay, route, sidecar/shim, daemon, consent, overlay, blocklist, session store, config, and pending-grant state, plus an in-app `doctor --json` run through the bundled CLI sidecar.
- Host-side pairing now emits a `hermes-relay://pair?payload=...` invite URL alongside the QR, and the desktop Paste invite flow accepts raw JSON, base64 JSON, copied invite URLs, or copied lines containing the invite URL. Desktop `--pair-qr` now uses `relay.code` for the relay auth exchange and keeps the top-level `key` reserved for the Hermes API bearer.
- Overview now includes an explicit active-relay desktop-tool permission control with Grant/Revoke actions, so users can allow the tray daemon from the GUI without needing an interactive terminal consent prompt.

---

## Open Questions

1. Should desktop replacement prune old desktop sessions from `remote-sessions.json`, or only switch the active desktop relay and leave CLI sessions intact?
2. Should "Use existing local session" auto-select when exactly one valid session exists, or require user confirmation every time?
3. Should the first implementation include Windows camera QR scanning, or keep paste invite as the desktop parity path?
4. Should Tailscale route preference be automatic when reachable, or should the user be able to prefer LAN for local testing?
5. Should theme have an Auto option later, or should desktop remain dark-only for the alpha release?

---

## Team Implementation Handoff

Use this as the goal prompt for a single coordinated implementation run:

> Implement `docs/plans/2026-05-17-desktop-android-pairing-parity.md`. Reference `docs/android-ui-design-reference.md` and the Android files listed in the plan, but do not change Android. Bring the Windows Tauri tray app to desktop parity for pairing, LAN/Tailscale endpoint choice, one active desktop relay instance, default dark theme, and collapsed Advanced settings. Remove visible Tier settings/copy from the tray UI while keeping backward-compatible config deserialization. Keep CLI/daemon behavior intact. Verify with `node --check tray/ui/app.js`, `node --check tray/ui/overlay.js`, `npm run tray:check`, `npm run tray:test`, and a live tray smoke run.

Recommended worker split:

- Coordinator: Own scope, resolve tradeoffs, keep docs and acceptance criteria aligned.
- Android reference worker: Map `PairScreen`, `ConnectionWizard`, `EndpointResolver`, `EndpointsCard`, and `SettingsExpandableCard` into concrete desktop UI/backend requirements. Read-only except docs notes.
- Rust/Tauri backend worker: Own `desktop/tray/src-tauri/src/main.rs` and config/session command behavior. Do not edit UI files except generated type assumptions if absolutely necessary.
- Desktop UI worker: Own `desktop/tray/ui/index.html`, `desktop/tray/ui/app.js`, and `desktop/tray/ui/styles.css`. Implement dark theme, Pair flow, one-instance UI, and Advanced disclosure.
- Verification worker: Own test/smoke execution and a concise verification note. Do not patch production files unless the coordinator assigns a specific fix.

All workers must assume other agents may be editing adjacent files. Do not revert unrelated dirty work.
