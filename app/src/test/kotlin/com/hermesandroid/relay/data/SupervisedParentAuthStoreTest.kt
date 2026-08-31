package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisedParentAuthStoreTest {
    @Test
    fun `new credential policy accepts strong pins and passwords`() {
        assertFalse(SupervisedParentAuthStore.validateNewSecret("12345".toCharArray(), SupervisedParentCredentialType.Pin).valid)
        assertTrue(SupervisedParentAuthStore.validateNewSecret("123456".toCharArray(), SupervisedParentCredentialType.Pin).valid)
        assertFalse(SupervisedParentAuthStore.validateNewSecret("1234567".toCharArray(), SupervisedParentCredentialType.Pin).valid)
        assertFalse(SupervisedParentAuthStore.validateNewSecret("short".toCharArray(), SupervisedParentCredentialType.Password).valid)
        assertTrue(SupervisedParentAuthStore.validateNewSecret("long passphrase".toCharArray(), SupervisedParentCredentialType.Password).valid)
        assertFalse(SupervisedParentAuthStore.validateNewSecret(" ".repeat(8).toCharArray(), SupervisedParentCredentialType.Password).valid)
        assertFalse(SupervisedParentAuthStore.validateNewSecret("x".repeat(65).toCharArray(), SupervisedParentCredentialType.Password).valid)
    }

    @Test
    fun `enrollment stores only salted PBKDF2 verifiers and returns six word recovery phrase`() = runTest {
        val dataStore = InMemoryParentAuthDataStore()
        val store = fastStore(dataStore)

        val enrollment = store.enroll(
            "correct horse".toCharArray(),
            SupervisedParentCredentialType.Password,
        ).getOrThrow()
        val raw = dataStore.data.first()[SupervisedParentAuthStore.recordKeyForTesting].orEmpty()

        assertEquals(6, enrollment.recoveryPhrase.split('-').size)
        assertEquals(6, enrollment.recoveryPhrase.split('-').distinct().size)
        assertTrue(raw.contains("PBKDF2WithHmacSHA256"))
        assertTrue(raw.contains("\"iterations\":1"))
        assertFalse(raw.contains("correct horse"))
        assertFalse(raw.contains(enrollment.recoveryPhrase))
        assertTrue(raw.contains("\"credentialType\":\"Password\""))
        assertTrue(raw.contains("\"recoveryFormat\":\"WordPhrase\""))
        val salts = Regex("\"(?:parentSalt|recoverySalt)\":\"([^\"]+)\"")
            .findAll(raw).map { it.groupValues[1] }.toList()
        assertEquals(2, salts.size)
        assertNotEquals(salts[0], salts[1])
    }

    @Test
    fun `production enrollment records 310000 rounds`() = runTest {
        val dataStore = InMemoryParentAuthDataStore()
        val store = SupervisedParentAuthStore.forTesting(
            dataStore = dataStore,
            iterations = 310_000,
        )

        store.enroll("production-strength".toCharArray(), SupervisedParentCredentialType.Password).getOrThrow()

        assertTrue(
            dataStore.data.first()[SupervisedParentAuthStore.recordKeyForTesting]
                .orEmpty().contains("\"iterations\":310000"),
        )
    }

    @Test
    fun `verification is fail closed when missing or corrupt`() = runTest {
        val dataStore = InMemoryParentAuthDataStore()
        val store = fastStore(dataStore)
        assertEquals(SupervisedParentAuthStatus.Missing, store.statusFlow.first())
        assertEquals(SupervisedParentAuthResult.Missing, store.verify("anything".toCharArray()))

        dataStore.edit { it[SupervisedParentAuthStore.recordKeyForTesting] = "not-json" }
        assertEquals(SupervisedParentAuthStatus.Corrupt, store.statusFlow.first())
        assertEquals(SupervisedParentAuthResult.Corrupt, store.verify("anything".toCharArray()))
    }

    @Test
    fun `unsupported or weakened records fail closed`() = runTest {
        val dataStore = InMemoryParentAuthDataStore()
        val store = fastStore(dataStore)
        store.enroll("parent password".toCharArray(), SupervisedParentCredentialType.Password).getOrThrow()
        val raw = dataStore.data.first()[SupervisedParentAuthStore.recordKeyForTesting].orEmpty()

        dataStore.edit {
            it[SupervisedParentAuthStore.recordKeyForTesting] = raw.replace("\"version\":1", "\"version\":2")
        }
        assertEquals(SupervisedParentAuthStatus.Corrupt, store.statusFlow.first())

        dataStore.edit {
            it[SupervisedParentAuthStore.recordKeyForTesting] = raw.replace("\"iterations\":1", "\"iterations\":0")
        }
        assertEquals(SupervisedParentAuthStatus.Corrupt, store.statusFlow.first())
    }

    @Test
    fun `records created before credential type selection remain verifiable`() = runTest {
        val dataStore = InMemoryParentAuthDataStore()
        val store = fastStore(dataStore)
        store.enroll("parent password".toCharArray(), SupervisedParentCredentialType.Password).getOrThrow()
        val current = dataStore.data.first()[SupervisedParentAuthStore.recordKeyForTesting].orEmpty()
        val legacy = current
            .replace(Regex(",\"credentialType\":\"Password\""), "")
            .replace(Regex(",\"recoveryFormat\":\"WordPhrase\""), "")
        dataStore.edit { it[SupervisedParentAuthStore.recordKeyForTesting] = legacy }

        assertEquals(SupervisedParentCredentialType.Legacy, store.credentialTypeFlow.first())
        assertEquals(SupervisedParentAuthResult.Success, store.verify("parent password".toCharArray()))
    }

    @Test
    fun `enroll cannot replace an existing or corrupt credential`() = runTest {
        val dataStore = InMemoryParentAuthDataStore()
        val store = fastStore(dataStore)
        store.enroll("first password".toCharArray(), SupervisedParentCredentialType.Password).getOrThrow()

        assertTrue(store.enroll("second password".toCharArray(), SupervisedParentCredentialType.Password).isFailure)
        assertEquals(SupervisedParentAuthResult.Success, store.verify("first password".toCharArray()))

        dataStore.edit { it[SupervisedParentAuthStore.recordKeyForTesting] = "corrupt" }
        assertTrue(store.enroll("second password".toCharArray(), SupervisedParentCredentialType.Password).isFailure)
    }

    @Test
    fun `authenticated clear removes credential and disables policies without losing settings`() = runTest {
        val dataStore = InMemoryParentAuthDataStore()
        val authStore = fastStore(dataStore)
        val policyStore = SupervisedModeStore.forTesting(dataStore)
        authStore.enroll("parent password".toCharArray(), SupervisedParentCredentialType.Password).getOrThrow()
        policyStore.setPolicy(
            "connection-a",
            SupervisedModePolicy(
                enabled = true,
                pinnedProfileName = "willow",
                capabilities = SupervisedCapabilities(attachments = false, voice = true),
            ),
        )
        policyStore.setPolicy(
            "connection-b",
            SupervisedModePolicy(
                enabled = true,
                pinnedProfileName = "coder",
                visibility = SupervisedVisibility(showTimestamps = true),
            ),
        )
        val beforeA = policyStore.policyFlow("connection-a").first()
        val beforeB = policyStore.policyFlow("connection-b").first()

        authStore.clearCredentialAndDisablePolicies().getOrThrow()

        assertEquals(SupervisedParentAuthStatus.Missing, authStore.statusFlow.first())
        assertEquals(beforeA.copy(enabled = false), policyStore.policyFlow("connection-a").first())
        assertEquals(beforeB.copy(enabled = false), policyStore.policyFlow("connection-b").first())
    }

    @Test
    fun `failed attempts persist across store recreation and backoff expires by clock`() = runTest {
        val dataStore = InMemoryParentAuthDataStore()
        val clock = AtomicLong(1_000L)
        var store = fastStore(dataStore, clock)
        store.enroll("parent password".toCharArray(), SupervisedParentCredentialType.Password).getOrThrow()

        repeat(4) {
            assertTrue(store.verify("wrong password".toCharArray()) is SupervisedParentAuthResult.Invalid)
        }
        val fifth = store.verify("wrong password".toCharArray())
        assertEquals(SupervisedParentAuthResult.Throttled(30_000L), fifth)

        store = fastStore(dataStore, clock)
        assertEquals(
            SupervisedParentAuthResult.Throttled(30_000L),
            store.verify("parent password".toCharArray()),
        )
        clock.addAndGet(30_001L)
        assertEquals(SupervisedParentAuthResult.Success, store.verify("parent password".toCharArray()))
    }

    @Test
    fun `concurrent store instances preserve the capped failure sequence`() = runTest {
        val dataStore = InMemoryParentAuthDataStore()
        val stores = List(5) { fastStore(dataStore) }
        stores.first().enroll("parent password".toCharArray(), SupervisedParentCredentialType.Password).getOrThrow()

        val results = stores.map { store -> async { store.verify("wrong password".toCharArray()) } }.awaitAll()

        assertEquals(4, results.count { it is SupervisedParentAuthResult.Invalid })
        assertEquals(1, results.count { it == SupervisedParentAuthResult.Throttled(30_000L) })
    }

    @Test
    fun `change requires the current credential and rotates recovery`() = runTest {
        val store = fastStore(InMemoryParentAuthDataStore())
        val original = store.enroll("old password".toCharArray(), SupervisedParentCredentialType.Password).getOrThrow()

        assertTrue(store.change("wrong".toCharArray(), "new password".toCharArray(), SupervisedParentCredentialType.Password).isFailure)
        assertEquals(SupervisedParentAuthResult.Success, store.verify("old password".toCharArray()))

        val replacement = store.change(
            "old password".toCharArray(),
            "654321".toCharArray(),
            SupervisedParentCredentialType.Pin,
        ).getOrThrow()
        assertNotEquals(original.recoveryPhrase, replacement.recoveryPhrase)
        assertTrue(store.verify("old password".toCharArray()) is SupervisedParentAuthResult.Invalid)
        assertEquals(SupervisedParentAuthResult.Success, store.verify("654321".toCharArray()))
        assertEquals(SupervisedParentCredentialType.Pin, store.credentialTypeFlow.first())
    }

    @Test
    fun `recovery is normalized one time and rotates both secrets`() = runTest {
        val store = fastStore(InMemoryParentAuthDataStore())
        val original = store.enroll("old password".toCharArray(), SupervisedParentCredentialType.Password).getOrThrow()
        val lowerSpaced = original.recoveryPhrase.uppercase().replace("-", " ").toCharArray()

        val replacement = store.resetWithRecoveryPhrase(
            lowerSpaced,
            "new password".toCharArray(),
            SupervisedParentCredentialType.Password,
        ).getOrThrow()

        assertNotEquals(original.recoveryPhrase, replacement.recoveryPhrase)
        assertEquals(SupervisedParentAuthResult.Success, store.verify("new password".toCharArray()))
        assertTrue(
            store.resetWithRecoveryPhrase(
                original.recoveryPhrase.toCharArray(),
                "another password".toCharArray(),
                SupervisedParentCredentialType.Password,
            )
                .isFailure,
        )
    }

    private fun fastStore(
        dataStore: DataStore<Preferences>,
        clock: AtomicLong = AtomicLong(1_000L),
    ): SupervisedParentAuthStore = SupervisedParentAuthStore.forTesting(
        dataStore = dataStore,
        iterations = 1,
        minimumAcceptedIterations = 1,
        nowMillis = clock::get,
    )
}

private class InMemoryParentAuthDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
