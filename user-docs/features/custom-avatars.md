# Custom avatars and pets

Hermes Relay has three separate visual concepts. Changing one does not replace
the others:

1. **Profile identity** — the profile image or letter beside the agent name and
   the first assistant message in a group.
2. **Background visualization** — Off or the animated **Sphere**, with an
   optional Sphere skin.
3. **Floating pet** — None or an installed bitmap companion that stays with you
   across the app.

Profile identity is managed from the agent sheet. Sphere and pet controls live
under **Settings → Appearance**.

## Profile badge image

The small face shown beside the active agent name is configured separately from
the full animated avatar. Open the agent sheet from Chat and use **Agent icon**:

- **Set image** chooses an image from your phone.
- **Import from agent host** copies a conventional image from the active Hermes
  profile on a paired Relay host. Put `avatar.png`, `avatar.jpg`, `profile.png`,
  or `profile.jpg` directly in `~/.hermes/` for the root Server default, or in
  `~/.hermes/profiles/<name>/` for a named profile. If Hermes' `active_profile`
  marker selects a named profile as Server default, use that named profile's
  directory. JPEG, PNG, WebP, and GIF are supported, including `.jpeg`.

The imported copy stays on the phone and is scoped to that Connection and
profile. Changing or removing the host file later does not silently change the
phone icon; tap import again to refresh it.

## Choosing a floating pet

Open **Settings → Appearance → Floating pet**. Choose **None** or an installed
pet. This does not change the profile badge or Sphere.

Each pet shows a summary of the **live signals** its pack supports:

| Badge | Meaning |
|-------|---------|
| **Voice** | Pack/renderer supports mic or speech amplitude on hosts that provide it; Android's app-level companion is hidden during full-screen voice |
| **Tools** | Uses a distinct working clip while Hermes runs a tool |
| **Activity** | Speeds up supported activity animation as a turn becomes busier |

With no pet selected, no floating companion is shown. The Sphere setting remains
independent.

For Petdex pets, tap **Browse Petdex**, choose **Install**, and the app converts,
selects, and maps the pet automatically. You do not edit `pet.json`, rename
Petdex rows, or create missing animations. The selected installed pet shows an
interactive **Idle / Walk left / Walk right / Jump / Fall / Held / Wave /
Working / Review / Waiting / Error** preview. Each choice names the exact clip
used and says whether it is direct, mirrored, a fallback, or a mirrored fallback.
An opposite native directional row is **Mirrored**; the legacy in-place
`running` row used for travel is **Mirrored fallback**; idle art is a plain
**Fallback** when no travel row exists.

## Sphere skins

When **Background visualization** is set to Sphere, the **Sphere skin** row changes the orb's colors (and, optionally, fine motion parameters). Pet selection does not affect it.

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

## Pets — bring your own companion

A **pet** is a floating bitmap companion, independent of the Sphere and profile
identity. It can be animated or still, and it is a self-contained pack: a small
`pet.json` manifest plus PNG/WebP frames or a sprite sheet. Pets are pure data—
frames and numbers, never executable code.

A pet can have separate clips for idle, thinking, writing, tool work, errors,
reactions, and optional left/right locomotion. Only `idle` is required; one idle
image and a one-line manifest make a complete static pet.

### Add a pet

Each pet is its own subdirectory under the app's external `pets/` folder, holding `pet.json` beside its image files. Push the whole pack directory:

```bash
# sideload flavor shown
adb push blob/ /sdcard/Android/data/com.axiomlabs.hermesrelay.sideload/files/pets/
```

On the **googlePlay** flavor, drop the `.sideload` suffix:
`/sdcard/Android/data/com.axiomlabs.hermesrelay/files/pets/`.

Neither path needs a runtime permission—app-scoped external storage is reachable
by `adb push` or a file manager. Reopen **Settings → Appearance → Floating pet**
and the pack appears as a companion choice.

::: tip Easiest: import in the app
You don't need `adb`. In **Settings → Appearance → Floating pet**, tap **Add a pet** and pick a Relay pet pack (`.zip`) or a single image, which becomes a one-frame static companion. You can also tap **Browse Petdex** to search the public gallery, review creator/source attribution, and explicitly install a supported pet. Petdex atlases download only after you tap Install and remain available offline afterward. Imported and Petdex pets appear in the same installed list.
:::

::: tip Authoring reference
For the manifest format — clips, sprite sheets, frame-rate limits, reactivity flags, and image best practices — see the Pet spec: [`docs/pet-spec.md`](https://github.com/Codename-11/hermes-relay/blob/main/docs/pet-spec.md).
:::

### Move or let the pet roam

The companion is app-level, so it keeps the same home while you move between
normal Hermes Relay screens.

- Long hold the pet, drag it, and release. It snaps to the nearest start/end
  edge or valid roaming surface with a visible falling/landing animation and
  remembers a proportional vertical position across rotation, resizing,
  relaunches, and RTL layouts. Dragging lifts into the held animation, stays
  clear of registered controls, and does not turn roaming off.
- Tap the pet to make it wave and open its menu.
- Turn on **Walk around the interface** to let it roam while Hermes is idle. This
  is off by default. Supported ledges are the measured top edges of Chat's
  composer and newest visible settled user/assistant bubble, Terminal's extra-keys
  bar, and the bottom status strip on Settings and About. Hermes Relay uses only
  these deliberately supported surfaces—it does not scan every button or text
  field—and the overlay adds no blank strip or lost text space.
- **Pet size** in Appearance adjusts the companion from 60–120%, with the former
  maximum size now shown as the 100% default. Existing saved choices keep their
  physical size. Art, touch target, and routing footprint change together, so a
  larger pet skips a tight ledge instead of overlapping a control or message.
- At the settled Chat bottom, the pet rests in blank space beside the latest
  bubble when that fits, otherwise on its safe top edge, then at the outer
  composer corner. Selected Settings ledges can register dynamically; ordinary
  cards, labels, fields, and buttons never become terrain automatically.
- When Chat's scroll-to-bottom button or Terminal's jump-to-latest pill is
  visible, the pet shortens or leaves that part of the ledge clear.
- After a plain response settles, the pet can walk along the composer to the
  clear outer side of the message, jump beside it, walk across a raised rail
  above the bubble, wave, return to the same edge, and drop to the composer. Its
  full footprint stays above the text. Narrow or blocked bubbles, cards,
  attachments, tool rows, and phone/voice actions are skipped.
- Directional clips match left/right travel; a brief turn pause, jump-to-fall
  apex, responsive shadow, landing squash, and walk-cycle-matched speed make the
  movement read as grounded. Between response visits, idle variety rotates
  through hops, waves, and rests.
- Scrolling Chat, Settings, or About pauses the pet exactly where it is, at full
  opacity. It resumes by replanning from that live point—no snap or teleport to
  the saved edge. Settings/About dialogs suspend it. On a screen without a
  supported ledge, it stays docked.
- Use the pet menu to pause roaming, reset position, open Appearance, or hide it.
  TalkBack offers move-to-start/end, move-up/down, and reset actions so dragging
  is never required.

The pet's behavior has a predictable priority: your tap/drag/drop first, then
Hermes activity, a newly completed response visit, normal roaming, and finally
idle reactions. Under **Pet temperament**, choose how often an otherwise-idle
pet acts:

| Temperament | Response visit | Roam interval | Idle reaction |
|-------------|----------------|---------------|---------------|
| **Calm** | 2.5 seconds | 12 seconds | 28 seconds |
| **Balanced** (default) | 1.5 seconds | 8 seconds | 18 seconds |
| **Playful** | 0.75 seconds | 5 seconds | 10 seconds |

Temperament never bypasses scrolling, dialogs, active Hermes work, disabled
animations, reduced motion, or TalkBack touch exploration.

Agent state and locomotion are separate. `running`/`working` means Hermes is
performing work in place; `walking-left`/`walking-right` or
`running-left`/`running-right` means the pet is physically crossing the safe
strip. Directional motion is used only while Hermes is idle, so thinking,
writing, tool work, errors, and reactions always take priority. Petdex's current
directional rows are preserved automatically; legacy Petdex pets without those
rows still move but show their idle art during travel.

### Generate a pet with AI

You don't have to draw anything. Because a pet is just **PNG frames or sprite sheets** plus a manifest, an AI image model can make the art for you — starting from a picture of **your own avatar** (a selfie, a character drawing, a brand mascot) — and you assemble the pack in minutes.

There are two good paths. The easiest is a **static per-state pack**: one expressive still PNG per state, referenced as `"frames": ["idle.png"]`. It has no in-state motion, but it avoids frame-registration drift and every state can still look distinct. The animated path is a **sprite sheet**: one image holding every frame of one animation in a grid, read left-to-right, top-to-bottom, referenced as a `sheet` clip. Use sprite sheets when you want motion.

::: tip Grab the whole kit
The prompt, the per-state motion list, and the manifest below are also available as a single plain-text file you can copy in one go: **[pet-prompt-kit.txt](/pet-prompt-kit.txt)**.
:::

::: tip Let an AI agent build the whole pack
If you use an AI coding agent **with image generation** (e.g. Codex or Claude Code), you can hand it the whole job instead of running these steps yourself: give it this page (or [`pet-prompt-kit.txt`](/pet-prompt-kit.txt)) plus a reference image of your avatar, and ask it to generate stills or sheets, write `pet.json`, and drop the folder into `pets/`. It can self-check before installing — a pack is structurally valid when it has an `idle` clip, every referenced image file exists, each clip is a `frames` list or a `sheet` with positive `frameWidth`/`frameHeight`/`frameCount`, and no path escapes the pack folder; it is visually ready when every PNG decodes as RGBA, keeps visible pixels inside the safe margin, and, for sheets, keeps the head/shoulder anchor fixed across cells. *(A pure code agent can write the manifest but can't draw the frames — it needs image-generation access.)*
:::

#### 1. Lock your character

Two moves turn *any* avatar into a consistent, reactive pet:

- **Use a reference image.** Attach a picture of your avatar to the image model and tell it to keep the face/outfit identical across every still or sheet. This is the single biggest lever for consistency — without it, the character drifts between states or frames.
- **Pick one signature accent.** Choose a single glow or outline color that **brightens when the agent is busy** (a soft aura works well). This is what makes the pet look *reactive* without redrawing the character — only the accent changes between states. Match it to your phone theme if you like.

#### 2A. Easier: generate expressive stills per state

Use this when you want maximum character fidelity with the least fuss. A still pack has no animation loop to communicate the state, so ask for **clearer expression, pose, hands, and accent cues** while keeping the character perfectly consistent.

```text
Create one square portrait PNG of {YOUR AVATAR — e.g. "the character in the attached reference image; keep the face, hair, outfit, colors, and proportions identical"},
drawn as {STYLE — e.g. "a clean flat-shaded illustration with crisp outlines, half-body, facing forward"}.
This is a single still frame for the agent state: {STATE}.
Make the state visually legible through expression, eye direction, hand pose, contained prop/accent placement, and accent brightness: {STILL CUE — see the table below}.
Keep the character's identity and anchor identical to the reference and to the other states — same face, colors, proportions, size, outfit, canvas crop, and head/shoulder position. Do not change the body scale or framing between states.
Use a square final canvas. For the sharpest full-width mobile rendering, generate native 2048x2048 art; 1024x1024 is a lighter default. Do not generate a tiny 256x256 still and upscale it afterward.
Keep all visible pixels inside a centered safe art box with transparent/chroma margin on every side: roughly 75-85% of the canvas for the character/effects, leaving at least 10-12% empty margin (about 192-256 px on a 2048 canvas, or 96-128 px on a 1024 canvas). Hair, hands, props, sparkles, glow, aura, and shadows all count as visible pixels and must stay inside that safe box.
Use a flat #00ff00 chroma-key background so it can be removed cleanly after generation. No scenic background, no text, no labels, no frame borders.
```

Use these still cues, remove the chroma-key background, and save each as a PNG with alpha:

| State | Save as | Still cue |
|-------|---------|-----------|
| **Idle** *(required)* | `idle.png` | calm neutral expression, relaxed shoulders, soft low accent |
| **Thinking** | `thinking.png` | thoughtful eyes, hand near chin or temple, small contained spark/accent |
| **Working** *(tool use)* | `working.png` | focused expression, small contained gear/tool/light held close to the body, brightest accent |
| **Writing** *(output)* | `writing.png` | eyes down, hand poised as if writing/typing, short contained light stroke near the hand |
| **Speaking** | `speaking.png` | mouth visibly speaking, direct engaged expression, subtle contained accent near the face |
| **Listening** | `listening.png` | attentive expression, hand near ear, accent pulled inward like a listening ring |
| **Error** | `error.png` | concerned or startled expression, small contained warning accent, no huge symbols |
| **Greet** *(one-shot)* | `greet.png` | friendly raised-hand wave, warm smile, contained accent bloom |
| **Done** *(one-shot)* | `done.png` | celebratory smile, open hands or small contained sparkle/accent near the shoulders |

#### 2B. Animated: generate one sheet per state

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

#### 3. Wire the images into a manifest

For a still pack, each generated PNG becomes a one-frame clip. This manifest wires up **all nine** — save it as `pet.json` beside the PNGs in a folder (e.g. `my-pet/`):

```json
{
  "$schema": "https://hermes-relay.dev/docs/pet.schema.json",
  "schemaVersion": 1,
  "id": "my-pet",
  "label": "My Pet",
  "reactive": { "voice": true, "intensity": true },
  "states": {
    "idle":      { "frames": ["idle.png"],      "fps": 1 },
    "thinking":  { "frames": ["thinking.png"],  "fps": 1 },
    "working":   { "frames": ["working.png"],   "fps": 1 },
    "writing":   { "frames": ["writing.png"],   "fps": 1 },
    "speaking":  { "frames": ["speaking.png"],  "fps": 1 },
    "listening": { "frames": ["listening.png"], "fps": 1 },
    "error":     { "frames": ["error.png"],     "fps": 1 },
    "greet":     { "frames": ["greet.png"],     "fps": 1 },
    "done":      { "frames": ["done.png"],      "fps": 1 }
  }
}
```

The sustained states (`idle`, `thinking`, `working`, `writing`, `speaking`, `listening`, `error`) stay visible for as long as the app is actually in that state. The still `greet` and `done` reactions are event clips, so the app holds one-frame reactions for about 1.8 seconds before returning to the current sustained state.

For an animated pack, each generated sheet becomes one clip. For a **4×4 grid of 256 px cells** (a 1024×1024 image holding 16 frames), each clip looks like this:

```json
{
  "$schema": "https://hermes-relay.dev/docs/pet.schema.json",
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

For sheets, `frameWidth`/`frameHeight` are the size of **one cell**, and `frameCount` is how many cells the model drew (rows × columns). Any rectangular grid works — `4×4` (16 frames), `3×3` (9), even a `1×16` strip — as long as `frameCount` matches and the sheet measures `cols×frameWidth` by `rows×frameHeight`. In either manifest style, shipping the `working` clip is what lights the **Tools** badge, and `intensity: true` lights **Activity**; together with the default voice bounce this pet advertises **Voice · Tools · Activity** — every reactive signal lit.

#### 4. Package it for import

The app imports a `.zip` pet pack. The most reliable archive shape is one top-level folder containing `pet.json` and only the referenced images:

```text
my-pet.zip
└─ my-pet/
   ├─ pet.json
   ├─ idle.png
   ├─ thinking.png
   └─ ...
```

If you are working from this repository, package a folder with the built-in helper:

```bash
node scripts/package-pet.mjs path/to/my-pet --out my-pet.zip
```

The helper validates `pet.json`, checks that every referenced image exists, excludes `qa/` and other unreferenced files, and writes the app-compatible zip.

<PetPackBuilder />

::: tip Validate as you author
The example above starts with a `$schema` line pointing at the published [pet schema](https://hermes-relay.dev/docs/pet.schema.json). Keep it and editors like VS Code will autocomplete the fields and flag mistakes — a missing `idle`, a bad frame count, a typo'd state key — before you ever push. The app ignores the `$schema` key, and an AI agent can lint its output against the same file.
:::

::: tip Safe box: leave room inside every cell
A 256 px cell is the **canvas**, not the size the character should fill. After background removal, all visible alpha should sit inside the centered 200-208 px safe art box, leaving at least 24 px of transparent padding on every side. If hair, hands, glow, or props touch the outer margin, scale the art down or make the motion smaller before installing.
:::

::: tip Smoothness: frames vs. fps
Sprite animation is frame-stepped, so smoothness comes from **frame count**, not speed — which is why this kit defaults to a **4×4 grid (16 frames)**. If a 16-frame sheet drifts, keep the 4×4 grid and fix registration first: generate states one at a time, keep the same reference and seed, or use one stable state-specific frame as a locked base and animate only blink, mouth, glow, sparkles, or a contained prop. Only drop to **3×3** or **2×2** when frame count is negotiable. And **match fps to frame count** so the loop length stays sane: 16 frames at `fps: 8` is a calm ~2 s cycle, while *4* frames at `fps: 8` is a frantic half-second. Keep calm states (`idle`/`listening`) a little slower than active ones (`speaking`/`done`).
:::

::: tip Resolution: size for the companion target
The pet art uses a 60 dp base size (50 dp in compact layouts), followed by the 60–120% Appearance scale, so huge full-screen assets waste memory. A 1024×1024 still is already generous; downsize finished assets when practical. For animated sheets, 256 px cells are a good default. You can also fine-tune playback speed in **Settings → Appearance** without re-authoring.
:::

::: warning Three things AI image models get wrong
- **Transparency.** Many models bake in a solid or checkerboard background even when you ask for "transparent." Ask for a flat `#00ff00` chroma-key background, remove it after generation, and save as a PNG **with alpha** before adding it to the pack — otherwise the pet draws inside an opaque box.
- **Consistency, registration, and clipping.** Keeping the *same* character across frames is the hard part — and keeping it **registered** (same position, scale, and framing in every cell) is harder still. If the character drifts up/down or changes size between cells, it visibly floats and jumps as it plays, with the edge of the next frame bleeding in. If visible pixels enter the outer 24 px margin, hair, hands, props, or glow can clip when the sheet is sliced. Reuse the reference image, keep the character/style/accent lines byte-identical, lock a fixed **seed**, stress "lock the head and shoulders in place," and validate that every cell keeps the same anchor and safe-box padding. If a generated **4×4** still drifts, preserve the 16 cells with a locked-base hybrid pass before reducing frame count.
- **No motion (over-locked).** The opposite of drift: lean too hard on "identical / locked" and the model copies one pose across all 16 cells, so the pet looks frozen even though it cycles through them. The cells are an **animation, not copies** — the moving parts (eyes, mouth, hands, hair, accent) must *visibly* change cell to cell, sweeping the full arc (a blink open→shut→open, a breath rise→settle). If a contact sheet reads as 16 near-identical tiles, regenerate demanding clearer per-frame change.
:::

::: tip Static packs are first-class
Want to confirm the pipeline today before perfecting nine animated sheets? Generate expressive stills and reference each as a one-frame `frames` clip (`"frames": ["idle.png"]`) instead of a `sheet`. `1024×1024` is already generous for the mobile companion target. No in-state motion, but every authored state remains visibly distinct, including brief still `greet` and `done` reactions.
:::

For a smooth, perfectly-stable animated character, hand-drawn pixel art still wins — but for a charming "good enough" companion, AI stills or sprite sheets get you there without ever opening a drawing app.

## Accessibility & reduced motion

If you disable app animations, Android's animator scale is 0, or TalkBack touch exploration is active, autonomous roaming stops and the pet renders as a still frame. Scrolling Chat, Settings, or About pauses autonomous travel without teleporting or dimming the pet: it lifts slightly, follows its measured ledge while that ledge remains visible, then falls and lands when scrolling settles. The keyboard/short-screen layout changes the base art from 60 dp to 50 dp while the saved size scale remains effective; typing does not dim, pause, or disable interaction with the pet. TalkBack custom actions provide movement, reset, Appearance, and hide controls without long-hold dragging. The Sphere follows its own independent motion setting.

When authoring a pet, make the first `idle` frame a good, legible still — that's what people who prefer reduced motion will see.

## Troubleshooting

**My pet (or skin) doesn't show up in the picker.** Invalid or incomplete packs are simply skipped — they never appear, and one bad pack never breaks the picker for the rest. Check logcat for the skip reason: tag `PetLoader` for pets, `SphereSkinLoader` for sphere skins.

**I pushed files but nothing changed.** Reopen Appearance to refresh pets. For
reliable installation on devices where scoped storage stalls `adb push`, use
**Add a pet** in the app. Sphere skins are still scanned at app start, so restart
the app after pushing a skin.

**My pet appears but renders blank.** A file that exists but isn't a decodable image (a corrupt or mislabeled `.png`) passes the manifest check but can't be drawn. Confirm your images actually open before shipping a pack.

**I deleted a pet that was selected.** The floating-pet choice resets to **None**. The profile identity and Sphere are unchanged.
