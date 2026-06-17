# Terminal Ergonomics + Chat-Parity Plan — tracking checklist

> Scoped 2026-06-17 from a 3-way comparison (current Android Terminal, upstream desktop
> TUI `ui-tui/src`, upstream web dashboard `/chat`). Companion to the e2e UX audit
> ([`2026-06-16-e2e-ux-audit.md`](./2026-06-16-e2e-ux-audit.md)).
> Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[-]` deferred/reclassified.

**Framing (read first).** "Terminal" means three different things across the surfaces:
the Android **Terminal tab is a raw tmux-backed remote shell** (xterm.js/WebView over relay
WSS); the upstream **desktop TUI and web "Chat" tab are the *agent* experience** styled as a
terminal — there is no raw shell there. The Android **Chat screen already is** the native
analog of that agent experience.

**Non-goal / guardrail.** Phase 2 does **NOT** replace the native Compose Chat with a
TUI/xterm surface. The web dashboard pipes the Ink TUI through a PTY only because it's cheap
to embed; Android already reimplements the agent natively, which is better on a phone. Phase 2
**enhances existing native components** (which already exist — see each item), it does not
rebuild the chat view.

Build note: Kotlin changes are built via `assembleSideloadDebug` only on explicit request
(Studio is the normal path). Review-before-deploy: edits land, user reviews, then build/deploy.

---

## Phase 1 — Terminal ergonomics (the raw shell, contained wins)

Reference: current gaps in `TerminalScreen.kt` / `TerminalWebView.kt` / `ExtraKeysToolbar.kt`
+ render parity from the web dashboard's xterm config.

- [ ] **1.1 Command history (↑ recall / recents).** Track submitted lines per tab; expose a
  recents affordance (↑ in the extra-keys row, or a small recents chip) to re-insert prior
  commands into the PTY input.
  *Why:* no history recall today — users retype or scroll-and-copy. The single biggest
  shell-ergonomics gap on mobile.
  *Accept:* a recent command can be recalled and sent without retyping.

- [x] **1.2 Explicit Copy-selection button.** Added a COPY key in `ExtraKeysToolbar` beside
  PASTE; reads the xterm selection via a new `window.getSelectionText()` JS hook
  (`index.html`) and commits non-empty text to the system clipboard (`TerminalScreen.kt`).
  *Accept:* selecting text + tapping Copy puts it on the system clipboard. ⚠️ on-device verify.

- [x] **1.3 Keyboard show/hide toggle.** Added a `⌨` key wired to `WindowInsetsControllerCompat`
  ime show/hide on the active tab's WebView (focuses xterm via the existing `window.focusTerminal`
  on show). `ExtraKeysToolbar.kt` + `TerminalScreen.kt`.
  *Accept:* the key reliably raises/dismisses the keyboard. ⚠️ on-device verify (IME insets vary).

- [ ] **1.4 Unread-activity dots on background tabs.** Inactive tabs already keep receiving
  output (stacked WebViews); badge a tab when new output arrives while hidden, clear on focus.
  *Why:* no signal that a background session produced output.
  *Accept:* a hidden tab that emits output shows a dot until viewed.

- [ ] **1.5 Scrollback position indicator.** Surface a lightweight position hint for the
  10k-line buffer (scrollbar overlay or "↑ N lines" pill); WebView scrollbar is currently off.
  *Why:* no sense of position when reviewing long output.
  *Accept:* scrolling shows where you are in scrollback.

- [ ] **1.6 Render parity with the web dashboard's xterm.** Adopt the proven web config:
  WebGL addon (DOM fallback on low-end), OSC 52 clipboard, confirm the `\x1b[RESIZE:cols;rows]`
  resize contract, and the JetBrains-Mono→Cascadia→Fira font cascade.
  *Why:* the web `/chat` terminal is the right *rendering* reference; matching it improves
  crispness, clipboard, and resize robustness.
  *Accept:* parity items present; no regression in reflow/selection.

- [-] **1.7 Inline rename + "don't ask again" on close.** Pencil affordance on the tab chip
  (rename is buried in the info sheet) and a remembered default for the detach-vs-kill dialog.
  *Deferred:* nice-to-have polish, lower priority than 1.1–1.6.

---

## Phase 2 — Chat → desktop-TUI parity (enhance the EXISTING native view)

Every item below refines a component that **already exists** — this phase adds polish/affordances,
it does not introduce a terminal surface or replace the chat view.

- [ ] **2.1 Collapsible tool/thinking tree.** Unify `ThinkingBlock` / `ToolProgressCard` /
  `CompactToolCall` / `SubagentLane` into a consistently nestable, collapsible presentation
  (chevrons + rails like the TUI's `ToolTrail`), with expand/collapse persisted per turn.
  *Verify first:* how nested/collapsible today vs. flat.
  *Why:* the TUI's tree is its standout "readable agent work" affordance.
  *Accept:* tool/thinking blocks collapse/expand and nest legibly.

- [~] **2.2 Clean per-session context-usage bar (pulled forward 2026-06-17 at user request).**
  `ContextMeterBar` already renders `contextUsage`; make it cleanly mirror the desktop TUI's
  per-session gauge: filled bar + percent + token counts (`used / window`) + color-coded health
  (green <50% · yellow 50–80% · orange 80–95% · red ≥95%). Context is **per session and loaded
  per session** — verify the value resets/loads on session switch.
  *Why:* the desktop status line's context gauge is its clearest "where am I" signal.
  *Accept:* the bar shows tokens + %, color-grades by fullness, and tracks the active session.
  Later (separate): also fold session duration into the bottom status strip.

- [ ] **2.3 Input history recall in the composer.** `CommandPalette` + slash list already
  exist; add ↑/↓ recall of previously *sent prompts* (not just slash commands) when the
  composer is empty/at-start.
  *Why:* the TUI's smart ↑ history recall is a core power-user affordance.
  *Accept:* ↑ in an empty composer recalls the last sent prompt(s).

- [ ] **2.4 Session picker polish toward `/resume`.** `SessionDrawerContent` already lists
  sessions; align it with the TUI's `/resume` (recency sort, message-count, preview/title,
  quick-jump).
  *Verify first:* what the drawer shows today.
  *Why:* faster, more legible session switching.
  *Accept:* the session list shows recency + preview and jumps quickly.

- [ ] **2.5 Queued-message edit/reorder.** `queuedMessages` already exists; add edit/reorder
  before send (TUI queue behavior).
  *Verify first:* current queue affordances.
  *Why:* lowers friction for sequential asks.
  *Accept:* a queued message can be edited or removed before it sends.

---

## Open question for later
- IA/discoverability: leave Terminal as a pushed destination (current), or add a clearer entry
  for paired/power users — without promoting it to a primary tab (it's a power tool; primary-tab
  promotion would clutter the "wife-approved" IA). Decide after Phase 1.

## Change log
- 2026-06-17 — Plan scoped from the 3-way terminal comparison. Direction chosen: **both,
  sequenced** (Phase 1 terminal ergonomics, then Phase 2 Chat parity). Phase 2 is explicitly
  an *enhancement* of the existing native Chat — not a TUI/xterm replacement.
- 2026-06-17 — **Batch 1 landed (not yet built/deployed):** 2.2 context bar pulled forward at
  user request + done (ChatViewModel `ContextWindowUsage` + `contextWindow` flow, per-session
  reset at all 4 points; `ContextMeterBar` rewritten into a clean `NN% · used/max` color-graded
  gauge; redundant header "NN% ctx" suffix removed). Terminal 1.2 (COPY key) + 1.3 (⌨ keyboard
  toggle) done. **Remaining Phase 1:** 1.1 history, 1.4 unread dots, 1.5 scroll indicator,
  1.6 web-render parity. Files: ChatViewModel.kt, ContextMeterBar.kt, ChatScreen.kt,
  ExtraKeysToolbar.kt, TerminalScreen.kt, assets/terminal/index.html.
