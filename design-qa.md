# Voice Settings design QA

Status: **passed**

## Compared

- Source: `voice-audit/mockup-04-recommended-hybrid.png`
- Implementation: `voice-audit/implementation-03-provider-preview-refined.png`
- Viewport: Android `Medium_Phone`, 1080 x 2400 px (about 390 dp wide), font scale 1.0
- State: Output tab, xAI Grok TTS, Eve selected and actively previewing

## Resolved findings

- **P2 - Group boundaries were too weak.** Provider, model/voice, and language/quality surfaces now use the product's rounded Material cards with outline-token borders and a subtle surface-variant fill.
- **P2 - Preview state was too tall.** The existing voice waveform now accepts a caller-provided height; inline preview uses a compact 28 dp waveform while the full voice overlay keeps its 56 dp default.
- **P2 - Long catalogs displaced the core controls.** The selected/recommended voices remain inline, while the full catalog opens from a compact `View all voices` action.
- **P2 - Secondary controls competed with selection.** Latency, fallback, expressive tags, and manual IDs now stay inside the collapsed Language & quality section.
- **P3 - Active tab lacked the target emphasis.** The selected segment now uses the theme primary/on-primary colors.

## Final review

- Layout and hierarchy match the selected hybrid: summary, Output/Listening/Advanced tabs, grouped provider, grouped model/voice, inline play/stop, active waveform, and collapsed language/quality.
- Interactions are wired: tabs switch sections, provider/model/voice selections update the draft, only one preview can play, the active play button becomes Stop, and full catalogs remain accessible.
- Accessibility: native Material controls retain semantics and practical tap targets; the active preview has explicit play/stop descriptions; the screen remains vertically scrollable for large text and long provider catalogs.
- Accepted native adaptation: Android exposed dropdowns and dynamic account-backed catalogs use more vertical space than the static concept. The hierarchy and core actions remain intact without clipping or overlap.

No open P0, P1, or P2 findings.
