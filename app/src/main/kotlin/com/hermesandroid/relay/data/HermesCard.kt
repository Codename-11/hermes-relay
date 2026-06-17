package com.hermesandroid.relay.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A rich content card emitted inline in an assistant message via the
 * `CARD:{json}` line marker. Parsed by
 * [com.hermesandroid.relay.network.upstream.ChatHandler] and rendered by
 * [com.hermesandroid.relay.ui.components.HermesCardBubble].
 *
 * The marker lives in the text stream alongside `MEDIA:...` for the same
 * reason: it works unchanged across every streaming endpoint we support
 * (`/v1/runs`, `/api/sessions/{id}/chat/stream`, `/v1/chat/completions`)
 * without a server-side schema change. When upstream gains structured card
 * events, the parser can fan out — the [HermesCard] model stays.
 *
 * Unknown [type] values fall back to a generic title+fields render so the
 * surface doesn't break when the agent emits a card the phone build hasn't
 * seen. Unknown [accent] / action [style] values degrade to their defaults
 * the same way.
 *
 * Example (single-line in practice):
 * ```
 * CARD:{"type":"approval_request","title":"Run shell command?",
 *       "body":"`rm -rf /tmp/cache`","accent":"warning",
 *       "actions":[{"label":"Allow","value":"/approve","style":"primary"},
 *                  {"label":"Deny","value":"/deny","style":"danger"}]}
 * ```
 */
@Serializable
data class HermesCard(
    /**
     * Card dispatcher key. Known built-ins are defined in [BuiltInTypes];
     * unknown values render via the generic fallback.
     */
    val type: String,
    val title: String? = null,
    val subtitle: String? = null,
    /** Markdown-rendered body text. Appears between the header and fields. */
    val body: String? = null,
    /**
     * Semantic accent — maps to a colorScheme token in the renderer.
     * Valid: `info` (default), `success`, `warning`, `danger`.
     */
    val accent: String? = null,
    val fields: List<HermesCardField> = emptyList(),
    val actions: List<HermesCardAction> = emptyList(),
    /** Small muted text at the bottom of the card. */
    val footer: String? = null,
    /**
     * Optional stable id from the agent. Used by the renderer to track
     * which action (if any) has been dispatched, so the same card reloaded
     * from session history doesn't re-prompt. Falls back to the card's
     * position in the message when null.
     *
     * For the gateway ask types this is the ask's `request_id` (or
     * `approval-<sid>-<ts>` for approval, which has no request id) — the
     * dispatch tracker keys answer-once semantics off it.
     */
    val id: String? = null,
    /**
     * Interactive input slot rendered between [fields] and [actions] —
     * the answer surface for the gateway ask cards (`ask.clarify` choice
     * chips + free text, `ask.secret` masked field, `ask.sudo`
     * hold-to-confirm). Null for every plain card. Submissions flow
     * through the renderer's `onInputSubmit(cardKey, value)` callback and
     * collapse the card via the same [HermesCardDispatch] list as button
     * actions.
     */
    val input: HermesCardInput? = null,
) {
    object BuiltInTypes {
        const val SKILL_RESULT = "skill_result"
        const val APPROVAL_REQUEST = "approval_request"
        const val LINK_PREVIEW = "link_preview"
        const val CALENDAR_EVENT = "calendar_event"
        const val WEATHER = "weather"

        // Gateway interactive asks (desktop-parity wave). Locally built
        // from clarify/approval/sudo/secret request events — never parsed
        // out of the text stream.
        const val ASK_APPROVAL = "ask.approval"
        const val ASK_CLARIFY = "ask.clarify"
        const val ASK_SUDO = "ask.sudo"
        const val ASK_SECRET = "ask.secret"
    }

    object Accents {
        const val INFO = "info"
        const val SUCCESS = "success"
        const val WARNING = "warning"
        const val DANGER = "danger"
    }
}

/**
 * Interactive input slot on a [HermesCard]. The flags compose rather than
 * branch — a sudo ask can be `masked + holdToConfirm` (password field whose
 * submit is the 650ms press-fill button), while clarify is
 * `choices + allowFreeText` and secret is `masked` alone.
 *
 * Security contract: when [masked] is true the submitted value is a secret.
 * It must never be echoed into chat content, logged, or synced via
 * CardDispatchSyncBuilder — record [SECRET_PROVIDED_STAMP] as the dispatch's
 * actionValue instead of the real value. The renderer masks the collapse
 * stamp for masked inputs regardless, but the dispatch record itself is
 * persisted and synced, so the caller must not put the secret there.
 */
@Serializable
data class HermesCardInput(
    /**
     * Input kind — one of [Kinds]. Drives which composite the renderer
     * builds; unknown kinds degrade to a plain free-text field so newer
     * asks still get an answer surface.
     */
    val kind: String,
    /** Quick-answer chips (clarify). Empty = no chip row. */
    val choices: List<String> = emptyList(),
    /** Render the inline free-text mini field under the chips. */
    val allowFreeText: Boolean = false,
    /** Password-style field: masked glyphs + reveal toggle (secret/sudo). */
    val masked: Boolean = false,
    /** Submit is a 650ms hold-to-confirm press-fill instead of a tap (sudo). */
    val holdToConfirm: Boolean = false,
    /**
     * Wall-clock expiry for timed asks (sudo 120s, clarify/secret 300s).
     * The renderer shows a countdown footer (Amber under 30s) and
     * self-collapses to "Expired — not granted" past it. Null = no timeout
     * (approval is session-scoped).
     */
    val expiresAtMillis: Long? = null,
) {
    object Kinds {
        const val CHOICE = "choice"
        const val TEXT = "text"
        const val SECRET = "secret"
        const val CONFIRM = "confirm"
    }

    companion object {
        /**
         * Sentinel recorded as [HermesCardDispatch.actionValue] when a
         * [masked] input is submitted. The real secret value goes only to
         * the ask-respond RPC — never into the dispatch record, chat
         * content, or session sync.
         */
        const val SECRET_PROVIDED_STAMP = "secret-provided"

        /**
         * Value submitted by a bare hold-to-confirm (no text field) — the
         * sudo/approval "yes" that carries no payload of its own.
         */
        const val CONFIRM_VALUE = "confirm"
    }
}

/**
 * A label/value row inside a card. [value] is rendered as markdown so the
 * agent can embed emphasis, inline code, or links.
 */
@Serializable
data class HermesCardField(
    val label: String,
    val value: String,
)

/**
 * A tappable action on a card.
 *
 * When the user taps the button, the ViewModel reads [mode] to decide how
 * to dispatch [value]:
 *  - [Modes.SEND_TEXT] (default): sends [value] as a new user message, so
 *    the agent sees it in its next turn. This is how approval_request's
 *    "Allow" / "Deny" replies flow back to the LLM.
 *  - [Modes.SLASH_COMMAND]: runs [value] as a slash command (e.g.
 *    `/approve` or `/clear`). The leading `/` is stripped if present.
 *  - [Modes.OPEN_URL]: opens [value] in an external browser — used by
 *    [HermesCard.BuiltInTypes.LINK_PREVIEW]'s "Open" button.
 *
 * [style] picks a button color from the colorScheme:
 *  - `primary` — filled, colorScheme.primary
 *  - `secondary` (default) — outlined, onSurfaceVariant
 *  - `danger` — outlined, colorScheme.error
 */
@Serializable
data class HermesCardAction(
    val label: String,
    val value: String,
    val style: String? = null,
    val mode: String? = null,
) {
    object Styles {
        const val PRIMARY = "primary"
        const val SECONDARY = "secondary"
        const val DANGER = "danger"
    }

    object Modes {
        const val SEND_TEXT = "send_text"
        const val SLASH_COMMAND = "slash_command"
        const val OPEN_URL = "open_url"

        /**
         * Ask-card answer: dispatch [value] straight to the gateway
         * ask-respond RPC (clarify/sudo/secret/approval.respond), never
         * as chat text. Dispatches in this mode are EXCLUDED from
         * [com.hermesandroid.relay.viewmodel.CardDispatchSyncBuilder] —
         * the server already absorbed the answer through the blocking
         * ask, and for secrets the value must not enter session memory.
         */
        const val SUBMIT_ASK = "submit_ask"
    }
}

/**
 * Local-only tracking of card action dispatch, stored alongside
 * [ChatMessage.cards] so a session reload from server history doesn't lose
 * "I already approved this" state. Keyed by the card's id (or its index
 * position as a fallback) + action value.
 *
 * Persisted to server-side session memory via
 * [com.hermesandroid.relay.viewmodel.CardDispatchSyncBuilder], modeled on
 * [com.hermesandroid.relay.voice.VoiceIntentSyncBuilder]: on the next
 * chat send, unsynced dispatches materialize as OpenAI-format `assistant`
 * (with structured `tool_calls`) + `tool` message pairs under a synthetic
 * `hermes_card_action` tool name, splicing them into the session history
 * the LLM sees. After the API client takes ownership of the request,
 * [com.hermesandroid.relay.network.upstream.ChatHandler.markCardDispatchesSynced]
 * flips [syncedToServer] so subsequent turns don't re-send the same
 * trace.
 */
@Serializable
data class HermesCardDispatch(
    val cardKey: String,
    val actionValue: String,
    val timestamp: Long,
    /**
     * Idempotency guard for the server-side session sync path.
     * Flipped to true by
     * [com.hermesandroid.relay.network.upstream.ChatHandler.markCardDispatchesSynced]
     * once the API client has accepted the request that carried this
     * dispatch's synthetic message pair. Once true, the dispatch is
     * excluded from future
     * [com.hermesandroid.relay.viewmodel.CardDispatchSyncBuilder.buildSyntheticMessages]
     * passes.
     */
    val syncedToServer: Boolean = false,
)
