# Clean mode, Sphere background, and floating pets — design record

**Status:** Implemented; concept model revised 2026-07-31 for #267
**Owner surface:** Android chat and Appearance settings

## Decision

The original implementation treated the Sphere and user-loaded pets as
interchangeable agent avatars. That model is superseded. Android now preserves
three independent concepts:

1. **Profile identity** is the speaking agent's profile image or letter fallback.
   It remains in the chat top bar and appears once at the start of an assistant
   message group.
2. **Sphere background visualization** is ambient presentation. Appearance can
   turn it Off or select Sphere, and Sphere skins remain scoped to it.
3. **Floating pet companion** is optional character presence. Appearance can
   select None or an imported pet; the selected pet does not replace profile
   identity or the Sphere.

This separation follows the established floating-companion behavior on Hermes
Desktop and Codex while adapting it to a phone-sized viewport.

## Android interaction contract

- One app-root host renders the selected companion across normal in-app
  navigation. The full-screen positioning layer is click-through; only the
  48/56 dp pet target accepts pointer input.
- Long hold starts a haptic drag. Movement is clamped to the protected viewport;
  release snaps to the nearest logical start/end edge and persists that edge plus
  a normalized vertical fraction. Pixel coordinates are not stored, so the home
  position remains meaningful after rotation, resizing, and RTL changes.
- Autonomous roaming is separately opt-in and defaults off. Chat registers a
  real 56 dp layout rail immediately above the composer; that is the only region
  where the pet walks on its own. Enabling roaming docks onto that rail; manual
  drag or a vertical accessibility move pauses roaming before applying free-form
  placement. Other app screens show the pet docked.
- Roaming runs only while Hermes is idle and the app is foregrounded. Thinking,
  streaming, tool work, errors, transcript scrolling, dragging, the pet menu,
  voice, clean/ambient mode, startup, and loss of a safe rail return it home or
  hide/suspend it as appropriate.
- App animation-off, Android animator scale 0, and TalkBack touch exploration
  disable autonomous travel and freeze the sprite on a stable frame. Scrolling
  also pauses and dims it to 60 percent opacity.
- The normal art size is 48 dp and compacts to 40 dp with the IME or a screen
  shorter than 700 dp.
- The pet remains a semantic button with its label and agent state. Its menu can
  enable/pause roaming, reset position, open Appearance, or hide the companion.
  TalkBack custom actions additionally move to logical start/end, move up/down in
  bounded steps, and reset without requiring drag.

## Appearance contract

Appearance exposes two independent sections below the existing profile identity
surfaces:

- **Background visualization:** Off or Sphere. Sphere skins remain available
  when Sphere is enabled.
- **Floating pet:** None or an imported pet, followed by add/manage, live preview,
  playback speed, activity reaction, stabilization, opt-in roaming, and reset
  position controls.

Deleting the selected pet resets only the companion selection to None. It does
not change the Sphere or active profile identity.

## Preference migration

The previous combined `agent_avatar` preference remains readable for one
release when the new floating-pet key has not been written:

- `sphere` or an absent value maps to **Floating pet: None**.
- A pet id maps to that pet as the floating companion.
- Missing or deleted pets resolve to no companion.

The Sphere remains enabled by default as the background visualization. Profile
identity data is unchanged.

## Preserved implementation boundaries

- Pet packs remain pure data loaded from `files/pets/<id>/pet.json`; the manifest
  and authoring contract are documented in [`../pet-spec.md`](../pet-spec.md).
- Petdex is an initial Android MVP installation source: browse the public v2
  catalog with v1 fallback, show creator/source attribution, and download only
  after an explicit Install action. Supported atlases are converted into local
  Relay packs and render offline thereafter.
- Petdex URLs and redirects are constrained to the exact HTTPS catalog/asset
  hosts; bounded metadata and PNG/WebP spritesheets are layout-checked before an
  atomic install. Catalog availability does not assert a uniform asset license.
- Petdex's canonical `running-left` and `running-right` rows are preserved as
  physical locomotion. The in-place `running` row remains agent work; movement
  clips never override thinking, streaming, tool, error, or reaction clips.
- Sphere skins remain a separate data system documented in
  [`../sphere-spec.md`](../sphere-spec.md).
- `MorphingSphereCore` and its web parity harness remain the source of truth for
  Sphere rendering.
- Clean text-flow mode remains a presentation of the active conversation, not a
  separate chat or profile state.

## Out of scope

- Using a pet as a sender/profile avatar.
- Coupling pet selection to Sphere visibility or skin selection.
- A system-wide, always-on-top Android overlay outside Hermes Relay. The
  companion is app-level and requires no overlay permission.
