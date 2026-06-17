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

- [x] **2.1 Collapsible tool/thinking — collapse state now persists.** Verify-first found the
  tree affordance already exists: `ToolProgressCard` + `ThinkingBlock` + `SubagentLane` are all
  chevron-collapsible and `SubagentLane` has a guide rail. The only real gap was that expand
  state reset on scroll-off / re-render. Fixed `ToolProgressCard` to `rememberSaveable` keyed per
  tool call (namespaced by the message item key), so a manual expand survives. Deeper multi-level
  nesting (beyond the 1-level subagent rail) left out — there's only ever 1 level today.

- [x] **2.2 Clean per-session context-usage bar.** Done (committed earlier): `ContextMeterBar`
  rewritten into a `NN% · used/max` color-graded gauge backed by a per-session `contextWindow`
  token flow, reset at all 4 session points; redundant header suffix removed. ⚠️ on-device verify
  (loads on resume after first server usage report; instant-on-resume fetch is a future option).

- [x] **2.3 Recent-prompt recall (mobile-native).** A soft keyboard has no ↑, so instead of
  keystroke recall the composer surfaces a row of tappable recent-prompt chips while it's empty
  (`recentPrompts` flow in `ChatViewModel`, bounded 15, slash-commands excluded). Tapping
  prefills (not auto-sends) so the user can tweak before resending; the row hides on typing /
  when a queue or fresh-chat is showing. `ChatViewModel.kt` + `ChatScreen.kt`.

- [x] **2.4 Session picker — already adequate (no work).** Verify-first: `SessionDrawer` already
  has recency sort, message counts, title/preview, quick-switch, rename, delete, pin, archive,
  and search. Nothing to add for `/resume` parity.

- [x] **2.5 Queued-message management — list + edit + remove.** Verify-first found the queue was
  opaque (count + Clear only). Now each queued message renders as a row: tap to pull it back into
  the composer for editing (`takeQueuedForEdit`), `✕` to drop just that one (`removeQueuedAt`).
  Reorder omitted — low value vs. drag-handle complexity on a transient queue.
  `ChatViewModel.kt` + `ChatScreen.kt`.

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
  add), OSC 52 left out. 1.7 still deferred.
- 2026-06-17 — **Phase 2 COMPLETE.** 2.1 tool-card collapse now persists (rememberSaveable; tree
  affordance already existed). 2.2 context bar (done earlier). 2.3 recent-prompt chips (mobile
  recall — no soft-keyboard ↑). 2.4 session picker already adequate (no work). 2.5 queue list +
  per-item edit/remove (reorder omitted). **Both phases done — only 1.7 (inline rename) deferred.**
  All on-device-verify items flagged ⚠️ above. Next session could revisit 1.7 + the IA question.
