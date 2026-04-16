---
name: hermes-relay-doctor
description: Smoke-test the Hermes-Relay bridge stack — checks relay health, phone connection, bridge channel, accessibility service, and screenshot capture in one pass
version: 1.0.0
author: Axiom Labs
license: MIT
platforms: [linux, macos]
metadata:
  hermes:
    tags: [android, bridge, debug, doctor, relay, health, troubleshoot]
    category: devops
    homepage: https://github.com/Codename-11/hermes-relay
    related_skills: [hermes-relay-pair, hermes-relay-self-setup]
---

# Hermes-Relay Doctor

Runs a sequential smoke-test of the full bridge stack and reports pass/fail with fix hints for each check. Use this before reaching for `android_*` tools when something feels off.

## When to Use

- User runs `/hermes-relay-doctor`.
- User says the bridge isn't working, the phone isn't connecting, or a screenshot is failing.
- You want to verify the stack is healthy before issuing `android_*` tool calls.
- The `android_ping()` tool returns an error or `phone_connected: false`.

## Procedure

Run the shim if it is installed:

```bash
hermes-relay-doctor
```

If the shim is missing (pre-0.3.x installs or after a manual uninstall), run the checks inline:

```bash
PORT="${RELAY_PORT:-8767}"
curl -sf "http://127.0.0.1:$PORT/health"
curl -sf "http://127.0.0.1:$PORT/bridge/status"
curl -sf "http://127.0.0.1:$PORT/ping"
curl -sf "http://127.0.0.1:$PORT/current_app"
curl -sf "http://127.0.0.1:$PORT/screen"
curl -sf "http://127.0.0.1:$PORT/screenshot"
```

All six checks call loopback (`127.0.0.1`) — no bearer token needed.

## Interpreting Results

| Check | Pass | Fail / Fix |
|-------|------|------------|
| `/health` | `{"status":"ok","version":"..."}` (200) | Relay not running — `systemctl --user start hermes-relay` or check `journalctl --user -u hermes-relay -n 30` |
| `/bridge/status` | `{"phone_connected":true,...}` (200) | Phone not connected — open Hermes-Relay app and scan a pairing QR (`/hermes-relay-pair`) |
| `/ping` | `{"phone_connected":true}` (200) | Same as above — phone disconnected mid-session; re-scan QR |
| `/current_app` | `{"package":"...","activity":"..."}` (200) | Accessibility service not connected — go to Android Settings → Accessibility → Hermes-Relay → enable |
| `/screen` | JSON with `nodes` array (200) | Same as above — also check the permission checklist in the Bridge screen |
| `/screenshot` | `{"token":"..."}` (200) | MediaProjection not granted — see "Screenshot gap" below |

### Screenshot gap (known issue)

`/screenshot` returns `500 {"error":"MediaProjection not granted — enable Bridge screenshots in the app"}` when the accessibility service is running but the system MediaProjection consent dialog hasn't been accepted.

Fix:
1. Open the Hermes-Relay app → **Bridge** screen.
2. Toggle the **Allow Agent Control** switch on.
3. Android shows a "Start recording?" system dialog — tap **Start**.
4. Re-run `/hermes-relay-doctor` to confirm the screenshot check passes.

This is a per-boot requirement on Android — the MediaProjection grant is revoked on reboot.

## Re-installing the shim

```bash
hermes-relay-update
```

Or re-run the full installer:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```
