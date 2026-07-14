# Hermes Relay Android UI Design Reference

Status: design reference for future Android, Quest, and pairing-flow work.

Use this document when a future session asks for mobile UI polish, pairing flow changes, QR scanning, connection setup, onboarding, terminal cockpit, or "make it feel more like Orca." It is intentionally a reference, not an implementation task list.

## Scope

This project should keep its native Android stack:

- Kotlin + Jetpack Compose + Material 3 for app chrome and interaction flows.
- CameraX + ML Kit for QR scanning.
- Android WebView only where it is already the correct tool, such as xterm.
- Meta Spatial SDK for Quest/XR surfaces where needed.

Do not propose a React Native or Expo migration just because Orca's mobile app uses that stack. The useful lesson from Orca is the visual discipline and pairing UX, not the framework choice.

The Windows systray no longer has a visual application surface. It uses a native
right-click menu and opens the real CLI/TUI for interactive work, so this Android
reference does not define desktop tray layout or navigation. Historical desktop
parity plans remain under `docs/plans/`; ADR 37 records the current boundary.

## Source References

Orca mobile references:

- Repo: https://github.com/stablyai/orca
- Mobile package stack: https://raw.githubusercontent.com/stablyai/orca/main/mobile/package.json
- Mobile design tokens: https://raw.githubusercontent.com/stablyai/orca/main/mobile/src/theme/mobile-theme.ts
- Pair scan screen: https://raw.githubusercontent.com/stablyai/orca/main/mobile/app/pair-scan.tsx
- Pair confirm screen: https://raw.githubusercontent.com/stablyai/orca/main/mobile/app/pair-confirm.tsx
- Connection log card: https://raw.githubusercontent.com/stablyai/orca/main/mobile/src/components/ConnectionLog.tsx
- Pairing parser: https://raw.githubusercontent.com/stablyai/orca/main/mobile/src/transport/pairing.ts

Local Hermes references:

- Theme: `app/src/main/kotlin/com/hermesandroid/relay/ui/theme/Theme.kt`
- Pair route: `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/PairScreen.kt`
- Connection wizard: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ConnectionWizard.kt`
- QR scanner: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/QrPairingScanner.kt`
- Terminal WebView: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/TerminalWebView.kt`
- Morphing sphere: `app/src/main/kotlin/com/hermesandroid/relay/ui/components/MorphingSphere.kt`

## Visual Direction

Aim for a quiet agent-control cockpit, not a marketing page and not a generic Material sample.

Primary traits:

- Dark graphite base with brand purple as an accent, not full-screen purple saturation.
- Tight spacing and simple cards.
- Short, direct labels.
- One primary action per step.
- Visible system state over explanatory prose.
- Mono log details for connection and terminal diagnostics.
- Rounded cards around 8-14dp only when framing a real repeated item or tool.
- Native platform affordances for permissions, back navigation, sheets, and settings.

Avoid:

- Big decorative hero sections inside the app.
- Many nested cards.
- Explaining the UI inside the UI.
- Broad gradients or decorative blobs.
- Large purple panels for every surface.
- Reworking business logic during design-only passes.

## Token Guidance

Orca's useful token model is very small:

- `bgBase`: near-black app background.
- `bgPanel`: slightly raised panel background.
- `bgRaised`: row or badge background.
- `borderSubtle`: hairline panel border.
- `textPrimary`, `textSecondary`, `textMuted`.
- One accent blue plus green, amber, red status colors.
- Small spacing scale: 4, 8, 12, 16, 24.
- Small radii: row 6, card 14, button 6, camera 8.
- Body typography around 14sp, title around 18sp, meta/mono around 12sp.

Hermes should translate this into Compose tokens while preserving brand:

- Keep `HermesPrimary`/purple for identity, active states, scanner lock-on, and high-signal affordances.
- Add or reuse graphite-neutral containers for dense operational screens.
- Keep navy/purple for branded onboarding and MorphingSphere moments, but reduce it in setup and diagnostic surfaces.
- Prefer status colors for status meaning, not brand purple.

## Pairing Flow Target

The pairing flow should feel like a short, observable operation:

1. Choose pair method: Scan QR, enter/paste code, or show this device/server code when supported.
2. Scan or enter payload.
3. Validate payload locally.
4. Probe candidate endpoints.
5. Verify API auth and relay session auth separately.
6. Save the selected connection/session.
7. Return to the active connection surface with a clear success state.

The user should always know which step is active and where a failure happened.

### QR Scanner

Use the current native `QrPairingScanner` direction as the baseline:

- Full-screen route, not a small dialog.
- Camera viewport is large enough to frame a real QR comfortably.
- Rounded rectangular camera viewport with no extra card around it.
- Simple back/close affordance.
- Short three-step setup text above or near the scanner.
- Scanner reticle or corner brackets that visibly lock onto a code.
- Paste/manual fallback remains available.
- Camera unmounts or pauses when a paste sheet is active.
- Permission-denied state offers settings or paste-code fallback.

Orca uses Expo Camera for this. Hermes should keep CameraX + ML Kit because the rest of the Android app is native and already has QR parsing for Hermes payload versions.

### Status Log Card

Borrow Orca's `ConnectionLog` pattern for pairing and reconnect surfaces.

Use the card during:

- Connecting after QR scan.
- Endpoint probing.
- Pair-confirm deep link flow.
- Error states when there is useful diagnostic history.
- Reconnect or repair flows from connection settings.

Card behavior:

- Hidden when empty.
- Appears below "Connecting..." or the current step title.
- Max height around 200-240dp.
- Auto-scrolls to the latest entry.
- Uses mono 11-12sp text.
- Shows elapsed time since the first entry, not wall-clock time.
- Shows severity by icon/glyph and color.
- Allows 1-2 lines of detail per event.
- Keeps detail technical enough to debug, but not raw stack traces.

Suggested entry levels:

- `info`: normal progress.
- `success`: step completed.
- `warn`: fallback or degraded path.
- `error`: step failed.

Suggested log messages:

- `QR decoded`
- `Checking LAN endpoint`
- `Checking Tailscale endpoint`
- `API health verified`
- `Relay route verified`
- `Pairing code accepted`
- `Session token saved`
- `Profile list loaded`
- `Timed out waiting for relay`
- `API auth failed`
- `Relay auth failed`

The most important split for Hermes is API auth versus relay auth. Do not collapse those into a generic "Cannot connect" message.

### Timeouts

Pairing should not spin indefinitely. Orca caps initial pairing at roughly 25 seconds because its RPC reconnect loop is otherwise long-lived. Hermes should use the same principle:

- A bounded pairing timeout for initial setup.
- Per-phase logs so the timeout explains where it stalled.
- Retry and paste-code fallback visible from the error state.
- Long-lived reconnect behavior can remain more tolerant outside initial pairing.

## Screen-Specific Guidance

### Home / Chat

Keep this as the active agent surface. It can retain the MorphingSphere as an identity and voice-state element, but the surrounding chrome should be quieter and more operational.

Use restrained top status: connection, profile, voice, and relay state. Avoid making the first screen a settings dashboard.

### Settings

Settings should read as a compact control room:

- Category rows should be simple and dense.
- Current active connection/profile summary should be visible.
- Connection repair should open the pairing flow with logs.
- Avoid stacking multiple large cards before the user reaches the actual controls.

### Pairing

Treat pairing as a first-class flow, not a settings subpanel.

Target layout:

- Top app bar with short title.
- Method chooser or scanner.
- Large camera viewport for scan path.
- Paste/manual code as secondary action.
- Connection log card during connect/error.
- Success returns to active connection, not a dead-end success page.

### Quest / XR

Use this reference for Quest visual tone, but do not force the phone layout into XR. Quest should keep the spatial cockpit concept:

- MorphingSphere and voice pipeline remain first-class.
- Terminal panels should be spatial, readable, and anchored.
- Status logs can become compact floating diagnostic panels.
- QR pairing should use the same state model and log language, adapted to headset camera/input constraints.

## Implementation Notes For Future Sessions

When implementing this reference:

- Start with the pairing route and connection setup screens.
- Add a Compose `ConnectionLogCard` equivalent before changing broad app theme.
- Keep log event generation in the ViewModel or connection coordinator, not inside pure UI rendering.
- Use structured events with `timestamp`, `level`, `message`, and optional `detail`.
- Keep scanner parsing behavior compatible with `HermesPairingPayload` v1/v2/v3.
- Verify API and relay auth as separate logged steps.
- Keep changes flavor-aware and test with the sideload variant first.

Recommended first implementation slice:

1. Add a reusable Compose connection log card.
2. Emit pairing log events from the pairing/connect path.
3. Show the log card in `PairScreen` / `ConnectionWizard` during connect and error states.
4. Tighten scanner layout and paste-code fallback to match this reference.
5. Only then consider broader theme token changes.

## Future Session Prompt

Use this wording when handing the work to a new session:

> Follow `docs/android-ui-design-reference.md`. Keep the native Compose stack. Start with the pairing flow: add the clean status log card pattern, keep CameraX/ML Kit QR scanning, preserve paste/manual fallback, and log API auth separately from relay auth. Do not redesign unrelated screens in the same pass.
