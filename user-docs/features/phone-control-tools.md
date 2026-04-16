# Phone Control Tools

The hermes-relay plugin registers `android_*` tools into the hermes-agent gateway. These tools let the agent read, navigate, and act on your phone through the bridge channel.

## Visibility Gate

All tools except `android_setup` are gated behind a single requirement: `phone_connected=true`. If no phone is paired and connected via WebSocket, the tools are hidden from the agent's tool list entirely. Only `android_setup` (which configures bridge URL and pairing code) remains visible so the agent can guide you through pairing.

This is a single gate, not a cascading permission check. Downstream layers handle finer-grained errors with structured responses (see [Structured Errors](#structured-error-responses) below).

## Tool Reference

| Tool | Category | Sideload only | Description |
|------|----------|:---:|-------------|
| `android_ping` | Connectivity | | Check bridge liveness |
| `android_read_screen` | Observation | | Get accessibility tree of current screen |
| `android_tap` | Action | | Tap at coordinates or by node ID |
| `android_tap_text` | Action | | Tap element by visible text |
| `android_type` | Action | | Type text into focused input field |
| `android_swipe` | Action | | Swipe gesture (direction + distance) |
| `android_scroll` | Action | | Scroll in direction |
| `android_open_app` | Navigation | | Launch app by package name |
| `android_press_key` | Navigation | | Press hardware/software key (back, home, recents) |
| `android_screenshot` | Observation | | Capture screenshot as base64 |
| `android_wait` | Observation | | Wait for element to appear on screen |
| `android_get_apps` | Observation | | List installed apps |
| `android_current_app` | Observation | | Get foreground app package name |
| `android_setup` | Configuration | | Configure bridge URL and pairing code |
| `android_search_contacts` | Phone utility | Yes | Search contacts by name |
| `android_send_sms` | Phone utility | Yes | Send SMS via `SmsManager` |
| `android_call` | Phone utility | Yes | Dial a phone number |
| `android_location` | Phone utility | Yes | Last-known GPS location |
| `android_return_to_hermes` | Navigation | | Bring Hermes Relay back to foreground |

Additional tools are registered in `plugin/tools/android_tool.py` for advanced use cases: `android_find_nodes`, `android_long_press`, `android_drag`, `android_describe_node`, `android_macro`, `android_clipboard_read`, `android_clipboard_write`, `android_media`, `android_screen_hash`, `android_diff_screen`, `android_send_intent`, `android_broadcast`, `android_events`, `android_event_stream`. The `android_navigate` tool (vision-driven navigation) is registered separately in `plugin/tools/android_navigate.py`.

## Direct Dispatch Tools

Three phone-utility tools bypass UI automation entirely. Instead of driving the Messages app or Phone dialer through taps and types, they call Android system APIs directly:

- **`android_send_sms`** -- Sends via `SmsManager` with send-result confirmation. Never leaves the current foreground app. Requires a phone number as the `to` argument -- if the user gave a contact name, the agent should call `android_search_contacts` first to resolve it.
- **`android_call`** -- Places a call directly. Brings the Phone app to foreground in dialer mode. The agent should call `android_return_to_hermes` after placing the call.
- **`android_search_contacts`** -- Queries the device contact database by name. Returns structured results with phone numbers sorted by preference.

All three have on-device safety modals (the destructive-verb confirmation overlay) and return structured error responses on denial.

## `android_return_to_hermes`

Brings the Hermes Relay app back to the foreground. The agent should call this as the final step of any multi-app task (sending a text, opening Maps, taking a screenshot from another app) so the user sees the agent's reply in-context without manually switching back.

Key behaviors:

- **Exempt from master toggle** -- works even when the bridge is disabled, so the agent can always wrap up cleanly.
- **Short-circuits when already foreground** -- if Hermes is already the active app, the call is a no-op.
- **Works on both flavors** -- no sideload restriction.

## Trust Model

The on-device safety modal is the user's final checkpoint for destructive actions. The agent should **not** double-confirm in chat before dispatching -- that adds friction without adding safety, since the phone-side modal is the real gate.

When a direct dispatch tool opens a safety modal, the user taps Allow or Deny on their phone. The agent waits for the result.

## Denial Is Final

If a tool returns `error_code: user_denied`, the user has explicitly refused the action via the on-device modal. The agent **must not** retry the same intent through alternate paths:

- Do not open the Messages app and drive the UI to send the same text.
- Do not open the Phone dialer and tap the Call button for the same number.
- Do not use `android_tap_text` to press a "Send" button as a workaround.

The denial applies to the **intent**, not to the specific tool call. The agent should acknowledge the refusal and stop. If the user later sends a new instruction in chat, that counts as a fresh intent.

## Structured Error Responses

When a tool call fails, the response includes machine-readable fields the agent can act on:

| Field | Purpose |
|-------|---------|
| `error` | Human-readable error message |
| `error_code` | Machine-readable code for programmatic handling |
| `required_permission` | Which Android permission is missing (when applicable) |
| `required_action` | What the user needs to do to fix it |
| `flavor` | Which build flavor returned the error (when flavor-gated) |

### Error codes

| Code | Meaning |
|------|---------|
| `permission_denied` | A required Android runtime permission hasn't been granted |
| `service_unavailable` | The accessibility service isn't running |
| `user_denied` | User tapped Deny on the on-device safety modal |
| `bridge_disabled` | The bridge master toggle is off |
| `sideload_only` | This tool requires the sideload flavor |

## Contact Phone Format

`android_search_contacts` returns a structured phone list for each contact:

```json
{
  "phones": [
    {"number": "+15551234567", "type": "mobile", "label": ""},
    {"number": "+15559876543", "type": "work", "label": "Office"}
  ]
}
```

The list is pre-sorted by preference: **mobile > main > home > work > other**. Galaxy Watch entries are deprioritized via a label heuristic -- entries whose label contains wearable-related keywords sort after regular phone entries. The agent should use `phones[0]` unless the user specified a particular number type.
