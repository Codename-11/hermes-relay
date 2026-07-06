# Sessions

## Session Drawer

Swipe from the left edge or tap the hamburger menu icon to open the session drawer. It shows all your chat sessions with:
- Session title
- Last activity timestamp
- Message count

## Creating a New Chat

Tap **New Chat** in the session drawer, or just start typing — a session is automatically created on your first message.

## Switching Sessions

Tap any session in the drawer to switch to it. Message history loads from the server automatically.

## Auto-Titles

Sessions are automatically titled from the first ~50 characters of your first message. You can rename them anytime.

## Renaming

Tap the edit icon next to a session in the drawer. Enter a new title and tap **Rename**.

## Deleting

Tap the delete icon next to a session. Confirm in the dialog. This permanently deletes the session and its message history on the server.

## Archive and Bulk Cleanup

Newer Hermes servers expose two safer server-side cleanup primitives that Relay uses from the dashboard/Manage client layer:

- **Export** — `GET /api/sessions/{id}/export` returns one session's metadata and messages as server-owned JSON, which clients can offer as a save/share step before destructive cleanup.
- **Archive / restore** — a soft session state via `PATCH /api/sessions/{id}`. Archived sessions are hidden from the default drawer list but can be listed explicitly by clients that offer an archive view.
- **Bulk cleanup** — `POST /api/sessions/prune`. Destructive cleanup must run as a dry-run first, showing the matched session count plus oldest/newest matched timestamps before applying. If the server does not expose this route, bulk cleanup should fall back to explicit per-session deletes rather than guessing locally.

## Session Persistence

Your last active session is saved and automatically resumed when you reopen the app.
