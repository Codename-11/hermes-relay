package com.hermesandroid.relay.voice

import android.content.Intent
import android.util.Log
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.models.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * === PHASE3-voice-intents (sideload flavor): real voice→bridge handler ===
 *
 * Classifies the transcribed utterance via [VoiceIntentClassifier]. On a
 * hit, it emits a `bridge` envelope through [ChannelMultiplexer] describing
 * the action to take (send SMS, open app, tap, scroll, back, home).
 *
 * ## Envelope wire shape
 *
 * ```
 * { channel: "bridge",
 *   type:    "tool.call",
 *   payload: { tool: "android_send_sms",
 *              args: { contact: "Sam", body: "I'll be 10 min late" },
 *              requires_confirmation: true,
 *              source:  "voice" } }
 * ```
 *
 * The exact envelope contract is owned by Wave 2 sibling safety-rails
 * (`bridge-safety-rails`) — we deliberately keep the payload shape simple +
 * documented so safety-rails can rename fields in a single place if needed.
 *
 * ## v1 confirmation (see interface doc for why)
 *
 * For destructive intents ([VoiceIntent.SendSms]) the impl:
 *
 * 1. Returns [IntentResult.Handled] with `requiresConfirmation=true` and a
 *    `spokenConfirmation` sentence ("About to text Sam: I'll be 10 min
 *    late. Say cancel to stop.").
 * 2. Starts a 5-second countdown coroutine.
 * 3. After 5 s, emits the bridge envelope — unless [cancelPending] was
 *    called first.
 *
 * For safe intents (open app, scroll, tap, back, home), the envelope is
 * emitted immediately and `requiresConfirmation=false`. No countdown.
 *
 * This is simpler than full conversational confirmation (where the user
 * says "yes" / "cancel" and that goes through another STT round). The
 * brief calls that out as a Wave 3 follow-up.
 */
internal class RealVoiceBridgeIntentHandler(
    private val multiplexer: ChannelMultiplexer?,
) : VoiceBridgeIntentHandler {

    companion object {
        private const val TAG = "VoiceBridgevoice-intents"
        /** v1 confirmation delay. Keep small so the UX is "you can cancel
         *  if you panic" not "let's have a conversation". */
        private const val CONFIRMATION_DELAY_MS = 5_000L
    }

    /** Own lifecycle so [cancelPending] can cut in-flight countdowns without
     *  touching the caller's scope. Replaced on each new tryHandle. */
    private val ownScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** The in-flight confirmation countdown, if any. */
    @Volatile
    private var pendingJob: Job? = null

    override suspend fun tryHandle(transcribedText: String): IntentResult {
        val intent = VoiceIntentClassifier.classify(transcribedText)
            ?: return IntentResult.NotApplicable

        // Drop any stale pending action before queueing a new one. Normally
        // the voice state machine won't allow concurrent tries, but belt
        // and braces.
        pendingJob?.cancel()
        pendingJob = null

        return when (intent) {
            // C4: resolve the contact name → phone number locally before
            // building the envelope. The pre-C4 implementation passed the
            // raw name (e.g. "Sam") as the `to` field, which the phone-side
            // /send_sms handler rejected because the `to` argument has to
            // pass a phone-number-shape regex. By resolving here we hand
            // the bridge a ready-to-dial number while keeping the safety
            // modal in the loop (BridgeCommandHandler still gates /send_sms
            // through the destructive-verb confirmation).
            is VoiceIntent.SendSms -> {
                val resolvedNumber = resolveContactPhone(intent.contact)
                if (resolvedNumber == null) {
                    Log.i(TAG, "C4 contact resolution failed for '${intent.contact}'")
                    IntentResult.Handled(
                        intentLabel = "Send SMS",
                        spokenConfirmation = "I couldn't find a contact called " +
                            "${intent.contact}.",
                        requiresConfirmation = false,
                    )
                } else {
                    handleDestructive(
                        label = "Send SMS",
                        spokenConfirmation = "About to text ${intent.contact} at " +
                            "$resolvedNumber: ${intent.body}. Say cancel to stop.",
                        envelope = buildSmsEnvelope(intent, resolvedNumber),
                    )
                }
            }
            // H5: resolve the human app name → Android package name locally
            // before dispatching. Pre-H5 we passed the raw name (e.g.
            // "Spotify") as the `package` field, which the phone's /open_app
            // handler rejected because PackageManager.getLaunchIntentForPackage
            // returns null for non-package strings. The fuzzy match (exact →
            // prefix → contains, all case-insensitive) covers most natural
            // utterances without needing a maintained alias table.
            is VoiceIntent.OpenApp -> {
                val resolvedPackage = resolveAppPackage(intent.appName)
                if (resolvedPackage == null) {
                    Log.i(TAG, "H5 app resolution failed for '${intent.appName}'")
                    IntentResult.Handled(
                        intentLabel = "Open App",
                        spokenConfirmation = "I couldn't find an app called " +
                            "${intent.appName}.",
                        requiresConfirmation = false,
                    )
                } else {
                    handleSafe(
                        label = "Open App",
                        envelope = buildOpenAppEnvelope(intent, resolvedPackage),
                    )
                }
            }
            is VoiceIntent.Tap -> handleSafe(
                label = "Tap",
                envelope = buildTapEnvelope(intent),
            )
            is VoiceIntent.Scroll -> handleSafe(
                label = "Scroll",
                envelope = buildScrollEnvelope(intent),
            )
            VoiceIntent.Back -> handleSafe(
                label = "Navigate back",
                envelope = buildPressKeyEnvelope("back"),
            )
            VoiceIntent.Home -> handleSafe(
                label = "Home",
                envelope = buildPressKeyEnvelope("home"),
            )
        }
    }

    override fun cancelPending() {
        val job = pendingJob
        pendingJob = null
        if (job != null) {
            Log.i(TAG, "cancelPending: user cancelled in-flight confirmation")
            job.cancel()
        }
    }

    // ---------------------------------------------------------------------
    // Dispatch helpers
    // ---------------------------------------------------------------------

    private fun handleDestructive(
        label: String,
        spokenConfirmation: String,
        envelope: Envelope,
    ): IntentResult.Handled {
        pendingJob = ownScope.launch {
            try {
                delay(CONFIRMATION_DELAY_MS)
                dispatch(envelope)
                Log.i(TAG, "$label: dispatched after confirmation window")
            } catch (_: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "$label: cancelled before dispatch")
            }
        }
        return IntentResult.Handled(
            intentLabel = label,
            spokenConfirmation = spokenConfirmation,
            requiresConfirmation = true,
        )
    }

    private fun handleSafe(
        label: String,
        envelope: Envelope,
    ): IntentResult.Handled {
        dispatch(envelope)
        return IntentResult.Handled(
            intentLabel = label,
            spokenConfirmation = null,
            requiresConfirmation = false,
        )
    }

    private fun dispatch(envelope: Envelope) {
        val mux = multiplexer
        if (mux == null) {
            Log.w(TAG, "dispatch: multiplexer null, dropping ${envelope.type}")
            return
        }
        try {
            mux.send(envelope)
        } catch (e: Exception) {
            // Don't crash voice mode if the bridge channel isn't open yet.
            // safety-rails's safety layer will surface a proper user-facing error.
            Log.w(TAG, "dispatch failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------
    // Envelope builders — see class-level comment for the wire shape.
    // ---------------------------------------------------------------------

    // ---------------------------------------------------------------------
    // C4 / H5 local resolvers
    //
    // Both resolvers run against the locally-available accessibility
    // service rather than dispatching a `bridge.command` and waiting on
    // the matching `bridge.response`. The voice intent code is on the same
    // process as the accessibility service, so a direct call is faster +
    // simpler than the response-correlation pattern the original TODO
    // sketch proposed. The bridge command we emit AFTER resolution still
    // goes through the safety modal, so destructive-verb gating and the
    // blocklist apply to the final action.
    //
    // Both helpers tolerate a null service instance (returning null) —
    // the calling tryHandle branch surfaces that as a "couldn't find
    // contact / app" spoken response rather than crashing the voice
    // session.
    // ---------------------------------------------------------------------

    /**
     * C4 resolver: voice contact name → first phone number on the best
     * matching contact, via the same `ActionExecutor.searchContacts`
     * code path that backs the `/search_contacts` bridge route.
     *
     * Implementation note: `searchContacts` returns the contact's phones
     * as a comma-joined string ("`+15551234567, +15559876543`") because
     * that's the wire shape the C2 tier designed for the agent's prose
     * output. For the voice flow we only need a single number to dial,
     * so we split on `,` and take the first non-blank entry. Trimming is
     * required because the join uses `", "` (comma + space).
     *
     * Returns null when:
     *   - the accessibility service is not connected
     *   - searchContacts returns ok=false (e.g. contacts permission
     *     missing — the user gets a spoken "couldn't find" response
     *     and can grant the permission then retry)
     *   - the contacts list is empty (no match for the spoken name)
     *   - the matched contact has no phone numbers stored
     */
    private suspend fun resolveContactPhone(contactName: String): String? {
        val service = HermesAccessibilityService.instance ?: run {
            Log.w(TAG, "C4 resolveContactPhone: a11y service not connected")
            return null
        }
        // ContactsContract queries hit the on-device content provider —
        // wrap in IO dispatcher so the voice coroutine doesn't block on
        // disk under load.
        val result = withContext(Dispatchers.IO) {
            service.actionExecutor.searchContacts(contactName, limit = 5)
        }
        if (!result.ok) {
            Log.i(TAG, "C4 searchContacts failed: ${result.error}")
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val contacts = result.data["contacts"] as? List<Map<String, Any?>>
            ?: return null
        if (contacts.isEmpty()) return null
        val phonesField = contacts.first()["phones"] as? String ?: return null
        return phonesField.split(",")
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    /**
     * H5 resolver: voice app name → Android package name via fuzzy match
     * against the launchable-app inventory. Mirrors the same
     * `PackageManager.queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)`
     * call that the `/get_apps` bridge route uses, so the resolution set
     * matches what the user sees in their launcher.
     *
     * Match priority (case-insensitive, first hit wins):
     *   1. Exact label match — "spotify" → label "Spotify"
     *   2. Prefix match — "chro" → label "Chrome Beta"
     *   3. Substring match — "google" → label "Google Maps"
     *
     * Substring is the loosest tier so the order matters: a word like
     * "messages" would match the literal Messages app via tier 1 before
     * matching "Google Messages" via tier 3.
     *
     * Returns null when no candidate label matches at any tier — the
     * voice flow surfaces that as "couldn't find an app called X".
     *
     * Note: requires the `<queries>` launcher-intent declaration in the
     * main AndroidManifest (added alongside the /get_apps fix in the
     * same release). Without it Android 11+ silently returns a near-
     * empty candidate list and every match attempt fails.
     */
    private suspend fun resolveAppPackage(appName: String): String? {
        val service = HermesAccessibilityService.instance ?: run {
            Log.w(TAG, "H5 resolveAppPackage: a11y service not connected")
            return null
        }
        val pm = service.packageManager
        // PackageManager queries can be slow on devices with many apps
        // — the same IO-dispatcher reasoning as resolveContactPhone applies.
        val candidates = withContext(Dispatchers.IO) {
            val launcher = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = runCatching { pm.queryIntentActivities(launcher, 0) }
                .getOrDefault(emptyList())
            resolved.mapNotNull { resolve ->
                val info = resolve.activityInfo?.applicationInfo
                    ?: return@mapNotNull null
                val pkg = info.packageName ?: return@mapNotNull null
                val label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(pkg)
                pkg to label
            }
        }
        if (candidates.isEmpty()) {
            Log.w(TAG, "H5 candidate list empty — missing <queries> in manifest?")
            return null
        }

        val needle = appName.trim().lowercase()
        // Tier 1: exact label match.
        candidates.firstOrNull { it.second.lowercase() == needle }
            ?.let { return it.first }
        // Tier 2: label starts with the needle.
        candidates.firstOrNull { it.second.lowercase().startsWith(needle) }
            ?.let { return it.first }
        // Tier 3: label contains the needle anywhere.
        candidates.firstOrNull { it.second.lowercase().contains(needle) }
            ?.let { return it.first }
        return null
    }

    // ---------------------------------------------------------------------
    // Envelope builders — see class-level comment for the wire shape.
    // ---------------------------------------------------------------------

    /**
     * === PHASE3-tier-C4: direct /send_sms bridge command (resolved) ===
     *
     * Builds the `/send_sms` bridge.command envelope with a
     * pre-resolved phone number. The resolution step happens in
     * [resolveContactPhone] before this is called. The phone's
     * destructive-verb confirmation modal still fires when
     * BridgeCommandHandler routes the `/send_sms` path, so even with
     * resolution in place the user has to tap-confirm before the SMS
     * actually leaves the device.
     */
    private fun buildSmsEnvelope(
        i: VoiceIntent.SendSms,
        resolvedNumber: String,
    ): Envelope = Envelope(
        channel = "bridge",
        type = "bridge.command",
        payload = buildJsonObject {
            put("request_id", java.util.UUID.randomUUID().toString())
            put("method", "POST")
            put("path", "/send_sms")
            put("body", buildJsonObject {
                put("to", resolvedNumber)
                put("body", i.body)
            })
            put("source", "voice")
        },
    )
    // === END PHASE3-tier-C4 ===

    /**
     * H5: emits `/open_app` with a resolved Android package name.
     * [resolveAppPackage] does the human-name → package fuzzy match;
     * this builder just wraps the resolved value in the bridge.command
     * envelope. `BridgeCommandHandler` still runs the target-package
     * blocklist check on /open_app, so a voice utterance like "open
     * banking app" still gets blocked if the banking package is on the
     * Tier 5 blocklist.
     */
    private fun buildOpenAppEnvelope(
        i: VoiceIntent.OpenApp,
        resolvedPackage: String,
    ): Envelope = Envelope(
        channel = "bridge",
        type = "bridge.command",
        payload = buildJsonObject {
            put("request_id", java.util.UUID.randomUUID().toString())
            put("method", "POST")
            put("path", "/open_app")
            put("body", buildJsonObject {
                put("package", resolvedPackage)
            })
            put("source", "voice")
        },
    )

    private fun buildTapEnvelope(i: VoiceIntent.Tap): Envelope = Envelope(
        channel = "bridge",
        type = "bridge.command",
        payload = buildJsonObject {
            put("request_id", java.util.UUID.randomUUID().toString())
            put("method", "POST")
            put("path", "/tap_text")
            put("body", buildJsonObject {
                put("text", i.target)
            })
            put("source", "voice")
        },
    )

    private fun buildScrollEnvelope(i: VoiceIntent.Scroll): Envelope = Envelope(
        channel = "bridge",
        type = "bridge.command",
        payload = buildJsonObject {
            put("request_id", java.util.UUID.randomUUID().toString())
            put("method", "POST")
            put("path", "/scroll")
            put("body", buildJsonObject {
                put("direction", i.direction.name.lowercase())
            })
            put("source", "voice")
        },
    )

    private fun buildPressKeyEnvelope(key: String): Envelope = Envelope(
        channel = "bridge",
        type = "bridge.command",
        payload = buildJsonObject {
            put("request_id", java.util.UUID.randomUUID().toString())
            put("method", "POST")
            put("path", "/press_key")
            put("body", buildJsonObject {
                put("key", key)
            })
            put("source", "voice")
        },
    )
}

// === END PHASE3-voice-intents (sideload impl) ===
