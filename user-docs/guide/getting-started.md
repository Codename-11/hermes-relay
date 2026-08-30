# Installation & Setup

This is the detailed reference for choosing a build, preparing a Hermes host,
connecting without QR, remote access, and security checks. If your Hermes
Dashboard already runs and your phone can reach it, the [Quick Start](./quick-start)
is the shorter recommended path.

<AndroidSetupPath mode="reference" />

## 1. Install the app

Choose the build by one question: do you want Hermes to operate the phone, or
only be available from it? Both builds come from the same codebase and can live
side-by-side.

<ReleaseTrackChooser />

The [Release tracks](/guide/release-tracks) page explains the full capability
and safety differences. Building from source is an advanced alternative under
the Sideload instructions below.

### Sideload APK {#sideload-apk}

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
Play pins to the app, compare this fingerprint:

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

Hermes-Relay can use two upstream Hermes surfaces:

- **Dashboard/Gateway** on `:9119` — primary Chat, sessions, sign-in, Manage, and standard voice
- **API server** on `:8642` — optional Chat fallback and advanced headless compatibility

::: tip Already have a Hermes server — or someone set one up for you?
If Hermes is already running, or a more technical friend handed you its
**Dashboard/Gateway address**, you're done with this step — skip straight to
[step 3 (Connect)](#_3-connect-chat). Everything below is only for setting up the
Hermes server itself the first time.
:::

You'll need a reachable current [Hermes Agent](https://hermes-agent.nousresearch.com)
instance with the Dashboard/Gateway enabled. The optional API server is a
fallback or headless compatibility surface. The Relay power-user plugin
(step 4) additionally needs Python 3.11+ on the server.

::::details Advanced: add the optional API fallback
The standard app path does not require the API server or an API key. Enable this
surface when you want automatic SSE fallback or an API-only headless
configuration. Installing Hermes and choosing a provider/model is ordinary
Hermes setup, so we defer that to the official docs
([Installation](https://hermes-agent.nousresearch.com/docs/getting-started/installation),
[Nous Portal](https://hermes-agent.nousresearch.com/docs/integrations/nous-portal),
[API Server](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server)).
The block below is just the app-facing minimum.

If Hermes is already installed and a provider is configured, skip the install and
`hermes setup --portal` lines and run only the `.env` + `hermes gateway` part.

::: code-group
```bash [macOS / Linux / WSL2 / Termux]
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
hermes setup --portal                      # log in / pick a provider — skip if already configured

# You, the server operator, create API_SERVER_KEY for this optional fallback.
# openssl generates a strong random value; Hermes Dashboard does not supply one.
# Current Hermes requires a usable key of at least 16 characters when the API
# server is enabled.
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
hermes setup --portal                      # log in / pick a provider — skip if already configured

# You, the server operator, create this key for the optional API fallback.
# Hermes Dashboard does not supply an API_SERVER_KEY.
# Current Hermes requires a usable key of at least 16 characters when the API
# server is enabled.
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

What each line does: `API_SERVER_ENABLED=true` turns the API server on (it's off by
default); `API_SERVER_HOST=0.0.0.0` makes it reachable on your network (Hermes
defaults to `127.0.0.1`, which only the host itself can reach); `API_SERVER_PORT`
is the port the app assumes; `API_SERVER_KEY` is the required bearer token the
app sends on every request when this optional server is enabled. Replace
`<this-computer-ip>` with the address your phone can reach — a LAN
IP, Tailscale name, or HTTPS reverse-proxy host. Don't use `127.0.0.1` from Android
unless Hermes is running on the phone itself.

::: warning Binding to 0.0.0.0 exposes the API to your whole network
`0.0.0.0` lets any device on the same network reach the API server, which is why
the bearer key matters. On a home LAN behind a router that's normally fine. On
untrusted or public networks, don't expose it directly — keep the key set and
front it with Tailscale or an HTTPS reverse proxy (see [Remote access](/guide/remote-access)).
To limit it to a single interface, set `API_SERVER_HOST` to a specific LAN IP
instead of `0.0.0.0`.
:::

::: tip The key is yours to choose — and you don't have to thumb-type it
Three easy ways to get the key onto your phone (no 64-character typing required):
- **Scan for Hermes on LAN** in the app finds the server automatically; you enter
  the key once.
- Ask your **Hermes agent to generate a QR code** containing the API URL and key —
  for example a QR encoding `{"api_url":"http://<this-computer-ip>:8642","api_key":"<your-key>"}` —
  then scan it from **Scan setup QR**.
- Type it by hand. A memorable passphrase is easier to type; a random key is
  stronger. Either works — and if your server runs with no key at all, leave the
  app's key field blank.
:::
::::

**Standard setup — enable the Dashboard/Gateway.** This one upstream surface
provides primary Chat, sessions, Manage, authentication, and standard voice.

::::details Enable Manage (Skills, Cron, Models, Keys) — run the dashboard
Run the Hermes dashboard on a phone-reachable URL. Because your
phone reaches it on a non-loopback address, the dashboard **requires auth** — it
won't start on `0.0.0.0` without a provider configured — so set credentials
*first*, then start it. On a trusted LAN or VPN, username/password is the quick
path:

::: code-group
```bash [macOS / Linux]
# Run on the Hermes host. Replace choose-a-strong-password with your own.
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
# Set the same HERMES_DASHBOARD_* values in $HOME\.hermes\.env (use your own password), then:
hermes dashboard --no-open --host 0.0.0.0 --port 9119
```
:::

You sign in with this username/password during connection setup or from the
app's sign-in screen. The same session authorizes Gateway chat, sessions, Manage,
and standard voice. For stronger setups Hermes also accepts a hashed password
(`HERMES_DASHBOARD_BASIC_AUTH_PASSWORD_HASH`) instead of plaintext, and for a
public or hosted dashboard you should use Nous OAuth or self-hosted OIDC rather
than a password — see the upstream
[Web Dashboard](https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard) docs.
On the host's own loopback the dashboard runs without auth; the credentials above
are needed only because your phone connects over the network. (The dashboard also
reads and writes `~/.hermes/.env`, which holds your keys and secrets.)

::: warning Dashboard auth and API bearer auth are different
Dashboard sign-in on `:9119` uses a native bearer on current gateways, or
exact-origin cookies on compatibility gateways, plus short-lived `/api/ws`
tickets. It is sufficient for the standard connection. An API key authenticates
only the optional API fallback on `:8642`; dashboard login does not create one,
and you should not enter a fake key when no API endpoint is configured.
:::
::::

## 3. Connect & chat

On first launch:

1. Tap through the onboarding pages.
2. On **Connect**, pick whichever is easiest:
   - **Hermes** → discover the server or type the Dashboard/Gateway URL
     (`http://192.168.1.100:9119`), then sign in when prompted.
   - **Scan setup QR** → scan a current payload with an explicit
     Dashboard/Gateway URL. Existing API-first QRs remain accepted for legacy
     and headless configurations.
3. Optional: add API fallback, Relay, or remote routes under **Advanced**.
4. Tap **Connect**.
5. On **Finish setup**, enable Android notifications if you want background
   chat alerts. Camera, microphone, notification companion, and Device Control
   permissions remain optional and can be reviewed one at a time. Choose
   **Not now** to keep those features off and start chatting.

That's it — Chat is live, and the Manage surfaces (Skills, Cron, MCP, Profiles,
Models/Config) light up too. Manage may ask you to sign in to the dashboard the
first time; that same sign-in also unlocks voice for the connection. **Relay
pairing is not required for any of this.**

::: tip Home and away on one connection
Save both a LAN Dashboard URL and a Tailscale Dashboard URL — for example
`http://100.x.y.z:9119` or a separately published `https://host.ts.net` URL —
and Android probes them on every connect using the highest-priority reachable
one. These are Dashboard routes, so they do not require an API endpoint or
`API_SERVER_KEY`. Chat and Manage move together — LAN at home, Tailscale when
you leave.
:::

### What you’ll see

The documentation uses deterministic renders from the real Android components,
so these screens update with the canonical screenshot set instead of drifting
like a hand-recorded demo.

<FirstRunPreview />

The chat header shows the agent name with a green pulse on the avatar when the
Dashboard/Gateway chat route is ready. If the dot is red:

- Is the Hermes Dashboard/Gateway running? (`hermes dashboard`)
- Can your phone reach the server? (same network, firewall rules)
- Is the URL correct? (include the port, e.g. `:9119`)

More: [Troubleshooting](/guide/troubleshooting) · [Chat guide](/guide/chat) ·
[Connections](/features/connections).

## 4. Recommended — complete the setup with Relay {#relay-server-optional}

The unmodified Hermes Dashboard/Gateway remains authoritative for Chat,
sessions, Manage, sign-in, and standard voice. The Relay plugin is an encouraged
extension for capabilities upstream Hermes does not yet expose: Terminal/TUI,
notifications, media handoff, desktop tools, Relay sessions, enhanced voice,
and optional Device Control.

Hermes-Relay follows an upstream-first rule: when upstream Hermes ships a
compatible capability, the standard path should move there and Relay should
stop duplicating it. Relay can be unavailable without blocking the upstream
connection, but pairing it provides the intended full product experience today.

### Install the server plugin {#install-the-server-plugin}

On the Hermes host:

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

`hermes pair` and `hermes relay` are supplied by the plugin through upstream
Hermes' plugin CLI support; they are not Hermes core commands. Use `--no-ssl`
only on a trusted LAN or VPN.

### Recommended: pair from the Hermes Web Dashboard

Restart or refresh the Dashboard/Gateway after installing the plugin, then:

1. Open the Hermes Web Dashboard and select **Relay**.
2. If the phone does not have its standard connection yet, click **Connect
   mobile app** and scan that tokenless QR from Android **Connect → Scan Hermes
   setup QR**. It contains only the Dashboard address.
3. Click **Pair new device**. Leave mode on **Auto** for the usual LAN plus
   configured remote candidates.
4. In Android, open **Settings → Connections → Pair Hermes Relay → Scan QR** and
   scan the one-time invite.

These are deliberately separate actions: **Connect mobile app** configures the
upstream Dashboard/Gateway connection; **Pair new device** grants a Relay
session. Neither converts Dashboard credentials into Relay credentials.

The Dashboard also shows the one-time code and a copyable invite for clients
without a camera. Pairing codes expire and are single-use; mint a fresh invite
instead of retrying a consumed code.

### Alternative: generate the same QR from a terminal

```bash
hermes pair
```

The command prints a text receipt, a terminal QR, a PNG path, and a pasteable
`hermes-relay://pair?...` invite. Scan the QR from Android. This uses the same
signed pairing contract as the Web Dashboard.

### Manual fallback when QR scanning is unavailable

- In Android, choose **Enter a Relay pairing code** and enter the Relay URL plus
  the code shown by the Dashboard or `hermes pair`.
- Or choose **Show Relay code** in Android, run the displayed
  `hermes pair --register-code <code>` command on the host, then tap **Connect**.

Keep manual URL, port, TLS, API fallback, and route-priority overrides under the
advanced path. The Dashboard's **Auto** pairing mode and the app's confirmed QR
receipt should be the default.

::::details Legacy installer and compatibility-only options

Use the legacy installer only when you also want the systemd user service, shell
shims, external skill-path registration, and the old clone/update workflow:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

The optional compatibility monkeypatch is separate from normal Relay pairing.
Modern Vanilla Hermes chat, Manage, and dashboard voice do not need it. Check it with
`hermes relay compat status`; install it only for older Hermes builds or
compatibility-only route gaps:

```bash
hermes relay compat status
hermes relay compat install
hermes relay compat remove
```

For persistent deployment, Docker, systemd, and TLS options, see the
[Relay Server docs](/reference/relay-server).
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
- **Use the helper's detected Tailscale HTTPS address** —
  `hermes-relay-tailscale enable` publishes `https://host.ts.net:10443` on a
  dedicated tailnet listener and proxies local Dashboard `:9119` plus its
  same-origin Relay ingress. The dedicated port avoids conflicts with an
  existing Traefik, Caddy, or nginx HTTPS listener on `:443`.
  A raw `http://100.x.y.z:9119` address remains valid when Dashboard is
  deliberately reachable there, but it has no application TLS. Prefer a reverse proxy + Let's Encrypt or a
  self-hosted VPN? Both work as long as the phone can reach each capability you
  configured.
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
Manage uses the Hermes dashboard/admin server and stores its native bearer or
compatibility cookies separately from Relay pairing credentials.

- **Dashboard auth disabled / open dashboard:** Manage works as long as Android
  can reach the dashboard URL.
- **Basic username/password login:** supported. Current gateways broker it in the
  system browser when `native_pkce` is advertised. Compatibility gateways post
  to `/auth/password-login` and store exact-origin Dashboard cookies.
- **Nous OAuth / OIDC redirect login:** supported. When the Dashboard advertises
  `native_pkce`, Android uses the upstream system-browser `/auth/native/*`
  broker. Older gateways use the in-app `/auth/login?provider=...` cookie
  compatibility flow. Both verify `/api/auth/me` and `/api/auth/ws-ticket`.
- **Private app route with public OIDC callback:** supported. Android may connect
  over LAN or Tailscale while the identity provider returns to an HTTPS public
  Dashboard origin. Register `<public-dashboard-origin>/auth/callback` with the
  provider. Hermes normally derives the origin from trusted proxy headers; set
  upstream `dashboard.public_url` / `HERMES_DASHBOARD_PUBLIC_URL` only when that
  reconstruction is unreliable. Native PKCE uses that origin for its browser
  transaction only. On the older cookie flow Android verifies the installation
  and asks before saving a different authenticated origin. You do not enter a
  second sign-in URL during normal setup.
- **Custom password providers:** supported when `/api/auth/providers` advertises
  `supports_password: true`.

Relay pairing does not replace dashboard login, and dashboard login does not mint
an API key: it matches the Hermes Desktop remote-gateway path by authenticating
`/api/ws` and `/api/pty` with the Dashboard session plus a single-use ticket from
`/api/auth/ws-ticket`. Android uses that gateway path when it is ready and falls
back to API-server SSE when it is not.
:::

::: details Manual connection setup (no QR)
**During onboarding:**

1. On the **Connect** page, tap **Hermes**.
2. Type your Dashboard/Gateway URL — e.g. `http://192.168.1.100:9119` — or discover it on LAN.
3. Sign in through the dashboard's configured provider when prompted.
4. Optional: expand **Advanced** to add an API fallback URL/key or Relay route.
5. Tap **Connect**.

**After onboarding:** open **Settings → Gateways** and select a Hermes host.
**Advanced** contains only compatibility controls: optional direct API URL/key,
an explicit direct Relay endpoint override, and the insecure-development toggle.
Use **Pair Relay** / **Re-pair** for the shared QR, enter-code, or show-code flow.
Edit the normal Dashboard/Gateway address and network paths under **Routes**.

For Vanilla Hermes setup, use discovery or enter the Dashboard/Gateway address.
If a QR includes a Relay block, Android shows the Relay pairing confirmation and
TTL/grants picker. Legacy API-only QRs still create an advanced compatibility
connection.
:::

::: details Uninstall the Relay plugin
If you used the upstream plugin manager:

```bash
hermes relay compat remove --all   # optional; only removes legacy compat hooks
hermes plugins remove hermes-relay
```

If you used the legacy installer:

```bash
bash ~/.hermes/hermes-relay/uninstall.sh
# or, if the clone is already gone:
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/uninstall.sh | bash
```
It removes the legacy systemd service, shell shims, editable package, external
skill path, clone, and compat hook. It is idempotent and never touches state
shared with other Hermes tools. Flags: `--dry-run`, `--keep-clone`,
`--remove-secret`. For agent-assisted cleanup, use the
[Agent Cleanup Prompt](/reference/agent-cleanup-prompt).
:::

<style scoped>
.gs-steps {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  margin: 1.25rem 0 2rem;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  background: var(--vp-c-divider);
  list-style: none;
}
.gs-steps li {
  display: grid;
  grid-template-columns: 30px 1fr;
  gap: 8px;
  align-items: center;
  margin: 0;
  padding: 11px 12px;
  background: var(--vp-c-bg-alt);
  color: var(--vp-c-text-2);
}
.gs-steps strong {
  font-family: var(--vp-font-family-mono);
  font-size: 0.66rem;
  color: var(--vp-c-brand-1);
}
.gs-steps span {
  font-family: var(--vp-font-family-mono);
  font-size: 0.68rem;
  letter-spacing: 0.03em;
  text-transform: uppercase;
}
@media (max-width: 640px) {
  .gs-steps {
    grid-template-columns: 1fr;
  }
}
</style>
