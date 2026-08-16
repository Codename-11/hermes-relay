# Local tool routing <ExperimentalBadge />

The big feature. The remote Hermes agent can read, write, search, execute, capture, paste, edit, and use explicitly brokered connected devices on **your machine** — not the server — through the same WSS relay it uses for chat. The agent's brain and conversation state stay on the host; your laptop is the hands.

## What the agent can do

Tools are registered in the `desktop` toolset. The agent sees them as normal tools alongside its usual ones — no special syntax needed, just "read my notes" or "run `tsc --noEmit`". The shipped toolset is grouped into seven families:

**Filesystem**

| Tool | Signature | Example use |
|------|-----------|-------------|
| `desktop_read_file` | `(path: string, max_bytes?: number)` | "Read my notes.md and summarize." |
| `desktop_write_file` | `(path: string, content: string, create_dirs?: boolean)` | "Write a quick-start guide to `~/Desktop/quickstart.md`." |
| `desktop_patch` | `(path: string, patch: string)` | Apply a unified diff. Strict — no fuzzy matching. Interactive approval prompt in `shell`/`chat` mode. |
| `desktop_search_files` | `(pattern: string, cwd?: string, max_results?: number, content?: boolean)` | "Find every file mentioning `DesktopToolRouter`." ripgrep with pure-Node fallback; skips `.git` / `node_modules` / `dist` / `.next` / `.cache`. |

**Shell**

| Tool | Signature | Example use |
|------|-----------|-------------|
| `desktop_terminal` | `(command: string, cwd?: string, timeout?: number)` | "Run `tsc --noEmit` and tell me what's broken." `bash -lc` on POSIX, `cmd /c` on Windows. |
| `desktop_powershell` | `(script: string, cwd?: string, timeout?: number, device?: string)` | Runs a private temporary PowerShell script (`pwsh` preferred, falls back to `powershell`) with complete UTF-8 stdout/stderr, native exit status, and explicit truncation metadata. |

**Process management**

| Tool | Signature | Example use |
|------|-----------|-------------|
| `desktop_spawn_detached` | `(command: string, cwd?: string)` | Start an unref'd background process; returns its PID and a log path. |
| `desktop_list_processes` | `()` | Enumerate running processes (`tasklist` / `ps`). |
| `desktop_kill_process` | `(pid: number)` | Terminate a process by PID. |
| `desktop_find_pid_by_port` | `(port: number)` | Find which process owns a port (`netstat` / `lsof` / `ss`). |

**Job API** (long-running tasks with persistent logs that survive a daemon restart)

| Tool | Signature | Example use |
|------|-----------|-------------|
| `desktop_job_start` | `(command: string, cwd?: string)` | Launch a long task; logs stream to `~/.hermes/desktop-jobs/<id>/`. |
| `desktop_job_status` | `(id: string)` | Check whether a job is running, finished, or failed. |
| `desktop_job_logs` | `(id: string)` | Tail a job's captured stdout/stderr. |
| `desktop_job_cancel` | `(id: string)` | Stop a running job (`taskkill /T` on Windows so the whole tree dies). |
| `desktop_job_list` | `()` | List all known jobs and their states. |

**File transfer**

| Tool | Signature | Example use |
|------|-----------|-------------|
| `desktop_copy_directory` | `(source: string, dest: string)` | Recursive copy via `fs.cp`. |
| `desktop_zip` | `(source: string, dest: string)` | Create a zip archive (`tar` → `zip` → PowerShell probe). |
| `desktop_unzip` | `(source: string, dest: string)` | Extract a zip archive. |
| `desktop_checksum` | `(path: string, algorithm?: string)` | Streamed `sha256` / `sha1` / `md5` of a file. |

**User-context bridges**

| Tool | Signature | Example use |
|------|-----------|-------------|
| `desktop_clipboard_read` | `()` | Read the user's system clipboard. Windows / macOS / Linux (Wayland-first). |
| `desktop_clipboard_write` | `(text: string)` | Write text to the system clipboard. |
| `desktop_screenshot` | `(display?: number \| string, save_to?: string)` | Capture all monitors (default), primary (`'primary'`), or a specific display (`1` / `2` / ...). Returns base64 + dimensions, or saves to `save_to` and returns the path. |
| `desktop_open_in_editor` | `(path: string, line?: number, col?: number, wait?: boolean)` | Open a file in the user's editor. Detects `$VISUAL` → `$EDITOR` → `code` / `cursor` / `subl` / `nvim` / `vim` on PATH → platform fallback. Injects `-g path:line:col` for GUI editors. |

**USB devices**

| Tool | Signature | Example use |
|------|-----------|-------------|
| `desktop_usb_devices` | `(reason?: string)` | List USB devices visible to the selected desktop host. |
| `desktop_usb_run` | `(executable: string, arguments?: string[], cwd?: string, timeout?: number, reason?: string)` | Direct-spawn a native or vendor USB utility without shell parsing. |

**Android Debug Bridge service**

| Tool | Signature | Example use |
|------|-----------|-------------|
| `desktop_adb_devices` | `(reason?: string)` | List devices visible to the local ADB backend. |
| `desktop_adb_shell` | `(serial: string, command: string, timeout?: number, reason?: string)` | Run one bounded Android shell operation on an explicit serial. |
| `desktop_adb_push` | `(serial: string, source: string, destination: string, timeout?: number, reason?: string)` | Push one local file to an explicit serial. |
| `desktop_adb_pull` | `(serial: string, source: string, destination: string, timeout?: number, reason?: string)` | Pull one device file to the local PC. |
| `desktop_adb_install` | `(serial: string, apk: string, replace?: boolean, timeout?: number, reason?: string)` | Install one local APK on an explicit serial. |
| `desktop_adb_logcat` | `(serial: string, lines?: number, timeout?: number, reason?: string)` | Return a bounded recent logcat snapshot. |

The two raw USB tools appear when the selected host's USB policy is Ask or
Allow. The six ADB tools additionally require a working ADB backend. Structured
mode omits the four general shell/process escape hatches but retains these
separately gated USB paths. A further **computer-use** family
(`desktop_computer_status` / `_screenshot` / `_action` / `_grant_request` /
`_cancel`) is registered for full local UI control but ships **experimental and
off by default**. Enable it persistently with `hermes-relay computer-use enable`
or from the Windows tray; one-process flag/env overrides remain available. Host
input still fails closed without a task-scoped grant approved locally.

All tools run under a **30-second AbortController** ceiling enforced by the router. `desktop_terminal` / `desktop_powershell` accept a per-call `timeout` (seconds, per the wire spec — converted to ms internally) that's clamped to a 10-minute maximum. `desktop_screenshot` has its own 10 s timeout and 50 MB cap. `desktop_clipboard_*` 5 s timeout and 10 MB cap.

Every client-routed tool also accepts `device`, using the stable device ID or an
unambiguous computer name shown by `desktop_health`. It is optional with one
connected PC and required when several desktop daemons are online. An
untargeted call fails closed rather than choosing the latest heartbeat.

Prefer the typed file, process, job, transfer, screen, clipboard, and connected-
device tools for ordinary work. `desktop_terminal`, `desktop_powershell`,
detached commands, and command jobs are intentionally labeled `system.execute`:
because they run as your Windows user, they can indirectly access files and
attached hardware. **Standard** mode withholds those four escape hatches.
Newly paired hosts default to **Ask Every Time**, which routes every available
command, file, screen/input, and USB operation through the local approval card.
Raw USB can independently be Off, Ask through the local
approval card for every operation, or can Allow after explicit confirmation.
It governs host-wide direct execution of native/vendor USB utilities and all
enabled USB services; it is not scoped to one physical device. ADB remains a
secondary service and its device operations require an exact serial. Full
Access overrides every available capability; changing one gate selects a matching
preset automatically and otherwise creates a Custom policy. Camera and microphone remain unavailable and unadvertised until
controlled paths exist.

The router heartbeats `desktop.status` every 30 s, advertising the full handler-name list, so the server's `desktop` channel knows which tools your client can service. Servers ping `/desktop/_ping?tool=<name>` to fail fast when a tool isn't advertised.

## How it works

1. You pair + connect via `hermes-relay` (bare = shell/TUI mode by default) or `hermes-relay chat`.
2. On connect, the CLI's `DesktopToolRouter` attaches to the relay's `desktop` channel and heartbeats every 30 s with the list of advertised tools.
3. Hermes's Python-side `desktop_tool.py` handlers register with `tools.registry` (same pattern as `android_tool.py`) — the agent sees `desktop_read_file` as just another tool.
4. When the agent calls a `desktop_*` tool, the Python handler HTTP-POSTs to `localhost:8767/desktop/<tool_name>` on the host.
5. The relay's `desktop` channel forwards the call over WSS to the connected CLI.
6. The CLI's `DesktopToolRouter` dispatches to an in-process handler (`fs.ts`, `terminal.ts`, `powershell.ts`, `process.ts`, `jobs.ts`, `transfer.ts`, `search.ts`, `clipboard.ts`, `screenshot.ts`, `editor.ts`).
7. The handler runs on **your** machine, returns the result, and the response bubbles back: CLI → relay → Python → Hermes → agent.
8. Typical round-trip: 60–100 ms for a simple command.

No hermes-agent core changes. It's the same pattern the Android client uses for `android_tap` / `android_screenshot` / etc. — just swapping the bridge endpoint for a desktop one.

### `desktop_open_in_editor` and interactive patches

In `shell` / `chat` modes (interactive TTY, not daemon, not piped stdin), the router carries an `interactive: true` flag. Two handlers use it:

- **`desktop_open_in_editor`** — launches the user's editor with the file at the requested line/col. Useful for "open this for me to review" agent flows.
- **`desktop_patch`** — agent-proposed patches render as ANSI-colored unified diffs (green/red/cyan, NO_COLOR/isTTY aware) on stderr, then prompt:
  ```
  Apply patch? [y]es / [n]o / [e]dit / [r]edraw  ›
  ```
  - **`y`** — apply the patch (strict, no fuzz).
  - **`n`** — reject; agent gets a structured error.
  - **`e`** — open the patch in `$EDITOR` and re-read on close (so you can hand-tweak before applying).
  - **`r`** — redraw the diff (in case it scrolled out).

In **non-interactive** modes (`daemon`, piped stdin), `desktop_patch` auto-rejects with a structured reason. The daemon **never** silently applies an agent-proposed edit.

### Native paste pipeline (alpha.13/14)

The `Ctrl+A v` chord and the `chat` REPL's `/paste` command share the same plumbing:

1. Client reads its own clipboard via `captureClipboardImage()` (Windows: PowerShell with **`-STA`** flag — alpha.10 fixed an MTA bug that returned null on a populated clipboard; macOS: `pngpaste`; Linux: `wl-paste --type image/png` → `xclip` fallback).
2. Validates magic bytes (PNG `89 50 4E 47` / JPEG `FF D8 FF` / WEBP `RIFF....WEBP`) to prevent content-type laundering.
3. POSTs the bytes to `/clipboard/inbox` on the relay (the new shared `stageClipboardImageToInbox(url, token)` helper).
4. In `Ctrl+A v` mode: types `/paste\r` into the PTY so the upstream Hermes TUI consumes it.
5. In `/paste` mode: stages the image with the server via the `image.attach.bytes` RPC; the next `prompt.submit` ships with the image attached.

Server-side, the fork's `_enrich_with_attached_images` pipeline handles multimodal payload plumbing and session-scoped image state — same path a local Hermes paste takes.

Drag-drop a file from Explorer onto Windows Terminal also works for image attach (the server's `input.detect_drop` recognizes the dropped path).

## Consent gate

On your first `shell` or `chat` session per relay URL with tools enabled, you'll see a prompt:

```
Desktop tools are about to be exposed to the remote Hermes agent.
The agent can read/write files, run shell commands, and search your filesystem.
This is AGENT-CONTROLLED access. Only use with trusted Hermes installs.
Type 'yes' to enable, or rerun with --no-tools to disable.
>
```

Only `yes` (case-insensitive) enables. Anything else (`y`, `no`, `Enter`, `Ctrl+C`) denies.

Consent is stored per-URL in `~/.hermes/remote-sessions.json` as `toolsConsented: true` and sticks across sessions. You won't be asked again for this relay until the URL changes or you wipe the session.

**Kill-switches:**
- `--no-tools` on any subcommand suppresses the router entirely for that invocation.
- Non-TTY stdin (e.g. piped invocations) fails closed — never auto-consents.
- Delete the session record (or set `toolsConsented: false` in the file) to force re-prompt.
- `daemon` mode fails closed without `toolsConsented: true` already on the record. The `--allow-tools` flag (only valid alongside `--token`) is the explicit-trust escape hatch for service-managed installs.

## Safety walls

The desktop tools run **in-process on your machine** with your full user privileges. That's a real risk — a compromised relay or a misaligned agent could ask to `rm -rf /`, exfiltrate tokens, or rewrite your `.ssh/config`. The walls:

1. **Consent per-URL, not per-run.** Once you say yes to `ws://hermes.example.com`, the agent on THAT server has persistent tool access. A different URL re-prompts.
2. **User privilege by default; explicit elevation only.** All tools inherit the daemon's privilege. The Windows tray remains unprivileged and requests UAC only when you explicitly choose **Restart as Administrator...**. **Return to user mode** stops the elevated daemon once and starts it normally. While an Administrator daemon is active, approved shell and input actions run elevated; the tray labels that state and displays a stronger warning for an active control grant.
3. **Per-call AbortController ceiling.** 30 seconds per tool call hard stop. A long-running compromise would trip this.
4. **Handler implementations are defensive:**
   - `desktop_read_file` caps at `max_bytes` (default 1 MB) and truncates with a marker.
   - `desktop_write_file` refuses to create parent dirs unless `create_dirs: true` is set.
   - `desktop_patch` is strict — any hunk mismatch aborts the whole patch. No fuzzy matching. Better to fail than to corrupt. **Interactive approval** in `shell`/`chat` mode; auto-rejects in daemon/non-interactive.
   - `desktop_terminal` uses `bash -lc` on POSIX, `cmd /c` on Windows — no shell injection beyond what the command itself carries (it IS the command).
   - `desktop_search_files` skips `.git` / `node_modules` / `dist` / `.next` / `.cache` by default.
   - `desktop_clipboard_*` capped at 10 MB / 5 s timeout in either direction.
   - `desktop_screenshot` capped at 50 MB / 10 s timeout; cleans up tempfiles when not saving to a user-supplied path.
5. **No stdin.** `desktop_terminal` pipes `/dev/null` to the child — a command that reads stdin hangs up immediately rather than blocking the handler.
6. **SIGKILL on abort/timeout.** No chance for a signal handler to trap and keep running.

**What we DON'T have yet (v1.0 targets):**
- Command allowlist / blocklist per session.
- Destructive-verb confirmation modal (like the Android bridge's `send_sms`/`call` prompts).
- Per-tool sandbox (e.g., restrict `desktop_read_file` to a project root).
- Code signing (`hermes-relay` binary is currently unsigned).

## Computer-use (experimental)

Beyond the 23 default tools, an **experimental computer-use family** (`desktop_computer_status` / `_screenshot` / `_action` / `_grant_request` / `_cancel`) drives full local mouse/keyboard UI control. It's **off by default** and gated in three stages:

1. **Enable** — run `hermes-relay computer-use enable` (or choose **Enable desktop use…** from the Windows tray) to persist the preference in `~/.hermes/desktop-settings.json`. Restart the daemon to apply it; the tray restarts automatically. `--experimental-computer-use` and `HERMES_RELAY_EXPERIMENTAL_COMPUTER_USE=1` are one-process enable overrides; `--no-computer-use` always wins for that invocation.
2. **Observe grant** — the agent requests an `observe` grant before screenshot/status use. Observe grants allow reading only; they cannot inject input.
3. **Assist/control approval + act** — `desktop_computer_grant_request(mode="assist"|"control")` must be **approved locally** before `desktop_computer_action` can send input. The grant is task-scoped and time-boxed (15 minutes by default, one hour maximum); `hermes-relay computer-use cancel`, the tray cancel action, disabling desktop use, or `desktop_computer_cancel` ends it.

### Approving a grant

How you approve depends on how the client is running:

- **Interactive (`shell` / `chat` on a TTY):** a visible prompt appears in your terminal — type `yes` to approve.
- **Headless (`daemon`, no TTY):** approvals route through the local file bridge at `~/.hermes/grant-bridge` (override with `HERMES_RELAY_GRANT_BRIDGE_DIR`). The daemon writes `request-<id>.json`; `hermes-relay grants` reviews it and writes the matching response. Without a local approver, the request times out and input stays failed-closed.
- **Windows tray:** a pending request raises a dedicated borderless approval card without opening the main management UI. The card identifies the host, operation scope, and reason; it never auto-approves.

Use `hermes-relay computer-use status` to see the persisted preference, daemon privilege, active grant/expiry, pending count, and whether a restart is required. Disabling desktop use requests exact-once cancellation from the running daemon. The Windows tray displays the same state and warns explicitly when an Administrator control grant is active.

Input injection is currently **Windows-only**; `status` / `screenshot` work cross-platform.

### Computer-use engines

The `desktop_computer_*` contract stays the same regardless of the local engine.
On Windows you can choose:

| Engine | Behavior |
|--------|----------|
| **CUA Driver** | Preferred/default structured engine. Targets a named PID and window through UI Automation and background dispatch, and can show an animated virtual cursor without moving the physical pointer. |
| **Windows input** | Explicit compatibility backend. Uses the original Windows input path and can depend on foreground focus or move the physical pointer for some actions. |

Check the detected runtime and current selection from the CLI:

```powershell
hermes-relay computer-use status --json
hermes-relay computer-use engine cua
hermes-relay computer-use cursor on
hermes-relay computer-use cua status
hermes-relay computer-use cua health
hermes-relay computer-use cua check-update
hermes-relay computer-use cua install --yes
hermes-relay computer-use cua update --yes
```

The management UI exposes the same choices under **Settings → Computer
control**. Selecting `cua` succeeds only when Hermes finds the canonical runtime
at `%USERPROFILE%\.cua-driver\packages\current\cua-driver.exe` and verifies its
supported version, manifest identity, allowlisted tools, and non-unrestricted
permission mode. Accessibility health is an explicit diagnostic while the
temporary Windows workaround for trycua/cua#3103 is active. Hermes does not trust whichever `cua-driver.exe`
happens to appear first on `PATH`. If the runtime is missing, incompatible, or
degraded, the UI explains why and a new control session can use Windows input
compatibility. Backend selection is made once per authenticated control session;
changing the preference never switches an active session underneath an action.

CUA sessions follow Hermes control sessions. Hermes derives the driver session
identity locally; an agent cannot choose or share a cursor ID. Each action uses
a fresh target-window snapshot, and element handles are wrapped in a short-lived,
single-use Hermes token bound to the control authority, grant, PID, window, and
snapshot generation. The handler returns a post-action snapshot so the caller
can verify whether the expected state actually changed. Grant expiry, cancellation, disconnect, policy change,
emergency stop, or daemon shutdown revokes the associated local state.

**Background is mandatory for CUA.** A target that cannot accept background input
returns a failure instead of silently bringing itself forward. The foreground
escalation control is reserved for a later explicit-authority path; this release
always reports it off, even if an older preview saved the preference, and Full
Access cannot enable it. The animated agent cursor is optional. It is a visual overlay
with a distinct session identity—not another Windows hardware pointer—and the
operator's physical cursor remains untouched by CUA actions.

CUA Driver is a separate optional dependency. Hermes-Relay does not bundle or
silently install/update it. `computer-use cua status|check-update` are
read-only; `install|update` require explicit `--yes` confirmation. Hermes uses
the canonical upstream package, validates versioned GitHub release metadata and
installer SHA-256, and refuses updates outside `>=0.19.3,<0.20.0`. These checks
do not claim a Windows publisher signature. Hermes executes the verified
temporary installer under a sanitized environment, then validates the canonical
`packages/current` binary, driver manifest, and permission mode. The UI and
`computer-use cua health` can recheck accessibility health without changing
the selected backend. Hermes forces CUA telemetry off for
its child invocations; any future telemetry opt-in belongs to the local
operator. `hermes-relay update` continues to manage only the Hermes-Relay CLI
and Windows UI.

Window-scoped snapshots and element actions use the selected backend. A normal
full-display screenshot remains on the separate read-only `system_capture` path;
using it does not switch the input backend. The local audit and UI Activity
timeline show bounded high-level evidence—backend, background dispatch, control
session, target app/window identifiers, action, phase, and verification state.
Accessibility text, screenshot bytes, entered values, and raw driver responses
are redacted from the activity drilldown.

::: warning CUA does not sandbox raw commands
CUA's PID/window and snapshot boundaries apply to structured computer-control
actions. If Commands, PowerShell, or terminal execution is allowed, the agent
can still invoke ordinary OS automation through that broader trusted path.
Disable raw command access when scoped computer control is part of your threat
model. Full Access never bypasses authenticated targeting, sensitive-surface
checks, UAC or Windows-session boundaries, audit, health checks, or emergency
stop.
:::

## Diagnosing routing

If the agent says "desktop_terminal is not available" or calls time out immediately:

```bash
# On the server, verify the channel sees your client
ssh you@<host> curl -s "http://127.0.0.1:8767/desktop/_ping?tool=desktop_terminal"
```

Expected (the default-advertised set — `desktop_computer_*` appears only when desktop use is persistently enabled or a one-process override is active):
```json
{
  "connected": true,
  "advertised_tools": [
    "desktop_read_file",
    "desktop_write_file",
    "desktop_patch",
    "desktop_search_files",
    "desktop_terminal",
    "desktop_powershell",
    "desktop_spawn_detached",
    "desktop_list_processes",
    "desktop_kill_process",
    "desktop_find_pid_by_port",
    "desktop_job_start",
    "desktop_job_status",
    "desktop_job_logs",
    "desktop_job_cancel",
    "desktop_job_list",
    "desktop_copy_directory",
    "desktop_zip",
    "desktop_unzip",
    "desktop_checksum",
    "desktop_clipboard_read",
    "desktop_clipboard_write",
    "desktop_screenshot",
    "desktop_open_in_editor"
  ],
  "client_status": { ... },
  "last_seen_at": 1776964298.02,
  "pending_commands": 0
}
```

If `connected: false`:
- No active shell/chat/daemon session is connected. Start one.
- `--no-tools` was used. Retry without it.
- Consent was denied. Delete the session record or re-pair.

If `connected: true` but the agent still says the tool is missing:
- The toolset isn't enabled for this Hermes session. Inside the shell, ask Hermes: "enable the `desktop` toolset for this session." Or add it to your Hermes config's default enabled toolsets.
- The plugin wasn't loaded on the gateway. See the `hermes-relay-self-setup` skill — `plugins.enabled` in `~/.hermes/config.yaml` must include `hermes-relay`.

## Daemon mode — tools without an open shell

`hermes-relay daemon` runs the WSS connection + tool router headless, so the agent can reach your machine while you're in another window or VS Code or off making coffee. Use `hermes-relay daemon start` to run it in the **background** (no console window, survives closing the terminal), `daemon status` to check it, and `daemon stop` to stop it. See [Subcommands → daemon](./subcommands.md#hermes-relay-daemon) for full lifecycle/log details.

Want to see what the agent actually ran on your machine? `hermes-relay audit` lists recent `desktop_*` activity from a local log. The management UI previews the latest three events and opens each event into a lifecycle stepper plus bounded request, stdout, stderr, result, exit, timing, and truncation details. Errors have a dedicated failure panel. Screenshot events may retain bounded local evidence for a larger viewer; Settings controls Off/1-day/7-day/30-day retention and clearing Activity removes it. Sensitive request inputs remain excluded from the JSON audit log.

The daemon records one interruption event when automatic reconnect begins and a
recovery event when it succeeds rather than adding one row per backoff attempt.
The Overview shows retry attempt/timing and supports **Retry now** or an explicit
disconnect. When the management UI is hidden, connection loss and restoration
use the same compact local-card language as permission requests; no duplicate
card appears while the UI is already open.

`daemon start` covers "background, this session." On Windows, **Start UI at sign-in** registers the optional tray as a per-user login entry. **Start daemon with UI** separately opts into connecting remote access when the tray launches and defaults off for existing installs. Neither is a Windows service. For Linux/macOS or a machine-level lifetime, wrap foreground `hermes-relay daemon` with your service manager of choice.

## Related

- [Pairing](./pairing.md) — must pair before tools work.
- [Subcommands](./subcommands.md) — `--no-tools` flag, daemon mode, `tools.list` introspection.
- [Troubleshooting](./troubleshooting.md) — common tool routing errors.
