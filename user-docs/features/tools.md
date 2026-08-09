# Tool Progress

When the Hermes agent executes tools during a conversation, Hermes-Relay displays tool call information to keep you informed about what the agent is doing.

## Display Modes

Configure how tool calls appear in **Settings > Chat > Tool call display**:

| Mode | Behavior |
|------|----------|
| **Off** | Hide routine activity summaries; keep approvals, failures, generated media, file changes, risks, and delegated work visible |
| **Compact** | Group consecutive routine calls into one summary; expanded rows stay compact |
| **Detailed** | Use the same clean summaries, with full arguments, duration, and results available when expanded |

## Activity summaries

Consecutive reads, searches, commands, browser actions, and device actions share
one quiet transcript row. While live, the row keeps a concise summary and a
single latest-activity ticker instead of growing the conversation for every
call. When the run settles, it becomes one collapsed summary. Tap it to inspect
the original calls in order.

In Detailed mode, each disclosed call includes:
- **Tool name** and a type-specific icon (terminal, web, file, etc.)
- **Arguments** — expandable section showing what was passed to the tool
- **Status** — in-progress spinner or completion checkmark
- **Duration** — how long the tool took to execute
- **Result** — expandable section showing the tool's output (after completion)

The run itself stays compact by default. Tap its summary, then any individual
tool row, to inspect the level of detail you need.

## Compact Mode

In Compact mode, expanding a run reveals small inline rows with each tool name
and status indicator. Approvals, failures, generated media, file changes,
output-risk findings, and delegated work always keep their independent surfaces
because they require attention or are the requested deliverable.

## Tool Types

Common tools you might see:
- **Terminal** — shell command execution
- **Web search** — internet queries
- **File operations** — reading, writing, listing files
- **Code execution** — running scripts or code snippets
- **Browser** — navigating and interacting with web pages

## How It Works

The Hermes API Server sends `tool.pending`/`tool.started` events when a tool begins, `tool.completed` when it succeeds, and `tool.failed` when it errors. The app preserves each call's identity and source order while choosing either an activity run or an independent lifecycle surface. No Relay plugin is required.
