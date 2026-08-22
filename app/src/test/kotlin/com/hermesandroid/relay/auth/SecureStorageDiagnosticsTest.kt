package com.hermesandroid.relay.auth

import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecureStorageDiagnosticsTest {
    @Before
    fun setUp() {
        DiagnosticsLog.clear()
    }

    @After
    fun tearDown() {
        DiagnosticsLog.clear()
    }

    @Test
    fun preferredStoreUnavailable_recordsSanitizedFallback() {
        SecureStorageDiagnostics.preferredStoreUnavailable()

        val entry = DiagnosticsLog.recent(setOf(DiagnosticCategory.Auth)).single()
        assertEquals(DiagnosticSeverity.Warning, entry.severity)
        assertEquals("Secure credential storage fallback activated", entry.title)
        assertTrue(entry.detail.orEmpty().contains("encrypted compatibility storage"))
        assertSanitized(entry.toString())
    }

    @Test
    fun legacyStoreRecovered_recordsCredentialLossGuidance() {
        SecureStorageDiagnostics.legacyStoreRecovered()

        val entry = DiagnosticsLog.recent(setOf(DiagnosticCategory.Auth)).single()
        assertEquals(DiagnosticSeverity.Warning, entry.severity)
        assertEquals("Encrypted credential storage recovered", entry.title)
        assertTrue(entry.detail.orEmpty().contains("cleared and rebuilt"))
        assertTrue(entry.suggestion.orEmpty().contains("Sign in or pair again"))
        assertSanitized(entry.toString())
    }

    @Test
    fun preferredStoreRecovered_recordsCredentialLossGuidance() {
        SecureStorageDiagnostics.preferredStoreRecovered()

        val entry = DiagnosticsLog.recent(setOf(DiagnosticCategory.Auth)).single()
        assertEquals(DiagnosticSeverity.Warning, entry.severity)
        assertEquals("Keystore credential storage recovered", entry.title)
        assertTrue(entry.detail.orEmpty().contains("cleared and rebuilt"))
        assertTrue(entry.suggestion.orEmpty().contains("Sign in or pair again"))
        assertSanitized(entry.toString())
    }

    @Test
    fun inMemoryStoreOnly_recordsPersistentStorageFailure() {
        SecureStorageDiagnostics.inMemoryStoreOnly()

        val entry = DiagnosticsLog.recent(setOf(DiagnosticCategory.Auth)).single()
        assertEquals(DiagnosticSeverity.Error, entry.severity)
        assertEquals("Credential storage is temporary", entry.title)
        assertTrue(entry.detail.orEmpty().contains("app process stops"))
        assertSanitized(entry.toString())
    }

    @Test
    fun repeatedEvent_isRecordedOnlyOnceWhileVisible() {
        repeat(3) {
            SecureStorageDiagnostics.preferredStoreRecovered()
        }

        assertEquals(1, DiagnosticsLog.recent(setOf(DiagnosticCategory.Auth)).size)
    }

    @Test
    fun clearingDiagnostics_allowsLaterIncidentToBeRecorded() {
        SecureStorageDiagnostics.preferredStoreRecovered()
        DiagnosticsLog.clear()

        SecureStorageDiagnostics.preferredStoreRecovered()

        assertEquals(1, DiagnosticsLog.recent(setOf(DiagnosticCategory.Auth)).size)
    }

    private fun assertSanitized(text: String) {
        assertFalse(text.contains("prefs"))
        assertFalse(text.contains("connectionId"))
        assertFalse(text.contains("AEADBadTagException"))
        assertFalse(text.contains("secret"))
    }
}
