---
name: hermes-relay-status
description: Print the current state of the paired Hermes-Relay Android phone — connection, device info, bridge permissions, and safety-rail configuration. Reads the relay's /bridge/status loopback endpoint.
version: 1.0.0
author: Axiom Labs
license: MIT
platforms: [linux, macos]
metadata:
  hermes:
    tags: [status, diagnostics, android, relay, bridge, hermes-relay]
    category: devops
    homepage: https://github.com/Codename-11/hermes-relay
    related_skills: [hermes-relay-pair]
---

# Hermes-Relay Phone Status

[Hermes-Relay](https://github.com/Codename-11/hermes-relay) is a native Android client for Hermes. This skill prints a snapshot of the phone currently paired to the local relay: whether it's connected, recent device telemetry (battery, screen, foreground app), which bridge permissions have been granted on the Android side, and what safety rails (blocklist, destructive-verb confirmation, auto-disable timer) are configured. It's the read-only counterpart to `/hermes-relay-pair`.

## When to Use

Invoke this skill when any of the following happens:

- User runs the `/hermes-relay-status` slash command.
- User asks to "check my phone", "is my phone connected?", "what's the battery on my phone?", "can the agent see my phone?", or anything equivalent.
- Before attempting a sequence of bridge tool calls (`android_tap`, `android_type`, `android_screenshot`, etc.) to confirm the phone is reachable and has the permissions those calls need.
- Debugging why a bridge call failed — status shows whether the problem is "phone offline", "permission missing", or "safety rail blocked it".

Do NOT use this skill to start or install the relay server itself — that is a prerequisite. Reference `hermes relay start`.

## Prerequisites

1. **Hermes-Relay plugin installed into the Hermes venv.** Verify by running `python -m plugin.status --help` — if it errors with `ModuleNotFoundError: No module named 'plugin'`, install it first: `pip install -e <path-to-hermes-relay-repo>`.
2. **Relay server running** on `RELAY_HOST:RELAY_PORT` (default `0.0.0.0:8767`). Without a live relay, this skill exits with code `1` and a "relay unreachable" error.
3. **Phone has connected at least once** since the last relay restart. The relay tracks phone state in memory, so a restart clears it — the phone re-pairs automatically on reconnect. Until then, status returns "no phone connected" with exit code `2`.

## Procedure

1. **Run the status command** — via the `terminal` tool:

   ```bash
   python -m plugin.status
   ```

   Or via the shell shim (installed by `install.sh` step 5):

   ```bash
   hermes-status
   ```

   If `python` resolves to the wrong interpreter (plugin not found), use the Hermes venv explicitly:

   ```bash
   ~/.hermes/hermes-agent/venv/bin/python -m plugin.status
   ```

2. **Useful flags** (pass only when needed, not by default):
   - `--json` — emit raw JSON instead of the pretty text block. Use when piping to `jq` or when the agent wants to inspect specific fields programmatically.
   - `--port <n>` — override the relay port if it's not on the default 8767.

3. **Show the output verbatim.** For the pretty block, pass the whole thing back to the user as a code block so the alignment is preserved. For JSON mode, summarize the interesting fields in plain language rather than dumping raw JSON to the user.

4. **Interpret the exit code.**
   - `0` — success, phone is connected. The output is the full status block.
   - `1` — relay unreachable. The relay isn't running on 127.0.0.1:8767 (or the overridden port). Tell the user: "The relay isn't responding on loopback — check that `hermes-relay.service` is up with `systemctl --user status hermes-relay`, or start it manually with `python -m plugin.relay --no-ssl`."
   - `2` — relay is up but no phone has connected since the last relay restart. Tell the user: "Your phone isn't currently paired to this relay. Run `/hermes-relay-pair` to mint a fresh QR and scan it with the Hermes-Relay app."

## Pitfalls

- **Relay not running.** `status` prints `[error] Cannot reach hermes-relay on 127.0.0.1:8767` to stderr and exits `1`. Fix: start the relay first (`systemctl --user start hermes-relay` or `python -m plugin.relay --no-ssl`) and re-run.
- **Plugin not installed.** `ModuleNotFoundError: No module named 'plugin'`. Fix: `pip install -e <hermes-relay-repo>` into the same Python environment Hermes uses. Use `which python` / `where python` to confirm you're targeting the Hermes venv.
- **Wrong venv.** If `hermes` CLI is global but plugin is in the Hermes venv, `python -m plugin.status` may resolve to the wrong Python. Call the venv Python explicitly: `~/.hermes/hermes-agent/venv/bin/python -m plugin.status`.
- **Phone shows as disconnected immediately after a relay restart.** Expected — the relay holds phone state in memory and wipes it on restart. The phone reconnects automatically on its next ping cycle (within ~30s). If it doesn't, check the phone side: the session token may need re-pairing via `/hermes-relay-pair`.
- **`bridge.accessibility_granted = false` but everything else looks fine.** The user has opened the Android app but not yet granted the Hermes-Relay accessibility service. Tell them: "Open Hermes-Relay → Bridge screen → the permission checklist will have Accessibility as the top row. Tap it to open Android Settings → Installed services → Hermes-Relay and flip the switch."
- **`bridge.master_enabled = false`.** Even with all four permissions granted, the in-app master toggle on the Bridge screen is off. Bridge tools will be soft-blocked. Tell them: "Your bridge master toggle is disabled — open the Bridge screen in the app and flip the 'Allow Agent Control' switch."

## Verification

After running the skill, confirm:

1. **Exit code matches what the agent reports.** If the agent says "connected" but exit code was `2`, something is wrong with the rendering layer.
2. **Permissions the user thinks are granted actually show `granted`.** If the user says "I granted accessibility yesterday" but status shows `not granted`, the accessibility service was killed by the OS and needs to be re-enabled on the phone.
3. **`last_seen_seconds_ago` is under 60.** If it's much higher, the phone's ping cycle has stalled — the TCP/WSS connection is probably dead even though state is still cached. A fresh pair via `/hermes-relay-pair` will fix it.

If any of those fail, fall back to the Pitfalls section.
