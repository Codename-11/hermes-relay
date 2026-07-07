# Hermes-Relay-Android v1.3.0

**Release Date:** July 6, 2026
**Since v1.2.6:** Realtime voice grows up — long tasks hand off to the background with a live progress chip while you keep talking, results survive disconnects (and arrive as a notification if you've left), and leaving voice mode no longer cancels a running task. Chats stop losing answers when the connection drops mid-reply, your agent can message you first (opt-in) with replies straight from the notification, and a stack of polish landed: app font picker, proportionate markdown, scrollable onboarding, smarter diagnostics reporting, and a cleaner Connections screen.

v1.3.0 is recommended for everyone. Realtime-voice background tasks pair best with relay plugin v1.3.0 on the server; the no-plugin (vanilla Hermes) path is unaffected.

---

## Download

**Installing on your phone?** Download **`hermes-relay-1.3.0-sideload-release.apk`** and tap it — that's the direct-install build with the full feature set (installs as `com.axiomlabs.hermesrelay.sideload`). Prefer the conservative build (no Device Control surface)? Get it from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The other file, `hermes-relay-1.3.0-googlePlay-release.aab`, is an Android App Bundle for uploading to Play Console — it **cannot** be installed by tapping it on a phone.

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Voice, hands-free
- **Background tasks with a live chip.** Ask for something big and keep talking — the task hands off to the background with a chip showing the current step, steps done, and a running timer, plus a ✕ to cancel. The answer is spoken when it's ready, even after a brief disconnect; if the voice session is gone for good, it arrives as a notification (the full answer is always in the chat).
- **Exit detaches, ✕ cancels.** Leaving voice mode or tapping stop no longer kills a running task or overwrites its delivered answer with "Cancelled." — the chip's ✕ is the one deliberate kill switch.
- **Quieter and quicker.** Milestone speech instead of step-by-step narration, immediate handoff for clearly long tools, and a faster first turn (the session warms up when you open voice mode).

### Chats
- **Answers survive dropped connections.** On long turns (slow local models, delegating skills) the app now recovers the finished answer from the server instead of hanging on "Still working…". (#166)
- **Proactive messages, two-way.** Your agent can message your phone first (off by default, opt-in on server and phone) and you can reply from the notification or the Hermes inbox.
- **Markdown that reads like chat.** Proportionate headings, unified text sizes, styled links, per-group timestamps.

### Polish
- **Pick your font** (Inter, Nunito, or system) and an animated thinking indicator; Quick Controls at the top of Settings.
- **Onboarding fits every screen** — slides scroll on short viewports and large font sizes. (#145)
- **Smarter diagnostics reporting** — informational entries file as questions with your actual connection mode, not as empty bug reports.
- **Connections redesign** — scannable list + tabbed detail (Overview / Routes / Advanced / Security); server voice-engine settings editable from the app.

---

## Upgrade notes
- App-side release on **both** flavors. Realtime-voice background-task features need relay plugin **v1.3.0** on the server; everything else works on unmodified upstream Hermes.
- `appVersionCode` is **21**.
- Releases now attach **two** files (sideload APK + Play bundle) instead of four — the parity/testing artifacts are gone from the release page. (#144)
