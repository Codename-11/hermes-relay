# Plan: Bridge Feature Expansion

> **Purpose.** Detailed implementation plan for the v0.4 bridge feature expansion wave. Each work unit here is self-contained enough that an agent can pick it up and execute independently, including per-unit Agent briefs, file lists, acceptance criteria, and reference-implementation pointers.
>
> **Why this file exists.** The high-level [`ROADMAP.md`](../../ROADMAP.md) at the repo root lists the milestones for this wave as brief bullets; this file is where the scoping detail lives. While this plan is active, it's the source of truth for the agent team. Once every unit here has shipped, the plan file gets archived or deleted and the shipped items live on in [`CHANGELOG.md`](../../CHANGELOG.md) and [`DEVLOG.md`](../../DEVLOG.md).
>
> **Origin.** Compiled 2026-04-13 from a comparison pass against [raulvidis/hermes-android](https://github.com/raulvidis/hermes-android) plus prior research. Scope covers bridge-channel tool expansion, reliability patterns, and one documentation/skill addition — deferred and research-horizon items live in [`ROADMAP.md`](../../ROADMAP.md), not here.
>
> **Related files.**
> - [`ROADMAP.md`](../../ROADMAP.md) — milestone-level view of where this plan sits in the broader arc
> - [`CHANGELOG.md`](../../CHANGELOG.md) — cumulative release history
> - [`DEVLOG.md`](../../DEVLOG.md) — session-by-session narrative log
> - [`CLAUDE.md`](../../CLAUDE.md) — project conventions every implementing agent should read first
> - [`docs/spec.md`](../spec.md) — formal architecture spec
> - [`docs/decisions.md`](../decisions.md) — architecture decision records (ADRs)

## How to use this file

1. **Bailey:** walk each work unit and mark its status checkbox (`[x] Now` / `[x] Next` / `[x] Later` / `[x] Skip`). Add inline notes where you want to override the default scope or implementation plan.
2. **Agent team lead:** once curation is done, spawn one agent per "Now" work unit using the **Agent brief** section of that unit as the prompt. Every unit is written to be self-contained — the agent shouldn't need to re-research anything except by reading the files listed.
3. **Each agent:** creates a `feature/<id>-<short-name>` branch (e.g. `feature/A3-find-nodes`), implements the unit per its **Scope / Acceptance criteria**, runs tests, opens a PR. Commit messages reference the unit ID (e.g. `feat(A5): android_screen_hash for change detection`).

## Scope legend

| Tier | Meaning |
|------|---------|
| **A** | High value, low effort, zero permission/flavor impact. Default "Now." |
| **B** | High value, medium effort OR zero permission impact but non-trivial wiring. Strong candidate for Now. |
| **C** | High value but Play Store sensitive (adds a runtime permission that triggers policy review). **Sideload flavor only.** |
| **P** | Architectural pattern / reliability improvement, not a new tool. |
| **Doc** | Documentation surface change. |

Longer-term / research / aspirational items live in [`ROADMAP.md`](../../ROADMAP.md), not in this plan.

**Effort sizing** (scope, not duration): **S** = trivial single-file change, **M** = multi-file change touching 2–4 layers, **L** = new subsystem or cross-cutting work.

**Flavor impact**: **both** / **sideload-only** / **googlePlay-safe**.

## Sequencing & parallelism

- Tier A tools (A1–A7, A10) are **mostly independent** — different agents can pick different tools. They share `plugin/tools/android_tool.py`, `plugin/relay/server.py` (HTTP routes), `BridgeCommandHandler.kt` (phone-side dispatch), and `ActionExecutor.kt` / `ScreenReader.kt` (phone-side logic). The agents will need to coordinate on merge order to avoid trivial merge conflicts in those files; suggest sequencing by the order below or using small, focused PRs.
- **A8 (WakeLockManager) should land first** if we adopt it — A1–A2 and any other gesture-dispatching tool benefits from wrapping its implementation in the wake-lock scope.
- **A11 (per-app SKILL.md)** is fully independent and can run in parallel with everything else. It's a doc-only unit with no code changes.
- Tier C items share the `AndroidManifest.xml` permission block and `FeatureFlags.kt` flavor gates. Suggest doing them in one coordinated batch or sequencing strictly.

---

# Tier A — Ready to ship now

## A1. `android_long_press`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** both

**Summary.** Perform a long press at screen coordinates or on an accessibility node.

**Why.** Fundamental Android interaction. Context menus, text selection, widget rearranging, "hold to confirm" UI all require it. Missing from our current toolkit — agents hit a wall whenever a UI uses long-press affordances.

**Scope / Acceptance criteria.**
- New plugin tool `android_long_press(x, y, node_id, duration=500)` registered in `plugin/tools/android_tool.py`.
- New HTTP route `POST /long_press` in `plugin/relay/server.py` between the existing `# === PHASE3-bridge-server ===` markers, delegating to `BridgeHandler.handle_command("POST", "/long_press", ...)`.
- New case in `BridgeCommandHandler.kt` routing `/long_press` envelopes to `ActionExecutor.longPress(...)`.
- New `suspend fun longPress(x: Int?, y: Int?, nodeId: String?, duration: Long = 500): ActionResult` in `ActionExecutor.kt`.
- Implementation: if `nodeId` → `performAction(ACTION_LONG_CLICK)`. If `(x, y)` → `GestureDescription` stroke with the given duration. Wrap in `BridgeSafetyManager.checkPackageAllowed` gate (same as other tap/type commands).
- Unit tests: `plugin/tests/test_android_long_press.py` covering argument validation (either coord or nodeId, not both missing), HTTP wire format, and delegation.

**Files to touch.**
- `plugin/tools/android_tool.py` (tool handler + schema)
- `plugin/relay/server.py` (HTTP route)
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/BridgeCommandHandler.kt` (route → executor)
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt` (suspend longPress fn)
- `plugin/tests/test_android_long_press.py` (new file)

**Implementation notes.**
- Model the Kotlin implementation on the existing `ActionExecutor.tap` — same gesture-completion callback pattern (`suspendCancellableCoroutine` + `GestureResultCallback`).
- Reference implementation: the `ActionExecutor.longPress` function in raulvidis's repo (can view at `https://raw.githubusercontent.com/raulvidis/hermes-android/main/hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/executor/ActionExecutor.kt` — search for `suspend fun longPress`).
- Consider wrapping in A8 (`WakeLockManager.wakeForAction`) if A8 has landed first.
- Default 500ms duration, allow override up to ~3000ms; reject values outside that range.

**Agent brief.**
> Implement `android_long_press` as a new bridge-channel tool for Hermes-Relay. This adds a long-press gesture capability that's currently missing — needed for context menus, text selection, widget rearranging. Read `CLAUDE.md` for project conventions (Kotlin + Compose, kotlinx.serialization, OkHttp, namespace `com.hermesandroid.relay`), then implement the work unit A1 in `BACKLOG.md` end-to-end: plugin tool, relay HTTP route, phone-side dispatcher, executor function, and a Python unit test. Model the suspend function on the existing `ActionExecutor.tap`. Gate the command through `BridgeSafetyManager.checkPackageAllowed` like other taps. Run `python -m unittest plugin.tests.test_android_long_press` before opening the PR. Branch: `feature/A1-long-press`. Commit style: Conventional Commits.

**Dependencies.** Benefits from (but does not require) A8 (WakeLockManager) landing first.

---

## A2. `android_drag`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** both

**Summary.** Drag from (startX, startY) to (endX, endY) over a duration.

**Why.** Rearranging home screen icons, pulling notification shade, dragging map pins, reordering list items — all common UI interactions we can't do today.

**Scope / Acceptance criteria.**
- Tool `android_drag(start_x, start_y, end_x, end_y, duration=500)` in `plugin/tools/android_tool.py`.
- `POST /drag` route in `plugin/relay/server.py`.
- Route in `BridgeCommandHandler.kt` → `ActionExecutor.drag(...)`.
- New `suspend fun drag(startX, startY, endX, endY, duration): ActionResult` — `GestureDescription` with a single stroke that goes from A→B over the specified duration. Wrap in safety gate.
- Unit tests: `plugin/tests/test_android_drag.py`.

**Files to touch.** Same five files as A1.

**Implementation notes.**
- Identical gesture pattern to A1 but with `Path().moveTo(startX, startY).lineTo(endX, endY)`.
- Reference: `ActionExecutor.drag` in raulvidis's repo.
- Durations above ~3000ms start to feel laggy and gesture dispatch can timeout — clamp.

**Agent brief.**
> Same preamble as A1. Implement unit A2 `android_drag` end-to-end. Single-stroke `GestureDescription` from (start_x, start_y) to (end_x, end_y). Wrap in `BridgeSafetyManager.checkPackageAllowed`. Tests in `plugin/tests/test_android_drag.py`. Branch: `feature/A2-drag`.

**Dependencies.** Same as A1 (benefits from A8).

---

## A3. `android_find_nodes`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Flavor:** both

**Summary.** Search the current screen for accessibility nodes matching filter criteria (text, className, clickable) without dumping the entire tree.

**Why.** `android_read_screen` returns the entire accessibility tree — can be several KB of JSON for a complex screen. Most agent workflows need "does a button labeled 'Send' exist?" not "show me everything." `find_nodes` is a targeted, efficient search.

**Scope / Acceptance criteria.**
- Tool `android_find_nodes(text=None, class_name=None, clickable=None, limit=20)` in `plugin/tools/android_tool.py`.
- `POST /find_nodes` route in `plugin/relay/server.py`.
- `BridgeCommandHandler.kt` route → `ScreenReader.searchNodes(text, className, clickable, limit)` (or `ActionExecutor.findNodes(...)` — your call which layer owns it; `ScreenReader` is more natural since it owns tree traversal).
- New function in `ScreenReader.kt`: walks the accessibility tree, filters by the three criteria (all optional, all ANDed), returns up to `limit` matching nodes in our existing `ScreenNode` JSON shape.
- Unit tests covering: filter-by-text, filter-by-class, filter-by-clickable, combined filters, limit enforcement, empty result case.

**Files to touch.**
- `plugin/tools/android_tool.py`
- `plugin/relay/server.py`
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/BridgeCommandHandler.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenReader.kt` (new `searchNodes` fn)
- `plugin/tests/test_android_find_nodes.py`

**Implementation notes.**
- Reference: `ScreenReader.searchNodes` in raulvidis's repo and the `findNodes` handler in their `ActionExecutor.kt`.
- Filter semantics: `text` is substring match (case-insensitive); `className` is exact match; `clickable=true/false/None` filters by `node.isClickable` when non-null.
- Keep `MAX_NODES=512` cap from existing `ScreenReader` as a safety rail — even if `limit` is higher, the underlying traversal stops at 512.
- Return nodes in the same shape as `android_read_screen` so the LLM gets consistent JSON.

**Agent brief.**
> Same preamble as A1. Implement unit A3 `android_find_nodes`. New tool that searches the current accessibility tree by text/class/clickable filters and returns up to `limit` matching nodes without dumping the full tree. Add `searchNodes` to `ScreenReader.kt`, route it via `BridgeCommandHandler.kt`, wire the plugin tool + HTTP route. Case-insensitive substring match for text, exact match for className. Tests in `plugin/tests/test_android_find_nodes.py`. Branch: `feature/A3-find-nodes`.

**Dependencies.** None.

---

## A4. `android_describe_node`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** both

**Summary.** Return the full property bag for a single accessibility node by ID: bounds, className, text, contentDescription, hintText, clickable/longClickable/focusable/editable/scrollable/checkable/checked/enabled/selected flags, viewIdResourceName, childCount.

**Why.** Our existing `ScreenNode` serializer is minimal for performance. When the agent needs richer context about one specific node (e.g. "is this toggle checked?", "what's the hint text on this input?"), it has to screenshot. This tool closes that gap cheaply.

**Scope / Acceptance criteria.**
- Tool `android_describe_node(node_id)` in `plugin/tools/android_tool.py`.
- `POST /describe_node` route in `plugin/relay/server.py`.
- Route in `BridgeCommandHandler.kt`.
- New `fun describeNode(nodeId: String): ActionResult` in `ActionExecutor.kt` or `ScreenReader.kt` — traverses the tree, finds the node by our existing stable-ID format, returns a map with all relevant properties.
- Unit tests.

**Files to touch.** Same five as A3 but with `describeNode` instead of `searchNodes`.

**Implementation notes.**
- Reference: `ActionExecutor.describeNode` in raulvidis's repo.
- Use the same stable-ID format as our existing `ScreenReader.buildNode` produces so describe round-trips cleanly from `read_screen` → `describe_node`.
- `hintText` is API 26+ only — check `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O`.
- Return `checked: null` when `!isCheckable` (not `false`) so the agent can distinguish "not a toggle" from "unchecked toggle".

**Agent brief.**
> Same preamble as A1. Implement unit A4 `android_describe_node`. Given a nodeId, return the full property bag (bounds, classes, text, contentDescription, hintText, state flags, viewIdResourceName, childCount). Reuse the existing node-ID format from `ScreenReader.buildNode`. Unit tests in `plugin/tests/test_android_describe_node.py`. Branch: `feature/A4-describe-node`.

**Dependencies.** None.

---

## A5. `android_screen_hash` + `android_diff_screen`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Flavor:** both

**Summary.** Two complementary tools: `android_screen_hash()` returns a cheap stable hash of the current screen content; `android_diff_screen(previous_hash)` compares the current screen against a prior hash and reports whether it changed (plus the new hash).

**Why.** **Biggest perf win in the tier.** Our `android_navigate` loop re-reads the full accessibility tree every iteration to decide whether the screen changed. A hash comparison is ~100x cheaper. Agent workflows that poll for "has the page loaded yet?" or "did my tap do anything?" become dramatically faster and cheaper in tokens. This is the one tool class most worth adding from their toolkit.

**Scope / Acceptance criteria.**
- Two tools in `plugin/tools/android_tool.py`: `android_screen_hash()` returning `{hash: str, node_count: int}` and `android_diff_screen(previous_hash)` returning `{changed: bool, hash: str, node_count: int}`.
- Two HTTP routes: `GET /screen_hash` and `POST /diff_screen`.
- Routes in `BridgeCommandHandler.kt`.
- New `fun screenHash(): ActionResult` and `fun diffScreen(previousHash: String): ActionResult` in `ActionExecutor.kt` (or a new `ScreenHasher.kt` if the logic warrants its own file).
- Hash computation: joint string of per-node hashes (className + text + bounds + viewId) over the full tree, then SHA-256 (or even just Kotlin's `hashCode` if we're feeling cheap — reference impl uses simple string join).
- Add `computeHash()` extension on our `ScreenNode` model in `ScreenContent.kt` or wherever it lives.
- Unit tests covering: stable hash across unchanged screens, different hash after text change, different hash after layout change, `diff_screen` positive + negative cases.

**Files to touch.**
- `plugin/tools/android_tool.py`
- `plugin/relay/server.py`
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/BridgeCommandHandler.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenReader.kt` (or new `ScreenHasher.kt`)
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenContent.kt` (add `computeHash()` extension on node model — confirm exact file location; may be inside ScreenReader.kt)
- `plugin/tests/test_android_screen_hash.py`

**Implementation notes.**
- Reference: `ActionExecutor.screenHash` and `diffScreen` in raulvidis's repo; their node-hash is extremely simple: `"$className|$text|$bounds"` per node, joined with `|`.
- We can do better: SHA-256 of the joined string gives a fixed-length hex hash. That's what we should return.
- **Hash stability is load-bearing** — the hash must not change when nothing meaningful changed. Be careful with things that oscillate (animation frames, timestamps). If a node's text includes a live counter, the hash will churn. Document this limitation in the tool description.
- `android_diff_screen` should return both the boolean "changed" and the new hash so the agent can update its reference without an extra call.
- **Once this ships, update `android_navigate`** to use `screen_hash` as a fast-path to detect whether a tap/swipe caused any change before committing to a full screen re-read. That's a follow-on unit — mark as dependent.

**Agent brief.**
> Same preamble as A1. Implement unit A5 `android_screen_hash` + `android_diff_screen`. Hash computation joins per-node fingerprints (className + text + bounds + viewId) across the accessibility tree, then SHA-256s the result. `screen_hash()` returns `{hash, node_count}`. `diff_screen(previous_hash)` returns `{changed, hash, node_count}`. Add `computeHash()` extension to the ScreenNode model. Tests in `plugin/tests/test_android_screen_hash.py` covering hash stability, change detection, and the diff tool happy/sad paths. Branch: `feature/A5-screen-hash`.

**Dependencies.** None. But unlocks future `android_navigate` optimization (see D5).

---

## A6. `android_clipboard_read` / `android_clipboard_write`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** both

**Summary.** Read and write the phone's system clipboard from the agent.

**Why.** Genuinely useful bridge utility. Agent can paste a URL, a generated password, a directions address. Or the user copies something on the phone and the agent reads it as context. Clipboard bridge is one of those "obvious once you have it" features.

**Scope / Acceptance criteria.**
- Two tools: `android_clipboard_read()` and `android_clipboard_write(text)`.
- Two HTTP routes: `GET /clipboard` and `POST /clipboard`.
- Routes in `BridgeCommandHandler.kt`.
- `fun clipboardRead(): ActionResult` and `fun clipboardWrite(text: String): ActionResult` in `ActionExecutor.kt`.
- Uses `Context.CLIPBOARD_SERVICE` → `ClipboardManager`. Standard API. No special permissions.
- Handle empty-clipboard case (return empty string, not error).
- Unit tests.

**Files to touch.** Same pattern as A1 × 2 tools.

**Implementation notes.**
- Reference: `ActionExecutor.clipboardRead` / `clipboardWrite` in raulvidis's repo.
- Use `ClipData.newPlainText("hermes", text)` for writes — the label "hermes" lets other apps see which app put content on the clipboard.
- On Android 12+, writing to the clipboard shows a toast "Hermes-Relay copied". That's a system-level privacy feature — can't suppress it, and shouldn't. Document in the tool description.
- **Security consideration.** Clipboard access is surprisingly powerful (2FA codes, bank account numbers). Consider gating clipboard_read through `BridgeSafetyManager.requiresConfirmation` unless the currently-foregrounded app is explicitly on an allowlist. Default to no-gate for parity with other tools; document the risk in the SKILL.md.

**Agent brief.**
> Same preamble as A1. Implement unit A6 clipboard bridge. Two tools — `android_clipboard_read()` returns the current clipboard text (empty string if nothing copied), `android_clipboard_write(text)` sets the clipboard. Use `ClipboardManager` system service. Uses the label `"hermes"` for the `ClipData`. No special permissions. Tests in `plugin/tests/test_android_clipboard.py`. Branch: `feature/A6-clipboard`.

**Dependencies.** None.

---

## A7. `android_media`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** both

**Summary.** Control system-wide media playback — play, pause, toggle, next, previous.

**Why.** Works against whatever media app is currently playing (Spotify, YouTube Music, Pocket Casts, etc.). Uses the system's media-button broadcast mechanism, which every compliant media app handles. No need for per-app integrations.

**Scope / Acceptance criteria.**
- Tool `android_media(action)` where action ∈ `{play, pause, toggle, next, previous}`.
- `POST /media` route.
- Route in `BridgeCommandHandler.kt`.
- `fun mediaControl(action: String): ActionResult` in `ActionExecutor.kt` — sends an ordered `ACTION_MEDIA_BUTTON` broadcast with the matching `KeyEvent.KEYCODE_MEDIA_*` keycode.
- Unit tests (can mock the broadcast).

**Files to touch.** Same pattern.

**Implementation notes.**
- Reference: `ActionExecutor.mediaControl` in raulvidis's repo.
- Broadcast pattern: `Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(ACTION_DOWN, KEYCODE_MEDIA_PLAY_PAUSE))` — send DOWN + UP as two ordered broadcasts.
- **Zero permissions required.** Media button broadcasts are system-wide and don't need `BIND_MEDIA_CONTROLLER` or anything else.
- Handle unknown action string with `ActionResult(false, "Unknown media action: ...")`.

**Agent brief.**
> Same preamble as A1. Implement unit A7 `android_media`. Control system-wide media playback via `ACTION_MEDIA_BUTTON` broadcast. Five actions: play, pause, toggle, next, previous. Map each to the corresponding `KeyEvent.KEYCODE_MEDIA_*` code. Send DOWN + UP broadcasts in order. No special permissions. Tests in `plugin/tests/test_android_media.py`. Branch: `feature/A7-media`.

**Dependencies.** None.

---

## A8. `WakeLockManager` — wrap gesture dispatch in a wake scope

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** both · **Tier:** P (pattern) applied to existing code

**Summary.** New singleton `WakeLockManager` with a `suspend fun wakeForAction<T>(block: suspend () -> T): T` helper that acquires a screen-brightness wake lock before running the block and releases it after. All gesture-dispatching executor functions are wrapped in it.

**Why.** Our bridge commands can silently fail when the phone screen is off — the gesture dispatches into the void. `WakeLockManager` forces the screen on for the duration of the action, then releases. This is the single biggest reliability fix from their codebase we're missing.

**Scope / Acceptance criteria.**
- New file: `app/src/main/kotlin/com/hermesandroid/relay/power/WakeLockManager.kt`.
- Implements `object WakeLockManager { suspend fun <T> wakeForAction(block: suspend () -> T): T }`.
- Uses `PowerManager.newWakeLock(...)`. **Check the raulvidis reference implementation at `hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/power/WakeLockManager.kt` for the exact flag set** — `SCREEN_BRIGHT_WAKE_LOCK` is deprecated since API 17 and the right choice depends on what Android versions we actually need to cover. The agent should verify against current Android docs rather than copying a deprecated API.
- Wrap `ActionExecutor.tap`, `tapText`, `typeText`, `swipe`, `scroll`, `longPress` (A1), `drag` (A2) in `WakeLockManager.wakeForAction { ... }`.
- Permission already covered — our manifest already has `WAKE_LOCK`? **Check:** if not, add `<uses-permission android:name="android.permission.WAKE_LOCK" />` to `app/src/main/AndroidManifest.xml`.
- Unit tests are optional since this is a thin wrapper around Android APIs — integration tested via existing action tests.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/power/WakeLockManager.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt` (wrap existing suspend funs)
- `app/src/main/AndroidManifest.xml` (confirm / add WAKE_LOCK)

**Implementation notes.**
- Reference: `WakeLockManager` in raulvidis's repo (same path structure — `power/WakeLockManager.kt`).
- **Use a short timeout** (e.g. 10s) — long-held wake locks are a battery drain bug waiting to happen. The try/finally should release even if the action throws.
- Don't wrap `openApp`, `pressKey`, `getInstalledApps`, `currentApp`, `describeNode`, `readScreen`, `findNodes`, `screenHash`, `diffScreen`, `clipboardRead/Write`, `mediaControl` — these don't need the screen to be on.
- The wake-lock context holder can be `private var lockCount: Int = 0` with ref-counting so nested calls don't release each other prematurely.

**Agent brief.**
> Same preamble as A1. Implement unit A8 `WakeLockManager`. Create a new file `app/src/main/kotlin/com/hermesandroid/relay/power/WakeLockManager.kt` with a `suspend fun <T> wakeForAction(block: suspend () -> T): T` helper that acquires a `SCREEN_BRIGHT_WAKE_LOCK` for the duration of the block. Wrap the gesture-dispatching functions in `ActionExecutor.kt` (tap, tapText, typeText, swipe, scroll) with this helper. Verify `WAKE_LOCK` permission is already in the manifest — if not, add it. Use ref-counting so nested calls don't release each other. Short timeout (10s) as a safety rail. Branch: `feature/A8-wake-lock`.

**Dependencies.** Blocks nothing but should land **before or alongside A1/A2/A9** so they pick up the wake-lock wrapping for free. If this unit lands first, A1/A2 just use the wrapper.

---

## A9. Three-tier `tapText` fallback cascade

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** both · **Tier:** P (pattern) applied to existing code

**Summary.** When `tapText` finds a matching text node but the node itself isn't clickable, walk up the parent chain to find the nearest clickable ancestor. If nothing clickable found, fall back to a coordinate tap at the node's bounds center.

**Why.** Real-world Android apps (Uber, Spotify, Instagram, Tinder) wrap clickable content in non-clickable text/image views all the time. Our current `tapText` fails on those. Their three-tier cascade handles 95% of the cases we currently fail on.

**Scope / Acceptance criteria.**
- Modify `ActionExecutor.tapText` to implement the cascade:
  1. Find node by text. If `node.isClickable` → `performAction(ACTION_CLICK)`.
  2. Walk up the parent chain (up to some depth limit, say 8 levels). If any ancestor is clickable → `performAction(ACTION_CLICK)` on it.
  3. Compute node center coordinates from `node.getBoundsInScreen()` and call `tap(cx, cy)`.
- Return a descriptive `ActionResult` indicating which path succeeded (so the agent's activity log shows "tapped via parent" or "tapped via coordinate fallback").
- Properly recycle `AccessibilityNodeInfo` refs in all branches (don't leak).
- Unit tests should cover all three cascade paths — may require instrumentation tests since accessibility node traversal is hard to unit-test.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt` (modify existing `tapText`)

**Implementation notes.**
- Reference: `ActionExecutor.tapText` in raulvidis's repo — the cascade is ~40 lines.
- **Node recycling is the bug-trap.** Every `info.parent`, `info.getChild(i)` returns a fresh `AccessibilityNodeInfo` that must be `.recycle()`'d. Walking up the parent chain in particular needs a loop that recycles the previous node before reassigning.
- Cap the parent walk depth at 8 to avoid infinite loops on broken trees.
- When falling back to coordinate tap, capture the bounds *before* recycling the node.

**Agent brief.**
> Same preamble as A1. Implement unit A9: rewrite `ActionExecutor.tapText` in `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt` to implement a three-tier cascade: direct node click → parent-walk click (max 8 levels up) → coordinate tap at bounds center. Return an `ActionResult` message that indicates which path succeeded. Careful with `AccessibilityNodeInfo.recycle()` — every node returned by `.parent` or `.getChild()` must be recycled. Branch: `feature/A9-tap-text-fallback`.

**Dependencies.** Benefits from A8 (wake lock) but doesn't require it.

---

## A10. `android_macro`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** both

**Summary.** A Python-side tool that takes a list of `{tool, args}` steps and executes them in order, stopping on first failure. Batched workflow execution.

**Why.** Agents know certain procedures (e.g. "open Spotify, tap Search, type X, tap first result, tap Play"). Batching them into one macro call cuts round-trips from 5 to 1, improves reliability, and encodes known-good workflows as replayable primitives. Complements `android_navigate` (which handles unknown workflows via vision).

**Scope / Acceptance criteria.**
- New tool `android_macro(steps: list, name: str = "unnamed")` in `plugin/tools/android_tool.py`.
- Entirely Python-side — dispatches to other `android_*` tools sequentially via a `_HANDLERS` dict.
- Stops on first step failure; returns `{success, name, completed, results, error?}` with the full trace.
- 500ms sleep between steps by default (configurable via optional `pace_ms` arg).
- Unit tests covering: happy path, step-N failure, unknown tool name, empty steps list.
- No phone-side changes.

**Files to touch.**
- `plugin/tools/android_tool.py`
- `plugin/tests/test_android_macro.py`

**Implementation notes.**
- Reference: the `android_macro` function in raulvidis's `hermes-android-plugin/android_tool.py`.
- Build the `_HANDLERS` dict by mapping tool names to their top-level handler functions defined in the same file. `{"android_tap": lambda args: android_tap(**args), ...}`.
- Parse step result as JSON; if it has `"success": false` or an `"error"` key, treat as failure.
- Include step index + tool name in failure messages for easy debugging.
- Document in the tool description: "Use this for known workflows. For unknown ones, use `android_navigate`. If a step might need vision to decide what to do next, don't batch past that point."
- Ensure we design in a way that's easy to scale for users to create their own macros and potetially a macro builder or recording feature?

**Agent brief.**
> Same preamble as A1. Implement unit A10 `android_macro`. This is a pure-Python tool in `plugin/tools/android_tool.py` that takes a list of `{tool, args}` steps and dispatches to other `android_*` tools in order. Stop on first failure. Return a structured trace. Build a `_HANDLERS` dict mapping tool names to handler functions. No phone-side changes needed. Tests in `plugin/tests/test_android_macro.py`. Branch: `feature/A10-macro`.

**Dependencies.** None, but becomes more valuable as more tools land (so you can batch longer procedures).

---

## A11. `skills/android/SKILL.md` — per-app playbook

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Tier:** Doc · **Flavor:** both

**Summary.** A new Hermes skill file that gives the LLM a ready-made playbook for common Android apps: package names, step-by-step procedures, pitfalls, and a hard "do not loop" rule.

**Why.** **Highest-leverage zero-code unit in the entire backlog.** Their `skills/android/SKILL.md` is 9.5 KB of prompt-engineered procedures for Uber / WhatsApp / Spotify / Maps / Settings / Tinder. The LLM reads this the first time it touches Android and immediately knows how to drive those apps. We get the same lift for free by dropping a similar file into our `skills/` directory — the installer already registers it via `skills.external_dirs`.

**Scope / Acceptance criteria.**
- New file: `skills/android/SKILL.md` with frontmatter:
  ```yaml
  ---
  name: android
  description: Control an Android phone via Hermes-Relay — navigate apps, tap, type, swipe, and drive Uber, WhatsApp, Spotify, Maps, Settings, and more
  version: 1.0.0
  metadata:
    hermes:
      tags: [android, phone, automation, accessibility, hermes-relay]
      category: android
  ---
  ```
- Sections:
  1. **How it works** — short architecture summary (phone connects to relay, tools proxy over)
  2. **Setup** — how to pair (our flow: `/hermes-relay-pair` or `hermes-pair`)
  3. **Available tools** — list of `android_*` tools + their purpose (update as new tools land)
  4. **Critical: do not loop** — hard-bound the agent to 5–7 tool calls per user request, STOP-and-report pattern
  5. **Workflow pattern** — `open_app → read_screen → 1-3 actions → verify → STOP`
  6. **Common package names** — table (Uber, WhatsApp, Spotify, Maps, Chrome, Gmail, Instagram, X, Tinder, Settings)
  7. **App-specific procedures** — step-by-step playbooks for Uber, WhatsApp, Spotify, Maps, Settings, Tinder (at minimum)
  8. **Pitfalls per app** — known issues the agent should anticipate
- **Must reference our tool surface accurately** — our 14 current tools plus any Tier A tools landed at write time. Do NOT reference tools we haven't shipped.
- **Must reference our canonical pairing flow** — `/hermes-relay-pair` or `hermes-pair`, NOT raulvidis's `android_setup(pairing_code)`.
- Verify the skill is picked up: restart hermes-agent, run `/skills` (or equivalent), confirm `android` shows up.

**Files to touch.**
- `skills/android/SKILL.md` (new)

**Implementation notes.**
- **Start from raulvidis's SKILL.md as a template** (`https://raw.githubusercontent.com/raulvidis/hermes-android/main/skills/android/SKILL.md`) but rewrite for our tool surface, our pairing flow, and our Conventional Commits / safety-rails vocabulary.
- Their "do not loop" rule is prompt-engineered loop prevention that complements our code-enforced `android_navigate` max_iterations. Keep it.
- Add a new section covering `android_navigate` (vision-driven) that doesn't exist in raulvidis's skill — this is something we have that they don't, and the LLM should know when to reach for it vs. the direct tools.
- Add voice-to-bridge intent notes if running on the `sideload` flavor — the agent should know that SMS-via-voice is handled differently.
- **Keep it agent-readable, not human-readable.** No marketing voice, no prose paragraphs. Short declarative sentences, numbered steps, tables where helpful.
- Ensure we design in a way that's easy to scale for users to create their own skills and potetially a skill builder or recording feature?

**Agent brief.**
> Same preamble as A1. Implement unit A11: create `skills/android/SKILL.md`. This is a Hermes skill file that gives the LLM a playbook for common Android apps. Model it on raulvidis's skill at `https://raw.githubusercontent.com/raulvidis/hermes-android/main/skills/android/SKILL.md` but rewrite for our tool surface (check `plugin/tools/android_tool.py` for the current tool list), our pairing flow (`hermes-pair` / `/hermes-relay-pair`), and our safety vocabulary. Include: how-it-works summary, setup instructions, tool reference, "do not loop" rule (max 5-7 tool calls per request, STOP-and-report pattern), workflow pattern, package name table, app-specific procedures for Uber/WhatsApp/Spotify/Maps/Settings/Tinder, and a section on when to use `android_navigate` vs direct tools. Verify the skill loads via hermes-agent's skill discovery. Branch: `feature/A11-android-skill`.

**Dependencies.** Updates to this file whenever new `android_*` tools land (add them to the tool reference section).

---

# Tier B — Strong consideration

## B1. `android_events` + `android_event_stream`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Flavor:** both

**Summary.** Stream real-time `AccessibilityEvent` objects (clicks, text changes, window transitions, scrolls) into a ring buffer and expose them via a polling tool. Enable/disable capture via a toggle tool.

**Why.** Enables passive monitoring and reactive triggers: "tell me when the user opens Slack", "watch for the next notification from X", "what's the user doing right now?". Complements our notification listener — notifications are one kind of event, accessibility events are everything else.

**Scope / Acceptance criteria.**
- New `EventStore` in `app/src/main/kotlin/com/hermesandroid/relay/event/EventStore.kt` — bounded ring buffer (~500 entries), thread-safe, event types filtered to signal-rich ones (click, text changed, window content changed, window state changed, scroll).
- `HermesAccessibilityService.onAccessibilityEvent()` feeds events into `EventStore` when streaming is enabled.
- Two tools: `android_events(limit=50, since=0)` and `android_event_stream(enabled=true)`.
- Two HTTP routes: `GET /events` and `POST /events/stream`.
- Routes in `BridgeCommandHandler.kt`.
- `android_event_stream(false)` clears the buffer as well as stops capture.
- Unit tests.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/event/EventStore.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/HermesAccessibilityService.kt` (hook `onAccessibilityEvent`)
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/BridgeCommandHandler.kt`
- `plugin/tools/android_tool.py`
- `plugin/relay/server.py`
- `plugin/tests/test_android_events.py`

**Implementation notes.**
- Reference: `EventStore.kt` + event handlers in raulvidis's repo.
- Be careful about event volume — `onAccessibilityEvent` can fire hundreds of times per second during a scroll. Apply throttling: keep one event per (type, package) per ~100ms window.
- **Privacy consideration.** Event streams can leak sensitive info (search queries, message content). Default to `enabled=false`. Require explicit enable. Document clearly.
- Consider gating `android_event_stream(true)` through a user confirmation modal the first time it's activated in a session.

**Agent brief.**
> Same preamble as A1. Implement unit B1 `android_events` + `android_event_stream`. New `EventStore.kt` under `event/` — bounded ring buffer (~500 entries), stores filtered AccessibilityEvents (click, text changed, window content changed, window state changed, scroll). Hook `HermesAccessibilityService.onAccessibilityEvent` to append when streaming is enabled. Two tools: `android_events(limit, since)` returns recent events, `android_event_stream(enabled)` toggles capture. Throttle event intake to 1 per (type, package) per 100ms. Default stream to disabled. Branch: `feature/B1-event-stream`.

**Dependencies.** None.

---

## B2. `android_speak` / `android_speak_stop`

- **Status:** `[ ] Now` `[ x ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** both

**Summary.** On-device text-to-speech — make the phone speak a string through its system speaker. Stop ongoing speech with a second tool.

**Why.** Distinct from our relay-mediated voice mode (which plays TTS *in the app*). `android_speak` uses the phone's system speaker via `android.speech.tts.TextToSpeech` — useful for hands-free scenarios (driving, hands-busy) where the user wants the phone to announce something out loud without opening the app.

**Scope / Acceptance criteria.**
- Tools `android_speak(text, flush=false)` and `android_speak_stop()`.
- HTTP routes `POST /speak` and `POST /stop_speaking`.
- `BridgeCommandHandler.kt` routes.
- `fun speak(text: String, flush: Boolean): ActionResult` and `fun stopSpeaking(): ActionResult` in `ActionExecutor.kt`.
- Initialize `TextToSpeech` lazily on first use, reuse across calls.
- `flush=true` uses `TextToSpeech.QUEUE_FLUSH` (interrupt current); `false` uses `QUEUE_ADD`.
- Unit tests (can mock TTS).

**Files to touch.** Standard pattern.

**Implementation notes.**
- Reference: `ActionExecutor.speak` + `speakStop` in raulvidis's repo.
- `TextToSpeech` init is async — first call may delay briefly while engine warms up.
- No new permissions.
- **Don't forget to shut down the TTS engine** in `BridgeAccessibilityService.onDestroy()` or we leak native resources.

**Agent brief.**
> Same preamble as A1. Implement unit B2 `android_speak` + `android_speak_stop`. Use `android.speech.tts.TextToSpeech` to speak text through the phone's system speaker. `flush=true` interrupts current speech; `false` queues. Lazy-init the TTS engine, reuse across calls, shut down in service destroy. No permissions needed. Tests in `plugin/tests/test_android_speak.py`. Branch: `feature/B2-speak`.

**Dependencies.** None.

---

## B3. `android_screen_record`

- **Status:** `[ ] Now` `[ x ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Flavor:** both

**Summary.** Record a short (N seconds, max ~30s) MP4 video of the phone screen via MediaProjection, upload to the relay via the existing media pipeline, return a `MEDIA:` token the agent can render.

**Why.** Sometimes a screenshot isn't enough — "show me what happens when I tap this" or "capture the animation so I can tell you why the layout looks wrong" need video. We already have the `MediaProjection` consent flow from the bridge work, so the permissions are in place.

**Scope / Acceptance criteria.**
- Tool `android_screen_record(duration_ms=5000)`.
- `POST /screen_record` route with the duration in the body.
- `BridgeCommandHandler.kt` route.
- New file `ScreenRecorder.kt` alongside `ScreenCapture.kt` — uses `MediaRecorder` + `VirtualDisplay` from the same `MediaProjection` holder.
- Returned video uploads via the existing `/media/register` → `MEDIA:hermes-relay://<token>` flow so the agent sees it as a standard media asset.
- Clamp duration 1s–30s; reject anything outside.
- Unit tests.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenRecorder.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/MediaProjectionHolder.kt` (may need tweaks to share with recorder)
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/BridgeCommandHandler.kt`
- `plugin/tools/android_tool.py`
- `plugin/relay/server.py`
- `plugin/tests/test_android_screen_record.py`

**Implementation notes.**
- Reference: raulvidis's `ScreenRecorder.kt` + their `ActionExecutor.screenRecord`.
- `MediaRecorder` config: H264 encoder, AAC audio (off — no audio needed), 720p max, bitrate ~3Mbps, frame rate 30.
- Output to `cacheDir/hermes-media/record_<ts>.mp4`, then hand off to the existing `MediaRegistry` upload flow.
- Respect file size caps — a 30s clip at 3Mbps is ~11MB. May need to reduce to 480p or lower bitrate to fit within the media settings cap.
- **Clean up the temp file after the agent fetches it** (or on cache eviction).

**Agent brief.**
> Same preamble as A1. Implement unit B3 `android_screen_record`. Use `MediaRecorder` + `VirtualDisplay` from the existing `MediaProjectionHolder` to record N seconds (1–30) of screen to an MP4 at 720p/3Mbps/30fps, no audio. Upload via the existing `/media/register` flow and return a `MEDIA:hermes-relay://<token>` marker. Cap duration; clean up temp files. Branch: `feature/B3-screen-record`.

**Dependencies.** None (MediaProjection consent flow already exists).

---

## B4. `android_send_intent` + `android_broadcast`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** both (but log carefully — this is a power tool)

**Summary.** Raw Intent/Broadcast escape hatch. `android_send_intent(action, data_uri, extras, package)` and `android_broadcast(action, extras)` let the agent trigger any Android Intent without a dedicated tool.

**Why.** Lots of app-specific workflows are best triggered by Intent URIs: `google.navigation:q=...` for Maps directions, `tel:...` for dialer, `smsto:...` for SMS composers, `mailto:...` for email, custom deep links for third-party apps. A raw Intent escape hatch means we don't need a dedicated tool for every edge case.

**Scope / Acceptance criteria.**
- Two tools in `plugin/tools/android_tool.py`.
- Two HTTP routes.
- `sendIntent` and `sendBroadcast` functions in `ActionExecutor.kt`.
- **Critical: gate through `BridgeSafetyManager`.** Raw Intents can trigger destructive actions (send SMS, make calls, start unknown apps). Run the destructive-verb check on the action string and extras.
- **Activity log entries must include the full Intent payload** so the user can audit what was triggered.
- Unit tests.

**Files to touch.** Standard pattern.

**Implementation notes.**
- Reference: `ActionExecutor.sendIntent` + `sendBroadcast` in raulvidis's repo.
- Add `FLAG_ACTIVITY_NEW_TASK` to Intents by default (required for startActivity from a Service context).
- Extras are serialized as `Map<String, String>` for simplicity — full Parcelable support is overkill. Document the limitation.
- Allow optional `package` override to force the Intent to a specific app (e.g. `setPackage("com.whatsapp")`).

**Agent brief.**
> Same preamble as A1. Implement unit B4 `android_send_intent` + `android_broadcast`. Raw Intent escape hatch. String-keyed `action`, optional `data_uri`, optional `extras` (Map<String, String>), optional `package` override. Broadcast variant is similar but uses `sendBroadcast`. Gate through `BridgeSafetyManager.checkPackageAllowed` AND destructive-verb check. Log the full payload to the activity log. Add `FLAG_ACTIVITY_NEW_TASK` to Intents. Branch: `feature/B4-send-intent`.

**Dependencies.** None.

---

## B5. Ktor embedded HTTP server on the phone (dev affordance)

- **Status:** `[ ] Now` `[ ] Next` `[ x ] Later` `[ ] Skip`
- **Size:** M · **Flavor:** sideload-only (or behind a developer flag in both)

**Summary.** Run a local Ktor/Netty HTTP server on `:8765` inside the app that exposes the same command surface as the relay WebSocket path. Developer affordance for direct-to-phone testing over USB or LAN without routing through the relay.

**Why.** When iterating on a new `android_*` command handler, the full stack is plugin Python → relay Python → WebSocket → phone → executor. That's a lot of moving parts. A local HTTP server lets you `curl http://phone-ip:8765/screen` or equivalently drive the phone from Postman, skipping the relay entirely. **Major dev ergonomics win** but zero runtime value for end users.

**Scope / Acceptance criteria.**
- Add Ktor dependencies to `gradle/libs.versions.toml` + `app/build.gradle.kts`.
- New file `BridgeServer.kt` + `BridgeRouter.kt` in `app/src/main/kotlin/.../server/`.
- Routes match the relay's HTTP route list 1:1, delegating to the same `ActionExecutor` / `ScreenReader` / `BridgeCommandHandler` logic.
- Start/stop controlled by a Developer Options toggle in `DeveloperSettingsScreen.kt`.
- **Off by default**, on only when explicitly enabled.
- Bind to `0.0.0.0:8765` so it's reachable from LAN.
- Simple token-based auth: require the same pairing code in an `Authorization: Bearer` header, matching the relay's auth semantics.
- Document in `CONTRIBUTING.md` — new "Direct phone HTTP testing" subsection under dev workflows.

**Files to touch.**
- `gradle/libs.versions.toml` (new Ktor lib refs)
- `app/build.gradle.kts` (new dependencies)
- `app/src/main/kotlin/com/hermesandroid/relay/server/BridgeServer.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/server/BridgeRouter.kt` (new)
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/DeveloperSettingsScreen.kt` (new toggle)
- `app/src/main/kotlin/com/hermesandroid/relay/data/FeatureFlags.kt` (new flag `devHttpServerEnabled`)
- `CONTRIBUTING.md` (doc update)

**Implementation notes.**
- Reference: `BridgeServer.kt` + `BridgeRouter.kt` in raulvidis's repo.
- Ktor + Netty is heavy — adds ~2MB to the APK. Consider if it's worth it vs. a lighter server. `NanoHTTPD` is smaller but less flexible; if you only need a few endpoints, hand-rolled `ServerSocket` + request parsing would be even smaller.
- **Don't start the server in production builds.** Gate behind `BuildConfig.DEBUG` + Developer Options toggle.
- The manifest already has `android:usesCleartextTraffic="true"` for our relay connections? **Check** — we use HTTPS by default so this might need a conditional network security config for the dev flavor.

**Agent brief.**
> Same preamble as A1. Implement unit B5 — developer-mode embedded HTTP server on the phone. Add Ktor + Netty deps, create `BridgeServer.kt` + `BridgeRouter.kt` under `server/` that expose the same command surface as the relay's HTTP routes, delegating to existing `ActionExecutor` / `ScreenReader`. Start/stop controlled by a Developer Options toggle gated behind `BuildConfig.DEBUG`. Bearer-auth with the pairing code. Document in `CONTRIBUTING.md`. **Consider** lighter alternatives to Ktor (NanoHTTPD or hand-rolled) to keep APK size down. Branch: `feature/B5-dev-http-server`.

**Dependencies.** None.

---

# Tier C — Sideload-only (Play Store sensitive)

> **Important for all Tier C units:** each adds a runtime permission that triggers Play Store policy review if shipped in the `googlePlay` flavor. These must be **sideload-only**. Use `FeatureFlags.BuildFlavor.current == SIDELOAD` checks to compile out the tool registrations in the `googlePlay` build, and add the permission declarations to `app/src/sideload/AndroidManifest.xml` rather than the main manifest. See how we handle `android_navigate` today for the pattern.

## C1. `android_location`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** sideload-only

**Summary.** Return the phone's last-known GPS location — latitude, longitude, accuracy, altitude, provider, timestamp.

**Why.** Unlocks contextual tasks: "how long is my commute from here?", "what's the nearest coffee shop?", "show me the drive route to Mom's place." Without location, the agent has to ask the user every time.

**Scope / Acceptance criteria.**
- Tool `android_location()` in `plugin/tools/android_tool.py` — sideload-only registration.
- `GET /location` route in the relay.
- Route in `BridgeCommandHandler.kt` — gated on `BuildFlavor.current == SIDELOAD`; returns `ActionResult(false, "android_location is sideload-only")` if called from googlePlay.
- `fun location(): ActionResult` in `ActionExecutor.kt` — uses `LocationManager.getLastKnownLocation()` across providers, returns the most accurate.
- Permissions: `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` declared in `app/src/sideload/AndroidManifest.xml`.
- Runtime permission request flow: check `ContextCompat.checkSelfPermission`; if not granted, return `ActionResult(false, "Grant location permission in Settings > Apps > Hermes-Relay > Permissions")`. Don't try to request at runtime from a service context.
- Unit tests.

**Files to touch.**
- `plugin/tools/android_tool.py`
- `plugin/relay/server.py`
- `app/src/main/kotlin/com/hermesandroid/relay/network/handlers/BridgeCommandHandler.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt`
- `app/src/sideload/AndroidManifest.xml` (permissions)
- `plugin/tests/test_android_location.py`

**Implementation notes.**
- Reference: `ActionExecutor.location` in raulvidis's repo.
- `getLastKnownLocation()` is fast but may return stale data. Accept that — don't request a fresh GPS fix from a background service (that requires a `LocationListener` + more permissions).
- Consider adding a max-age filter: if the last known location is older than 5 minutes, return an error asking the user to open a maps app briefly to refresh the fix.
- **Privacy**: log location fetches in the activity log so the user can see when the agent queried their position.

**Agent brief.**
> Same preamble as A1. Implement unit C1 `android_location`. **Sideload-only.** Add `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` to `app/src/sideload/AndroidManifest.xml` only — NOT the main manifest. Use `LocationManager.getLastKnownLocation()` across providers, pick the most accurate, return lat/lon/accuracy/altitude/provider/timestamp. Check runtime permission; return a helpful error if not granted. Gate the tool registration in `plugin/tools/android_tool.py` and the command handler in `BridgeCommandHandler.kt` behind `BuildFlavor.current == SIDELOAD`. Log fetches to the activity log. Branch: `feature/C1-location`.

**Dependencies.** Requires the flavor-gating pattern already in place for `android_navigate`.

---

## C2. `android_search_contacts`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** M · **Flavor:** sideload-only

**Summary.** Search the phone's contact database by name, return matching contacts with phone numbers.

**Why.** Unlocks "text Sam saying X" flows without tapping through a contact picker. Also enables the voice-to-bridge SMS flow to do name lookups directly.

**Scope / Acceptance criteria.**
- Tool `android_search_contacts(query, limit=20)` — sideload-only.
- `POST /search_contacts` route.
- Route in `BridgeCommandHandler.kt` — flavor-gated.
- `fun searchContacts(query, limit): ActionResult` in `ActionExecutor.kt` — uses `ContactsContract` ContentResolver query.
- Returns list of `{id, name, phones}` where phones is a comma-separated list of numbers.
- `READ_CONTACTS` permission in sideload manifest only.
- Runtime permission check.
- Unit tests.

**Files to touch.** Standard + sideload manifest.

**Implementation notes.**
- Reference: `ActionExecutor.searchContacts` in raulvidis's repo.
- Use `ContactsContract.Contacts.CONTENT_FILTER_URI` with the query appended — native contacts search.
- For each matching contact, do a second query on `ContactsContract.CommonDataKinds.Phone.CONTENT_URI` to get numbers.
- **Careful with batch queries** — can be slow on devices with thousands of contacts. Apply the `limit` SQL-side via ORDER BY + LIMIT.
- **Privacy**: log contact queries.

**Agent brief.**
> Same preamble as A1. Implement unit C2 `android_search_contacts`. **Sideload-only.** Add `READ_CONTACTS` to `app/src/sideload/AndroidManifest.xml`. Use `ContactsContract.Contacts.CONTENT_FILTER_URI` for the search, then resolve phone numbers via `ContactsContract.CommonDataKinds.Phone`. Return `{id, name, phones}` entries. Runtime permission check + helpful error if not granted. Flavor-gate the tool + handler. Branch: `feature/C2-contacts`.

**Dependencies.** None, but composes well with C3 and C4.

---

## C3. `android_call`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** sideload-only for auto-dial; googlePlay can ship dialer-open fallback

**Summary.** Dial a phone number. With `CALL_PHONE` permission, auto-dials via `ACTION_CALL`; without it, opens the dialer via `ACTION_DIAL` (free, no permission).

**Why.** Composes with C2 to enable "call Sam" voice flows.

**Scope / Acceptance criteria.**
- Tool `android_call(number)`.
- `POST /call` route.
- Route in `BridgeCommandHandler.kt`.
- `fun makeCall(number: String): ActionResult` — checks `CALL_PHONE` permission; if granted, uses `Intent.ACTION_CALL`; otherwise uses `ACTION_DIAL` (user has to manually tap the call button in the dialer).
- `CALL_PHONE` permission in sideload manifest only. `ACTION_DIAL` requires no permission — **shippable in googlePlay as a dialer-opener**.
- **Strongly gate through `BridgeSafetyManager` destructive-verb confirmation.** Phone calls are expensive and irreversible.
- Log the number to the activity log.
- Unit tests.

**Files to touch.** Standard + sideload manifest.

**Implementation notes.**
- Reference: `ActionExecutor.makeCall` in raulvidis's repo.
- URI format: `tel:1234567890`.
- Use `FLAG_ACTIVITY_NEW_TASK` on the Intent (required from service context).
- **Consider shipping the dialer-open path in googlePlay** too — no permission needed, still useful.

**Agent brief.**
> Same preamble as A1. Implement unit C3 `android_call`. Two modes: with `CALL_PHONE` permission (sideload flavor), auto-dial via `ACTION_CALL`; without it (googlePlay flavor), open the dialer via `ACTION_DIAL`. URI format `tel:<number>`. Add `CALL_PHONE` to sideload manifest only. **Must go through `BridgeSafetyManager.awaitConfirmation` — calls are irreversible.** Log the full number to the activity log. Flavor-gate the auto-dial path; dialer-open ships in both. Branch: `feature/C3-call`.

**Dependencies.** None, but composes with C2.

---

## C4. `android_send_sms`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** sideload-only

**Summary.** Send an SMS directly via `SmsManager.sendTextMessage` — no UI interaction, no tapping through the Messages app.

**Why.** Our current voice-to-bridge SMS flow taps through the default SMS app's UI, which is fragile (depends on Samsung Messages / Google Messages / carrier variant UI). Direct `SmsManager` is one line and always works. Big reliability improvement for the voice mode's most-used flow.

**Scope / Acceptance criteria.**
- Tool `android_send_sms(to, body)`.
- `POST /send_sms` route.
- Route in `BridgeCommandHandler.kt`.
- `fun sendSms(to, body): ActionResult` — uses `SmsManager.sendTextMessage`. On API 31+, get SmsManager via `service.getSystemService(SmsManager::class.java)`; on older APIs, use `SmsManager.getDefault()`.
- `SEND_SMS` permission in sideload manifest only.
- **Must go through `BridgeSafetyManager` destructive-verb confirmation** — SMS is the prototype "destructive action" in the existing verb list.
- Update the voice-to-bridge intent handler (`VoiceBridgeIntentHandlerImpl.kt` in sideload) to use `android_send_sms` directly instead of tapping through the SMS app UI.
- Log the full SMS payload to the activity log.
- Unit tests.

**Files to touch.** Standard + sideload manifest + voice intent handler.

**Implementation notes.**
- Reference: `ActionExecutor.sendSms` in raulvidis's repo.
- **Google Play restriction warning**: `SEND_SMS` requires a Play Store policy declaration + default SMS app status. **This is why this tool is sideload-only**. Even with permission granted locally, Play Store will reject an app that requests `SEND_SMS` without the default-SMS-app justification.
- Multi-part SMS: `sendTextMessage` handles up to 160 chars. For longer messages, use `sendMultipartTextMessage` with `divideMessage`.
- **Confirm every send.** The existing destructive-verb confirmation modal should fire on `send` + the text contents.
- On failure (airplane mode, no signal, carrier reject), return a descriptive error — `SmsManager` doesn't throw, it just silently no-ops unless you register a `PendingIntent` result callback. Register one for reliability.

**Agent brief.**
> Same preamble as A1. Implement unit C4 `android_send_sms`. **Sideload-only.** Add `SEND_SMS` to `app/src/sideload/AndroidManifest.xml`. Use `SmsManager.sendTextMessage` (API-version-aware getSystemService pattern). Register a `PendingIntent` result callback so failures surface as real errors. Handle multi-part via `divideMessage` + `sendMultipartTextMessage`. **Gate through `BridgeSafetyManager.awaitConfirmation` — SMS is a destructive action.** Log the full payload. Update `VoiceBridgeIntentHandlerImpl.kt` (sideload flavor) to use this tool directly instead of tapping through the SMS app UI. Branch: `feature/C4-send-sms`.

**Dependencies.** Enhances the existing voice-to-bridge SMS intent flow. Composes with C2 (contacts lookup).

---

# Patterns (architectural, not tools)

## P1. `ScreenReader`: search ALL windows, not just `rootInActiveWindow`

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Flavor:** both

**Summary.** Our `ScreenReader` uses `rootInActiveWindow` which misses system overlays, popup menus, notification shade content, and multi-window split-screen state. Change to iterate `service.windows.mapNotNull { it.root }`.

**Why.** Small change, fixes a whole class of bugs where the agent can't see overlay dialogs or popups. Their codebase uses the multi-window approach throughout.

**Scope / Acceptance criteria.**
- Modify `ScreenReader.readCurrentScreen` (and any helper that uses `rootInActiveWindow`) to iterate all windows.
- Merge the trees under a synthetic root or return a list of per-window trees (your call which makes more sense for our node-ID scheme).
- Careful recycling of every `AccessibilityWindowInfo.root` we fetch.
- No tool changes — this is an internal improvement.

**Files to touch.**
- `app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenReader.kt`

**Implementation notes.**
- Reference: the `findNodeById` pattern in raulvidis's `ActionExecutor.kt` — it does `service.windows.mapNotNull { it.root }`.
- `AccessibilityWindowInfo.getRoot()` returns a new `AccessibilityNodeInfo` that must be recycled.
- The node-ID scheme may need to include a window index prefix to disambiguate nodes across windows.

**Agent brief.**
> Same preamble as A1. Implement unit P1: modify `ScreenReader` to iterate all accessibility windows instead of just `rootInActiveWindow`. Catches system overlays, popups, notification shade content. Preserve careful node recycling. Update the stable node-ID scheme to include a window index prefix if multiple windows are returned. No tool changes — internal improvement. Branch: `feature/P1-all-windows`.

**Dependencies.** Should land before or in parallel with A3/A4 (find_nodes, describe_node) so those tools benefit from the wider coverage.

---

# Documentation & Skills

## User-Docs

- Keep user-docs site updated when relevant docs or files are touched or if we need to create new entries, etc. We should ensure we have a clean table of features and what works in sideload etc.

## Doc1. Per-app `SKILL.md` playbook

**Already captured as A11** — promoted to Tier A because it's the single highest-leverage zero-code addition.

---

## Doc2. Update README's "Features" section as Tier A ships

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Tier:** Doc

**Summary.** Every Tier A tool that lands gets a line in the `README.md` Features section (the curated 7-bullet list). The bullet should describe the capability in user-facing language, not the tool name.

**Agent brief.** Low-priority chore — can be bundled into each Tier A PR or done in a single sweep at the end.

---

## Doc3. Update `docs/spec.md` to document the new bridge surface

- **Status:** `[ x ] Now` `[ ] Next` `[ ] Later` `[ ] Skip`
- **Size:** S · **Tier:** Doc

**Summary.** `docs/spec.md` has a section listing the `android_*` tool surface. Update it as new tools land. Also note the architectural patterns adopted from raulvidis's repo.

**Agent brief.** Low-priority chore — bundle into the final sweep after Tier A lands.

---

# Deliberately excluded (from raulvidis/hermes-android)

The following were in their codebase but we explicitly **do not** adopt. Documented here so they don't re-surface in future reviews.

| Item | Why excluded |
|---|---|
| **`ws://` plaintext transport model** | We have TLS support. Their `ws://` is a prototype shortcut. |
| **No safety rails posture** | Their "trust the server completely once paired" model is fine for a prototype but unsafe for end users. Our Tier 5 safety rails (blocklist, destructive-verb confirmation, auto-disable, activity log) are a product differentiator. |
| **SharedPreferences for credentials** | We use Android Keystore (StrongBox-preferred) for session tokens. SharedPreferences is not hardware-backed. |
| **Single-file `ActionExecutor` object monolith** | Their `ActionExecutor.kt` is one ~600-line `object`. Ours is modularized across `executor/`, `accessibility/`, `bridge/`, `network/handlers/`. Keep modular. |
| **XML layouts + terminal-themed UI** | Ours is Compose + Material 3. Modern, maintainable, more consistent. |
| **`android_setup(pairing_code)` flow** | Their agent-first pairing (agent calls `android_setup`, user enters server IP in phone app) is a workable inverse of our QR-first flow but requires extra roundtrips and feels clunky. Our QR scan is one tap. |
| **Per-command HTTP routes on phone's Ktor server as the canonical path** | B5 captures this as an *optional dev affordance*, not the canonical command path. Canonical stays WebSocket via the relay. |

---

# Agent team execution guide

Once Bailey has curated the status checkboxes and picked which units are "Now", kick off the agent team as follows.

## Preflight

1. **Confirm CLAUDE.md is up to date** with any recent architecture changes. Every agent reads it first.
2. **Confirm A8 (WakeLockManager) sequencing** — if we're doing it, land it before A1/A2/A9 so they pick up the wake-lock wrapping.
3. **Confirm A11 (SKILL.md) kickoff** — this can run in parallel with everything else. Assign first if you want it done earliest.

## Per-unit agent spawn template

```
Agent({
  description: "Implement <unit_id>: <short_name>",
  subagent_type: "general-purpose",
  prompt: `Read CLAUDE.md for project context. Then read the work unit <unit_id> in BACKLOG.md — that unit's Agent brief section is your mandate.

  Work strictly on a new branch: feature/<unit_id>-<kebab-short-name>.

  Implement end-to-end per the unit's Scope / Acceptance criteria section. Follow the Files to touch list. Study the reference implementation notes in the unit for any tricky patterns.

  Run existing tests plus whatever new tests the unit describes. Commit via Conventional Commits referencing the unit ID (e.g. 'feat(<unit_id>): <summary>'). Open a PR titled '<unit_id>: <summary>' against main with the unit's scope section as the PR body.

  If you hit a blocker that the unit doesn't cover, STOP and report back — don't invent your own scope.`,
  isolation: "worktree"
})
```

## Parallelism plan

Suggested first wave (can run simultaneously):
- **A8** (WakeLockManager) — blocks A1/A2/A9's best wrapping so sequence first OR make it the first merge
- **A3** (find_nodes) — independent
- **A4** (describe_node) — independent
- **A5** (screen_hash + diff_screen) — independent, high value
- **A6** (clipboard) — independent
- **A7** (media) — independent
- **A10** (macro) — pure Python, independent
- **A11** (SKILL.md) — pure Markdown, independent
- **P1** (all windows) — benefits A3/A4 downstream, can land in parallel

Second wave (after A8 lands):
- **A1** (long_press)
- **A2** (drag)
- **A9** (tapText cascade)

Third wave (Tier B once Tier A is green):
- **B1** (events stream)
- **B2** (speak)
- **B3** (screen record)
- **B4** (send_intent)
- **B5** (dev HTTP server) — optional

Fourth wave (Tier C, batched because they all touch the sideload manifest):
- Do all of **C1-C4** on a single integration branch to avoid manifest merge conflicts, then PR as one unit.

## Coordination notes

- **Shared files.** `plugin/tools/android_tool.py`, `plugin/relay/server.py`, `BridgeCommandHandler.kt`, and `ActionExecutor.kt` will see changes from multiple Tier A units. Agents should rebase frequently and keep their diffs tight. Suggest landing one Tier A unit per day to avoid compounding merge conflicts.
- **Commit hygiene.** Conventional Commits. Unit ID in the subject line. One PR per unit.
- **Testing.** Every unit must add or extend unit tests. Run locally before opening the PR via `python -m unittest plugin.tests.test_<name>` (not `pytest` — the existing conftest doesn't load reliably).
- **Review.** Agent-written PRs should be self-reviewed via the `feature-dev:code-reviewer` subagent before requesting human review. Catches most quality issues.

---

# Status at a glance

Fill this out after curation. Example format:

| Unit | Status | Owner | Branch | PR |
|------|--------|-------|--------|-----|
| A1 | Now | agent-1 | feature/A1-long-press | #NNN |
| A2 | Now | agent-2 | feature/A2-drag | — |
| ... | ... | ... | ... | ... |

---

*End of backlog. Last updated 2026-04-13.*
