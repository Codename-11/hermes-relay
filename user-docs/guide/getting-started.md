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

Download the latest APK from [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases/latest), or wait for the Play Store listing. See [Sideload APK](#sideload-apk) below for step-by-step install and integrity-verification instructions.

### 2. Install the server plugin

On the machine running your Hermes agent:

```bash
curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash
```

This installs the `hermes-relay` plugin into `~/.hermes/plugins/hermes-relay` and pulls in its Python dependencies (`requests`, `aiohttp`, `segno`). Restart hermes to load it.

::: tip What you get
The plugin registers **14 `android_*` device control tools** (tap, type, read screen, screenshot, open apps, etc.) plus the **`hermes pair` CLI command** for generating pairing QR codes. No separate skill install, no `qrencode` binary needed.
:::

### 3. Pair your phone

You have two paths to the same QR — pick whichever fits where you already are:

**From an active Hermes session** (shortest path if you're already chatting with Hermes): type `/hermes-relay-pair` in any chat surface — CLI, Discord, Telegram, anywhere Hermes is listening. The agent generates the QR and renders it inline for you. No shell required.

**From a shell** (power-user / scriptable): on the server, run

```bash
hermes pair
```

Both routes share the same implementation and produce the same payload. This prints a QR code **and** the plain-text connection details (server URL, API key). Scan the QR from the app's onboarding screen — or type the values in manually if your terminal can't render QR blocks. The text fallback is always shown, so this works inside Hermes's Rich TUI panel and over SSH with limited charsets.

**One scan configures chat *and* the relay.** If you've already started the Hermes-Relay WSS server on the same host (see [Relay Server](#relay-server-optional) below), `hermes pair` automatically detects it at `localhost:8767`, mints a fresh 6-char pairing code, pre-registers the code with the relay via its loopback-only `/pairing/register` endpoint, and embeds the relay URL and code in the same QR. The phone scans once and is ready for chat, terminal, and bridge.

If the relay isn't running, `hermes pair` prints an `[info]` line pointing at `hermes relay start` and renders an API-only QR — chat still works, and you can pair with the relay later once it's up. You can also force API-only mode explicitly:

```bash
hermes pair --no-relay
```

::: warning Security
The QR contains credentials — your API key if one is set, and the relay pairing code if a relay block was embedded. Don't screenshot or share it. The relay code is one-shot and expires in 10 minutes, but the API key is long-lived.
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
If you're running Hermes on the same machine (or connecting via `localhost`), you can leave `API_SERVER_KEY` unset. The key is only needed when exposing the API server over the network. If you do set one, `hermes pair` reads it automatically.
:::

## Sideload APK

If you'd rather not use Google Play, you can install the signed APK directly from GitHub Releases. This works on any Android 8.0+ device.

### 1. Download the APK

Head to [github.com/Codename-11/hermes-relay/releases/latest](https://github.com/Codename-11/hermes-relay/releases/latest) and grab **`app-release.apk`** from the assets list.

::: warning Download the .apk, not the .aab
Each release also ships an `app-release.aab` file. That's the Android App Bundle format Google Play uses internally — it **won't install directly** on your device. Always pick `app-release.apk` for sideloading.
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
sha256sum app-release.apk
# Compare the output against the matching line in SHA256SUMS.txt
```

**Windows PowerShell:**

```powershell
Get-FileHash -Algorithm SHA256 app-release.apk
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
keytool -printcert -jarfile app-release.apk
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

**After onboarding:** open **Settings → Connection**. The top card (**Pair with your server**) shows a **Scan Pairing QR** button and a status summary for the API server, relay, and session. To enter values by hand, expand the **Manual configuration** card below it — API Server URL, API Key, Relay URL, and Insecure Mode live there, along with **Save & Test**.

The `hermes pair` command always prints these same values as plain text alongside the QR code, so you can copy them directly.

## Relay Server (Optional)

The relay server is only needed for **Terminal** (remote shell) and **Bridge** (agent-driven phone control). Chat works without it.

::: tip Start the relay
```bash
# If you installed the hermes-relay plugin (recommended):
hermes relay start --no-ssl

# Or directly from a repo checkout:
python -m plugin.relay --no-ssl
```
Run this on the same machine as hermes-agent. If the relay is running when you execute `hermes pair`, its URL and a freshly-registered pairing code are automatically embedded in the QR — you don't need to enter anything in the app.
:::

For persistent deployment, Docker, systemd, and TLS options, see the [Relay Server docs](/reference/relay-server).

If you only saw an API-only QR earlier (because the relay wasn't running), just start the relay and re-run `hermes pair` — the new QR will include the relay block.

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
