# Hermes-Relay-Android v1.4.1

**Release Date:** July 11, 2026

**Since v1.4.0:** Chat now keeps durable work visible and recoverable. Follow background terminal work from the conversation, receive its completion automatically, and reopen the app into the same in-flight answer with its visible progress intact. Voice adds practical spoken controls and mode presets, while streaming chat gets smoother Markdown, table, and image handling.

v1.4.1 is recommended for everyone. Realtime Agent delivery hardening and voice presets pair with relay plugin v1.4.1; Standard chat and Vanilla Hermes voice remain compatible with unmodified upstream Hermes.

---

## Download

**Installing on your phone?** Download hermes-relay-1.4.1-sideload-release.apk and tap it — that's the direct-install build with the full feature set (installs as com.axiomlabs.hermesrelay.sideload). Prefer the conservative build (no Device Control surface)? Get it from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The other file, hermes-relay-1.4.1-googlePlay-release.aab, is an Android App Bundle for uploading to Play Console — it **cannot** be installed by tapping it on a phone.

Verify integrity with SHA256SUMS.txt from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Chat that keeps up

- **See background work where it belongs.** Standard Chat surfaces active and recent background processes in a compact strip and expandable sheet with elapsed time, output, a targeted Stop action, and local Dismiss.
- **Get the completion without asking again.** When Hermes finishes detached work, its follow-up answer appears in the originating conversation automatically. The server's internal completion marker stays in history but is shown as a compact process notice.
- **Come back to the same answer.** Closing and reopening the app restores the partial reply, live reasoning, lifecycle status, tool and subagent states, background-task state, and any pending approval or clarification. The app reattaches when the server still has a live turn, otherwise it reconciles the finished transcript without repeating your prompt.

### Voice you can direct

- **Use natural spoken controls.** Pause or resume listening, stop speech, cancel background work, repeat a settled result, or start a new Standard voice chat.
- **Choose an interaction preset.** Hands-free, Low latency, Careful tools, and Quiet presets adjust existing voice and long-task behavior without changing your voice identity or routing.
- **Keep delivered answers authoritative.** Realtime delivery is generation-safe and uses one relay-TTS fallback if the provider cannot deliver a completed Hermes result.

### Clearer conversations

- **Browse images together.** Adjacent images form a compact gallery that opens at the image you selected.
- **Read while the reply streams.** Markdown settles into its final styling as text arrives, wide tables stay usable, and motion-sensitive indicators respect system accessibility settings.

---

## Upgrade notes

- App version: **1.4.1** (versionCode **23**).
- Realtime Agent improvements pair with relay plugin **1.4.1**.
- Standard Chat and Vanilla Hermes voice continue to work against unmodified upstream Hermes.
