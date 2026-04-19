# Profiles

A **profile** in Hermes-Relay is an upstream-Hermes agent directory — an isolated Hermes instance on your server with its own config, model, and identity. When you pair with a server, the phone auto-discovers every profile on that server and exposes them in the **agent sheet** — a bottom sheet that opens when you tap the agent name in the Chat top bar.

## The three layers

| Layer | What it picks | Scope | Where it lives |
|---|---|---|---|
| **Connection** | Which Hermes server | One pairing per server | Top-bar chip on the left (hidden with a single connection) |
| **Profile** | Which agent *on that server* | Per chat turn, clears on Connection switch | Agent sheet — tap the agent name in the top bar |
| **Personality** | Which system-prompt preset | Per chat turn | Agent sheet — same sheet, below Profile |

Pick in order: the server (top bar), then the agent + persona (agent sheet). Switching either Profile or Personality shows a toast confirming the new selection.

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

- **Hidden when empty.** If the server has no `~/.hermes/profiles/*/` entries (and just the default root config), the Profile section of the agent sheet doesn't render.
- **"Default"** option at the top of the Profile list. Selecting it clears the override and uses the server's `config.yaml/model.default`.
- **Disabled mid-stream.** You can't switch profile during an in-flight chat turn.
- **Persisted per Connection (v0.7.0).** Your pick survives app restart and follows the Connection it was made on — switching to Connection B brings up B's last-selected profile (or its default if never set), switching back to A restores A's selection. Removing a Connection also clears its remembered pick.
- **Jump from Settings.** The "Active agent" card at the top of Settings summarizes the current Connection / Profile / Personality and navigates straight to Chat with the agent sheet pre-opened.

## Runtime metadata (v0.7.0)

Each profile row in the agent sheet now shows what the relay observes about the profile on disk and at runtime:

- **Status dot (green vs grey).** A 6 dp dot rendered next to the profile name. Green when the relay has recently probed the profile's gateway and got a response; grey when the probe is idle, stale, or the gateway isn't running. Gateway-off profiles **stay selectable** — the probe is best-effort and can be wrong across a server restart, so we hint (50% alpha row) rather than disable.
- **"N skills" chip.** Shown when `skill_count > 0`. Counts the skills visible inside the profile directory's skills root. Hidden when zero. Useful for picking "the profile that has the scheduling skill" at a glance.
- **"SOUL" badge.** Shown when the profile has a non-empty `SOUL.md` on disk. Decoupled from whether the system-message content actually loaded — a SOUL badge means "the file exists and isn't empty", an active SOUL in chat means the server actually served the content.

When a profile with a non-empty SOUL is active AND you pick a non-default personality, the agent sheet adds an inline "Profile SOUL overrides personality while active" caption under the personality section, mirroring the existing note under the profile section. Both are kept so the precedence rule is visible from either side of the sheet.

All three indicators are optional on the wire — if you're paired with a pre-v0.7.0 relay that doesn't report them, the dot renders grey, the chips stay hidden, and the badge doesn't appear. Nothing else changes.

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
