# Chat

## Sending Messages

Type your message in the input field and tap the send button (or press Enter on a hardware keyboard). Messages stream in real-time via Server-Sent Events.

## Markdown

Assistant responses render with full markdown support:
- **Bold** and *italic* text
- `inline code` and fenced code blocks with syntax highlighting
- Links, lists, blockquotes
- Tables and horizontal rules

## Copying Messages

Long-press any message bubble to copy its text to your clipboard.

## Canceling Streams

While the assistant is responding, a stop button appears next to the input field. Tap it to cancel the current stream.

## Tool Execution

When the agent uses tools (terminal commands, web search, file operations), you'll see tool progress cards with:
- Tool-type-specific icons
- Expandable argument and result details
- Completion duration

## Personalities

The personality picker in the top bar lets you switch the agent's communication style. Available personalities:

| Personality | Style |
|-------------|-------|
| Default | Standard assistant |
| Concise | Brief and direct |
| Creative | Imaginative and expressive |
| Technical | Precise and detailed |
| Teacher | Educational, step-by-step |
| Formal | Professional tone |
| Pirate | Arr, matey! |
| Kawaii | Cute and enthusiastic |

## Reasoning Display

When the agent uses extended thinking (Claude, o1), a collapsible "Thinking" block appears above the response. Toggle this in **Settings > Chat > Show reasoning**.

## Token Tracking

Each assistant message shows token usage below the timestamp:
- Input tokens sent
- Output tokens received
- Estimated cost
