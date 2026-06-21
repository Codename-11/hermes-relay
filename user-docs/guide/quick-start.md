# Quick Start

Install → connect → talk, in about two minutes. This page is deliberately
short: every step links to a detail page if you want the full story, and
nothing here requires the Relay plugin — a vanilla Hermes install is enough.

## 1. Install the app

Grab the latest APK from the
[releases page](https://github.com/Codename-11/hermes-relay/releases) and
install it. Play-store, integrity-verification, and build-from-source options
live in [Installation & Setup](./getting-started).

## 2. Have Hermes running

You need a reachable [hermes-agent](https://github.com/NousResearch/hermes-agent)
with its API server enabled. If yours isn't running yet,
[Installation & Setup](./getting-started) has the copy/paste commands for the
host machine.

## 3. Connect

Open the app and swipe through to **Connect**. Enter your server's address —
`http://<host>:8642` — and your API key, and the wizard probes everything for
you, finishing with a capability card:

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

::: details Want more? Power tools via Relay
Pairing the optional [Hermes-Relay plugin](./getting-started#relay-server-optional)
adds Terminal (a real tmux on your server), Bridge device control (sideload
builds), realtime provider-native voice, and per-profile voice providers.
Everything above keeps working without it.
:::
