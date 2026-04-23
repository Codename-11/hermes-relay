# Desktop CLI alpha.6 — Seamless-local dev experience

**Goal:** Use the Hermes agent installed on our hermes-host from local PC as seamlessly as if it were installed locally. Close the "works but feels remote" gap.

**Release target:** `desktop-v0.3.0-alpha.6`.

## Scope — in this release (6 agent workstreams, parallel)

| # | Feature | Agent | Files owned |
|---|---|---|---|
| 1+8 | **Workspace-awareness envelope + active-editor signal.** Client detects `cwd`/`git_root`/`git_branch`/`git_status_summary`/`hostname`/`platform`/`active_shell`, sends as `desktop.workspace` envelope on WSS auth. Also polls `tmux display-message -p` / VSCode active-buffer when available and sends `desktop.active_editor` hint every 5 s if `--watch-editor` is on. Surfaces snapshot in `doctor` + new `workspace` subcommand. Server-side: relay stashes as live session metadata (ephemeral, not persisted). Hermes-agent plugin hook deferred to alpha.7. | **A** | new: `src/workspaceContext.ts`, `src/activeEditor.ts`, `src/commands/workspace.ts`; modify: `src/transport/RelayTransport.ts`, `src/commands/doctor.ts`, `plugin/relay/channels/desktop.py` (optional — relay can discard fields it doesn't know) |
| 2 | **`hermes-relay update`.** Polls GitHub Releases API, compares `VERSION` constant to resolved latest `desktop-v*` tag, downloads + swaps binary. POSIX: atomic `rename` (running process's inode stays live). Windows: write to `hermes-relay.new.exe`, prompt user to restart, then shim self-move on next invocation. `--check` does dry-run, `--yes` skips confirm. | **B** | new: `src/commands/update.ts`, `src/updater.ts` |
| 3+4 | **Editor tool + interactive patch approval.** `desktop_open_in_editor(path, line?, col?)` detects `$EDITOR` / `code` / `cursor` / `vim` / `subl` / `nvim`, launches with file:line:col. `desktop_patch` upgraded: in interactive shell/chat mode, renders unified diff via built-in colorizer, prompts `y`/`n`/`e`/`r` (accept/reject/edit-first/reason); non-TTY modes (daemon, piped stdin) auto-accept only if the client told the agent to trust (via existing `toolsConsented` flag) — otherwise auto-reject with structured reason. | **C** | new: `src/tools/handlers/editor.ts`, `src/tools/editorDetect.ts`, `src/tools/patchApproval.ts`; modify: `src/tools/handlers/fs.ts`, `src/tools/router.ts`; report tool schema delta for `plugin/tools/desktop_tool.py` |
| 5 | **Conversation picker on connect.** Before shell attach / chat loop, if the relay's session API lists ≥2 sessions, render a numbered picker with age + first-prompt preview via arrow-key readline. `--session <id>` / `--new` bypass. Single-session case: auto-pick silently. Zero-session case: fresh start silently. | **D** | new: `src/sessionPicker.ts`; modify: `src/commands/shell.ts`, `src/commands/chat.ts` |
| 9+12 | **Clipboard bridge + screenshot.** `desktop_clipboard_read` / `desktop_clipboard_write` / `desktop_screenshot` handlers. Platform-shelled (Windows: `powershell Get-Clipboard` / `Set-Clipboard` / `Add-Type System.Drawing` → png; macOS: `pbpaste`/`pbcopy`/`screencapture -x`; Linux: `xclip -sel clip -o`/`xclip -sel clip`/`grim` or `scrot`). Tools return `{format: "text"|"png", bytes_base64?, path?, error?}`. Consent-gated per existing flow; no new permission. | **E** | new: `src/tools/handlers/clipboard.ts`, `src/tools/handlers/screenshot.ts`; modify: `src/tools/router.ts`; report schema delta for `plugin/tools/desktop_tool.py` |
| 13 | **`hermes` alias.** Install-time secondary symlink in `~/.hermes/bin/hermes` → `hermes-relay`. Windows: small `hermes.cmd` or `hermes.ps1` shim. Uninstall removes both. Saves muscle-memory users the `-relay` suffix. | **F** | modify: `desktop/scripts/install.sh`, `install.ps1`, `uninstall.sh`, `uninstall.ps1`, `README.md` |

**Integrator responsibilities (me):**
- All `desktop/src/cli.ts` edits (schema-widen): import + `KNOWN_COMMANDS` + dispatch + HELP line for new subcommands (`update`, `workspace`).
- All `plugin/tools/desktop_tool.py` additions (tool schema registrations for new handlers).
- `npm run smoke` coverage expansion to include new subcommands.
- Version bump to `0.3.0-alpha.6`, CHANGELOG entries, tag + push.

## Deferred to ROADMAP — revisit for alpha.7 / alpha.8 / v1.0

| # | Feature | Why deferred |
|---|---|---|
| 6 | Per-project session stickiness | Depends on alpha.7 hermes-agent plugin hook that actually consumes the workspace envelope; premature now. |
| 7 | Shell-history context hook | Requires careful shell-integration install (rc file edits) which our install philosophy currently avoids. Design pass needed. |
| 10 | Desktop notifications | Nice-to-have; daemon is new; let's see real-world latency first. |
| 11 | Env var passthrough | Security-sensitive; needs a per-var prompt UX design + threat model doc. Not a quick ship. |
| 14 | Global hotkey | OS-specific helper installers; out of scope for binary-only release. |
| 15 | Watch mode | Needs a DSL + safety bounds. Treat as its own feature branch. |

## Integration sequence

1. Update ROADMAP.md with the alpha.6 scope + deferred items.
2. Launch 6 parallel agents (A–F) with isolated file ownership.
3. I integrate cli.ts + desktop_tool.py after all agents report.
4. Expand `npm run smoke` to cover new subcommands.
5. Version bump + CHANGELOG entries.
6. Push dev → merge to main --no-ff → tag desktop-v0.3.0-alpha.6 → verify release.
7. Update live install on user's machine via `hermes-relay update` (dogfood).

## Non-goals for alpha.6
- No hermes-agent PR. Server stays stable; relay discards unknown envelope fields gracefully.
- No signed binaries. Still unsigned experimental.
- No npm publish. Still curl-binary-only.
- No multi-client routing. Single-client on the `desktop` channel remains.
