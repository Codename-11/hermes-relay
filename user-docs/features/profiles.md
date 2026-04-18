# Profiles

A **profile** in Hermes-Relay is a saved connection to a Hermes server. Add multiple profiles and switch between them with one tap — for example, a home server and a work server, or a dev instance and a production one.

## What a profile contains

Each profile stores everything needed to talk to one Hermes install:

- API server URL (`http://host:8642`) and relay URL (`wss://host:8767`)
- Its own pairing record — session token, device ID, optional API key
- Its own sessions, memory, personalities, and skill list (fetched from that server)
- Last-active session ID, so switching back takes you where you left off

Profiles do **not** share sessions, memory, or personalities. Each Hermes server is a separate world. What *is* shared across profiles: the app theme and settings, bridge safety preferences (blocklist, destructive-verb confirmation, auto-disable), and the device's TOFU cert-pin record for each host.

## Switching profile

Tap the profile chip in the top bar (next to the personality chip). A bottom sheet shows all your profiles with a health indicator for each. Tap one to switch.

On switch, the app:

1. Cancels any in-flight chat stream.
2. Disconnects from the current relay.
3. Rebinds to the selected profile's endpoints.
4. Reconnects and reloads sessions, personalities, and skills from the new server.
5. Restores the last-active session on that profile, if there was one.

The whole thing takes under a second on a healthy connection.

## Managing profiles

Open **Settings → Profiles**. Each card shows the profile's label, hostname, pairing status, and last-seen timestamp. From the card you can:

- **Rename** — tap the label to edit inline. The default is the server's hostname; change it to whatever makes sense ("Home", "Work", "Lab NAS").
- **Re-pair** — if the session token expires or the server's pairing state was wiped, re-scan the QR code. This reuses the same onboarding flow but keeps the profile's ID and label.
- **Revoke** — server-side logout. The token is invalidated on the server; the profile stays in the app but is marked unpaired.
- **Remove** — deletes the profile and its stored auth material. The TOFU cert pin for the server's host survives, so if you re-add the same server later, it's still trusted without a re-verify.

Tap **Add profile** to create a new one. This launches the standard QR pairing flow (same as first-time setup). After pairing, the profile is saved with the server's hostname as its default label.

## How this differs from personalities

- **Personality** = a system prompt preset *on* one agent. Switching a personality just changes what the agent behaves like on the next message. Memory, sessions, tools, and model are unchanged.
- **Profile** = a whole different Hermes server. Switching a profile changes *everything* — the conversation history, the agent's memory, the personalities that are even available to pick from.

The profile chip is to the left of the personality chip in the top bar, reflecting this hierarchy: pick the server first, then pick the personality *on* that server.

## Things that stay the same across profiles

- **Bridge safety preferences.** The blocklist, destructive-verb confirmation, and auto-disable timer apply to whichever server is active. This is intentional — the safety model is about your phone and your risk tolerance, not which server you're talking to. If this ever becomes a problem, let us know.
- **Theme + app settings** — your preferences aren't tied to a server.
- **TOFU cert pins** — pinned per `host:port`, so two profiles on the same server share the pin.

## Migrating from single-profile

If you upgrade from an earlier version, your existing paired device becomes your first profile automatically. No re-pair, no re-scan, no interruption. The profile gets your server's hostname as its label — rename it in Settings → Profiles whenever you like.
