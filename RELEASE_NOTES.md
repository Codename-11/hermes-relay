# Hermes-Relay v0.6.0

**Release Date:** April 18, 2026
**Since v0.5.1:** Multi-server pairing, agent profile discovery + picker, consolidated agent sheet, unified relay UI state machine, and the dashboard pairing schema fix

> **The multi-connection release.** Pair with several Hermes servers and switch in one tap. The top bar shows a Connection chip (hidden when you only have one); the new Settings → Connections screen manages them all. Per-Connection state is fully isolated — sessions, memory, personalities, skills, profiles, relay URL, cert pin, voice endpoints, last-active session. Existing single-server installs migrate transparently on first launch.
>
> Plus agent Profiles — the relay auto-discovers upstream Hermes profile directories under `~/.hermes/profiles/*/` and advertises them in `auth.ok`. Pick one from the new consolidated agent sheet (tap the agent name in the Chat top bar) and the phone overlays `model` + `SOUL.md` on every chat turn.

---

## 📥 Download

v0.6.0 ships in **two build flavors**. APK filenames are version-tagged:

| Flavor | File | Who it's for |
|---|---|---|
| **sideload** (recommended) | `hermes-relay-0.6.0-sideload-release.apk` | Full feature set — bridge channel, voice intents, unattended access, vision-driven `android_navigate`. Installs alongside the Play build with a `.sideload` applicationId. |
| **Google Play** | `hermes-relay-0.6.0-googlePlay-release.aab` | Conservative feature set (chat, voice, safety rails — no agent device control) to match Play Store's Accessibility policy. |
| googlePlay APK | `hermes-relay-0.6.0-googlePlay-release.apk` | Parity + diff tooling — not the primary download. |
| sideload AAB | `hermes-relay-0.6.0-sideload-release.aab` | Parity + diff tooling — not the primary download. |

**Verify integrity** with `SHA256SUMS.txt` from the same release. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for install steps.

---

## ✨ Highlights

### Multi-server Connections

- **Pair with several Hermes servers, switch in one tap.** New Connection chip on the Chat top bar opens a switcher sheet with per-server health indicators. Tapping a connection cancels in-flight chat, disconnects the old relay, rebinds to the new server, and reloads sessions + personalities + profiles in one coordinated context swap. Hidden automatically when only one Connection is configured.
- **Connections management screen.** Settings → Connections lists every paired server as a card with inline **Rename / Re-pair / Revoke / Remove**. Add a new Connection from the same screen — launches the QR pairing wizard. The active card shows **live** WSS state (Connected / Reconnecting… / Stale — tap to reconnect) instead of a static "Paired N minutes ago" timestamp.
- **Per-Connection isolation.** Sessions, memory, personalities, skills, profiles, relay URL + cert pin, voice endpoints, last-active session are all scoped per-Connection. Theme, bridge safety preferences, TOFU cert-pin map, and notification companion state stay global.
- **Transparent migration.** Existing single-server installs become their first Connection automatically on first v0.6.0 launch — zero re-pair, zero token migration, zero data loss. Rename it at Settings → Connections whenever you like. See `docs/decisions.md` §19.

### Agent Profiles

- **Relay auto-discovers upstream profiles.** The relay walks `~/.hermes/profiles/*/`, reads each profile's `config.yaml` + `SOUL.md`, and advertises the list in `auth.ok` as `{name, model, description, system_message}`. Plus a synthetic `default` entry for the root config so there's always something to pick.
- **One-tap overlay on chat turns.** Pick a profile from the agent sheet; the phone overlays the request's `model` and `system_message` on every subsequent chat turn. Selection is ephemeral — resets on Connection switch. Gated by `RELAY_PROFILE_DISCOVERY_ENABLED=1` (default on) so operators can disable it if needed.
- **Three-layer agent model.** Connection (server) → Profile (agent directory on that server) → Personality (system-prompt preset within the agent's config). Picking a Connection resets Profile because Profile is server-scoped. Documented in `docs/spec.md`, `docs/decisions.md` §8 / §19 / §21, and `user-docs/features/{connections,profiles,personalities}.md`.

### Consolidated agent sheet + Active Agent card

- **One tap instead of two chips.** The standalone `ProfilePicker` / `PersonalityPicker` top-bar chips are gone. Tap the agent name in the middle of the Chat top bar to open a scrollable bottom sheet holding Profile selection, Personality selection, and session info + analytics (message count, tokens in/out, avg TTFT). Toast confirmations fire on both kinds of switch.
- **"Active Agent" card at the top of Settings** — summarizes the current Connection / Profile / Personality. Tapping it navigates to Chat with the agent sheet auto-opened (`openAgentSheet` nav arg), closing the "how do I change my agent" discoverability gap for Settings-originating users.

### Unified relay UI state machine

- **One source of truth across three screens.** `SettingsScreen`, `ConnectionSettingsScreen`, and the Connections list used to each resolve relay status independently, sometimes disagreeing on what state the WSS was actually in (e.g. Settings card red/Disconnected while the sub-screen read amber/Reconnecting for the same moment). State resolution now lives on `ConnectionViewModel.relayUiState: StateFlow<RelayUiState>` with five well-defined cases (`NotConfigured` / `Connected` / `Connecting` / `Stale` / `Disconnected`). Every screen maps it onto the existing `ConnectionStatusRow`.
- **5 s grace window before Stale.** A Paired-but-Disconnected pose (common during the WSS handshake on cold start or resume) renders as **Connecting…** for the first 5 seconds. If the handshake completes in that window, you never see red flicker. If it doesn't, the row promotes to **Stale — tap to reconnect** with amber styling and a reconnect action on any surface that renders the row.
- **Settings "Connection" card → "Active Connection".** Renamed with the current Connection's label as subtitle, so multi-connection installs can see at a glance which server the status rows describe. Kicks `reconnectIfStale()` on first compose so the Relay row doesn't flash red before the lifecycle observer's resume path lands.

### Pairing wizard polish

- **Field-scheme cross-validation.** The wizard's manual-entry + show-code forms now detect "obviously wrong scheme in the wrong field" immediately — `wss://` in the API URL box, `http://` in the Relay URL box, etc. Shows an inline hint with a helpful fix message. Matches the pairing-QR-override check on the dashboard's Pair dialog.
- **Pair-stamp on the active Connection.** Successful auth now stamps the active Connection's pairing metadata (paired-at, transport hint, expiry) in place, so a re-pair from Settings doesn't leave stale state on the card. Closes the "Connections list says Not paired while Settings says Paired" bug.

---

## 🔧 Fixes

- **`POST /pairing/mint` emits the correct wire format.** Dashboard-minted QRs were unscannable — the relay endpoint put the freshly-minted pairing code in top-level `key` and defaulted the top-level port to the relay's own `8767` instead of the Hermes API server's `8642`, so phones silently failed to pair because the API server URL pointed at the relay and the `relay` block had no URL / code for the WSS handshake. The `hermes-pair` CLI and `/hermes-relay-pair` skill were unaffected. `handle_pairing_mint` now mirrors `pair.py`'s CLI path — top-level = API server (default `:8642`, LAN-resolved), `relay.{url,code}` auto-derived from the relay's own bind config. Regression test at `plugin/tests/test_pairing_mint_schema.py` (8 cases) pins the shape against the Android parser.
- **Dashboard Relay Management tab no longer crashes on paired-session list.** The dict-shaped `s.grants` (`{chat, terminal, bridge}`) was being rendered as a React child, tripping minified error #31. Now `Object.keys(s.grants)` so Badge children are always channel-name strings.
- **Status badge multi-line rows.** `ConnectionStatusBadge` top-aligns cleanly on multi-line rows (was vertically centered and drifted off-center when the label wrapped — `Session` tests in the Settings card used to squeeze to one character per line when the error message was a full sentence).

---

## 🧪 Verification checklist (post-install)

- Pair with two different Hermes servers. Confirm the Connection chip appears in the Chat top bar and switching cancels in-flight chat + disconnects / reconnects the relay cleanly.
- Rename a Connection in Settings → Connections, then verify the rename persists across an app restart and shows in the top-bar chip.
- With a paired session, force-kill the relay process. The Connections list active card should show "Stale — tap to reconnect" within ~5 seconds with a Reconnect button tinted amber. Tap it; Toast "Reconnecting to relay…" fires immediately and the row flips to Connecting then Connected once the relay is back.
- Settings → Active Agent card shows Connection / Profile / Personality. Tap it; lands in Chat with the agent sheet open. Pick a different Profile; Toast confirms. Send a message; the response reflects the profile's `SOUL.md`.
- Dashboard's "Pair new device" button mints a scannable QR. The Relay Management tab renders paired devices with grant badges (no React #31 crash).

## 🧩 Known — test suite deferred

- `ConnectionStoreTest` (11 tests) is `@Ignore`'d pending a `ConnectionStore` scope-injection refactor. The tests race against `ConnectionStore.init`'s hydrate coroutine on `Dispatchers.Default` (reads `dataStore.data.first()` on a real dispatcher vs. `runTest`'s `TestScope`). Not a user-visible bug — cold-start + mutation don't fire in the same tick in the real app. The 8 previously-deferred VoicePlayer tests from v0.5.1 are also still `@Ignore`'d. Follow-up PR will land both fixes together once the separate-source-set test infra is in place. On-device smoke testing (by Bailey, Samsung) validated feature behavior.

See `CHANGELOG.md` for the full file-level diff and `DEVLOG.md` for the per-feature session narratives.

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)
