# Desktop Computer Use MVP

Status: experimental implementation plan, Phase 1 started 2026-04-26.

This plan converts the Obsidian draft at `~/obsidian-vault/3. System/Projects/Hermes-Relay/Plans/Desktop Control - Computer Use + Overlay.md` into repo-local implementation scope.

## Constraints

- The desktop CLI is a local workspace package and release-binary target. It is not published to npm, and docs must not tell users to install `@hermes-relay/cli` from npm.
- The first slice extends the existing desktop WSS/tool channel. It does not introduce a new relay channel or a native app.
- The safety model is visible, explicit, and grant-scoped. There is no silent mouse or keyboard control.
- The current native UI does not exist. Tauri/Rust/React remains the later path for tray, overlay, permission prompts, emergency stop, and OS-native input APIs.

## Phase 1 Scope

Ship an observe-first tool surface behind an explicit experimental opt-in:

- `desktop_computer_status`
- `desktop_computer_screenshot`
- `desktop_computer_action`
- `desktop_computer_grant_request`
- `desktop_computer_cancel`

The desktop client registers handlers for all five names, but the heartbeat only advertises them when either `--experimental-computer-use` is passed or `HERMES_RELAY_EXPERIMENTAL_COMPUTER_USE=1` is set. Without that opt-in, the relay check endpoint fails closed because the client does not advertise the tools.

## Behavior Contract

`desktop_computer_status` reports:

- platform and display metadata
- experimental protocol marker
- local in-memory computer-use grant state
- overlay availability as `visible: false`
- host input as not implemented

`desktop_computer_screenshot` wraps the existing `desktop_screenshot` backend and returns PNG bytes or a saved path plus coordinate metadata. Region capture, cursor certainty, and sensitive-window redaction are reported as planned or unavailable rather than silently claimed.

`desktop_computer_action` never performs host input in Phase 1. Missing assist/control grant returns `grant_required`; even with a future grant shape it returns `not_implemented` until a visible confirmation or overlay path exists.

`desktop_computer_grant_request` accepts `observe`, `assist`, and `control`, but Phase 1 only grants in-memory observe mode. Assist/control requests fail closed.

`desktop_computer_cancel` revokes the active in-memory computer-use grant and is safe to call when no grant exists.

## Later Phases

1. Add a durable local consent broker separate from broad desktop tool consent.
2. Add local per-action approval in interactive CLI sessions.
3. Add a native Tauri shell with tray, always-visible overlay chip, grant prompts, action log, and emergency stop.
4. Add Windows input through a brokered native helper only after the visible-control path exists.
5. Add UI Automation context and sandboxed workspace execution as separate design passes.

## Verification

Phase 1 is accepted when:

- desktop TypeScript build passes
- Python plugin tests for the desktop schemas pass
- existing desktop tools remain advertised by default
- computer-use tools are marked experimental and hidden unless explicitly enabled
- action/input requests fail closed without task-scoped assist/control grant
