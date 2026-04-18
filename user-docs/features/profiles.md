# Profiles

A **profile** in Hermes-Relay is an upstream-Hermes agent directory — an isolated Hermes instance on your server with its own config, model, and identity. When you pair with a server, the phone auto-discovers every profile on that server and exposes them in the chat top bar.

## The three layers

| Layer | What it picks | Scope | Where it lives |
|---|---|---|---|
| **Connection** | Which Hermes server | One pairing per server | Top-of-screen chip (when you have ≥2 connections) |
| **Profile** | Which agent *on that server* | Per chat turn, clears on Connection switch | Chat top-bar chip (left of Personality) |
| **Personality** | Which system-prompt preset | Per chat turn | Chat top-bar chip (right of Profile) |

Pick from left to right: the server, then the agent on that server, then the persona.

## Where profiles come from

Upstream Hermes profiles live at `~/.hermes/profiles/<name>/` — each is a full, isolated Hermes environment with:

- Its own `config.yaml` (model, personalities, provider keys, everything)
- Its own `.env` (API credentials)
- Its own `SOUL.md` (the profile's identity / system prompt)
- Its own sessions, memory, skills, cron jobs, state database

See the upstream docs: `hermes profile create`, `hermes profile use`, `hermes -p <name> <command>` — each profile gets its own CLI alias.

Create them with:

```bash
hermes profile create mizu
mizu setup
```

Or clone from an existing profile:

```bash
hermes profile create coder --clone
```

The phone doesn't create profiles — you do that on the server. The phone just picks them up on the next pairing (or the next `auth.ok` round-trip after a relay restart).

## What "switching profile" does on the phone

On chat send with a profile selected, the phone:

1. Replaces the request's `model` field with the profile's `model.default`.
2. Replaces the request's `system_message` with the profile's `SOUL.md` content (if the profile has a SOUL — some don't).

So you get the profile's **model + persona** applied to the turn. The response comes back from the same gateway you're paired with, using whatever Victor-or-whoever's default provider keys are configured.

## What switching profile DOES NOT do

This is important and subtle.

- **No memory isolation.** The profile's memory DB isn't loaded — the Connection's default memory is used.
- **No session isolation.** Sessions you see in the sessions list are the Connection's sessions, not the profile's.
- **No separate API keys.** The profile's `.env` is ignored by this overlay — the Connection's `.env` provides keys.
- **No separate skills / cron jobs.** Those are instance-scoped and need the profile's own gateway to be active.

**If you want true profile isolation,** run that profile's gateway as its own service on its own port:

```bash
hermes -p mizu platform start api --port 8643
```

Then add that gateway as a separate **Connection** on the phone (pair with it like a new server). Each Connection has its own sessions, memory, and state — because each Connection *is* a distinct gateway.

## Picker behaviour

- **Hidden when empty.** If the server has no `~/.hermes/profiles/*/` entries (and just the default root config), the picker chip doesn't render.
- **"Default"** option at the top of the dropdown. Selecting it clears the override and uses the server's `config.yaml/model.default`.
- **Disabled mid-stream.** You can't switch profile during an in-flight chat turn.
- **Ephemeral.** Selection resets on app restart and on Connection switch. Persisting per-Connection is on the roadmap.

## Disabling discovery on the server

If you want Connections-only semantics (for example, a minimal deployment where every profile is its own Connection), set this in the relay's config:

```yaml
relay:
  profile_discovery_enabled: false
```

Restart the relay; the picker stays hidden and the app treats the install as single-agent.

## Profile + Personality interaction

If you select a profile AND a personality, the **profile wins** — its `SOUL.md` is sent as `system_message`, not the personality's prompt. That's a deliberate choice: a profile is a richer concept (whole identity), and picking both implies you want the profile's full persona. Pick one at a time to keep behaviour obvious.

## At a glance

- **Connection** = a whole Hermes server.
- **Profile** = a named agent *on* that server, discovered from `~/.hermes/profiles/`. Picking one overlays its model + SOUL for chat turns.
- **Personality** = a system-prompt preset *within* the agent's config.

See [Connections](./connections.md) for the server-level concept and [Personalities](./personalities.md) for the preset-prompt layer.
