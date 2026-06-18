# Sphere skin spec

The agent sphere (the ASCII "orb" on the empty chat screen, ambient mode, and
the voice overlay) is a **hot-swappable component**. Built-in skins ship with the
app, an **Adaptive** skin recolors to match your theme, and you can side-load your
own skins from a small JSON file. This document is the authoring reference.

> The sphere's motion (the core math in `MorphingSphereCore.kt`, mirrored in the
> docs-site `preview/web/sphere.js`) is shared and unchanged. A skin only changes
> **colors**, optional **animation parameters**, and which **live signals** the
> orb reacts to. User skins are an Android feature; the web embed always renders
> the Classic look.

## Where skins live

Drop `*.json` files into the app's private `spheres/` directory:

```
<app files dir>/spheres/my-orb.json
```

The app creates the folder on first launch. The easiest way to add a file today:

```bash
# replace the path with your app's files dir (sideload flavor shown)
adb push my-orb.json /sdcard/Android/data/com.axiomlabs.hermesrelay.sideload/files/spheres/
```

Then reopen **Settings → Appearance → Agent sphere** — valid skins appear in the
picker with a **Custom** tag. Invalid files are skipped (check logcat for the
reason); one bad file never breaks the picker.

## Format

```json
{
  "schemaVersion": 1,
  "id": "my-orb",
  "label": "My Orb",
  "description": "A warm custom sphere",
  "reactive": { "voice": true, "tools": true, "intensity": true, "gaze": false },
  "defaults": { "color1": "#7FE9DE", "color2": "#8C5CFF" },
  "states": {
    "thinking": { "color1": "#AEBFFF", "color2": "#6BDCFF" },
    "error":    { "color1": "#FF6B78", "color2": "#F2B14B" }
  }
}
```

### Top-level fields

| Field | Required | Notes |
|-------|----------|-------|
| `schemaVersion` | no (default `1`) | Must be ≤ the version this build supports (currently **1**). |
| `id` | recommended | Stable identity + persistence key. Falls back to the filename. |
| `label` | no | Shown in the picker. Falls back to `id`. |
| `description` | no | Free text. |
| `reactive` | no | Which live signals the orb honors. See below. |
| `defaults` | no | Colors/params used for any state not in `states`. |
| `states` | no | Per-state overrides keyed by state name. |

### States

Valid state keys: `idle`, `thinking`, `streaming`, `listening`, `speaking`,
`error`. Each is a `{ color1, color2, params? }` object. The orb mixes `color1`
and `color2` over time, so pick two related hues for a calm look or two contrasting
hues for a lively one.

Any state you omit falls back to `defaults`, then to the built-in **Classic**
colors — so a one-line `defaults` is a complete, valid skin.

### Colors

`#RRGGBB` or `#AARRGGBB` (alpha is ignored — the orb computes its own glow
alpha). The `#` is optional. Colors are emissive (drawn as glowing glyphs on the
canvas), so **luminous** hues read best; very dark colors look dim.

### Reactivity — optional and detectable

`reactive` declares which live signals this skin responds to. The host only feeds
a signal the skin opts into, and the picker shows the capability summary
(`Voice · Tools · Activity`) so users can see what a skin does before selecting it.

| Flag | Default | Effect when `true` |
|------|---------|--------------------|
| `voice` | `true` | Listening/speaking amplitude swells + turbulence. |
| `tools` | `true` | Tool-call bursts pulse the orb. |
| `intensity` | `true` | General activity drives turbulence / ripple / flow. |
| `gaze` | `false` | Reserved — gaze-bias aiming (not fed by the chat/voice renderer yet). |

Set a flag to `false` for a calmer, more static orb (e.g. a "focus" skin that
ignores tool bursts).

### Params (advanced)

Each state may include an optional `params` object to tune the motion. All fields
are optional and inherit the built-in per-state default; values are clamped to
safe ranges.

| Param | Range | Meaning |
|-------|-------|---------|
| `breatheSpeed` | 0–5 | Idle breathing rate. |
| `breatheAmp` | 0–0.5 | Breathing depth. |
| `lightSpeedX` / `lightSpeedY` | 0–3 | Light-spot drift speed. |
| `lightInfluence` | 0–1 | Directional light vs. radial shading mix. |
| `coreTightness` | 0–1 | How concentrated the bright core is. |
| `turbulenceAmp` | 0–1 | Surface noise. |
| `rippleScale` | 0–5 | Concentric ripple strength. |
| `heartbeatSpeed` | 0–12 | Pulse rate. |
| `radialFlowSpeed` | 0–3 | Outward flow speed. |

## Minimal example

```json
{ "id": "ember-orb", "label": "Ember Orb", "defaults": { "color1": "#FFC24B", "color2": "#FF7A3C" } }
```

## Built-in skins (for reference)

| Skin | Look | Reacts to |
|------|------|-----------|
| **Adaptive** | Recolors to the active theme | Voice · Tools · Activity |
| **Classic** | Original green-violet orb | Voice · Tools · Activity |
| **Aurora** | Teal + violet | Voice · Tools · Activity |
| **Solar** | Amber + ember | Voice · Tools · Activity |
| **Mono** | Grayscale, no tool pulses | Voice · Activity |
