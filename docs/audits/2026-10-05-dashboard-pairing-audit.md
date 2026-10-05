# Dashboard and CLI+UI pairing audit — 2026-10-05

## Scope and evidence

Reviewed plugin discovery, activation, frontend SDK use, Python backend mounting,
WebSocket admission, host terminal and slash-command pairing, CLI candidate
selection, and the management UI's URL-and-code form. Compared current Relay
integration code with unmodified upstream Hermes commit
`e473f5a9c976a0b5bc292aa415dae28c638a47c3`.

Rendered the committed dashboard bundle with that upstream's actual SDK registry,
host stylesheet, React 19.2.7 and Nous UI 0.18.2. Backend responses were synthetic;
no production pairing code, API key or session credential was used. The management UI
was rendered from its normal application entry point with demo data and an
isolated window-API adapter, including its configured 380×620 window size.
These are frontend checks, not installed-binary or second-computer certification.
The live authenticated dashboard could not be audited past its sign-in screen.

## Findings and disposition

| Finding | Evidence | Disposition |
|---|---|---|
| Legacy installer claims the dashboard is enabled after installing its manifest, without activating the user plugin. | `install.sh` only creates the symlink/toggles the manifest; upstream `_plugin_activated` requires `plugins.enabled` and respects `plugins.disabled`. | Installer now reports installed files and the explicit upstream activation command. Canonical installation already documents `--enable`. |
| Discovery rescan cannot mount a newly installed Python backend. | Upstream `rescan_dashboard_plugins` refreshes discovery only; `_mount_plugin_api_routes` runs when the Dashboard module initializes. | Installer and setup docs identify the Dashboard process restart requirement separately from discovery and browser refresh. |
| Default Android invites are advertised as suitable for CLI+UI. | Web pairing instructions offered the invite to Desktop; CLI filters all Dashboard ingress candidates before dialing because ticket authentication is unsupported. | Dialog now classifies supported direct/pinned routes; CLI errors and management UI explain recovery without weakening authentication. |
| Terminal output hides secondary connection routes. | `pair_command` minted a full signed endpoint list but passed only the top-level URL to `render_text_block`. | Text receipts now use the authoritative minted endpoint list and display each surface's protocol and effective port. Disabled optional API servers remain omitted. |
| Code-only host pairing supplies insufficient address information. | `/relay pair` returned only the code; `--register-code` labeled a loopback HTTP registration address as Relay. | Both now display a read-only address receipt; local registration is explicitly host-only, and the configured direct Relay listener is shown separately. |
| Management form cannot consume the trust-bearing invite described in older instructions. | Original `PairHostPage` and Rust `pair_host` accepted only a WebSocket URL and six-character code; signed invite import existed in the CLI. | The form now offers full invite import alongside manual entry. The Dashboard explicitly mints CLI+UI-compatible invites using its existing direct-route option. |

The current SDK's registration, Tabs render-function API, Button flags, Badge
tones, Dialog primitives and toast hooks remain compatible with the plugin.
Actual rendered Overview, Devices and pairing views confirmed these contracts.
The current upstream WebSocket guard owner is already handled by Relay's
feature detection. No evidence justified replacing the plugin's UI system or
loosening its fail-closed Dashboard admission.

The receiving CLI also lists every advertised Relay candidate before probing,
including protocols, effective ports and Dashboard ingress incompatibility.
Priority probing can stop at the first usable route without hiding alternatives.

Upstream's optional `plugins.isolation: host` is a separate compatibility
constraint: its Dashboard forwarder only registers HTTP routes and buffers
responses. Plugin WebSockets require the default `in_process` mode. This
requirement is now documented; no operator configuration was changed.

## Rendered flow

1. **Overview — healthy SDK render.** Existing theme tokens and host components
   render correctly; pairing and standard mobile setup remain separate actions.
2. **Devices — healthy empty state.** Standard connection setup and optional
   Relay pairing have separate instructions and controls.
3. **Pair new device — corrected guidance.** The full endpoint receipt remains
   available, now with explicit protocol/port labels and CLI+UI compatibility.
   Public and tailnet probe results in screenshots are fixture data, not network
   reachability evidence.
4. **Standard mobile setup — preserved boundary.** The setup QR carries only
   Dashboard identity and explains that sign-in and Relay pairing are separate.
5. **Management UI Pair host — invite import added.** The native-size frontend
   accepts the full snippet alongside direct address/code entry. It invokes the
   installed CLI using a bounded child-process environment value; the invite
   is omitted from command arguments and management activity records. Real
   native RPC and installation were not exercised. The installed CLI's exact
   environment handoff was separately exercised against a real local Relay.

Screenshot evidence is retained with the local audit artifacts. Source and
fixture checks cannot establish full accessibility compliance or physical
network reachability. Existing dialog semantics and focus ownership were
preserved; native-size and narrow-screen captures checked wrapping and scroll.

## Verification

- Focused pairing/registration/slash/backend Python tests passed.
- Three current-upstream conformance tests passed: ticket pairing/reconnect,
  subprotocol admission and ticket replay denial, loopback peer enforcement,
  and plugin/policy gates before ticket consumption.
- Dashboard receipt tests and committed IIFE bundle build passed.
- CLI pairing candidate tests and strict TypeScript check passed.
- Management UI TypeScript and production frontend build passed.
- Native Rust compile check and eight management contract tests passed.
- Three native clipboard tests passed for multiline/Unicode preservation,
  UTF-8 and byte-limit rejection, and the actual bounded PowerShell helper
  pipeline with a fixture data source. The operator's clipboard was not read
  or modified by those tests. The Paste command is restricted to the main
  management window and reads only after its button is clicked.
- Installed Windows CLI 0.4.0-beta.7 passed full-invite pairing through the
  child environment, persisted both candidates, omitted the one-use code from
  storage, and rejected reused/malformed invites without storing a pairing.
  The Node 24.14.0 development-runtime invocation aborted on both the patch and
  unchanged integration base; its diagnosis is recorded separately in TODO.
- Installer shell syntax, user-docs locale checks and public route-contract
  validation passed.
- No branch push, shared-service restart, deployment, native installer run, or
  production client pairing was performed.

The normal CLI+UI computer-connector flow retains Relay pairing and local
per-host permissions. No mandatory Dashboard sign-in or authentication redesign
is required for that role. Full signed-invite import is now implemented in the
management UI. Support for protected Dashboard ingress remains a separate
optional transport feature recorded in
[the project TODO](../project/TODO.md#optional-dashboard-transport-support-for-cliui),
not as blockers for the current Relay connection model.

## Upstream sources

- [SDK registry and UI contract](https://github.com/NousResearch/hermes-agent/blob/e473f5a9c976a0b5bc292aa415dae28c638a47c3/web/src/plugins/registry.ts)
- [Activation, discovery and rescan](https://github.com/NousResearch/hermes-agent/blob/e473f5a9c976a0b5bc292aa415dae28c638a47c3/hermes_cli/web_routers/dashboard_ui.py)
- [Backend mounting and hosted isolation](https://github.com/NousResearch/hermes-agent/blob/e473f5a9c976a0b5bc292aa415dae28c638a47c3/hermes_cli/web_server_dashboard.py)
- [WebSocket authentication and request policy](https://github.com/NousResearch/hermes-agent/blob/e473f5a9c976a0b5bc292aa415dae28c638a47c3/hermes_cli/web_server_chat.py)
