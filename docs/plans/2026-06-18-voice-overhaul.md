# Voice Mode Overhaul — Design Spec

**Status:** Design spec — ready for orchestration after the attachment branch merges to `dev`
**Date:** 2026-06-18
**Owner surface:** Android app (voice overlay, voice settings, voice prefs), relay (already done)
**Goal path:** `docs/plans/2026-06-18-voice-overhaul.md`
**Goal:** Fix the voice overlay UX, make profiles carry voice settings (mode/provider/voice-model) via the Relay path, and clean up the voice-settings page. Standard path stays vanilla and honestly labels voice as host-global.

---

## Bottom Line

- **Per-profile voice is mostly already built.** The relay reads `?profile=` on every voice route, persists per-profile `voice_output:`/`realtime_voice:` to `~/.hermes/profiles/<name>/config.yaml`, and `RelayVoiceClient` already sends `?profile=`. The only missing piece is **client-side**: `VoicePreferences` uses global un-namespaced DataStore keys, so engine/route/enhanced picks leak across profiles. Namespace them by profile and per-profile voice works on the Relay path with zero server work.
- **Standard path can't carry per-profile voice** (upstream `/api/profiles/*` has no voice field; `/api/audio/*` is host-global). Ship via Relay, label Standard as "global voice," file the upstream PR as the long-term path.
- **The overlay fixes are low-effort, high-value, all `[Client]`.**

---

## Verified Context (from the 2026-06-18 audit)

- Active profile: per-connection `ProfileSelectionStore` (`selected_profile_<connectionId>`), in-memory `ProfileController._selectedProfile` (`viewmodel/connection/ProfileController.kt:97-189`); session IDs scoped per (connection, profile, transport) in `data/ProfileSessionStore.kt:70-134`; per-session config reset on switch already correct (commit `0800dde`, `ChatViewModel.activateGatewayProfile()` :690-711, `switchProfileContext()` :1511-1522).
- **Voice prefs are global:** `data/VoicePreferences.kt:120-133` — `KEY_ENGINE_MODE`, `KEY_AUDIO_ROUTE`, `KEY_INTERACTION_MODE`, `KEY_ENH_VOICE/MODEL/AUDIO_TAGS/PERSONA/LANGUAGE` are single un-namespaced keys.
- **Relay per-profile voice plumbing is complete:** `plugin/relay/voice.py:409`, `voice_output.py:127/193/252-255`, `realtime_voice.py:101/164/211-214`, scoping in `plugin/relay/profile_voice.py` (`request_profile()` :25-32, `resolve_profile_voice_scope()` :35-90, `save_profile_voice_section()` :213-243). Client already targets it via `RelayVoiceClient.urlWithProfile()`.
- **Upstream `/api/profiles/*`** exposes only `soul`, `description`, `model` (`hermes_cli/web_server.py:9438-9498`) — no voice field.
- **Overlay** is `ui/components/VoiceModeOverlay.kt`, hosted at `ChatScreen.kt:2620-2627` inside the content `Box` of the `ModalNavigationDrawer` (`ChatScreen.kt:1222`, default `gesturesEnabled=true`).

---

## Work Packages (orchestration-ready; file ownership keeps workers conflict-free)

### WP-V1 — Voice overlay fixes  `[Client]`  (no deps)
**Owns:** `ui/components/VoiceModeOverlay.kt`, `ui/screens/ChatScreen.kt`, `ui/RelayApp.kt`
1. **Click-through (4b):** add a gesture-consuming scrim to the overlay root Box (`VoiceModeOverlay.kt:162-166`) via `pointerInput(Unit){ detectTapGestures{} }`; and pass `gesturesEnabled = !voiceUiState.voiceMode` to `ModalNavigationDrawer` (`ChatScreen.kt:1222`). (Consider promoting the overlay to a `Dialog` with `usePlatformDefaultWidth=false` if cleaner.)
2. **Topbar wrapping (4a):** convert the expanded 4×`weight(1f)` pill Row (`VoiceModeOverlay.kt:790-800`) to a `FlowRow`; trim the collapsed header (`:702-758`) to icon + "Voice" + single weighted title (maxLines=1) + chevron + close; move StatusPill + CompactVoiceMicButton into the expanded body.
3. **Settings link (4c):** add `onNavigateToVoiceSettings: () -> Unit = {}` to `ChatScreen`'s signature (`:357-380`), wire it at `RelayApp.kt:1415` to `navController.navigate(Screen.VoiceSettings.route)`, thread into the overlay as `onOpenSettings`, add a gear button in the expanded controls (`:802-827`); `exitVoiceMode()`/minimize before navigating.

### WP-V2 — Per-profile voice (client) `[Client]` (no deps; relay half done)
**Owns:** `data/VoicePreferences.kt`, `viewmodel/VoiceViewModel.kt`
1. Namespace the voice DataStore keys by profile — mirror `ProfileSelectionStore`'s `_<connectionId>` keying, extend to `_<connectionId>_<profile>`. Prioritize engine mode + route + enhanced overrides; interaction-mode/silence-threshold may stay global ergonomic prefs (document the split).
2. Layer per-profile values over global defaults (override map) so unset profiles fall back cleanly.
3. `VoiceViewModel.onProfileChanged` (`:1058-1065`): re-read the per-profile voice prefs so the VM's engine/route reflect the new profile before the next turn.

### WP-V3 — Voice settings IA cleanup `[Client]` (dep: WP-V2 for the per-profile section)
**Owns:** `ui/screens/VoiceSettingsScreen.kt` (+ optional new `VoiceSettingsViewModel`)
1. Hoist Profile + Scope into a single "Voice scope" banner under `VoiceProfileSummaryCard` (`:380`); drop the 4–5 duplicate per-card Profile/Scope rows.
2. Merge the overlapping "Enhanced Voice" (`:741`) and "Hermes Chat + Voice Output" (`:874`) TTS cards into one "Text-to-Speech" card with "Streaming output" vs "Basic synthesize fallback" sub-groups (or gate Enhanced behind an Advanced expander).
3. Move dead/disabled controls (Auto-TTS `:672`, STT language radios `:1843-1847`) behind one "Experimental / coming soon" expander or remove.
4. Add a "Voice for this profile" section under the summary card that hosts WP-V2's profile-scoped engine/route/enhanced toggles and shows the resolved scope.
5. Extract each `SectionCard` to its own `@Composable` and lift the relay-config fetch into a VM (the file is ~2600 lines).

### WP-V4 — Honest Standard labeling `[Client]` (small; can fold into V3)
- On Standard, make the scope badge say "global voice" and keep the existing "speaks through your Hermes server's configured TTS… Pair Relay to pick providers" copy. Related multi-profile honesty items from the audit: **1.3** (SSE session list isn't profile-scoped — don't present it as such) and **1.4** (effort chip "unknown" after switch — label "default"); include if cheap, else file as follow-ups.

**Concurrency:** V1 ∥ V2 (disjoint files). V3 after V2 (per-profile section needs namespaced prefs). V4 folds into V3. Max concurrent 2.

---

## Deferred / upstream
- **Per-profile voice on Standard** — needs an upstream PR adding a voice section to the profile config + making `/api/audio/*` honor `?profile=`/active profile. File it; don't block. `[Standard]`

## Doc updates at implementation time
- `docs/spec.md` voice section: document per-profile voice scope (Relay) + the overlay being a true modal.
- `user-docs/` voice guide: per-profile voice (Relay), Standard = host-global.
- `CHANGELOG.md [Unreleased]`.
