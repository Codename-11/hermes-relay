package com.hermesandroid.relay.voice

/**
 * === PHASE3-voice-intents (sideload flavor): keyword classifier ===
 *
 * Lightweight regex/keyword classifier for phone-control intents in voice
 * mode. Deliberately **not** an LLM — we want deterministic, fast, offline,
 * and easy to tune. Anything that doesn't match a known pattern falls
 * through to normal chat (the caller returns [IntentResult.NotApplicable]).
 *
 * ## Recognized intents (v1)
 *
 * Patterns are tried in order and the first match wins. All matching is
 * case-insensitive. Leading filler words ("hey", "okay", "please", "can you",
 * "could you", "would you") are stripped before matching.
 *
 * | Intent | Example phrases | Parsed out |
 * |---|---|---|
 * | [VoiceIntent.SendSms] | `text Sam I'll be 10 min late` / `send a message to mom saying on my way` / `text my wife hey` | `contact`, `body` |
 * | [VoiceIntent.OpenApp] | `open camera` / `launch maps` / `open the spotify app` / `start gmail` | `appName` |
 * | [VoiceIntent.Tap] | `tap send` / `press the ok button` / `click on continue` | `target` |
 * | [VoiceIntent.Scroll] | `scroll down` / `scroll up` / `scroll to the top` / `scroll to the bottom` | `direction` |
 * | [VoiceIntent.Back] | `go back` / `navigate back` / `back` | — |
 * | [VoiceIntent.Home] | `press home` / `go home` / `home screen` | — |
 *
 * ## Tuning
 *
 * Each regex is intentionally simple. False negatives (classifier says
 * "not applicable" when the user meant a phone action) are **preferred**
 * over false positives (classifier confidently fires bridge tools for a
 * chat question). Utterances like "can you text me when you're done?"
 * should NOT match SendSms because the parser wouldn't get a real contact
 * / body pair. The SendSms regex requires a "saying" separator or a body
 * after the contact name; one-off references without a body fall through.
 *
 * To add a new intent: add a case to [VoiceIntent], add a regex + parser
 * in [classify], and document it here.
 */
internal sealed class VoiceIntent {
    data class SendSms(val contact: String, val body: String) : VoiceIntent()
    data class OpenApp(val appName: String) : VoiceIntent()
    data class Tap(val target: String) : VoiceIntent()
    data class Scroll(val direction: ScrollDirection) : VoiceIntent()
    data object Back : VoiceIntent()
    data object Home : VoiceIntent()

    enum class ScrollDirection { UP, DOWN, TOP, BOTTOM }
}

internal object VoiceIntentClassifier {

    // Filler words that commonly prefix a command. Stripped before matching
    // so "hey could you please text Sam I'll be late" still fires SendSms.
    private val FILLERS = Regex(
        "^(?:hey(?:\\s+hermes)?|okay|ok|um+|uh+|please|" +
            "can\\s+you|could\\s+you|would\\s+you|will\\s+you)\\s+",
        RegexOption.IGNORE_CASE,
    )

    // text X saying Y  |  text X: Y  |  send a message to X saying Y  |  text X Y
    // Ordered: the "saying"/":" variants match before the no-separator form
    // so we don't greedily swallow the body into the contact field.
    private val SMS_WITH_SEPARATOR = Regex(
        "^(?:text|send\\s+(?:a\\s+)?(?:message|text|sms)\\s+to)\\s+" +
            "(?<contact>[\\w'\\- ]+?)\\s+" +
            "(?:saying|that|:\\s*|,\\s*)\\s*" +
            "(?<body>.+)$",
        RegexOption.IGNORE_CASE,
    )

    // Fallback: "text <contact> <body>" where <contact> is one or two words.
    // Keeps the contact parse conservative so "text my wife" without a body
    // falls through to NotApplicable instead of sending an empty SMS.
    private val SMS_NO_SEPARATOR = Regex(
        "^text\\s+(?<contact>(?:my\\s+)?[\\w'\\-]+(?:\\s+[\\w'\\-]+)?)\\s+(?<body>.{2,})$",
        RegexOption.IGNORE_CASE,
    )

    // open / launch / start <app>  — with optional "the" and "app" suffix.
    private val OPEN_APP = Regex(
        "^(?:open|launch|start)\\s+(?:the\\s+)?(?<app>[\\w\\- ]+?)(?:\\s+app)?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    // tap / press / click / click on <target>
    private val TAP = Regex(
        "^(?:tap|press|click(?:\\s+on)?)\\s+(?:the\\s+)?(?<target>[\\w\\- ]+?)(?:\\s+button)?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private val SCROLL = Regex(
        "^scroll\\s+(?:to\\s+the\\s+)?(?<dir>up|down|top|bottom)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private val BACK = Regex(
        "^(?:go\\s+)?(?:navigate\\s+)?back\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private val HOME = Regex(
        "^(?:press\\s+)?(?:go\\s+)?home(?:\\s+screen)?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Run all patterns against [rawText]. Returns the first match or null
     * if nothing looks like a phone-control intent.
     *
     * Contract: false negatives are preferred over false positives.
     */
    fun classify(rawText: String): VoiceIntent? {
        val text = rawText.trim().replace(FILLERS, "").trim().trimEnd('.', '!', '?')
        if (text.isBlank()) return null

        // Ordered: match the most specific patterns first so "text" doesn't
        // accidentally match an OpenApp for "open text" (it wouldn't anyway
        // because the open regex requires the verb first, but keep the
        // ordering defensive).
        SMS_WITH_SEPARATOR.matchEntire(text)?.let { m ->
            val contact = m.groups["contact"]?.value?.trim().orEmpty()
            val body = m.groups["body"]?.value?.trim().orEmpty()
            if (contact.isNotEmpty() && body.isNotEmpty()) {
                return VoiceIntent.SendSms(contact = contact, body = body)
            }
        }
        SMS_NO_SEPARATOR.matchEntire(text)?.let { m ->
            val contact = m.groups["contact"]?.value?.trim().orEmpty()
            val body = m.groups["body"]?.value?.trim().orEmpty()
            if (contact.isNotEmpty() && body.length >= 2) {
                return VoiceIntent.SendSms(contact = contact, body = body)
            }
        }
        OPEN_APP.matchEntire(text)?.let { m ->
            val app = m.groups["app"]?.value?.trim().orEmpty()
            if (app.isNotEmpty()) return VoiceIntent.OpenApp(app)
        }
        TAP.matchEntire(text)?.let { m ->
            val target = m.groups["target"]?.value?.trim().orEmpty()
            if (target.isNotEmpty()) return VoiceIntent.Tap(target)
        }
        SCROLL.matchEntire(text)?.let { m ->
            val dir = when (m.groups["dir"]?.value?.lowercase()) {
                "up" -> VoiceIntent.ScrollDirection.UP
                "down" -> VoiceIntent.ScrollDirection.DOWN
                "top" -> VoiceIntent.ScrollDirection.TOP
                "bottom" -> VoiceIntent.ScrollDirection.BOTTOM
                else -> null
            }
            if (dir != null) return VoiceIntent.Scroll(dir)
        }
        if (BACK.matchEntire(text) != null) return VoiceIntent.Back
        if (HOME.matchEntire(text) != null) return VoiceIntent.Home

        return null
    }
}

// === END PHASE3-voice-intents (sideload classifier) ===
