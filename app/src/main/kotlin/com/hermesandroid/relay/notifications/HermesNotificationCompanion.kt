package com.hermesandroid.relay.notifications

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.models.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Opt-in [NotificationListenerService] that forwards posted-notification
 * metadata to the user's paired Hermes assistant over the existing WSS
 * connection.
 *
 * This is the same Android API used by Wear OS, Android Auto, Tasker,
 * and every smart-watch companion app — it's part of the public SDK
 * and the user grants/revokes access via Android's system "Notification
 * access" page (`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`).
 * The user can revoke at any time and Android shows the running
 * listener in their system permissions list.
 *
 * Wire-up:
 *  1. The user opens Settings → Notification companion in the app
 *  2. They tap "Open Android Settings" and toggle Hermes-Relay on
 *  3. Android binds this service automatically
 *  4. [onListenerConnected] sets the static [active] reference
 *  5. [onNotificationPosted] builds an [Envelope] and pushes it to
 *     the [ChannelMultiplexer] (set by `ConnectionViewModel` once
 *     the relay handshake completes)
 *  6. Multiplexer hands the envelope to ConnectionManager → WSS → relay
 *
 * Pattern: a static `companion object` reference to the bound service
 * instance + a static [multiplexer] slot that the ViewModel injects.
 * This is the standard Android service-to-app handoff (matches
 * `HermesAccessibilityService` in agent accessibility's worktree).
 */
class HermesNotificationCompanion : NotificationListenerService() {

    /**
     * Buffer for entries that arrive before [multiplexer] has been
     * wired up (e.g. notifications during app cold-start). Bounded so
     * we don't OOM if the multiplexer is never set. Drained on the
     * next [onNotificationPosted] call once a multiplexer is present.
     */
    private val pendingEnvelopes = ConcurrentLinkedQueue<Envelope>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        active = this
        Log.i(TAG, "NotificationListener bound — companion is live")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (active === this) {
            active = null
        }
        Log.i(TAG, "NotificationListener disconnected")
    }

    override fun onDestroy() {
        if (active === this) {
            active = null
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val entry = sbn.toEntry() ?: return
        val envelope = entry.toEnvelope()

        // Drain any backlog first so order is preserved.
        val mux = multiplexer
        if (mux == null) {
            pendingEnvelopes.offer(envelope)
            // Cap the buffer at a sensible size — drop oldest on overflow.
            while (pendingEnvelopes.size > MAX_PENDING) {
                pendingEnvelopes.poll()
            }
            Log.d(
                TAG,
                "Buffered notification (no multiplexer yet, pending=${pendingEnvelopes.size})",
            )
            return
        }

        // Drain pending first (in arrival order) then send the new one.
        while (true) {
            val next = pendingEnvelopes.poll() ?: break
            try {
                mux.sendNotification(next)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to send buffered notification: ${t.message}")
            }
        }
        try {
            mux.sendNotification(envelope)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send notification: ${t.message}")
        }
    }

    /**
     * We don't act on notification removal — the cache on the relay is
     * append-only with LRU eviction. Could be added later if the LLM
     * needs to know "this one was dismissed".
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally no-op for now.
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun StatusBarNotification.toEntry(): NotificationEntry? {
        val n = notification ?: return null
        val extras = n.extras ?: return null

        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        val sub = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString()

        // Skip notifications with no human-readable content — they're
        // usually background sync placeholders that just confuse the LLM.
        if (title.isNullOrBlank() && text.isNullOrBlank()) return null

        return NotificationEntry(
            packageName = packageName,
            title = title,
            text = text,
            subText = sub,
            postedAt = postTime,
            key = key,
        )
    }

    private fun NotificationEntry.toEnvelope(): Envelope {
        val payload = JSON.encodeToJsonElement(NotificationEntry.serializer(), this) as JsonObject
        return Envelope(
            channel = "notifications",
            type = "notification.posted",
            payload = payload,
        )
    }

    companion object {
        private const val TAG = "HermesNotifCompanion"
        private const val MAX_PENDING = 50

        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /**
         * The currently bound service instance, or null if the user
         * has not granted notification access (or has revoked it).
         * Set in [onListenerConnected]. Use [isAccessGranted] to check
         * the system permission state without depending on this flag,
         * since the service may not have bound yet right after the
         * user grants access.
         */
        @Volatile
        var active: HermesNotificationCompanion? = null
            private set

        /**
         * Multiplexer reference injected by `ConnectionViewModel` once
         * the relay handshake completes. Service buffers envelopes
         * until this is set so notifications that arrive during cold
         * start aren't dropped.
         */
        @Volatile
        var multiplexer: ChannelMultiplexer? = null

        /**
         * True if the user has granted notification-access permission
         * to this app in Android Settings. Cheap synchronous check
         * against `enabled_notification_listeners` — safe to call from
         * Compose recomposition.
         */
        fun isAccessGranted(context: Context): Boolean {
            val pkg = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val expected = ComponentName(
                pkg,
                HermesNotificationCompanion::class.java.name,
            ).flattenToString()
            // The flat string is a colon-separated list of component names.
            return flat.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
