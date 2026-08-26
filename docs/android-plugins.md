# Android Plugins

Hermes Android exposes opt-in, plugin-provided pages through the authenticated
upstream Dashboard connection. The product name is **Plugins**. The implementation
borrows the contribution model from the official Hermes Desktop Plugin SDK while
using an Android-specific execution boundary.

## Security boundary

Android never downloads or evaluates plugin JavaScript, Kotlin, bytecode, WebViews,
or arbitrary Compose code. A backend plugin supplies a bounded, versioned JSON
document and the app renders it with host-owned Compose components. Documents cannot
provide network URLs, Android intents, filesystem paths, gateway calls, secrets, or
authentication material.

The Dashboard plugin catalog establishes plugin identity. All follow-up reads and
writes stay below `/api/plugins/<plugin-id>/...`; the client rejects traversal,
queries, fragments, absolute URLs, and cross-plugin paths. Enablement and grants are
stored locally per connection, effective profile, and plugin.

Read-only pages are available after the user enables a plugin. Mutating actions also
require the local `plugin.api.write` grant. Actions with a confirmation string require
an additional per-use confirmation in the app. The app never gives plugins direct
access to session tokens or cookies.

## Discovery contract

1. Read the authenticated catalog at `GET /api/dashboard/plugins`.
2. Probe catalog entries that expose an API at
   `GET /api/plugins/<plugin-id>/mobile/manifest`.
3. Accept only manifests whose `id` matches the catalog identity, whose
   `schema_version` is supported, and whose `min_host_api` is compatible.
4. Load enabled page contributions through their plugin-scoped document endpoint.

The v1 manifest shape is:

```json
{
  "schema_version": 1,
  "id": "weather",
  "display_name": "Weather",
  "description": "Local forecasts and alerts",
  "version": "1.0.0",
  "min_host_api": 1,
  "default_enabled": false,
  "requested_capabilities": [
    { "id": "plugin.api.write", "reason": "Save alert preferences" }
  ],
  "contributions": [
    {
      "id": "forecast",
      "surface": "page",
      "title": "Forecast",
      "document": { "method": "GET", "path": "mobile/pages/forecast" }
    }
  ]
}
```

## Declarative page surface

Schema v1 supports complete scrollable pages composed from groups, cards, text,
badges, buttons, text inputs, toggles, progress indicators, opaque plugin-scoped
images, dividers, and spacers. Literal values and state bindings drive conditional
visibility and enabled state. Host-owned fade, scale, vertical-slide, and highlight
transitions make pages reactive and animated without allowing executable UI code.
They become immediate static updates when system animations are disabled or touch
exploration is active.

Button actions may call a relative endpoint in their own plugin namespace. Write
requests receive both the action's declared arguments and the page's current bound
state, then the host reloads the document so the plugin remains authoritative. The
host validates document size, nesting, identifiers, bindings, text lengths, animation
values, and asset references before rendering.

Future schema versions can add elements or contribution surfaces deliberately. An
unknown v1 element is rejected rather than interpreted as code or rendered in a
WebView.

## Relationship to chat cards

Existing model-generated `CARD:{json}` content remains presentation-only chat output.
It does not identify a plugin, inherit plugin grants, or gain access to plugin action
endpoints. Plugin authority always begins with the authenticated Dashboard catalog and
the matching mobile manifest.

## Live agent-created pages

When the optional Hermes-Relay plugin is installed, the active Hermes agent can use
the `relay_plugin_draft` tool to create or replace a generated declarative page. The
tool accepts the same bounded schema as Android, stores JSON atomically below
`HERMES_HOME/mobile-plugins`, and rejects every `action.request`. Generated previews
therefore cannot reach Relay management APIs or acquire executable backend behavior.
The contribution ID `git` is reserved for the Relay plugin's native Git workspace;
generated drafts cannot shadow or duplicate that route.

Android presents that reserved contribution as a first-class native surface rather
than a generic declarative page. When the live plugin API confirms Git is available,
Chat can show a compact branch/change rail and a dot-only context action; **Chat →
Show Git workspace in Chat** hides those two Chat affordances without disabling the
workspace. The full native workspace remains reachable from Settings and Plugins and
owns repository/branch selection, diffs, staging, confirmed destructive actions,
commits, and remotes. Session `git_repo_root`/`cwd` metadata selects an exact matching
repository when possible; ambiguous catalogs require an explicit user choice. Mobile
discovery alone is not treated as runtime readiness: a missing Git API route renders a
retryable unavailable state instead of exposing the raw Dashboard error.

The Relay mobile manifest exposes drafts as preview pages under the authenticated
`hermes-relay` plugin namespace. Android polls the catalog every five seconds while
the Plugins hub is visible and polls a visible generated page every five seconds.
No catalog or page polling continues after that surface leaves the foreground.

Publishing and persistent removal are user decisions:

1. The agent creates or updates a draft and may list it.
2. Android displays it as **Draft preview**.
3. The user enables the Relay Plugins surface and its scoped write grant.
4. **Keep** publishes the exact current draft; **Remove** requires confirmation.
5. The agent-side publish/remove tools return `approval_required` instead of applying
   persistent mutations themselves. Only a session-lifecycle draft may be removed
   directly by the agent because that operation is local and reversible by redrafting.

The first store version is host-local and uses lifecycle labels, but does not isolate
draft visibility between simultaneous Hermes sessions. True task-private previews,
profile/connection publication precedence, revision-bound approvals, and push catalog
invalidation remain future protocol work. The current polling path is deliberately
small and requires no upstream fork.
