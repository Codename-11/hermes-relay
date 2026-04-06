# Tool Progress

When the Hermes agent executes tools during a conversation, Hermes Relay shows rich progress cards that keep you informed about what the agent is doing.

## What You See

Each tool call appears as an inline card in the chat with:
- **Tool name** and a type-specific icon (terminal, web, file, etc.)
- **Arguments** — expandable section showing what was passed to the tool
- **Status** — in-progress spinner or completion checkmark
- **Duration** — how long the tool took to execute
- **Result** — expandable section showing the tool's output (after completion)

## Tool Types

Common tools you might see:
- **Terminal** — shell command execution
- **Web search** — internet queries
- **File operations** — reading, writing, listing files
- **Code execution** — running scripts or code snippets
- **Browser** — navigating and interacting with web pages

## How It Works

The Hermes API Server sends `tool.pending`/`tool.started` events when a tool begins, `tool.completed` when it succeeds, and `tool.failed` when it errors. The app renders these as interactive cards that update in real-time as the response streams.

## Expanding Details

Tap a tool card to expand it and see the full arguments and results. This is useful for inspecting exactly what command was run or what output was returned.
