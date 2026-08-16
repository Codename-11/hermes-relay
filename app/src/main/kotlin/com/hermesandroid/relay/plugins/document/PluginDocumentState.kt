package com.hermesandroid.relay.plugins.document

data class PluginDocumentState(
    val values: Map<String, PluginValue> = emptyMap(),
) {
    operator fun get(key: String): PluginValue = values[key] ?: PluginValue.NullValue

    fun updated(key: String, value: PluginValue): PluginDocumentState =
        copy(values = values + (key to value))

    fun resolve(text: PluginText): String = when (text) {
        is PluginText.Literal -> text.value
        is PluginText.Binding -> this[text.key].displayString() ?: text.fallback
    }

    fun matches(condition: PluginCondition?): Boolean = when (condition) {
        null -> true
        is PluginCondition.Truthy -> this[condition.key].isTruthy()
        is PluginCondition.Equals -> this[condition.key] == condition.value
        is PluginCondition.Not -> !matches(condition.condition)
        is PluginCondition.All -> condition.conditions.all(::matches)
        is PluginCondition.Any -> condition.conditions.any(::matches)
    }

    companion object {
        fun from(document: PluginDocument): PluginDocumentState =
            PluginDocumentState(document.initialState)
    }
}

fun PluginValue.displayString(): String? = when (this) {
    is PluginValue.StringValue -> value
    is PluginValue.BooleanValue -> value.toString()
    is PluginValue.NumberValue -> value.toString().removeSuffix(".0")
    PluginValue.NullValue -> null
}

fun PluginValue.isTruthy(): Boolean = when (this) {
    is PluginValue.StringValue -> value.isNotBlank()
    is PluginValue.BooleanValue -> value
    is PluginValue.NumberValue -> value != 0.0 && !value.isNaN()
    PluginValue.NullValue -> false
}

sealed interface PluginDocumentValidation {
    data object Valid : PluginDocumentValidation
    data class Invalid(val errors: List<String>) : PluginDocumentValidation
}

/** Rejects pathological or ambiguous documents before they reach Compose. */
object PluginDocumentValidator {
    const val MAX_PAGES = 32
    const val MAX_ELEMENTS = 500
    const val MAX_DEPTH = 16
    const val MAX_CHILDREN = 100
    const val MAX_TEXT_LENGTH = 16_000
    const val MAX_STATE_ENTRIES = 250

    private val safeIdentifier = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun validate(document: PluginDocument): PluginDocumentValidation {
        val errors = mutableListOf<String>()
        if (document.schemaVersion != PluginDocument.CURRENT_SCHEMA_VERSION) {
            errors += "Unsupported schema version: ${document.schemaVersion}"
        }
        if (document.pages.isEmpty() || document.pages.size > MAX_PAGES) {
            errors += "Document must contain 1..$MAX_PAGES pages"
        }
        if (document.initialState.size > MAX_STATE_ENTRIES) {
            errors += "Initial state exceeds $MAX_STATE_ENTRIES entries"
        }

        val ids = mutableSetOf<String>()
        var elements = 0
        document.initialState.keys.forEach { validateIdentifier("state key", it, errors) }
        document.pages.forEach { page ->
            validateIdentifier("page id", page.id, errors)
            validateText(page.title, errors)
            walk(page.content, 1) { element, depth ->
                elements += 1
                if (depth > MAX_DEPTH) errors += "Element ${element.id} exceeds depth $MAX_DEPTH"
                validateIdentifier("element id", element.id, errors)
                if (!ids.add(element.id)) errors += "Duplicate element id: ${element.id}"
                validateElement(element, errors)
            }
        }
        if (elements > MAX_ELEMENTS) errors += "Document exceeds $MAX_ELEMENTS elements"
        return if (errors.isEmpty()) PluginDocumentValidation.Valid
        else PluginDocumentValidation.Invalid(errors.distinct())
    }

    private fun validateElement(element: PluginElement, errors: MutableList<String>) {
        validateCondition(element.visibleWhen, errors)
        when (element) {
            is PluginElement.Group -> if (element.children.size > MAX_CHILDREN) {
                errors += "Element ${element.id} exceeds $MAX_CHILDREN children"
            }
            is PluginElement.Card -> Unit
            is PluginElement.Text -> validateText(element.text, errors)
            is PluginElement.Badge -> validateText(element.text, errors)
            is PluginElement.Button -> {
                validateText(element.label, errors)
                validateIdentifier("action id", element.action.id, errors)
                element.action.arguments.keys.forEach { validateIdentifier("action argument", it, errors) }
                element.action.confirmation?.let { validateText(it, errors) }
                validateCondition(element.enabledWhen, errors)
            }
            is PluginElement.TextInput -> {
                validateText(element.label, errors)
                element.placeholder?.let { validateText(it, errors) }
                validateIdentifier("binding", element.binding, errors)
                if (element.maxLength !in 1..PluginElement.DEFAULT_INPUT_LIMIT) {
                    errors += "Input ${element.id} has an invalid maxLength"
                }
            }
            is PluginElement.Toggle -> {
                validateText(element.label, errors)
                validateIdentifier("binding", element.binding, errors)
                validateCondition(element.enabledWhen, errors)
            }
            is PluginElement.Progress -> {
                element.valueBinding?.let { validateIdentifier("binding", it, errors) }
                element.label?.let { validateText(it, errors) }
                if (element.value == null && element.valueBinding == null) {
                    errors += "Progress ${element.id} needs a value or binding"
                }
                if (element.value != null && (!element.value.isFinite() || element.value !in 0.0..1.0)) {
                    errors += "Progress ${element.id} value must be between 0 and 1"
                }
            }
            is PluginElement.Image -> {
                validateIdentifier("asset id", element.asset.id, errors)
                validateText(element.contentDescription, errors)
                if (!element.aspectRatio.isFinite() || element.aspectRatio !in 0.25f..4f) {
                    errors += "Image ${element.id} has an invalid aspect ratio"
                }
            }
            is PluginElement.Divider, is PluginElement.Spacer -> Unit
        }
    }

    private fun validateCondition(condition: PluginCondition?, errors: MutableList<String>) {
        when (condition) {
            null -> Unit
            is PluginCondition.Truthy -> validateIdentifier("condition key", condition.key, errors)
            is PluginCondition.Equals -> validateIdentifier("condition key", condition.key, errors)
            is PluginCondition.Not -> validateCondition(condition.condition, errors)
            is PluginCondition.All -> condition.conditions.forEach { validateCondition(it, errors) }
            is PluginCondition.Any -> condition.conditions.forEach { validateCondition(it, errors) }
        }
    }

    private fun validateText(text: PluginText, errors: MutableList<String>) {
        when (text) {
            is PluginText.Literal -> if (text.value.length > MAX_TEXT_LENGTH) {
                errors += "Text exceeds $MAX_TEXT_LENGTH characters"
            }
            is PluginText.Binding -> {
                validateIdentifier("text binding", text.key, errors)
                if (text.fallback.length > MAX_TEXT_LENGTH) {
                    errors += "Text fallback exceeds $MAX_TEXT_LENGTH characters"
                }
            }
        }
    }

    private fun validateIdentifier(label: String, value: String, errors: MutableList<String>) {
        if (!safeIdentifier.matches(value)) errors += "Invalid $label: $value"
    }

    private fun walk(
        element: PluginElement,
        depth: Int,
        visit: (PluginElement, Int) -> Unit,
    ) {
        visit(element, depth)
        when (element) {
            is PluginElement.Group -> element.children.forEach { walk(it, depth + 1, visit) }
            is PluginElement.Card -> walk(element.child, depth + 1, visit)
            else -> Unit
        }
    }
}
