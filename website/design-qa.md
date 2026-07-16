# Design QA

- Source visual truth: `design/responsive-reference.png`
- Desktop implementation evidence: `design/implementation-desktop-hero.png`, `design/implementation-desktop-surface.png`
- Mobile implementation evidence: `design/implementation-mobile-hero.png`, `design/implementation-mobile-surface.png`
- Combined comparison evidence: `design/qa-comparison.png`
- Annotation-fix evidence: `design/implementation-annotated-desktop.png`, `design/implementation-annotated-gallery-full.png`, `design/implementation-annotated-mobile-hero.png`, `design/implementation-annotated-mobile-gallery-full.png`
- Annotation comparison evidence: `design/qa-comparison-annotations.png`
- Live-sphere evidence: `design/implementation-sphere-desktop-hero.png`, `design/implementation-sphere-desktop-voice.png`, `design/implementation-sphere-mobile-hero.png`, `design/implementation-sphere-mobile-voice.png`
- Corrected motion evidence: `design/implementation-voice-smooth-desktop.png`, `design/implementation-voice-smooth-mobile.png`
- Live-sphere comparison evidence: `design/qa-comparison-sphere.png`
- Voice-reference evidence: `public/product/voice.png`, `design/implementation-voice-reference-desktop.png`, `design/implementation-voice-reference-mobile.png`, `design/implementation-voice-reference-card.png`
- Voice-reference comparison evidence: `design/qa-comparison-voice-reference.png`
- Voice-transcript evidence: `design/implementation-voice-transcript-desktop.png`, `design/implementation-voice-transcript-mobile.png`, `design/implementation-voice-transcript-card.png`
- Voice-transcript comparison evidence: `design/qa-comparison-voice-transcript.png`
- CLI balance evidence: `design/implementation-cli-balance-desktop.png`, `design/implementation-cli-balance-mobile.png`
- Voice chrome first-pass evidence: `design/implementation-voice-chrome-desktop.png`, `design/implementation-voice-chrome-mobile.png` (superseded by the width-ratio correction below)
- Header and theme evidence: `design/implementation-header-dark-desktop.png`, `design/implementation-header-light-desktop.png`, `design/implementation-header-light-mobile.png`
- Desktop viewport: 1440 × 1000
- Annotated desktop viewport: 2018 × 986, with a 2018 × 1600 gallery inspection
- Mobile viewport: 390 × 844
- State: dark theme, default landing route, mobile navigation closed after interaction test

## Findings

No actionable P0, P1, or P2 differences remain.

- Fonts and typography: Space Grotesk and Space Mono reproduce the reference hierarchy and utility labels. Desktop and mobile headline wrapping remains deliberate and untruncated.
- Spacing and layout rhythm: the desktop hero preserves the large phone/copy balance; the Android/CLI region uses the reference's two-column split and becomes a sequential mobile flow without horizontal overflow.
- Colors and visual tokens: graphite, warm white, Hermes violet, cyan utility accents, and green live states match the source direction with accessible contrast.
- Image quality and asset fidelity: the implementation uses the repository's real logo, full-resolution Chat and Manage captures, and the canonical live Voice renderer. No placeholder or approximate product imagery remains.
- Motion and product identity: the hero atmosphere and Voice surface use the same canonical `preview/web/sphere.js` renderer as the docs. The Voice surface also ports the product waveform's three-layer frequencies, amplitude scaling, listening/speaking palettes, and thinking spinner behavior.
- Voice fidelity: the live preview now follows the real Android capture and current overlay hierarchy—compact provider header, dominant sphere, waveform beneath it, state-specific instruction, and bottom mic/stop control—rather than an invented status strip.
- Voice transcript fidelity: the preview now follows the production overlay's compact `YOU`/`AGENT` transcript rows, progressively reveals partial user and agent text, retains the latest response unbounded, and tracks the active state without moving the sphere or waveform slots.
- Voice control fidelity: the top control now mirrors the current Android `VoiceSessionPill` as an inset opaque rounded surface with Voice, profile summary, expand, and close affordances. The primary mic uses the app's approximately 72dp-on-360dp width ratio instead of a viewport-based clamp.
- Secondary phone framing: the Chat capture remains at its intrinsic 1:2 ratio, but its smaller 200 px mobile footprint keeps it subordinate to the feature list. The decorative speaker is fully contained in the top bezel and no longer crosses the screenshot UI.
- Source fidelity: the ambient hero sphere now follows the docs `SphereMark.vue` behavior, including scroll/pointer gaze, exponential smoothing, restrained idle wander, fast-out-slow-in transitions, canonical colors, and the darker shadow hemisphere that makes the bright focus readable.
- Product accuracy: the terminal preview uses the real `hermes-relay` binary and current attach language from `desktop/README.md`; the footer uses the docs homepage's Hermes Agent, Axiom-Labs, and MIT License attribution.
- CLI balance and accuracy: the CLI column now teaches three shipped surfaces—tmux session continuity, the consented background daemon, and the local audit log—using their real `hermes-relay` commands. The two-plus-one desktop card rhythm fills the former dead space beside Android and becomes a single stack on mobile.
- Copy and content: the selected hero, self-hosted trust line, Android/CLI framing, and explicit local-consent language match the approved direction and current product boundary.
- Header identity: the current stable Android version is derived from `gradle/libs.versions.toml`, linked to its release, and paired with the compact `Android + CLI for Hermes` descriptor. The descriptor hides at narrow widths while the brand, version, theme control, and menu remain visible.
- Theme behavior: light and dark palettes are system-aware on first visit, persist explicit user choice, and update the control label/icon accessibly. Product captures, the terminal, and the Voice preview intentionally remain dark embedded surfaces in either page theme.
- Setup clarity: a compact setup band mirrors the docs homepage's additive model—vanilla Hermes first, optional Relay plugin for the power path—with direct links to Quick Start and the canonical “how the pieces combine” guide.
- Bottom conversion: the setup band repeats the Android install action after the product story, followed by Quick Start and the quieter Relay plugin path, so visitors do not need to return to the hero.
- Community support: Ko-fi appears in the privacy/open-source strip as a restrained secondary link, keeping Android, CLI, and Quick Start as the page's primary actions.
- Flagship capability: Device Control now has one focused product section using a live recreation grounded in the current Bridge screen. Its copy and visual both preserve the Sideload + Relay plugin boundary, local master control, safety settings, and visible activity history.
- Production readiness: `https://hermes-relay.dev` is the canonical Astro site origin for canonical tags, Open Graph/Twitter cards, robots, sitemap, and SoftwareApplication JSON-LD. The launch build remains static and tracking-free.
- Delivery efficiency: canonical Android PNG captures remain untouched while deterministic 260/520 px Chat and 360/720 px Manage WebP derivatives provide correctly sized mobile and desktop sources.

## Full-view comparison evidence

`design/qa-comparison.png` places the full responsive source board beside the desktop hero, desktop product split, mobile hero, and mobile product split. The implementation preserves the source's information hierarchy and responsive sequence. The source's photographic rock floor and luminous connection curve were intentionally reduced so the real app captures and direct-connection copy remain the visual truth.

## Focused region comparison evidence

- Desktop hero: `design/implementation-desktop-hero.png` confirms headline scale, CTA priority, trust line, and dominant phone crop.
- Desktop product split: `design/implementation-desktop-surface.png` confirms the Android/CLI hierarchy, central divider, and restrained feature density.
- Mobile hero: `design/implementation-mobile-hero.png` confirms the collapsed navigation, stacked CTAs, legible trust line, and centered phone reflow.
- Mobile product split: `design/implementation-mobile-surface.png` confirms the desktop columns become a readable sequential flow.

## Comparison history

1. Initial pass found two P2 issues: reveal animation left content dim during immediate capture, and mobile hero spacing pushed almost all product imagery below the first viewport.
2. Fixes: removed reveal-gated visibility, hid the mobile eyebrow, tightened mobile headline/action spacing, and reduced the mobile phone frame to preserve an above-the-fold product glimpse.
3. Post-fix evidence: the current desktop and mobile screenshots in `design/` show fully visible content, correct responsive reflow, and no horizontal overflow.
4. Browser annotations found three P2 issues: the route status overlaid the hero phone, headline leading allowed line collisions, and the Voice/Manage captures were cropped into undersized thumbnails.
5. Fixes: moved route status into a centered line beneath the phone, relaxed display leading and tracking at both breakpoints, and expanded the secondary screenshot grid while preserving each capture's natural 1:2 aspect ratio.
6. Post-annotation evidence: the hero phone and route label have separate bounding boxes at 2018 × 986 and 390 × 844; full-height gallery captures show both secondary screens uncropped at desktop and mobile widths.
7. The motion pass replaced the static hero sphere fragment and Voice thumbnail with live renderers. The first pass recomputed waveform phase from elapsed time multiplied by changing amplitude and used a simplified exponential sphere blend; this produced erratic motion and visible state discontinuities.
8. Fixes: ported the docs HeroDemo smoothstep tween rig and monotonic on-screen clock, restored per-layer waveform phase accumulators from `VoiceWaveform.kt`, added attack/release amplitude following, 400 ms palette transitions, 360 ms fold/unfold transitions, and the product's edge-alpha mask.
9. Post-motion evidence: `design/qa-comparison-sphere.png` and the corrected desktop/mobile captures show a clean three-layer waveform, stable sphere geometry, and unchanged 1:2 alignment beside the Manage screenshot. Browser timing checks observed Listening → Thinking → Speaking in order without console errors.
10. The screenshot-reference pass found four P2 drifts: Voice anatomy did not match the real capture, the hero sphere lacked the docs gaze behavior and was too dim, the terminal used the unrelated `hermes chat` command, and the footer omitted the docs attribution.
11. Fixes: rebuilt the Voice preview around the capture/current overlay hierarchy, ported docs gaze and brightness behavior, replaced the terminal content with the real Relay attach sequence, and aligned the footer with the public docs.
12. Post-reference evidence: `design/qa-comparison-voice-reference.png` shows the source capture and responsive implementation together; desktop and 390 px mobile browser checks show the new preview uncropped beside Manage.
13. The transcript/framing pass found two P2 drifts: the small Chat phone's decorative speaker crossed into the captured UI and the live Voice card reproduced only the older idle screenshot rather than the current overlay's rolling transcript.
14. Fixes: shortened the secondary phone while preserving its full 1:2 capture, moved the speaker slot into the bezel, and added the production `YOU`/`AGENT` hierarchy with progressively streamed user and assistant text plus a stable latest-content viewport.
15. Post-fix evidence: `design/qa-comparison-voice-transcript.png` pairs the canonical Android capture with the live speaking preview; desktop and 390 px captures confirm the transcript remains legible, the Voice/Manage cards stay aligned, and the phone screenshot is unobstructed.
16. The column-balance pass found one P2 issue: Android's additional Voice/Manage evidence extended far below the shorter CLI story, leaving the right half visibly empty.
17. Fix: added command-led cards for resumable sessions, the background daemon, and the local audit log, sourced from `desktop/README.md`. Desktop uses a two-card row plus one wide anchor card; mobile uses an equal-width vertical stack.
18. Post-fix evidence: `design/implementation-cli-balance-desktop.png` and `design/implementation-cli-balance-mobile.png` show balanced column endings, readable commands, and no horizontal overflow.
19. The Voice-chrome pass found two P2 drifts: the preview rendered the old screenshot's flat text header instead of the current floating session pill, and its mic scaled from viewport width until it exceeded the app's proportions.
20. Fix: rebuilt the header as the current inset rounded session control with expand/close affordances and changed the mic to a 20%-of-card width rule capped at 48 px, with a 30 px mobile floor.
21. Post-fix evidence: `design/implementation-voice-chrome-desktop.png` and `design/implementation-voice-chrome-mobile.png` show the pill silhouette and mic proportions at 1778 × 986 and 390 × 844 without clipping or overflow.
22. Follow-up review found the pill silhouette was still too heavy because its 10% height was derived from the 1:2 card's tall axis, producing a 47.4 px control on a 238 px-wide miniature.
23. Fix: changed the pill to the Compose control's approximately 56dp-on-360dp width ratio with a 28–37 px clamp, tightened horizontal padding, and returned the recovered height to the animated content stage.
24. Post-fix browser measurement: the desktop pill is 36.6 px high on a 238 px card; the mobile pill is 28 px high on a 159.6 px card. Both preserve the inset shape without the previous heavy band.
25. The product-header pass added a repository-derived `v1.4.5` release badge, the compact Android/CLI descriptor, and a persisted system-aware light/dark switch without expanding the mobile navigation footprint.
26. Post-header evidence: desktop dark and light captures preserve the same hierarchy; the 390 × 844 light capture fits the brand, version, theme control, and menu in one row with no horizontal overflow. The expanded menu remains readable, and a dark selection survives reload.
27. The setup-path pass moved Ko-fi from the footer into the privacy/open-source strip, removed the unused Discussions destination, and reorganized footer links into Product, Resources, and Project groups.
28. The same pass added one source-aligned setup band: vanilla Hermes capabilities first, optional Relay plugin power features second, with Quick Start as the primary action and the canonical release-track explanation as the supporting link.
29. The flagship-feature pass found no canonical Device Control image in the deterministic screenshot set because those store-safe captures exclude sideload-only capabilities.
30. Fix: added a Bridge-screen recreation sourced from the current Compose screen and strings, with Agent Control, relay/safety state, blocked apps, destructive-action confirmation, idle auto-disable, and Activity Log represented without implying availability in the Google Play build.
31. The production pass added the final-domain metadata surface, social card/touch icon, robots and sitemap endpoints, JSON-LD, Nixpacks/Node configuration, and a built-site link/fragment/asset validator.
32. Lighthouse identified low-contrast CTA and utility text, a version-badge label mismatch, and oversized mobile screenshot delivery. The pass corrected the colors and accessible name and introduced checked responsive image derivatives without altering the canonical captures.
33. Final production Lighthouse result: Performance 91, Accessibility 100, Best Practices 100, SEO 100; CLS 0 and total blocking time 0 ms.

## Verification

- Primary interactions tested: mobile menu open, in-page Product navigation, menu auto-close.
- CTA and navigation destinations inspected in the rendered DOM.
- Browser console checked: no warnings or errors.
- Mobile horizontal overflow checked: none.
- Route status overlap check at desktop and mobile: passed.
- Secondary screenshot intrinsic-size and full-aspect checks: passed for both 1080 × 2160 captures.
- Live Voice sequence checked in-browser: Listening, Thinking, and Speaking states all rendered, with the waveform changing from cool purple/blue to green/teal.
- Live transcript checked in-browser: partial user text grows during Listening, the Thinking row appears during processing, and agent text grows during Speaking. No horizontal overflow or browser console warnings/errors were observed at 2018 × 986 or 390 × 844.
- CLI card responsiveness checked in-browser: the 1778 × 986 layout renders two equal cards plus a full-width third card; the 390 × 844 layout renders three 331 px cards in one column. Browser console remained clear.
- Voice chrome checked in-browser: the 238 px desktop card renders a 217.5 × 36.6 px session pill and a 40.7 px active mic; the 159.6 px mobile card renders a 145.4 × 28 px pill and a 30 px mic. Mobile has no horizontal overflow and the browser console remained clear.
- Header checked in-browser at 1778 × 986 and 390 × 844: release link resolves to `android-v1.4.5`, desktop descriptor is visible, mobile descriptor is hidden, mobile menu opens cleanly, and both themes render without horizontal overflow.
- Theme persistence checked in-browser: switching to dark updates the accessible label to `Switch to light theme`, and the dark selection remains active after reload.
- Setup path checked in-browser at 1778 × 986 and 390 × 844: the primary action uses the established violet treatment, supporting copy and links remain readable, the privacy actions wrap cleanly, and no horizontal overflow or console warnings were introduced.
- Device Control checked in-browser at 1778 × 986 and 390 × 844: the copy/Bridge split is balanced on desktop, the capability and safety story becomes a deliberate mobile sequence, the 331 px Bridge recreation remains legible, and neither theme introduces clipping or horizontal overflow. The embedded Bridge surface intentionally remains dark in the light page theme.
- Production metadata verified in the built HTML against `https://hermes-relay.dev`; robots and sitemap resolve to the same origin, and the 1200 × 630 social card was inspected at full resolution.
- Responsive delivery checked in-browser at 1778 × 986 and 390 × 844: mobile selects `chat-260.webp`, desktop retains the higher-density source where appropriate, and both layouts remain visually unchanged with no console errors or horizontal overflow.
- Animation lifecycle: request-animation-frame pacing, device-pixel-ratio cap, monotonic on-screen clock, offscreen pause, hidden-tab pause, and reduced-motion still frame are implemented.
- `npm run check`: passed.
- `npm run build`: passed.

## Follow-up polish

- P3: a future asset pass could add a lightweight photographic grounding texture beneath the hero phone if the landing page needs more of the mockup's physical depth.

final result: passed
