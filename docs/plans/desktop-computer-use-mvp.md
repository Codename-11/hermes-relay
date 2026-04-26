# Desktop Computer Use MVP

Status: experimental implementation plan, Phase 2 CLI-control slice started 2026-04-26.

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

## Phase 2 Scope

Add the smallest practical control path:

- durable per-URL computer-use consent separate from broad desktop tool consent
- in-memory observe/assist/control grants
- local per-action CLI approval
- Windows-only bounded input actions through a temporary PowerShell/User32 backend
- daemon/non-interactive fail-closed behavior

## Behavior Contract

`desktop_computer_status` reports:

- platform and display metadata
- experimental protocol marker
- local in-memory computer-use grant state
- local computer-use consent state
- CLI approval availability
- host input state (`available_with_per_action_cli_approval`, `blocked_headless`, or unsupported)

`desktop_computer_screenshot` wraps the existing `desktop_screenshot` backend and returns PNG bytes or a saved path plus coordinate metadata. Region capture, cursor certainty, and sensitive-window redaction are reported as planned or unavailable rather than silently claimed.

`desktop_computer_action` performs bounded Windows input only when all gates pass:

- the client is launched with `--experimental-computer-use` or `HERMES_RELAY_EXPERIMENTAL_COMPUTER_USE=1`
- durable per-URL computer-use consent exists
- an in-memory assist/control grant is active
- the desktop client is interactive and can show a local per-action approval prompt
- the user types the one-action approval phrase

If any gate is missing, the tool returns a structured failure such as `grant_required`, `computer_use_consent_required`, `not_interactive`, `rejected`, or `unsupported_platform`.

`desktop_computer_grant_request` accepts `observe`, `assist`, and `control`. Grants are in-memory, time-limited, and do not replace per-action approval.

`desktop_computer_cancel` revokes the active in-memory computer-use grant and is safe to call when no grant exists.

## Later Phases

1. Add a native Tauri shell with tray, always-visible overlay chip, grant prompts, action log, and emergency stop.
2. Replace the PowerShell/User32 Windows MVP with a signed brokered native helper.
3. Add UI Automation context and sandboxed workspace execution as separate design passes.
4. Add stale screenshot/capture-id correlation before expanding action types.

## Verification

Current desktop computer-use slices are accepted when:

- desktop TypeScript build passes
- Python plugin tests for the desktop schemas pass
- existing desktop tools remain advertised by default
- computer-use tools are marked experimental and hidden unless explicitly enabled
- action/input requests fail closed without task-scoped assist/control grant
- input requests fail closed in daemon/non-interactive mode
- `/desktop/health` exposes the computer-use heartbeat state for server-side debugging
