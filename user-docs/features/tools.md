# Tool Progress

When the Hermes agent executes tools during a conversation, Hermes Relay displays tool call information to keep you informed about what the agent is doing.

## Display Modes

Configure how tool calls appear in **Settings > Chat > Tool call display**:

| Mode | Behavior |
|------|----------|
| **Off** | Tool calls are hidden entirely |
| **Compact** | Inline one-line display showing tool name and status icon |
| **Detailed** | Full progress cards with icons, arguments, duration, and results |

## Detailed Mode

In Detailed mode, each tool call appears as an interactive card with:
- **Tool name** and a type-specific icon (terminal, web, file, etc.)
- **Arguments** — expandable section showing what was passed to the tool
- **Status** — in-progress spinner or completion checkmark
- **Duration** — how long the tool took to execute
- **Result** — expandable section showing the tool's output (after completion)

Cards **auto-expand** while the tool is running and **auto-collapse** when the tool completes. Tap to expand/collapse manually.

## Compact Mode

In Compact mode, each tool call appears as an inline element showing the tool name and a status indicator (spinner while running, checkmark on completion). This is less intrusive for conversations with many tool calls.

## Tool Types

Common tools you might see:
- **Terminal** — shell command execution
- **Web search** — internet queries
- **File operations** — reading, writing, listing files
- **Code execution** — running scripts or code snippets
- **Browser** — navigating and interacting with web pages

## How It Works

The Hermes API Server sends `tool.pending`/`tool.started` events when a tool begins, `tool.completed` when it succeeds, and `tool.failed` when it errors. The app renders these as interactive cards (or compact elements) that update in real-time as the response streams.
