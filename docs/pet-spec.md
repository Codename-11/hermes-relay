# Pet spec

The floating pet is an optional app-level Android companion that can stand and
walk on supported screen surfaces. It is independent of both the active
profile's identity image and the Sphere background visualization. You can
side-load a still or animated bitmap frame-sequence / sprite atlas as a pet.
This document is the authoring reference.

> **See also** [`sphere-spec.md`](./sphere-spec.md) — the Sphere is an optional
> background visualization with its own skin system. Choosing or hiding a pet
> does not change the Sphere, and choosing a Sphere skin does not change the
> pet.

> A pet is a self-contained pack of images plus a small JSON manifest. Pets are
> **pure data** — frames + numbers, never code. The renderer is dependency-free
> (Android `BitmapFactory` + a rate-capped Compose `Canvas`), so a pet is just a
> set of PNGs the app plays back. Pets are an Android feature; the docs-site
> Sphere embed is a separate visualization.

## Three independent appearance concepts

Android keeps these concepts separate:

1. **Profile identity** — the profile image or letter fallback that identifies
   the speaking agent in the top bar and at the first assistant message in a
   group.
2. **Background visualization** — Off or Sphere. Sphere skins apply only to this
   visualization (see [`sphere-spec.md`](./sphere-spec.md)).
3. **Floating pet** — None or one selected pet companion.

Pick a companion in **Settings → Appearance → Floating pet**. One app-level host
keeps it present across normal in-app navigation. Selecting a pet does not
replace the profile identity or Sphere.

## Placement and roaming

The companion has a durable home and an optional autonomous roaming mode:

- Long hold the pet, drag it, and release. It stays inside a protected viewport,
  avoids registered controls, and visibly falls to the nearest valid manual or
  roaming surface. Dragging never changes the roaming preference, so an enabled
  pet resumes exploring after it lands. The app stores a logical edge plus a
  normalized vertical position—not raw pixels. The placement therefore adapts to
  rotation, resizing, and RTL layouts. **Reset position** restores the default
  end-edge home near the lower-right Chat area in left-to-right layouts (and
  mirrors correctly in right-to-left layouts).
- Tap the pet for a wave and its menu. The tap reaction is lower priority than
  active Hermes work and never interrupts a drag or drop. The menu can enable or
  pause roaming, reset position, open Appearance, or hide the companion.
- **Walk around the interface** is opt-in and off by default. Screen owners
  explicitly register curated perches and obstacles using their live measured
  bounds; the app does not scan arbitrary UI elements or treat the accessibility
  tree as walkable geometry. The supported perches are Chat's composer, the
  newest visible settled user/assistant bubble, Terminal's extra-keys toolbar, and
  the persistent bottom status strip on Settings and About. They use existing
  control or content edges, so the overlay inserts no spacer and reduces no text
  or control layout area. Other routes keep the pet docked at its saved edge.
  Additional Settings terrain can publish through the same live registry, but
  every card/header remains an explicit opt-in with current scroll/modal state;
  arbitrary Compose or accessibility elements never become terrain.
- At the settled Chat bottom, the home hierarchy is a full-footprint side pocket
  beside the latest bubble, its raised top edge, then the outer composer corner.
  A wide pocket permits pacing; a narrow valid pocket becomes an idle point.
  Layout and scroll updates replan from the live coordinate without teleporting.
- Appearance scales the pet from 60–120% (100% default). The new 100% default is
  the previous control's 125% physical size; stored values are rebased once so
  existing choices keep the same rendered size. The same value drives
  art, the minimum-accessible touch target, collision footprint, perch fit, and
  route clearance. A larger pet skips a narrow ledge instead of covering UI.
- Visible jump-to-latest controls are registered as temporary obstacles: Chat's
  scroll-to-bottom button and Terminal's jump-to-latest pill trim the ledge into
  pet-sized safe segments, or block it when no usable segment remains. When the
  controls disappear, their measured obstacle is removed.
- After a plain assistant response settles, the pet may make one deterministic
  visit: walk along the composer to a clear screen-side gutter, jump vertically
  beside the message, step onto a rail raised above the bubble, walk across its
  top, pause and wave, walk back, then drop beside the message to the composer.
  The full sprite footprint remains above the bubble and its jump/drop path does
  not cross text. Cards, attachments, tool rows, phone/voice actions, narrow
  bubbles, and bubbles without a safe gutter are skipped.
- Horizontal travel uses directional walking aliases and a duration rounded to
  complete walk cycles, preventing sliding feet. Turns pause briefly. Vertical
  travel uses a squash anticipation, `jumping` through the apex, `falling` on
  descent, an altitude-responsive shadow, and a small landing squash. Ambient
  idle actions cycle through a hop, wave, and rest rather than repeating one
  animation continuously. TalkBack vertical moves intentionally pause roaming
  before applying free-form placement.
- Only the pet-sized target consumes touch input; the root positioning layer is
  click-through. Registered UI bounds keep autonomous movement and drag
  placement clear of curated controls while content may pass behind the overlay.
- Roaming requires an idle agent, the foreground app, enabled animation, and an
  available safe rail. Agent activity, scrolling, dragging, an open pet menu,
  startup, voice, clean/ambient mode, Android animator scale 0, or TalkBack touch
  exploration stops autonomous travel. During scrolling, the pet follows its
  current measured or synthetic habitat rail while it remains valid; it does
  not re-dock or teleport. The IME/short-screen compact layout uses 50 dp base
  art before the saved size scale. Settings
  and About publish their scroll state and hide the companion while their dialogs
  are open.

The behavior director has one deterministic priority order: direct interaction,
Hermes activity, a pending response visit, autonomous roaming, then idle. This
keeps user intent and truthful agent-state animation ahead of decorative motion.
TalkBack users also get actions to move to start/end, move up/down, reset, open
Appearance, and hide without performing a drag gesture.

### Temperament and pacing

Appearance offers three phone-local pacing presets. They change only how often
an otherwise-idle pet may act; they do not bypass any safety, activity, scrolling,
dialog, reduced-motion, or accessibility gate.

| Temperament | Response visit | Roam interval | Idle reaction |
|-------------|----------------|---------------|---------------|
| **Calm** | after 2.5 s | every 12 s | every 28 s |
| **Balanced** (default) | after 1.5 s | every 8 s | every 18 s |
| **Playful** | after 0.75 s | every 5 s | every 10 s |

## Petdex catalog and custom packs

Appearance offers two installation paths:

- **Browse Petdex** opens a searchable public catalog. Android prefers Petdex's
  compact v2 manifest and falls back to the v1 manifest if v2 cannot be loaded.
  Catalog metadata is cached in memory for five minutes. The gallery requests
  the same cropped, cached idle-frame thumbnails as Hermes Desktop while a
  virtualized grid keeps the full catalog reachable; the full atlas is
  downloaded to the phone only after an explicit Install action. Installation
  converts and selects the pet immediately; enabling roaming and optionally
  choosing a temperament are the only behavior settings. Petdex users do not
  edit a manifest, rename rows, or create extra animations.
- **Add a pet** imports a custom Relay-format `.zip` or single image from device
  storage. This path remains independent of Petdex and accepts the manifest
  format documented below.

Each Petdex card shows the submitting creator and links to its source page.
Petdex does not provide one uniform license for every community asset, so a
catalog listing is not permission to redistribute or relicense the art. Review
the linked source and creator-provided terms before reuse outside the app.

Installing converts the supported Petdex atlas into a normal local Relay pet
pack under `files/pets/petdex-<slug>/`, retaining `source: "petdex"`, the source
URL, and creator attribution in `pet.json`. The installed `pet.json` and
spritesheet are sufficient for rendering, so the selected companion continues
to work offline. Network access is needed to refresh the catalog, load an
uncached gallery thumbnail, or install another pet. An installed Petdex pet can
be selected, hidden, or removed like a custom pack.

### Petdex trust and resource limits

Petdex network access is deliberately narrow:

- Catalog requests accept HTTPS on port 443 only from `petdex.dev` or
  `assets.petdex.dev`; asset requests accept only `assets.petdex.dev`. URLs with
  embedded credentials are rejected, and each redirect is revalidated (maximum
  three redirects).
- v2 and v1 catalog responses are limited to 4 MiB and 8 MiB respectively, with
  at most 10,000 entries. Per-pet metadata is limited to 256 KiB.
- Spritesheets are limited to 32 MiB and 16 million decoded pixels, must be PNG
  or WebP, and must match a supported Petdex atlas layout.
- Installation uses a temporary directory, validates the exact generated Relay
  pack, then swaps it into place atomically. A failed download or validation
  does not replace an existing installed pet.

The importer downloads only Petdex metadata and the referenced spritesheet; it
does not run install scripts or execute pack content.

### Petdex atlas mapping

Android supports both current 8-column × 9-row and legacy 9-column × 8-row
Petdex atlases. Cells are 192 × 208 pixels. Current rows use their canonical
counts—idle 6, running-right 8, running-left 8, waving 4, jumping 5, failed 8,
waiting 6, running 6, and review 6. Android derives an average fps from each
row's canonical total duration (700–1220 ms); its constant-fps clip model cannot
express Desktop's longer final-frame hold exactly. `startFrame` selects the row
without copying the atlas.

Packs written by the original Android adapter may still declare six frames for
every current row. The loader recognizes generated `source: "petdex"` clips by
their canonical geometry and offsets and normalizes them at read time, so an
existing install loses blank wave/jump cells and regains full directional/error
cycles without a reinstall. Custom pack frame counts are never rewritten.

The adapter preserves Petdex's source row names instead of flattening them:

| Meaning | Current Petdex row | Relay use |
|---------|--------------------|-----------|
| Rest | `idle` | Idle and ultimate fallback |
| Review | `review` | Thinking/review fallback |
| In-place work | `running` | Working/tool and streaming fallback |
| Horizontal travel | `running-left`, `running-right` | Physical locomotion only while the agent is idle |
| Waiting | `waiting` | Listening/waiting compatibility and preview |
| Greeting/completion | `waving` | Speaking, greet/done, tap, and ambient-wave fallback |
| Failure | `failed` | Error fallback |
| Celebration | `jumping` | Success/celebrate, physical jump, and fall fallback |

Legacy `wave`, `run`, and `jump` row names remain accepted. Legacy atlases do
not contain directional rows, so they remain valid activity-reactive pets but
use the legacy run row (mirrored for rightward travel) when available. Custom
Relay packs may add
`walking-left`/`walking-right`, `walk-left`/`walk-right`,
`running-left`/`running-right`, or `run-left`/`run-right` clips.

Locomotion and activity are intentionally separate. Directional clips can be
selected only when the agent state is Idle; thinking, streaming, tool work,
errors, and one-shot reactions always win.

### Capability preview and honest fallbacks

The selected installed Petdex pet has an interactive preview for **Idle, Walk
left, Walk right, Jump, Fall, Held, Wave, Working, Review, Waiting,** and
**Error**. Each choice is rendered through the same resolver as the overlay and
names the exact manifest/Petdex row used. The label also says whether that row
is direct, mirrored, a fallback, or a mirrored fallback, so a missing optional
animation is visible before the pet roams.

The important fallback rules are:

- left/right travel accepts the matching `walking-*`, `walk-*`, `running-*`, or
  `run-*` row; if only the opposite direction exists it is mirrored. A legacy
  `run`/`running` row is treated as left-facing travel and mirrored to the right.
  The preview labels an opposite native directional row **Mirrored**, but labels
  the legacy in-place row **Mirrored fallback** because it is not native travel
  art. If no travel row exists, idle art moves as a plain **Fallback**.
- `falling`/`fall` falls back to `jumping`/`jump`, then idle. Jump, held, and wave
  each fall back to idle when their optional row is absent.
- Working uses `working`, `run`, or `running`; Review uses `thinking` or `review`;
  Waiting uses `listening` or `waiting`; Error uses `error` or `failed`. The
  renderer's existing activity chain ultimately falls back to idle.

This preview describes pack/renderer capability. A host can still suppress an
otherwise available action when its current route, agent state, accessibility
policy, or measured geometry does not permit it.

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

Then reopen **Settings → Appearance → Floating pet** — valid pets appear in the
picker. Invalid/incomplete packs are skipped (check logcat, tag `PetLoader`);
one bad pack never breaks the picker, and with no pets installed the companion
picker offers **None**.

The in-app **Add a pet** importer accepts either a `.zip` pack or a single image.
Zip packs may put `pet.json` at the archive root or inside one top-level folder;
the importer validates the manifest, rejects unsafe archive paths, and installs
the pack under the sanitized manifest `id`. A single image is wrapped as a
one-frame static `idle` pet.

For generated folders, the canonical repo-side packer writes the tidy top-level
folder shape and includes only manifest-referenced assets:

```bash
node scripts/package-pet.mjs path/to/my-pet --out my-pet.zip
```

## Manifest format (`pet.json`)

```json
{
  "schemaVersion": 1,
  "id": "blob",
  "label": "Blob",
  "description": "A friendly blob companion",
  "reactive": { "voice": false, "tools": false, "intensity": true },
  "states": {
    "idle":     { "frames": ["idle_0.png", "idle_1.png", "idle_2.png"], "fps": 6 },
    "thinking": { "frames": ["think_0.png", "think_1.png"], "fps": 8 },
    "writing":  { "sheet": "write.png", "frameWidth": 64, "frameHeight": 64, "frameCount": 4, "fps": 12 }
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
| `source` | no | Informational origin identifier, such as `petdex`; never fetched or executed by the loader. |
| `sourceUrl` | no | Informational source/attribution page; never fetched by the loader. |
| `creator` | no | Creator attribution retained with gallery-installed packs. |
| `reactive` | no | Which live signals the pet honors. See below. |
| `states` | **yes** | Per-state clips. A usable `idle` clip is **required**. |
| `defaults` | no | Fallback clip for any state with no usable clip. |

### Clips and states

A **clip** is one animation loop, defined as **either**:

- a **frame sequence** — `"frames": ["a.png", "b.png", ...]`, or
- a **sprite sheet** — `"sheet": "atlas.png"` plus `"frameWidth"`,
  `"frameHeight"`, and `"frameCount"` (cells are read left-to-right,
  top-to-bottom; optional `"startFrame"` chooses the zero-based first cell).
  Any rectangular grid works — a 4×4 sheet holds 16 frames, decoded as one
  bitmap regardless of cell count.

Both forms take an `"fps"` (frames per second; clamped to **1–60**, default
`8`). All clips **loop** while their state is active. A frame sequence may contain
just **one** image; that is a valid static clip for the state. For animated clips,
smoothness comes from frame **count**, not rate — a 4-frame loop looks steppy at
any speed; prefer **8–16 frames** for fluid motion, and match fps to the count (a
4-frame `idle` at `fps` 3–4 reads calm; `fps` 6+ looks busy). The frames must
also actually **differ**: 16 near-identical cells play as a frozen image no
matter the count or fps — each cell has to be a distinct keyframe of a visible
motion arc (a common failure when AI generation is over-constrained to keep every
cell "identical").

### Editor validation (JSON Schema)

A JSON Schema for this manifest is published at
`https://hermes-relay.dev/docs/pet.schema.json` (source of truth:
`user-docs/public/pet.schema.json`). Add it as the first key of a `pet.json` for
editor autocomplete and inline validation — and for an AI agent to lint its own
output against:

```json
{ "$schema": "https://hermes-relay.dev/docs/pet.schema.json", "id": "blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }
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
glance at the pet tells you whether it's idle, thinking, writing, working, or in
an error state. This mirrors a 30-year convention (Microsoft Agent's
`Think`/`Write`/`Process` animation set): a **looping base clip per activity**.

The agent reports six activity states; you author clips by name and they resolve
through a fallback chain (first existing clip wins). The names you write are
**friendly aliases**, so `writing` targets the output/streaming state:

| Agent activity | What it means | Author clip (alias) | Fallback chain |
|----------------|---------------|---------------------|----------------|
| **Idle** | Waiting between turns | `idle` | `idle` |
| **Thinking** | Reasoning before output | `thinking` *(or `review`)* | `thinking` → `review` → `idle` |
| **Working** | Running a tool, mid-turn | `working` *(or `run`/`running`)* | *opt-in overlay — see below* |
| **Streaming** | Writing / producing output | `writing` *(or `streaming`)* | `writing` → `streaming` → `working` → `run` → `running` → `review` → `thinking` → `idle` |
| **Listening** | Mic open (voice) | `listening` *(or `waiting`)* | `listening` → `waiting` → `idle` |
| **Speaking** | Talking via TTS (voice) | `speaking` *(or `talking`)* | `speaking` → `talking` → `wave` → `waving` → `writing` → `idle` |
| **Error** | A turn failed | `error` *(or `failed`)* | `error` → `failed` → `review` → `thinking` → `idle` |

`idle` is the **only** hard requirement; every other state falls back to it.
Author the subset you want and the chain fills the rest.

The app-level host receives Chat's Idle, Thinking, Streaming, Error, tool-burst,
and completion signals. The manifest vocabulary retains `listening` and
`speaking` for pack compatibility and preview tooling, but Android hides the pet
during its full-screen voice presentation. The Sphere and voice presentation
remain separate systems.

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
- **Basic — 3 clips:** `idle` + `thinking` + `writing`. Rest / reasoning / output
  form the live Chat spine.
- **Standard — 5 clips:** add `error` and `working` (the tool-use overlay), so
  the user sees *thinking* vs. *running a tool* vs. *writing*.
- **Compatibility — optional clips:** `listening` and `speaking` remain valid
  manifest states for preview tooling and possible non-Android hosts, but the
  current Android companion host does not drive them.
- **Expressive — + reactions:** add one-shot `greet` and `done` clips (below) for
  personality on top of the sustained loops.

### One-shot reactions

Beyond the sustained per-state loops, a pet can play a brief **reaction** that
fires once and returns to the base loop — the touch that turns a status display
into a character (cf. the Peon Pet's celebrate-on-finish). Both are **opt-in**
(play only if you ship the clip). They can be multi-frame, or a single still
frame held for about 1.8 seconds. They're suppressed under reduced motion.

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
  needs a host event the pet doesn't yet receive (unlike `greet`/`done`, which
  ride activity-state transitions).

## Reactivity — optional and detectable

`reactive` declares which live signals the pet honors; the picker shows the
summary so users see what a pet does before selecting it.

| Flag | Default | Effect when `true` |
|------|---------|--------------------|
| `voice` | `true` | Declares voice-amplitude bounce support in the renderer and preview. The current Android Chat perch does not provide voice amplitude. |
| `tools` | *auto* | **Driven by the `working` clip, not this flag.** A pet that ships a usable `working` clip reacts to tool use (swaps to it while a tool runs) and advertises **Tools**; one without it doesn't — so the flag is ignored and can't over-promise. |
| `intensity` | `false` | The active clip plays **faster** as agent activity ramps (up to ~1.6× at peak) — a base/working loop visibly "works harder" while output streams. Advertises **Activity**. |

Capability badges describe what the pack and renderer support, not which signals
every host surface supplies. On the Android Chat perch, `working` and
`intensity` are live; `voice` is retained for pack compatibility and preview.
The clips carry most of a pet's expressiveness, while the working overlay and
intensity-driven playback speed add live motion on top.

## Frames and images

- PNG with alpha is recommended (transparent background composites cleanly).
- Frames are **contain-fit and centered** in the pet area, preserving aspect
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
  can use a lot of RAM, and a single very large image can fail to decode. For a
  static per-state pack, one `2048×2048` PNG per state is reasonable for
  full-width, high-density phones because each clip contains only one frame and
  only the **current** clip is decoded. For animated frame sequences, keep frames
  smaller and clip lengths modest (≤ ~30 frames); for many frames prefer a
  **sprite sheet** over a long frame sequence — a sheet decodes as one bitmap, so
  its cells can be larger (256–512 px) without the per-frame cost of a sequence.
  **Size art for the companion perch**: a 128 px cell or still is sufficient
  for the 50/60 dp base rendered pet, even at the 120% setting, and avoids
  unnecessary decode cost.
  Only the **selected** pet's **current** clip is decoded, off the main thread.
- File names must stay **inside the pack directory** — paths that escape it
  (`../…`) are rejected.

## Generating frames with AI

You don't have to draw a pet by hand. There are two AI-friendly workflows:

1. **Static per-state pack:** generate one expressive PNG per state and reference
   each as a one-item `frames` clip, for example
   `{ "frames": ["thinking.png"], "fps": 1 }`. This is the easiest path: no
   frame registration problem, no drifting animation, and every state can still
   read differently through pose, expression, hands, props, and accent color.
2. **Animated sprite sheets:** generate one grid per state and reference each as
   a `sheet` clip. A 4×4 grid of 256 px cells maps directly to
   `{ "sheet": "idle.png", "frameWidth": 256, "frameHeight": 256, "frameCount": 16 }`.

For a static pack, make the state pose more expressive because there is no motion
arc to carry the meaning. Keep the same reference image, style, palette, outfit,
face, proportions, canvas size, and head/shoulder anchor for every state, but
change the still's readable cue: thoughtful eyes and hand for `thinking`, a
contained tool/gear for `working`, mouth/gesture for `speaking`, warm warning
accent for `error`, a clear wave for `greet`, and a celebratory smile/accent for
`done`. Use transparent PNGs (or chroma-key then remove it), keep the same safe
box margins, and avoid scenery/text/backgrounds. For normal use, `1024×1024`
stills are already larger than the Android companion perch requires; downsize
finished assets when practical to reduce package and decode cost.

For animated sheets, the reliable AI workflow is:

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

Two caveats from [Frames and images](#frames-and-images) bite hardest with
animated sheets: **registration** — many models still move or resize the
character between cells even with a strict prompt — and **edge padding** — models
like to fill the full cell, which clips hair, hands, props, or effects when the
sheet is sliced. If a 16-frame AI sheet looks good but drifts, use a hybrid pass:
pick a stable state-specific generated frame, keep it locked, and animate only
small secondary elements such as blink, mouth, glow, sparkles, or a contained
prop. This keeps a 4×4 sheet smooth without letting the head, shoulders, or
silhouette float. Only reduce the frame count (for example 3×3 or 2×2) when
frame count is negotiable; then update `frameCount`/`fps` accordingly.

Hand-drawn or rigged art still wins for smooth, perfectly-stable loops.

The ready-to-use, fill-in-the-blanks prompt template plus a per-state motion table
live in the user guide under **Custom Avatars → Generate a pet with AI**
(`user-docs/features/custom-avatars.md`).

## Reduced motion / accessibility

When the user disables animations, Android animator scale is 0, or TalkBack touch
exploration is active, autonomous roaming stops and the pet is rendered
**paused**. Transcript, Settings, and About scrolling pause autonomous travel
without re-docking, teleporting, or dimming the companion.
With the keyboard open or on a short screen its base art compacts from 60 dp to
50 dp before the persisted 60–120% scale is applied. Keyboard visibility does
not dim or pause the pet: playback, roaming on valid compact terrain, taps, and
dragging remain available while typing. Dialogs on
supported Settings/About routes suspend the companion entirely. Temperament
never overrides these gates. Author the first `idle` frame to be a good, legible
still. The companion exposes its name and current state, plus non-drag
move/reset/configure/hide accessibility actions.

During a scroll, a pet lifts slightly and remains attached to its registered
ledge or text-safe settled Chat habitat while that terrain stays visible. This
makes Settings scrolling visibly affect the pet even when its only safe terrain
is the fixed status-strip rail, and prevents a moving chat bubble from sliding
under a pet settled beside it.
If a scrolling content ledge leaves the safe
viewport, the overlay keeps the pet at its last safe screen coordinate and uses
the falling state; after the gesture and fling settle, it lands on the nearest
visible valid lower rail (or jumps to the nearest remaining rail when none is
below). An incidental message rail is never selected as the recovery landing;
the latest text-safe Chat habitat remains eligible. The pet does not scroll
off-screen with content, and no unregistered Settings card becomes terrain
automatically.

## On-device visual review checklist

Use a Petdex pet with current directional rows where possible, then repeat the
fallback checks with a pack missing one or more optional rows:

- [ ] With roaming enabled, the pet walks the composer end-to-end above the
  control without covering input text, buttons, or the scroll-to-bottom control.
- [ ] After a plain assistant response, it approaches through the outer gutter,
  jumps beside the bubble, walks across the raised top rail, waves, returns to
  the same edge, and drops to the composer without covering message text.
- [ ] A narrow bubble or blocked gutter is skipped; cards, attachments, tool
  rows, and phone/voice action bubbles are never visited.
- [ ] Scrolling Chat, Settings, and About freezes the pet at its current screen
  coordinate with no teleport, edge snap, or scroll-only dimming; motion replans
  after scrolling stops. Settings/About dialogs suspend it.
- [ ] Left/right travel faces correctly. The Petdex preview reports and visibly
  demonstrates direct, mirrored, fallback, and mirrored-fallback selection for
  Walk left/right, Jump, Fall, Held, Wave, Working, Review, Waiting, and Error.
- [ ] A tap waves and opens the menu. Long hold lifts into held art; drag avoids
  registered controls; release visibly falls and settles on a valid persisted or
  roaming surface with a landing squash, without changing the roaming preference.
- [ ] Jump anticipation, apex transition to falling, altitude-responsive shadow,
  landing squash, turn pauses, and foot-speed synchronization read naturally.
- [ ] Calm, Balanced, and Playful produce visibly different response/patrol/idle
  pacing without interrupting direct interaction or active Hermes work.
- [ ] Terminal walks the extra-keys toolbar; Settings/About walk the bottom
  status rail; routes without a registered rail stay docked.
- [ ] App animation-off, Android animator scale 0, and TalkBack touch exploration
  stop autonomous motion. TalkBack can move, reset, configure, and hide the pet
  without dragging.

## Minimal example

```
pets/blob/pet.json
pets/blob/idle.png
```

```json
{ "id": "blob", "label": "Blob", "states": { "idle": { "frames": ["idle.png"], "fps": 1 } } }
```

A single-frame `idle` is a complete, valid static pet.

## Removing a pet

Delete the pack directory (or its `pet.json`) and reopen Appearance. If the
removed pet was selected, the floating-pet choice resets to **None**. The Sphere
and profile identity are unchanged.

## Migration from the combined picker

For one release, Android reads the former `agent_avatar` preference when the new
floating-pet preference is absent:

- `sphere` or no previous selection becomes **Floating pet: None**.
- A valid pet id remains selected as the floating companion.
- A missing or deleted pet resolves to no companion.

The Sphere remains the default background visualization. Profile images and
letter fallbacks are not migrated because they were never pet selections.
