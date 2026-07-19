# Features

Hermes-Relay ships in two flavors — **Google Play** (easy install, conservative scope) and **Sideload** (manual install, full feature set). Chat, profiles, voice, relay pairing, terminal/TUI relay, media, notification companion, sessions, and diagnostics are available across both. The Device Control channel — where the agent reads or controls your phone — is sideload-only.

A **<span class="track-badge track-badge--sideload">Sideload only</span>** badge on a feature row means the feature is compiled out of the Google Play APK and is only available in the sideload build. See the [Release tracks](/guide/release-tracks) page for the full breakdown and a decision guide.

## Chat & Communication

| Feature | Description |
|---------|-------------|
| [Vanilla Hermes Chat Transport](/features/direct-api) | Gateway `/api/ws` (live thinking) preferred, API-server SSE fallback — never the relay |
| [Voice Mode](/features/voice) | Real-time voice conversation — you talk, the agent answers aloud via your server's configured TTS/STT providers |
| [Markdown Rendering](/features/markdown) | Full markdown with syntax-highlighted code blocks |
| [Reasoning Display](/features/reasoning) | Collapsible extended-thinking blocks |
| [Connections](/features/connections) | Save multiple Hermes servers — one-tap switch from the top-bar chip |
| [App Themes](/features/themes) | Eight looks — the whole app follows your choice, light/dark aware |
| [Profiles](/features/profiles) | Auto-discovered upstream agent directories — overlay model + SOUL on chat turns |
| [Personalities](/features/personalities) | Dynamic from `GET /api/config` — picker, agent name on bubbles |
| [Command Palette](/guide/chat#slash-commands) | Searchable command browser — 29 gateway commands, personalities, 90+ skills |
| [Slash Commands](/guide/chat#slash-commands) | Inline autocomplete as you type `/` |
| [Vanilla Hermes Setup](/guide/getting-started#_3-connect-chat) | Connect through the Dashboard/Gateway; add API fallback or Relay later when needed |
| [Token Tracking](/features/tokens) | Per-message usage and cost |
| [Tool Progress](/features/tools) | Configurable display — Off, Compact, or Detailed |

## Bridge Core

Available on both tracks:

| Feature | Description | Track |
|---------|-------------|-------|
| Relay pairing and status | QR/manual pairing, relay session grants, endpoint health, and diagnostics | Both |
| Terminal/TUI relay | Remote shell and Hermes TUI access through paired relay sessions | Both |
| Notification companion | Optional Android Notification Access forwards posted-notification metadata to your relay | Both |
| [Notification triggers](/features/notification-triggers) | Opt-in local rules post an “Ask Hermes?” prompt when matching notifications arrive, with an activity log and kill switch | Both |
| Media handoff | Relay-registered media appears in chat and can be shared through Android-native flows | Both |

## Device Control <span class="track-badge track-badge--sideload">Sideload only</span>

**Reading the screen**

| Feature | Description | Track |
|---------|-------------|-------|
| Read what's on screen | Agent sees the active window so it can answer "what does this say?" | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Multi-window read | Sees system overlays, popups, and the notification shade — not just the foregrounded app | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Filtered node search | "Find every clickable labelled 'Save'" — precise accessibility-tree queries instead of guessing | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Per-node property lookups | Stable node IDs that can be handed back to tap/scroll, plus full property bags for resolution | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Screen change detection | Cheap screen-hash + diff so the agent can wait for a screen to actually change without polling | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Real-time UI event stream | Live accessibility-event stream for "wait until this loads" and "notice when a dialog opens" waits | <span class="track-badge track-badge--sideload">Sideload only</span> |

**Acting on the phone**

| Feature | Description | Track |
|---------|-------------|-------|
| Tap, type, swipe, scroll | Core UI actions with destructive-verb confirmation | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Long-press | Context menus, text selection, widget rearranging — by coordinate or node ID | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Drag | Rearrange icons, pull notification shade, drag map pins — point A → point B over a duration | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Smarter tap fallbacks | Three-tier cascade handles apps that wrap labels in non-clickable parents | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Clipboard bridge | Read and write the system clipboard from the agent side | <span class="track-badge track-badge--sideload">Sideload only</span> |
| System media control | Play / pause / next / previous / volume on whichever app is playing | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Macro batching | Run a sequence of actions as one workflow without a round-trip per step | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Raw Intent escape hatch | Send a direct Android Intent or broadcast for apps that expose deep-link actions | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Gesture reliability under idle | Short-lived wake-lock keeps gestures landing on dim/idle screens | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Per-app playbooks | Bundled `android` skill with reusable flows for common apps | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Voice → bridge intent routing | "Text Sam I'll be 10 min late" — fully hands-free | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Vision-driven navigation (`android_navigate`) | Agent looks at the screen and figures out what to tap on its own | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Workflow recording | Show the agent something once, ask it to repeat the workflow later | <span class="track-badge track-badge--sideload">Sideload only</span> |

**Phone utilities** <span class="track-badge track-badge--sideload">Sideload only</span>

| Feature | Description |
|---------|-------------|
| Direct SMS | Send text messages via `SmsManager` with send-result confirmation — no dialer bounce |
| File and media sharing | Share images, PDFs, audio, video, generated files, and captions through Android's native share sheet |
| MMS attachment handoff | Open the messaging app with recipient, text, and attachments prepared for user-mediated send |
| Contact search | Look up a phone number by contact name for voice intents like "text Mom" |
| One-tap dialing | Place a call directly from the sideload build |
| Location awareness | GPS last-known-location read for "where am I?" and location-scoped commands |

Google Play builds do not include AccessibilityService-backed screen reading or phone-control code. Direct Device Control probes fail closed with `403 device_control_sideload_only`.

## Device Control Safety Rails <span class="track-badge track-badge--sideload">Sideload only</span>

| Feature | Description |
|---------|-------------|
| Per-app blocklist | Banking, password managers, and work email default-blocked from bridge actions |
| Confirmation on destructive verbs | "Send", "pay", "delete", "transfer" always prompt before acting |
| Auto-disable on idle | Bridge turns itself off after a configurable idle period; re-enable requires biometric |
| Activity log | Every command logged with timestamp, result, and screenshot thumbnail |
| Persistent notification | One-tap kill switch in the system tray whenever bridge is on |

## Session Management

| Feature | Description |
|---------|-------------|
| Session drawer | Create, switch, rename, delete sessions |
| Auto-titles | Sessions titled from first message |
| Message history | Loads from server on session switch |
| Persistence | Last session resumes on app restart |

## Analytics

| Feature | Description |
|---------|-------------|
| Stats for Nerds | TTFT, completion times, token usage, health latency, stream rates |
| Canvas bar charts | Purple gradient charts in Settings |

## UX Polish

| Feature | Description |
|---------|-------------|
| Animated splash screen | Scale + overshoot + fade animation, hold-while-loading |
| Chat empty state | Logo + suggestion chips |
| Animated streaming dots | Pulsing 3-dot indicator during streaming |
| Haptic feedback | On send, copy, stream complete, error |
| App context prompt | Toggleable system message for mobile context |

## Security

| Feature | Description |
|---------|-------------|
| Android Keystore session storage | StrongBox-preferred, TEE fallback |
| TOFU cert pinning | Trust-on-first-use SHA-256 SPKI fingerprints |
| Native auth separation | Dashboard session for the standard path; optional API bearer and Relay session remain scoped to their own surfaces |
| Per-channel grants | Time-bound access for terminal, bridge, TUI, and voice channels |

## Choose your track

Hermes-Relay ships as two distinct APKs from the same source tree. Pick whichever fits — or install both side-by-side, they coexist on the device.

<FeatureMatrix />

For the full decision guide and install instructions for each, see [Release tracks](/guide/release-tracks).

## Coming Soon

| Feature | Status |
|---------|--------|
| Push Notifications | Future — Agent-initiated alerts |
| Memory Viewer | Future — View/edit agent memories |
| Cross-device handoff | Future — Hand a task from your phone to a desktop hand |

<style scoped>
.track-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 12px;
  font-family: var(--vp-font-family-mono);
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  white-space: nowrap;
  border: 1px solid transparent;
  line-height: 1.4;
}
.track-badge--sideload {
  color: var(--vp-c-brand-1);
  border-color: var(--vp-c-brand-1);
  background: var(--vp-c-brand-soft);
}
</style>
