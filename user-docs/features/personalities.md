# Personalities

Hermes-Relay fetches available personalities from the Hermes API Server and lets you switch between them.

> A **personality** is a system-prompt preset on one agent. Switching a personality changes how the agent behaves on the next message, but not the server, the agent configuration, or the underlying model. To switch *server*, see [Connections](./connections.md); to switch *which named agent on that server*, see [Profiles](./profiles.md).

## Personality Picker

The Personality picker lives inside the **agent sheet** — tap the agent name in the Chat top bar to open it, then scroll to the Personality section. It shows all personalities configured on your server (from `GET /api/config` → `config.agent.personalities`), with the server's default (from `config.display.personality`) at the top.

### How to Switch

Open the agent sheet and tap a personality. Its system prompt is sent with each subsequent chat request — the server applies it to the agent. A toast confirms the switch. The sheet also surfaces a Profile section (see [Profiles](./profiles.md)) and session info + analytics (message count, tokens in/out, avg TTFT) in the same surface.

### Agent Name on Bubbles

Assistant messages display the active personality name above the chat bubble, so you always know which personality is responding.

### How It Works

Personalities are defined server-side in `~/.hermes/config.yaml` under `agent.personalities`. Each is a name and a system prompt string. The app fetches the full list and sends the selected personality's system prompt via the `system_message` field in chat requests. The server's default personality applies automatically when no override is sent.

## Personality Slash Commands

Type `/personality <name>` in the chat input to switch personalities. The autocomplete dropdown shows all available personality commands with descriptions.

### How to Discover

Type `/personality` in the chat input — autocomplete shows all available options. The list includes both built-in Hermes personalities and any custom ones configured on your server.

## Adding Custom Personalities

Add new personalities to your Hermes server config (`~/.hermes/config.yaml`):

```yaml
agent:
  personalities:
    my-agent: "You are a custom agent with specific behavior..."
```

Restart the gateway and the new personality appears in the app's picker automatically — no app update needed.
