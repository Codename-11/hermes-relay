# Connections

A **connection** in Hermes-Relay is a saved link to a Hermes server. Add multiple connections and switch between them with one tap — for example, a home server and a work server, or a dev instance and a production one.

## What a connection contains

Each connection stores everything needed to talk to one Hermes install:

- API server URL (`http://host:8642`) and relay URL (`wss://host:8767`)
- Its own pairing record — session token, device ID, optional API key
- Its own sessions, memory, personalities, and skill list (fetched from that server)
- Last-active session ID, so switching back takes you where you left off

Connections do **not** share sessions, memory, or personalities. Each Hermes server is a separate world. What *is* shared across connections: the app theme and settings, bridge safety preferences (blocklist, destructive-verb confirmation, auto-disable), and the device's TOFU cert-pin record for each host.

## Switching connection

Tap the connection chip in the top bar (left side, next to the agent name). A bottom sheet shows all your connections with a health indicator for each. Tap one to switch. The chip hides automatically if you only have one connection — nothing to switch between.

On switch, the app:

1. Cancels any in-flight chat stream.
2. Disconnects from the current relay.
3. Rebinds to the selected connection's endpoints.
4. Reconnects and reloads sessions, personalities, and skills from the new server.
5. Restores the last-active session on that connection, if there was one.

The whole thing takes under a second on a healthy connection.

## Managing connections

Open **Settings → Connections**. Each card shows the connection's label, hostname, pairing status, and last-seen timestamp. From the card you can:

- **Rename** — tap the label to edit inline. The default is the server's hostname; change it to whatever makes sense ("Home", "Work", "Lab NAS").
- **Re-pair** — if the session token expires or the server's pairing state was wiped, re-scan the QR code. This reuses the same onboarding flow but keeps the connection's ID and label.
- **Revoke** — server-side logout. The token is invalidated on the server; the connection stays in the app but is marked unpaired.
- **Remove** — deletes the connection and its stored auth material. The TOFU cert pin for the server's host survives, so if you re-add the same server later, it's still trusted without a re-verify.

Tap **Add connection** to create a new one. This launches the standard QR pairing flow (same as first-time setup). After pairing, the connection is saved with the server's hostname as its default label.

## How this differs from personalities and profiles

- **Personality** = a system prompt preset *on* one agent. Switching a personality just changes what the agent behaves like on the next message. Memory, sessions, tools, and model are unchanged.
- **Profile** = an upstream-Hermes agent directory on the server (`~/.hermes/profiles/<name>/`). Selecting a profile overlays its model + `SOUL.md` for chat turns. See [Profiles](./profiles.md).
- **Connection** = a whole different Hermes server. Switching a connection changes *everything* — the conversation history, the agent's memory, the personalities and profiles that are even available to pick from.

The Connection chip lives on the left of the Chat top bar. Profile and Personality selection both live in the **agent sheet** — tap the agent name in the middle of the top bar to open it. That order reflects the hierarchy: pick the server first, then pick the profile and personality *on* that server.

## Things that stay the same across connections

- **Bridge safety preferences.** The blocklist, destructive-verb confirmation, and auto-disable timer apply to whichever server is active. This is intentional — the safety model is about your phone and your risk tolerance, not which server you're talking to. If this ever becomes a problem, let us know.
- **Theme + app settings** — your preferences aren't tied to a server.
- **TOFU cert pins** — pinned per `host:port`, so two connections on the same server share the pin.

## Migrating from single-connection

If you upgrade from an earlier version, your existing paired device becomes your first connection automatically. No re-pair, no re-scan, no interruption. The connection gets your server's hostname as its label — rename it in Settings → Connections whenever you like.
