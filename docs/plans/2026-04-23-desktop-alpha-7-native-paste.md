# Desktop CLI alpha.7 — Native image paste in `hermes-relay chat`

**Goal:** Land native image paste in the `hermes-relay chat` REPL. Users type `/paste` (system clipboard), `/screenshot` (primary display capture), or `/image <path>` (file on disk), and the NEXT `prompt.submit` ships with the image attached so the vision-capable model sees it in the same turn. Parity with Claude Desktop's paste UX, minus OS-level Ctrl+V — terminals don't deliver image bytes to stdin, that's an OS-level constraint we can't paper over.

**Release target:** `desktop-v0.3.0-alpha.7`. Spans TWO repos — `Codename-11/hermes-relay` (client + this plan) and the `Codename-11/hermes-agent` fork on the `axiom` branch (server tui_gateway RPC).

## Architecture finding

The fork's `tui_gateway/server.py` ALREADY implements the multimodal consumer side. The `@method("clipboard.paste")`, `@method("image.attach")`, and `@method("input.detect_drop")` RPCs exist today, and `_enrich_with_attached_images` wires them into every `prompt.submit` — appending whatever's in `session["attached_images"]` to the outgoing request before the model call. The hard part (multimodal payload plumbing, session-scoped image state, vision-model routing) is already done.

The gap is narrow and specific: those RPCs assume server-local filesystem access — they read `~/Pictures/...`, poke the host clipboard, screenshot the server's display. That doesn't work for a remote Node client connecting via WSS from its own machine. The client has the clipboard / screen / file; the server has the model. We need one new RPC that lets the client push bytes to the server, plus client slash commands that capture the bytes locally and call it.

## Scope — two workstreams, two repos

| Workstream | Repo / branch | Owned files |
|---|---|---|
| **Fork** | `Codename-11/hermes-agent` → `feat/image-attach-bytes` → merge to `axiom` | `tui_gateway/server.py` (ONE new `@method`) |
| **Client** | `Codename-11/hermes-relay` → `feat/native-paste` | new `desktop/src/chatAttach.ts`; modify `desktop/src/commands/chat.ts` |

### Fork workstream — `image.attach.bytes` RPC

ONE new `@method("image.attach.bytes")` handler on `tui_gateway/server.py`. Request shape:

```json
{"session_id": "<id>", "format": "png|jpeg|webp", "bytes_base64": "...", "filename_hint": "optional.png"}
```

Behavior: validate magic bytes match the claimed `format` (PNG `89 50 4E 47`, JPEG `FF D8 FF`, WEBP `RIFF....WEBP`); reject mismatches to prevent content-type laundering. Decode and write to `~/.hermes/images/remote_<unix_ts>_<rand6>.<ext>`. Append the written path to `session["attached_images"]` — the existing list `_enrich_with_attached_images` reads. Return `{ok: true, saved_path, size_bytes}`.

Everything after the attach works unchanged because `_enrich_with_attached_images` already handles the hard part.

### Client workstream — `chatAttach.ts` + REPL commands

New `desktop/src/chatAttach.ts` with three capture functions:

- `captureClipboardImage()` — Windows: PowerShell `Get-Clipboard -Format Image` piped to a temp PNG; macOS: `pngpaste` if present, else `osascript` AppleScript clipboard bridge; Linux: `wl-paste --type image/png` (Wayland), fallback `xclip -selection clipboard -t image/png -o` (X11). Returns `{format, bytes, width?, height?}` or `{error: "no image in clipboard"}`.
- `captureScreenshot()` — Windows: `System.Drawing.Bitmap.CopyFromScreen` via temp `.ps1` (same pattern as alpha.6's screenshot handler); macOS: `screencapture -x -t png`; Linux: `grim` → `scrot` → `import` fallback chain.
- `readImageFile(path)` — `fs.readFile` + sniff magic bytes for format + size check (enforce 20 MB cap client-side so we fail before a 50 MB base64 payload hits the wire).

All three return a uniform `{format, bytes_base64, size_bytes, source_desc}` shape. `source_desc` is the feedback line text ("clipboard 1920×1080, 234 KB" / "screenshot primary display, 412 KB" / "file my-diagram.png, 89 KB").

New REPL slash-command branch in `desktop/src/commands/chat.ts`:

- `/paste` → `captureClipboardImage()` → RPC `image.attach.bytes` → echo `[📎 clipboard 1920×1080, 234 KB — attached to next message]`
- `/screenshot` → `captureScreenshot()` → RPC → echo
- `/image <path>` → `readImageFile(path)` → RPC → echo

User's next typed line (normal chat input, not starting with `/`) sends as usual; server enriches automatically via `_enrich_with_attached_images`. Stack multiple attachments if the user `/paste`s three times before sending — they all ship on the next `prompt.submit`.

## Relay

No changes needed. The `tui` channel is a transparent RPC forwarder — multimodal envelopes pass through unchanged. `plugin/relay/channels/*` is untouched this release.

## Non-goals for alpha.7

- **No Ctrl+V terminal keybinding.** Terminals don't paste image bytes to stdin; that's an OS-level pipeline gap we can't close from Node. `/paste` is the ergonomic equivalent and the only honest path.
- **No Kitty / iTerm2 inline image protocols.** Defer to alpha.10+ if someone asks. Most terminals don't support them and the slash-command route works in any TTY.
- **No PTY shell mode support.** The `shell` subcommand pipes raw bytes to a remote `hermes` CLI over the `terminal` channel; that CLI has its own paste handling (it's a separate process on the server side). Out of scope.
- **No multimodal `prompt.submit` payload extension.** The attach-then-submit pattern is cleaner, matches the existing server-side state model, and avoids schema churn on the hottest RPC in the system.
- **No fork PR upstream yet.** Ship to `axiom` first, validate in our deploy, then consider upstream.

## Deploy sequence

1. **Fork:** Push branch `feat/image-attach-bytes` on `Codename-11/hermes-agent`. Merge to `axiom` (the deployed branch).
2. **hermes-host:** `git pull` on the `axiom` checkout + `systemctl --user restart hermes-gateway`. New RPC is live.
3. **hermes-relay repo:** Ship `desktop-v0.3.0-alpha.7` with `chatAttach.ts` + the REPL slash commands. CI builds + tags.
4. **User:** Runs `hermes-relay update` (shipped in alpha.6) on their box to pick up the new CLI.
5. **End-to-end:** Client `/paste` → `image.attach.bytes` → server recognizes → image reaches the vision model on next prompt.submit.

## Fallback behavior when server hasn't been updated yet

If hermes-host still runs an older `tui_gateway/server.py` (no `image.attach.bytes` method), the client's RPC call gets `method not found` back. The client catches this specific error and prints `[attach failed: method not found — server may need axiom rollout]` to stderr, then continues the REPL cleanly. No crash, no stuck state — the user can still send text. Surfacing the exact error points Bailey (or any operator) at the fix immediately.

## Validation

**Client-side:**
- `npm run smoke` — existing 5 assertions still green after the `chatAttach.ts` addition.
- Manual: `/paste` with an image in clipboard, `/screenshot`, `/image ./test.png` — each echoes feedback, each appends to next prompt.submit.

**Server-side:**
- `python -m py_compile hermes-agent-fork/tui_gateway/server.py` (syntax check).
- `python -m unittest` against any existing `tui_gateway` test module (if present) — new handler gets magic-byte-mismatch + oversized-payload + unknown-format unit tests.

**Live end-to-end on hermes-host:**
- Paste a screenshot in `hermes-relay chat` REPL → ask "what do you see?" → confirm the model describes the actual image content (not a generic "I can't see images" disclaimer, which would indicate the attach never landed).

## Coordination notes

- Parallel agents own `desktop/src/chatAttach.ts` and the fork's `tui_gateway/server.py` respectively. This docs-sync agent (me) does NOT edit those files.
- Integrator handles the version bump to `0.3.0-alpha.7`, the CHANGELOG `[Unreleased]` → release cut, and the tag push after both workstreams land on `dev`.
- DEVLOG entry is the integrator's job at release time, not this sync pass.
