package com.hermesandroid.relay.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AuthManager's pure logic: pairing code generation and AuthState types.
 *
 * AuthManager itself requires Android Context (EncryptedSharedPreferences) and
 * ChannelMultiplexer, so we cannot instantiate it in JVM tests. Instead, we test
 * the pairing code algorithm and the AuthState sealed class directly.
 */
class AuthManagerTest {

    // --- Pairing code generation ---
    // Mirror the companion object constants from AuthManager:
    // PAIRING_CODE_LENGTH = 6, PAIRING_CODE_CHARS = ('A'..'Z') + ('0'..'9')

    private val codeLength = 6
    private val allowedChars = (('A'..'Z') + ('0'..'9')).toSet()

    // Excluded ambiguous characters that should NOT be in PAIRING_CODE_CHARS
    // Note: The current AuthManager uses ALL A-Z and 0-9. This test documents
    // that behavior. If ambiguous chars (0, O, 1, I) should be excluded,
    // the implementation would need updating.
    private val ambiguousChars = setOf('0', 'O', '1', 'I')

    private fun generatePairingCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..codeLength)
            .map { chars.random() }
            .joinToString("")
    }

    @Test
    fun pairingCode_hasCorrectLength() {
        val code = generatePairingCode()
        assertEquals(codeLength, code.length)
    }

    @Test
    fun pairingCode_onlyContainsAllowedCharacters() {
        repeat(50) {
            val code = generatePairingCode()
            for (char in code) {
                assertTrue(
                    "Character '$char' not in allowed set",
                    char in allowedChars
                )
            }
        }
    }

    @Test
    fun pairingCode_doesNotContainLowercase() {
        repeat(50) {
            val code = generatePairingCode()
            for (char in code) {
                assertFalse(
                    "Code should not contain lowercase: $char",
                    char.isLowerCase()
                )
            }
        }
    }

    @Test
    fun pairingCode_doesNotContainSpecialCharacters() {
        repeat(50) {
            val code = generatePairingCode()
            for (char in code) {
                assertTrue(
                    "Code should only contain alphanumeric: $char",
                    char.isLetterOrDigit()
                )
            }
        }
    }

    @Test
    fun pairingCode_regeneration_producesDifferentCode() {
        // With 36^6 = ~2.2 billion combinations, collision probability is negligible
        val codes = (1..20).map { generatePairingCode() }.toSet()
        assertTrue(
            "Expected multiple unique codes out of 20 generated, got ${codes.size}",
            codes.size > 1
        )
    }

    @Test
    fun pairingCode_allCharactersInRange() {
        // Generate many codes and verify all characters are within expected bounds
        val allChars = (1..100).flatMap { generatePairingCode().toList() }.toSet()
        for (char in allChars) {
            assertTrue(
                "Character '$char' outside allowed range",
                char in 'A'..'Z' || char in '0'..'9'
            )
        }
    }

    // --- AuthState sealed class ---

    @Test
    fun authState_unpaired_isCorrectType() {
        val state: AuthState = AuthState.Unpaired
        assertTrue(state is AuthState.Unpaired)
    }

    @Test
    fun authState_pairing_isCorrectType() {
        val state: AuthState = AuthState.Pairing
        assertTrue(state is AuthState.Pairing)
    }

    @Test
    fun authState_paired_holdsToken() {
        val state = AuthState.Paired("my-secret-token")
        assertTrue(state is AuthState.Paired)
        assertEquals("my-secret-token", state.token)
    }

    @Test
    fun authState_failed_holdsReason() {
        val state = AuthState.Failed("Invalid pairing code")
        assertTrue(state is AuthState.Failed)
        assertEquals("Invalid pairing code", state.reason)
    }

    @Test
    fun authState_allTypesAreDistinct() {
        val states: List<AuthState> = listOf(
            AuthState.Unpaired,
            AuthState.Pairing,
            AuthState.Paired("token"),
            AuthState.Failed("reason")
        )

        assertEquals(4, states.map { it::class }.distinct().size)
    }

    @Test
    fun authState_unpaired_singletonEquality() {
        val a = AuthState.Unpaired
        val b = AuthState.Unpaired
        assertEquals(a, b)
        assertTrue(a === b)
    }

    @Test
    fun authState_pairing_singletonEquality() {
        val a = AuthState.Pairing
        val b = AuthState.Pairing
        assertEquals(a, b)
        assertTrue(a === b)
    }

    @Test
    fun authState_paired_dataClassEquality() {
        val a = AuthState.Paired("token123")
        val b = AuthState.Paired("token123")
        assertEquals(a, b)
    }

    @Test
    fun authState_paired_differentTokens_notEqual() {
        val a = AuthState.Paired("token-A")
        val b = AuthState.Paired("token-B")
        assertNotEquals(a, b)
    }

    @Test
    fun authState_failed_dataClassEquality() {
        val a = AuthState.Failed("bad code")
        val b = AuthState.Failed("bad code")
        assertEquals(a, b)
    }

    @Test
    fun authState_failed_differentReasons_notEqual() {
        val a = AuthState.Failed("reason A")
        val b = AuthState.Failed("reason B")
        assertNotEquals(a, b)
    }

    // --- State transition patterns ---

    @Test
    fun authState_transitionSequence_unpairedToPairingToPaired() {
        var state: AuthState = AuthState.Unpaired
        assertTrue(state is AuthState.Unpaired)

        state = AuthState.Pairing
        assertTrue(state is AuthState.Pairing)

        state = AuthState.Paired("session-token-abc")
        assertTrue(state is AuthState.Paired)
        assertEquals("session-token-abc", (state as AuthState.Paired).token)
    }

    @Test
    fun authState_transitionSequence_unpairedToFailed() {
        var state: AuthState = AuthState.Unpaired
        assertTrue(state is AuthState.Unpaired)

        state = AuthState.Failed("Server unreachable")
        assertTrue(state is AuthState.Failed)
        assertEquals("Server unreachable", (state as AuthState.Failed).reason)
    }

    @Test
    fun authState_transitionSequence_pairingToFailed() {
        var state: AuthState = AuthState.Pairing
        assertTrue(state is AuthState.Pairing)

        state = AuthState.Failed("Code expired")
        assertTrue(state is AuthState.Failed)
    }
}
