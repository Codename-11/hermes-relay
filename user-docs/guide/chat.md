# Chat

## Sending Messages

Type your message in the input field and tap the send button (or press Enter on a hardware keyboard). Messages stream in real-time via Server-Sent Events. There is a 4096 character limit with a counter shown near the limit. Haptic feedback fires on send.

## Voice Mode

Tap the microphone FAB in the bottom-right corner to switch into voice mode. The sphere expands to fill the screen, listens while you speak, and performs the agent's response as it streams back. Three interaction modes (tap, hold, continuous) are in **Settings → Voice**. Transcribed utterances appear in your chat history as normal user messages. Full details and troubleshooting: [Voice Mode](/features/voice).

## Empty State

When no messages exist, the chat shows a logo with "Start a conversation" and suggestion chips. Tapping a chip populates the input field.

## Streaming

While the assistant is responding, animated pulsing dots indicate active streaming. A stop button appears next to the input field — tap it to cancel the current stream. Haptic feedback fires when the stream completes.

## Smooth Auto-Scroll

While the assistant is responding, the chat list smoothly follows new tokens, reasoning deltas, and tool cards as they stream in. The behavior is designed to feel like a live transcript:

- **At the bottom?** New content scrolls into view automatically. The viewport stays pinned to the latest token, even while the message bubble grows tall with reasoning blocks and tool execution cards.
- **Scrolled up to read history?** Auto-follow pauses. You won't be yanked back while you're reading.
- **Want to resume?** Either scroll back to the bottom manually or tap the floating arrow button that appears when there's new content below. Both restore live-follow immediately.

You can disable this behavior under **Settings > Chat > Smooth auto-scroll** if you'd rather scroll manually. It's enabled by default.

## Markdown

Assistant responses render with full markdown support:
- **Bold** and *italic* text
- `inline code` and fenced code blocks with syntax highlighting
- Links, lists, blockquotes
- Tables and horizontal rules

## Copying Messages

Long-press any message bubble to copy its text to your clipboard. Haptic feedback confirms the copy.

## Slash Commands

Two ways to discover and use commands:

### Inline Autocomplete
Type `/` in the input field to see a filtered autocomplete popup. It narrows as you type and shows up to 8 matches with descriptions.

### Command Palette
Tap the **`/`** button next to the input field to open a full searchable command palette. Browse by category, search by name or description, and tap to insert.

### Command Sources
All commands are fetched dynamically from the server where possible:

- **Session commands**: `/new`, `/retry`, `/undo`, `/branch`, `/compress`, `/resume`, etc. — hermes gateway built-ins
- **Configuration**: `/model`, `/personality`, `/reasoning`, `/yolo`, `/verbose`, `/voice`
- **Info**: `/help`, `/status`, `/usage`, `/insights`, `/commands`
- **Personalities**: generated from server config (`config.agent.personalities`) — `/personality victor`, `/personality creative`, etc.
- **Skills**: dynamically fetched from `GET /api/skills` — 90+ server skills grouped by category (creative, devops, research, etc.)

## Tool Execution

When the agent uses tools (terminal commands, web search, file operations), tool calls are displayed based on your display mode setting (**Settings > Chat > Tool call display**):

| Mode | Behavior |
|------|----------|
| **Off** | Tool calls hidden |
| **Compact** | Inline one-line display with tool name and status |
| **Detailed** | Full progress cards with icons, arguments, duration, results |

In Detailed mode, tool cards auto-expand while the tool is running and auto-collapse when complete. Tap to expand/collapse manually.

## Personalities

The personality picker in the top bar shows personalities fetched from the Hermes API Server (`GET /api/config` → `config.agent.personalities`). The server's default personality is shown first, followed by all configured alternatives.

When you select a personality, its system prompt is sent with each chat request. The active personality name appears above assistant message bubbles.

You can also switch via `/personality <name>` slash commands in the chat input.

## Reasoning Display

When the agent uses extended thinking (Claude, o1), a collapsible "Thinking" block appears above the response. Toggle this in **Settings > Chat > Show reasoning**.

## Token Tracking

Each assistant message shows token usage below the timestamp:
- Input tokens sent
- Output tokens received
- Estimated cost

## App Context Prompt

When enabled (**Settings > Chat > App context prompt**), a system message is sent with each request telling the agent the user is on mobile. This helps the agent tailor responses for a mobile interface. Enabled by default.
