# Hermes-Relay UX Fix Plan — tracking checklist

> Companion to [`2026-06-16-e2e-ux-audit.md`](./2026-06-16-e2e-ux-audit.md). Check items off
> as they land. Branch: `feature/ux-audit-wave-1` off `dev`.
> Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[-]` deferred/reclassified.

Build note: Kotlin changes are **not** built here — Android Studio is the build path. Run
`./gradlew lint` before pushing (the CI gate). All edits below match existing in-file patterns.

---

## Phase 1 — "Wife-approved" quick wins (copy / small UI, high trust-per-effort)

- [x] **1.1 Kill "Standard/Advanced" tier labels on Welcome.** `ui/onboarding/OnboardingScreen.kt`
  WelcomePage `SetupPathSummary` labels → capability language ("Chat & Manage" / "Power tools").
  *Why:* violates the project's own no-tier-labels rule (`docs/plans/2026-06-07-…:20,426`).
  *Accept:* first onboarding screen shows no Easy/Standard/Advanced tier words.

- [x] **1.2 Gate explains the server-side relay-plugin prerequisite.** `ui/components/PowerFeatureGate.kt`
  `RequiresPairing` / `Unavailable` explanations → name that the Relay plugin must be installed
  & running on the Hermes server.
  *Why:* Standard-only users dead-loop "Pair to unlock → nothing to pair to."
  *Accept:* gate copy tells a plugin-less user what to do on the server, not just "pair."

- [x] **1.3 Flip destructive-confirm button emphasis.** `ui/components/DestructiveVerbConfirmDialog.kt`
  Make **Deny** the dominant safe button (filled), **Allow** lower-emphasis caution (amber outline);
  weighty via wording ("Allow this action"). Update the now-accurate KDoc.
  *Why:* red fill on *Allow* inverts "red = stop" muscle memory; the code already contradicts its
  own KDoc which promises the opposite.
  *Accept:* Deny is visually dominant; Allow is not a loud red CTA.

- [x] **1.4 Reframe Bridge safety summary in protection language.** `ui/components/BridgeSafetySummaryCard.kt`
  Rows → "Apps the agent can't touch", "Words that always ask first", "Turns itself off when idle";
  reassurance caption.
  *Why:* "Destructive verbs: 12" reads as *capabilities granted*, not *protections*.
  *Accept:* the three rows read as reassurances, not opaque integers.

- [x] **1.5 Notification-companion: action first.** `ui/screens/NotificationCompanionSettingsScreen.kt`
  Reorder Status (grant button) above About.
  *Why:* the user came to turn it on, not to read.
  *Accept:* Status + "Open Android Settings" is the first card.

- [x] **1.6a Suggestion chips send on tap.** `ui/screens/ChatScreen.kt` empty-state chips → send
  immediately instead of prefilling the composer.
  *Why:* a casual user expects a tap to start the conversation.
  *Accept:* tapping a suggestion sends it (haptic + clears composer).

- [x] **1.7 Disable the "Auto-TTS (coming soon)" live toggle.** `ui/screens/VoiceSettingsScreen.kt`
  Switch `enabled = false` while the feature is unimplemented.
  *Why:* a live toggle that does nothing is a 1.0 trust leak.
  *Accept:* the toggle is visibly disabled, copy unchanged.

---

## Phase 2 — Recovery & feedback (the trust contract)

- [-] **1.6b Persistent mic in the input bar.** *Reclassified from Phase 1* — `ChatInputBar.kt`
  is "morph, never widen"; the mic already shows when the field is empty, so a persistent mic only
  helps *while typing*. Design decision, not a quick win. Still open.
- [x] **2.1 Distinct "Pairing expired → Pair again" state.** Added `RelayUiState.Expired`
  (`RelayUiState.kt`) + derivation branch (`ConnectionViewModel.kt`: `AuthState.Failed → Expired`).
  The relay row now reads "Pairing expired — tap to pair again" and opens re-pair instead of looping
  a doomed reconnect (only `Stale` calls `connectRelay()`; `reconnectIfStale` is gated on `Paired`).
  ⚠️ Needs on-device validation that a real relay restart drives `authState → Failed` (the trigger).
- [x] **2.2 Context-aware Verify/timeout errors.** `ConnectionWizard.kt` timeout copy is now
  method-aware (code-not-accepted for EnterCode/ShowCode; gateway hint for Scan; generic otherwise).
- [~] **2.3 Tighten the feedback loop.** Done: "Stopped" snackbar + haptic on cancel (`ChatScreen.kt`);
  "Still working…" escalation after 4s of no first token (`MessageBubble.kt`). Deferred: transient
  "Ran /x ✓" slash chip — slash already surfaces a system-notice bubble + send-haptic; a transient
  chip needs a VM→UI event channel, tracked for later.
- [x] **2.4 Camera-denial → manual fallthrough.** `ConnectionWizard.kt` returns to the Method chooser
  with actionable guidance instead of a vanishing toast.
- [~] **2.5 Terminal usability.** Done: PASTE key in `ExtraKeysToolbar` wired to clipboard→`sendInput`
  (`TerminalScreen.kt`). Deferred: auto-focus keyboard on attach — WebView IME focus behaves
  differently across devices and must be validated on-device before shipping.

---

## Phase 3 — Management depth & structural

- [ ] **3.1 Resolve Bridge route-gate vs staged-copy contradiction** (`RelayApp.kt:1467` vs `BridgeScreen.kt:262`).
- [ ] **3.2 Manage: Cron *create* + Config *edit* (or honest relabel).**
- [ ] **3.3 Credentialed-MCP install; unify the two profile editors; surface server usage analytics.**
- [ ] **3.4 Workspace/file browser (Hermex parity).**
- [ ] **3.5 Crisp onboarding setup promise + 3-step mental model; reconsider public "Relay" naming.**

---

## Change log
- 2026-06-16 — Plan created from the e2e UX audit. Starting Phase 1.
- 2026-06-16 — **Phase 1 complete** (7 edits across 7 files on `feature/ux-audit-wave-1`):
  onboarding tier labels → capability copy; gate names the server-side Relay-plugin
  prerequisite; destructive-confirm Deny is now the dominant button + Allow is amber
  low-emphasis; Bridge safety summary reframed as protections; notification-companion
  Status card first; suggestion chips send on tap; Auto-TTS toggle disabled. `1.6b`
  (persistent mic) reclassified to Phase 2 — needs a design decision, not a quick win.
  Not yet committed; Android Studio build + `./gradlew lint` pending before push.
- 2026-06-16 — **Phase 2 (recovery & feedback)**: 2.1 (`RelayUiState.Expired` + derivation),
  2.2 (method-aware verify timeout), 2.4 (camera-denial fallthrough) complete; 2.3 partial
  ("Stopped" ack + "Still working…" escalation done, slash chip deferred); 2.5 partial (PASTE key
  done, auto-keyboard deferred to on-device validation). 13 app files touched across both phases.
