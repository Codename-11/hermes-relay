---
name: hermes-relay-self-setup
description: Install, verify, update, troubleshoot, or uninstall the Hermes-Relay plugin and Android pairing pipeline. Self-contained setup recipe for AI agents helping a user get Hermes-Relay running end-to-end.
version: 1.0.0
author: Axiom Labs
license: MIT
platforms: [linux, macos]
metadata:
  hermes:
    tags: [setup, install, hermes-relay, android, bridge, agent-readable]
    category: devops
    homepage: https://github.com/Codename-11/hermes-relay
    related_skills: [hermes-relay-pair]
---

# Hermes-Relay Self-Setup

[Hermes-Relay](https://github.com/Codename-11/hermes-relay) is a native Android client for the Hermes AI agent platform. It ships a Python plugin (relay server + tools + skills) that runs alongside hermes-agent on the host, plus a Kotlin Compose Android app that talks to it. This skill is the canonical, agent-readable setup recipe — it covers fresh installs, updates, verification, pairing, troubleshooting, and uninstallation.

## When to Use

Invoke this skill when any of the following happens:

- User runs the `/hermes-relay-self-setup` slash command.
- User asks to "install Hermes-Relay", "set up the Android app", "update Hermes-Relay", "fix my Hermes-Relay install", or anything equivalent.
- User reports any of: missing `hermes-pair` / `hermes-status` shims, missing `android_*` tools, "Bridge is disabled" 403s, `phone_connected: false` from the relay, stale plugin tools after a `git pull`.
- User pasted the "For AI Agents" copy-paste block from the Hermes-Relay README or docs site and asked you to follow it.

Do NOT use this skill to write feature code or modify the plugin source. This is a setup/maintenance skill, not a development one.

## Prerequisites

1. **hermes-agent already installed** at `~/.hermes/hermes-agent/`. Verify with:
   ```bash
   ls ~/.hermes/hermes-agent/venv/bin/python
   ```
   If that file doesn't exist, **stop and ask the user to install hermes-agent first** — you cannot install hermes-relay without it. Hermes-Relay is a plugin, not a standalone product.

2. **Linux or macOS host.** The relay's terminal channel uses a real PTY, which is POSIX-only. Windows hosts can run chat/bridge but the terminal tab won't work.

3. **Internet access** to fetch the install script from GitHub. The one-liner installer pulls from `raw.githubusercontent.com`.

## Procedure

### A. Fresh install (or repair)

The canonical install command is **idempotent** — safe to re-run on any existing install. It pulls the latest main, refreshes the editable pip install, recreates the shell shims, re-registers the skills directory, restarts the relay service, and prompts before restarting hermes-gateway.

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

**Confirm before running** if the user has active chat sessions — the optional gateway restart will interrupt them for ~2 seconds. The installer prompts for the gateway restart by default; you do not need to opt in unless the user added new plugin tools and wants them re-imported immediately.

To opt into the gateway restart automatically (for scripted runs):
```bash
HERMES_RELAY_RESTART_GATEWAY=1 curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

To skip it entirely:
```bash
HERMES_RELAY_NO_RESTART_GATEWAY=1 curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

### B. Update an existing install

Same command. The installer is idempotent — it detects an existing clone, fast-forwards it from main, and re-runs every step. **Do not** suggest manual `git pull` + `pip install -e` + shim recreation; the one-liner does all of that more reliably.

### C. Verify the install

After install completes, run these checks in order. Stop and report if any fail.

1. **Relay health endpoint** (unauthenticated, fastest signal):
   ```bash
   curl -s http://localhost:8767/health
   ```
   Expect: `200 OK` with a JSON body containing `"status": "ok"` and a non-blank `"version"`.

2. **Status endpoint** (loopback only, expects no phone yet on a fresh install):
   ```bash
   curl -s http://localhost:8767/bridge/status
   ```
   Expect: `503` + `{"phone_connected": false, "error": "no phone connected"}` until a phone has paired and pushed a status envelope.

3. **Shell shims installed**:
   ```bash
   command -v hermes-pair && command -v hermes-status
   ```
   Expect: both resolve to `~/.local/bin/`.

4. **Plugin loaded by hermes-agent**:
   ```bash
   ~/.hermes/hermes-agent/venv/bin/python -c "import plugin.pair; import plugin.status; print('plugin OK')"
   ```
   Expect: `plugin OK` with no `ModuleNotFoundError`.

5. **Systemd service running** (if installed as a user service):
   ```bash
   systemctl --user is-active hermes-relay.service
   ```
   Expect: `active`.

If any check fails, jump to the relevant entry in the **Troubleshooting** section below.

### D. Pair the phone

Once the install verifies green, pair the user's Android device:

```bash
hermes-pair
```

Or, from any Hermes chat session:
```
/hermes-relay-pair
```

Both invoke the `hermes-relay-pair` skill which generates a single QR code that configures both the chat (API server) and bridge/terminal (relay) channels. Tell the user to open the Hermes-Relay Android app → `Settings` → `Connection` → `Scan Pairing QR` (or the Connect page during onboarding).

After scan, verify with:
```bash
hermes-status
```
Expect: `phone_connected: yes` with device name, battery, granted permissions, and safety state populated.

### E. Update vs. fresh install — same command

Both are the same one-liner. The user does not need to know which mode they're in; the installer figures it out.

### F. Uninstall

```bash
bash ~/.hermes/hermes-relay/uninstall.sh
```

Or, if the clone is gone:
```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/uninstall.sh | bash
```

The uninstaller is idempotent and never touches shared state (`~/.hermes/.env`, `state.db`, the hermes-agent venv core). Useful flags:
- `--dry-run` — preview without changing anything
- `--keep-clone` — leave the git tree in place for later
- `--remove-secret` — also wipe the QR signing identity (only do this if explicitly requested)

## Troubleshooting

### `hermes-agent not found at ~/.hermes/hermes-agent`
The user hasn't installed hermes-agent yet. Hermes-Relay is a plugin — it requires hermes-agent as a host. Direct them to the upstream installer at <https://github.com/NousResearch/hermes-agent> (or the user's preferred fork) and stop until they confirm it's installed.

### `pip install -e ... failed`
Either the venv Python is broken or the venv is missing build tools. Try:
```bash
~/.hermes/hermes-agent/venv/bin/python -m pip install --upgrade pip
~/.hermes/hermes-agent/venv/bin/python -m pip install -e ~/.hermes/hermes-relay
```
Surface any error to the user — do not try to "fix" venv internals.

### `hermes-status` says `phone_connected: false` after pairing
The relay's status cache is wiped on every restart (in-memory only). The phone re-pushes its status envelope every 30 seconds. Either wait, or have the user toggle Bridge off + on in the Android app — that triggers an immediate `pushNow()`.

### Bridge commands return 403 "Bridge is disabled"
The user has the bridge master toggle off. They need to open the Hermes-Relay app → Bridge tab → flip "Allow Agent Control" on. This is a deliberate gate; do not bypass it.

### `android_phone_status` tool not found by the agent
hermes-gateway hasn't re-imported the plugin since `git pull`. Restart it:
```bash
systemctl --user restart hermes-gateway
```
Or re-run the installer with `HERMES_RELAY_RESTART_GATEWAY=1` to do this automatically.

### Pairing rate-limited
The relay clears all rate-limit blocks on `/pairing/register`. Re-run `hermes-pair` and the previous block lifts.

### Relay won't start as a systemd service
Check the journal:
```bash
journalctl --user -u hermes-relay -n 30 --no-pager
```
Most common cause: another `python -m plugin.relay` instance is holding port 8767. Fix:
```bash
pkill -f 'python -m plugin.relay'
systemctl --user restart hermes-relay
```

### `phone_connected: false` plus `last_seen_seconds_ago: null`
No phone has ever pushed a status envelope to this relay process. This is the normal pre-pairing state — pair a phone first.

### Phone is paired but the agent's `android_*` tool calls fail
Check that the bridge is enabled AND the relevant permission is granted. Use `hermes-status` to see what's actually granted. If `screen_capture_granted` is false, the user needs to tap the Screen Capture row in the Bridge tab to launch the system consent dialog. If `accessibility_granted` is false, they need to enable `Hermes-Bridge` in Android Settings → Accessibility.

## Verification (final)

After install + pair, confirm all of the following before declaring the setup complete:

1. `curl -s http://localhost:8767/health` returns `200` + valid JSON
2. `hermes-status` returns `phone_connected: yes` with the user's device name
3. The user can send a test chat message from the Android app and receive a streaming response
4. The agent can successfully call `android_phone_status()` from a Hermes chat and receive a structured payload (this verifies the gateway has re-imported the plugin tools)

If all four pass, the install is healthy. If any fail, return to the matching Troubleshooting entry.

## Safety

- **Always confirm before running install commands.** Do not run them silently.
- **Never restart `hermes-gateway` without asking.** It interrupts active chat sessions.
- **Do not modify `~/.hermes/.env`** or `~/.hermes/config.yaml` outside what the installer does. Those are user-owned config.
- **Do not bypass the bridge master toggle**, the destructive-verb confirmation modal, or the blocklist. Those are deliberate safety features.
- **Do not push to origin** for the user. Updates go through the installer; code changes go through their own workflow.
