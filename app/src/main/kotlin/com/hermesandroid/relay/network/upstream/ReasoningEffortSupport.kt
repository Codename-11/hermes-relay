package com.hermesandroid.relay.network.upstream

/** Canonical reasoning-effort values accepted by upstream Hermes. */
object ReasoningEfforts {
    const val DEFAULT = "medium"

    val canonical: List<String> =
        listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    fun normalize(value: String?): String {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return normalized.takeIf { it in canonical } ?: DEFAULT
    }
}

/** Provider/model capability row advertised by upstream `model.options`. */
data class GatewayModelCapabilities(
    /** Legacy capability flag. Null means the server did not advertise support either way. */
    val reasoning: Boolean? = null,
    /** Exact selectable values on newer servers. Null means use the canonical compatibility list. */
    val reasoningEfforts: List<String>? = null,
    /** Explicit false means the advertised list is advisory rather than selectable. */
    val reasoningEffortsExact: Boolean? = null,
)

data class ReasoningEffortAvailability(
    val supported: Boolean?,
    val choices: List<String>,
    val exact: Boolean,
) {
    fun accepts(effort: String): Boolean = supported != false && (!exact || effort in choices)
}

/** Exact provider/model identity to which a confirmed effort belongs. */
data class ReasoningEffortIdentity(val provider: String, val model: String)

/**
 * Resolve the active provider/model's advertised reasoning contract.
 *
 * Older servers expose either no capability entry or only `reasoning: true`;
 * those receive the full canonical compatibility list. An explicit false
 * disables the control. A `reasoning_efforts` array is authoritative.
 */
fun resolveReasoningEffortAvailability(
    providers: List<GatewayModelProvider>,
    provider: String?,
    model: String?,
    relayCapabilities: Map<ReasoningEffortIdentity, GatewayModelCapabilities> = emptyMap(),
): ReasoningEffortAvailability {
    val normalizedProvider = provider?.trim().orEmpty()
    val normalizedModel = model?.trim().orEmpty()
    if (normalizedProvider.isEmpty() || normalizedModel.isEmpty()) {
        return ReasoningEffortAvailability(
            supported = null,
            choices = ReasoningEfforts.canonical,
            exact = false,
        )
    }
    val providerRow = providers.firstOrNull {
        it.slug.equals(normalizedProvider, ignoreCase = true)
    }

    val upstream = providerRow?.capabilities?.get(normalizedModel)
        ?: providerRow?.capabilities?.entries?.firstOrNull {
            it.key.equals(normalizedModel, ignoreCase = true)
        }?.value
    val identity = ReasoningEffortIdentity(
        provider = normalizedProvider.lowercase(),
        model = normalizedModel,
    )
    val relay = relayCapabilities[identity]

    fun exactAvailability(capabilities: GatewayModelCapabilities): ReasoningEffortAvailability {
        val choices = capabilities.reasoningEfforts.orEmpty()
            .map { it.trim().lowercase() }
            .filter { it in ReasoningEfforts.canonical }
            .distinct()
        return ReasoningEffortAvailability(
            supported = capabilities.reasoning ?: choices.isNotEmpty(),
            choices = choices,
            exact = true,
        )
    }

    // Contract precedence: authoritative upstream, authoritative Relay overlay,
    // explicit upstream suppression, then compatibility fallback.
    if (upstream?.reasoningEfforts != null && upstream.reasoningEffortsExact == true) {
        return exactAvailability(upstream)
    }
    if (relay?.reasoningEfforts != null && relay.reasoningEffortsExact == true) {
        return exactAvailability(relay)
    }
    if (upstream?.reasoning == false) {
        return ReasoningEffortAvailability(supported = false, choices = emptyList(), exact = false)
    }

    return ReasoningEffortAvailability(
        supported = upstream?.reasoning,
        choices = ReasoningEfforts.canonical,
        exact = false,
    )
}
