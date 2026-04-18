# Security

## API Key Storage

API keys are stored using Android's `EncryptedSharedPreferences`:
- Encryption: AES-256-GCM
- Key management: Android Keystore (hardware-backed when available)
- Keys are never included in backups or exports

## Network Security

The app enforces HTTPS for all connections except localhost development:

```xml
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain>localhost</domain>
    <domain>127.0.0.1</domain>
    <domain>10.0.2.2</domain>  <!-- Emulator -->
  </domain-config>
  <base-config cleartextTrafficPermitted="false">
    <trust-anchors>
      <certificates src="system" />
    </trust-anchors>
  </base-config>
</network-security-config>
```

## Authentication

| Surface | Method |
|---------|--------|
| API Server | Bearer token (`Authorization: Bearer <key>`) |
| Relay Server | Pairing code followed by session token |

## Relay Auth Flow

1. Operator runs `/hermes-relay-pair` (from any Hermes chat surface) or `hermes-pair` (shell shim) on the Hermes host
2. The pair command probes `localhost:RELAY_PORT/health`; if the relay is up, it mints a fresh 6-char code
3. It pre-registers the code with the relay via the loopback-only `POST /pairing/register` endpoint
4. The relay URL + code are embedded in the QR payload alongside the API server credentials
5. Phone scans once, opens WSS, and sends the code in its first `system/auth` envelope
6. Relay consumes the code and returns a session token
7. App stores the token in EncryptedSharedPreferences
8. Future connections use the token directly (no re-pairing)
9. Token expires after 30 days or on manual revoke

Pairing codes use the full `A-Z / 0-9` alphabet (36 chars). The earlier "no ambiguous 0/O/1/I" restriction was dropped on 2026-04-11 when the pairing flow moved from "human retypes code" to "code flows through QR + HTTP" — the phone-side generator uses the full alphabet, and enforcing the smaller alphabet silently rejected valid codes.

`POST /pairing/register` is gated to loopback callers only (`127.0.0.1` / `::1`). Only a process running on the same host as the relay can inject pairing codes — a LAN attacker cannot. Trust anchor: the operator with host shell access.

## Rate Limiting

- Failed WebSocket authentication attempts are rate-limited per IP
- After 5 failed attempts in 60 seconds, the IP is blocked for 5 minutes
- Blocked IPs receive HTTP 429

## Data Protection

- Session tokens encrypted in EncryptedSharedPreferences
- API keys never logged or included in error messages
- Backup exports exclude tokens and API keys
- DataStore preferences are app-private (standard Android sandbox)

## Bridge Security — Five-Stage Safety Gate

The bridge channel gives the agent the ability to read your screen, tap, type, swipe, open apps, and take screenshots. This is powerful and inherently sensitive — treat it with the same caution as remote desktop access. To keep that power bounded, every bridge command must pass five independent gates before a single gesture dispatches:

1. **Session grant** — the paired device's session must include a `bridge` channel grant (the TTL and grant matrix chosen at pair time)
2. **In-app master toggle** — the **Allow Agent Control** switch on the Bridge tab is the user-facing kill switch. Labelled with a `MASTER` pill and "Master switch —" subtitle as of v0.4.1 so its parent-gate role is legible at a glance. Tapping the Switch when the Accessibility grant is missing surfaces a snackbar ("Accessibility Service must be enabled first.") with an "Open Settings" action that deep-links to Android's Accessibility Settings rather than silently dropping the tap.
3. **`HermesAccessibilityService`** — a standard Android accessibility-service grant from system Settings
4. **`MediaProjection` consent** — a one-tap system dialog granted per screen-capture session (required for `/screenshot`)
5. **Tier 5 safety rails** — per-command, content-aware checks run on the phone side before any gesture executes

### Tier 5 Safety Rails

The Tier 5 pipeline runs inside `BridgeSafetyManager` on every inbound command:

- **App blocklist** — ~30 banking / payments / password-manager / 2FA / email / work apps are pre-seeded as defaults. Editable from **Settings → Bridge → Safety**. The blocklist is checked against the currently foregrounded app on every command, and against the *target* package on `/open_app` so an agent can't bypass it by launching a banking app.
- **Destructive-verb confirmation** — commands that carry text payloads (`/tap_text`, `/type`) or target UI elements by node ID (`/tap`, `/long_press`) run a word-boundary regex against a configurable verb list (`send`, `pay`, `delete`, `transfer`, `confirm`, `submit`, `post`, `publish`, `buy`, `purchase`, `charge`, `withdraw` by default). For `/tap` and `/long_press` with a `nodeId`, the phone resolves the tapped node's text via `ScreenReader.findNodeById` and pattern-matches that text. A match opens a full-screen `WindowManager` overlay modal showing the command, the flagged verb, and the full payload text. The user must tap Allow before the gesture fires. Fails closed on timeout or missing overlay permission. **Denial is final** — the phone returns `error_code: user_denied` with an explicit "do not retry via UI automation" instruction; the agent cannot circumvent a denial by driving the same app's UI through a different tool path.
- **Idle auto-disable timer** — the bridge flips itself off after 5-120 minutes of inactivity (user-configurable). The timer resets on every command, so an active session stays live. Process death clears state so a stale grant can't survive a crash.
- **Optional persistent status overlay** — a small floating "Hermes active" pill rendered via `SYSTEM_ALERT_WINDOW` while the bridge is armed. When unattended access is on (sideload only) it switches to an amber "Unattended ON" variant and is forced visible regardless of the user's overlay preference.
- **In-app unattended banner** (v0.4.1, sideload only) — when master + unattended are both on, a 28dp amber strip renders at the top of every Hermes-Relay tab so the user always has an in-app affordance to disable. Pairs with the system overlay chip: the banner covers the app-foregrounded case, the chip covers the app-backgrounded case.
- **Persistent foreground notification** — `BridgeForegroundService` runs a non-dismissible notification with a one-tap **Disable** action any time the bridge master toggle is on, so there's always an in-sight kill switch. Owned by the master toggle, not by the unattended toggle — disabling the master flips the notification off.

### What bypasses the gate

`/ping`, `/current_app`, and `/return_to_hermes` — liveness, introspection, and self-foreground — bypass the master-enable gate so agents and operators can check bridge health and return focus to Hermes without first unlocking actions. On the **googlePlay** flavor, a separate whitelist gate blocks ALL action routes before the master-enable gate even fires — only read-only routes (`/screen`, `/current_app`, `/get_apps`, `/clipboard` GET, `/return_to_hermes`) pass.

### Sideload-only permissions

The `sideload` build ships a fourth tier of phone-utility tools (`/location`, `/search_contacts`, `/call`, `/send_sms`) that require runtime permissions Google Play's policy forbids without a default-dialer / default-SMS-app justification. These are compiled out of the `googlePlay` build entirely; picking which flavor to install is itself a trust decision.

### Activity log

Every command is logged to the Bridge tab's activity log (timestamp, status, result text, optional screenshot token) so the user can audit what the agent has been doing. The log is capped at 100 entries and lives in local DataStore.

## Recommendations

1. **Use HTTPS** in production — the network security config enforces it by default
2. **Rotate API keys** periodically in your Hermes server config
3. **Disconnect when idle** — especially if bridge is enabled (or let the auto-disable timer handle it)
4. **Avoid public WiFi** for relay connections without additional encryption
5. **Keep the app updated** — security patches ship with new releases
