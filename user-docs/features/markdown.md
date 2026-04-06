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

## How It Works

The app uses [Multiplatform Markdown Renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) — a Compose-native renderer that maps markdown elements to Compose text styles and layouts. User messages render as plain text.

## Settings

Markdown rendering is always enabled for assistant messages. There is no toggle — plain text would make most responses harder to read.
