# Pet spec

The agent avatar (the embodiment shown on the empty chat screen, clean mode,
the voice overlay, onboarding, and the startup splash) is a **swappable
component**. The built-in **Sphere** ships with the app, and you can side-load
your own animated **"pet"** — a bitmap frame-sequence or sprite atlas — to
replace it. This document is the authoring reference.

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

Each pet is a **subdirectory** of the app's private `pets/` directory, holding a
`pet.json` manifest beside its image assets:

```
<app files dir>/pets/<id>/pet.json
<app files dir>/pets/<id>/idle_0.png
<app files dir>/pets/<id>/idle_1.png
...
```

The app creates the `pets/` folder on first launch. The easiest way to add a
pack today (sideload flavor shown):

```bash
# push a whole pack directory
adb push blob/ /sdcard/Android/data/com.axiomlabs.hermesrelay.sideload/files/pets/
```

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
  top-to-bottom).

Both forms take an `"fps"` (frames per second; clamped to **1–60**, default
`8`).

You only need the three **core clips** — `idle`, `thinking`, `speaking` — and
`idle` is the only hard requirement. The agent's six internal states map onto
your clips with this fallback chain (first existing clip wins):

| Agent state | Clip lookup order |
|-------------|-------------------|
| idle | `idle` |
| thinking | `thinking` → `idle` |
| streaming | `streaming` → `speaking` → `thinking` → `idle` |
| listening | `listening` → `idle` |
| speaking | `speaking` → `idle` |
| error | `error` → `idle` |

So a minimal pet supplies just `idle`; add `thinking` and `speaking` for life
during a turn. Any state with no match (and no `defaults`) falls back to `idle`.

### Reactivity — optional and detectable

`reactive` declares which live signals the pet honors; the picker shows the
summary so users see what a pet does before selecting it.

| Flag | Default | Effect when `true` |
|------|---------|--------------------|
| `voice` | `true` | Voice amplitude gives a subtle scale "bounce" while speaking/listening. |
| `tools` | `false` | Declared for the badge; reserved (the default pet renderer doesn't add tool pulses). |
| `intensity` | `false` | Declared for the badge; reserved. |

The clips themselves carry most of a pet's expressiveness (idle vs. thinking vs.
speaking loops); `voice` adds the only extra motion the renderer applies today.

### Frames and images

- PNG with alpha is recommended (transparent background composites cleanly).
- Frames are **contain-fit and centered** in the avatar area, preserving aspect
  ratio — they don't have to match the screen's shape.
- Keep frames reasonably small (e.g. 64–256 px) and clip lengths modest; only
  the **selected** pet's **current** clip is decoded, off the main thread.
- File names must stay **inside the pack directory** — paths that escape it
  (`../…`) are rejected.

### Reduced motion / accessibility

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
