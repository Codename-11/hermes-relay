# Design — Hermes-Relay Release Video Pipeline

## Visual systems

The pipeline can swap between styles per release. Keep the **release copy** product-focused; style labels are production notes, not public on-screen wording.

### Dark Hermes-Relay cockpit

- background: `#0D0F1A`
- cards: `#161830` / `#1C1E38`
- text: `#F0EDF8`
- purple: `#6B35E8` / `#9B6BF0`
- teal: `#2DD4BF`
- success green: `#4ADE80`

### Warm Technical Atlas (optional)

Use when the preview should feel calmer, more editorial, and more systems-diagram than dark HUD.

- off-white / ivory / paper canvas; not pure white, not dark HUD
- thin monoline vector geometry, construction arcs, precise curves/ellipses
- dimensional orbit/globe/reference arcs to imply 3D relay space
- sparse mono labels and tiny status chips as annotations, not dashboard clutter
- line draw-ons, packet/data motion along paths, subtle orbital drift
- high negative space; labels outside collision zones
- avoid neon grids, scanlines, radar sweeps, reticles, cyberpunk glow soup, and chunky UI borders

## Motion pattern

1. Signal/logo burst lands immediately with the first audio impact.
2. Release thesis card.
3. Product proof cards: route/status chips + fresh target-release screenshots when available; otherwise abstract info cards.
4. Power-route payoff.
5. Closing card with repo and `@axiom_labs_dev`.

## Audio

Default lane: dubstep / technical bass. Half-time rhythm, low sub/bass bed, wobble sections, kick/snare weight, dark risers/transition impacts. No cheerful product-pop and no sparse bell/ding stingers.

## Animation taste

- Prefer clean highlight sweeps, glow passes, scan ticks, and line pulses.
- Avoid bouncy scale pops on cards/chips; this should feel technical and controlled, not startup-pop.

## QA requirements

- `npm run check` has zero errors.
- Rendered video has video and audio streams.
- Duration matches selected audio or explicit manifest duration.
- Audio loudness is sane.
- Contact sheet has no blank/broken frames.
- Public handle casing remains lowercase.
- Scene review includes a metadata-based contact sheet (`contact-sheet-scenes.png`) with one card per scene, `start → stop`, and duration.
- For detailed review, generate `storyboard.html` alongside the MP4 with one full card per scene, midpoint frame, timing, on-screen copy, and review notes.

## Copy policy

- Re-ground every copy pass in the live release source (`CHANGELOG.md` / `RELEASE_NOTES.md` / `whats_new.txt`, or `--release-json` for previews) before drafting.
- Map each on-screen line to a real Added/Changed/Fixed item; cut or fix any line that cannot be traced.
- Use real feature names and verifiable claims, but write headlines/sub lines in plain language for a broad audience; move jargon (transport tiers, provider names, "cold start", "reachability", product names) into diagram chips/annotations, not the headline.
- Keep correct positioning: the simple setup stays simple, power tools stay optional; never imply the optional plugin is required for baseline chat/manage/voice.
- Keep design-style labels out of on-screen public copy.

## Layout QA

- After each render, extract one midpoint frame per scene and vision-check diagram/annotation scenes for collisions (labels over line art, labels over labels, text escaping the frame, crowding).
- For free-positioned mini-board annotations, keep the diagram centered/compact and push annotations to the four corners with a bounded max-width.
- A passing HyperFrames `inspect` does not catch label-over-line-art overlap inside a free-positioned board — the per-scene vision pass is required.
