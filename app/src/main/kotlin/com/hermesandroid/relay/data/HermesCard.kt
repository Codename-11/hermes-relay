package com.hermesandroid.relay.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A rich content card emitted inline in an assistant message via the
 * `CARD:{json}` line marker. Parsed by
 * [com.hermesandroid.relay.network.handlers.ChatHandler] and rendered by
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
     */
    val id: String? = null,
) {
    object BuiltInTypes {
        const val SKILL_RESULT = "skill_result"
        const val APPROVAL_REQUEST = "approval_request"
        const val LINK_PREVIEW = "link_preview"
        const val CALENDAR_EVENT = "calendar_event"
        const val WEATHER = "weather"
    }

    object Accents {
        const val INFO = "info"
        const val SUCCESS = "success"
        const val WARNING = "warning"
        const val DANGER = "danger"
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
 * [com.hermesandroid.relay.network.handlers.ChatHandler.markCardDispatchesSynced]
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
     * [com.hermesandroid.relay.network.handlers.ChatHandler.markCardDispatchesSynced]
     * once the API client has accepted the request that carried this
     * dispatch's synthetic message pair. Once true, the dispatch is
     * excluded from future
     * [com.hermesandroid.relay.viewmodel.CardDispatchSyncBuilder.buildSyntheticMessages]
     * passes.
     */
    val syncedToServer: Boolean = false,
)
