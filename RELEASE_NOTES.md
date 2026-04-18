# Hermes-Relay v0.5.0

**Release Date:** April 17, 2026
**Since v0.4.0:** v0.4.1 fast-follows (bootstrap middleware, tiered permissions, unattended access mode, voice-session sync) + 2026-04-17 polish pass (UI redesign, agent-aware status, auto-return, activity log)

> **The polish release.** v0.4.0 shipped the bridge channel surface; v0.5.0 makes it usable. The Bridge tab is restructured around a clear master/sub-feature hierarchy, the agent now knows about your phone's unattended state and screen lock so it can warn you instead of failing silently, and the phone auto-returns to Hermes-Relay after every run so you're never stranded on Starbucks/Chrome/whatever the agent left you in.

---

## 📥 Download

v0.5.0 ships in **two build flavors**. APK filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| **sideload** (recommended) | `hermes-relay-0.5.0-sideload-release.apk` | Full feature set — bridge channel, voice intents, unattended access, vision-driven `android_navigate`. Installs alongside the Play build with a `.sideload` applicationId. |
| **Google Play** | `hermes-relay-0.5.0-googlePlay-release.aab` | Conservative feature set (chat, voice, safety rails — no agent device control) to match Play Store's Accessibility policy. |
| googlePlay APK | `hermes-relay-0.5.0-googlePlay-release.apk` | Parity + diff tooling — not the primary download. |
| sideload AAB | `hermes-relay-0.5.0-sideload-release.aab` | Parity + diff tooling — not the primary download. |

**Verify integrity** with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for install steps.

> **Why two flavors?** The `googlePlay` build stays inside Play Store's Accessibility Service policy. The `sideload` build unlocks the full agent-control feature set and installs with a `.sideload` applicationId so both can coexist (sideload launcher labelled **"Hermes Dev"**). See [Release tracks comparison](https://codename-11.github.io/hermes-relay/guide/release-tracks.html).

---

## ✨ Highlights

### Bridge tab redesign

- **MASTER pill + rewritten copy.** The "Allow Agent Control" toggle now reads as the parent gate it actually is — a small "MASTER" pill next to the title plus subtitle text that explicitly names sub-features ("master switch — agent can read screen and act via the sub-features below"). No more wondering whether unattended is a peer or a child.
- **Snackbar instead of dead taps.** Tapping the master Switch when accessibility isn't granted used to be a silent no-op (Android's disabled-Switch behavior). Now it shows a snackbar with an "Open Settings" action that deep-links straight to Android's Accessibility Settings page.
- **Card reorder.** Master → Permission Checklist → [Advanced] → Unattended → Safety → Activity Log. Prereqs come before opt-in features; Activity Log goes last because it's a history view.
- **Runtime-permission rows always do something.** Tapping Microphone / Camera / Contacts / SMS / Phone / Location / Notifications now opens Android's app-info Settings page on every tap — same affordance as the special-permission rows (Accessibility, Overlay, Notification Listener). Eliminates the silent no-op after permanent denial that confused users on previous releases.
- **Optional badge no longer wraps.** "Notification Listener" + "Optional" on a portrait phone used to render as a lumpy two-line pill. Fixed via FlowRow layout + non-wrapping badge text.

### Unattended access — gating + global banner (sideload only)

- **Gated on master.** The unattended Switch is now disabled when Agent Control is off, with subtitle text "Requires Agent Control — enable the master switch above first." User can't flip it without observable feedback.
- **Inline keyguard alert.** The "Keyguard detected" warning is now an inline alert band inside the Unattended card instead of a separate sibling card.
- **Global UnattendedGlobalBanner.** Thin amber strip (28dp) at the top of every tab when master + unattended are both on. Pulsing amber dot, "Unattended access ON — agent can wake and drive this device", tap-to-Bridge. Theme-aware colours, WCAG AA+ in both light and dark mode.
- **System overlay chip + in-app banner now coordinate.** The cross-app WindowManager chip (visible when Hermes-Relay is backgrounded) hides when our app is foregrounded — the banner takes over. Backed by a new `AppForegroundTracker` (ProcessLifecycleOwner). Result: one indicator at a time, no visual noise.

### Agent-aware phone status

- **Unattended/screen/credential-lock visible to the LLM.** The system-prompt block (built by `PhoneStatusPromptBuilder`) and the `bridge.status` envelope (consumed by the host-side `android_phone_status` tool) both gained `unattended.supported / .enabled / .credential_lock_detected` and `screen_on` fields. The agent can now warn you upfront ("the screen is off and unattended access is off — wake the phone first") instead of finding out reactively via `keyguard_blocked` errors after a wasted command.
- **`android_phone_status` tool description tightened.** The LLM is now told explicitly when to warn (screen-off + unattended-off → ask user to wake; unattended-on + credential-lock → expect `keyguard_blocked`). Push-on-toggle so host cache reflects state changes within ~1s.

### Auto-return to Hermes-Relay

- **Tightened tool prompts.** `android_return_to_hermes` and `android_open_app` descriptions are rewritten with explicit `REQUIRED FINAL STEP` / `MANDATORY CLEANUP` framing. Less likely to be skipped.
- **Dual-signal safety net.** New `BridgeRunTracker` singleton coordinates two completion signals: Chat-tab SSE `run.completed` (fast, fires when phone's Chat tab is in the loop) and a 12s bridge-idle timer (works for ANY frontend — Discord, CLI, web, Slack). Whichever fires first dispatches a local `/return_to_hermes` so the phone auto-returns to Hermes-Relay after the agent finishes — even if the LLM forgot to call the return tool itself.

### Activity log finally records actions

- The Bridge tab's Activity Log card was scaffolded in v0.3 but never wired — the UI rendered the flow, the DataStore was ready, but no code ever called `recordActivity()`. Fixed in v0.5.0: `BridgeCommandHandler` now emits a `BridgeActivityEntry` for every dispatched command, with Success / Failed / Blocked status, route-specific summaries (`tap (540, 1200)`, `open_app com.starbucks.mobilecard`, `type "hello"`), and error text on failures. High-frequency polls (`/ping`, `/events`, `/current_app`) suppressed so the log shows user-meaningful activity, not noise.

### "Current app" status finally populated

- The `Current app` field in the Bridge tab's master card was hardcoded `null` with a TODO. Now wired to `HermesAccessibilityService.instance.currentApp` with a 5s periodic refresh — finally shows the actual foreground package.

---

## 🔧 Fixes

- **Banner status-bar overlap.** The new global banner sat at y=0 of the window with no inset padding, so on Android 15 edge-to-edge mode the system status bar icons (clock / wifi / battery) drew on top of the banner text. Fixed via `windowInsetsPadding(WindowInsets.statusBars)` + conditional `consumeWindowInsets` on the Scaffold so child TopAppBars don't double-pad.
- **TalkBack semantics.** Banner is now announced as a Button with the role hint, not just plain prose with a clickable hit-target.
- **Repository config-change churn.** `remember(LocalContext.current) { Repository(ctx) }` would re-instantiate DataStore repos on every rotation / dark-mode swap / locale change. Switched to keying off `applicationContext` (process-stable).

---

## 🏗️ Includes from v0.4.1 fast-follows

(Already merged into the integration branch before the polish pass — listed here for context since v0.4.1 wasn't tagged on its own.)

- **Bootstrap command middleware** for `/v1/chat/completions` + `/v1/runs` so vanilla upstream installs still see slash-command routing without the fork.
- **Tiered permission checklist** with JIT error surfacing for missing permissions.
- **Unattended access mode** (sideload-only) — the wake-lock + keyguard-dismiss machinery this release polishes.
- **Voice-session sync** so phone-local voice intents land in the server LLM session as proper assistant/tool message pairs.

---

## 🧪 Verification checklist (post-install)

- Bridge tab: master toggle, MASTER pill, Settings-deep-link snackbar on accessibility-needed.
- Permission rows tap → Android app-info page (every row).
- Unattended toggle disabled when master is off; enabled + scary-dialog when master is on.
- Global banner appears when master + unattended both ON; hides on Onboarding / Voice mode.
- WindowManager chip hides when Hermes-Relay is foregrounded.
- "Current app" status updates every ~5s while the Bridge tab is open.
- Activity Log populates with bridge commands (open_app, screen, tap, type, etc.) with Success/Failed/Blocked status.
- Discord-test: ask the agent to open Starbucks and read order history. After it finishes (no more tool calls), phone auto-returns to Hermes within ~12s.
- `/bridge/status` JSON envelope includes `unattended.{supported, enabled, credential_lock_detected}`.

See `CHANGELOG.md` for the full file-level diff and `DEVLOG.md` for the session narrative.

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)
