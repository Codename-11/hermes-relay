package com.hermesandroid.relay.data

/**
 * Curated, offline sample conversation for **Demo mode** — the zero-setup,
 * zero-network "Try the demo" path surfaced on the Connect screen.
 *
 * Why this exists: Hermes-Relay is a client for a *user-run* Hermes server, so
 * a fresh install with no connection has nothing to show. Google Play review
 * (and any curious first-run user) hits an empty Connect wall. Demo mode feeds
 * this canned transcript through the **real** chat pipeline
 * ([com.hermesandroid.relay.network.upstream.ChatHandler] →
 * [com.hermesandroid.relay.viewmodel.ChatViewModel] → `ChatScreen`), so the app
 * showcases streaming chat, Markdown, a tool-progress card, and a rich
 * [HermesCard] without a single network call. See [DemoMode] for the state
 * holder and `docs/play-store-listing.md` (App access) for the reviewer note.
 *
 * Content contract (keep it this way):
 *  - **Obviously fictional, English, no real personal/server data** — public
 *    repo hygiene. "Aurora Bay" is a made-up city; "Hermes" is the agent.
 *  - **Fully self-contained / renders with zero network** — every message is
 *    terminal (not streaming), every attachment is [AttachmentState.LOADED]
 *    with no `relayToken` (which would trigger a relay fetch), and no inline
 *    `http(s)` image needs to be fetched. The unit test asserts this.
 *  - **Deterministic timestamps** ([DEMO_BASE_TIME] + offsets) so the demo
 *    looks the same every launch and the content is unit-testable.
 */
object DemoContent {

    /**
     * Fixed base wall-clock for demo timestamps (≈ mid-2025). Constant rather
     * than `System.currentTimeMillis()` so the transcript is deterministic and
     * the unit tests don't flake on timing.
     */
    const val DEMO_BASE_TIME: Long = 1_750_000_000_000L

    /** Stable session id for the demo conversation. */
    const val DEMO_SESSION_ID: String = "demo-session"

    /** Display name used on the assistant bubbles in the demo. */
    const val DEMO_AGENT_NAME: String = "Hermes"

    /**
     * The canned conversation, oldest-first (the order `ChatScreen` renders).
     * Two short exchanges: a capability tour that runs a tool and emits a rich
     * card, then a quick "can you code?" follow-up showing a Markdown code
     * block. 1–2 exchanges is enough to convey what the app does.
     */
    fun transcript(): List<ChatMessage> = listOf(
        ChatMessage(
            id = "demo-user-1",
            role = MessageRole.USER,
            content = "Hey Hermes — what can this app do? And what's the weather in Aurora Bay?",
            timestamp = DEMO_BASE_TIME,
            clientOnly = true,
        ),
        ChatMessage(
            id = "demo-assistant-1",
            role = MessageRole.ASSISTANT,
            content = ASSISTANT_TOUR,
            timestamp = DEMO_BASE_TIME + 3_000L,
            agentName = DEMO_AGENT_NAME,
            badges = listOf("Demo"),
            toolCalls = listOf(
                ToolCall(
                    id = "demo-tool-1",
                    name = "web_search",
                    args = "{\"query\":\"weather in Aurora Bay today\"}",
                    result = "Aurora Bay — 18°C, partly cloudy, wind 12 km/h NW.",
                    success = true,
                    isComplete = true,
                    provenance = "demo",
                    startedAt = DEMO_BASE_TIME + 800L,
                    completedAt = DEMO_BASE_TIME + 2_300L,
                ),
            ),
            cards = listOf(
                HermesCard(
                    type = HermesCard.BuiltInTypes.WEATHER,
                    title = "Aurora Bay",
                    subtitle = "Partly cloudy",
                    accent = HermesCard.Accents.INFO,
                    fields = listOf(
                        HermesCardField("Now", "18°C · feels like 17°C"),
                        HermesCardField("Wind", "12 km/h NW"),
                        HermesCardField("Sunset", "8:42 PM"),
                    ),
                    footer = "Sample data — demo mode",
                    id = "demo-weather",
                ),
            ),
            clientOnly = true,
        ),
        ChatMessage(
            id = "demo-user-2",
            role = MessageRole.USER,
            content = "Nice! Can you write code too?",
            timestamp = DEMO_BASE_TIME + 9_000L,
            clientOnly = true,
        ),
        ChatMessage(
            id = "demo-assistant-2",
            role = MessageRole.ASSISTANT,
            content = ASSISTANT_CODE,
            timestamp = DEMO_BASE_TIME + 12_000L,
            agentName = DEMO_AGENT_NAME,
            badges = listOf("Demo"),
            clientOnly = true,
        ),
    )

    /**
     * Assistant reply appended when the user sends a message INSIDE demo
     * mode. The composer must not be a silent no-op (it reads as broken —
     * see the demo-polish TODO), but there is no server to answer, so the
     * "reply" is an honest notice pointing at the exit path. Same content
     * contract as the transcript: clientOnly, terminal, zero network.
     *
     * @param id unique message id supplied by the caller (UUID-based; two
     *   rapid sends must not collide on LazyColumn keys).
     * @param nowMs wall-clock timestamp for the bubble.
     */
    fun composerReply(id: String, nowMs: Long): ChatMessage = ChatMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        content = COMPOSER_REPLY,
        timestamp = nowMs,
        agentName = DEMO_AGENT_NAME,
        badges = listOf("Demo"),
        clientOnly = true,
    )

    // --- Message bodies (Markdown). Kept as constants so the content is easy
    // to scan and the [transcript] builder stays readable. ---

    private val COMPOSER_REPLY: String = """
        This is the offline demo, so I can't answer for real — nothing here talks to a server.

        Connect your own Hermes server to chat live: tap **Connect** in the demo banner above.
    """.trimIndent()

    private val ASSISTANT_TOUR: String = """
        I'm **Hermes**, the agent running on *your* server. Here's a quick tour of what this app surfaces:

        - **Live streaming chat** with Markdown, code blocks, and reasoning
        - **Tool calls** rendered as progress cards — watch me work in real time
        - **Rich cards** for structured results like the one below
        - Optional **Terminal**, **Bridge**, and **Voice** once you connect a server

        I just looked up the forecast for you:
    """.trimIndent()

    private val ASSISTANT_CODE: String = """
        Absolutely — code blocks render with syntax-aware styling. For example:

        ```kotlin
        fun greet(name: String): String = "Hello, ${'$'}name!"

        println(greet("Aurora Bay"))
        // -> Hello, Aurora Bay!
        ```

        Connect your Hermes server to chat for real, run tools, and pick up where this demo leaves off.
    """.trimIndent()
}
