# Hermes-Relay v0.6.1

**Release Date:** May 6, 2026
**Since v0.6.0:** API-key voice auth, durable pairing/session recovery, route failover hardening, dashboard pairing UI polish, and full Android bridge media/MMS handoff support.

v0.6.1 is a compatibility and bridge-contract release. It keeps the v0.6.0 multi-connection model, then tightens the real-world paths we just exercised: LAN/Tailscale pairing, API-key voice mode, stale session recovery, and Android phone bridge tools.

---

## Download

v0.6.1 ships in two build flavors. APK and AAB filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| sideload | `hermes-relay-0.6.1-sideload-release.apk` | Recommended for full bridge/device-control features, including share/MMS/file handoff. Installs as `com.axiomlabs.hermesrelay.sideload`. |
| Google Play | `hermes-relay-0.6.1-googlePlay-release.aab` | Conservative Play-track build for chat and voice without sideload-only bridge-control surfaces. |
| googlePlay APK | `hermes-relay-0.6.1-googlePlay-release.apk` | Parity/testing artifact. |
| sideload AAB | `hermes-relay-0.6.1-sideload-release.aab` | Parity/testing artifact. |

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for install steps.

---

## Highlights

### Voice auth and pairing durability

- Voice routes now accept either Relay session voice grants or a saved Hermes API bearer token for `/voice/config`, `/voice/transcribe`, and `/voice/synthesize`.
- Chat and voice can work from manual API URL + API key setup without a full QR pairing. Bridge, terminal, Android control, media, clipboard, and pair-session features still use the Relay session path.
- Relay sessions can recover through trusted device refresh after server restarts/updates, reducing forced re-pairing.
- Pairing and endpoint resolution were hardened for LAN/Tailscale transitions, including route normalization and clearer VPN fallback behavior.

### Android bridge media, files, and MMS handoff

- Added `android_share_media` for text, files, relay media tokens, screenshots, and attachment lists through Android's native share sheet.
- Added `android_send_mms` for MMS compose/share handoff with body text and attachments.
- Added the missing `/return_to_hermes` relay route and `android_return_to_hermes` tool so docs, tools, relay HTTP, and the phone command contract match.
- `android_send_sms` remains text-only and now returns structured states such as `sent`, `blocked`, `awaiting_confirmation`, `timeout`, and `failed`.
- The active plugin import now uses `plugin.tools.android_tool` as the single source of truth, preventing Hermes tool exposure drift.

### Dashboard and operator flow

- Dashboard-minted pairing QRs and the QR modal styling were corrected so the plugin owns its dialog layout instead of inheriting host dashboard card styling.
- The relay CLI can toggle insecure LAN API-key voice auth at runtime with `hermes relay insecure-api-key status|on|off`.
- Docs now spell out the bridge HTTP routes, SMS schema, `current_app` limitations, share/MMS handoff behavior, sideload-only restrictions, and direct HTTP fallback route contract.

---

## Verification

- Relay/tool regression slice: `134 passed`
- Android Kotlin compile: `:app:compileSideloadDebugKotlin` passed
- Android Kotlin compile: `:app:compileGooglePlayDebugKotlin` passed
- Dashboard build passed before deployment
- Remote staging deploy restarted `hermes-relay` and `hermes-gateway`, both active

## Post-install smoke

- Install the sideload APK over the existing sideload app with `adb install -r`.
- Existing pairing should survive a normal same-flavor update. Re-pair only if you uninstall app data, switch flavor/applicationId, revoke the device, or the server session store was intentionally cleared.
- Confirm chat works over the selected route, then test voice with the saved Hermes API key.
- For bridge media: try `android_share_media` or `POST /share_media` with a relay media token or host file path. The phone should show an on-device confirmation and then Android's native share UI.
- For MMS: `android_send_mms` opens the composer with the attachment; it does not silently send MMS.
