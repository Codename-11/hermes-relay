# Voice Intents

Spoken commands that route directly to bridge actions instead of going through the chat LLM. Say "text Sam I'll be late" and the phone resolves the contact, shows a 5-second countdown, and sends the SMS -- no round-trip to the server needed.

## How It Works

When voice mode transcribes your speech, the text hits a regex-based keyword classifier before it reaches the chat pipeline. If the classifier recognizes a phone-control pattern (send a text, open an app, tap a button, go back, go home), it dispatches the action through the bridge channel locally on the device. If nothing matches, the transcription falls through to the LLM as a normal chat message with an immediate "Thinking..." indicator.

The classifier is intentionally simple. It prefers false negatives (treating a command as chat) over false positives (firing a bridge action when you meant to ask a question). Ambiguous utterances like "can you text me when you're done?" won't accidentally trigger an SMS.

## Sideload Only

Voice intents are compiled out of the Google Play build. The `googlePlay` flavor ships a `NoopVoiceBridgeIntentHandler` that always returns "not applicable", so every utterance goes to chat. The `sideload` flavor ships the real classifier and handler.

## Supported Intents

Patterns are tried in order. The first match wins. All matching is case-insensitive. Filler words ("hey", "okay", "please", "can you", "could you") are stripped before matching.

| Intent | Example phrases | What happens |
|--------|----------------|--------------|
| **Send SMS** | "text Sam I'll be late", "send Hannah a text saying hi", "message Mom saying on my way" | Resolves contact, 5s countdown, safety modal, direct `SmsManager` send |
| **Open App** | "open Chrome", "launch Spotify", "start Gmail" | Fuzzy-matches app name (exact > prefix > contains), launches via `PackageManager` |
| **Tap** | "tap Send", "press OK", "click Continue" | Taps the first matching UI element by visible text |
| **Back** | "go back", "navigate back", "back" | Presses system Back |
| **Home** | "go home", "home screen", "press home" | Presses system Home |

Scroll was a voice intent in earlier versions but was removed in v0.4.0. Nobody says "scroll down" aloud in practice, and the regex was maintenance debt for a near-zero-usage intent. The server-side `android_scroll` tool still works through the normal LLM tool-calling path.

## Confirmation Countdown

Destructive intents (currently just Send SMS) go through a 5-second confirmation window before dispatch:

1. The classifier resolves the contact and phone number locally on the device.
2. TTS speaks a preview: "About to text Sam at 555-1234: I'll be late. Say cancel to stop."
3. A visual countdown progress bar appears on the voice overlay.
4. After 5 seconds, the action fires through the bridge safety pipeline (including the destructive-verb confirmation modal).
5. If you say "cancel", "stop", or "never mind" during the countdown, the action is cancelled. The voice state machine intercepts these phrases while a destructive action is pending and routes them to `cancelPending()` instead of treating them as new utterances.

Safe intents (open app, tap, back, home) execute immediately with no countdown.

## Post-Dispatch Feedback

After an action completes (or fails), two things happen:

- **TTS speaks the result** -- "SMS sent to Sam" or "I couldn't find a contact called Sam."
- **A chat bubble appears** showing the outcome with structured details (contact name, resolved number, app package, match tier).

This keeps the voice session conversational -- you hear what happened without needing to look at the screen.

## Contact Resolution

### By name

The classifier extracts a contact name from the utterance and resolves it against the device's contact database via the accessibility service. If no match is found, TTS says "I couldn't find a contact called X" and no action dispatches.

### Phone number literals

If the "contact" field looks like a phone number ("text +1 555 1234 saying hi"), the classifier skips contact lookup entirely. The number is normalized (spaces, dashes, and parentheses stripped) and passed directly to the `/send_sms` bridge route.

### Multiple contacts

When the contact search returns more than one match and the top result isn't an exact name match, the spoken preview adds a disambiguation hint: "Found 3 contacts matching Sam. Using Sam Wilson." The alphabetically-first match is used. A multi-turn voice picker is planned for a future release.

### Multiple phone numbers

When the selected contact has more than one phone number on file, numbers are sorted by type preference: **mobile > main > home > work > other**. Galaxy Watch entries are deprioritized via a label heuristic (entries with labels containing "Watch" or similar wearable identifiers sort after phone entries). The spoken preview qualifies which number was picked: "About to text Sam Wilson Mobile at 555-1234."

## Error Handling

The classifier handles several failure states gracefully -- each returns a spoken TTS message instead of silently failing:

| Condition | Spoken response |
|-----------|----------------|
| Contacts permission not granted | "I need Contacts permission to look up that number. Tap the Bridge tab to grant it." |
| SMS permission not granted | Permission-specific message directing you to system Settings |
| Accessibility service not running | "I can't reach the bridge service. Make sure Hermes accessibility is enabled in Settings." |
| Contact not found | "I couldn't find a contact called X." |
| Contact has no phone number | "X doesn't have a phone number on file." |
| App not found | "I couldn't find an app called X." |

## Fall-Through to Chat

Any utterance the classifier doesn't recognize goes to the LLM as a normal chat message. The voice mode immediately shows "Thinking..." state and proceeds with the standard send-and-stream flow. There's no visible delay or indication that classification happened -- from the user's perspective, the utterance simply becomes a chat turn.
