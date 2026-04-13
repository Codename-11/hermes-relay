# Hermes-Relay v0.3.0

**Phase 3 — Bridge channel.** The agent can now read the phone's screen, tap, type, swipe, and take screenshots — gated behind a deliberate in-app master toggle, per-channel session grants, Android Accessibility Service permission, MediaProjection consent, and a five-stage safety rails system (blocklist / destructive-verb confirmation modal / idle auto-disable / persistent status overlay / master gate). Plus the agent gets two new introspection tools so it stops making blind bridge calls, a host-side manual pair fallback for camera-less setups, per-channel grant revoke on the Paired Devices screen, and a whole workflow cleanup for solo + agent-team development.

## Download

- **Most people**: grab **`app-release.apk`** below and sideload it. See the [sideload guide](https://codename-11.github.io/hermes-relay/guide/getting-started.html#sideload-apk) for step-by-step instructions.
- **Google Play users**: rolling to Internal testing after this release.
- **`app-release.aab`** is the Google Play format — *not* installable directly.
- **Verify integrity** with `SHA256SUMS.txt` before installing.

## Two build flavors

v0.3.0 ships two Android variants on different policy tracks:

| Flavor | Label | Track | Tiers |
|---|---|---|---|
| **googlePlay** | `Hermes-Relay` | Play Store (Internal → Production) | 1 (chat), 2 (voice), 5 (safety rails) |
| **sideload** | `Hermes Dev` | `.sideload` applicationId suffix, installs alongside googlePlay | All tiers including voice-to-bridge intents (Tier 3) and vision-driven `android_navigate` (Tier 4) |

The `sideload` flavor installs with a different applicationId so both can coexist on one device. Sideload is the "power user" track — everything's unlocked. The Google Play flavor is conservative by design to match Play Store's Accessibility policy review.

## Highlights

### Phase 3 Bridge Channel (the big one)

Agent controls the phone — read the screen, tap, type, swipe, take screenshots — through a brand new `HermesAccessibilityService` + `MediaProjection` pipeline. The entire channel is behind multiple independent gates that all have to be green before a single command executes:

1. **Session token must have a `bridge` grant** (set at pair time via the Session TTL picker)
2. **In-app master toggle "Allow Agent Control"** — the load-bearing user-facing gate on the Bridge tab
3. **Android Accessibility Service permission** (granted in Android Settings)
4. **Android MediaProjection consent** (per-session system dialog — Android 14+ compliant via `foregroundServiceType=mediaProjection`)
5. **Tier 5 safety rails** — blocklist (30 default banking/payments/2FA apps), destructive-verb confirmation modal (`send`, `pay`, `delete`, `transfer`, etc.), idle auto-disable timer (5–120 min), optional persistent status overlay, confirmation timeout (10–60s)

**Bridge tab UI**: master toggle card, bridge status card with live bridge-connected indicator, permission checklist with in-app **Test** buttons on every row, activity log with tap-to-expand entries, safety summary card with live countdown.

**Bridge Safety settings screen**: searchable package picker for the blocklist, destructive verb editor with chip list, sliders for the auto-disable timer and confirmation timeout, status overlay toggle with the overlay-permission walk-through.

**Persistent foreground service** with the "Hermes has device control" notification, Disable + Settings actions (the Settings action deep-links straight to Bridge Safety settings), declared as `specialUse | mediaProjection` so the screen capture grant survives backgrounding and Android 14+ auto-revocation.

### Voice Mode (full polish)

Real-time voice conversation via relay TTS/STT. Tap the mic in the chat bar → ASCII morphing sphere expands → listens → transcribes → streams response sentence-by-sentence through the TTS queue.

- Three interaction modes (Tap-to-talk / Hold-to-talk / Continuous)
- Reactive layered-sine waveform visualizer with pill-edge merge
- Sphere voice states (Listening = blue/purple, Speaking = green/teal)
- New relay endpoints — `POST /voice/transcribe`, `POST /voice/synthesize`, `GET /voice/config`
- 6 TTS + 5 STT providers via `~/.hermes/config.yaml`
- Voice settings screen — interaction mode, silence threshold, provider info, Test Voice

**New in 0.3.0**: **Voice-to-bridge intent routing** (sideload track only, Tier 3) — spoken commands like *"text Mom saying on my way"* route to the bridge channel with destructive-verb confirmation instead of going through the chat channel.

### Notification Companion

`HermesNotificationCompanion` (`NotificationListenerService`) reads posted notifications and forwards them to the relay over a new `notifications` channel so the agent can summarize them or act on them. Opt-in via the standard Android notification-access grant.

### Agent introspection tools

Two new Hermes tools close the "agent has no idea which permissions are granted" loop:

- **`android_phone_status()`** — agent-callable tool that returns the full structured phone state (device model, battery, screen, current app, bridge permission flags, safety state) via a new loopback-only `/bridge/status` relay endpoint. The agent can now ask "what can I actually do on this phone right now?" instead of making blind bridge calls.
- **`hermes-status`** — matching CLI shim for operators. Three exit codes so shell scripts can tell connected / relay-down / no-phone apart.

### Self-install skill for AI agents

**`/hermes-relay-self-setup`** — a canonical agent-readable setup recipe with dual-mode delivery from a single file. Pre-install users have an AI fetch the raw GitHub URL; post-install users get it as a slash command. The README has a copy-paste prompt block users can drop into Claude / GPT / any agent to hand off the setup.

### Manual pairing fallback

For when you can't scan a QR — phone is your only camera, host is SSH-only, you're pairing from the same device that's running the host terminal:

1. Open the app → Settings → Connection → **Manual pairing code (fallback)** → copy the 6-char code
2. On the host, run `hermes-pair --register-code ABCD12` (composes with `--ttl` and `--grants`)
3. Come back to the card in the app → tap **Connect**

Numbered 3-step walkthrough with a tappable monospace shell-command surface and a real Connect button.

### Per-channel grant revoke

Paired Devices screen now shows per-channel grant chips with relative TTL labels and an inline x icon. Tap the x → confirm → revoke just that one channel. The full-session Revoke button is still there for nuking the whole session.

### Installer polish

- **TUI pass on `install.sh`** — ANSI colors, boxed banner, unicode step bullets, spinner for the long pip install step, structured closing message
- **Three shell shims** — `hermes-pair`, `hermes-status`, `hermes-relay-update` (new — discoverable name for "re-run the installer")
- **`install.sh` restart actually restarts** — fixed a subtle bug where `enable --now` on an already-active systemd service was a no-op
- **Optional `hermes-gateway` restart prompt** — gates on TTY, respects `HERMES_RELAY_RESTART_GATEWAY=1` for non-interactive runs

### Connection pairing flow

- **New `ConnectionWizard`** — shared three-step wizard used by both first-run onboarding and re-pair from Settings. Eliminates the "half-paired" state
- **Lifecycle-aware health checks** — probe fires on foreground resume and network-available, with a new `Probing` tri-state and gray pulsing badge. Kills the foreground-lag flash

## What's fixed (the interesting ones)

- **Android 14 MediaProjection grant evaporation** — fixed via `foregroundServiceType=mediaProjection` declaration + master-toggle-gated consent flow
- **Master toggle gate broken end-to-end** — `cachedMasterEnabled` was never written. Service now owns the observer.
- **MediaProjection consent flow never wired** — `MainActivity` now hosts the `ActivityResultLauncher` + process-singleton rendezvous for non-Activity callers
- **`install.sh` `enable --now` no-op** — fixed with explicit `restart` detection
- **Three version sources drifted** — synced via new `scripts/bump-version.sh`

## Workflow changes

v0.3.0 also introduces the team workflow going forward:

- **Feature branches + `--no-ff` merges** — direct-to-main is reserved for single-file typos
- **Version bumps only happen on `main` at release-prep** — never on feature branches. Use `scripts/bump-version.sh` for atomic bumps
- **Branch protection on `main`** — direct push blocked, PR must pass CI, force push blocked

See [RELEASE.md](https://github.com/Codename-11/hermes-relay/blob/main/RELEASE.md) for the full recipe and [CHANGELOG.md](https://github.com/Codename-11/hermes-relay/blob/main/CHANGELOG.md) for the complete list.
