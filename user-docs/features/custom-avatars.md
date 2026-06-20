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

::: tip Authoring reference
For the manifest format — clips, sprite sheets, frame-rate limits, reactivity flags, and image best practices — see the Pet spec: [`docs/pet-spec.md`](https://github.com/Codename-11/hermes-relay/blob/main/docs/pet-spec.md).
:::

## Accessibility & reduced motion

If you disable animations — either the app's own setting or your phone's OS-level **reduce motion** — or if a screen reader is active, the avatar renders as a **still frame** instead of looping. The Sphere pins to a single frame; a pet freezes on its current frame and its voice bounce is suppressed.

When authoring a pet, make the first `idle` frame a good, legible still — that's what people who prefer reduced motion will see.

## Troubleshooting

**My pet (or skin) doesn't show up in the picker.** Invalid or incomplete packs are simply skipped — they never appear, and one bad pack never breaks the picker for the rest. Check logcat for the skip reason: tag `PetLoader` for pets, `SphereSkinLoader` for sphere skins.

**I pushed files but nothing changed.** Pets and skins are scanned at app start. Fully restart the app after pushing files, then reopen Appearance.

**My pet appears but renders blank.** A file that exists but isn't a decodable image (a corrupt or mislabeled `.png`) passes the manifest check but can't be drawn. Confirm your images actually open before shipping a pack.

**I deleted a pet that was selected.** No problem — if the selected pet is removed, the avatar falls back to the **Sphere** automatically.
