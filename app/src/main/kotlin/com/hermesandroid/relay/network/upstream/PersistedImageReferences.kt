package com.hermesandroid.relay.network.upstream

/**
 * Parsed form of upstream's persisted user-image directives.
 *
 * Only canonical, full-line `@image:<absolute-path>` values are recognized.
 * Unknown or malformed directives stay visible as text. Valid directives are
 * removed from the bubble so a host-local path is never exposed in the UI.
 */
internal data class PersistedImageReferences(
    val cleanedText: String,
    val paths: List<String>,
)

internal object PersistedImageReferenceParser {
    private const val MAX_INPUT_CHARS = 256 * 1024
    private const val MAX_PATH_CHARS = 2_048
    private const val MAX_ATTACHMENTS = 8

    private val imageExtensions = setOf(
        "avif",
        "bmp",
        "gif",
        "heic",
        "heif",
        "jpeg",
        "jpg",
        "png",
        "webp",
    )

    fun parse(content: String): PersistedImageReferences {
        if (content.isEmpty() || content.length > MAX_INPUT_CHARS || "@image:" !in content) {
            return PersistedImageReferences(content, emptyList())
        }

        val keptLines = ArrayList<String>()
        val paths = ArrayList<String>()

        for (line in content.lines()) {
            val path = parseDirectiveLine(line)
            if (path == null) {
                keptLines += line
            } else if (paths.size < MAX_ATTACHMENTS) {
                paths += path
            }
            // Recognized refs beyond the attachment cap are still removed:
            // exposing a server-local path is worse than omitting an excessive
            // attachment from a deliberately bounded gallery.
        }

        return PersistedImageReferences(
            cleanedText = keptLines.joinToString("\n").trim(),
            paths = paths,
        )
    }

    private fun parseDirectiveLine(line: String): String? {
        if (!line.startsWith("@image:")) return null
        val rawValue = line.removePrefix("@image:")
        if (rawValue.isEmpty() || rawValue.length > MAX_PATH_CHARS) return null

        val path = unwrapCanonicalValue(rawValue) ?: return null
        if (path.isBlank() || path.any { it == '\u0000' || it == '\r' || it == '\n' }) return null
        if (!isAbsolutePath(path) || !hasImageExtension(path)) return null
        return path
    }

    private fun unwrapCanonicalValue(value: String): String? {
        val first = value.first()
        if (first !in charArrayOf('`', '"', '\'')) {
            return value.takeIf { candidate -> candidate.none { it.isWhitespace() } }
        }
        if (value.length < 3 || value.last() != first) return null
        val inner = value.substring(1, value.lastIndex)
        return inner.takeIf { first !in it }
    }

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith("/") ||
            (
                path.length >= 3 &&
                    path[0].isLetter() &&
                    path[1] == ':' &&
                    (path[2] == '\\' || path[2] == '/')
                )

    private fun hasImageExtension(path: String): Boolean {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in imageExtensions
    }
}
