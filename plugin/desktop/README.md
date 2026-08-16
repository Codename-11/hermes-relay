# Hermes-Relay plugin for official Hermes Desktop

This is the Desktop half of the unified `hermes-relay` plugin package. Official
Hermes Desktop discovers it at `plugins/hermes-relay/desktop/plugin.js`, beside
the existing Python and web Dashboard halves.

The plugin is opt-in and never opens itself. Enabling or loading it only adds
three labeled native entry points: **Relay** in the sidebar and status bar, and
**Hermes Relay: Open** in the command palette. One of those explicit actions
registers, restores, and focuses the management pane. The pane uses the host's
native tab close and drag/dock affordances.

All backend calls use the official profile-aware `ctx.rest()` namespace and the
existing `dashboard/plugin_api.py` routes. The pane keeps no server state, does
not poll, does not notify, and does not perform network work while closed.

## SDK baseline

Implemented against upstream Hermes Desktop source
`0296c7d8aa1d1cdb7bec8522031ca6b14eadc604` (2026-08-14), using only:

- `PluginContext.register`, `registerMany`, `onDispose`, `rest`, `os`, and
  `panes.reveal`
- pane, sidebar, status-bar, and palette contribution areas
- the exported React/query/i18n/UI-kit surface

The SDK currently has no public API for programmatic pane move coordinates or
for agent-driven focus of contributed pane IDs. Users move/dock the pane with
native drag targets; the plugin does not use private layout or Electron hooks.

## Test

```bash
cd plugin/desktop
npm test
```
