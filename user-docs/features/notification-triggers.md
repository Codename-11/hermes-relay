# Notification triggers

Notification triggers make the Notification companion proactive without letting it act silently in other apps.

## Opt-in and kill switch

Triggers are off by default. Enable them from **Settings → Notifications → Event triggers (MVP)** after granting Android Notification access.

The kill switch on the same screen pauses every trigger immediately without deleting rules or the activity log.

## MVP rule schema

Rules are stored locally on the phone in Android DataStore file `notification_triggers`.

The first schema is intentionally small:

| Field | Meaning |
|-------|---------|
| `id` | Stable rule id |
| `label` | Human-readable label shown in the activity log |
| `enabled` | Per-rule on/off flag |
| `app_package` | Exact Android package match, e.g. `com.slack` |
| `title_contains` | Optional case-insensitive title substring |
| `text_contains` | Optional case-insensitive text/subtext substring |
| `action` | MVP supports `ask_me` |
| `require_confirmation` | Reserved for future action types; `ask_me` is safe automatic prompting |

At least one filter is required. The app refuses empty match-all rules so a first experiment cannot spam every notification on the phone.

## Safe trigger path

When a matching notification arrives, Hermes-Relay records an activity-log entry and posts a local Android notification: **“Ask Hermes about this?”** Tapping it opens chat so the user can decide what to ask.

This path uses the same notification metadata already shared by the Notification companion: app package, title, text, subtext, timestamp, and notification key. It does not send a new Hermes prompt automatically and does not reply in another app.

## Activity log

The settings screen shows the latest 25 trigger matches with rule label, package, title/text preview, timestamp, and result. This is local app-private state in DataStore, not a relay-server audit table.

## Confirmation policy

Allowed to run automatically after explicit opt-in:

- Matching a notification rule.
- Writing the local activity log.
- Posting a local “Ask Hermes?” prompt.

Requires explicit confirmation first:

- Sending a message or replying in another app.
- Routing notification content to another person, channel, or service.
- Starting bridge gestures, typing, raw intents, SMS/MMS, calls, purchases, deletes, or any other action with side effects outside Hermes-Relay.
- Any future summarize/route action that would send notification text to a model or external destination without a foreground user gesture.

The MVP chooses `ask_me` first because it proves event-trigger plumbing while keeping the user in the loop before content leaves the local prompt/log path.
