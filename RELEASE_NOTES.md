# Hermes-Relay-Android v1.0.0

**Release Date:** June 13, 2026
**Since v0.8.1:** The 1.0 milestone — a rechromed app, a first-class standard (no-plugin) path, live-thinking gateway chat, and a broad polish pass.

v1.0.0 is the first stable release. The headline is that a **plain, unmodified Hermes agent is now enough**: chat, Manage, and voice all work against vanilla upstream with no relay plugin. The relay plugin is now purely additive (phone control, terminal, notification companion, extra voice engines).

---

## Download

v1.0.0 ships in two Android build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| Google Play | `hermes-relay-1.0.0-googlePlay-release.aab` | Upload this Android App Bundle to Play Console. It has no AccessibilityService, screen reading, screenshots, gestures, SMS/calls, contacts/location, overlays, or unattended phone control. |
| sideload | `hermes-relay-1.0.0-sideload-release.apk` | Direct-install APK for full Device Control. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| googlePlay APK | `hermes-relay-1.0.0-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-1.0.0-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Standard path is first-class — no plugin required

Chat, Manage, and voice now work against an unmodified upstream Hermes agent. Chat streams over the API server; Manage and voice use the Hermes dashboard with a single sign-in. The relay plugin stays optional and only adds power tools.

### Gateway chat transport with live thinking

Chat can now ride the upstream dashboard `/api/ws` gateway (the same surface the official hermes-desktop client speaks). It's the only vanilla-upstream path that streams reasoning **live**, so the Thinking block and sphere light up *during* generation instead of after. "Auto" prefers the gateway when the dashboard is reachable and Manage is signed in, and falls back to the SSE endpoints per turn on any failure.

- **Warm-start + keep-alive.** The app pre-warms the gateway on foreground so the first token lands fast (the cold session-setup cost moves off the send path). An opt-in **Keep connected in background** toggle (both flavors) holds the connection open via a foreground service so a long-backgrounded conversation resumes instantly.
- **Attachments at desktop parity.** Images, PDFs, and any other file upload natively over the gateway (`image.attach_bytes` / `pdf.attach` / `file.attach`). Turns that fall back to an endpoint that can't carry a file now post a visible notice instead of dropping it silently.
- **Steering, edit & resend, subagent lanes.** Send mid-turn to inject guidance into the running turn; edit your own messages to rewind and regenerate; watch per-task subagent lanes stream under the bubble; a context-window meter warns as the window fills.
- **Turn-complete notifications** when the app is backgrounded.

### Manage parity with the desktop dashboard

The Manage tab now does what the desktop dashboard does: change models from the full provider catalog, manage provider keys (write-only, masked, reveal), create/edit profiles and SOUL.md, and browse/install/update skills. Manage data is cached to disk so a cold launch renders instantly.

### Redesigned chat input + seamless connection UX

A cleaner Telegram-style input bar (pill field, one morphing Send/Voice/Stop/Steer/Queue button, no slash button). Network route handoffs (LAN↔Tailscale) and reconnects no longer repaint or reload the chat, and connection/update status now slide down as in-theme toasts over the content instead of pushing the UI around.

### Voice

The provider-native Realtime Agent keeps one session open across turns (follow-ups retain context), and long Hermes runs are promoted to tracked background tasks so the conversation stays responsive and the answer is spoken when it's ready.

### Docs + branding

The documentation site was rechromed to the app's cockpit theme and repositioned around the two-path story (just connect → give it hands), with a reworked Android getting-started funnel and a Google Play badge.

---

## Upgrade notes

- **Google Play submission:** the opt-in keep-alive feature adds a `FOREGROUND_SERVICE_SPECIAL_USE` service. Complete the Play Console **Foreground service permissions** declaration for `specialUse` at submission (see `docs/play-store-listing.md`).
- **PDF attachments** over the gateway require `poppler-utils` (`pdftoppm`) on the Hermes host; without it, PDF attach reports an error and the message still sends as text.
- `appVersionCode` is **12**.
