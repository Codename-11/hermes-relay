package com.hermesandroid.relay.ui.screens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HRUI-022 — `parseModelOptions` must keep the full provider catalog visible.
 *
 * Newer upstream only returns unconfigured provider skeleton rows when the
 * client opts in via `include_unconfigured=1`; those rows arrive with EMPTY
 * `models` plus picker hints (`authenticated=false`, `key_env`, `warning`).
 * Dropping them silently hides every provider that still needs an API key,
 * killing the Manage → Keys setup affordance.
 */
class ModelOptionsParserTest {

    private fun parse(json: String): List<ModelProviderOption> =
        parseModelOptions(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun newUpstream_keepsUnconfiguredSkeletonRowAlongsideAuthenticatedProvider() {
        // Shape from upstream build_models_payload(include_unconfigured=True,
        // picker_hints=True): one authenticated row with models, one canonical
        // skeleton row with empty models + setup-hint fields.
        val options = parse(
            """
            {
              "providers": [
                {
                  "slug": "openai",
                  "name": "OpenAI",
                  "is_current": true,
                  "is_user_defined": false,
                  "models": ["gpt-5.5", "gpt-5.5-mini"],
                  "total_models": 2,
                  "authenticated": true
                },
                {
                  "slug": "anthropic",
                  "name": "Anthropic",
                  "is_current": false,
                  "is_user_defined": false,
                  "models": [],
                  "total_models": 0,
                  "source": "canonical",
                  "authenticated": false,
                  "auth_type": "api_key",
                  "key_env": "ANTHROPIC_API_KEY",
                  "warning": "paste ANTHROPIC_API_KEY to activate"
                }
              ],
              "model": "gpt-5.5",
              "provider": "openai"
            }
            """.trimIndent(),
        )

        // Both rows survive — the empty-models skeleton must NOT be dropped.
        assertEquals(2, options.size)

        val authenticated = options[0]
        assertEquals("openai", authenticated.id)
        assertEquals("OpenAI", authenticated.label)
        assertTrue(authenticated.authenticated)
        assertEquals(listOf("gpt-5.5", "gpt-5.5-mini"), authenticated.models)

        // Skeleton row: greyed, sorted after authenticated rows, and keeps the
        // Keys-guidance affordance (setup hint + unauthenticated flag).
        val skeleton = options[1]
        assertEquals("anthropic", skeleton.id)
        assertEquals("Anthropic", skeleton.label)
        assertFalse(skeleton.authenticated)
        assertTrue(skeleton.models.isEmpty())
        assertEquals("paste ANTHROPIC_API_KEY to activate", skeleton.setupHint)
    }

    @Test
    fun oldUpstream_fullListByDefault_parsesIdentically() {
        // Old upstream returned the universe without an opt-in; unauthenticated
        // rows could still carry curated models. Nothing about that shape may
        // parse differently after the include_unconfigured change.
        val options = parse(
            """
            {
              "providers": [
                {
                  "slug": "openai",
                  "name": "OpenAI",
                  "models": ["gpt-5.5"],
                  "authenticated": true
                },
                {
                  "slug": "xai",
                  "name": "xAI",
                  "models": ["grok-4"],
                  "authenticated": false,
                  "warning": "paste XAI_API_KEY to activate"
                }
              ],
              "model": "gpt-5.5",
              "provider": "openai"
            }
            """.trimIndent(),
        )

        assertEquals(2, options.size)
        assertEquals("openai", options[0].id)
        assertTrue(options[0].authenticated)
        // Unauthenticated-with-models keeps its catalog visible (greyed rows).
        assertEquals("xai", options[1].id)
        assertFalse(options[1].authenticated)
        assertEquals(listOf("grok-4"), options[1].models)
        assertEquals("paste XAI_API_KEY to activate", options[1].setupHint)
    }

    @Test
    fun missingAuthenticatedHint_defaultsByModelPresence() {
        // Payloads without picker hints: a row with models is assumed usable;
        // an empty row can only be an unconfigured skeleton, so grey it.
        val options = parse(
            """
            {
              "providers": [
                {"slug": "openai", "name": "OpenAI", "models": ["gpt-5.5"]},
                {"slug": "anthropic", "name": "Anthropic", "models": []}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, options.size)
        assertTrue(options[0].authenticated)
        assertFalse(options[1].authenticated)
        assertNull(options[1].setupHint)
    }

    @Test
    fun idResolution_prefersSlugThenFallsBackForLegacyShapes() {
        val options = parse(
            """
            {
              "providers": [
                {"slug": "openai", "id": "ignored", "name": "OpenAI", "models": ["gpt-5.5"]},
                {"id": "legacy-id", "name": "Legacy", "models": ["m1"]},
                {"name": "name-only", "models": ["m2"]},
                {"models": ["orphan-model"]}
              ]
            }
            """.trimIndent(),
        )

        // The selectable id must be the canonical provider slug when present —
        // /api/model/set expects the slug, not the display label.
        assertEquals(listOf("openai", "legacy-id", "name-only"), options.map { it.id })
    }

    @Test
    fun authenticatedRowsSortAheadOfSkeletons_preservingServerOrder() {
        val options = parse(
            """
            {
              "providers": [
                {"slug": "a-skel", "name": "A", "models": [], "authenticated": false},
                {"slug": "z-auth", "name": "Z", "models": ["m1"], "authenticated": true},
                {"slug": "b-auth", "name": "B", "models": ["m2"], "authenticated": true}
              ]
            }
            """.trimIndent(),
        )

        // Authenticated first; server (canonical) order kept within each group.
        assertEquals(listOf("z-auth", "b-auth", "a-skel"), options.map { it.id })
    }
}
