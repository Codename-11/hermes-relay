# Clean Sphere / Text-Flow Chat Mode + Swappable Avatar "Pets" — Design Spec

**Status:** Design spec — ready for orchestration after the voice overhaul merges to `dev`
**Date:** 2026-06-18
**Owner surface:** Android app (chat screen, avatar rendering, appearance settings)
**Goal path:** `docs/plans/2026-06-18-chat-clean-mode-and-pets.md`
**Goal:** A touch-and-hold minimalist chat mode that shows only the agent avatar with clean themed text flowing in and fading out, AND make the avatar a swappable surface so users can drop in custom "pets" in place of the sphere. Decided scope: **P1 + P2 + P3** (mode + avatar seam + pet plugin system).

---

## Bottom Line

- **Largely a recombination of existing assets, not greenfield.** An `ambientMode` already exists (`ChatScreen.kt:561`, long-press → fullscreen sphere, bubbles hidden, `:1631-1677`/`:1866-1872`). A user-loadable, theme-adaptive **sphere-skin** system already exists (`SphereSkin`/`SphereSpec` JSON from `files/spheres/`, picked in Appearance settings). The clean mode evolves ambient mode; pets need exactly **one** new seam above the skin layer.
- **The voice overlay already solves the hard layout** (centered sphere + auto-scrolling transcript, `VoiceModeOverlay.kt:262-280`) — clean mode is the "third presentation" of the same avatar surface and should borrow it.
- **Sequence after the voice overhaul merges** — both efforts heavily rewrite `ChatScreen.kt`, `VoiceModeOverlay.kt`, `RelayApp.kt`; running them concurrently would collide.

---

## Verified Context (from the 2026-06-18 chat audit)

- `ambientMode` flag: `ChatScreen.kt:561`; enter via background long-press `:1866-1872` (already scoped so bubbles consume their own long-press first, `MessageBubble.kt:316`); fullscreen sphere `:1631-1677`; sphere call sites `:1652/1740/1876/2801`.
- Streaming source: `ChatViewModel.messages` (`:1258`), `isStreaming` (`:1278`), `sendMessage(text)` (`:1815`); message fields `content/isStreaming/isThinkingStreaming/thinkingContent` (`ChatMessage.kt:26-34`).
- Animation vocabulary already in-repo: `fadeIn(tween)+slideInVertically` (`ChatScreen.kt:1404-1414`); the "clean mono materialize/fade" reference is `ChatLoadingCommandRow` (`:2929-3048`).
- Avatar: `MorphingSphere.kt` (renderer, manual frame loop `:105-133`), `MorphingSphereCore.kt` (`SphereState` enum `:19-26`, algorithm `:181` — byte-for-byte mirrored in `preview/web/sphere.js` + `MorphingSphereCoreParityTest`); skin system `SphereSkin.kt` (`SphereReactivity` `:28-49`), `SphereSpec.kt`, `SphereSkinLoader.kt`, locals provided at `RelayApp.kt:788-790`, picker `AppearanceSettingsScreen.kt:356-419`.
- Typography tokens: `Type.kt:45-58` (bodyMedium SansSerif, labelSmall Monospace), `RelayRefresh.kt:171-177` (`relayMetadataStyle` mono).
- Voice overlay reuse: `VoiceModeOverlay.kt:262-280` (sphere slot + transcript), `:226-228` (streaming-length signal).

---

## Work Packages (orchestration-ready). Note: this effort is **largely sequential** — P2 swaps the call sites P1 touches, P3 builds on P2's seam — so it runs as a tight C1→C2→C3 chain, not parallel waves.

### WP-C1 — P1: Clean text-flow mode (sphere only) `[Client]`
**Owns:** `ui/screens/ChatScreen.kt` (ambient-mode evolution), new `ui/components/AgentTextFlow.kt` (+ a minimal in-mode composer, either a stripped reuse or a small new field to avoid `ChatInputBar` contention).
1. Promote `ambientMode` (`ChatScreen.kt:561`) from passive fullscreen sphere to interactive clean mode. Keep the long-press entry; make **exit an explicit control** (top-corner dismiss + back handling), NOT any-tap (the mode now has a composer).
2. Borrow the voice-overlay sphere+transcript skeleton (`VoiceModeOverlay.kt:262-280`) but swap the persistent transcript for the ephemeral flow.
3. **Text-flow** (`AgentTextFlow`): bind to the last assistant message's streaming `content`; tokenize into words/segments; animate each NEW segment with `fadeIn(tween(~180))+slideInVertically{it/6}` (reuse `:1404-1414` vocab); keep a bounded buffer (≤6 lines) where settled lines dwell ~2.5–4s then `fadeOut(tween(~600))` and are **removed** (not just alpha-0) so they stop composing. Don't fade the still-growing tail; start the final-line dwell when `isStreaming` flips false (`:1156-1159`). Plain `Column` + `AnimatedVisibility`, max-width-capped.
4. Typography: a mono flow style `bodyMedium.copy(fontFamily = FontFamily.Monospace)`, color `onSurfaceVariant`, restrained glow — themed, not overdone.
5. Minimal composer → `chatViewModel.sendMessage(...)` (same call as `:2499`); no model/effort pills, no attach, no slash palette in clean mode.
6. State stays **UI-local** (`remember { mutableStateOf }`) — it's a presentation over the same conversation, not new VM state. Optional sticky pref mirroring `animationEnabled` (`ConnectionViewModel.kt:1276`).

### WP-C2 — P2: `AgentAvatar` seam (refactor-only) `[Client]` (dep: C1)
**Owns:** new `ui/components/avatar/AgentAvatar.kt` + `SphereAvatar.kt`, `ui/RelayApp.kt` (locals), `ui/components/MorphingSphere.kt` (wrap), `ui/components/VoiceModeOverlay.kt` (call-site swap), `ui/screens/ChatScreen.kt` (call-site swaps), `ui/screens/AppearanceSettingsScreen.kt` (picker).
1. Define `interface AgentAvatar { id/label/description/source; val reactivity: SphereReactivity; @Composable fun Render(state: AvatarRenderState, modifier) }` and `data class AvatarRenderState(state: SphereState, intensity, toolCallBurst, voiceAmplitude, voiceMode)` — REUSE the existing state vocabulary + input bundle + reactivity contract.
2. `SphereAvatar` = default impl; its `Render` is the current `MorphingSphere` body verbatim, internally consuming `LocalSphereSkin` (so `SphereSkin` stays unchanged, nested inside). Two-level model: which avatar → for the sphere avatar, which skin.
3. Add `LocalAgentAvatar`/`LocalAvailableAvatars` at `RelayApp.kt:788` beside the skin locals. Swap every `MorphingSphere(...)` call site (`ChatScreen.kt:1652/1740/1876/2801`, `VoiceModeOverlay.kt:274`, and C1's clean mode) to `LocalAgentAvatar.current.Render(renderState, modifier)`. All three surfaces (chat / clean mode / voice) get pets for free.
4. Picker: `AppearanceSettingsScreen.kt:356-419` becomes "Agent avatar" with avatar chips on top; skin chips remain below when the sphere avatar is selected. Reuse `reactivity.summary()` badge.
5. Leave `MorphingSphereCore` + the parity test + `preview/web/sphere.js` untouched.

### WP-C3 — P3: Pet plugin system `[Client]` (dep: C2)
**Owns:** new `ui/components/avatar/PetAvatar.kt` + `PetLoader.kt`, `ui/screens/AppearanceSettingsScreen.kt` (pet chips), `docs/` (pet spec page mirroring `docs/sphere-spec.md`), the `files/pets/` on-disk contract.
1. `PetLoader` mirrors `SphereSkinLoader` (load + validate user JSON from `files/pets/<id>/pet.json` + asset frames).
2. `PetAvatar` renders an animated drawable / sprite atlas; maps `SphereState`→animation clip (idle/thinking/speaking loops) and `voiceAmplitude`→scale/bounce. **Cap frame rate** the way `MorphingSphere.kt:105-133` does (manual loop, not an unbounded `rememberInfiniteTransition`).
3. Pet chips in the avatar picker; a doc page describing the pet spec.

---

## Cross-cutting requirements (apply across C1–C3)
- **Accessibility (currently a gap):** clean mode makes the avatar the only content, so a **non-animating fallback is mandatory** — honor `animationEnabled` (`ConnectionViewModel.kt:1276`) and OS reduced-motion; when off, still show the text statically. The fade-out flow needs a non-fading a11y mirror / live-region announcement (faded text is unreadable to TalkBack — compounds the existing `MessageBubble.kt:162` 100-char a11y truncation and the sphere's missing `contentDescription`, `MorphingSphere.kt:144`).
- **Performance:** bound the text buffer, remove faded lines, and cap any sprite frame rate; clean mode runs the avatar at full rate during a turn.
- **Discoverability:** the entry is a quiet long-press; add a first-run hint or a Settings toggle so the mode is findable.
- **Gesture safety:** the in-mode composer must not steal the exit gesture — exit is an explicit control.

## Optional adjacent bundle (NOT in the decided scope; separate decision)
Part-A chat polish from the audit — per-code-block copy + horizontal scroll, visible copy affordance, mid-stream stall feedback, profile/skill-aware empty-state chips, the ~40-flow recomposition hotspot at the top of `ChatScreen`. Can be folded in or run separately.

## Doc updates at implementation time
- `docs/spec.md` + `docs/sphere-spec.md`: clean mode, the `AgentAvatar` seam, the pet spec.
- `user-docs/`: clean mode + custom pets guide.
- `CHANGELOG.md [Unreleased]`.
