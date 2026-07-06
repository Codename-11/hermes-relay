# Hermes-Relay-Android v1.2.6

**Release Date:** June 27, 2026
**Since v1.2.5:** A fix for chats stuck showing "Untitled", and a calmer way to surface connection status. Chats now keep your first message as a stand-in title until the server names them (and titles reconcile after a turn / via a new refresh button in the session drawer), renaming sticks on non-default agent profiles, and the connection-status card no longer floats over your chat — transient states slide the screen down as a thin top banner, with the floating alert reserved for persistent errors.

v1.2.6 is recommended for everyone.

---

## Download

**Installing on your phone?** Download **`hermes-relay-1.2.6-sideload-release.apk`** and tap it — that's the direct-install build with the full feature set (installs as `com.axiomlabs.hermesrelay.sideload`). Prefer the conservative build (no Device Control surface)? Get it from [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay).

The other file, `hermes-relay-1.2.6-googlePlay-release.aab`, is an Android App Bundle for uploading to Play Console — it **cannot** be installed by tapping it on a phone.

Verify integrity with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for APK install steps.

---

## Highlights

### Fixed
- **Chats stuck showing "Untitled".** The session drawer treated the server's session list as fully authoritative for the title, so a re-list that arrived before (or without) the server auto-naming a chat overwrote the optimistic first-message preview with a blank title. The drawer now keeps a known local title when the server returns a blank one, re-pulls shortly after a turn settles, and offers a manual refresh button — so chats stop reading "Untitled". The api_server SSE path never auto-titles, which is why the preview is now the durable fallback there. (#133)
- **Rename on a non-default agent profile.** A non-default profile's chats live in that profile's own store, but rename went through the shared path — so the new title never landed. Renaming is now profile-scoped (the write twin of the earlier session-delete and list fixes).

### Changed
- **Calmer connection status.** Transient/active/warning connection status — reconnecting, checking, LAN↔Tailscale handoffs — now renders as a thin banner at the top that takes its own space (content slides down) instead of a card floating over the chat. A persistent **error** keeps the floating alert so it still demands attention. Frequent confirmations (copied, profiles updated, profile/personality switches) moved to the same top banner instead of a bottom pop-up.

---

## Upgrade notes
- This is an app-side release on **both** flavors — no Device Control or server changes needed.
- `appVersionCode` is **20**.
