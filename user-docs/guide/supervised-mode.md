---
title: Supervised Mode
description: Configure a parent-controlled, profile-pinned Hermes-Relay Android experience
---

# Supervised Mode

::: warning Physical certification pending
Supervised Mode is implemented in the Android client, but it has not completed
physical managed-device certification. Treat it as experimental and do not call
an installation child-ready until the managed-device checks below pass.
:::

Supervised Mode is a parent-controlled, restricted view of Hermes-Relay for
Android. It is intended for a parent or guardian who has already created and
reviewed a suitably restricted Hermes profile and wants the phone app to expose
only an approved set of chat features.

It is a client-interface control, not a child account or a server security
boundary. The selected Hermes profile still controls the agent's prompt, model,
tools, provider credentials, content behavior, and server-side data.

## Before enabling it

Prepare the Hermes profile first. At minimum, review its:

- identity and system instructions;
- model and provider safety settings;
- enabled skills, tools, and external services;
- memory, files, schedules, and existing sessions;
- voice and image-generation providers;
- retention and parental-review expectations.

Supervised Mode cannot make an unrestricted profile safe by hiding controls on
the phone. Ordinary prose can still cause the configured agent to use whatever
server-side capabilities that profile has.

## Set it up

From full Android Settings, the parent:

1. Open **Settings → Advanced → Supervised Mode** for the active Hermes
   Connection.
2. Choose one existing named profile.
3. Choose either an app-specific six-digit parent PIN or a parent password of
   at least eight characters. The PIN uses the app keypad; passwords use the
   normal keyboard and password-manager flow. This is separate from the phone's screen lock.
4. Save the one-time six-word recovery phrase somewhere the supervised user
   cannot access. You may share it to a parent-only destination; delete the
   message or saved copy from this phone afterward.
5. Select the allowed features and any stricter attachment or history limits.
6. Choose a visibility preset or customize what appears in Chat.
7. Review the summary, then verify the parent credential to enable the mode.

The app returns to the pinned profile's Chat screen. If the Connection or
profile is unavailable, the restricted client shows a recovery state
without falling back to another profile or exposing full Settings.

## The everyday experience

Chat should look like ordinary Hermes-Relay Chat. There is no persistent
Supervised Mode banner consuming conversation space. The agent name and avatar
remain the primary identity, with a small connection state when permitted.

The existing Settings button opens **Restricted Settings**, which contains only
approved preferences. A clearly labelled **Parent access** row asks for the
app-specific parent PIN or password before any parent controls or full
application settings appear.

Restricted Settings may include:

- a supervised-only theme, text size, language, and haptics;
- parent-approved pet display and, when allowed, a phone-local profile icon and
  chat background;
- accessibility preferences;
- message presentation and sensitive-media blur;
- harmless playback or interaction preferences when voice is allowed;
- Help and About;
- the locked Parent access row.

Connections, profiles, Manage, model controls, personalities, reasoning,
approvals, tools, plugins, Terminal, TUI, Bridge, Device Control, notification
companion, diagnostics, logs, files, credentials, developer options, Relay
management, and other sessions are not shown.

The command palette, slash autocomplete, server command catalog, and command
action cards are also absent. Messages whose first non-whitespace character is
`/` are rejected by the restricted client. Approved outcomes such as New chat
and Cancel remain normal, explicit buttons. If the agent requests approval, a
secret, clarification, or elevated access, the restricted client denies or
skips that request and shows a short notice. A parent can retry the task later
from the full client after authentication.

## Allowed features

The parent chooses capabilities independently. The proposed controls are:

| Capability | Suggested default | Effect when disabled |
|---|---:|---|
| Text chat | On | Required for the restricted chat experience |
| New chat | On | Removes the new-conversation action |
| Cancel reply | On | Removes Stop while a reply is running |
| Steer reply | On | Queues or disables mid-reply input instead |
| Attachments | Parent choice | Removes pickers, camera/share intake, paste-to-file, and restored attachment drafts |
| Standard voice | Parent choice | Removes recording, voice intents, and voice preferences |
| Generated media | On | Hides generated-image viewing and related actions |
| Save/share media | Off | Keeps permitted media view-only inside the app |
| Copy replies | On | Removes copy actions |
| Retry | On | Removes retry/regenerate actions |
| Quote/reply | On | Removes quote/reply actions |
| Edit and resend | Off | Prevents rewriting earlier prompts from the client |
| Session history | Parent choice | Limits the pinned profile to the current or approved conversations |
| Session actions | Off | Individually allows pin, rename, archive, share, and delete for visible history |

Attachments are a general capability, not a one-image rule. When enabled, the
normal supported attachment flow and app limits apply unless the parent chooses
a stricter maximum size or permitted-type policy. When disabled, every Android
entry point must be removed or rejected consistently, including share intents
and a draft restored after process death.

Standard voice uses the existing host-side voice configuration. Provider
credentials stay on the Hermes host and are not exposed in Restricted Settings.

The supervised theme is stored separately from the parent app theme and applies
only while the restricted root is locked. Pet display is parent-controlled.
Profile-icon and background changes can be enabled independently for the
supervised user; the authenticated parent retains those controls either way.

Session actions use a separate allowlist. The parent can allow all, allow none,
or choose individual actions. Copying technical session identifiers, browsing
other profiles, Relay Threads, and drawer customization remain unavailable.
Delete continues to require confirmation.

Generated media is limited by display policy, not by a claim that Android can
prove how the server created it. Parents may allow viewing while disabling save
and share. Ordinary remote links, files, and unsupported media retain the app's
normal safety behavior.

## What appears in Chat

Visibility controls affect presentation only. They never suppress an error,
safety notice, parent-action state, or connection failure that requires
attention.

### Simple (recommended)

- Shows the agent name and avatar.
- Shows generic **Connected**, **Working**, and **Reconnecting** states.
- Hides model, profile, provider, route, context, token usage, reasoning, and
  tool details.
- Keeps the header and composer visually quiet.

### Transparent

Adds parent-approved timestamps, bounded usage or context information, and
safe activity labels. It still does not reveal tool arguments, tool results,
host paths, credentials, or administration surfaces.

### Custom

Lets the parent control individual surfaces, including:

- model name and profile name;
- connection state and route identity;
- timestamps, context, and token usage;
- generic work status, tool names, and tool detail;
- reasoning visibility;
- message and media actions.

Model and profile names default off for a new policy. The agent's friendly name
and avatar provide the normal identity in the Simple preset.

## Parent access and relocking

Enabling, changing, or ending Supervised Mode requires the app-specific parent
PIN or password. Parent access relocks when its authenticated task closes, after
the configured inactivity period, when the app backgrounds, or after process
recreation. Failure delays persist across app restart.

The credential is global to this Android app installation, not scoped to one
Connection. Changing it or resetting it with the current recovery phrase rotates
the phrase, which is shown only once. If the credential record is missing
or damaged, Supervised Mode fails closed. A legacy installation that was already
enabled before app-specific parent credentials existed must reset local app
data, reconnect, and configure the mode again; it does not silently trust the
current Android user. That reset does not delete server-owned Hermes history.

While parent access is unlocked, **Remove parent credential** is available in
the parent controls even if the recovery phrase has been lost. Confirming it
disables Supervised Mode for every Connection and removes the app-wide PIN or
password and recovery verifier. Pinned profiles, capability toggles, appearance,
visibility, session controls, and relock settings are preserved. Server sessions
and history are preserved.

If both the parent credential and recovery phrase are lost, use Android
**Settings → Apps → Hermes-Relay → Storage → Clear data**. This also removes
local Connections, sign-ins, preferences, and caches, but does not delete
server-owned Hermes sessions. Uninstall/reinstall is not the documented escape
hatch because Android may restore backed-up local app state.

The app stores salted PBKDF2 verifiers, not the parent password or recovery phrase,
and applies persisted attempt delays. Prefer a strong password: a six-digit PIN
still has limited resistance if a privileged attacker copies the app-private
data and guesses offline. Stock Android cannot create parent-only biometric
enrollment for one app or tell the app which enrolled fingerprint or face was
used, so device biometrics are not accepted as parent identity.

The restricted root is restored before the first interactive screen. Deep
links, notification actions, shortcuts, saved back stacks, and share intents
must not provide a route around it. A missing or unreadable policy fails closed
to restricted recovery instead of opening full Settings.

Ending the mode may clear local drafts, pending attachments, and supervised
media caches according to the parent's choice. It does not automatically delete
Hermes sessions or history stored on the server. Parents review or delete that
history through their normal authenticated Hermes interface.

## Optional Relay visibility

If the Android client is paired with the optional Relay plugin, it may identify
itself with a client-reported **Supervised** tag and a short, non-sensitive
capability summary. The Relay UI can then make the device easy to recognize and
can revoke its paired Relay session through the normal paired-device controls.

The tag is informational. Relay does not enforce the Android policy, pin the
Hermes profile, filter direct Dashboard/Gateway chat, or certify that the client
is unmodified. Revoking the Relay session disables Relay-backed access for that
pairing; it does not remotely end an Android-only mode or revoke an independent
Dashboard sign-in. Supervised Mode does not require Relay.

## Limits of protection

Supervised Mode cannot control:

- another Hermes client or a modified Android build;
- someone with direct access to the Hermes server or parent credentials;
- tools, files, services, and provider behavior enabled in the selected profile;
- server-side session retention or provider data handling;
- the developmental suitability or factual accuracy of model output;
- Android behavior outside the Hermes-Relay app.

The experimental Supervised Mode and parent-authentication screens currently use
canonical English in every app locale pending fluent review of the complete
security and recovery wording. Do not assume those screens are localized.

Use it alongside a restrictive Hermes profile, parental supervision, Android
parental or enterprise controls where appropriate, and regular review of the
profile and its conversations.

## Certification requirement

The feature should not be described as child-ready until the exact Android
build passes automated policy and navigation tests plus physical testing on a
managed/restricted Android device. Certification must cover authentication,
relocking, restart and offline recovery, process death, deep links,
notifications, share intents, attachments, voice, session ownership, Relay
tagging/revocation, and attempts to escape the restricted interface.

See [Profiles](/features/profiles) for the server-owned identity model and
[Chat](/guide/chat) for the full, unrestricted interface.
