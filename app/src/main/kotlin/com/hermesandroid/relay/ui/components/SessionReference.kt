package com.hermesandroid.relay.ui.components

data class SessionReference(val profile: String, val sessionId: String) {
    val label: String
        get() = "$profile · ${sessionId.takeLast(10)}"
}

private val SESSION_REFERENCE = Regex("@session:([A-Za-z0-9_.-]{1,64})/([A-Za-z0-9_.:-]{1,160})")

/** Find session references outside fenced and inline code. */
internal fun parseSessionReferences(markdown: String): List<SessionReference> {
    val visible = buildString(markdown.length) {
        var fenced = false
        markdown.lineSequence().forEach { line ->
            if (line.trimStart().startsWith("```")) {
                fenced = !fenced
                appendLine()
            } else if (fenced) {
                appendLine()
            } else {
                var inline = false
                line.forEach { char ->
                    if (char == '`') inline = !inline
                    append(if (inline || char == '`') ' ' else char)
                }
                appendLine()
            }
        }
    }
    return SESSION_REFERENCE.findAll(visible)
        .map {
            SessionReference(
                it.groupValues[1],
                it.groupValues[2].trimEnd('.', ',', ';', '!', '?', ')', ']', '}'),
            )
        }
        .filter { it.sessionId.isNotBlank() }
        .distinct()
        .take(16)
        .toList()
}
