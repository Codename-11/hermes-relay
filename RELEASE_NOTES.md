# Hermes-Relay v0.3.0

**Release Date:** April 13, 2026
**Since v0.2.0:** 56 commits · 8 major feature merges · 2 new build flavors · 1 new agent-control channel

> **Bridge channel.** The agent can now read your screen, tap, type, swipe, and take screenshots on your phone — gated behind a five-stage safety rails system, a master toggle, the Android Accessibility Service, MediaProjection consent, and per-channel session grants. Plus full voice-mode polish, a notification companion, and two new agent-introspection tools so the agent stops flying blind.

---

## 📥 Download

v0.3.0 ships in **two build flavors**. APK filenames are version-tagged, so every file carries its release number:

| Flavor | File | Who it's for |
|---|---|---|
| **sideload** (recommended) | `hermes-relay-0.3.0-sideload-release.apk` | Full feature set — bridge channel, voice-to-bridge intents, vision-driven `android_navigate`. Installs alongside the Play Store build with a `.sideload` applicationId. |
| **Google Play** | `hermes-relay-0.3.0-googlePlay-release.aab` | Uploaded to Play Console for Internal testing. Conservative feature set (chat, voice, safety rails — no agent device control) to match Play Store's Accessibility policy. |
| googlePlay APK | `hermes-relay-0.3.0-googlePlay-release.apk` | Parity + diff tooling — not the primary download. |
| sideload AAB | `hermes-relay-0.3.0-sideload-release.aab` | Parity + diff tooling — not the primary download. |

**Verify integrity** with `SHA256SUMS.txt` from the same release before installing. See the [Sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for the step-by-step install walkthrough.

> **Why two flavors?** The `googlePlay` build stays inside Play Store's Accessibility Service policy review. The `sideload` build unlocks the full agent-control feature set and installs with a `.sideload` applicationId suffix so both can coexist on the same device — the sideload launcher is labelled **"Hermes Dev"** for disambiguation. For a capability-by-capability breakdown, see the [Release tracks comparison](https://codename-11.github.io/hermes-relay/guide/release-tracks.html).

---

## ✨ Highlights

- **Bridge Channel** — The agent can read the phone's screen, tap, type, swipe, and take screenshots via a new `HermesAccessibilityService` + `MediaProjection` pipeline. Five independent safety gates must all be green before a single command executes: session grant → master toggle → Accessibility permission → MediaProjection consent → safety rails.

- **Voice Mode polish** — Full-screen voice UI with an ASCII morphing sphere (Listening = blue/purple, Speaking = green/teal), reactive layered-sine waveform visualizer, pill-edge merge, tap/hold/continuous interaction modes, and sentence-boundary streaming through a TTS queue. Backed by three new relay endpoints — `POST /voice/transcribe`, `POST /voice/synthesize`, `GET /voice/config` — with 6 TTS and 5 STT providers available via `~/.hermes/config.yaml`.

- **Voice-to-bridge intents** *(sideload only)* — Spoken commands like *"text Mom saying on my way"* route to the bridge channel with destructive-verb confirmation instead of falling through to chat. Regex-based intent classifier covers send-sms / open-app / tap / back / home (scroll removed in v0.4.0 — server-side android_scroll tool handles it instead).

- **Notification Companion** — `HermesNotificationCompanion` (`NotificationListenerService`) forwards posted notifications to the relay over a new `notifications` channel so the agent can summarize them or act on them. Opt-in via the standard Android notification-access grant.

- **Agent introspection tools** — Two new Hermes tools close the "agent has no idea what permissions are granted" loop: `android_phone_status()` returns the full structured phone state (battery, screen, current app, bridge permission flags, safety state) via a new loopback-only `/bridge/status` relay endpoint, and `hermes-status` is a matching CLI shim for operators with three exit codes so shell scripts can tell connected / relay-down / no-phone apart.

- **Per-channel grant revoke** — Paired Devices screen now shows per-channel grant chips with relative TTL labels and an inline x icon. Tap the x → confirm → revoke just that channel. The full-session Revoke button still nukes the whole session.

- **Manual pairing fallback** — For setups without a QR path (phone is the only camera / host is SSH-only): Settings → Connection → **Manual pairing code (fallback)** → copy the 6-char code → run `hermes-pair --register-code ABCD12 [--ttl 30d --grants chat:never,bridge:7d]` on the host → tap **Connect**.

- **Self-install skill for AI agents** — `/hermes-relay-self-setup` is a single-source agent-readable install recipe. Pre-install users paste the raw GitHub URL into any AI; post-install users get it as a slash command. The README has a copy-paste prompt block to hand off setup to Claude / GPT / any agent.

- **Version-tagged release artifacts** — Every APK and AAB in this release is named `hermes-relay-<version>-<flavor>-<buildType>`, so `which-file-is-which` is obvious at a glance and multiple versions don't overwrite each other in your Downloads folder.

---

## 📱 Bridge Channel

The headline feature. Everything in this section is gated behind the five-stage safety system documented above.

### Bridge Tab UI
- **Master toggle card** — "Allow Agent Control" is the load-bearing user-facing gate
- **Bridge status card** — live bridge-connected indicator (reuses `ConnectionStatusBadge`'s pulsing ring)
- **Permission checklist** — Accessibility / Screen Capture / Overlay / Notification Listener rows with in-app **Test** buttons that fire single-command smoke tests instead of requiring the agent to be online
- **Activity log** — tap-to-expand entries with timestamps, status, result text, and optional screenshot tokens (capped at 100 entries)
- **Safety summary card** — live countdown to auto-disable, blocklist/verb counts at a glance

### Safety Rails
- **App blocklist** — 30 default banking / payments / password-manager / 2FA apps pre-seeded; searchable `PackageManager.queryIntentActivities(CATEGORY_LAUNCHER)` picker for custom entries
- **Destructive-verb confirmation modal** — word-boundary regex match against `/tap_text` + `/type` payloads. Default verbs: `send`, `pay`, `delete`, `transfer`, `confirm`, `submit`, `post`, `publish`, `buy`, `purchase`, `charge`, `withdraw`. Modal rendered via a `WindowManager` overlay so it's visible even when Hermes isn't in the foreground.
- **Idle auto-disable** — 5-120 min slider. Any command resets the timer; process death clears state so a stale grant can't survive a crash.
- **Optional persistent status overlay** — small floating "Hermes active" pill via `SYSTEM_ALERT_WINDOW`, gated behind the overlay-permission walk-through
- **Confirmation timeout** — 10-60s slider; fails-closed if missing overlay permission

### AccessibilityService Pipeline
- `HermesAccessibilityService` — `@Volatile` singleton so `BridgeCommandHandler` reaches the live service without DI
- `ScreenReader` — UI tree → `ScreenContent(rootBounds, nodes[], truncated)` with node recycling and `MAX_NODES=512` cap
- `ActionExecutor` — `tap`/`tapText`/`swipe`/`scroll` via `GestureDescription` wrapped in `suspendCancellableCoroutine` so suspend form actually waits for completion; `typeText` via `ACTION_SET_TEXT`; `pressKey` mapped to a curated string vocab (no raw KeyEvent codes)
- `BridgeForegroundService` — persistent "Hermes has device control" notification with **Disable** + **Settings** action buttons; declared as `foregroundServiceType=specialUse|mediaProjection` per Android 14+ requirements

### Relay Bridge Server
- 18 HTTP routes registered on `plugin/relay/server.py` — `/ping`, `/screen`, `/screenshot`, `/get_apps`, `/current_app`, `/tap`, `/tap_text`, `/type`, `/swipe`, `/open_app`, `/press_key`, `/scroll`, `/wait`, `/setup`, `/send_sms`, `/call`, `/search_contacts`, `/return_to_hermes` (last 4 added in v0.4.0)
- Wire protocol migrated from the legacy standalone `plugin/tools/android_relay.py` (port 8766) into the unified relay on port 8767. Envelope fields match the legacy relay byte-for-byte.
- 30s per-command timeout, fail-fast on phone disconnect so HTTP callers don't wedge

---

## 🎙️ Voice Mode

- **Three interaction modes** — Tap-to-talk / Hold-to-talk / Continuous, configurable in Voice Settings
- **Reactive layered-sine waveform visualizer** — three overlapping waves at co-prime frequencies (1.2 / 2.1 / 3.4) with amplitude-driven phase velocity, pill-edge merge via `BlendMode.DstIn` + geometric `sin(π·t)` taper
- **Sphere voice states** — `SphereState.Listening` (soft blue/purple, subtle amplitude wobble) and `SphereState.Speaking` (vivid green/teal, dramatic core-warmth pulse, data ring spin up to 4× on peak)
- **In-flow STT display** — "YOU"-labelled transcribed text lives between the waveform and the response area so the eye flow is one linear motion (mic → up → STT → response), not split top↔bottom
- **Auto-scroll response** — text area follows new tokens via `LaunchedEffect(responseText.length) { scrollState.animateScrollTo(maxValue) }` with a fade-to-transparent gradient mask at top and bottom edges (`graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }` + `drawWithContent + BlendMode.DstIn`)
- **Stop preserves history** — tapping Stop while the agent is speaking freezes the current response text on screen instead of clearing it; voice mode stays open
- **Voice SFX chimes** — pre-synthesized 200 ms PCM sweeps (ascending 440→660 Hz enter, descending mirror exit) via `AudioTrack.MODE_STATIC` with `USAGE_ASSISTANT`
- **Sentence-boundary TTS queue** — top-level `extractNextSentence(StringBuilder)` helper with whitespace-lookahead for abbreviations (`e.g.`, `i.e.`, `Dr.`, etc.), dedicated consumer coroutine that only triggers auto-resume when the queue is actually drained (waveform stays alive through multi-sentence playback)
- **Attack-fast/release-slow envelope follower** — 0.75 / 0.10 at 60 Hz replaces the old Compose spring so the sphere and waveform respond instantly to speech onsets
- **Voice test toasts** — three-toast lifecycle (Testing / Success / Failed: reason) with explicit `cancel()` between trigger and result so toasts don't stack

---

## 🔔 Notifications & Agent Introspection

- **`HermesNotificationCompanion`** — `NotificationListenerService` subclass with the same opt-in flow as Wear OS / Android Auto / Tasker
- **Cold-start buffer** — `pendingEnvelopes` queue capped at 50 entries preserves ordering when notifications fire before the multiplexer is wired
- **In-memory bounded deque** on the relay side — `NotificationsChannel` holds the most recent 100 entries, wiped on relay restart by design (matches smartwatch-companion semantics)
- **`android_notifications_recent(limit=20)`** — Hermes tool registered by `plugin/tools/android_notifications.py`, calls the loopback `GET /notifications/recent` endpoint
- **Notification Companion settings screen** — status indicator, test notification dump (pulls `service.activeNotifications` directly for end-to-end verification without a relay round-trip), open-Android-settings button
- **`android_phone_status()`** — new agent tool returning the full phone state via loopback `/bridge/status`
- **`hermes-status`** — CLI shim with three exit codes for shell-scriptable bridge state queries

---

## 🔐 Security & Pairing

- **Android 14+ MediaProjection** — `foregroundServiceType=mediaProjection` added to `BridgeForegroundService` so the screen-capture grant survives backgrounding and Android 14+'s auto-revocation window (symptom prior to fix: consent dialog appears, user allows, dialog closes, grant evaporates within a frame)
- **Master toggle gate fix** — `cachedMasterEnabled` was never written in v0.2.0, so the gate was no-op; service now owns the observer directly
- **MediaProjection consent flow** — `MainActivity` hosts the `ActivityResultLauncher` + a process-singleton `MediaProjectionHolder` rendezvous for non-Activity callers
- **Self-healing EncryptedSharedPreferences** — `KeystoreTokenStore` and `LegacyEncryptedPrefsTokenStore` catch `AEADBadTagException` on master-key rotation (happens automatically on every Android Studio reinstall) and rebuild the prefs file instead of leaving the user permanently unable to decrypt their session token
- **Strict-mode sandbox opt-in** — `RELAY_MEDIA_STRICT_SANDBOX=1` re-enables the allowlist enforcement on `/media/by-path`; permissive-by-default since 2026-04-11 (path-token route still always enforces sandbox)
- **Per-channel grant revoke API** — `PATCH /sessions/{token_prefix}` restarts the clock from now; `DELETE /sessions/{token_prefix}` matches on first-4+ chars, 200 exact / 404 zero / 409 ambiguous, self-revoke flagged via `revoked_self: true`

---

## 🛠️ Installer & Developer Workflow

- **`install.sh` TUI pass** — ANSI colors, boxed banner, unicode step bullets, spinner for the long pip install step, structured closing message
- **`install.sh` restart actually restarts** — fixed the subtle bug where `enable --now` on an already-active systemd service was a no-op (the source of the 2026-04-12 "install ran clean but relay is still on stale code" debug session)
- **Optional `hermes-gateway` restart prompt** — gates on TTY, respects `HERMES_RELAY_RESTART_GATEWAY=1` for non-interactive runs so it's automation-safe
- **`hermes-relay-update` shim** — two-line wrapper around the canonical curl pipe, re-fetches the latest `install.sh` on every invocation so improvements to the installer itself take effect immediately
- **Three version sources in lockstep** — `scripts/bump-version.sh` atomically bumps `gradle/libs.versions.toml`, `pyproject.toml`, and `plugin/relay/__init__.py::__version__` with SemVer validation and monotonic `appVersionCode` enforcement
- **Branch protection on `main`** — direct push blocked except for the `release: vX.Y.Z` pattern, PR must pass CI before merge, force push + branch deletion blocked
- **Feature branches + `--no-ff` merges** — direct-to-main reserved for single-file typos; agent-team branches get per-commit traces in `git log --graph`
- **`base { archivesName }` in `app/build.gradle.kts`** — injects the app version into every APK/AAB filename so release artifacts are self-identifying at every stage of the pipeline

---

## 🐛 Notable Bug Fixes

- **Fix: Android 14 MediaProjection grant evaporation** — missing `foregroundServiceType=mediaProjection` declaration
- **Fix: master toggle gate broken end-to-end** — `cachedMasterEnabled` never written
- **Fix: re-pair required after every Android Studio rebuild** — self-heal corrupted `EncryptedSharedPreferences` on master-key rotation
- **Fix: voice response text cleared on Stop** — `interruptSpeaking()` was wiping `responseText`; now freezes on-screen
- **Fix: auto-scroll didn't follow streaming tokens** — added `LaunchedEffect(length)` driver
- **Fix: STT bubble split user gaze top↔bottom** — moved to in-flow position between waveform and response
- **Fix: sphere too small in voice mode** — Compose `weight()` divides *remaining* space; bumped sphere weight to 1.5f vs response 1f for ~60% share
- **Fix: `install.sh` restart was a no-op on already-active services** — explicit `systemctl --user restart` detection
- **Fix: flavored APK upload paths in CI** — `apk/*/debug/*.apk` glob matches both `googlePlay` and `sideload` flavor directories
- **Fix: `BIND_ACCESSIBILITY_SERVICE` as `uses-permission`** — lint `ProtectedPermissions` violation; already declared correctly on the `<service>` tag
- **Fix: `AutoDisableWorker.notify()` lint `MissingPermission`** — suppression with documented helper gate
- **Fix: stray pairing rate-limit blocks surviving relay restart** — `/pairing/register` now clears all blocks on success
- **Fix: `MissingFeature` newline not treated as sentence boundary in voice TTS chunker**
- **Fix: `ChevronRight` icon missing** — reverted to `AutoMirrored.Filled.ChevronRight`

---

## 📚 Documentation & Skills

- **Installer README + DEVLOG update** — canonical update cycle documented top-to-bottom
- **`docs/spec.md` + `docs/decisions.md`** — bridge pipeline, safety rails architecture, two-flavor rationale
- **`/hermes-relay-self-setup` skill** — single-source agent-readable install recipe (dual-mode: pre-install via raw URL, post-install via slash command)
- **`/hermes-relay-pair` skill** — canonical category layout (`devops`), matches `metadata.hermes.category` frontmatter
- **`user-docs` flavor comparison** — "Which build should I pick?" decision guide on the Release Tracks page
- **Android Studio dev loop** — Bailey's testing convention documented in CLAUDE.md so future sessions don't try to `adb install` from the tool side

---

## 👥 Contributors

Primary development by **@Codename-11** (Bailey Dixon). Implementation assisted by Claude Code on isolated feature branches with `--no-ff` merges to preserve the per-component history.

Dependency bumps via Dependabot: markdown-renderer, gradle-wrapper, haze, camera, kotlinx-coroutines-test.

---

**Full Changelog**: [v0.2.0...v0.3.0](https://github.com/Codename-11/hermes-relay/compare/v0.2.0...v0.3.0)
**See also**: [RELEASE.md](https://github.com/Codename-11/hermes-relay/blob/main/RELEASE.md) for the release recipe, [CHANGELOG.md](https://github.com/Codename-11/hermes-relay/blob/main/CHANGELOG.md) for cumulative history.
