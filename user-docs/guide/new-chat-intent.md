---
title: Start a New Chat from a Widget or Shortcut
description: Launch Hermes-Relay straight into a new chat on a chosen gateway and agent profile
---

# Start a New Chat from a Widget or Shortcut

Hermes-Relay accepts an explicit Android intent that opens a **new, empty chat**
on a gateway and agent profile you choose, with the message box focused and the
keyboard up so you can type or dictate right away. Use it from a home-screen
widget, a launcher shortcut, or an automation app to get "new chat with
*profile*" in one tap.

The intent never sends anything. It only opens the draft; you still review and
send the message yourself.

## The intent

| Part | Value |
|------|-------|
| Action | `com.hermesandroid.relay.action.NEW_CHAT` |
| Target | `MainActivity` of the installed app (explicit component recommended) |
| Extra `com.hermesandroid.relay.extra.GATEWAY_URL` | Optional. The gateway's URL, for example `https://hermes.example.com`. Omit to use the active gateway. |
| Extra `com.hermesandroid.relay.extra.PROFILE` | Optional. The profile name, for example `research`. `default` selects the default profile. Omit to keep the current profile. |

Both extras must be strings. An extra that is present but empty or the wrong type
is rejected rather than ignored, so a typo can never open a chat on a different
agent.

### Example

```bash
adb shell am start \
  -n com.axiomlabs.hermesrelay.sideload/com.hermesandroid.relay.MainActivity \
  -a com.hermesandroid.relay.action.NEW_CHAT \
  --es com.hermesandroid.relay.extra.GATEWAY_URL "https://hermes.example.com" \
  --es com.hermesandroid.relay.extra.PROFILE "research"
```

Replace the package with `com.axiomlabs.hermesrelay` for the Google Play build
(`.sideload` and `.candidate` suffixes mark the other tracks).

In an app that builds a `PendingIntent` (for example a widget), create an
`Intent` with the same component, action, and string extras.

## How the gateway and profile are matched

- The gateway URL is compared with every URL saved for each connection (API,
  Relay, Dashboard, and route candidates) by **host**, plus **scheme and port**
  when you include them. Paths are ignored and `ws`/`wss` count as `http`/`https`.
  A bare host such as `hermes.example.com` matches any port or scheme.
- If no saved connection matches, or more than one does, nothing is created.
- The profile is matched by name against the gateway's profile list,
  ignoring case. `default` means the profile named `default` (or the one the
  gateway flags as its default).
- If the requested connection is not active, the app switches to it first and
  waits for its profiles to load.

## When it does nothing

The app shows a warning and does **not** open a chat when:

- the gateway is not configured on the device, or the URL matches several connections;
- the profile does not exist on that gateway (after waiting up to 15 seconds for
  the profile list), or the connection is locked to a different profile;
- [Supervised Mode](/guide/supervised-mode) is enabled;
- an extra is empty, oversized, or not a string.

A request that has not completed within 30 seconds is dropped. Intents delivered
when the app is restored from the Recents screen or recreated after a process
restart are ignored, so an old launch is never replayed.

## Security note

Any app on the device can send this intent. The worst it can do is open an empty
chat on a gateway and profile you already have configured; it cannot read data,
send a message, or add a gateway.
