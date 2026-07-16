# Quick Start

Install → connect → talk, in about two minutes. This page is deliberately
short: every step links to a detail page if you want the full story, and
nothing here requires the Relay plugin — a vanilla Hermes install is enough.

## 1. Install the app

For most people, the fastest path is the Google Play build. It installs in one
tap, updates automatically, and includes Chat, Voice, Manage, and the optional
Relay-powered features that do not require phone control.

<StoreBadge />

Need Hermes to read, tap, type, or navigate on your phone? Install the signed
**Sideload** APK instead. [Compare the two builds and install either one →](./getting-started#_1-install-the-app)

## 2. Have Hermes running

You need a reachable [hermes-agent](https://github.com/NousResearch/hermes-agent)
with its API server enabled. If yours isn't running yet,
[Installation & Setup](./getting-started) has the copy/paste commands for the
host machine.

## 3. Connect

Open the app and swipe through to **Connect**. Enter your server's address —
`http://<host>:8642` — and the API key configured on that Hermes host. If the
server intentionally runs without `API_SERVER_KEY`, leave the key field blank.
The wizard probes everything for you, finishing with a capability card:

> Don't want to type it? Tap **Scan for Hermes on LAN** to auto-find the server,
> or use **Scan setup QR** — you can even ask your Hermes agent to generate a QR
> with your URL and key. Full host setup is in [Installation & Setup](./getting-started).

| Line | What it means |
|---|---|
| **Chat** | API server reachable — you can talk |
| **Manage** | Dashboard found — skills, models, keys, profiles from the phone |
| **Voice** | Speech ready via your server (or one sign-in away) |
| **Relay** | Optional power tools — fine to leave unpaired |

## 4. Sign in to Manage (only if asked)

If your dashboard requires sign-in, do it once under the **Manage** tab.
That same session also unlocks voice for the connection.

## 5. Talk

Type a message, or tap the mic and speak. That's the whole Vanilla Hermes setup.

::: tip You are connected when…
The capability card shows **Chat · Ready** and the Chat header carries a green
connection pulse. **Manage**, **Voice**, and **Relay** may still show optional or
sign-in states without blocking your first message.
:::

[See the exact first-run screens and detailed setup →](./getting-started#_3-connect-chat)

::: details Want more? Power tools via Relay
Pairing the optional [Hermes-Relay plugin](./getting-started#relay-server-optional)
adds Terminal (a real tmux on your server), Bridge device control (sideload
builds), realtime provider-native voice, and per-profile voice providers.
Everything above keeps working without it.
:::
