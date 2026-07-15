# Prototype Instructions

Run the local server yourself and open the preview in the browser available to this environment. Do not give the user server-start instructions when you can run it.

Before making substantial visual changes, use the Product Design plugin's `get-context` skill when the visual source is unclear or no longer matches the current goal. When the user gives durable prototype-specific design feedback, preferences, or decisions, record them in `AGENTS.md`.

When implementing from a selected generated mock, treat that image as the source of truth for layout, component anatomy, density, spacing, color, typography, visible content, and hierarchy.

## Selected Hermes-Relay direction

- Preserve the cinematic, phone-led hero from the selected "Pocket Command Center" direction.
- Use the clearer Android-versus-CLI secondary layout from the connected-systems direction.
- Desktop and mobile are equal requirements. Mobile must reflow intentionally: stacked CTAs, centered product imagery, and sequential Android then CLI sections.
- Keep the public docs as a separate destination; this directory is the product/marketing site.
- Keep hero copy comfortably separated; tight display typography must never cause line collisions.
- Keep phone-frame corners restrained enough that captured top and bottom navigation remain comfortably inside the frame. Static screenshot phones should use their intrinsic 1:2 image ratio rather than cropping into a fixed decorative frame. Decorative speaker slots must stay inside the physical border, never over the captured UI, and secondary phone renders must not grow tall enough to dominate their feature list.
- Place connection-route status on its own centered line beneath the hero phone, not over the device artwork.
- Show secondary Android screenshots at their natural aspect ratio and at a useful comparison size; do not crop them into thumbnails.
- Reuse the canonical MorphingSphere renderer as a restrained hero atmosphere layer and as the live Voice surface; pair the Voice sphere with the product's layered waveform animation while keeping Manage as concrete screenshot evidence.
- Keep Voice motion behavior aligned with the docs and Android sources: monotonic on-screen clocks, smoothstep sphere tweens, accumulated waveform phases, attack/release amplitude tracking, and folded processing transitions. Do not derive phase directly from a changing amplitude.
- Match the live Voice preview to the real Android capture and current overlay hierarchy: inset rounded session pill with the current header controls, large sphere, waveform directly beneath it, rolling YOU/AGENT transcript, state instruction, and bottom mic/stop control. Size both the session pill and primary mic from the miniature app surface width—the pill follows the app's roughly 56dp-on-360dp ratio and the mic its 72dp-on-360dp ratio—rather than scaling either control from the browser viewport or the card's doubled height. The transcript must stream and follow the latest content like the production overlay; do not rely only on the older idle screenshot or add invented status strips and helper copy.
- Match the ambient hero sphere to the docs homepage interaction: pointer and scroll-driven gaze, smoothed tracking, subtle idle wander, and the canonical sphere core.
- CLI marketing examples must use the real `hermes-relay` binary and language taken from `desktop/README.md` or `desktop/src/cli.ts`.
- Keep the Android and CLI columns visually balanced at desktop widths. CLI support cards should teach concrete shipped commands and capabilities from `desktop/README.md` rather than adding generic marketing filler; collapse them into a single readable stack on mobile.
- Keep product identity visible in the header: derive the current stable Android release from `gradle/libs.versions.toml`, pair it with the short descriptor "Android + CLI for Hermes," and retain the persisted light/dark theme switch on desktop and mobile. Theme work must preserve the dark product screenshots, terminal, and live Voice demo as intentional embedded surfaces inside either page theme.
- Static product imagery is sourced from the canonical deterministic scenes in `assets/screenshots/` via `docs/media/screenshots.json`. Keep `public/product/` synchronized with `npm run assets:sync`; all website checks must retain the non-mutating `assets:check` drift gate.
- Responsive WebP screenshot derivatives are generated from those canonical deployment copies by `scripts/responsive-assets.mjs`. Keep the committed variants deterministic and use `srcset`/`sizes`; never optimize the PNG source in place.
- Device Control is the sideload-only visual exception: represent it with a restrained live recreation grounded in `BridgeScreen.kt` and its current resource strings until a canonical non-Play screenshot exists. Keep the Sideload app + paired Relay plugin requirement explicit, and preserve the real safety vocabulary (blocked apps, destructive-action confirmation, idle auto-disable, and Activity Log).
- The canonical production origin is `https://hermes-relay.dev`; `PUBLIC_SITE_URL` is only an optional preview/staging override. Keep canonical, Open Graph, robots, sitemap, and structured-data URLs on that shared Astro `site` value, validate overrides as HTTPS origins, and do not add analytics or third-party tracking without an explicit product decision.
