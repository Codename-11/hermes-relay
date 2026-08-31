# Sphere background and floating pets — design record

**Status:** Implemented; interaction model revised 2026-08-01 for #267. The
separate Chat clean-focus presentation was removed 2026-08-27; the background
visualization and floating-pet contracts remain current.
**Owner surface:** Android app shell, Chat, Terminal, and Appearance settings

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
- Tap waves and opens the pet menu. Long hold starts a haptic drag and lifts the
  pet into its held state. Movement is clamped to the protected viewport and
  routed around registered controls; release disables roaming and visibly falls
  to the nearest logical start/end edge, then persists that edge plus a normalized
  vertical fraction. Pixel coordinates are not stored, so the home position
  remains meaningful after rotation, resizing, and RTL changes.
- Autonomous roaming is separately opt-in and defaults off. Screen owners
  register a curated set of live-measured perches and obstacles; the host does
  not scan arbitrary composables or accessibility nodes. Chat's composer and
  newest visible settled assistant bubble, Terminal's extra-keys toolbar, and
  the persistent bottom status strip on Settings/About are supported perches,
  using existing edges with no reserved spacer. Chat's scroll-to-bottom button
  and Terminal's jump-to-latest pill register only while visible and trim or
  block the rail segments they occupy.
- A response visit is an explicit composer → clear outer gutter → raised bubble
  top → opposite bubble edge → return → composer excursion. The pet walks across
  the bubble, but its complete footprint remains above the content and vertical
  entry/drop remains beside the text. Unsafe, narrow, or interactive responses
  are skipped. Response bubbles are not ambient patrol rails.
- Horizontal motion uses directional clips with travel duration rounded to full
  walk cycles and a short turn pause. Vertical motion uses squash anticipation,
  `jumping` to the apex, `falling` on descent, a height-responsive shadow, and a
  landing squash. Idle variety cycles through hops, waves, and rests.
- Roaming runs only while Hermes is idle and the app is foregrounded. Thinking,
  streaming, tool work, errors, transcript scrolling, dragging, the pet menu,
  voice, startup, and loss of a safe rail pause or dock it as
  appropriate. The director's fixed priority is direct interaction, agent
  activity, response visit, roaming, then idle.
- App animation-off, Android animator scale 0, and TalkBack touch exploration
  disable autonomous travel and freeze the sprite on a stable frame. Scrolling
  freezes the current screen position without re-docking, teleporting, or
  scroll-only dimming. Settings/About dialogs suspend the companion.
- The normal art size is 48 dp and compacts to 40 dp with the IME or a screen
  shorter than 700 dp; the IME pause is visually subdued.
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
  playback speed, activity reaction, stabilization, opt-in roaming,
  Calm/Balanced/Playful temperament, and reset-position controls.

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
  Relay packs, selected immediately, and render offline thereafter. Users do not
  edit manifests or remap animations to use Petdex pets.
- Petdex URLs and redirects are constrained to the exact HTTPS catalog/asset
  hosts; bounded metadata and PNG/WebP spritesheets are layout-checked before an
  atomic install. Catalog availability does not assert a uniform asset license.
- Petdex's canonical `running-left` and `running-right` rows are preserved as
  physical locomotion. The in-place `running` row remains agent work; movement
  clips never override thinking, streaming, tool, error, or reaction clips.
- The selected installed pet exposes renderer-backed previews for idle,
  directional travel, jump/fall, held, wave, work, review, waiting, and error.
  Each preview names the exact source row and distinguishes direct, mirrored,
  fallback, and mirrored-fallback behavior.
- Sphere skins remain a separate data system documented in
  [`../sphere-spec.md`](../sphere-spec.md).
- `MorphingSphereCore` and its web parity harness remain the source of truth for
  Sphere rendering.

## Out of scope

- Using a pet as a sender/profile avatar.
- Coupling pet selection to Sphere visibility or skin selection.
- A system-wide, always-on-top Android overlay outside Hermes Relay. The
  companion is app-level and requires no overlay permission.
