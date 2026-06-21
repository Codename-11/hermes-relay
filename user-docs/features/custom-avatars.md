# Custom Avatars

The agent has a face — the animated embodiment you see on the empty chat screen, in full-screen clean/ambient mode, on the voice overlay, during onboarding, and on the startup splash. You can change it. Pick the built-in **Sphere** and recolor it, or drop in your own animated **pet** as a complete replacement.

Everything lives under **Settings → Appearance → Agent avatar**, and adding your own is pure data — frames and JSON, never code.

## The two-level model

Appearance is two stacked choices:

1. **Agent avatar** — *what* the embodiment is. Either the built-in **Sphere** (an animated ASCII orb) or one of your **pets** (an animated bitmap companion).
2. **Sphere skin** — *how the Sphere looks*. A second row of chips that recolors the orb. It only appears when the Sphere avatar is selected; pets carry no skins, so the row hides when a pet is chosen.

So the flow is: choose an avatar, and if that avatar is the Sphere, optionally choose a skin.

## Choosing an avatar

Open **Settings → Appearance → Agent avatar**. The Sphere is always the first chip; any valid pets you've installed appear beside it. Selecting a chip switches every surface — chat, clean mode, voice, onboarding, splash — at once.

Each chip shows a small summary of the **live signals** the avatar reacts to, so you know what it does before you pick it:

| Badge | Meaning |
|-------|---------|
| **Voice** | Reacts to mic/speech amplitude — swells or bounces while listening and speaking |
| **Tools** | Pulses on tool-call activity during a turn |
| **Activity** | General turn activity drives motion (turbulence, ripple, flow) |

With no pets installed and the Sphere selected, the app behaves exactly as it always has.

## Sphere skins

When the Sphere is your avatar, a **Sphere skin** row appears. A skin changes the orb's **colors** (and, optionally, fine motion parameters) — the underlying orb animation stays the same.

Built-in skins include:

| Skin | Look |
|------|------|
| **Adaptive** | Recolors itself to match your active app theme |
| **Classic** | The original green-violet orb |
| **Aurora** | Teal + violet |
| **Solar** | Amber + ember |
| **Mono** | Grayscale, calmer (no tool pulses) |

### Add your own skin

A custom skin is a single `*.json` file describing colors plus optional per-state overrides and motion params. Drop it into the app's external `spheres/` folder:

```bash
# sideload flavor shown
adb push my-orb.json /sdcard/Android/data/com.axiomlabs.hermesrelay.sideload/files/spheres/
```

On the **googlePlay** flavor, drop the `.sideload` suffix from the package:
`/sdcard/Android/data/com.axiomlabs.hermesrelay/files/spheres/`.

The app creates the folder on first launch, so it's there waiting after your first run. A one-line `defaults` block (two colors) is already a complete, valid skin; everything else is optional polish.

::: tip Authoring reference
For the full format — every state key, the optional motion `params`, color rules, and the reactivity flags — see the Sphere skin spec: [`docs/sphere-spec.md`](https://github.com/Codename-11/hermes-relay/blob/main/docs/sphere-spec.md).
:::

## Pets — bring your own avatar

A **pet** is an animated bitmap companion that replaces the Sphere entirely. It's a **self-contained pack**: a small `pet.json` manifest plus the images it plays back — either PNG frames or a single sprite sheet. Pets are **pure data** — frames and numbers, never code — and the renderer is dependency-free, so a pet is really just a folder of PNGs the app animates.

A pet can have separate clips for the agent's **idle**, **thinking**, and **speaking** states, but the only hard requirement is `idle`. A minimal pet is one idle image and a one-line manifest — a static picture avatar.

### Add a pet

Each pet is its own subdirectory under the app's external `pets/` folder, holding `pet.json` beside its image files. Push the whole pack directory:

```bash
# sideload flavor shown
adb push blob/ /sdcard/Android/data/com.axiomlabs.hermesrelay.sideload/files/pets/
```

On the **googlePlay** flavor, drop the `.sideload` suffix:
`/sdcard/Android/data/com.axiomlabs.hermesrelay/files/pets/`.

Neither path needs a runtime permission — app-scoped external storage is reachable by `adb push` (or a file manager) directly. Reopen **Settings → Appearance → Agent avatar** and your pet appears as a chip alongside the Sphere.

::: tip Easiest: import in the app
You don't need `adb`. In **Settings → Appearance → Agent avatar**, tap **Add a pet** and pick a file — it accepts a pet pack (`.zip`) **or a single image** (PNG/JPG), which becomes a one-frame **static avatar** with no manifest authoring. Imported pets appear immediately; remove them from the **Installed pets** list.
:::

::: tip Authoring reference
For the manifest format — clips, sprite sheets, frame-rate limits, reactivity flags, and image best practices — see the Pet spec: [`docs/pet-spec.md`](https://github.com/Codename-11/hermes-relay/blob/main/docs/pet-spec.md).
:::

### Generate a pet with AI

You don't have to draw anything. Because a pet is just a few **sprite sheets** plus a manifest, an AI image model can make the art for you — starting from a picture of **your own avatar** (a selfie, a character drawing, a brand mascot) — and you assemble the pack in minutes.

A sprite sheet is a single image holding every frame of one animation in a grid, read left-to-right, top-to-bottom. That's the easiest thing to ask an image model for (it's *one* picture), and it's exactly what the manifest's `sheet` clip form expects. You make **one sheet per state**.

::: tip Grab the whole kit
The prompt, the per-state motion list, and the manifest below are also available as a single plain-text file you can copy in one go: **[pet-prompt-kit.txt](/pet-prompt-kit.txt)**.
:::

::: tip Let an AI agent build the whole pack
If you use an AI coding agent **with image generation** (e.g. Codex or Claude Code), you can hand it the whole job instead of running these steps yourself: give it this page (or [`pet-prompt-kit.txt`](/pet-prompt-kit.txt)) plus a reference image of your avatar, and ask it to generate the sheets, write `pet.json`, and drop the folder into `pets/`. It can self-check before installing — a pack is structurally valid when it has an `idle` clip, every referenced image file exists, each clip is a `frames` list or a `sheet` with positive `frameWidth`/`frameHeight`/`frameCount`, and no path escapes the pack folder; it is visually ready when every sheet decodes as RGBA, keeps visible pixels inside the safe margin, and keeps the head/shoulder anchor fixed across cells. *(A pure code agent can write the manifest but can't draw the frames — it needs image-generation access.)*
:::

#### 1. Lock your character

Two moves turn *any* avatar into a consistent, reactive pet:

- **Use a reference image.** Attach a picture of your avatar to the image model and tell it to keep the face/outfit identical across the sheet. This is the single biggest lever for consistency — without it, the character drifts from frame to frame.
- **Pick one signature accent.** Choose a single glow or outline color that **brightens when the agent is busy** (a soft aura works well). This is what makes the pet look *reactive* without redrawing the character — only the accent changes between states. Match it to your phone theme if you like.

#### 2. Generate one sheet per state

Paste this template into any capable image model. Fill in the `{braces}` — keep the **character**, **style**, and **accent** lines *identical* every time, and change only the **animate** line:

```text
A sprite sheet of {YOUR AVATAR — e.g. "the character in the attached reference image; keep the face, hair, and outfit identical"},
drawn as {STYLE — e.g. "a clean flat-shaded illustration with crisp outlines, half-body, facing forward"}.
Give it a signature {ACCENT — e.g. "soft teal-to-violet aura"} that reads as "alive" and grows brighter when it is more active.
Lay out 16 frames in a 4x4 grid, evenly spaced, with each cell exactly 256x256 px.
Treat each cell as the final transparent canvas, not as the full art box: keep all visible pixels inside a centered 200-208 px safe art box, leaving at least 24 px of empty margin on every side (28 px preferred). Hair, hands, props, sparkles, glow, aura, and shadows all count as visible pixels and must stay inside that safe box.
Animate across the frames: {MOTION FOR THIS STATE — see the table below}.
Keep the character's identity and anchor identical in every cell — same face, colors, proportions, size, and head/shoulder position (it never drifts up or down or grows or shrinks, and always stays fully inside its cell). But the 16 cells are an ANIMATION, not copies: the moving parts (eyes, mouth, hands, hair, accent) must visibly change from cell to cell, sweeping smoothly through the full motion across all 16 frames (a blink goes open→half→shut→half→open; breathing clearly rises then settles). Adjacent frames must be noticeably different in the animated areas — never repeat a pose.
Use a flat #00ff00 chroma-key background in every cell so it can be removed cleanly after generation. No scenic background, no text, no labels, no grid lines, no frame borders.
```

Drop one of these motion lines into `{MOTION FOR THIS STATE}`, remove the chroma-key background, save the result as a PNG with alpha, and put it under the matching file name. The last column is what that clip exercises in the app:

| State | Save as | `animate:` line to use | Drives |
|-------|---------|------------------------|--------|
| **Idle** *(required)* | `idle.png` | a calm breathing loop — tiny chest/coat expansion and a single blink; the head and shoulder anchor stays locked; the accent pulses softly | base loop |
| **Thinking** | `thinking.png` | eyes glancing upward in thought, a tiny glowing spark contained near the temple; a cooler, dimmer accent | thinking clip |
| **Working** *(tool use)* | `working.png` | a focused, busy pose — turning a small glowing gear or tool held close to the body inside the safe box; the accent at its brightest and steady | **Tools** badge + overlay |
| **Writing** *(output)* | `writing.png` | a quick scribbling or typing motion, eyes down, short contained light strokes close to the hand | streaming clip + intensity speedup |
| **Speaking** | `speaking.png` | the mouth opening and closing as if talking, no head bob; contained accent rings ripple near the face in time | speaking clip + voice bounce |
| **Listening** | `listening.png` | attentive eyes, a hand near the ear without leaning the body; the accent drawing inward into a soft contained ring | listening clip |
| **Error** | `error.png` | a small startled "oops" — wide eyes and a tiny sweat-drop, the accent flickering briefly to a dim red | error clip |
| **Greet** *(one-shot)* | `greet.png` | a small friendly wave that stays inside the safe box, the head and shoulders locked, the accent blooming gently | greet reaction (on appear) |
| **Done** *(one-shot)* | `done.png` | a happy celebration — brighter smile, small contained sparkles near the shoulders or hands, the accent blooming without reaching the cell edge | done reaction (turn finish) |

Only `idle` is required — start there for a one-clip pet, then add as many states as you like. For what each state means and how unspecified ones fall back, see the [Pet spec](https://github.com/Codename-11/hermes-relay/blob/main/docs/pet-spec.md).

#### 3. Wire the sheets into a manifest

Each generated sheet becomes one clip. This manifest wires up **all nine** — save it as `pet.json` beside the PNGs in a folder (e.g. `my-pet/`). For a **4×4 grid of 256 px cells** (a 1024×1024 image holding 16 frames), each clip looks like this:

```json
{
  "$schema": "https://codename-11.github.io/hermes-relay/pet.schema.json",
  "schemaVersion": 1,
  "id": "my-pet",
  "label": "My Pet",
  "reactive": { "voice": true, "intensity": true },
  "states": {
    "idle":      { "sheet": "idle.png",      "frameWidth": 256, "frameHeight": 256, "frameCount": 16, "fps": 8 },
    "thinking":  { "sheet": "thinking.png",  "frameWidth": 256, "frameHeight": 256, "frameCount": 16, "fps": 10 },
    "working":   { "sheet": "working.png",   "frameWidth": 256, "frameHeight": 256, "frameCount": 16, "fps": 10 },
    "writing":   { "sheet": "writing.png",   "frameWidth": 256, "frameHeight": 256, "frameCount": 16, "fps": 12 },
    "speaking":  { "sheet": "speaking.png",  "frameWidth": 256, "frameHeight": 256, "frameCount": 16, "fps": 12 },
    "listening": { "sheet": "listening.png", "frameWidth": 256, "frameHeight": 256, "frameCount": 16, "fps": 8 },
    "error":     { "sheet": "error.png",     "frameWidth": 256, "frameHeight": 256, "frameCount": 16, "fps": 10 },
    "greet":     { "sheet": "greet.png",     "frameWidth": 256, "frameHeight": 256, "frameCount": 16, "fps": 12 },
    "done":      { "sheet": "done.png",      "frameWidth": 256, "frameHeight": 256, "frameCount": 16, "fps": 12 }
  }
}
```

`frameWidth`/`frameHeight` are the size of **one cell**, and `frameCount` is how many cells the model drew (rows × columns). Any rectangular grid works — `4×4` (16 frames), `3×3` (9), even a `1×16` strip — as long as `frameCount` matches and the sheet measures `cols×frameWidth` by `rows×frameHeight`. Shipping the `working` clip is what lights the **Tools** badge, and `intensity: true` lights **Activity**; together with the default voice bounce this pet advertises **Voice · Tools · Activity** — every reactive signal lit. Then push the folder exactly as shown above and reopen Appearance.

::: tip Validate as you author
The example above starts with a `$schema` line pointing at the published [pet schema](https://codename-11.github.io/hermes-relay/pet.schema.json). Keep it and editors like VS Code will autocomplete the fields and flag mistakes — a missing `idle`, a bad frame count, a typo'd state key — before you ever push. The app ignores the `$schema` key, and an AI agent can lint its output against the same file.
:::

::: tip Safe box: leave room inside every cell
A 256 px cell is the **canvas**, not the size the character should fill. After background removal, all visible alpha should sit inside the centered 200-208 px safe art box, leaving at least 24 px of transparent padding on every side. If hair, hands, glow, or props touch the outer margin, scale the art down or make the motion smaller before installing.
:::

::: tip Smoothness: frames vs. fps
Sprite animation is frame-stepped, so smoothness comes from **frame count**, not speed — which is why this kit defaults to a **4×4 grid (16 frames)**. If a 16-frame sheet drifts, keep the 4×4 grid and fix registration first: generate states one at a time, keep the same reference and seed, or use one stable state-specific frame as a locked base and animate only blink, mouth, glow, sparkles, or a contained prop. Only drop to **3×3** or **2×2** when frame count is negotiable. And **match fps to frame count** so the loop length stays sane: 16 frames at `fps: 8` is a calm ~2 s cycle, while *4* frames at `fps: 8` is a frantic half-second. Keep calm states (`idle`/`listening`) a little slower than active ones (`speaking`/`done`).
:::

::: tip Resolution: size for the biggest surface
The avatar is **contain-fit** into whatever space it occupies, and *one* set of frames serves every surface — so author for the **largest** place it appears (the full-screen chat background) and small placements (the voice overlay) just downscale and stay sharp. A 128 px cell upscaled to fill the chat background looks pixelated; **256 px cells** (a 1024×1024 sheet for a 4×4 grid) are a good default. Because a sprite sheet decodes as **one** bitmap, you can even go to 512 px cells (2048×2048) for extra crispness at a modest memory cost — that ceiling is per *sheet*, not per frame, so it's far cheaper than the same frames as separate files. You can also fine-tune the running speed live in **Settings → Appearance** without re-authoring.
:::

::: warning Three things AI image models get wrong
- **Transparency.** Many models bake in a solid or checkerboard background even when you ask for "transparent." Ask for a flat `#00ff00` chroma-key background, remove it after generation, and save as a PNG **with alpha** before adding it to the pack — otherwise the pet draws inside an opaque box.
- **Consistency, registration, and clipping.** Keeping the *same* character across frames is the hard part — and keeping it **registered** (same position, scale, and framing in every cell) is harder still. If the character drifts up/down or changes size between cells, it visibly floats and jumps as it plays, with the edge of the next frame bleeding in. If visible pixels enter the outer 24 px margin, hair, hands, props, or glow can clip when the sheet is sliced. Reuse the reference image, keep the character/style/accent lines byte-identical, lock a fixed **seed**, stress "lock the head and shoulders in place," and validate that every cell keeps the same anchor and safe-box padding. If a generated **4×4** still drifts, preserve the 16 cells with a locked-base hybrid pass before reducing frame count.
- **No motion (over-locked).** The opposite of drift: lean too hard on "identical / locked" and the model copies one pose across all 16 cells, so the pet looks frozen even though it cycles through them. The cells are an **animation, not copies** — the moving parts (eyes, mouth, hands, hair, accent) must *visibly* change cell to cell, sweeping the full arc (a blink open→shut→open, a breath rise→settle). If a contact sheet reads as 16 near-identical tiles, regenerate demanding clearer per-frame change.
:::

::: tip Fastest first pass
Want to confirm the pipeline today before perfecting nine animated sheets? Generate **one** 3×3 image — "nine cells, the same character, one per state: idle, thinking, working, writing, speaking, listening, error, greeting, celebrating" — slice it into nine single-frame PNGs, and reference each as a one-frame `frames` clip (`"frames": ["idle.png"]`) instead of a `sheet`. No in-state motion, but every state is visibly distinct — a complete proof of the pipeline from a single generation.
:::

For a smooth, perfectly-stable animated character, hand-drawn pixel art still wins — but for a charming "good enough" companion, a few AI sprite sheets get you there without ever opening a drawing app.

## Accessibility & reduced motion

If you disable animations — either the app's own setting or your phone's OS-level **reduce motion** — or if a screen reader is active, the avatar renders as a **still frame** instead of looping. The Sphere pins to a single frame; a pet freezes on its current frame and its voice bounce is suppressed.

When authoring a pet, make the first `idle` frame a good, legible still — that's what people who prefer reduced motion will see.

## Troubleshooting

**My pet (or skin) doesn't show up in the picker.** Invalid or incomplete packs are simply skipped — they never appear, and one bad pack never breaks the picker for the rest. Check logcat for the skip reason: tag `PetLoader` for pets, `SphereSkinLoader` for sphere skins.

**I pushed files but nothing changed.** Pets and skins are scanned at app start. Fully restart the app after pushing files, then reopen Appearance.

**My pet appears but renders blank.** A file that exists but isn't a decodable image (a corrupt or mislabeled `.png`) passes the manifest check but can't be drawn. Confirm your images actually open before shipping a pack.

**I deleted a pet that was selected.** No problem — if the selected pet is removed, the avatar falls back to the **Sphere** automatically.
