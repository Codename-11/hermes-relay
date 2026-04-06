# Reasoning Display

When the AI model uses extended thinking (Claude with thinking enabled, o1-style reasoning), Hermes Relay displays the reasoning process in a collapsible block above the response.

## How It Works

The Hermes API Server streams `tool.progress` events (reasoning/thinking) alongside `assistant.delta` events (text content). The app collects thinking chunks into a separate block that appears above the main response content.

## Appearance

- A "Thinking" header with a collapse/expand toggle
- The reasoning text is displayed in a visually distinct style (muted colors, smaller font)
- Collapsed by default after the response completes
- Expandable with a single tap

## Settings

Toggle reasoning display in **Settings > Chat > Show reasoning**. When disabled, thinking events are still received but not rendered in the UI.

## When Does It Appear?

Reasoning blocks only appear when the model actually uses extended thinking. Not all models or all prompts trigger it. You will see it most often with:
- Complex multi-step problems
- Code generation tasks
- Analytical questions
