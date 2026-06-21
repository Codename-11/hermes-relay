# Pet spec

The agent avatar (the embodiment shown on the empty chat screen, clean mode,
the voice overlay, onboarding, and the startup splash) is a **swappable
component**. The built-in **Sphere** ships with the app, and you can side-load
your own animated **"pet"** — a bitmap frame-sequence or sprite atlas — to
replace it. This document is the authoring reference.

> **See also** [`sphere-spec.md`](./sphere-spec.md) — the Sphere avatar has its
> own skin system; pets and Sphere skins are the two ways to customize the agent
> avatar.

> A pet is a self-contained pack of images plus a small JSON manifest. Pets are
> **pure data** — frames + numbers, never code. The renderer is dependency-free
> (Android `BitmapFactory` + a rate-capped Compose `Canvas`), so a pet is just a
> set of PNGs the app plays back. Pets are an Android feature; the docs-site
> embed always renders the Sphere.

## Two levels: avatar, then skin

Appearance has a two-level model:

1. **Agent avatar** — Sphere (built-in) or one of your pets.
2. **Sphere skin** — only applies to the Sphere avatar (see
   [`sphere-spec.md`](./sphere-spec.md)). When a pet is selected, skins don't
   apply and the skin chips hide.

Pick your avatar in **Settings → Appearance → Agent avatar**. Valid pets appear
as chips alongside the Sphere; selecting one switches every surface at once.

## Where pets live

Each pet is a **subdirectory** of the app's `pets/` directory, holding a
`pet.json` manifest beside its image assets:

```
<app files dir>/pets/<id>/pet.json
<app files dir>/pets/<id>/idle_0.png
<app files dir>/pets/<id>/idle_1.png
...
```

This lives in **app-scoped external storage**
(`/sdcard/Android/data/<applicationId>/files/pets/`), which is reachable by
`adb push` (or a file manager) with **no runtime permission** on API 19+; the
app falls back to internal storage only if external storage is unavailable. The
app creates the `pets/` folder on first launch. The easiest way to add a pack
today (sideload flavor shown):

```bash
# push a whole pack directory
adb push blob/ /sdcard/Android/data/com.axiomlabs.hermesrelay.sideload/files/pets/
```

On the **googlePlay** flavor, drop the `.sideload` suffix from the package —
`/sdcard/Android/data/com.axiomlabs.hermesrelay/files/pets/`.

Then reopen **Settings → Appearance → Agent avatar** — valid pets appear in the
picker. Invalid/incomplete packs are skipped (check logcat, tag `PetLoader`);
one bad pack never breaks the picker, and with no pets installed the app behaves
exactly as today (Sphere only).

## Manifest format (`pet.json`)

```json
{
  "schemaVersion": 1,
  "id": "blob",
  "label": "Blob",
  "description": "A friendly blob companion",
  "reactive": { "voice": true, "tools": false, "intensity": false },
  "states": {
    "idle":     { "frames": ["idle_0.png", "idle_1.png", "idle_2.png"], "fps": 6 },
    "thinking": { "frames": ["think_0.png", "think_1.png"], "fps": 8 },
    "speaking": { "sheet": "talk.png", "frameWidth": 64, "frameHeight": 64, "frameCount": 4, "fps": 12 }
  },
  "defaults": { "frames": ["idle_0.png"], "fps": 1 }
}
```

### Top-level fields

| Field | Required | Notes |
|-------|----------|-------|
| `schemaVersion` | no (default `1`) | Must be ≤ the version this build supports (currently **1**). |
| `id` | recommended | Stable identity + persistence key. Falls back to the pack directory name. |
| `label` | no | Shown in the picker. Falls back to `id`. |
| `description` | no | Free text. |
| `reactive` | no | Which live signals the pet honors. See below. |
| `states` | **yes** | Per-state clips. A usable `idle` clip is **required**. |
| `defaults` | no | Fallback clip for any state with no usable clip. |

### Clips and states

A **clip** is one animation loop, defined as **either**:

- a **frame sequence** — `"frames": ["a.png", "b.png", ...]`, or
- a **sprite sheet** — `"sheet": "atlas.png"` plus `"frameWidth"`,
  `"frameHeight"`, and `"frameCount"` (cells are read left-to-right,
  top-to-bottom; any rectangular grid works — a 4×4 sheet holds 16 frames,
  decoded as one bitmap regardless of cell count).

Both forms take an `"fps"` (frames per second; clamped to **1–60**, default
`8`). All clips **loop** while their state is active. Smoothness comes from frame
**count**, not rate — a 4-frame loop looks steppy at any speed; prefer **8–16
frames** for fluid motion, and match fps to the count (a 4-frame `idle` at `fps`
3–4 reads calm; `fps` 6+ looks busy). The frames must also actually **differ**:
16 near-identical cells play as a frozen image no matter the count or fps — each
cell has to be a distinct keyframe of a visible motion arc (a common failure when
AI generation is over-constrained to keep every cell "identical").

### Editor validation (JSON Schema)

A JSON Schema for this manifest is published at
`https://codename-11.github.io/hermes-relay/pet.schema.json` (source of truth:
`user-docs/public/pet.schema.json`). Add it as the first key of a `pet.json` for
editor autocomplete and inline validation — and for an AI agent to lint its own
output against:

```json
{ "$schema": "https://codename-11.github.io/hermes-relay/pet.schema.json", "id": "blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }
```

The `$schema` key is an unknown field to the loader and is silently ignored
(`ignoreUnknownKeys`), so it never affects rendering. The schema encodes the
manifest's **structural** rules — `schemaVersion`, a required `idle` clip, the
frames-or-sheet clip shapes, positive sheet dimensions — but it **cannot** check
the things only the device knows: whether a referenced file actually exists in
the pack, or decodes as an image. Those remain load-time checks (see
[Frames and images](#frames-and-images)).

## Agent states & pet behavior

The point of per-state clips is to make the agent's activity **legible** — a
glance at the pet tells you whether it's idle, thinking, writing, or talking.
This mirrors a 30-year convention (Microsoft Agent's `Think`/`Write`/`Process`
animation set, Live2D motion groups, VTuber lip-sync): a **looping base clip per
activity**, plus amplitude-driven motion while speaking.

The agent reports six activity states; you author clips by name and they resolve
through a fallback chain (first existing clip wins). The names you write are
**friendly aliases**, so `writing` targets the output/streaming state:

| Agent activity | What it means | Author clip (alias) | Fallback chain |
|----------------|---------------|---------------------|----------------|
| **Idle** | Waiting between turns | `idle` | `idle` |
| **Thinking** | Reasoning before output | `thinking` | `thinking` → `idle` |
| **Working** | Running a tool, mid-turn | `working` | *opt-in overlay — see below* |
| **Streaming** | Writing / producing output | `writing` *(or `streaming`)* | `writing` → `streaming` → `speaking` → `thinking` → `idle` |
| **Listening** | Mic open (voice) | `listening` | `listening` → `idle` |
| **Speaking** | Talking via TTS (voice) | `speaking` | `speaking` → `writing` → `thinking` → `idle` |
| **Error** | A turn failed | `error` | `error` → `thinking` → `idle` |

`idle` is the **only** hard requirement; every other state falls back to it.
Author the subset you want and the chain fills the rest.

### The `working` overlay — tool use vs. thinking

`working` is special: not one of the six base states but a **tool-use overlay**.
When the agent runs a tool mid-turn (while thinking or writing), a pet that ships
a `working` clip swaps to it; as tool activity subsides it returns to the base
state. This makes *acting* read differently from *thinking* — the strongest
cross-system convention (Microsoft Agent splits `Think` from `Process`/`Search`;
the `pi-animations` indicator splits Thinking · Working · Tool).

It is **opt-in**: with no `working` clip the pet just keeps its base-state clip
during tool use (the original behavior). Shipping a usable `working` clip is also
what lights the **Tools** badge — no separate flag needed.

### Authoring ladder (how much buys how much)

- **Minimal — 1 clip:** `idle`. A present, state-agnostic companion.
- **Basic — 3 clips:** `idle` + `thinking` + `speaking`. Rest / busy / talking —
  the core spine. (Add `voice` reactivity for a talking bounce.)
- **Standard — 5 clips:** add `writing` (distinct output loop) and `listening`
  (mic open), so chat-output vs. voice-output vs. mic-open read at a glance.
- **Rich — 7 clips:** add `error` and `working` (the tool-use overlay, so the
  user sees *thinking* vs. *running a tool* vs. *writing*). The full story.
- **Expressive — + reactions:** add one-shot `greet` and `done` clips (below) for
  personality on top of the sustained loops.

### One-shot reactions

Beyond the sustained per-state loops, a pet can play a brief **reaction** that
fires once and returns to the base loop — the touch that turns a status display
into a character (cf. the Peon Pet's celebrate-on-finish). Both are **opt-in**
(play only if you ship the clip) and **multi-frame** (a single-frame reaction is
skipped). They're suppressed under reduced motion.

| Reaction | Clip (alias) | Fires when |
|----------|--------------|-----------|
| **Greet** | `greet` *(or `wake`)* | the pet first appears on screen |
| **Done** | `done` *(or `celebrate`)* | a productive turn finishes (streaming/speaking → idle) |

Triggers are derived from the agent activity the pet already observes, so no
extra setup is needed — drop in a `done` clip and the pet celebrates each time a
reply completes. A reaction overlays whatever else would show (including
`working`), plays start→end once, then hands back to the base state.

> **Loop vs. one-shot.** Per-state clips (`idle`/`thinking`/`working`/`writing`/
> `speaking`/…) are **looping bases**; `greet`/`done` are **one-shots** layered
> over them.

### Forthcoming behavior (designed, not yet rendered)

These are specified so authors can plan, but the renderer doesn't drive them
yet — they're tracked in `TODO.md`. Authoring the clips/flags now is harmless.

- **`attention` reaction** — a one-shot when a notification arrives. Reserved: it
  needs a host event the avatar doesn't yet receive (unlike `greet`/`done`, which
  ride activity-state transitions).

## Reactivity — optional and detectable

`reactive` declares which live signals the pet honors; the picker shows the
summary so users see what a pet does before selecting it.

| Flag | Default | Effect when `true` |
|------|---------|--------------------|
| `voice` | `true` | Voice amplitude gives a subtle scale "bounce" while speaking/listening. |
| `tools` | *auto* | **Driven by the `working` clip, not this flag.** A pet that ships a usable `working` clip reacts to tool use (swaps to it while a tool runs) and advertises **Tools**; one without it doesn't — so the flag is ignored and can't over-promise. |
| `intensity` | `false` | The active clip plays **faster** as agent activity ramps (up to ~1.6× at peak) — a base/working loop visibly "works harder" while output streams. Advertises **Activity**. |

The badge only ever advertises what the renderer actually delivers: a declared
flag the renderer doesn't honor is dropped, so a pet can't over-promise. The
clips carry most of a pet's expressiveness (idle vs. thinking vs. working vs.
writing vs. speaking loops); `voice` (bounce), the `working` overlay, and
`intensity` (playback speed) add the live motion on top.

## Frames and images

- PNG with alpha is recommended (transparent background composites cleanly).
- Frames are **contain-fit and centered** in the avatar area, preserving aspect
  ratio — they don't have to match the screen's shape.
- Sprite-sheet cells need their own internal padding. Treat the declared
  `frameWidth`/`frameHeight` as the **transparent cell canvas**, not as the
  amount of space the character should fill. For 256 px cells, keep all visible
  alpha inside a roughly **200–208 px safe art box** with at least **24 px of
  transparent padding** on every side; 28 px is safer for glow, hair, hands, and
  sampling. Scale that margin proportionally for other cell sizes. Effects,
  props, hair, hands, and aura pixels count as visible content — none of them
  should touch the cell edge.
- A referenced image that **exists but isn't a decodable image** (a corrupt or
  non-PNG file with a `.png` name) is **not** caught at load time — the pet still
  appears valid in the picker but renders **blank**. Verify your images actually
  open before shipping a pack.
- **Memory:** while a pet is selected, every frame of its current clip is decoded
  into memory at **full resolution** (there is no downscaling). Many large frames
  can use a lot of RAM, and a single very large image can fail to decode. As an
  authoring best practice (not an enforced cap), keep frames ≲256 px and clip
  lengths modest (≤ ~30 frames); for many frames prefer a **sprite sheet** over a
  long frame sequence — a sheet decodes as one bitmap, so its cells can be larger
  (256–512 px) without the per-frame cost of a sequence. **Size cells to the
  largest surface the avatar appears on** (the full-screen chat background): a
  128 px cell upscaled that far looks pixelated, while smaller surfaces (the
  voice overlay) just downscale and stay sharp. Only the **selected** pet's
  **current** clip is decoded, off the main thread.
- File names must stay **inside the pack directory** — paths that escape it
  (`../…`) are rejected.

## Generating frames with AI

You don't have to draw a pet by hand. The **sprite-sheet** clip form (one image,
a grid of frames) is exactly what an AI image model produces most naturally, so
the common workflow is to generate **one sheet per state** and reference each as
a `sheet` clip. A 4×4 grid of 256 px cells maps directly to
`{ "sheet": "idle.png", "frameWidth": 256, "frameHeight": 256, "frameCount": 16 }`.

The reliable AI workflow is:

1. Generate each state **one sheet at a time** from the same reference image.
2. Ask for a flat removable chroma-key background (for example `#00ff00`) rather
   than trusting "transparent background"; remove the key and save PNG-with-alpha
   before shipping.
3. Require a centered safe art box inside every cell. For 256 px cells, keep all
   visible pixels inside ~200–208 px with at least 24 px transparent padding
   (28 px preferred).
4. Validate after background removal: the sheet must decode as RGBA, have the
   expected total dimensions, contain the expected number of evenly-spaced cells,
   have no visible alpha inside the unsafe margin, and keep the head/shoulder
   anchor at the same position and scale across frames.

Two caveats from [Frames and images](#frames-and-images) bite hardest here:
**registration** — many models still move or resize the character between cells
even with a strict prompt — and **edge padding** — models like to fill the full
cell, which clips hair, hands, props, or effects when the sheet is sliced. If a
16-frame AI sheet looks good but drifts, use a hybrid pass: pick a stable
state-specific generated frame, keep it locked, and animate only small secondary
elements such as blink, mouth, glow, sparkles, or a contained prop. This keeps a
4×4 sheet smooth without letting the head, shoulders, or silhouette float. Only
reduce the frame count (for example 3×3 or 2×2) when frame count is negotiable;
then update `frameCount`/`fps` accordingly.

Hand-drawn or rigged art still wins for smooth, perfectly-stable loops.

The ready-to-use, fill-in-the-blanks prompt template plus a per-state motion table
live in the user guide under **Custom Avatars → Generate a pet with AI**
(`user-docs/features/custom-avatars.md`).

## Reduced motion / accessibility

When the user disables animations (app setting or OS reduce-motion) or a screen
reader is exploring, the avatar is rendered **paused** — the pet freezes on its
current frame and the voice bounce is suppressed. Author the first `idle` frame
to be a good, legible still.

## Minimal example

```
pets/blob/pet.json
pets/blob/idle.png
```

```json
{ "id": "blob", "label": "Blob", "states": { "idle": { "frames": ["idle.png"], "fps": 1 } } }
```

A single-frame `idle` is a complete, valid pet (a static image avatar).

## Removing a pet

Delete the pack directory (or its `pet.json`) and reopen Appearance. If the
removed pet was selected, the avatar falls back to the **Sphere** automatically.
