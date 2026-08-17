package com.hermesandroid.relay.auth

import com.hermesandroid.relay.network.shared.InvalidCredentialException
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthManagerBackupCredentialTest {
    @Test
    fun backupValidationRejectsMultilineCredentialsBeforeRestore() {
        for (secrets in listOf(
            ConnectionAuthSecrets(sessionToken = "first\nsecond"),
            ConnectionAuthSecrets(refreshToken = "first\r\nsecond"),
            ConnectionAuthSecrets(apiKey = "first second"),
            ConnectionAuthSecrets(profileApiKeys = mapOf("work" to "first\tsecond")),
        )) {
            assertThrows(InvalidCredentialException::class.java) {
                AuthManager.validateStoredSecrets(secrets)
            }
        }
    }
}
