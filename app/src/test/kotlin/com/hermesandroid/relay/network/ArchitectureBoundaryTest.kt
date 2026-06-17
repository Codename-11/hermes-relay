package com.hermesandroid.relay.network

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test

/**
 * Enforces the ADR 34 package fence on **production** code (test sources are
 * intentionally excluded — they may reference both sides):
 *
 *  - vanilla-upstream network code (`network.upstream[.*]`) must not import Relay code
 *  - Relay network code (`network.relay[.*]`) must not import upstream code
 *  - the neutral shared layer (`network.shared`) must import neither
 *
 * This turns the "standard path = vanilla upstream" invariant from a CLAUDE.md
 * convention into a failing test: a future file that pulls a relay client into
 * the standard path (or vice-versa) breaks CI here, not in code review.
 */
class ArchitectureBoundaryTest {

    @Test
    fun `upstream network code must not import relay`() {
        Konsist.scopeFromProduction()
            .files
            .filter { it.packagee?.name?.startsWith(UPSTREAM) == true }
            .assertFalse { file -> file.imports.any { it.name.startsWith(RELAY) } }
    }

    @Test
    fun `relay network code must not import upstream`() {
        Konsist.scopeFromProduction()
            .files
            .filter { it.packagee?.name?.startsWith(RELAY) == true }
            .assertFalse { file -> file.imports.any { it.name.startsWith(UPSTREAM) } }
    }

    @Test
    fun `shared network code must import neither upstream nor relay`() {
        Konsist.scopeFromProduction()
            .files
            .filter { it.packagee?.name?.startsWith(SHARED) == true }
            .assertFalse { file ->
                file.imports.any { it.name.startsWith(UPSTREAM) || it.name.startsWith(RELAY) }
            }
    }

    private companion object {
        const val UPSTREAM = "com.hermesandroid.relay.network.upstream"
        const val RELAY = "com.hermesandroid.relay.network.relay"
        const val SHARED = "com.hermesandroid.relay.network.shared"
    }
}
