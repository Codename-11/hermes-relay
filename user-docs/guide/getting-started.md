<script setup>
import { withBase } from 'vitepress'
</script>

# Installation & Setup

## Prerequisites

- Android device or emulator (API 26+ / Android 8.0+)
- A running [Hermes Agent](https://hermes-agent.nousresearch.com) instance (v0.8.0+ recommended) with the API server enabled
- Python 3.11+ on the server only if you plan to install the optional Relay power-user plugin

## Quick Start

The default path is standard Hermes first: connect Android to the Hermes API/dashboard, then add Relay pairing only when you need power-user features.

### 1. Install the Android app

Hermes-Relay ships in **two flavors** built from the same codebase:

- **Google Play** — easy install, automatic updates, chat, profiles, voice, terminal/TUI relay, media, notification companion, relay sessions, and diagnostics. It does not include AccessibilityService-backed screen reading or phone control.
- **Sideload** — manual install from GitHub Releases, full feature set including hands-free voice control of your phone (the agent can read the screen, tap, type, swipe, and navigate apps for you).

The two builds use different application IDs, so you can install both side-by-side and try them out. Most users want the Google Play version. Read the [Release tracks](/guide/release-tracks) page for the full feature comparison and a decision guide before you pick.

Once you've decided: install from the [Play Store listing](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay), or grab the file ending in `-sideload-release.apk` from the newest Android release (`android-v*`; historical Android releases used bare `v*`) on [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases) and follow the [Sideload APK](#sideload-apk) section below for step-by-step install and integrity-verification instructions.

### 2. Prepare Hermes on your computer or server

Hermes-Relay for Android uses two upstream Hermes surfaces:

- **API server** on `:8642` for Chat and sessions
- **Dashboard** on `:9119` for Manage sign-in and admin screens

If Hermes is already installed, start at `hermes setup --portal`. If you already have a model/provider configured, you can skip that line.

**macOS / Linux / WSL2 / Termux:**

```bash
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
hermes setup --portal

mkdir -p ~/.hermes
API_SERVER_KEY="$(openssl rand -hex 32)"
cat >> ~/.hermes/.env <<EOF
API_SERVER_ENABLED=true
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642
API_SERVER_KEY=$API_SERVER_KEY
EOF
chmod 600 ~/.hermes/.env

echo "Android API URL: http://<this-computer-ip>:8642"
echo "Android API key: $API_SERVER_KEY"
hermes gateway
```

**Windows PowerShell:**

```powershell
iex (irm https://hermes-agent.nousresearch.com/install.ps1)
hermes setup --portal

$HermesDir = Join-Path $HOME ".hermes"
New-Item -ItemType Directory -Force $HermesDir | Out-Null
$ApiKey = ([guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N"))
@"
API_SERVER_ENABLED=true
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642
API_SERVER_KEY=$ApiKey
"@ | Add-Content (Join-Path $HermesDir ".env")

Write-Host "Android API URL: http://<this-computer-ip>:8642"
Write-Host "Android API key: $ApiKey"
hermes gateway
```

Replace `<this-computer-ip>` with the address your phone can reach, such as a LAN IP, Tailscale name, or HTTPS reverse-proxy host. Do not use `127.0.0.1` from Android unless Hermes is running on the phone itself.

For **Manage**, also run the Hermes dashboard on a phone-reachable URL. For a trusted LAN or VPN, the quick path is username/password auth:

```bash
# Run in a second terminal on the Hermes host.
DASHBOARD_SECRET="$(openssl rand -base64 32)"
cat >> ~/.hermes/.env <<EOF
HERMES_DASHBOARD_BASIC_AUTH_USERNAME=admin
HERMES_DASHBOARD_BASIC_AUTH_PASSWORD=choose-a-strong-password
HERMES_DASHBOARD_BASIC_AUTH_SECRET=$DASHBOARD_SECRET
EOF
chmod 600 ~/.hermes/.env

hermes dashboard --no-open --host 0.0.0.0 --port 9119
```

On Windows, set the same `HERMES_DASHBOARD_*` values in `$HOME\.hermes\.env`, then run the same `hermes dashboard --no-open --host 0.0.0.0 --port 9119` command in a second PowerShell window.

For a public or hosted dashboard, use upstream Hermes dashboard auth with Nous OAuth/OIDC instead of a simple password.

For the upstream details, see the Hermes [Installation](https://hermes-agent.nousresearch.com/docs/getting-started/installation), [Nous Portal](https://hermes-agent.nousresearch.com/docs/integrations/nous-portal), [API Server](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server), and [Web Dashboard](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard) docs.

::: warning Dashboard auth and API bearer auth are different
The API key above is for Android Chat on `:8642`. Dashboard sign-in on `:9119` uses dashboard cookies plus short-lived `/api/ws` tickets. Android supports dashboard username/password and Nous/OIDC sign-in for Manage, but dashboard login does not create an API key.
:::

### 3. Connect Android to Hermes

On first launch:

1. Tap through the standard onboarding pages.
2. On **Connect**, choose **Standard Hermes**.
3. Enter the API URL, for example `http://192.168.1.100:8642`, or tap **Scan for Hermes on LAN**.
4. Optional: scan a generic setup QR that contains an API URL, or JSON with `api_url` and optional `api_key`.
5. Enter the same value you set in `API_SERVER_KEY` for Android Chat fallback when the QR did not include it.
6. Optional: enter a Tailscale API URL such as `https://your-host.ts.net:8642`.
7. Tap **Connect**.

This enables Chat plus Manage surfaces such as Skills, Cron, MCP, Profiles, Models/Config, and Settings. Manage may ask you to sign in to the dashboard; choose **Sign in with Nous Research** when the dashboard advertises the `nous` provider, or use the username/password provider on trusted LAN/VPN deployments. Relay pairing is not required for this standard path.

When both a LAN URL and a Tailscale URL are saved, Android probes the saved routes and uses the highest-priority reachable one. Chat and Manage move together: LAN at home, Tailscale when you leave the local network.

### 4. Optional: add Relay power tools

Skip this unless you want Terminal, Bridge, Relay sessions, channel grants, or relay-backed device-control features.

On the Hermes host:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
hermes relay start --no-ssl
hermes pair
```

`hermes pair` is provided by the Hermes-Relay plugin through upstream Hermes' plugin CLI support; it is not a built-in Hermes core command. Then scan the QR in Android from **Settings -> Connections -> Pair Relay** or from onboarding's **Scan setup QR** path. If the relay is not running, the plugin can still print an API-only QR, so Chat works and Relay can be paired later.

More detail:

- [Relay Server](#relay-server-optional) for persistent service setup
- [Remote access](/guide/remote-access) for Tailscale, VPN, and public URL recipes
- [Connections](/features/connections) for multiple servers and route switching

::: tip Multiple Hermes servers
The app can save more than one Hermes server, such as Home and Work. Add or switch servers later in **Settings -> Connections**.
:::

## Dashboard Login From Android

Manage uses the Hermes dashboard/admin server and stores dashboard cookies separately from Relay pairing credentials.

- **Dashboard auth disabled/open dashboard:** Manage should work as long as Android can reach the dashboard URL.
- **Basic username/password login enabled:** supported. Android posts to `/auth/password-login` with the upstream `basic` provider, stores the dashboard cookies, and checks `/api/auth/me`.
- **Nous OAuth / OIDC redirect login enabled:** supported for dashboard auth. Android opens the dashboard's `/auth/login?provider=...` flow in an in-app WebView, imports the resulting dashboard cookies, checks `/api/auth/me`, and probes `/api/auth/ws-ticket`.
- **Custom password providers:** supported when `/api/auth/providers` advertises `supports_password: true`.

Relay pairing does not replace dashboard login. Dashboard login also does not mint an API key: it matches the Hermes Desktop remote-gateway path by authenticating `/api/ws` and `/api/pty` with dashboard cookies plus a single-use ticket from `/api/auth/ws-ticket`. Android now supports that login and ticket probe; API-key chat remains the fallback until Android's native dashboard-gateway chat adapter is wired in.

## Sideload APK

If you'd rather not use Google Play, you can install the signed APK directly from GitHub Releases. This works on any Android 8.0+ device.

### 1. Download the APK

Head to [github.com/Codename-11/hermes-relay/releases](https://github.com/Codename-11/hermes-relay/releases), open the newest Android release (`android-v*`; historical Android releases used bare `v*`), and grab the file ending in **`-sideload-release.apk`** from the assets list — for example, `hermes-relay-0.8.0-sideload-release.apk`. Every release is version-tagged, so the exact prefix changes each version but the `-sideload-release.apk` suffix stays constant.

::: tip Why "sideload" and not "googlePlay"?
Each release ships both a `-sideload-release.apk` (full Device Control set — screen reading, bridge channel, voice-to-bridge intents, vision-driven navigation) and a `-googlePlay-release.apk` (conservative Play Store build without AccessibilityService-backed screen reading or phone control). Most sideloaders want the `-sideload-` flavor. The two builds install with different application IDs, so you can have both side-by-side.
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

Open the downloaded APK from your Downloads notification or the Files app, then tap **Install**. The first launch will walk you through standard Hermes connection; Relay pairing is optional for power tools.

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

## Manual Setup

If you don't want to scan a QR, enter standard connection details by hand during onboarding or later from Settings.

**During onboarding:**

1. The app opens with an onboarding flow
2. On the **Connect** page, tap **Standard Hermes**
3. Type your API server URL, for example `http://192.168.1.100:8642`, scan for Hermes on LAN, or scan a generic QR containing the API URL/key
4. Enter the same value you set in `API_SERVER_KEY` for Android Chat fallback when it was not included by the QR
5. Optional: enter a Tailscale API URL such as `https://your-host.ts.net:8642`
6. Tap **Connect**

**After onboarding:** open **Settings → Connections**. Each Hermes host is a card in the list; the currently-active card expands inline to show status rows, route details, and an **Advanced** section with manual API URL/API key config, Relay URL override, insecure-mode toggle, and the manual Relay pairing-code fallback flow. The per-card **Pair Relay** / **Re-pair** button is the entry point for scanning a Relay QR when you need power tools.

For Standard setup there is no built-in upstream mobile pairing command yet, so use LAN scan, copy/paste, or a generic QR containing the API URL/key. If a QR includes a Relay block from the Hermes-Relay plugin, Android will show the Relay pairing confirmation and TTL/grants picker; if it is API-only, Android saves the standard API/dashboard connection.

## Relay Server (Optional)

The relay server is only needed for **Terminal** (remote shell) and **Bridge** (agent-driven phone control). Chat works without it.

::: tip Start the relay
```bash
# If you installed the hermes-relay plugin (recommended):
hermes relay start --no-ssl

# Or directly from a repo checkout:
python -m plugin.relay --no-ssl
```
Run this on the same machine as hermes-agent. On current upstream Hermes installs with the Hermes-Relay plugin enabled, the plugin-provided `hermes pair` command is available. If the relay is running when you execute `hermes pair` (or `/hermes-relay-pair`), its URL and a freshly-registered pairing code are automatically embedded in the QR — you don't need to enter anything in the app.
:::

For persistent deployment, Docker, systemd, and TLS options, see the [Relay Server docs](/reference/relay-server).

If you only saw an API-only QR earlier (because the relay wasn't running), just start the relay and re-run the plugin-provided `hermes pair` — the new QR will include the relay block.

## Connecting from Anywhere (Tailscale, VPN, Public URL)

Hermes-Relay supports **multi-endpoint pairing**: one QR carries every network path your server is reachable on, and the phone auto-picks whichever is reachable at the moment. Works across LAN / cell / tailnet / public reverse proxy without re-pairing when you change networks.

**Default — `--mode auto`.** `hermes pair --mode auto` (run on the server) probes the LAN, detects Tailscale if it's running, and emits an ordered candidate list in the QR. To include an external reverse-proxy or Cloudflare Tunnel URL, add `--public-url https://hermes.example.com`.

**Enable Tailscale on the server** with `hermes-relay-tailscale enable` — this fronts the loopback-bound relay port `8767` and Hermes API port `8642` with `tailscale serve`, using Tailscale's managed TLS + tailnet ACLs. Both ports matter: relay pairing covers terminal/bridge/control features, while chat and API-key voice use the Hermes API server. Skip this if you prefer a reverse proxy + Let's Encrypt, or a self-hosted VPN — both work identically as long as the phone can reach both services.

**Forcing a specific mode at pair time** — `--mode` accepts `auto`, `lan`, `tailscale`, or `public`. `--prefer <role>` promotes a named role to priority 0 (e.g. `--prefer tailscale` for a QR biased toward the tailnet even when LAN is reachable). Role matching is open-vocabulary for endpoint roles emitted by operator tooling, but the built-in CLI modes are fixed.

**Override per-session on the phone** — Settings → Connections → [active card] → **Show routes** expander → row menu → **Prefer this route**.

For the full matrix (Tailscale, Caddy + Let's Encrypt, Cloudflare Tunnel, self-hosted WireGuard, plaintext over trusted VPN) with working config blocks, see [Remote access](/guide/remote-access), [remote-access.md](https://github.com/Codename-11/hermes-relay/blob/main/docs/remote-access.md), and the [Connections page](/features/connections#multi-endpoint-pairing-one-qr-for-every-network).

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
