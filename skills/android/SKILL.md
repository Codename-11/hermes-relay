---
name: android
description: Control an Android phone via Hermes-Relay — navigate apps, tap, type, swipe, and drive Uber, WhatsApp, Spotify, Maps, Settings, and more
version: 1.0.0
author: Axiom Labs
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [android, phone, automation, accessibility, hermes-relay]
    category: android
    homepage: https://github.com/Codename-11/hermes-relay
    related_skills: [hermes-relay-pair]
---

# Android Phone Control

Drive a paired Android phone from Hermes using the `android_*` tool family. This skill is the playbook the LLM reads before touching those tools for the first time — architecture, pairing, the tool surface, bounded tool budgets, and per-app step-by-step procedures.

## 1. How it works

1. The Hermes-Relay Android app runs on the phone and exposes an `AccessibilityService` + `MediaProjection` dispatcher.
2. The phone connects outbound to the Hermes-Relay WSS relay (default `:8767`) as the `bridge` channel.
3. Each `android_*` tool sends an HTTP request to the relay on loopback, which forwards it as a `bridge.command` envelope over the WSS socket to the phone. The phone executes the action and replies with `bridge.response`.
4. Sensitive actions pass through **Tier 5 safety rails** on the phone (blocklist, destructive-verb confirmation, idle auto-disable). See section 9.
5. `sideload` builds unlock additional capabilities behind a build-flavor flag (voice-to-bridge intents, direct SMS, direct dial). See section 10.

## 2. Setup — how the phone gets paired

**Do not call `android_setup`.** That is a fallback for teaching the host about an existing session token — it does NOT pair a new phone. For first-time setup or re-pairing, use one of the canonical flows:

| Invocation | When to use |
|---|---|
| `/hermes-relay-pair` (slash command) | Any Hermes chat session. Invokes the `hermes-relay-pair` skill, which generates a pairing QR for the user to scan. |
| `hermes-pair` (shell shim) | Terminal users on the host. Runs `python -m plugin.pair`. Same QR, no chat round-trip. |

If the user has not paired yet, stop and hand off to `/hermes-relay-pair`. Do not attempt to drive tools against an unpaired phone — every call will return `503 bridge not connected`.

To confirm the phone is reachable before starting a workflow, call `android_ping` once. A healthy response looks like `{"status": "ok", "bridge": {...}}`. Anything else means the phone is not connected — stop and tell the user.

## 3. Available tools

All of these live in `plugin/tools/android_tool.py` (except `android_navigate` in `plugin/tools/android_navigate.py`). Every tool returns a JSON string.

### Screen reading

| Tool | Purpose |
|---|---|
| `android_read_screen(include_bounds=False)` | Dump the accessibility tree of the current screen. Each node has `nodeId`, `text`, `contentDescription`, `className`, `clickable`, `focusable`, and optionally `bounds`. This is your primary "what's on screen" input — prefer it over screenshots when you only need to read text or find a tap target. |
| `android_screenshot()` | Capture a JPEG of the phone screen. Returns a `MEDIA:hermes-relay://<token>` line that the phone fetches over the bearer-auth'd relay. Use this when you need vision (logos, layouts, CAPTCHAs, non-accessibility-reachable content) — not for text that `android_read_screen` already exposes. |

### Interaction

| Tool | Purpose |
|---|---|
| `android_tap(x=None, y=None, node_id=None)` | Tap by accessibility `node_id` (preferred — stable across screens) or by `(x, y)` pixel coordinates (fallback). |
| `android_tap_text(text, exact=False)` | Tap the first element whose visible text matches. Use when you can see the word on screen but don't have a node id from `android_read_screen` yet. `exact=False` does substring matching. |
| `android_type(text, clear_first=False)` | Type into the currently focused input field. Set `clear_first=True` to blank it before typing. You still need a preceding tap to focus the field. |
| `android_swipe(direction, distance="medium")` | Screen-level swipe. `direction` is one of `up`, `down`, `left`, `right`. `distance` is `short`, `medium`, or `long`. |
| `android_scroll(direction, node_id=None)` | Scroll within a scrollable container. Pass `node_id` from `android_read_screen` to scroll a specific list; omit it to scroll the root. |
| `android_press_key(key)` | Press a curated system key: `back`, `home`, `recents`, `power`, `volume_up`, `volume_down`, `enter`, `delete`, `tab`, `escape`, `search`, `notifications`. No raw KeyEvent injection. |
| `android_open_app(package)` | Launch an app by package name. See section 7 for common package names. |
| `android_wait(text=None, class_name=None, timeout_ms=5000)` | Poll every 500 ms for an element to appear. Cheaper than burning a retry budget on read_screen loops. |

### App state

| Tool | Purpose |
|---|---|
| `android_current_app()` | Return the package and activity of the foreground app. Use to confirm a launch succeeded or that you didn't land on a permission modal. |
| `android_get_apps()` | List all installed apps with package names and labels. Use when the user asks for "an app that does X" and you need to discover what's installed. |
| `android_ping()` | Health check — is the phone connected to the relay? Call once at the start of a workflow, not on every step. |

### Orchestration

| Tool | Purpose |
|---|---|
| `android_navigate(intent, max_iterations=5)` | Close-the-loop vision-driven navigation. Takes one screenshot per step, asks a vision model what to tap next, and dispatches to the direct tools above. Hard-capped at `max_iterations` (default 5, absolute ceiling 20). Returns a full trace of every step. See section 6 for when to reach for this instead of the direct tools. |

There is also an `android_setup(bridge_session_token)` function in the same module — that is a fallback for reconfiguring the host after a re-install when you already have a session token. It is **not** how you pair a phone. See section 2.

## 4. Critical: do not loop

**Hard rule: cap any single user request at 5–7 tool calls total.** When you hit that budget, STOP and report the current state to the user. Do not retry. Do not keep tapping. Do not "try one more thing."

Why this exists:

- Accessibility trees are noisy. The same visible "Send" button can have three different `nodeId`s across a single app depending on what's loaded above it. A naive retry loop burns minutes chasing stale node ids.
- Phones have Tier 5 safety rails (section 9). If a tap is blocked, retrying it will just produce the same 403. Report the block — don't hammer the gate.
- `android_navigate` is the correct escape hatch for "I don't know what to tap next." Its iteration cap is code-enforced. Your job as the outer agent is to bound the total *number of invocations*, not to write your own retry logic around it.

**STOP-and-report pattern.** When you cannot make progress:

1. Call `android_read_screen` once.
2. Summarize what you see (2–3 sentences).
3. Ask the user what they want you to do next. Do not guess.

**Acceptable tool budgets for common workflows:**

| Workflow | Budget |
|---|---|
| Launch an app and read what's on screen | 2–3 tools (`open_app` → `wait` → `read_screen`) |
| Send a WhatsApp message to a known contact | 5–6 tools |
| Book an Uber to a known destination | 6–7 tools |
| Find an unknown setting | hand off to `android_navigate` with `max_iterations=5` |
| "Scroll until you see X" | 3 swipes max, then stop and report |

If you realize mid-workflow that you need more than 7 calls, stop, explain the situation to the user, and ask whether to continue.

## 5. Workflow pattern

The canonical shape of every bounded Android workflow:

```
open_app(package)
  → wait(text="<landmark>", timeout_ms=5000)
  → read_screen()
  → 1–3 targeted actions (tap_text / tap / type / swipe)
  → read_screen()        # verify the action landed
  → STOP and report
```

Key discipline:

- **Verify before you chain.** Never assume a tap "worked" — re-read the screen and confirm the next landmark is present before sending another action.
- **Prefer `tap_text` over `tap(x, y)`.** Coordinates go stale the instant the layout shifts (keyboard pops, ad loads, notification arrives). `nodeId` from a fresh `read_screen` is second-best; raw coordinates are last resort.
- **Never `open_app` twice in a row.** If the first didn't land, check `android_current_app` and report.
- **Never chain more than 3 actions without a `read_screen`.** UI state drifts faster than you expect.

## 6. When to use `android_navigate` vs direct tools

Use the **direct tools** (`tap_text`, `type`, `swipe`, etc.) when you know the exact step sequence — for example, the app playbooks in section 8. You are in control of the budget, you can inspect intermediate state, and every step is cheap.

Use **`android_navigate(intent, max_iterations=N)`** when you need vision in the loop: the user's goal is clear but the path is not, the layout is unfamiliar, or the target element is visible in pixels but not in the accessibility tree (icon-only buttons, canvas-rendered UI, custom views). `android_navigate` takes one screenshot per step, lets a vision model pick the next action, and dispatches to the same direct tools under the hood. The iteration cap is enforced in code (default 5, max 20).

Rules of thumb:

- **Known app, known steps → direct tools.** (Uber, WhatsApp, Spotify, Maps — see section 8.)
- **Unknown app or UI → `android_navigate`.** Let vision decide.
- **Mixed → direct tools to get into the right screen, then `android_navigate` for the last-mile decision.** Don't burn vision budget on `open_app` → `wait` that you can script.
- **Never wrap `android_navigate` in your own retry loop.** If it fails, read the trace, summarize for the user, and stop.

## 7. Common package names

| App | Package |
|---|---|
| Uber (rider) | `com.ubercab` |
| WhatsApp | `com.whatsapp` |
| Spotify | `com.spotify.music` |
| Google Maps | `com.google.android.apps.maps` |
| Chrome | `com.android.chrome` |
| Gmail | `com.google.android.gm` |
| Instagram | `com.instagram.android` |
| X (formerly Twitter) | `com.twitter.android` |
| Tinder | `com.tinder` |
| Settings | `com.android.settings` |

If the user asks about an app not on this list, call `android_get_apps` and match on the human-readable label.

## 8. App playbooks

Each playbook is a minimal bounded sequence. Follow them; do not improvise extra steps.

### Uber — book a ride to a known destination

1. `android_open_app("com.ubercab")`
2. `android_wait(text="Where to", timeout_ms=8000)` — Uber's home search field.
3. `android_tap_text("Where to")` — opens the destination picker.
4. `android_type("<destination>")` — type the address into the focused field.
5. `android_wait(text="<destination-first-word>", timeout_ms=4000)` — wait for autocomplete.
6. `android_tap_text("<first matching result>")` — select the result.
7. `android_read_screen()` — verify the ride-type picker appeared. STOP and report the quoted fare + ETA. **Do not auto-confirm the ride** — requesting a ride is a destructive action that spends money. Let the user confirm manually, or let the Tier 5 destructive-verb gate (section 9) prompt them.

**Pitfalls:**

- Uber sometimes wraps the destination field in a non-clickable container. If `tap_text("Where to")` does nothing, re-read the screen and look for a clickable ancestor node.
- If a login or onboarding splash appears, stop and report — do not try to log in for the user.
- The "Confirm" / "Request" button is on the destructive-verb list by default (see section 9). Expect a 403 if you try to tap it without user confirmation.

### WhatsApp — send a message to a contact

1. `android_open_app("com.whatsapp")`
2. `android_wait(text="Chats", timeout_ms=5000)` — landing tab.
3. `android_tap_text("Search")` or tap the magnifying glass icon.
4. `android_type("<contact name>")` — narrows the chat list.
5. `android_tap_text("<contact name>")` — opens the chat.
6. `android_tap_text("Message")` — focus the input field. (Label varies by locale; fall back to `read_screen` and find the EditText node.)
7. `android_type("<message body>")`
8. **STOP and report.** Do not auto-tap Send — it is on the destructive-verb list. Either let the user tap Send, or invoke the Tier 5 confirmation modal by calling `android_tap_text("Send")` and letting the phone prompt.

**Pitfalls:**

- Multiple chats with the same contact name will show a picker — if `read_screen` shows >1 match, stop and ask which one.
- End-to-end encryption banners on first-chat-of-the-day intercept the input field. Dismiss with `android_tap_text("OK")` if present before typing.

### Spotify — play a track or playlist

1. `android_open_app("com.spotify.music")`
2. `android_wait(text="Search", timeout_ms=5000)`
3. `android_tap_text("Search")`
4. `android_type("<query>")`
5. `android_wait(text="<first word of query>", timeout_ms=3000)`
6. `android_tap_text("<first matching result>")` — opens the track or playlist.
7. `android_tap_text("Play")` — start playback.
8. `android_read_screen()` — verify the now-playing bar shows the expected track. Report and STOP.

**Pitfalls:**

- Free-tier accounts sometimes play a different track than the one tapped (shuffle mode override). Always verify from the now-playing bar, not from what you tapped.
- The Play button is sometimes labeled by icon only. If `tap_text("Play")` fails, re-read and use the node with `className` containing `PlayButton`.

### Google Maps — navigate to a place

1. `android_open_app("com.google.android.apps.maps")`
2. `android_wait(text="Search here", timeout_ms=5000)`
3. `android_tap_text("Search here")`
4. `android_type("<place>")`
5. `android_wait(text="<place-first-word>", timeout_ms=3000)`
6. `android_tap_text("<first result>")`
7. `android_tap_text("Directions")`
8. `android_read_screen()` — report ETA + distance + mode, then STOP. **Do not auto-start turn-by-turn.** "Start" is on the destructive-verb list because it changes the phone's mode of operation.

**Pitfalls:**

- Maps opens straight into "navigation mode" if the user had a previous route cached. Always `read_screen` first and check whether you're on home or already navigating.
- Location permission prompts block everything. If you see a permission modal, stop and hand it to the user.

### Settings — change a specific setting

1. `android_open_app("com.android.settings")`
2. `android_tap_text("Search settings")` — Settings has a global search that saves 80% of navigation effort.
3. `android_type("<setting name>")` — e.g. `"wifi"`, `"notifications"`, `"display"`.
4. `android_wait(text="<setting name>", timeout_ms=3000)`
5. `android_tap_text("<matching result>")`
6. `android_read_screen()` — you're now on the target settings page. Make the toggle change (`tap_text(<label>)`) and verify.
7. Report the before/after state, STOP.

**Pitfalls:**

- OEM skins (Samsung One UI, Xiaomi HyperOS) ship with different Settings package names on some devices. If `com.android.settings` fails, try `com.samsung.android.lool` or fall back to `android_get_apps` and filter on "Settings".
- The Search bar is at the top on stock Android but mid-screen on Samsung. Use `read_screen` to find it, not coordinates.

### Tinder — swipe through the card deck

1. `android_open_app("com.tinder")`
2. `android_wait(text="Discovery", timeout_ms=5000)` or landmark of your choice.
3. `android_read_screen()` — confirm a profile card is visible.
4. `android_swipe("right")` to like, `android_swipe("left")` to pass. (Do NOT use coordinate taps — Tinder's card stack is canvas-rendered and coordinates are unstable.)
5. Repeat step 4 up to 3 times per invocation.
6. `android_read_screen()` — verify the deck advanced. Report and STOP.

**Pitfalls:**

- A "It's a Match!" modal interrupts the deck. Read the screen and tap `Keep Swiping` before continuing.
- Do not auto-send messages. Match-message composition is a destructive action the user should drive.

## 9. Safety rails awareness

The phone enforces user-configurable **Tier 5 safety rails** on every `bridge.command`. You will see these as `403` responses. Do not retry on a 403 — report it.

- **Blocklist.** A DataStore-backed list of package names (banking, payments, password managers, 2FA apps) blocked by default. If the current app is on the blocklist, every tool except `android_ping` and `android_current_app` returns `403 blocked package <name>`. Tell the user to either switch out of the blocked app or remove the app from the blocklist in Hermes-Relay → Bridge → Safety.
- **Destructive-verb confirmation.** `android_tap_text` and `android_type` are intercepted when the payload contains any of the default destructive verbs (`send`, `pay`, `delete`, `transfer`, `confirm`, `submit`, `post`, `publish`, `buy`, `purchase`, `charge`, `withdraw`). The phone pops a confirmation modal with the full payload — the user has a configurable timeout (default 30 s) to allow or deny. A denied or timed-out confirmation returns `403 user denied destructive action`.
- **Idle auto-disable.** After a configurable number of minutes of inactivity (default 30, range 5–120), the phone auto-disables device control and posts a notification. The next `bridge.command` will return `403`. Tell the user to re-enable the bridge master toggle.

**Agent discipline:** expect 403s, report them, do not retry. The user's safety config is not something you should try to work around.

## 10. Sideload-only features

Hermes-Relay ships in two build flavors: `googlePlay` (Play Store, conservative accessibility config) and `sideload` (full agent control). The sideload build unlocks:

- **Voice-to-bridge intents.** Transcribed voice utterances like *"text mom saying on my way"* or *"open Spotify"* are classified on-device and routed through the bridge channel instead of becoming chat messages. Destructive intents (SMS) speak a confirmation and start a 5-second cancellable countdown before dispatching.
- **Contact search, direct SMS, direct dial.** Not exposed as `android_*` tools — they're user-facing voice intents only. You will not see them in your tool list.
- **Full accessibility capability set.** `typeAllMask`, gesture dispatch, interactive windows, view IDs — used by the direct tools under the hood. The Play flavor uses a narrower subset.
- **Location queries.** Phone-side, voice-triggered only.

Agent rule: **you do not need to know which flavor is running to use the `android_*` tools**. Every tool in section 3 works in both flavors. The flavor only matters if you're building a feature that requires sideload capabilities, in which case check `BuildFlavor.current` on the phone side, not from the tool layer.

## 11. Extending this skill

This file is read by the LLM before it touches the `android_*` tools. Keep it tight and keep it current:

1. When new `android_*` tools land, add them to the section 3 table with a one-line purpose.
2. When new apps are commonly requested, add them to section 7 and write a 6–12 step playbook in section 8.
3. When the Tier 5 safety rails change (new blocklist defaults, new destructive verbs, new gates), update section 9.
4. Do not add marketing prose, screenshots, or "Why Hermes-Relay is great" sections. This is an agent-readable spec, not a README.

When adding a new playbook, follow the section 8 template: numbered steps using tool-call syntax, a "Pitfalls" subsection with 2–4 bullets, and an explicit STOP-and-report step before any destructive action.
