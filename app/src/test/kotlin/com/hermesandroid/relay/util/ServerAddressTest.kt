package com.hermesandroid.relay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [ServerAddress] — the shared validation/parse helper
 * that stands between user-entered server addresses and okhttp's *throwing*
 * `url(String)`/`toHttpUrl()`.
 *
 * The crash this guards (issue #131): the literal UI/docs string
 * `"Manage sign-in and admin screens"` reached a request builder as a host and
 * okhttp threw `IllegalArgumentException: Invalid URL host`, uncaught on a Main
 * coroutine → force-close. Every assertion here is the contract that makes that
 * impossible: malformed input becomes a typed null/error, and the helper itself
 * NEVER throws.
 *
 * No Android framework / Robolectric — okhttp's `HttpUrl` is plain JVM.
 */
class ServerAddressTest {

    // --- The exact crash trigger ---

    @Test
    fun rejectsTheUiLabelThatCausedTheCrash() {
        // The reported value. Spaces are illegal in a host, so it must never be
        // treated as a usable address.
        assertFalse(ServerAddress.isValidUserInput("Manage sign-in and admin screens"))
        assertNull(ServerAddress.parse("http://Manage sign-in and admin screens"))
        assertNull(ServerAddress.parseUserInput("Manage sign-in and admin screens"))
        assertNotNull(ServerAddress.fieldError("Manage sign-in and admin screens", "Dashboard URL"))
    }

    @Test
    fun helpersNeverThrowOnAdversarialInput() {
        // Whatever the user pastes, these return — they do not throw. (A throw
        // here is the whole bug class.) Each value is also genuinely invalid:
        // a space in the host or whitespace-only after trim.
        val nasties = listOf(
            "Manage sign-in and admin screens",
            "http://exa mple.com",
            "two words",
            " ",
            "\t\n",
        )
        for (value in nasties) {
            assertFalse("expected invalid: '$value'", ServerAddress.isValidUserInput(value))
            assertNull("expected null parse: '$value'", ServerAddress.parseUserInput(value))
        }
    }

    // --- Lenient user input (the setup field): bare hosts get http:// ---

    @Test
    fun acceptsBareHostsIpsAndLocalhost() {
        assertTrue(ServerAddress.isValidUserInput("192.168.1.10"))
        assertTrue(ServerAddress.isValidUserInput("192.168.1.10:8642"))
        assertTrue(ServerAddress.isValidUserInput("localhost"))
        assertTrue(ServerAddress.isValidUserInput("100.64.0.1:9119"))
    }

    @Test
    fun acceptsExplicitHttpAndHttpsUrls() {
        assertTrue(ServerAddress.isValidUserInput("http://hermes.example.com"))
        assertTrue(ServerAddress.isValidUserInput("https://hermes.example.com:9119"))
        // A bare host normalizes to http:// with the host preserved.
        assertEquals("localhost", ServerAddress.parseUserInput("localhost")?.host)
        assertEquals("http", ServerAddress.parseUserInput("localhost")?.scheme)
        assertEquals(9119, ServerAddress.parseUserInput("https://h.example:9119")?.port)
    }

    @Test
    fun blankAndWhitespaceAreInvalidUserInput() {
        assertFalse(ServerAddress.isValidUserInput(""))
        assertFalse(ServerAddress.isValidUserInput("   "))
        assertFalse(ServerAddress.isValidUserInput(null))
    }

    // --- Strict parse (the request-builder guard primitive): scheme required ---

    @Test
    fun strictParseRequiresAnHttpScheme() {
        // Missing scheme → null (a stored base URL is always scheme-bearing, so
        // anything without one is junk).
        assertNull(ServerAddress.parse("localhost"))
        assertNull(ServerAddress.parse("192.168.1.10:8642"))
        // Non-http(s) schemes are not usable on this surface.
        assertNull(ServerAddress.parse("ws://host"))
        assertNull(ServerAddress.parse("wss://host"))
        assertNull(ServerAddress.parse("ftp://host"))
        // Blank / null.
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("   "))
        assertNull(ServerAddress.parse(null))
        // Valid.
        assertNotNull(ServerAddress.parse("http://localhost:9119"))
        assertEquals("https", ServerAddress.parse("https://h.example")?.scheme)
    }

    // --- loopbackHostWarning: loopback addresses can't reach the server from a phone ---

    @Test
    fun loopbackHostWarningFlagsLoopbackAndAnyInterfaceHosts() {
        val loopbacks = listOf(
            "localhost",
            "localhost:8642",
            "http://localhost:8642",
            "127.0.0.1",
            "https://127.0.0.1:9119",
            "::1",
            "[::1]",
            "http://[::1]:8642",
            "0.0.0.0",
            "http://0.0.0.0:8642",
        )
        for (value in loopbacks) {
            assertNotNull("expected warning: '$value'", ServerAddress.loopbackHostWarning(value))
        }
    }

    @Test
    fun loopbackHostWarningIsNullForReachableAddresses() {
        val reachable = listOf(
            "192.168.1.10",
            "192.168.1.10:8642",
            "http://10.0.0.5:8642",
            "100.64.0.1:8642",
            "hermes.tail1234.ts.net",
            "https://hermes.example.com:8642",
            "hermes-box",
            // Not loopback: hostname that merely starts with 127.
            "127.evil.example.com",
        )
        for (value in reachable) {
            assertNull("expected no warning: '$value'", ServerAddress.loopbackHostWarning(value))
        }
    }

    @Test
    fun loopbackHostWarningIsNullForBlankAndJunk() {
        assertNull(ServerAddress.loopbackHostWarning(""))
        assertNull(ServerAddress.loopbackHostWarning("   "))
        assertNull(ServerAddress.loopbackHostWarning("not a host"))
    }

    // --- fieldError: inline UI message contract ---

    @Test
    fun fieldErrorIsNullForBlankAndValidButSetForJunk() {
        // Blank is acceptable (the dashboard-URL field is optional) → no error.
        assertNull(ServerAddress.fieldError("", "Dashboard URL"))
        assertNull(ServerAddress.fieldError("   ", "Dashboard URL"))
        // Valid host → no error.
        assertNull(ServerAddress.fieldError("192.168.1.10:8642", "API server URL"))
        assertNull(ServerAddress.fieldError("https://hermes.example.com", "Dashboard URL"))
        // Junk → a message that names the field.
        val error = ServerAddress.fieldError("Manage sign-in and admin screens", "Dashboard URL")
        assertNotNull(error)
        assertTrue(error!!.contains("Dashboard URL"))
    }
}
