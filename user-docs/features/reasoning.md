# Reasoning Display

When the AI model uses extended thinking, Hermes-Relay displays the reasoning process as a quiet inline disclosure above the response.

## How It Works

The Hermes API Server streams `tool.progress` events (reasoning/thinking) alongside `assistant.delta` events (text content). The app collects thinking chunks into a separate block that appears above the main response content.

## Appearance

- A live "Thinking…" row with a collapse/expand toggle and no tinted card
- The reasoning text is displayed in a visually distinct style (muted colors, smaller font)
- Open automatically while live and collapsed to "Thought" after completion
- Expandable with a single tap

If you explicitly open or close the disclosure, that choice is preserved when
the turn settles. Empty reasoning never creates a row; the separate reply
status owns the wait before any reasoning or answer text arrives.

## Settings

Toggle reasoning display in **Settings > Chat > Show reasoning**. When disabled, thinking events are still received but not rendered in the UI.

## When Does It Appear?

Reasoning blocks only appear when the model actually uses extended thinking. Not all models or all prompts trigger it. You will see it most often with:
- Complex multi-step problems
- Code generation tasks
- Analytical questions
