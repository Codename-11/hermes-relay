package com.hermesandroid.relay.plugins.document

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A declarative Android surface supplied by a Hermes plugin.
 *
 * Documents contain no executable code, URLs, Android intents, or gateway calls. The host
 * renders the bounded element vocabulary and decides how (or whether) to handle actions and
 * asset references.
 */
@Serializable
data class PluginDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val pages: List<PluginPage>,
    val initialState: Map<String, PluginValue> = emptyMap(),
    /** Host-owned revision for generated pages; ordinary installed plugins may omit it. */
    @SerialName("host_revision") val hostRevision: Int? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class PluginPage(
    val id: String,
    val title: PluginText,
    val content: PluginElement,
)

@Serializable
sealed interface PluginValue {
    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : PluginValue

    @Serializable
    @SerialName("boolean")
    data class BooleanValue(val value: Boolean) : PluginValue

    @Serializable
    @SerialName("number")
    data class NumberValue(val value: Double) : PluginValue

    @Serializable
    @SerialName("null")
    data object NullValue : PluginValue
}

@Serializable
sealed interface PluginText {
    @Serializable
    @SerialName("literal")
    data class Literal(val value: String) : PluginText

    @Serializable
    @SerialName("binding")
    data class Binding(
        val key: String,
        val fallback: String = "",
    ) : PluginText
}

@Serializable
sealed interface PluginCondition {
    @Serializable
    @SerialName("truthy")
    data class Truthy(val key: String) : PluginCondition

    @Serializable
    @SerialName("equals")
    data class Equals(val key: String, val value: PluginValue) : PluginCondition

    @Serializable
    @SerialName("not")
    data class Not(val condition: PluginCondition) : PluginCondition

    @Serializable
    @SerialName("all")
    data class All(val conditions: List<PluginCondition>) : PluginCondition

    @Serializable
    @SerialName("any")
    data class Any(val conditions: List<PluginCondition>) : PluginCondition
}

@Serializable
data class PluginAnimation(
    val enter: PluginAnimationKind = PluginAnimationKind.NONE,
    val change: PluginAnimationKind = PluginAnimationKind.NONE,
    val speed: PluginAnimationSpeed = PluginAnimationSpeed.NORMAL,
)

@Serializable
enum class PluginAnimationKind {
    NONE,
    FADE,
    SCALE,
    SLIDE_VERTICAL,
    HIGHLIGHT,
}

@Serializable
enum class PluginAnimationSpeed { FAST, NORMAL, SLOW }

@Serializable
data class PluginAction(
    val id: String,
    val arguments: Map<String, PluginValue> = emptyMap(),
    val confirmation: PluginText? = null,
    val request: PluginActionRequest? = null,
)

@Serializable
data class PluginActionRequest(
    val method: String = "POST",
    val path: String,
)

@Serializable
sealed interface PluginElement {
    val id: String
    val visibleWhen: PluginCondition?
    val animation: PluginAnimation

    @Serializable
    @SerialName("group")
    data class Group(
        override val id: String,
        val direction: PluginDirection = PluginDirection.COLUMN,
        val children: List<PluginElement>,
        val spacing: PluginSpacing = PluginSpacing.MEDIUM,
        override val visibleWhen: PluginCondition? = null,
        override val animation: PluginAnimation = PluginAnimation(),
    ) : PluginElement

    @Serializable
    @SerialName("card")
    data class Card(
        override val id: String,
        val child: PluginElement,
        override val visibleWhen: PluginCondition? = null,
        override val animation: PluginAnimation = PluginAnimation(),
    ) : PluginElement

    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        val text: PluginText,
        val style: PluginTextStyle = PluginTextStyle.BODY,
        override val visibleWhen: PluginCondition? = null,
        override val animation: PluginAnimation = PluginAnimation(),
    ) : PluginElement

    @Serializable
    @SerialName("badge")
    data class Badge(
        override val id: String,
        val text: PluginText,
        val tone: PluginTone = PluginTone.NEUTRAL,
        override val visibleWhen: PluginCondition? = null,
        override val animation: PluginAnimation = PluginAnimation(),
    ) : PluginElement

    @Serializable
    @SerialName("button")
    data class Button(
        override val id: String,
        val label: PluginText,
        val action: PluginAction,
        val style: PluginButtonStyle = PluginButtonStyle.PRIMARY,
        val enabledWhen: PluginCondition? = null,
        override val visibleWhen: PluginCondition? = null,
        override val animation: PluginAnimation = PluginAnimation(),
    ) : PluginElement

    @Serializable
    @SerialName("text_input")
    data class TextInput(
        override val id: String,
        val label: PluginText,
        val binding: String,
        val placeholder: PluginText? = null,
        val maxLength: Int = DEFAULT_INPUT_LIMIT,
        override val visibleWhen: PluginCondition? = null,
        override val animation: PluginAnimation = PluginAnimation(),
    ) : PluginElement

    @Serializable
    @SerialName("toggle")
    data class Toggle(
        override val id: String,
        val label: PluginText,
        val binding: String,
        val enabledWhen: PluginCondition? = null,
        override val visibleWhen: PluginCondition? = null,
        override val animation: PluginAnimation = PluginAnimation(),
    ) : PluginElement

    @Serializable
    @SerialName("progress")
    data class Progress(
        override val id: String,
        val valueBinding: String? = null,
        val value: Double? = null,
        val label: PluginText? = null,
        override val visibleWhen: PluginCondition? = null,
        override val animation: PluginAnimation = PluginAnimation(),
    ) : PluginElement

    @Serializable
    @SerialName("image")
    data class Image(
        override val id: String,
        val asset: PluginAssetReference,
        val contentDescription: PluginText,
        val aspectRatio: Float = 16f / 9f,
        override val visibleWhen: PluginCondition? = null,
        override val animation: PluginAnimation = PluginAnimation(),
    ) : PluginElement

    @Serializable
    @SerialName("divider")
    data class Divider(
        override val id: String,
        override val visibleWhen: PluginCondition? = null,
        override val animation: PluginAnimation = PluginAnimation(),
    ) : PluginElement

    @Serializable
    @SerialName("spacer")
    data class Spacer(
        override val id: String,
        val size: PluginSpacing = PluginSpacing.MEDIUM,
        override val visibleWhen: PluginCondition? = null,
        override val animation: PluginAnimation = PluginAnimation(),
    ) : PluginElement

    companion object {
        const val DEFAULT_INPUT_LIMIT = 2_000
    }
}

/** An opaque, plugin-scoped asset id. The host resolves it; documents cannot supply URLs. */
@Serializable
data class PluginAssetReference(val id: String)

@Serializable
enum class PluginDirection { ROW, COLUMN }

@Serializable
enum class PluginSpacing { NONE, SMALL, MEDIUM, LARGE }

@Serializable
enum class PluginTextStyle { BODY, LABEL, TITLE, HEADLINE }

@Serializable
enum class PluginTone { NEUTRAL, INFO, SUCCESS, WARNING, DANGER }

@Serializable
enum class PluginButtonStyle { PRIMARY, SECONDARY, TEXT }
