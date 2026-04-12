# Features

Hermes-Relay ships in two flavors — **Google Play** (easy install, conservative scope) and **Sideload** (manual install, full feature set). Most chat, voice, and session features are identical across both. The bridge channel — where the agent reads or controls your phone — is where the two tracks diverge.

A **<span class="track-badge track-badge--sideload">Sideload only</span>** badge on a feature row means the feature is compiled out of the Google Play APK and is only available in the sideload build. See the [Release tracks](/guide/release-tracks) page for the full breakdown and a decision guide.

## Chat & Communication

| Feature | Description |
|---------|-------------|
| [Direct API Connection](/features/direct-api) | HTTP/SSE streaming to Hermes API Server |
| [Voice Mode](/features/voice) | Real-time voice conversation — sphere listens, agent speaks back via your server's configured TTS/STT providers |
| [Markdown Rendering](/features/markdown) | Full markdown with syntax-highlighted code blocks |
| [Reasoning Display](/features/reasoning) | Collapsible extended-thinking blocks |
| [Personalities](/features/personalities) | Dynamic from `GET /api/config` — picker, agent name on bubbles |
| [Command Palette](/guide/chat#command-palette) | Searchable command browser — 29 gateway commands, personalities, 90+ skills |
| [Slash Commands](/guide/chat#inline-autocomplete) | Inline autocomplete as you type `/` |
| [QR Code Pairing](/guide/getting-started#qr-code-pairing-recommended) | Scan `hermes-pair` QR to auto-configure connection |
| [Token Tracking](/features/tokens) | Per-message usage and cost |
| [Tool Progress](/features/tools) | Configurable display — Off, Compact, or Detailed |

## Bridge — Phone Control

| Feature | Description | Track |
|---------|-------------|-------|
| Read what's on screen | Agent can see the active screen so it can answer "what does this say?" | Both |
| Notification triage | Agent reads incoming notifications and summarizes them for you | Both |
| Calendar read | "What's on my schedule today?" — read-only access to your calendar | Both |
| Tap, type, and swipe with confirmation | Agent performs UI actions on your behalf | Both <sup>*</sup> |
| Voice → bridge intent routing | "Text Sam I'll be 10 min late" — fully hands-free | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Vision-driven navigation (`android_navigate`) | Agent looks at the screen and figures out what to tap on its own | <span class="track-badge track-badge--sideload">Sideload only</span> |
| Workflow recording | Show the agent something once, ask it to repeat the workflow later | <span class="track-badge track-badge--sideload">Sideload only</span> |

<sup>*</sup> On Google Play, the accessibility service is read-only — the gesture-synthesis code is compiled out for policy reasons. The action UI still surfaces but write actions silently no-op. Sideload ships the full gesture surface.

## Bridge — Safety Rails (always on, both tracks)

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
| Bearer token auth | Optional API key authentication |
| Per-channel grants | Time-bound access for terminal/bridge channels |

## Choose your track

Hermes-Relay ships as two distinct APKs from the same source tree. Pick whichever fits — or install both side-by-side, they coexist on the device.

<FeatureMatrix />

For the full decision guide and install instructions for each, see [Release tracks](/guide/release-tracks).

## Coming Soon

| Feature | Phase |
|---------|-------|
| Push Notifications | Future — Agent-initiated alerts |
| Memory Viewer | Future — View/edit agent memories |
| Cross-device handoff | Phase 3+ — Hand a task from phone to desktop terminal session |

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
