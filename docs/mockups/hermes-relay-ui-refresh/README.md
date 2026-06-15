# Hermes Relay UI Refresh Mockups

Created as a design-only pass. These files do not change the Android app.

## Preview

Open `index.html` in a browser:

```powershell
Start-Process "C:\Users\Bailey\Desktop\Open-Projects\hermes-relay\docs\mockups\hermes-relay-ui-refresh\index.html"
```

## Direction

The proposed layout is a "Relay cockpit":

- Chat remains the default surface and keeps the sessions drawer.
- Manage remains primary for the standard install path.
- Bridge becomes a first-class mode instead of being buried in Settings.
- Terminal is always one tap away from Chat, Manage, and Bridge.
- Settings moves behind the agent/profile area and system menu instead of occupying a fat bottom tab.
- The bottom of the app becomes a thin live status strip, closer to official Hermes Desktop, rather than a large navigation bar.

## Inputs

Local app observations:

- `RelayApp.kt` currently registers Chat, Manage, Settings as bottom nav items, while Terminal and Bridge are routed but reached from secondary surfaces.
- `ChatScreen.kt` already has the useful pieces: session drawer, agent header, endpoint chip, command palette, voice, and attachment composer.
- `SettingsScreen.kt` owns profile inspection, connections, Hermes management, chat/voice/media/appearance, and a "Power tools" section containing Terminal and Bridge.
- `BridgeCoreScreen.kt` already has the right Bridge grouping: Connections, Terminal, Voice, Notification companion, Media, and Relay sessions.
- `AgentInfoSheet` already consolidates Profile, Personality, and Connection. The mockup turns that into the main profile-management affordance.

Official Hermes references:

- [Hermes Agent home](https://hermes-agent.nousresearch.com/) for the stark black/white/electric-blue and ASCII/glyph language.
- [Hermes Desktop page](https://hermes-agent.nousresearch.com/desktop) for the electric-blue duotone visual system and feature ordering.
- [Desktop App docs](https://hermes-agent.nousresearch.com/docs/user-guide/desktop) for the chat-first layout, left sidebar, bottom status bar, right preview rail, management panes, command palette, sessions, and profile concepts.
- [NousResearch/hermes-agent desktop routes](https://github.com/NousResearch/hermes-agent/blob/main/apps/desktop/src/app/routes.ts) for the official top-level management surfaces: settings, command center, skills, messaging, artifacts, cron, profiles, and agents.

## Proposed Implementation Shape

1. Replace the large `NavigationBar` in `RelayApp.kt` with:
   - a compact top `RelayModeStrip` for Chat, Manage, Bridge;
   - a persistent Terminal icon shortcut in the app chrome;
   - a thin bottom `RelayStatusStrip` for connection, model/profile, and safety state.

2. Make Bridge primary:
   - keep `BridgeCoreScreen.kt` as the standard-install Bridge landing;
   - for sideload builds, keep Device Control under Bridge as a protected advanced section;
   - keep Settings links for discoverability, but stop making Settings the only path.

3. Promote profile management:
   - use `AgentInfoSheet` as the profile switcher and connection switcher;
   - add a clear route from that sheet to `ProfileInspectorScreen`;
   - surface default/profile-isolated API state in the header and status strip.

4. Improve sessions:
   - add search, pinned/archived groupings, and a profile filter to `SessionDrawerContent`;
   - preserve the current drawer model so Chat remains uncluttered.

5. Official-Hermes styling translation:
   - keep Hermes Relay's navy/purple base;
   - introduce electric blue as a hard accent, not a full repaint;
   - add sparse ASCII/glyph texture and sharper dividers;
   - use compact mono metadata for connection/session/model state.

## Files to Touch Later

- `app/src/main/kotlin/com/hermesandroid/relay/ui/RelayApp.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/ChatScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/SettingsScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/screens/BridgeCoreScreen.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/SessionDrawer.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/components/ConnectionInfoSheet.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/theme/Theme.kt`
- `app/src/main/kotlin/com/hermesandroid/relay/ui/theme/Type.kt`
