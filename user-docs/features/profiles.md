# Profiles

A **profile** in Hermes-Relay is a named agent configuration on a Hermes server. Each profile binds a name to a model (and an optional description) in the server's `~/.hermes/config.yaml`. Pick a profile in the top bar and the next chat turn routes through that model.

## The three layers

Hermes-Relay has three layers of "which agent am I talking to":

| Layer | What it picks | Scope | Picker |
|---|---|---|---|
| **Connection** | Which Hermes server | One connection → one relay + API endpoint + pairing record | Top-of-screen chip (when you have ≥2 connections) |
| **Profile** | Which agent *on that server* | Per chat turn. Server-specific, clears on Connection switch. | Chat top-bar chip (left of Personality) |
| **Personality** | Which system prompt | Per chat turn. Layered on top of the Profile's model. | Chat top-bar chip (right of Profile) |

The picker hierarchy reads left-to-right: server → agent → persona.

## What a profile contains

A profile is a server-side config entry with three fields:

```yaml
# ~/.hermes/config.yaml
profiles:
  - name: fast
    model: gpt-4o-mini
    description: Quick responses for short questions
  - name: careful
    model: claude-sonnet-4-6
    description: Long-form reasoning and careful code review
  - name: local
    model: ollama/llama3.1
    description: Offline, no API costs
```

Each time the phone pairs with a server, the full list is included in the `auth.ok` handshake. No explicit fetch needed — the picker populates automatically.

## How the picker works

The Profile chip in the chat top bar shows:

- **"Default"** when no profile is selected. The server's configured default model handles the turn.
- **The profile's name** when one is selected. Every chat turn on this Connection gets `"model": "<profile.model>"` in the request body; the server routes through that model.

Selecting a profile applies to **chat only**. Voice transcribe/synthesize and bridge commands aren't affected — voice provider config is already per-server, and bridge doesn't invoke a model.

The chip is hidden when the server has no profiles configured. Add entries to `~/.hermes/config.yaml` and restart `hermes-gateway`; the next pairing (or auth.ok round-trip) will populate the picker.

## What clears the selection

- Switching Connections clears it to Default. Profiles are server-specific, so carrying a selection across servers would point at a profile that might not exist.
- Restarting the app clears it. Selection is ephemeral in v1 — we may persist it per-Connection in a follow-up if it turns out people want stickiness.

Personality is orthogonal: swapping a personality doesn't touch the profile, and vice versa. Personality sends a `system_message` override; profile sends a `model` override. The server sees both and honors both.

## When to use which

- **Different model for different kinds of question?** Use Profile. Quick-and-cheap for short asks, bigger-and-slower for reasoning.
- **Same model, different persona?** Use Personality. You're talking to the same agent but asked it to wear a different hat.
- **Different server entirely (home vs work)?** Use Connection.

## Profiles vs Personalities at a glance

- **Personality** = a system-prompt preset on one agent. Swap the persona, keep the agent.
- **Profile** = a named agent config (model + description) on one server. Swap the agent, keep the server.
- **Connection** = the server itself. Swap the whole world.

See [Connections](./connections.md) for the server-level concept and [Personalities](./personalities.md) for the system-prompt layer.
