package com.hermesandroid.relay.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationTriggerStoreTest {

    @Test
    fun disabledByDefaultDoesNotMatch() = runBlocking {
        val store = NotificationTriggerStore(InMemoryPreferencesDataStore())
        store.saveSingleRule(
            NotificationTriggerRule(appPackage = "com.slack"),
        )

        assertNull(store.firstMatchingRule(entry(packageName = "com.slack")))
    }

    @Test
    fun matchesEnabledRuleByAppAndTextFilter() = runBlocking {
        val store = NotificationTriggerStore(InMemoryPreferencesDataStore())
        store.setMasterEnabled(true)
        store.saveSingleRule(
            NotificationTriggerRule(
                label = "Slack from Sam",
                appPackage = "com.slack",
                textContains = "Sam",
            ),
        )

        assertNotNull(
            store.firstMatchingRule(
                entry(
                    packageName = "com.slack",
                    title = "Axiom",
                    text = "Sam: deploy finished",
                ),
            ),
        )
        assertNull(
            store.firstMatchingRule(
                entry(
                    packageName = "com.slack",
                    title = "Axiom",
                    text = "Alex: deploy finished",
                ),
            ),
        )
    }

    @Test
    fun killSwitchBlocksMatchesWithoutDeletingRule() = runBlocking {
        val store = NotificationTriggerStore(InMemoryPreferencesDataStore())
        store.setMasterEnabled(true)
        store.saveSingleRule(NotificationTriggerRule(appPackage = "com.slack"))
        store.setKillSwitch(true)

        assertNull(store.firstMatchingRule(entry(packageName = "com.slack")))
        assertEquals(1, store.settings.first().rules.size)
    }

    @Test
    fun emptyFilterRuleNeverMatches() {
        val rule = NotificationTriggerRule(
            appPackage = " ",
            titleContains = "",
            textContains = null,
        )

        assertEquals(false, rule.matches(entry(packageName = "com.any")))
    }

    @Test
    fun activityLogIsNewestFirstAndCapped() = runBlocking {
        val store = NotificationTriggerStore(InMemoryPreferencesDataStore())

        repeat(NotificationTriggerStore.MAX_ACTIVITY_LOG_ENTRIES + 2) { idx ->
            store.appendActivity(
                NotificationTriggerActivityEntry(
                    ruleId = "rule",
                    ruleLabel = "Rule",
                    action = NotificationTriggerAction.AskMe,
                    packageName = "pkg.$idx",
                    matchedAt = idx.toLong(),
                    result = "prompt posted",
                ),
            )
        }

        val log = store.settings.first().activityLog
        assertEquals(NotificationTriggerStore.MAX_ACTIVITY_LOG_ENTRIES, log.size)
        assertEquals("pkg.${NotificationTriggerStore.MAX_ACTIVITY_LOG_ENTRIES + 1}", log.first().packageName)
    }

    private fun entry(
        packageName: String,
        title: String? = "Title",
        text: String? = "Text",
        subText: String? = null,
    ) = NotificationEntry(
        packageName = packageName,
        title = title,
        text = text,
        subText = subText,
        postedAt = 123L,
        key = "$packageName:key",
    )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }
}
