<script setup>
import { withBase } from 'vitepress'
</script>

# Installation & Setup

## Prerequisites

- Android device or emulator (API 26+ / Android 8.0+)
- A running [Hermes Agent](https://hermes-agent.nousresearch.com) instance (v0.8.0+ recommended) with the API server enabled
- Python 3.11+ on the server (for the pairing plugin)

## Quick Start

Three commands get you from zero to connected:

### 1. Install the Android app

Hermes-Relay ships in **two flavors** built from the same codebase:

- **Google Play** — easy install, automatic updates, the agent can read your screen and notifications. The accessibility-service surface is conservative by design so the build stays inside Play Store policy.
- **Sideload** — manual install from GitHub Releases, full feature set including hands-free voice control of your phone (the agent can tap, type, swipe, and navigate apps for you).

The two builds use different application IDs, so you can install both side-by-side and try them out. Most users want the Google Play version. Read the [Release tracks](/guide/release-tracks) page for the full feature comparison and a decision guide before you pick.

Once you've decided: install from the [Play Store listing](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay), or grab the file ending in `-sideload-release.apk` from the [latest GitHub Release](https://github.com/Codename-11/hermes-relay/releases/latest) and follow the [Sideload APK](#sideload-apk) section below for step-by-step install and integrity-verification instructions.

### 2. Install the server plugin

On the machine running your Hermes agent:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

The installer follows Hermes's canonical skill-distribution pattern:

1. Clones the repo to `~/.hermes/hermes-relay/` (override with `$HERMES_RELAY_HOME`)
2. `pip install -e ~/.hermes/hermes-relay/` into the hermes-agent venv — editable, so `git pull` is all that's needed to update the plugin
3. Adds `~/.hermes/hermes-relay/skills` to `skills.external_dirs` in `~/.hermes/config.yaml` (idempotent YAML edit) so the `hermes-relay-pair` skill is picked up on every hermes-agent load
4. Symlinks `~/.hermes/plugins/hermes-relay` → the clone's `plugin/` subdir
5. Installs a thin `~/.local/bin/hermes-pair` shim that execs `python -m plugin.pair` inside the hermes-agent venv
6. Installs a systemd user unit at `~/.config/systemd/user/hermes-relay.service` (optional — skipped on macOS, WSL-without-systemd, bare chroots)

Restart hermes-agent after install.

::: tip What you get
- **Full Hermes-Relay Android app features** — sessions browser, conversation history on app restart, personality picker, command palette, memory management. Just install the plugin and it works.
- **18 `android_*` device control tools** (tap, type, read screen, screenshot, open apps, send SMS, call, search contacts, etc.) — registered by the plugin
- **`/hermes-relay-pair` slash command** — backed by the `devops/hermes-relay-pair` skill and usable from any Hermes chat surface
- **`hermes-pair` shell shim** — for scripts and power-user flows
- **Voice mode endpoints** on the WSS relay (transcribe, synthesize, voice config) wired into the Android app's voice mode UI

No separate skill install, no `qrencode` binary needed.
:::

::: info Updating
Because the installer uses `pip install -e` for the plugin and `external_dirs` for the skill, updates are a single command:

```bash
cd ~/.hermes/hermes-relay && git pull && bash install.sh
systemctl --user restart hermes-gateway hermes-relay
```

`bash install.sh` is idempotent — safe to re-run as often as you like. It re-applies every step against the existing install, picks up any new files, and rebuilds the systemd unit from the latest template.
:::

::: info Uninstalling
A clean uninstaller ships in the same repo:

```bash
bash ~/.hermes/hermes-relay/uninstall.sh
```

Or if you don't have the clone any more:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/uninstall.sh | bash
```

The uninstaller reverses every install step in the opposite order, is idempotent, and never touches state shared with other Hermes tools (`~/.hermes/.env`, the gateway's `state.db`, the `hermes-agent` venv core). Useful flags:

```bash
bash uninstall.sh --dry-run         # preview without changing anything
bash uninstall.sh --keep-clone      # leave ~/.hermes/hermes-relay in place
bash uninstall.sh --remove-secret   # also wipe the QR signing identity
```

By default the QR signing secret at `~/.hermes/hermes-relay-qr-secret` is preserved, so re-installing keeps the same identity and any phones still holding their session tokens stay valid.
:::

### 3. Pair your phone

You have two equivalent entry points — pick whichever fits where you already are:

**From an active Hermes session** (shortest path if you're already chatting with the agent): type `/hermes-relay-pair` in any chat surface — CLI, Discord, Telegram, anywhere Hermes is listening. The `hermes-relay-pair` skill generates the QR and renders it inline for you. No shell required.

**From a shell** (power-user / scriptable): on the server, run

```bash
hermes-pair
```

The dashed `hermes-pair` is a thin shim that execs `python -m plugin.pair` in the hermes-agent venv. Both routes share the same implementation and produce the same QR + plain-text output.

::: warning `hermes pair` (with a space) is not currently exposed
A top-level `hermes pair` sub-command would be nice, but hermes-agent v0.8.0's top-level argparser doesn't forward to third-party plugins' `register_cli_command()` dict yet. Use `/hermes-relay-pair` or the dashed `hermes-pair` shim in the meantime — both work today and will keep working once the upstream gap is closed.
:::

This prints a QR code **and** the plain-text connection details (server URL, API key). Scan the QR from the app's onboarding screen — or type the values in manually if your terminal can't render QR blocks. The text fallback is always shown, so this works inside Hermes's Rich TUI panel and over SSH with limited charsets.

**One scan configures chat *and* the relay.** If you've already started the Hermes-Relay WSS server on the same host (see [Relay Server](#relay-server-optional) below), `hermes-pair` automatically detects it at `localhost:8767`, mints a fresh 6-char pairing code, pre-registers the code with the relay via its loopback-only `/pairing/register` endpoint, and embeds the relay URL and code in the same QR. The phone scans once and is ready for chat, terminal, and bridge.

If the relay isn't running, `hermes-pair` prints an `[info]` line pointing at `hermes relay start` and renders an API-only QR — chat still works, and you can pair with the relay later once it's up. You can also force API-only mode explicitly:

```bash
hermes-pair --no-relay
```

#### Choosing session lifetime + channel grants

By default the phone prompts you to pick a session TTL when you scan the QR (1 day / 7 days / 30 days / 90 days / 1 year / never expire). You can also **pre-set** the TTL and per-channel grants on the host side so the phone's picker dialog opens with your chosen values already selected:

```bash
# Pair for 7 days
hermes-pair --ttl 7d

# Pair indefinitely, limit terminal to 30 days and bridge to 1 day
hermes-pair --ttl never --grants terminal=30d,bridge=1d

# Short-lived dev session
hermes-pair --ttl 1d
```

Supported duration formats: `1d`, `7d`, `30d`, `90d`, `1y`, `never` (or any `<number><unit>` combo where unit is `s`/`m`/`h`/`d`/`w`/`y`). Grants can be pre-set for the `terminal` and `bridge` channels and are automatically clamped to the overall session TTL — a grant cannot outlive its session.

::: tip Camera unavailable? Use manual pairing
If you can't scan a QR — for example you're SSH'd into the host from the same phone you want to pair, the host has no display attached, or there's no second camera-equipped device handy — Hermes-Relay ships a manual fallback flow. Open the app's **Settings → Connections → [active card] → Advanced → Manual pairing code (fallback)** section to read its locally-generated 6-char code, then on the host run:

```bash
hermes-pair --register-code ABCD12             # default 30d session
hermes-pair --register-code ABCD12 --ttl 7d    # composes with --ttl / --grants
```

The command pre-registers your code with the local relay over loopback and prints a confirmation. Tap **Connect** in the same card and you're paired. Same 10-minute single-use expiry as QR codes; same TTL/grant rules — `--ttl` and `--grants` flags compose with `--register-code` exactly the same way they compose with the default QR flow.
:::

The phone's TTL picker dialog always opens on scan, preselected with your chosen values, so you have one final chance to confirm or override before the session is created. The selection you make is persisted as the new default for future pairs.

::: tip Never expire
`Never expire` is always available in the picker regardless of transport. The phone treats your intent as the trust model rather than gating on secure-transport detection — if you explicitly pick it, the session stays active until you revoke it from **Paired Devices**.
:::

#### Transport security + insecure-mode consent

The app renders a **Transport Security** badge next to each Connection row:

- 🔒 **Secure (TLS)** — paired over `wss://`
- 🔓 **Insecure (LAN only / Tailscale / Local dev)** — paired over plain `ws://` with the reason you picked on the consent dialog
- 🔓 **Insecure** — plain `ws://` with no reason recorded

The first time you toggle insecure mode on, a consent dialog opens with a plain-language threat-model explanation and a reason picker. The reason is displayed on the badge but is not enforced — it's informational, to make the choice visible to you later.

The app also runs a **Trust On First Use** (TOFU) cert pinning check on `wss://` connections: on the first successful handshake it records the server's certificate fingerprint, and every subsequent connect verifies against it. If the cert changes (because the relay was rebuilt, the Let's Encrypt cert rolled over, or an MITM is happening), the connection fails loudly. Re-pairing via QR is taken as explicit consent to pin a new certificate.

#### Paired Devices management

**Settings → Connections → [active card] → Paired Devices** lists every device currently paired with the relay — device name, transport badge, session expiry, per-channel grant chips, and a **Revoke** button per row. Revoking the current device wipes local state and redirects to the pair flow. Any paired device can revoke any other; for single-operator setups this is intentional (so you can manage everything from one phone), multi-user deployments will need a role model later.

::: tip Multiple Hermes servers
The app supports pairing with more than one Hermes server (home + work, dev + prod, etc.) and switching with a single tap. Once you've paired the first server, open **Settings → Connections** to add a second — it launches the same QR flow. When you have two or more, a **Connection** radio list appears inside the agent sheet (tap the agent name in the Chat top bar), letting you switch without re-pairing. See [Connections](/features/connections) for the full model.
:::

::: warning Security
The QR contains credentials — your API key if one is set, and the relay pairing code if a relay block was embedded. The pairing QR is now also signed with HMAC-SHA256 using a host-local secret (auto-created at `~/.hermes/hermes-relay-qr-secret`, mode 0o600). Don't screenshot or share it. The relay code is one-shot and expires in 10 minutes, but the API key is long-lived.
:::

## Hermes Server Setup

Enable the API server in your Hermes configuration (`~/.hermes/.env`):

```bash
API_SERVER_ENABLED=true
API_SERVER_KEY=your-secret-key-here
API_SERVER_HOST=0.0.0.0  # Allow network access (default is localhost only)
API_SERVER_PORT=8642
```

::: tip API key is optional for local setups
If you're running Hermes on the same machine (or connecting via `localhost`), you can leave `API_SERVER_KEY` unset. The key is only needed when exposing the API server over the network. If you do set one, `hermes-pair` reads it automatically.
:::

## Sideload APK

If you'd rather not use Google Play, you can install the signed APK directly from GitHub Releases. This works on any Android 8.0+ device.

### 1. Download the APK

Head to [github.com/Codename-11/hermes-relay/releases/latest](https://github.com/Codename-11/hermes-relay/releases/latest) and grab the file ending in **`-sideload-release.apk`** from the assets list — for example, `hermes-relay-0.4.0-sideload-release.apk`. Every release is version-tagged, so the exact prefix changes each version but the `-sideload-release.apk` suffix stays constant.

::: tip Why "sideload" and not "googlePlay"?
Each release ships both a `-sideload-release.apk` (full feature set — bridge channel, voice-to-bridge intents, vision-driven navigation) and a `-googlePlay-release.apk` (conservative Play Store build). Most sideloaders want the `-sideload-` flavor. The two builds install with different application IDs, so you can have both side-by-side.
:::

::: warning Download the .apk, not the .aab
Each release also ships `-release.aab` files. That's the Android App Bundle format Google Play uses internally — it **won't install directly** on your device. Always pick a file ending in `.apk` for sideloading.
:::

### 2. Allow installs from your browser (first time only)

Android blocks APKs from unknown sources by default. Before the install prompt appears, you'll need to grant permission to whichever app you used to download the file (usually Chrome, Firefox, or your Files app):

- **Settings → Apps → Special app access → Install unknown apps**
- Pick the browser or file manager you downloaded the APK with
- Toggle **Allow from this source**

The exact wording varies by OEM (Samsung calls it "Install unknown apps", Pixel calls it "Install unknown apps", older versions use "Security → Unknown sources"), but the idea is the same.

### 3. Install it

Open the downloaded APK from your Downloads notification or the Files app, then tap **Install**. The first launch will walk you through onboarding and pairing.

### 4. Verify integrity (optional but recommended)

Every release ships a `SHA256SUMS.txt` file alongside the APK. Compare the checksum of your download against it before installing:

**macOS / Linux / Git Bash:**

```bash
sha256sum hermes-relay-*-sideload-release.apk
# Compare the output against the matching line in SHA256SUMS.txt
```

**Windows PowerShell:**

```powershell
Get-FileHash -Algorithm SHA256 hermes-relay-*-sideload-release.apk
# Compare the Hash column against the matching line in SHA256SUMS.txt
```

If the hashes don't match, **don't install** — redownload and try again.

### 5. Verify the signing certificate (advanced)

The APK is signed with the Codename-11 release keystore. If you want to confirm the signature matches the one Google Play pins to the app, check the SHA256 fingerprint of the certificate:

- **Subject:** `CN=Bailey Dixon, Codename-11`
- **SHA256 fingerprint:**
  ```
  A9:A4:2D:94:20:8B:94:B3:68:5B:01:93:E3:94:9B:90:50:AD:80:60:56:E7:16:3C:FC:E5:11:AF:68:0D:79:4B
  ```

You can inspect it yourself with:

```bash
keytool -printcert -jarfile hermes-relay-*-sideload-release.apk
```

## Manual Install (from source)

If you prefer to build the app yourself:

```bash
git clone https://github.com/Codename-11/hermes-relay.git
cd hermes-relay
scripts/dev.bat build    # Build debug APK
scripts/dev.bat run      # Build + install + launch (requires connected device)
```

## Manual Pairing

If you don't want to use QR pairing, you can enter connection details by hand — either during the app's onboarding flow or later from Settings.

**During onboarding:**

1. The app opens with an onboarding flow
2. On the **Connect** page, tap **Enter manually**
3. Type your API Server URL (e.g., `http://192.168.1.100:8642`) and API Key
4. Tap **Test Connection** to verify
5. Optionally enter a **Relay URL** for Terminal/Bridge features
6. Tap **Get Started**

**After onboarding:** open **Settings → Connections**. Each paired server is a card in the list; the currently-active card expands inline to show status rows, endpoint details, and an **Advanced** section with manual URL config, insecure-mode toggle, and the manual pairing-code fallback flow. The per-card **Re-pair** button is the one-tap entry point for scanning a new QR. API Server URL, API Key, Relay URL, and Insecure Mode all live under the active card's **Advanced** expander, with **Save & Test** for each.

The `hermes-pair` command always prints these same values as plain text alongside the QR code, so you can copy them directly.

## Relay Server (Optional)

The relay server is only needed for **Terminal** (remote shell) and **Bridge** (agent-driven phone control). Chat works without it.

::: tip Start the relay
```bash
# If you installed the hermes-relay plugin (recommended):
hermes relay start --no-ssl

# Or directly from a repo checkout:
python -m plugin.relay --no-ssl
```
Run this on the same machine as hermes-agent. If the relay is running when you execute `hermes-pair` (or `/hermes-relay-pair`), its URL and a freshly-registered pairing code are automatically embedded in the QR — you don't need to enter anything in the app.
:::

For persistent deployment, Docker, systemd, and TLS options, see the [Relay Server docs](/reference/relay-server).

If you only saw an API-only QR earlier (because the relay wasn't running), just start the relay and re-run `hermes-pair` — the new QR will include the relay block.

## Connecting from Anywhere (Tailscale, VPN, Public URL)

Hermes-Relay supports **multi-endpoint pairing**: one QR carries every network path your server is reachable on, and the phone auto-picks whichever is reachable at the moment. Works across LAN / cell / tailnet / public reverse proxy without re-pairing when you change networks.

**Default — `--mode auto`.** `hermes-pair --mode auto` (run on the server) probes the LAN, detects Tailscale if it's running, and emits an ordered candidate list in the QR. To include an external reverse-proxy or Cloudflare Tunnel URL, add `--public-url https://hermes.example.com`.

**Enable Tailscale on the server** with `hermes-relay-tailscale enable` — this fronts the loopback-bound relay port with `tailscale serve --https=8767`, using Tailscale's managed TLS + tailnet ACLs. Skip this if you prefer a reverse proxy + Let's Encrypt, or a self-hosted VPN — both work identically; Hermes-Relay doesn't care how the phone reaches the host as long as it can.

**Forcing a specific mode at pair time** — `--prefer <role>` promotes a named role to priority 0 (e.g. `--prefer tailscale` for a QR biased toward the tailnet even when LAN is reachable). Open vocabulary — any role string you pass through `--mode` or a custom operator setup works here.

**Override per-session on the phone** — Settings → Connections → [active card] → **Show endpoints** expander → row menu → **Prefer this endpoint**.

For the full matrix (Tailscale, Caddy + Let's Encrypt, Cloudflare Tunnel, self-hosted WireGuard, plaintext over trusted VPN) with working config blocks, see [remote-access.md](https://github.com/Codename-11/hermes-relay/blob/main/docs/remote-access.md) and the [Connections page](/features/connections#multi-endpoint-pairing-one-qr-for-every-network).

## Verify Connection

Once you're connected, the chat looks like this — streaming responses, tool cards, markdown rendering, and the personality picker all live:

<div class="demo-video-wrap">
  <video
    :src="withBase('/chat_demo.mp4')"
    :poster="withBase('/chat_demo_poster.jpg')"
    controls
    muted
    loop
    playsinline
    preload="metadata"
  />
</div>

<style scoped>
.demo-video-wrap {
  display: flex;
  justify-content: center;
  margin: 1.5rem 0 2rem;
}
.demo-video-wrap video {
  max-width: 320px;
  width: 100%;
  height: auto;
  border-radius: 16px;
  box-shadow: 0 20px 40px -15px rgba(0, 0, 0, 0.5),
              0 0 0 1px var(--vp-c-divider);
  background: #000;
}
</style>

After onboarding, the chat header shows the agent name with an animated green pulse on the avatar when the API server is reachable. If the dot is red (no pulse), check:

- Is the Hermes agent running? (`hermes gateway`)
- Is `API_SERVER_ENABLED=true`?
- Can your phone reach the server? (same network, firewall rules)
- Is the URL correct? (include port, e.g., `:8642`)
