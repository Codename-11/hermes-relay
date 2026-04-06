# Personalities

Hermes Relay includes 8 built-in personalities that change the agent's communication style without affecting its capabilities.

## Available Personalities

| Personality | Description |
|-------------|-------------|
| **Default** | Standard assistant — balanced and helpful |
| **Concise** | Brief, direct answers with minimal elaboration |
| **Creative** | Imaginative, expressive, uses metaphors and analogies |
| **Technical** | Precise, detailed, uses proper terminology |
| **Teacher** | Educational tone, explains step-by-step, asks guiding questions |
| **Formal** | Professional, polished language |
| **Pirate** | Arr! Nautical vocabulary and seafaring spirit |
| **Kawaii** | Cute, enthusiastic, uses emoticons |

## How to Switch

Tap the personality chip in the chat top bar. A dropdown shows all available options. The selected personality applies to the next message you send and all subsequent messages in the session.

## How It Works

The personality is sent as a system prompt modifier with each chat request. It adjusts the agent's tone and style but does not limit what it can do — tool use, code generation, and all other capabilities remain available regardless of personality.

## Per-Session

Each session remembers its selected personality. Switching sessions restores the personality that was active in that session.
