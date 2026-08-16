# Profiles

A **profile** in Hermes-Relay is an upstream-Hermes agent directory — an isolated Hermes instance on your server with its own config, model, and identity. When you connect to a server, Android discovers its profiles and exposes them in the **Profile Shelf** directly below the Chat top bar.

## The three layers

| Layer | What it picks | Scope | Where it lives |
|---|---|---|---|
| **Connection** | Which Hermes server | One pairing per server | Top-bar chip on the left (hidden with a single connection) |
| **Profile** | Which agent *on that server* | Persisted per Connection, with sessions isolated by transport | Profile Shelf — tap the agent name in the top bar |
| **Personality** | Which system-prompt preset | Per active session | Agent Passport |

Pick the server, then the agent from the Profile Shelf. Agent Passport remains the place to inspect identity, model, personality, reasoning, and safety configuration.

## Where profiles come from

Upstream Hermes profiles live at `~/.hermes/profiles/<name>/` — each is a full, isolated Hermes environment with:

- Its own `config.yaml` (model, personalities, provider keys, everything)
- Its own `.env` (API credentials)
- Its own `SOUL.md` (the profile's identity / system prompt)
- Its own sessions, memory, skills, cron jobs, state database

See the upstream docs: `hermes profile create`, `hermes profile use`, `hermes -p <name> <command>` — each profile gets its own CLI alias.

Create them with:

```bash
hermes profile create mizu
mizu setup
```

Or clone from an existing profile:

```bash
hermes profile create coder --clone
```

The phone doesn't create profiles — you do that on the server. The phone just picks them up on the next pairing (or the next `auth.ok` round-trip after a relay restart).

## What "switching profile" does on the phone

When a profile is selected, the phone first checks whether the relay advertised that profile's own Hermes API server.

- **With a profile API server:** chat, session browsing, memory, tools, model, and SOUL come from that profile's routed API. The chat session drawer clears and refetches through that profile route, so you see that profile's sessions instead of the default agent's sessions.
- **Without a profile API server:** the app falls back to the compatibility overlay. It sends the profile `model.default` and `SOUL.md` on each chat turn, but memory, sessions, tools, and provider auth still come from the active Connection.
- **Voice:** relay-owned voice routes receive the selected profile too. Voice Settings shows whether TTS/STT, streaming voice output, or realtime voice came from profile config or fell back to relay/global defaults. Saving voice output or experimental realtime settings while a named profile is active writes that profile's `voice_output:` / `realtime_voice:` section, so profiles like `mizuki` and `victor` can keep different voices.

**If you want true profile isolation,** run that profile's gateway as its own service on its own port:

```bash
hermes -p mizu platform start api --port 8643
```

Then make sure the relay advertises that API server in the profile metadata, or add that gateway as a separate **Connection** on the phone. Each routed profile API has its own sessions, memory, and state because it is a distinct gateway.

## Profile Shelf behaviour

- **Collapsible and compact.** Tap the avatar/name in the Chat header to expand or collapse the shelf. The hamburger still opens only the active profile's Session Drawer.
- **Active capsule.** The active avatar/name/chevron opens Agent Passport. Inactive agents are 48 dp avatar targets; the fixed overflow opens the same full switcher used by Passport's **Switch agent** control.
- **Hidden for one effective identity.** The shelf takes no space when only one visible identity remains. Saved ordering and hidden preferences are honored, but a hidden active profile stays visible until you switch away.
- **Server default is distinct.** The home-glyph **Server default** choice follows the server's sticky default without changing it. A profile literally named `default` is a separate explicit profile with its own session and presentation state.
- **Transport-safe switching.** Gateway turns can continue in the background and reconcile to their original conversation, so profile switching remains available. SSE switching is disabled only while an SSE turn is live.
- **No live-session hot swap.** Switching restores the destination profile's last compatible Gateway or SSE session, or opens a fresh draft. It never changes the agent inside the conversation currently on screen.
- **Session controls reset.** Model, personality, reasoning, approval, Fast, and YOLO choices from the old session do not leak into the new profile.
- **Persisted per Connection (v0.7.0).** Your pick survives app restart and follows the Connection it was made on — switching to Connection B brings up B's last-selected profile (or its default if never set), switching back to A restores A's selection. Removing a Connection also clears its remembered pick.
- **Long press for management.** Inspect the profile, open Passport, pin or unlock the profile, or hide it from the shelf. Display ordering and unhide controls remain under **Manage profile display**.

## Runtime metadata (v0.7.0)

Each profile row in the agent sheet now shows what the relay observes about the profile on disk and at runtime:

- **Status dot (green vs grey).** A 6 dp dot rendered next to the profile name. Green when the relay has recently probed the profile's gateway and got a response; grey when the probe is idle, stale, or the gateway isn't running. Gateway-off profiles **stay selectable** — the probe is best-effort and can be wrong across a server restart, so we hint (50% alpha row) rather than disable.
- **"N skills" chip.** Shown when `skill_count > 0`. Counts the skills visible inside the profile directory's skills root. Hidden when zero. Useful for picking "the profile that has the scheduling skill" at a glance.
- **"SOUL" badge.** Shown when the profile has a non-empty `SOUL.md` on disk. Decoupled from whether the system-message content actually loaded — a SOUL badge means "the file exists and isn't empty", an active SOUL in chat means the server actually served the content.

When a profile with a non-empty SOUL is active AND you pick a non-default personality, the agent sheet adds an inline "Profile SOUL overrides personality while active" caption under the personality section, mirroring the existing note under the profile section. Both are kept so the precedence rule is visible from either side of the sheet.

All three indicators are optional on the wire — if you're paired with a pre-v0.7.0 relay that doesn't report them, the dot renders grey, the chips stay hidden, and the badge doesn't appear. Nothing else changes.

### Profile Inspector

From the **Settings** tab, tap the **Inspect Agent** card (directly under Active Agent) to open the currently selected profile. On current Hermes gateways the inspector reads and saves the profile through the upstream `profiles.describe` / `profiles.configure` contract. Older gateways retain the paired Relay inspector behavior. Four tabs:

- **Config** — the profile's `config.yaml` rendered as a collapsible JSON tree. Nested objects collapse by default; tap to expand. Values render in monospace. The file path is shown at the top as a caption so you can `cd` to it from a shell if you want to edit.
  - **Secrets are masked by default.** Any value whose key name contains `key`, `token`, `secret`, `password`, or `credential` (case-insensitive) renders as `abcd...wxyz` for values ≥12 chars or `********` for shorter ones. Tap the eye icon next to the value to reveal it for that row. Reveal state is session-scoped — leaving the screen wipes it. Numbers and booleans are never masked.
- **SOUL** — the profile's `SOUL.md` rendered as markdown. The `</>` toggle in the top-right of the pane flips between rendered and raw monospace source. Byte size + file path show above the content.
- **Memory** — one card per file under the profile's `memories/` directory (non-recursive). Each card shows the filename and byte size; tap to expand and see its content.
- **Skills** — every skill visible to the profile, grouped by category. Current gateways also expose toolsets and save skill/toolset changes together.

#### Editing (v0.7.1+)

**Config**, **SOUL**, and **Memory** support in-app edits. Current gateways own Config, SOUL, Skills, and Toolsets; paired Relay remains the owner of memory files. After a gateway save, the app reports which sections applied or failed and reloads authoritative profile state. Failed drafts stay editable for retry; successful drafts clear only after that reload.

If a gateway can describe profiles but cannot configure them, the inspector stays available as a read-only Gateway view and disables its Gateway-owned edit controls after the capability rejection. Named-profile chats also require the Gateway to confirm the exact profile owner before Android sends a prompt; older or stale gateways that cannot prove that scope fall back safely instead of opening the launch profile under the selected agent's name.

For memory entries, the **+ New entry** button at the bottom of the Memory tab opens a filename prompt (must end in `.md`, no slashes, no leading `.`) and drops you into an empty editor. A filename that collides with an existing entry is rejected; edit the existing entry via its per-card pencil instead.

#### Skill toggles

On current gateways, skill and toolset switches create reviewable drafts and **Save changes** uses the upstream profile configuration RPC. On older gateways, the legacy Relay skill-toggle route remains available; servers that return 501 keep the switches disabled for that inspector session.

Very large files (SOUL or a memory entry) are still truncated server-side; when that happens the tab shows a banner noting only the first slice is visible — the editor refuses to open on truncated content so you don't accidentally overwrite the tail. Use the Refresh icon in the top bar to re-fetch every tab, or Retry inside a tab's error state to refetch just that one.

#### Picker naming

The Profile Shelf and its canonical full switcher use these conventions:

- **Server default** — the no-override row with a home glyph. It follows the server's current sticky default without changing that setting.
- Actual profiles use their configured display description when available, then their profile name. A local agent icon wins over the display initial, and the full switcher can show the profile's model without inventing presence or activity state.
- When the server emits a `profiles.updated` push (profile added, renamed, or removed on the server side), the app applies the new list immediately and shows a brief "Profiles updated" snackbar. A profile you had selected that the server then removes falls back to Server default automatically.

The Settings card is visible whether or not a profile is currently active; when there's no active profile, the card renders at half opacity with "No active agent" and does nothing when tapped.

## Disabling discovery on the server

If you want Connections-only semantics (for example, a minimal deployment where every profile is its own Connection), set this in the relay's config:

```yaml
relay:
  profile_discovery_enabled: false
```

Restart the relay; the picker stays hidden and the app treats the install as single-agent.

## Profile + Personality interaction

If you select a profile AND a personality, the **profile wins** — its `SOUL.md` is sent as `system_message`, not the personality's prompt. That's a deliberate choice: a profile is a richer concept (whole identity), and picking both implies you want the profile's full persona. Pick one at a time to keep behaviour obvious.

## At a glance

- **Connection** = a whole Hermes server.
- **Profile** = a named agent *on* that server, discovered from `~/.hermes/profiles/`. Picking one overlays its model + SOUL for chat turns.
- **Personality** = a system-prompt preset *within* the agent's config.

See [Connections](./connections.md) for the server-level concept and [Personalities](./personalities.md) for the preset-prompt layer.
