package com.hermesandroid.relay.network.upstream

internal const val SESSION_LIST_PAGE_LIMIT = 100
internal const val SESSION_LIST_WINDOW_LIMIT = 200

internal data class SessionListPage(
    val limit: Int,
    val offset: Int,
)

internal fun sessionListPages(requestedLimit: Int): List<SessionListPage> {
    val window = requestedLimit.coerceIn(1, SESSION_LIST_WINDOW_LIMIT)
    return buildList {
        var offset = 0
        while (offset < window) {
            add(
                SessionListPage(
                    limit = minOf(SESSION_LIST_PAGE_LIMIT, window - offset),
                    offset = offset,
                ),
            )
            offset += SESSION_LIST_PAGE_LIMIT
        }
    }
}
