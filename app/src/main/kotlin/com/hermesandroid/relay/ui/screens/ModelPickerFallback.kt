package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.network.upstream.ApiModelOption

/**
 * A provider catalog failure must not manufacture a model picker from every
 * installed bot's default. Gateway chat can show *actual* named API routes,
 * whereas Direct API mode may list the endpoint's advertised request ids.
 */
internal fun fallbackModelPickerOptions(
    apiModelOptions: List<ApiModelOption>,
    selectedModel: String?,
    isGatewayTransport: Boolean,
): List<ApiModelOption> {
    val advertised = if (isGatewayTransport) {
        apiModelOptions.filter { it.routeDetail != null }
    } else {
        apiModelOptions
    }
    val selected = selectedModel?.trim()?.takeIf(String::isNotEmpty)?.let(::ApiModelOption)
    return (advertised + listOfNotNull(selected)).distinctBy { it.id }
}
