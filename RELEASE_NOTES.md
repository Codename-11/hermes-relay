# Hermes-Relay-Android v1.2.0

**Release Date:** June 20, 2026
**Since v1.1.0:** A big personalization release — app themes, swappable sphere skins, and animated agent **pets** — paired with a transparency pass (see which transport you're on and exactly what the agent is told), a much faster cold start, in-app crash reporting, and a broad reliability sweep.

v1.2.0 is about making Hermes-Relay feel like *yours* and making it honest about what it's doing. Dress the app in one of eight themes, swap the agent orb for a hand-picked or AI-generated **pet** that reacts to what the agent is doing, and give each profile its own icon. At the same time, the chat status strip now names the actual streaming path (⚡ Gateway, 📡 Sessions, …), a "What the agent sees" sheet shows the exact extra context prepended to your next turn, and cold start is roughly three times faster. If something does go wrong, the app now catches the crash and offers a one-tap, pre-filled bug report.

---

## Download

v1.2.0 ships in two Android build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| Google Play | `hermes-relay-1.2.0-googlePlay-release.aab` | Upload this Android App Bundle to Play Console. It has no AccessibilityService, screen reading, screenshots, gestures, SMS/calls, contacts/location, overlays, or unattended phone control. |
| sideload | `hermes-relay-1.2.0-sideload-release.apk` | Direct-install APK for full Device Control. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| googlePlay APK | `hermes-relay-1.2.0-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-1.2.0-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Make it yours

- **App themes.** A theme picker in Settings → Appearance ships eight looks — the signature Hermes Relay brand (full light/dark) plus ports of the Nous Hermes baselines: Hermes Teal, Nous Blue, Midnight, Ember, Mono, Cyberpunk, and Rosé. The whole app follows your choice; Light/Dark/Auto applies to themes that ship both modes.
- **Agent pets — a living avatar.** Replace the orb with an animated pet that reacts to the agent: idle / thinking / writing / speaking / listening, a distinct **working** pose during tool calls, one-shot **greet** and **celebrate** reactions, and a loop that speeds up as output streams. Add or remove pets right in Appearance (no `adb`), preview each state, tune playback speed, and toggle frame auto-stabilization. Pets are pure data — an AI authoring kit and JSON schema let you generate one from sprite art.
- **Hot-swappable sphere skins + per-profile icons.** Keep the orb but reskin it (Adaptive, Classic, Aurora, Solar, Mono, or your own JSON skin), and give each agent profile its own small icon beside its name — all client-side, never sent to Hermes.

### See what's actually happening

- **Transport path is visible.** The chat status strip now shows which streaming path is in use — ⚡ Gateway (live thinking), 📡 Sessions, Completions, or Runs — and Chat Settings adds a basic→best tier ladder explaining the active path and its fallback.
- **"What the agent sees" sheet.** Tap the context meter to see the exact extra context prepended to your next turn — persona/profile, phone status, any per-turn voice hint, and (when paired) the relay's own server-side context. The audit is honest about what the phone sends versus what's applied on the server.
- **Spoken-turn badges + voice render-path visibility.** Voice and Realtime Agent replies carry a chip in the scrollback, and Voice Settings shows whether speech is rendering over the streaming or basic path.

### Privacy

- **Sensitive-media blur.** When paired to the relay, the agent can mark private/NSFW media and the phone blurs it per your setting — sensitivity stays model-emitted (no on-device or relay-side classifier), and the exact instruction is visible in the "What the agent sees" sheet. Vanilla Hermes (no plugin) is unaffected.

### Faster, calmer, more honest

- **~3× faster cold start.** The app was building several hardware-keystore-encrypted stores at launch, serializing on a process-global lock and stalling the chat header for seconds. It now builds a single keyset shared with the dashboard cookies, cutting measured time-to-connected from ~2.9 s to ~1 s. Existing sign-ins migrate automatically.
- **Honest loading, never stale.** Model, personality, and approvals show a brief "checking…" state and fade in once the server confirms them; standard controls (Model, YOLO, Fast, reasoning effort) always appear — live when ready, "checking…" while loading, or cleanly disabled with the reason — instead of being hidden or showing a maybe-wrong value.

### More reliable

- **In-app crash reporting.** A force-close now surfaces a clean dialog on next launch with the stack trace — Copy it, or **Report** to open a pre-filled GitHub issue from the bug template. The handler re-raises so Play vitals still record the crash.
- **QR pairing hardened for foldables.** On devices where the camera can't initialize, the scanner shows a "camera unavailable — pair manually" card instead of force-closing.
- **Crash fixes.** No more crash opening a chat with a server-local image, and the PDF viewer no longer crashes when a document closes mid-render.
- **Chat correctness.** In-chat model picks now actually apply — both on a new chat and mid-conversation — server-side turn errors stay on screen as an error bubble, per-reply token counts and provenance badges survive the post-turn reload, and server steering markers (`[System: …]`) no longer appear as chat bubbles.

### Voice & terminal polish

- **Enhanced voice control (Gemini & xAI).** When the relay uses a Gemini or xAI voice provider, Voice Settings can pick a voice/model and turn on expressive tone/speech tags. Vanilla Hermes voice stays configured server-side.
- **Leaner terminal.** A scrollable, fully-legible key bar, TUI-correct arrows and bracketed paste, a compact single-row header, and relay sessions on an isolated, TUI-tuned tmux so editors and full-screen tools behave.

---

## Upgrade notes

- Cold-start speedup migrates the encrypted credential and dashboard-cookie stores automatically on first launch; in rare cases Manage/voice may ask for a one-time re-login (cookies are re-obtainable).
- App themes, sphere skins, and pets are available on **both** flavors — they're client-side and need no Device Control.
- `appVersionCode` is **14**.
