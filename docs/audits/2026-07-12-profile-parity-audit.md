# Profile parity audit — 2026-07-12

## Contract

Hermes profiles are isolated agent homes. A selected profile owns its config,
model/provider defaults, SOUL, skills, credentials, sessions, memory, and history.
Android may use the upstream dashboard and TUI-gateway surfaces to operate that
profile, but it must never substitute the launch/default profile's database after
a scoped operation fails.

Official upstream behavior used for this audit:

- `session.create` and `session.resume` bind an optional `profile`.
- `session.activate` addresses an already-live gateway session.
- saved session discovery/history is distinct from live-session activation.
- selecting a profile for one conversation is not the same as changing the
  server-wide sticky default profile.

## Supported matrix

| Surface | Profile behavior |
| --- | --- |
| Select/switch | Starts or restores the selected profile's context; never hot-swaps the agent inside an existing live session. |
| Create/resume/recover | Gateway RPCs carry the selected profile; durable recovery retains the profile context key and session id. |
| List/history | Dashboard REST reads the selected profile's database. A scoped failure remains a failure and cannot fall through to the default database. |
| Rename/delete | Dashboard REST writes the selected profile's database. Optimistic UI changes roll back on failure. |
| Model/provider/reasoning/fast | Fresh sessions inherit profile defaults; explicit in-chat choices are session-scoped and cleared when switching profiles. |
| Personality | Profile-local selection is reset/reseeded on switch; it is not carried from the prior profile. |
| SOUL, skills, keys and config | Managed through the profile-aware dashboard surfaces. |
| Local identity | Display alias, icon, lock state, last session, and voice preferences are keyed by connection and profile. |
| In-flight recovery | Reattaches to the exact live session when possible, otherwise polls only the selected profile's history and stops with an actionable error after repeated read failures. |

## Upstream limits

- Standard dashboard audio remains host-global upstream; truly profile-local
  Standard voice requires upstream profile-aware audio endpoints. Relay voice
  already carries profile context and is the supported profile-aware path.
- The optional relay plugin is installed once but enabled independently in each
  Hermes profile. A profile without the plugin still retains the vanilla upstream
  chat/Manage path, but relay-only features are unavailable for that profile.
- Server-wide **Activate Profile** remains an explicit Manage action. The chat
  profile picker is conversation context and must not mutate the sticky server
  default used by other clients.

## Regression gates

1. Switch default → non-default → default while each profile has distinct sessions.
2. Create, resume, rename, and delete a session in each profile; refresh and restart.
3. Force-stop during a long non-default-profile turn; reopen and verify exact-session
   reattachment or profile-scoped history settlement.
4. Make the profile-scoped history endpoint fail; verify no default-profile rows
   appear and recovery exits with a reconnect message after three failed polls.
5. Verify model, reasoning, fast mode, personality, icon, alias, voice preferences,
   and last-session state do not cross profile boundaries.
