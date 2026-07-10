# Hermes-Relay-Android v1.4.0

**Release Date:** July 9, 2026

**Since v1.3.0:** Realtime voice can keep a long task moving while you ask a quick follow-up, queue another long request, and deliver the finished answer in the selected realtime voice. Recovery is substantially stronger across backgrounding and route changes, model choices apply to the next session, and stale listening, thinking, reconnecting, and cancellation states no longer strand the voice screen. This release also adds model-catalog refresh, proactive notification rules, multi-device Bridge targeting, session-cleanup plumbing, and broad chat, startup, and security fixes.

v1.4.0 is recommended for everyone. Realtime Agent remains experimental and pairs with relay plugin v1.4.0; the no-plugin Standard chat and Vanilla Hermes voice paths remain upstream-compatible.

---

## Download

**Installing on your phone?** Download **`hermes-relay-1.4.0-sideload-release.apk`** and tap it — that's the direct-install build with the full feature set (installs as `com.axiomlabs.hermesrelay.sideload`). Prefer the conservative build (no Device Control surface)? Get it from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The other file, `hermes-relay-1.4.0-googlePlay-release.aab`, is an Android App Bundle for uploading to Play Console — it **cannot** be installed by tapping it on a phone.

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Realtime voice that finishes the job

- **Keep talking while work runs.** Quick follow-ups can be answered while one Hermes task runs in the background, and another long request can wait in a bounded queue instead of being discarded.
- **Hear the authoritative answer.** Exact xAI delivery uses provider-native forced speech, finished-task answers can be replayed from the task chip, and TTS/text/notification fallbacks keep a result from disappearing when the realtime floor is unavailable.
- **Stronger route recovery.** Recorded turns wait for relay-confirmed resume, unacknowledged audio is replayed without starting a second Hermes run, and long-lived sessions get a fresh bounded retry window when the route actually drops. Retired sockets and sessions cannot overwrite a newer connection.
- **Clean lifecycle state.** Provider transcripts no longer impersonate active microphone capture; Stop settles local placeholders; exit detaches durable work while clearing session-owned UI; rejected, unacknowledged, or terminal cancels cannot leave an undismissable reconnecting task chip.
- **Your model and voice selection sticks.** Realtime Agent model and voice choices are scoped to the active connection/profile, survive restart, and apply when the next session opens.

### Chat and model management

- **Long turns stay alive.** Gateway submits use the server's long-turn window and idle-progress checks, avoiding premature transport fallback and duplicate turns.
- **Phone context reaches Hermes.** Voice-intent traces, card actions, and supported attachments now use payload channels the upstream server actually consumes; unsupported attachment paths report the gap instead of dropping it silently.
- **Refresh model catalogs on demand.** Chat and Manage can explicitly reload dynamic/custom provider models, while Manage keeps unconfigured providers visible with key-setup guidance.
- **Session cleanup groundwork.** The dashboard client supports export, prune preview/apply, archive, restore, and archived-session filtering for the Manage surface.

### Phone automation

- **Notification triggers.** Opt-in rules can match app notifications and show a safe local "Ask Hermes?" prompt, with recent activity and a global pause switch.
- **Multi-device Bridge targeting.** Relay tools can select a paired phone, foldable, tablet, or explicit device ID instead of assuming one Android client.

### Reliability and security

- **Older Android crash safety.** Collection calls that require Android 15 were removed from lower-API paths, and encrypted-storage dependencies are pinned to the compatible line.
- **Bad server addresses fail safely.** Malformed relay, media, session, voice, and chat URLs surface a normal connection error instead of closing the app.
- **Credential paths stay private.** Relay media delivery resolves symlinks and blocks credential, token, pairing, SSH, and system-config locations.
- **Cleaner voice failures.** Duplicate error surfaces are gone, fallback speech animates the voice UI, routine provider idle expiry opens fresh on the next turn, and fresh sessions emit one ready event.

---

## Upgrade notes

- App-side release on **both** flavors. Realtime Agent background/recovery features require relay plugin **v1.4.0**; Standard chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
- `appVersionCode` is **22**.
- Realtime Agent is still an experimental engine. Stable assistant speech remains available through **Hermes Chat + Voice Output**.
