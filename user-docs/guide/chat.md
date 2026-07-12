# Chat

## The input bar

The chat bar is deliberately minimal — three elements, and one button that
changes its job instead of cluttering the bar:

- **`+`** — tap to attach a file or image. **Long-press** it to open the full
  **command palette**. (There's no separate slash button anymore.)
- **The pill field** — type here. It grows up to five lines, then scrolls.
- **One trailing button** — it morphs to match what you can do right now:

| The button shows | When | What it does |
|---|---|---|
| **Send** (arrow) | You've typed something | Sends your message |
| **Voice** (waveform) | The field is empty | Starts a voice conversation |
| **Stop** (square) | A reply is streaming and the field is empty | Cancels the current reply |
| **Steer** (cyan arrow) | You type while a reply is streaming | Nudges the live reply — see [Steering](#steering) |
| **Queue** (arrow + clock) | You type mid-reply and it can't be steered yet | Holds your message for the next turn |

The bar never widens as it morphs — the trailing slot just cross-fades. A tiny
character counter appears as an overline above the bar only as you approach the
4096-character limit (amber, then red at the limit). Haptic feedback fires on
send and when a reply finishes.

## Sending messages

Type and tap **Send** (or press Enter on a hardware keyboard). Replies stream in
real time. When the assistant is thinking, animated dots pulse **inside** the
reply bubble until the first words arrive.

## Steering

You don't have to wait for a reply to finish to redirect it. Start typing while
a reply is streaming and the Send button turns into a cyan **Steer** button:

- If the agent is between tool calls, your text is **injected into the running
  turn** — it adjusts course without starting over. A small `↳ steered` note
  marks where you stepped in.
- If the agent is mid-reasoning and can't accept a steer yet, your message is
  **queued** for the next turn instead (the button shows a clock badge, and a
  caption above the bar tells you which happened).

Steering is available on the [Gateway transport](/guide/getting-started#_3-connect-chat)
(the live path used when you're signed in to Manage).

## Voice mode

When the field is empty, the trailing button is a **waveform** — tap it to start
a hands-free voice conversation. The sphere expands to fill the screen, listens
while you speak, and performs the reply as it streams back. Transcribed
utterances appear in your chat history as normal messages.

If voice needs setup on your connection, the button shows a small **amber dot**
(it still works — tapping explains what's needed) rather than looking disabled.
Interaction modes (tap, hold, continuous) live in **Settings → Voice**. Full
details and troubleshooting: [Voice Mode](/features/voice).

## Edit & resend

Made a typo, or want to take the conversation a different direction? **Long-press
one of your own messages** and choose **Edit** — the conversation rewinds to that
point and re-runs from your edited text. Available on the Gateway transport when
no reply is currently streaming.

## Slash commands

Two ways to discover and run commands:

- **Inline autocomplete** — type `/` in the field for a filtered popup that
  narrows as you type, showing up to eight matches with descriptions.
- **Command palette** — **long-press the `+`** for a full searchable palette.
  Browse by category, search by name or description, and tap to insert.

Commands are fetched dynamically from your server wherever possible:

- **Session commands** — `/new`, `/retry`, `/undo`, `/branch`, `/compress`,
  `/resume`, etc. (Hermes gateway built-ins, pulled from the server's catalog)
- **Configuration** — `/model`, `/personality`, `/reasoning`, `/yolo`,
  `/verbose`, `/voice`
- **Info** — `/help`, `/status`, `/usage`, `/insights`, `/commands`
- **Personalities** — generated from server config (`config.agent.personalities`)
- **Skills** — fetched from native `GET /v1/skills`; legacy detail routes remain available only where the optional compatibility layer provides them

## Tool execution

When the agent uses tools (terminal commands, web search, file operations), tool
calls render based on **Settings → Chat → Tool call display**:

| Mode | Behavior |
|------|----------|
| **Off** | Tool calls hidden |
| **Compact** | Inline one-line display with tool name and status |
| **Detailed** | Full progress cards with icons, arguments, duration, results |

In Detailed mode, a card first shows a quiet **"preparing…"** state while the
model writes the tool's arguments, then fills in live as the tool runs and
auto-collapses when it completes. Each card carries a right-aligned timestamp and
duration (e.g. `3.1s · 5:32 PM`). Tap to expand/collapse manually.

### Subagent lanes

When the agent delegates work to subagents, each one renders as its own
collapsible **lane** beneath the reply — a guide rail with that subagent's
thinking and tool rows, so a complex multi-agent turn stays readable instead of
interleaving into one stream. Lanes auto-collapse as each subagent finishes.

### Rich cards & interactive prompts

Alongside tool-progress cards, replies can render **rich cards** — structured
Material cards for link previews, calendar entries, weather, and other skill
output.

Some cards are **interactive prompts** the agent is waiting on: an approval,
a clarifying question, a `sudo` password, or a secret. Answer right in the card —
the button row collapses into a confirmation once you choose, and your response
is synced into the agent's session memory so later turns can reference it.
Sensitive prompts (sudo, secrets) are masked and hold-to-confirm. See
[Markdown Rendering → Rich Cards](/features/markdown#rich-cards) for the full
visual vocabulary.

## Context meter

A thin strip under the chat header tracks how full the conversation's context
window is. It stays invisible until you're about halfway, then turns **amber**
around 75% and **red** near 90%, with a `· NN% ctx` readout in the header
subtitle as you get close to the limit — a quiet heads-up to wrap up or
`/compress` before the model starts dropping the earliest messages.

## Turn-complete notifications

Long task running while you switch away? If you leave the app mid-reply,
Hermes-Relay posts a **"Hermes replies"** notification when the turn finishes, so
you can jump back in. Toggle it under **Settings → Chat → Notify when Hermes
finishes** (it asks for notification permission the first time).

## Smooth auto-scroll

While a reply streams, the list follows new tokens, reasoning, and tool cards
like a live transcript:

- **At the bottom?** New content scrolls into view automatically and stays
  pinned to the latest token, even as the bubble grows with reasoning and tool
  cards.
- **Scrolled up to read history?** Auto-follow pauses — you won't be yanked back.
- **Want to resume?** Scroll back to the bottom, or tap the floating arrow that
  appears when there's new content below.

Disable it under **Settings → Chat → Smooth auto-scroll** if you'd rather scroll
manually. It's on by default.

## Markdown

Replies render with full markdown support:

- **Bold** and *italic* text
- `inline code` and fenced code blocks with syntax highlighting
- Links, lists, blockquotes
- Tables and horizontal rules

## Reasoning display

When the agent uses extended thinking, a collapsible **Thinking** block appears
above the reply and streams live as the model reasons. Toggle it under
**Settings → Chat → Show reasoning**.

## Copying messages

Long-press any message bubble to copy its text. Haptic feedback confirms the copy.
(For your own messages, long-press also offers [Edit & resend](#edit-resend).)

## Empty state

With no messages, the chat shows a logo, "Start a conversation," and suggestion
chips. Tapping a chip populates the input field.

## Agent sheet — profile + personality

Tap the agent name in the middle of the top bar to open the **agent sheet** — a
scrollable bottom sheet for Profile selection, Personality selection, and session
info + analytics for the current conversation (message count, tokens in/out, avg
TTFT).

- **Profile** — [upstream Hermes agent directories](/features/profiles)
  auto-discovered on the server. Selecting one overlays its model + SOUL for
  subsequent turns.
- **Personality** — system-prompt presets from `config.agent.personalities`. The
  server default is shown first, then all configured alternatives.

Switching either shows a toast. The active personality name appears above
assistant bubbles. You can also switch via `/personality <name>`.

## Connection chip

If you've paired more than one Hermes server, a **Connection chip** appears on
the left of the top bar. Tap it to open a switcher listing all your connections
with a health indicator. With a single connection, the chip is hidden. See
[Connections](/features/connections).

## Token tracking

Each assistant message shows token usage below the timestamp:

- Input tokens sent
- Output tokens received
- Estimated cost

## App context prompt

When enabled (**Settings → Chat → App context prompt**, on by default),
Hermes-Relay tells the agent it's talking to a phone so replies stay
mobile-friendly and concise, and can attach optional bridge/permission and
safety-rail summaries. On the standard (API-server) connection this rides an
invisible system message. The Gateway connection carries no app-context preamble
— its protocol has no hidden per-turn slot, and adding one would leave the text
in your saved chat history — so there the agent reads phone state on demand via
the `android_phone_status` tool. Privacy-sensitive fields (foreground app,
battery) default off and are only added when you opt in.

## Persistent connection

By default the connection to Hermes is held open while Hermes-Relay is on screen
and for a couple of minutes after you switch away, so a quick return is instant;
on a longer absence it reconnects when you reopen the chat. Returning to a chat,
the app pre-warms the connection in the background so your first message is fast.

If you want the connection to stay fully open even when the app is in the
background, turn on **Settings → Quick Controls → Persistent connection** (off by
default). It holds the app's connection to Hermes open via an ongoing
notification, so messages and live features stay responsive; for relay-paired
setups it also keeps device control and notification mirroring reachable. Tap
**Turn off** on the notification, or flip the toggle, to stop. This uses more
battery; swiping the app away from recents also ends it. It's the same approach
apps like Home Assistant use to stay connected.
