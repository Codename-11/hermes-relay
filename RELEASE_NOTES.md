# Hermes-Relay v0.7.0

**Release Date:** May 19, 2026
**Since v0.6.1:** profile-aware chat/voice state, relay-owned voice provider settings, realtime voice lab/testbench routes, Android voice overlay polish, and Relay package voice-provider support.

v0.7.0 is a minor release for the profile and voice workstream. The stable Android path is still Hermes chat streaming plus relay-managed voice output, while realtime provider work remains isolated as a lab/testbench and planned experimental mode.

---

## Download

v0.7.0 ships in two Android build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| sideload | `hermes-relay-0.7.0-sideload-release.apk` | Recommended for full bridge/device-control features, voice overlay testing, and profile-aware relay features. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| Google Play | `hermes-relay-0.7.0-googlePlay-release.aab` | Conservative Play-track build for chat, profiles, and voice without sideload-only bridge-control surfaces. |
| googlePlay APK | `hermes-relay-0.7.0-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-0.7.0-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for install steps.

---

## Highlights

### Profile-aware Hermes use

- Profile selection now resolves against the active server and keeps profile-specific chat sessions separate.
- Default/Victor display is normalized so the selected profile name stays visible through streamed and finalized messages.
- Session drawer and voice settings can reflect the active profile instead of treating every connection as one shared default context.
- Profile API URL resolution handles per-profile Hermes API servers and avoids phone-side `localhost` fallbacks when a remote profile is selected.

### Voice settings and output quality

- Relay now owns profile voice configuration endpoints instead of depending on Hermes config edits for realtime voice settings.
- Android can fetch provider/model/voice option metadata, save per-profile voice choices, and fall back to advanced manual entry when a provider cannot expose a complete option list.
- Voice output uses balanced coalescing: assistant speech is grouped into natural chunks, while tool/status speech remains immediate.
- Waveform and playback state are better aligned to real audio output, reducing premature mic return and output-state jitter.
- Barge-in remains explicitly experimental, with known self-capture limitations documented in settings.

### Realtime provider lab and Relay package

- Added standalone voice lab CLI/TUI tooling, provider adapters, waveform/playback support, evaluation helpers, and generated WAV/JSONL artifact ignores for OpenAI, xAI, ElevenLabs, and stub testing.
- Added relay routes for streaming voice output, realtime playground calls, provider options, and profile voice config.
- Added a plan for the next experimental Realtime Hermes Voice Agent mode, where providers handle speech but Hermes remains the authority for profiles, sessions, memory, tools, confirmations, and transcript history.

### Android voice UI polish

- Voice mode includes better tap-to-talk, continuous-mode, overlay, compact-mode, and state-display behavior.
- Continuous mode no longer starts a session solely because the preference is enabled; voice sessions start and stop through explicit controls.
- Voice overlay state is closer to chat state, including live transcript/tool timeline surfaces without forcing an exit and reload.

### Included groundwork

- Desktop tray pairing and consent-flow improvements are included from the dev branch.
- Experimental shared `relay-core`, `relay-ui`, and Quest prototype modules are included for future shared pairing/terminal/voice work. They do not change the Android phone app's default flow.

---

## Verification

- Relay version metadata: `python scripts/check-relay-version-sync.py --expect 0.7.0` passed.
- Android version metadata: `scripts\dev.bat version` reported `Hermes-Relay v0.7.0 (versionCode 9)`.
- Relay route/auth/session/provider slice: 99 pytest tests passed.
- Voice lab provider/tooling slice: 31 pytest tests passed.
- Android sideload and Google Play Kotlin compile passed.
- Focused Android voice/profile unit slice and release-CI unit slice passed.

## Post-install smoke

- Install the sideload APK over the existing sideload app with `adb install -r`.
- Existing pairing should survive a same-flavor update. Re-pair only if you uninstall app data, switch flavor/applicationId, revoke the device, or intentionally clear the server session store.
- Confirm the profile selector shows the expected server default and named profiles, then create/switch a chat while watching that the agent name remains stable.
- In Voice settings, confirm the selected profile's provider/model/voice options load, save a per-profile voice, and run a short voice test.
- In Voice mode, test tap-to-talk first, then continuous mode. Leave barge-in off unless you are explicitly testing the experimental self-capture path.
