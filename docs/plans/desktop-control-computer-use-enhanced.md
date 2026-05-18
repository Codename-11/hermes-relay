# Desktop Control — Computer Use + Overlay (Enhanced)

**Status:** Phase 0-5 Windows tray/management pass implemented
**Parent:** [[Desktop Client]]
**Updated:** 2026-05-17
**Tech Decision:** Tauri v2 recommended (with strong CLI fallback)
**Goal:** Deliver a seamless, easy-to-use desktop "hand" for Hermes with visible control, simple pairing, systray management, and advanced configurability.

**Follow-on UI parity plan:** `docs/plans/2026-05-17-desktop-android-pairing-parity.md` supersedes the visible Easy/Standard/Advanced tier language in the tray UI. Keep the CLI/JSON operator path, but the next desktop UI pass should remove tier selectors/copy, default to dark mode, expose one active desktop relay instance, and move low-frequency controls behind a collapsed Advanced section.

---

## Tech Stack Decision (New)

**Recommendation:** Tauri v2 (Rust + static web UI) for the native tray/overlay shell.

**Why Tauri is the right fit**
- Lightweight binaries using system webview — matches our thin-client / Hermes Hand philosophy.
- Excellent support for tray icons, always-on-top overlays, native permission dialogs, and global hotkeys.
- Clean Rust interop for the OS-level screenshot and input primitives we need anyway.
- Better resource usage than Electron for a background "hand" app.
- Good path for code signing and distribution.

**Risks & Mitigations**
- Rust learning curve → Keep all core logic in TypeScript first; Rust only for thin native helpers.
- Overlay quirks on Windows → Keep the chip compact, status-only, click-through, dynamically sized to its current label, and anchored to the monitor work area above the taskbar; fall back to CLI prompts if the native window is unavailable.
- Distribution → Use existing prebuilt binary pattern plus a Windows Tauri installer as the primary Easy/Standard surface.

**Fallbacks (important)**
- Phases 1-4 must work fully in the existing `@hermes-relay/cli` daemon with no Tauri dependency.
- Advanced users can stay on CLI + JSON config forever.
- Tauri app is the polished "Easy tier" experience, not a hard requirement.

**Alternatives considered**
- Electron: Too heavy for our goals.
- Pure native (WinUI3/SwiftUI): High maintenance cost.
- CLI-only + node-tray: Insufficient for visible grants and overlay.

**Decision:** Proceed with Tauri v2 for the native layer while treating the TypeScript CLI as the always-working advanced/headless surface.

---

## User Experience Tiers (New)

| Tier       | Target          | Default Experience                          | Configurability                          |
|------------|-----------------|---------------------------------------------|------------------------------------------|
| Easy       | Most users      | Pair once → tray runs → compact above-taskbar status pill → tray/hotkey pause | Simple tray settings                     |
| Standard   | Power users     | Full tray menu + task monitor + device management | Edit `desktop-control.json` or settings panel |
| Advanced   | Operators       | CLI + daemon + custom policies + hotkeys    | Direct JSON, scripts, per-scope rules    |

**Default posture:** the desktop tray should feel as simple as the Android Relay app pairing. Advanced options are always available via JSON or CLI flags, but should not be presented as a visible app tier selector in the tray UI.

---

## Seamless Pairing + Management Flow (Enhanced)

**Target easy flow (Easy tier)**
1. Run `hermes-relay pair` or click "Pair new relay" in tray.
2. Show QR or 6-character code (reuse existing flow).
3. On success: tray icon appears, status pill shows "Observing".
4. Left-clicking the tray icon opens the dashboard; right-clicking opens menu items for Devices, Revoke, Task Log, Settings, and Emergency Stop.
5. Clicking a device opens a lightweight management window.

**Management surface**
- Reuse existing relay `/sessions` and device listing.
- Local revoke that also clears active grants.
- Status badges: Observing / Control Active / Paused / Disconnected.
- Advanced: `--config` path or `~/.hermes/desktop-control.json` for blocklists, hotkeys, overlay position, auto-grant scopes.

This gives the "seamless and easy" default while exposing full power for advanced users.

---

## Safety & Non-Negotiables (Unchanged from original)

- No invisible control.
- Task-scoped, time-limited, revocable grants.
- Visible overlay/chip required for any Control actions.
- Emergency stop always available (tray + hotkey).
- Sensitive apps blocked by default.
- Full local audit log.

**Default blocklist baseline**
- Password managers and credential vaults.
- MFA / passkey / OS credential prompts.
- Banking, brokerage, crypto wallet, and payment apps.
- OS security, firewall, account, keychain, and administrator settings.
- Private-key and token material such as `.ssh`, keychain, credential-store, and browser password surfaces.

Advanced users can narrow or extend this through `~/.hermes/desktop-control.json`; the Easy tier should keep the baseline on unless a future explicit local override is approved.

---

## Proposed Tool Surface (Unchanged)

`desktop_computer_status`, `desktop_computer_screenshot`, `desktop_computer_action`, `desktop_computer_grant_request`, `desktop_computer_cancel`.

---

## Implementation Phases (Updated)

### Phase 0 — Research Pack + Tech Decision (Ready Now)
- Port this enhanced plan into repo `docs/plans/`.
- Add ADR for Tauri choice + CLI fallback.
- Update `ROADMAP.md` and `desktop/README.md`.
- Define Easy/Standard/Advanced tiers and default blocklist.

### Phase 1 — Tool Schema + Server Registration
- Add the five `desktop_computer_*` tools to the Python plugin and TypeScript handlers.
- Ensure they advertise only when the feature flag is enabled.
- Verification: existing 22 desktop tools continue to work.

### Phase 2 — Observe Mode
- Reliable screenshot with display metadata.
- `desktop_computer_status` reporting permissions and current grant.

### Phase 3 — Assist Mode + Local Confirmation
- Grant store (`observe` / `assist` / `control`).
- Per-action prompts in TTY mode; fail-closed in daemon mode.
- Structured error responses for missing grants or blocked apps.

### Phase 4 — Windows Input MVP
- Native helper for mouse/keyboard primitives.
- Post-action screenshot option.
- Dangerous key-combo policy gates.

### Phase 5 — Overlay / Tray App + Seamless Pairing (Enhanced)
- [x] Create Tauri v2 shell sharing config with the CLI.
- [x] Tray icon + menu with Devices, Revoke, Pause, Settings.
- [x] Desktop dashboard chrome with a navigation-only sidebar, shared Chevron Compass mark, and sticky topbar daemon controls.
- [x] Always-on-top compact overlay pill showing current mode with dynamic width, anchored bottom-center in the monitor work area just above the taskbar.
- [x] Native assist/control grant prompt via tray Grant Requests view.
- [x] Task monitor window with timeline and clear.
- [x] Global emergency stop hotkey.
- [x] Pairing integration so new pairs can start the tray-managed daemon.
- [x] Dashboard and overlay refresh from the same daemon state after start, pause, emergency stop, grants, settings, and log clears.
- [x] Terminal / CLI tab that opens the remote TUI in a real terminal, turns the saved active relay into copyable standard `hermes-relay` shell, chat, daemon, status, tools, and doctor commands, and keeps `--remote` as an explicit one-off override instead of the default copied path.
- [x] Diagnostics tab that renders local pairing/config/shim/daemon checks and runs `hermes-relay doctor --json` through the bundled sidecar.
- [x] Verification: fresh profile can launch, see the compact status-only pill, and pause control from the tray or dashboard.

### Phase 6 — UI Automation Context (Optional)
- Active window metadata and element tree (redacted by default).

### Phase 7 — Sandbox Workspace (Longer Term)
- Safer isolated execution for shell/code/browser tasks.

---

## Acceptance Criteria (Updated)

MVP is complete when:
- Easy-tier user can pair, see a visible compact status pill, and pause control from the tray, dashboard, or hotkey.
- All actions in Control mode require an active grant and visible overlay.
- CLI daemon continues to work fully for advanced users.
- Local policy (`desktop-control.json`) allows advanced configuration.
- Emergency stop works from tray and hotkey.

Do not ship until the user can instantly understand from the UI whether Hermes is merely observing or actively controlling the desktop.

---

## Open Questions (Updated)

1. Should the Tauri app be installed by default or opt-in after first pair?
2. Code signing timeline for Windows production builds?
3. How do grants behave with multiple desktop clients?
4. Exact Windows process/window-title match list for the default blocklist baseline?
5. Should sandbox workspace be a separate `hermes-relay run` command?

**Resolved in first Windows tray pass:** the Windows installer defaults to the tray app, and the tray bundles the compiled CLI sidecar so pair/daemon/devices workflows do not require a separate PATH install. The overlay now follows the Conjure-style bottom-center pill pattern instead of a larger dashboard-adjacent card, and it is status-only/click-through so it cannot steal focus or expose Windows chrome.

---

## Related Files

- Original draft: `~/obsidian-vault/3. System/Projects/Hermes-Relay/Plans/Desktop Control — Computer Use + Overlay.md`
- Desktop client source: `desktop/src/`
- Relay plugin: `plugin/tools/desktop_tool.py`
- CLI commands: `desktop/src/commands/`

---

**Brief goal prompt for Codex (copy-paste ready):**

"Read the enhanced plan at docs/plans/desktop-control-computer-use-enhanced.md. Start by completing Phase 0: port the enhancements into the repo, add the Tauri decision ADR, and update ROADMAP.md. Then implement Phase 1 tool schemas while ensuring full backward compatibility with the existing desktop CLI. Focus on keeping the CLI as the primary surface and Tauri as the optional polished tray/overlay experience. Make the default pairing flow seamless for Easy-tier users."

**Current pairing/settings parity goal prompt:** use `docs/plans/2026-05-17-desktop-android-pairing-parity.md` instead for the next tray UI pass.
