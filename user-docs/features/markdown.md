# Markdown Rendering

Assistant messages render with full markdown support — structured, readable output instead of plain text.

## Supported Syntax

- **Bold** and *italic* text
- `inline code` spans
- Fenced code blocks with syntax highlighting
- Ordered and unordered lists
- Links (tappable, opens in browser)
- Blockquotes
- Tables
- Horizontal rules
- Headings (levels 1-6)

## Code Blocks

Code blocks include:
- **Syntax highlighting** — language-aware coloring via the Highlights library with Atom theme (auto-switches between dark and light mode)
- **Language label** when specified in the fence
- **Monospace font** for readability
- **Horizontal scrolling** for long lines

## Rich Cards

When an agent or skill has something structured to show — an approval prompt, a link preview, a calendar entry, a weather snapshot — the phone renders it as an inline **card** instead of plain markdown. Cards appear between the assistant's prose and any attached files, with:

- A **colored accent stripe** on the leading edge (blue = info, green = success, amber = warning, red = danger)
- A **type icon** (shield for approvals, globe for links, calendar, sun, sparkle for generic skill output)
- **Title**, **subtitle**, **markdown body**, and **label/value fields** — the body supports the same markdown syntax as message bubbles
- **Action buttons** you can tap to respond (for example, an `approval_request` card asking "Run shell command?" shows **Allow** in primary style and **Deny** in danger style)

After you tap an action the button row collapses into a **"Chose: Allow"** confirmation, so a session reload from history doesn't re-prompt you. Unknown card types — ones added after your phone build — still render cleanly via a generic fallback (title + body + fields + actions), so updates to the agent side never break your scrollback.

## How It Works

The app uses [Multiplatform Markdown Renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) — a Compose-native renderer that maps markdown elements to Compose text styles and layouts. User messages render as plain text.

## Settings

Markdown rendering is always enabled for assistant messages. There is no toggle — plain text would make most responses harder to read.
