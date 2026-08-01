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

- The pet uses a fixed, non-draggable perch immediately above the composer.
  Keeping it in layout prevents it from covering transcript content, snackbars,
  scroll controls, or composer actions.
- The normal size is 48 dp. The pet compacts to 40 dp when the IME is visible or
  the screen is shorter than 700 dp.
- Scrolling pauses the pet and dims it to 60 percent opacity. Disabling
  animations also pauses it on a stable frame.
- The Chat perch receives idle, thinking, writing, error, tool-burst, and
  completion signals. Listening, speaking, and voice-amplitude aliases remain
  valid pack/preview vocabulary but are not driven by the Android Chat perch.
- The companion is not rendered in clean/ambient mode, where the Sphere remains
  the intentional large-format presentation.
- The pet is an accessible button whose menu identifies its current state and
  offers **Appearance** and **Hide companion**.
- V1 is intentionally fixed-position: no dragging or free roaming on mobile.

## Appearance contract

Appearance exposes two independent sections below the existing profile identity
surfaces:

- **Background visualization:** Off or Sphere. Sphere skins remain available
  when Sphere is enabled.
- **Floating pet:** None or an imported pet, followed by add/manage, live preview,
  playback speed, activity reaction, and stabilization controls.

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
- Sphere skins remain a separate data system documented in
  [`../sphere-spec.md`](../sphere-spec.md).
- `MorphingSphereCore` and its web parity harness remain the source of truth for
  Sphere rendering.
- Clean text-flow mode remains a presentation of the active conversation, not a
  separate chat or profile state.

## Out of scope

- Using a pet as a sender/profile avatar.
- Coupling pet selection to Sphere visibility or skin selection.
- A draggable overlay or free-roaming pet on Android.
