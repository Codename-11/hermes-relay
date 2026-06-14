<script setup>
import { withBase } from 'vitepress'
</script>

# Installation & Setup

Three steps — install the app, point it at your Hermes, say hello. If your
Hermes agent is already running, this takes about two minutes and needs nothing
installed on the server.

<div class="gs-steps">
  <span>1 · Install the app</span>
  <span>2 · Point it at Hermes</span>
  <span>3 · Connect &amp; chat</span>
</div>

## 1. Install the app

The easiest way — install from Google Play and get automatic updates:

<div class="gs-install-cta">
  <StoreBadge />
</div>

That's the **Google Play** build: chat, profiles, voice, terminal/TUI relay,
media, the notification companion, relay sessions, and diagnostics. It's what
most people want.

Prefer to install the APK by hand, or want the full phone-control feature set?
That's the **Sideload** build — same app, plus the agent can read your screen,
tap, type, and navigate apps for you.

::: details Google Play vs. Sideload — which build is right for me?
Both flavors are built from the same codebase and install with **different
application IDs**, so you can run them side-by-side and try both.

| | Google Play | Sideload |
|---|---|---|
| Install | One tap, auto-updates | Manual APK from GitHub Releases |
| Chat, voice, Manage | ✅ | ✅ |
| Terminal / TUI relay, media, notifications | ✅ | ✅ |
| Device Control (screen reading, taps, typing, vision navigation) | — | ✅ |

Most users want **Google Play**. Pick **Sideload** if you want the agent to
operate your phone for you. The [Release tracks](/guide/release-tracks) page has
the full feature comparison and a decision guide.
:::

::::details Sideload install — step by step (download, verify, install)
Grab the signed APK directly from GitHub Releases — works on any Android 8.0+
device.

**1. Download the APK.** Head to
[github.com/Codename-11/hermes-relay/releases](https://github.com/Codename-11/hermes-relay/releases),
open the newest Android release (`android-v*`; historical Android releases used
bare `v*`), and grab the file ending in **`-sideload-release.apk`** — for
example, `hermes-relay-1.0.0-sideload-release.apk`.

::: warning Download the .apk, not the .aab
Each release also ships `-release.aab` files. That's the Android App Bundle
format Google Play uses internally — it **won't install directly** on your
device. Always pick a file ending in `.apk`.
:::

**2. Allow installs from your browser (first time only).** Android blocks APKs
from unknown sources by default:

- **Settings → Apps → Special app access → Install unknown apps**
- Pick the browser or file manager you downloaded the APK with
- Toggle **Allow from this source**

The exact wording varies by OEM (Samsung and Pixel both say "Install unknown
apps"; older versions use "Security → Unknown sources"), but the idea is the same.

**3. Install it.** Open the downloaded APK from your Downloads notification or
the Files app, then tap **Install**.

**4. Verify integrity (optional but recommended).** Every release ships a
`SHA256SUMS.txt`. Compare your download's checksum before installing:

::: code-group
```bash [macOS / Linux / Git Bash]
sha256sum hermes-relay-*-sideload-release.apk
# Compare the output against the matching line in SHA256SUMS.txt
```
```powershell [Windows]
Get-FileHash -Algorithm SHA256 hermes-relay-*-sideload-release.apk
# Compare the Hash column against the matching line in SHA256SUMS.txt
```
:::

If the hashes don't match, **don't install** — redownload and try again.

**5. Verify the signing certificate (advanced).** The APK is signed with the
Codename-11 release keystore. To confirm the signature matches the one Google
Play pins to the app:

- **Subject:** `CN=Bailey Dixon, Codename-11`
- **SHA256 fingerprint:**
  ```
  A9:A4:2D:94:20:8B:94:B3:68:5B:01:93:E3:94:9B:90:50:AD:80:60:56:E7:16:3C:FC:E5:11:AF:68:0D:79:4B
  ```

```bash
keytool -printcert -jarfile hermes-relay-*-sideload-release.apk
```
::::

::: details Build it yourself from source
```bash
git clone https://github.com/Codename-11/hermes-relay.git
cd hermes-relay
scripts/dev.bat build    # Build debug APK
scripts/dev.bat run      # Build + install + launch (requires connected device)
```
:::

## 2. Point it at Hermes

Hermes-Relay talks to two upstream Hermes surfaces:

- **API server** on `:8642` — Chat and sessions
- **Dashboard** on `:9119` — Manage sign-in and admin screens

::: tip Already running Hermes with its API server enabled?
You're done with this step — head to [step 3](#_3-connect-chat). Everything below
is only for setting Hermes up the first time.
:::

You'll need a reachable [Hermes Agent](https://hermes-agent.nousresearch.com)
instance (v0.8.0+ recommended). The Relay power-user plugin (step 4) additionally
needs Python 3.11+ on the server.

::::details Set up Hermes + an API key (first-time server setup)
If Hermes is already installed, start at `hermes setup --portal`. If you already
have a model/provider configured, you can skip that line.

::: code-group
```bash [macOS / Linux / WSL2 / Termux]
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
```powershell [Windows]
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
:::

Replace `<this-computer-ip>` with the address your phone can reach — a LAN IP,
Tailscale name, or HTTPS reverse-proxy host. Don't use `127.0.0.1` from Android
unless Hermes is running on the phone itself.

For the upstream details, see the Hermes
[Installation](https://hermes-agent.nousresearch.com/docs/getting-started/installation),
[Nous Portal](https://hermes-agent.nousresearch.com/docs/integrations/nous-portal),
and [API Server](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server) docs.
::::

::::details Enable Manage (Skills, Cron, Models, Keys) — run the dashboard
For **Manage**, run the Hermes dashboard on a phone-reachable URL. On a trusted
LAN or VPN, the quick path is username/password auth:

::: code-group
```bash [macOS / Linux]
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
```powershell [Windows]
# Set the same HERMES_DASHBOARD_* values in $HOME\.hermes\.env, then:
hermes dashboard --no-open --host 0.0.0.0 --port 9119
```
:::

For a public or hosted dashboard, use upstream Hermes dashboard auth with Nous
OAuth/OIDC instead of a simple password. See the upstream
[Web Dashboard](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard) docs.

::: warning Dashboard auth and API bearer auth are different
The API key from the previous step is for Android Chat on `:8642`. Dashboard
sign-in on `:9119` uses dashboard cookies plus short-lived `/api/ws` tickets.
Android supports dashboard username/password and Nous/OIDC sign-in for Manage,
but dashboard login does **not** create an API key.
:::
::::

## 3. Connect & chat

On first launch:

1. Tap through the onboarding pages.
2. On **Connect**, choose **Standard Hermes**.
3. Enter the API URL — e.g. `http://192.168.1.100:8642` — or tap **Scan for
   Hermes on LAN**.
4. If your QR or URL didn't include the key, enter the value you set in
   `API_SERVER_KEY`.
5. Optional: add a Tailscale API URL such as `https://your-host.ts.net:8642`.
6. Tap **Connect**.

That's it — Chat is live, and the Manage surfaces (Skills, Cron, MCP, Profiles,
Models/Config) light up too. Manage may ask you to sign in to the dashboard the
first time; that same sign-in also unlocks voice for the connection. **Relay
pairing is not required for any of this.**

::: tip Home and away on one connection
Save both a LAN URL and a Tailscale URL and Android probes them on every connect,
using the highest-priority reachable one. Chat and Manage move together — LAN at
home, Tailscale when you leave.
:::

### See it working

Once you're connected, chat streams like this — responses, tool cards, markdown,
and the personality picker, all live:

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

The chat header shows the agent name with a green pulse on the avatar when the
API server is reachable. If the dot is red:

- Is the Hermes agent running? (`hermes gateway`)
- Is `API_SERVER_ENABLED=true`?
- Can your phone reach the server? (same network, firewall rules)
- Is the URL correct? (include the port, e.g. `:8642`)

More: [Troubleshooting](/guide/troubleshooting) · [Chat guide](/guide/chat) ·
[Connections](/features/connections).

## 4. Optional — add Relay power tools

Skip this unless you want **Terminal**, **Bridge** device control, **Relay
sessions**, channel grants, or relay-backed device-control features. Chat, voice,
and Manage all work without it.

::::details Install the Relay plugin + pair
On the Hermes host:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
hermes relay start --no-ssl
hermes pair
```

`hermes pair` is provided by the Hermes-Relay plugin through upstream Hermes'
plugin CLI support; it is not a built-in Hermes core command. Then scan the QR in
Android from **Settings → Connections → Pair Relay**, or from onboarding's **Scan
setup QR** path. If the relay isn't running, the plugin can still print an
API-only QR, so Chat works and Relay can be paired later.

::: tip Start the relay
```bash
# If you installed the hermes-relay plugin (recommended):
hermes relay start --no-ssl

# Or directly from a repo checkout:
python -m plugin.relay --no-ssl
```
Run this on the same machine as hermes-agent. On current upstream Hermes installs
with the plugin enabled, the plugin-provided `hermes pair` is available — when the
relay is running, its URL and a fresh pairing code are embedded in the QR
automatically.
:::

For persistent deployment, Docker, systemd, and TLS options, see the
[Relay Server docs](/reference/relay-server).

If you only saw an API-only QR earlier (because the relay wasn't running), just
start the relay and re-run `hermes pair` — the new QR will include the relay block.
::::

::: tip Multiple Hermes servers
The app can save more than one Hermes server, such as Home and Work. Add or
switch servers later in **Settings → Connections**.
:::

## Connecting from anywhere (Tailscale, VPN, public URL)

Hermes-Relay supports **multi-endpoint pairing**: one QR carries every network
path your server is reachable on, and the phone auto-picks whichever is reachable
at the moment — LAN, cell, tailnet, or public reverse proxy — without re-pairing
when you change networks.

- **Default — `--mode auto`.** `hermes pair --mode auto` (on the server) probes
  the LAN, detects Tailscale if it's running, and emits an ordered candidate list
  in the QR. Add `--public-url https://hermes.example.com` to include an external
  reverse-proxy or Cloudflare Tunnel URL.
- **Enable Tailscale on the server** with `hermes-relay-tailscale enable` — this
  fronts the loopback relay port `8767` and Hermes API port `8642` with
  `tailscale serve`, using Tailscale's managed TLS + tailnet ACLs. (Both ports
  matter: relay pairing covers terminal/bridge/control; chat and API-key voice
  use the Hermes API server.) Prefer a reverse proxy + Let's Encrypt or a
  self-hosted VPN? Both work identically as long as the phone can reach both
  services.
- **Force a mode at pair time** — `--mode` accepts `auto`, `lan`, `tailscale`, or
  `public`. `--prefer <role>` promotes a named role to priority 0 (e.g.
  `--prefer tailscale`).
- **Override per-session on the phone** — Settings → Connections → [active card] →
  **Show routes** → row menu → **Prefer this route**.

For the full matrix (Tailscale, Caddy + Let's Encrypt, Cloudflare Tunnel,
self-hosted WireGuard, plaintext over trusted VPN) with working config blocks,
see [Remote access](/guide/remote-access) and the
[Connections page](/features/connections#multi-endpoint-pairing-one-qr-for-every-network).

## Reference

::: details Dashboard login from Android (auth modes)
Manage uses the Hermes dashboard/admin server and stores dashboard cookies
separately from Relay pairing credentials.

- **Dashboard auth disabled / open dashboard:** Manage works as long as Android
  can reach the dashboard URL.
- **Basic username/password login:** supported. Android posts to
  `/auth/password-login` with the upstream `basic` provider, stores the dashboard
  cookies, and checks `/api/auth/me`.
- **Nous OAuth / OIDC redirect login:** supported. Android opens the dashboard's
  `/auth/login?provider=...` flow in an in-app WebView, imports the resulting
  cookies, checks `/api/auth/me`, and probes `/api/auth/ws-ticket`.
- **Custom password providers:** supported when `/api/auth/providers` advertises
  `supports_password: true`.

Relay pairing does not replace dashboard login, and dashboard login does not mint
an API key: it matches the Hermes Desktop remote-gateway path by authenticating
`/api/ws` and `/api/pty` with dashboard cookies plus a single-use ticket from
`/api/auth/ws-ticket`. API-key chat remains the fallback until Android's native
dashboard-gateway chat adapter is wired in.
:::

::: details Manual connection setup (no QR)
**During onboarding:**

1. On the **Connect** page, tap **Standard Hermes**.
2. Type your API server URL — e.g. `http://192.168.1.100:8642` — scan for Hermes
   on LAN, or scan a generic QR containing the API URL/key.
3. Enter the value you set in `API_SERVER_KEY` if the QR didn't include it.
4. Optional: enter a Tailscale API URL such as `https://your-host.ts.net:8642`.
5. Tap **Connect**.

**After onboarding:** open **Settings → Connections**. Each Hermes host is a card;
the active card expands inline to show status rows, route details, and an
**Advanced** section with manual API URL/key config, Relay URL override,
insecure-mode toggle, and the manual Relay pairing-code fallback. The per-card
**Pair Relay** / **Re-pair** button scans a Relay QR when you need power tools.

For Standard setup there is no built-in upstream mobile pairing command yet, so
use LAN scan, copy/paste, or a generic QR with the API URL/key. If a QR includes
a Relay block, Android shows the Relay pairing confirmation and TTL/grants picker;
if it's API-only, Android saves the standard API/dashboard connection.
:::

::: details Uninstall the Relay plugin
```bash
bash ~/.hermes/hermes-relay/uninstall.sh
# or, if the clone is already gone:
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/uninstall.sh | bash
```
Idempotent, and never touches state shared with other Hermes tools. Flags:
`--dry-run`, `--keep-clone`, `--remove-secret`.
:::

<style scoped>
.gs-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 1.25rem 0 2rem;
}
.gs-steps span {
  font-family: var(--vp-font-family-mono);
  font-size: 0.72rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--vp-c-text-2);
  background: var(--vp-c-bg-alt);
  border: 1px solid var(--vp-c-divider);
  border-radius: 999px;
  padding: 0.35rem 0.85rem;
}
.gs-install-cta {
  margin: 1.25rem 0 1.5rem;
}
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
