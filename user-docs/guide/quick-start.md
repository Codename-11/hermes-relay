# Quick Start

Start with the standard upstream Dashboard/Gateway connection. Then pair Relay
when you want the additional tools and enhancements it provides.

<AndroidSetupPath mode="quick" />

::: tip Upstream first, Relay encouraged
Chat, sessions, Manage, sign-in, and standard voice use the unmodified Hermes
Dashboard/Gateway. The Relay plugin is an encouraged extension for Terminal and
TUI, notifications, desktop tools, Relay sessions, enhanced voice, optional
Device Control, and media compatibility or metadata. Ordinary inbound files use
current upstream Dashboard routes. When upstream Hermes provides a compatible
surface, Hermes-Relay prefers it instead of duplicating it.
:::

## Standard setup, then optional Relay enhancements

### 1. Install the Android app

For most people, install the Google Play build. It updates automatically and
includes the everyday Hermes experience plus Relay capabilities that do not
require Android Device Control.

<StoreBadge />

Choose the signed **Sideload** APK only when you also want Hermes to read and
operate the phone screen. [Compare the builds and verify the APK →](./getting-started#_1-install-the-app)

### 2. Start Hermes

The Dashboard/Gateway must be running and reachable from the phone. This is all
the server-side setup required for standard Chat, sessions, Manage, voice, and
inbound files:

```bash
hermes dashboard
```

### 3. Add the standard Hermes connection

In Android **Connect**, choose **Find Hermes on LAN**. If discovery cannot find
the host, choose **Enter your Hermes address** and enter the Dashboard URL you
open in a browser, normally `http://<host>:9119`. Sign in when prompted.

This creates a complete standard connection with no plugin or Relay URL. If the
Relay Dashboard page is already installed, its **Connect mobile app** action can
instead provide the same tokenless Dashboard-address setup by QR; that QR does
not pair Relay or contain a password, cookie, or API key.

### 4. Optional: install and pair Relay

For the recommended complete experience, install and start Relay on the Hermes
host after the standard connection works:

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

Use `--no-ssl` only on a trusted LAN or VPN. For away-from-home access,
[Tailscale is the recommended route](./remote-access). Refresh or restart the
Dashboard/Gateway plugin catalog after installation; a **Relay** page should
appear.

In the Web Dashboard's **Relay** page:

1. Click **Pair new device** and leave mode on **Auto** unless you need a
   specific route.
2. In Android, open **Settings → Connections → Pair Hermes Relay → Scan QR**.
3. Scan the one-time QR and confirm the routes and session duration.

The QR is single-use and normally advertises the Dashboard-origin Relay ingress,
plus any configured direct compatibility routes. It can carry LAN, Tailscale,
and public candidates so the phone can choose the best reachable route. Pairing unlocks Terminal/TUI,
notifications, Relay sessions, enhanced voice, desktop-tool handoff,
media enhancements, and—on the Sideload build—Device Control.

### 5. Confirm success and talk

You are ready when:

- **Chat · Ready** appears and the Chat header has a green connection pulse.
- **Manage** and **Voice** are ready, or show the one sign-in action they need.
- **Relay · Paired** appears when you completed step 4.

Open Chat and send a message. Pairing Relay is encouraged for the complete
experience, but an unavailable Relay must not block upstream Chat, Manage, or
standard voice.

## Other supported paths

::: details Generate the QR from a terminal
On the Hermes host, run:

```bash
hermes pair
```

The command prints connection details, a scannable one-time QR, a PNG path, and
a pasteable `hermes-relay://pair?...` invite. Scan the QR from Android. This is
the same pairing contract used by the Web Dashboard.
:::

::: details No camera or QR available
- **Standard connection:** enter the Dashboard address manually in Android.
- **Relay code:** create **Pair new device** in the Web Dashboard, then use
  Android's **Enter a Relay pairing code** path with the shown URL and code.
- **Phone-generated code:** choose **Show Relay code** in Android, run the shown
  `hermes pair --register-code <code>` command on the host, then tap **Connect**.
:::

[Detailed Android setup and security notes →](./getting-started) ·
[Dashboard and Desktop plugin pairing →](../features/dashboard) ·
[Troubleshooting →](./troubleshooting)
