package com.hermesandroid.relay.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Minimal notification-trigger MVP schema and persistence.
 *
 * Storage location: Android DataStore preferences file `notification_triggers`
 * under the app-private data directory. Rules and the visible activity log are
 * JSON strings so schema evolution remains additive and lenient.
 */
@Serializable
data class NotificationTriggerRule(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "Ask me about matching notifications",
    val enabled: Boolean = true,
    @SerialName("app_package")
    val appPackage: String? = null,
    @SerialName("title_contains")
    val titleContains: String? = null,
    @SerialName("text_contains")
    val textContains: String? = null,
    val action: NotificationTriggerAction = NotificationTriggerAction.AskMe,
    @SerialName("require_confirmation")
    val requireConfirmation: Boolean = false,
)

@Serializable
enum class NotificationTriggerAction {
    @SerialName("ask_me")
    AskMe,
}

@Serializable
data class NotificationTriggerActivityEntry(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("rule_id")
    val ruleId: String,
    @SerialName("rule_label")
    val ruleLabel: String,
    val action: NotificationTriggerAction,
    @SerialName("package_name")
    val packageName: String,
    val title: String? = null,
    @SerialName("text_preview")
    val textPreview: String? = null,
    @SerialName("matched_at")
    val matchedAt: Long,
    val result: String,
)

@Serializable
data class NotificationTriggerSettings(
    @SerialName("master_enabled")
    val masterEnabled: Boolean = false,
    @SerialName("kill_switch")
    val killSwitch: Boolean = false,
    val rules: List<NotificationTriggerRule> = emptyList(),
    @SerialName("activity_log")
    val activityLog: List<NotificationTriggerActivityEntry> = emptyList(),
)

data class NotificationTriggerMatch(
    val rule: NotificationTriggerRule,
    val entry: NotificationEntry,
)

internal val Context.notificationTriggerDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "notification_triggers")

class NotificationTriggerStore(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val settings: Flow<NotificationTriggerSettings> = dataStore.data.map { prefs ->
        NotificationTriggerSettings(
            masterEnabled = prefs[KEY_MASTER_ENABLED] ?: false,
            killSwitch = prefs[KEY_KILL_SWITCH] ?: false,
            rules = decodeList<NotificationTriggerRule>(prefs[KEY_RULES_JSON]),
            activityLog = decodeList<NotificationTriggerActivityEntry>(prefs[KEY_ACTIVITY_LOG_JSON]),
        )
    }

    suspend fun setMasterEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_MASTER_ENABLED] = enabled }
    }

    suspend fun setKillSwitch(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_KILL_SWITCH] = enabled }
    }

    suspend fun saveSingleRule(rule: NotificationTriggerRule) {
        dataStore.edit { prefs ->
            prefs[KEY_RULES_JSON] = json.encodeToString(listOf(rule.normalized()))
        }
    }

    suspend fun clearActivityLog() {
        dataStore.edit { prefs -> prefs.remove(KEY_ACTIVITY_LOG_JSON) }
    }

    suspend fun firstMatchingRule(entry: NotificationEntry): NotificationTriggerMatch? {
        val snapshot = settings.first()
        if (!snapshot.masterEnabled || snapshot.killSwitch) return null
        val rule = snapshot.rules.firstOrNull { it.matches(entry) } ?: return null
        return NotificationTriggerMatch(rule = rule, entry = entry)
    }

    suspend fun appendActivity(entry: NotificationTriggerActivityEntry) {
        dataStore.edit { prefs ->
            val current = decodeList<NotificationTriggerActivityEntry>(prefs[KEY_ACTIVITY_LOG_JSON])
            prefs[KEY_ACTIVITY_LOG_JSON] = json.encodeToString(
                (listOf(entry) + current).take(MAX_ACTIVITY_LOG_ENTRIES),
            )
        }
    }

    private inline fun <reified T> decodeList(raw: String?): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())
    }

    private fun NotificationTriggerRule.normalized(): NotificationTriggerRule = copy(
        label = label.trim().ifBlank { "Ask me about matching notifications" },
        appPackage = appPackage.cleanBlank(),
        titleContains = titleContains.cleanBlank(),
        textContains = textContains.cleanBlank(),
    )

    companion object {
        private val KEY_MASTER_ENABLED = booleanPreferencesKey("notification_triggers_enabled")
        private val KEY_KILL_SWITCH = booleanPreferencesKey("notification_triggers_kill_switch")
        private val KEY_RULES_JSON = stringPreferencesKey("notification_trigger_rules_json")
        private val KEY_ACTIVITY_LOG_JSON = stringPreferencesKey("notification_trigger_activity_log_json")
        const val MAX_ACTIVITY_LOG_ENTRIES = 25

        fun defaultRule(): NotificationTriggerRule = NotificationTriggerRule()
    }
}

fun NotificationTriggerRule.matches(entry: NotificationEntry): Boolean {
    if (!enabled) return false
    val app = appPackage.cleanBlank()
    val titleNeedle = titleContains.cleanBlank()
    val textNeedle = textContains.cleanBlank()

    // Avoid accidental "match every notification on the phone" rules. The UI
    // requires at least one filter too, but this keeps imported/future schema
    // data safe.
    if (app == null && titleNeedle == null && textNeedle == null) return false

    if (app != null && !entry.packageName.equals(app, ignoreCase = true)) return false
    if (titleNeedle != null && !entry.title.orEmpty().contains(titleNeedle, ignoreCase = true)) {
        return false
    }
    if (textNeedle != null) {
        val haystack = listOfNotNull(entry.text, entry.subText).joinToString("\n")
        if (!haystack.contains(textNeedle, ignoreCase = true)) return false
    }
    return true
}

fun NotificationTriggerRule.summary(): String {
    val parts = buildList {
        appPackage.cleanBlank()?.let { add("app $it") }
        titleContains.cleanBlank()?.let { add("title contains “$it”") }
        textContains.cleanBlank()?.let { add("text contains “$it”") }
    }
    return if (parts.isEmpty()) "No filters set" else parts.joinToString(" · ")
}

private fun String?.cleanBlank(): String? = this?.trim()?.takeIf { it.isNotBlank() }

object NotificationTriggerPromptNotifier {
    private const val TAG = "NotifTriggerPrompt"
    private const val CHANNEL_ID = "notification_triggers"
    private const val CHANNEL_NAME = "Notification triggers"
    private const val NOTIFICATION_ID_BASE = 4300
    private const val CHAT_ROUTE = "chat"

    /**
     * Safe automatic action: post a local prompt that asks the user whether to
     * involve Hermes. It does not send an LLM request, reply, tap, text, route,
     * or otherwise act on another app without the user tapping first.
     */
    @SuppressLint("MissingPermission", "NotificationPermission")
    fun notifyAskMe(
        context: Context,
        rule: NotificationTriggerRule,
        entry: NotificationEntry,
    ): String {
        ensureChannel(context)
        if (!hasPostNotificationsPermission(context)) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted — logging trigger without prompt")
            return "skipped: post-notifications permission missing"
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAV_ROUTE, CHAT_ROUTE)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tapPending = PendingIntent.getActivity(context, notificationId(entry), tapIntent, pendingFlags)

        val title = "Ask Hermes about this?"
        val source = entry.title?.takeIf { it.isNotBlank() } ?: entry.packageName
        val body = entry.text?.takeIf { it.isNotBlank() }
            ?: "Rule matched: ${rule.summary()}"
        val expanded = "Matched ${rule.summary()}\n\n$source\n$body"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("$source — ${body.take(96)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded.take(700)))
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(entry), notification)
            "prompt posted"
        }.getOrElse { exc ->
            Log.w(TAG, "notifyAskMe: notify failed", exc)
            "skipped: prompt failed (${exc.javaClass.simpleName})"
        }
    }

    private fun notificationId(entry: NotificationEntry): Int {
        val suffix = (entry.key.hashCode() and 0x0fff)
        return NOTIFICATION_ID_BASE + suffix
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Prompts shown when an explicitly enabled notification trigger matches."
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    private fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
