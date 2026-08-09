# Design QA

## Profile Shelf refinement

Status: **blocked**

### Compared

- Source: the selected compact Profile Shelf mock-up from the implementation task.
- Implementation: native Compose dimensions, theme tokens, and interaction semantics in `ProfileShelf.kt`.
- Target state: expanded shelf below the Chat app bar, Server default resolving to the active profile identity.

### Source-level review

- The shelf now shares Chat's base surface and uses only a subtle bottom divider.
- The selected profile uses a 44 dp neutral capsule inside a 48 dp interaction target, with a restrained accent outline.
- Profile artwork is 36 dp inside 48 dp targets; Server default keeps the resolved avatar and adds a small home badge.
- Overflow is pinned in a contained 40 dp visual surface inside its 48 dp target.
- Existing TalkBack labels, long-press actions, horizontal scrolling, switch gating, and full-switcher routing remain intact.

### Blocker

No ADB device is connected, so a real-dimension implementation capture cannot be compared with the selected mock-up. Source inspection and compiled tests do not substitute for visual device QA.

final result: blocked

## Agent Passport

Status: **passed**

### Compared

- Source: `C:\Users\Bailey\.codex\generated_images\019f6640-e6dc-7c33-8aca-a1610e2a19f0\call_JzVlJKlG8KWTJRo2xup7oTlq.png`
- Implementation: `C:\Users\Bailey\.codex\visualizations\2026\07\25\agent-drawer-audit\passport-qa-final.png`
- Source size: 852 x 1846 px
- Device viewport: Android, 1080 x 2340 px, font scale 1.0
- State: Agent tab, Victor pinned profile, disconnected gateway

### Resolved findings

- **P2 - Header hierarchy and identity treatment differed from the selected concept.** The sheet now uses the Agent Passport title, large profile card, circular avatar, pin-style profile chip, status line, and four-column metrics strip.
- **P2 - Configuration controls lacked the concept's card hierarchy.** Personality, model, and reasoning are grouped into one outlined Active configuration card with matching icon medallions and row affordances.
- **P2 - Safety controls did not match the selected inline treatment.** Approval status, chat override, and fast tier now share one outlined Safety & speed card with compact segmented controls.
- **P2 - Primary action looked detached and visually unfinished.** Start new chat now uses the selected full-width purple-to-relay gradient, white outlined chat icon, and a single clean label.
- **P2 - Nested sheet and content gestures caused visible vertical bounce.** Sheet drag gestures are disabled for this scrollable detail surface and overscroll is suppressed; repeated device swipes keep the sheet anchored while the content scrolls.

### Final review

- Layout and hierarchy match the selected Agent Passport direction: profile identity, metrics, Agent/Session tabs, active configuration, safety and speed, primary chat action, and identity customization.
- Interactions remain wired: profile selection, configuration rows, Agent/Session tabs, approval and fast controls, new chat, and identity customization.
- Runtime values remain live rather than copied from the static concept; connection state, message count, route label, provider, and model can therefore differ from the reference.
- Native Android adaptation preserves system insets and scrollability on the taller 1080 x 2340 device viewport.

No open P0, P1, or P2 findings.

final result: passed

## Assistant overlay

- Reference: `C:\Users\Bailey\.codex\generated_images\019fb003-68ea-7052-85c7-3745fa7e838e\call_jPwpDr9QfaCDA6k2c01StBYe.png`
- Implementation captures:
  - `C:\Users\Bailey\.codex\visualizations\2026\07\29\019fb003-68ea-7052-85c7-3745fa7e838e\assistant-live-compact.png`
  - `C:\Users\Bailey\.codex\visualizations\2026\07\29\019fb003-68ea-7052-85c7-3745fa7e838e\assistant-live-expanded.png`
- Combined comparison: `C:\Users\Bailey\.codex\visualizations\2026\07\29\019fb003-68ea-7052-85c7-3745fa7e838e\assistant-design-comparison.png`
- Device viewport: 1080 × 2340, portrait, Samsung SM-S938U
- States reviewed: system assistant compact overlay and expanded overlay over a non-Hermes app

### Full comparison

The implementation preserves the selected progression: a wide bottom compact
bar over the current app, an in-place expanded assistant panel, and an explicit
Open full voice action. The current app remains visible behind both assistant
surfaces. The live expanded panel uses the existing Hermes theme rather than
copying the mock's illustrative app chrome.

### Focused comparison

- Compact: wide rounded bar, active voice orb, status/transcript, expand, and
  stop controls match the reference hierarchy.
- Expanded: drag handle, Hermes identity/status, waveform, transcript/response,
  Stop, collapse, and Open full voice match the reference hierarchy.
- Device review found the translucent expanded surface did not infer a readable
  content color for its title and collapse icon. The surface now explicitly
  uses the theme's `onSurface` color.

### Iteration history

1. Cold assistant activation initially proved the session could overlay another
   app without launching `MainActivity`.
2. Unlocked-device captures proved compact and expanded presentation over
   Reddit.
3. Combined reference/device review found and corrected the expanded foreground
   color inheritance defect.
4. A fresh final capture and full-voice handoff check remain pending because the
   device was locked after the corrected APK was installed.

final result: blocked

## Conversation voice dock

- Reference: `C:\Users\Bailey\.codex\generated_images\019fba42-1e93-78e1-9cd6-6919fb52d5bc\exec-89feab69-9caf-4084-b107-6bff43e511d6.png`
- Implementation captures:
  - `C:\Users\Bailey\.codex\visualizations\2026\07\31\019fba42-1e93-78e1-9cd6-6919fb52d5bc\voice-ui-audit\implemented_conversation_final.png`
  - `C:\Users\Bailey\.codex\visualizations\2026\07\31\019fba42-1e93-78e1-9cd6-6919fb52d5bc\voice-ui-audit\conversation_refined_expanded.png`
  - `C:\Users\Bailey\.codex\visualizations\2026\07\31\019fba42-1e93-78e1-9cd6-6919fb52d5bc\voice-ui-audit\focus_final_expanded.png`
- Combined comparison: `C:\Users\Bailey\.codex\visualizations\2026\07\31\019fba42-1e93-78e1-9cd6-6919fb52d5bc\voice-ui-audit\voice-dock-design-comparison-final.png`
- Expanded before/after comparison: `C:\Users\Bailey\.codex\visualizations\2026\07\31\019fba42-1e93-78e1-9cd6-6919fb52d5bc\voice-ui-audit\voice-expanded-before-after.png`
- Entry transition recording: `C:\Users\Bailey\.codex\visualizations\2026\07\31\019fba42-1e93-78e1-9cd6-6919fb52d5bc\voice-ui-audit\voice_entry_transition.mp4`
- Entry transition frame sequence: `C:\Users\Bailey\.codex\visualizations\2026\07\31\019fba42-1e93-78e1-9cd6-6919fb52d5bc\voice-ui-audit\voice_entry_transition_frames.png`
- Reference size: 853 x 1844 px
- Device viewport: 1080 x 2340, portrait, Samsung SM-S938U
- States reviewed: collapsed idle dock, expanded Tap-mode controls, collapsed and expanded Focus header, Focus-to-Conversation transition

### Full comparison

The implementation preserves the selected hierarchy inside the existing native
chat screen: voice state and waveform at left, one centered stateful microphone,
Focus and expansion at right, and the regular message composer immediately below.
Runtime chat history and the app header intentionally remain live rather than
copying the mockup's illustrative content.

### Focused comparison

- The conversation state no longer retains the focus-mode top session pill.
- The dock and text composer share one rounded surface and one microphone owner.
- The 52 dp mic remains the dominant action without obscuring model or reasoning controls.
- Expanded Tap, Hold, Auto, route, profile, Overlay, Exit, and Settings controls open above the dock and remain readable without truncation.
- Device review found the compact waveform painting beyond its assigned width and touching the Ready label. Compact bar geometry now fits a dedicated 40 dp lane with a visible gap before status text.
- Expanded review found disconnected metadata pills and excess vertical space. Both modes now use one two-line voice-route summary with a compact secondary-action row.
- Focus review found the animated avatar drawing above the expanded header and obscuring controls. The focus header now owns the foreground z-order, and its collapsed identity text is split into two readable lines.
- Voice entry always starts in Conversation presentation, including when Focus was the persisted preference. The composer expands upward over 240 ms with a coordinated fade, the mic remains anchored, and device frame review found no Focus overlay flash or clipped controls.

### Iteration history

1. Installed the selected implementation and verified the Focus-to-Conversation transition on the connected phone.
2. Captured collapsed and expanded conversation states at the production device viewport.
3. Corrected the waveform/status collision identified during live review.
4. Rebuilt, reinstalled, and captured the corrected idle and expanded states.
5. Compared the reference and final device capture together at normalized dimensions.
6. Compared the original and refined expanded Conversation and Focus states together; the final panels remove metadata fragmentation, header truncation, and avatar bleed-through.
7. Recorded a persisted-Focus voice entry, inspected the 30 fps frame sequence, and verified that Conversation appears first through a continuous composer expansion before Focus is offered as an explicit action.
8. Re-audited the installed Standard-mode drawer in both presentations. The final Conversation and Focus captures are:
   - `C:\Users\Bailey\.codex\visualizations\2026\07\31\019fba42-1e93-78e1-9cd6-6919fb52d5bc\voice-ui-audit-standard\21-final-expanded.png`
   - `C:\Users\Bailey\.codex\visualizations\2026\07\31\019fba42-1e93-78e1-9cd6-6919fb52d5bc\voice-ui-audit-standard\24-final-focus-expanded.png`
   The route summary is now two clearly separated lines (`Standard mode · Victor` and `xAI TTS · Leo`), with duplicate provider/model aliases removed and configuration provenance left to Voice Settings.
9. Verified the Conversation long-press menu on-device in `22-final-speak-menu.png`: completed assistant messages expose Copy, Quote in reply, and Speak response without stacking Android's text-selection toolbar over the app menu.
10. Verified the connection footer remains present under the Conversation composer. Focus remains the only in-app voice presentation that hides it.
11. Attachment-state unit coverage now distinguishes a real image from PDF/generic files, using Attachment ready as the non-image fallback.

No open P0, P1, or P2 findings.

final result: passed

## System voice overlay redesign

- Selected reference: `C:\Users\Bailey\.codex\generated_images\019fba42-1e93-78e1-9cd6-6919fb52d5bc\exec-6a606b04-58c3-4a6d-b11b-cd25f99a047d.png`
- Source concepts:
  - `C:\Users\Bailey\.codex\generated_images\019fba42-1e93-78e1-9cd6-6919fb52d5bc\exec-31a5512a-9cb7-455a-b111-d5797ab09e93.png`
  - `C:\Users\Bailey\.codex\generated_images\019fba42-1e93-78e1-9cd6-6919fb52d5bc\exec-bbfd616e-3847-4a2e-bbd0-65deb5e4e98c.png`
- Baseline captures:
  - `C:\Users\Bailey\.codex\visualizations\2026\07\31\019fba42-1e93-78e1-9cd6-6919fb52d5bc\voice-ui-audit-standard\29-current-overlay-expanded.png`
  - `C:\Users\Bailey\.codex\visualizations\2026\07\31\019fba42-1e93-78e1-9cd6-6919fb52d5bc\voice-ui-audit-standard\30-current-overlay-expanded.png`
- Device viewport: 1080 x 2340, portrait, Samsung SM-S938U
- States to review: collapsed system overlay and expanded system overlay over a non-Hermes app

### Implemented direction

- One stable rounded surface and header remain mounted in both states.
- Only the expansion body clips and fades, using a short 160 ms reveal instead of animating the full card with a spring.
- Waveform, transcript, response, route detail, and actions share one flat hierarchy separated only by hairlines.
- Standard-mode route data uses readable labels and removes duplicate provider/model aliases.
- Minimize, Hide, Open Hermes, microphone control, and Exit remain wired.

### Pending visual QA

Final same-viewport implementation captures and a combined reference/implementation comparison require user-positioned device states. UI review is intentionally user-driven.

final result: blocked
