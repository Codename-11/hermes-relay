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

- [-] **1.1 Command history — reclassified (already satisfied at the shell).** The extra-keys
  `↑` already sends `[A` (`TerminalViewModel.sendKey`), which is exactly what bash/zsh
  consume for native history recall. So history works today via the shell itself; an app-level
  parallel history would be redundant and could fight the shell's line editor. The audit's
  "no history" finding was inaccurate. An app-level cross-session *recents* quick-run is a
  possible future nicety, not a gap — left out.

- [x] **1.2 Explicit Copy-selection button.** Added a COPY key in `ExtraKeysToolbar` beside
  PASTE; reads the xterm selection via a new `window.getSelectionText()` JS hook
  (`index.html`) and commits non-empty text to the system clipboard (`TerminalScreen.kt`).
  *Accept:* selecting text + tapping Copy puts it on the system clipboard. ⚠️ on-device verify.

- [x] **1.3 Keyboard show/hide toggle.** Added a `⌨` key wired to `WindowInsetsControllerCompat`
  ime show/hide on the active tab's WebView (focuses xterm via the existing `window.focusTerminal`
  on show). `ExtraKeysToolbar.kt` + `TerminalScreen.kt`.
  *Accept:* the key reliably raises/dismisses the keyboard. ⚠️ on-device verify (IME insets vary).

- [x] **1.4 Unread-activity dots on background tabs.** `TabState.unreadOutput` set when a
  `terminal.output` lands on a non-active tab, cleared on `selectTab`; a small primary-colored
  dot on the inactive tab chip (`TerminalTabBar`). `TerminalViewModel.kt` + `TerminalTabBar.kt`.
  *Accept:* a hidden tab that emits output shows a dot until viewed. ⚠️ on-device verify.

- [x] **1.5 "Jump to latest" pill.** xterm `onScroll` reports `atBottom` via a new
  `onScrollPosition` bridge method → `TabState.scrolledUp`; a tappable pill appears over the
  terminal when scrolled up and snaps back via the existing `scrollTerminalToBottom`.
  `index.html` + `TerminalWebView.kt` + `TerminalViewModel.kt` + `TerminalScreen.kt`.
  *Accept:* scrolling up shows the pill; tap returns to the live tail. ⚠️ on-device verify.

- [-] **1.6 Render parity — reclassified (largely already present / blocked).** The font cascade
  (`JetBrains Mono → Fira Code → Cascadia → Roboto Mono → monospace`) is **already** in
  `index.html`, and the `\x1b[RESIZE:cols;rows]` resize contract already holds. **WebGL** can't
  be added — `addon-webgl.js` isn't vendored (only fit/search/web-links are) and fetching it is
  out of scope; the DOM renderer is fine. **OSC 52** is niche now that COPY (1.2) covers
  user-initiated copy + carries a minor clipboard-write surface — left out. Net: no work needed.

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
- 2026-06-17 — **Batch 1 (committed):** 2.2 context bar pulled forward + done; terminal 1.2
  (COPY) + 1.3 (⌨ toggle). Committed to dev (animations/ARR, chat, terminal, docs, play-listing).
- 2026-06-17 — **Phase 1 COMPLETE.** 1.4 unread dots + 1.5 jump-to-latest pill done. 1.1 (history)
  reclassified — shell-native `↑` already recalls history; no app work needed. 1.6 (render parity)
  reclassified — font cascade + resize contract already present, WebGL addon not vendored (can't
  add), OSC 52 left out. 1.7 still deferred. Next: Phase 2 (native-Chat parity) — 2.2 done; 2.1
  tool tree, 2.3 input history recall, 2.4 session-picker polish, 2.5 queue edit remain.
