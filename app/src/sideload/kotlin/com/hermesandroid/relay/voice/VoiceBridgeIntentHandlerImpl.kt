package com.hermesandroid.relay.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.handlers.LocalDispatchResult
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
    // === PHASE3-voice-intents-localdispatch ===
    // Local in-process dispatcher — calls
    // BridgeCommandHandler.handleLocalCommand(envelope) which runs the
    // same dispatch + Tier 5 safety pipeline as the WSS-incoming path,
    // but without the WSS round-trip. Voice intents originate on the
    // phone and target the phone's own accessibility service, so going
    // over the relay would just bounce the bridge.command back as
    // "unexpected from phone" (which is exactly what happened in the
    // 2026-04-14 on-device test that motivated this rework).
    //
    // Optional with a null default so test harnesses constructing this
    // class without the full DI graph still compile. In production
    // RelayApp wires the real dispatcher in; if it's somehow null at
    // runtime the dispatch() helper falls back to the legacy multiplexer
    // path with a WARN log so the regression is at least visible.
    private val localBridgeDispatcher: LocalBridgeDispatcher? = null,
    // === END PHASE3-voice-intents-localdispatch ===
    // === PHASE3-voice-intents-postdispatch ===
    // Callback invoked AFTER the dispatch completes (success or failure)
    // so voice mode can emit a follow-up chat bubble showing the real
    // outcome. Wired by VoiceViewModel to write to ChatViewModel. Null
    // for test harnesses and any call site that doesn't care about the
    // post-dispatch trace — we still run dispatch, we just don't emit
    // the follow-up notification.
    private val onDispatchResult: VoiceIntentResultCallback? = null,
    // === END PHASE3-voice-intents-postdispatch ===
    // === PHASE3-voice-countdown ===
    // Callback invoked at the MOMENT a destructive intent enters its v1
    // countdown window. VoiceViewModel uses this to stamp a
    // DestructiveCountdownState into VoiceUiState so the overlay can
    // draw a progress bar synchronized with the real delay. Null for
    // test harnesses and the Play flavor (no destructive intents).
    private val onCountdownStart: VoiceIntentCountdownCallback? = null,
    // === END PHASE3-voice-countdown ===
) : VoiceBridgeIntentHandler {

    companion object {
        private const val TAG = "VoiceBridgevoice-intents"
        /** v1 confirmation delay. Keep small so the UX is "you can cancel
         *  if you panic" not "let's have a conversation". */
        private const val CONFIRMATION_DELAY_MS = 5_000L

        /**
         * Loose shape regex for "looks like a phone number". Accepts an
         * optional leading `+`, followed by 7–20 characters consisting of
         * digits, spaces, dashes, and parentheses. Good enough to detect
         * the common spoken-out-loud forms ("+1 555 123 4567",
         * "(555) 123-4567", "555 1234") without false-positiving on
         * contact names. Deliberately not a strict E.164 validator —
         * that's the /send_sms route's job.
         */
        private val PHONE_NUMBER_REGEX = Regex("^\\+?[\\d\\s\\-()]{7,20}$")
    }

    /** True if [text] looks like a typed / spoken phone number literal. */
    private fun looksLikePhoneNumber(text: String): Boolean =
        PHONE_NUMBER_REGEX.matches(text.trim())

    /**
     * Strip spaces, dashes, and parentheses from a phone-number literal so
     * the /send_sms bridge route's shape regex (E.164-ish) accepts it.
     * Preserves the leading `+` if present.
     */
    private fun normalizePhoneNumber(text: String): String =
        text.trim().filter { it.isDigit() || it == '+' }

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
                // Pre-flight SMS permission check — if SEND_SMS isn't granted
                // we'd burn through the 5s confirmation window + safety modal
                // and then silently fail inside executor.sendSms. Better to
                // skip dispatch entirely and speak an actionable message. The
                // LLM tool-calling path hits the same permission check in
                // ActionExecutor.sendSms (line 1414) — this is just moving the
                // voice-local check earlier to avoid the dead countdown.
                val smsPermission = checkSmsPermission()
                if (smsPermission != null) {
                    IntentResult.Handled(
                        intentLabel = "Send SMS",
                        spokenConfirmation = smsPermission,
                        requiresConfirmation = false,
                        details = mapOf(
                            "contact" to intent.contact,
                            "body" to intent.body,
                            "error" to "permission_missing_sms",
                        ),
                    )
                } else if (looksLikePhoneNumber(intent.contact)) {
                    // Phone-number literal bypass: "text +1 555 123 4567
                    // saying hi" shouldn't try to look up a contact named
                    // "+1 555 123 4567". Normalize whitespace + separators
                    // so the /send_sms route's shape-regex accepts the
                    // number, then dispatch straight to the destructive
                    // countdown path. The chat trace still renders cleanly
                    // because `contact` and `resolvedNumber` are both set
                    // in the details map.
                    val normalized = normalizePhoneNumber(intent.contact)
                    handleDestructive(
                        label = "Send SMS",
                        spokenConfirmation = "About to text ${intent.contact}: " +
                            "${intent.body}. Say cancel to stop.",
                        envelope = buildSmsEnvelope(intent, normalized),
                        details = mapOf(
                            "contact" to intent.contact,
                            "resolvedNumber" to normalized,
                            "body" to intent.body,
                        ),
                    )
                } else {
                    when (val resolution = resolveContactPhone(intent.contact)) {
                        is ContactResolution.Found -> {
                            // Multi-match disambiguation hint: when the
                            // search hit more than one contact AND the
                            // name we picked isn't a case-insensitive
                            // exact match for the query, prepend a short
                            // "Found N contacts…" sentence so the user
                            // knows we had to choose. Single matches and
                            // exact-name matches speak the original
                            // preview as-is. Full multi-turn picker is
                            // Wave 3 (deferred).
                            val exactMatch = resolution.matchedName
                                .equals(intent.contact, ignoreCase = true)
                            val ambiguous = resolution.totalMatches > 1 && !exactMatch
                            val disambiguationPrefix = if (ambiguous) {
                                "Found ${resolution.totalMatches} contacts matching " +
                                    "${intent.contact}. Using ${resolution.matchedName}. "
                            } else {
                                ""
                            }
                            // Multi-phone hint: when the selected contact
                            // has more than one phone on record we say
                            // "their Mobile" / "their Work" so the user
                            // hears which number got picked. Matters for
                            // the Galaxy Watch vs mobile case Bailey hit
                            // 2026-04-15 where the Watch entry came first
                            // in insertion order and got auto-selected
                            // silently — the user couldn't tell from the
                            // spoken preview which number the SMS was
                            // actually going to.
                            val phoneQualifier = if (resolution.totalPhones > 1) {
                                val label = resolution.phoneLabel.takeIf { it.isNotBlank() }
                                    ?: resolution.phoneType.replaceFirstChar { it.uppercase() }
                                " $label"
                            } else {
                                ""
                            }
                            handleDestructive(
                                label = "Send SMS",
                                spokenConfirmation = disambiguationPrefix +
                                    "About to text ${resolution.matchedName}$phoneQualifier at " +
                                    "${resolution.number}: ${intent.body}. Say cancel to stop.",
                                envelope = buildSmsEnvelope(intent, resolution.number),
                                details = mapOf(
                                    "contact" to intent.contact,
                                    "matchedName" to resolution.matchedName,
                                    "totalMatches" to resolution.totalMatches.toString(),
                                    "totalPhones" to resolution.totalPhones.toString(),
                                    "phoneType" to resolution.phoneType,
                                    "phoneLabel" to resolution.phoneLabel,
                                    "resolvedNumber" to resolution.number,
                                    "body" to intent.body,
                                ),
                            )
                        }
                        is ContactResolution.ServiceMissing -> IntentResult.Handled(
                            intentLabel = "Send SMS",
                            spokenConfirmation = "I can't reach the bridge service. " +
                                "Make sure Hermes accessibility is enabled in Settings.",
                            requiresConfirmation = false,
                            details = mapOf(
                                "contact" to intent.contact,
                                "error" to "service_missing",
                            ),
                        )
                        is ContactResolution.PermissionMissing -> IntentResult.Handled(
                            intentLabel = "Send SMS",
                            spokenConfirmation = "I need Contacts permission to look up " +
                                "that number. Tap the Bridge tab to grant it.",
                            requiresConfirmation = false,
                            details = mapOf(
                                "contact" to intent.contact,
                                "error" to "permission_missing_contacts",
                            ),
                        )
                        is ContactResolution.NotFound -> IntentResult.Handled(
                            intentLabel = "Send SMS",
                            spokenConfirmation = "I couldn't find a contact called " +
                                "${intent.contact}.",
                            requiresConfirmation = false,
                            details = mapOf(
                                "contact" to intent.contact,
                                "error" to "contact_not_found",
                            ),
                        )
                        is ContactResolution.NoPhoneNumber -> IntentResult.Handled(
                            intentLabel = "Send SMS",
                            spokenConfirmation = "${intent.contact} doesn't have a phone " +
                                "number on file.",
                            requiresConfirmation = false,
                            details = mapOf(
                                "contact" to intent.contact,
                                "error" to "contact_no_phone",
                            ),
                        )
                        is ContactResolution.OtherError -> IntentResult.Handled(
                            intentLabel = "Send SMS",
                            spokenConfirmation = "I hit an error looking up ${intent.contact}: " +
                                resolution.message,
                            requiresConfirmation = false,
                            details = mapOf(
                                "contact" to intent.contact,
                                "error" to "other_error",
                                "errorMessage" to resolution.message,
                            ),
                        )
                    }
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
                when (val resolution = resolveAppPackage(intent.appName)) {
                    is AppResolution.Found -> {
                        Log.i(
                            TAG,
                            "H5 resolved '${intent.appName}' → '${resolution.label}' " +
                                "(${resolution.packageName}) via ${resolution.matchTier}",
                        )
                        handleSafe(
                            label = "Open App",
                            envelope = buildOpenAppEnvelope(intent, resolution.packageName),
                            details = mapOf(
                                "requestedName" to intent.appName,
                                "appLabel" to resolution.label,
                                "packageName" to resolution.packageName,
                                "matchTier" to resolution.matchTier,
                            ),
                        )
                    }
                    is AppResolution.ServiceMissing -> IntentResult.Handled(
                        intentLabel = "Open App",
                        spokenConfirmation = "I can't reach the bridge service. " +
                            "Make sure Hermes accessibility is enabled in Settings.",
                        requiresConfirmation = false,
                        details = mapOf(
                            "requestedName" to intent.appName,
                            "error" to "service_missing",
                        ),
                    )
                    is AppResolution.NotFound -> IntentResult.Handled(
                        intentLabel = "Open App",
                        spokenConfirmation = "I couldn't find an app called " +
                            "${intent.appName}.",
                        requiresConfirmation = false,
                        details = mapOf(
                            "requestedName" to intent.appName,
                            "error" to "app_not_found",
                        ),
                    )
                    is AppResolution.OtherError -> IntentResult.Handled(
                        intentLabel = "Open App",
                        spokenConfirmation = resolution.message,
                        requiresConfirmation = false,
                        details = mapOf(
                            "requestedName" to intent.appName,
                            "error" to "other_error",
                            "errorMessage" to resolution.message,
                        ),
                    )
                }
            }
            is VoiceIntent.Tap -> handleSafe(
                label = "Tap",
                envelope = buildTapEnvelope(intent),
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

    override fun hasPendingDestructive(): Boolean = pendingJob?.isActive == true

    // ---------------------------------------------------------------------
    // Dispatch helpers
    // ---------------------------------------------------------------------

    private fun handleDestructive(
        label: String,
        spokenConfirmation: String,
        envelope: Envelope,
        details: Map<String, String> = emptyMap(),
    ): IntentResult.Handled {
        // Notify the UI BEFORE launching the countdown coroutine so the
        // progress animation starts in lockstep with the real delay. If
        // we invoked this after launch(), there's a small window where
        // the delay timer is running but the overlay shows nothing.
        onCountdownStart?.invoke(label, CONFIRMATION_DELAY_MS)
        pendingJob = ownScope.launch {
            try {
                delay(CONFIRMATION_DELAY_MS)
                val result = dispatch(envelope)
                Log.i(TAG, "$label: dispatched after confirmation window (status=${result.status})")
                // Emit follow-up chat trace with the real outcome — "SMS
                // sent" / "user denied" / "permission missing" etc.
                onDispatchResult?.invoke(label, result)
            } catch (_: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "$label: cancelled before dispatch")
                onDispatchResult?.invoke(
                    label,
                    LocalDispatchResult(
                        status = 499,
                        errorMessage = "cancelled before dispatch",
                        errorCode = "cancelled",
                        resultJson = null,
                    ),
                )
            }
        }
        return IntentResult.Handled(
            intentLabel = label,
            spokenConfirmation = spokenConfirmation,
            requiresConfirmation = true,
            details = details,
        )
    }

    private suspend fun handleSafe(
        label: String,
        envelope: Envelope,
        details: Map<String, String> = emptyMap(),
    ): IntentResult.Handled {
        val result = dispatch(envelope)
        onDispatchResult?.invoke(label, result)
        return IntentResult.Handled(
            intentLabel = label,
            spokenConfirmation = null,
            requiresConfirmation = false,
            details = details,
        )
    }

    /**
     * Dispatch a bridge envelope. Prefers the in-process
     * [localBridgeDispatcher] which runs through
     * `BridgeCommandHandler.handleLocalCommand` and stays on the device.
     * Falls back to `multiplexer.send` only if the local dispatcher is
     * null (test harnesses, mis-wiring) — and that fallback will log a
     * WARN because the WSS path is broken for phone-originated bridge
     * commands (the relay drops them as "unexpected").
     *
     * The `bridge.command` envelope built by the [buildXxxEnvelope]
     * helpers above is shape-compatible with both paths: same fields,
     * same `request_id`, same JSON body. The only difference is whether
     * we hand it to the local in-process handler (correct) or push it
     * through WSS (broken).
     */
    private suspend fun dispatch(envelope: Envelope): LocalDispatchResult {
        val local = localBridgeDispatcher
        if (local != null) {
            try {
                return local(envelope)
            } catch (e: Exception) {
                // Don't crash voice mode if the local dispatcher throws.
                // BridgeCommandHandler.handleLocalCommand has its own
                // try/catch around dispatch(), so anything reaching here
                // is a programming error worth logging loudly.
                Log.w(TAG, "local dispatch failed: ${e.message}", e)
                return LocalDispatchResult(
                    status = 500,
                    errorMessage = e.message ?: "local dispatcher threw",
                    errorCode = "dispatch_exception",
                    resultJson = null,
                )
            }
        }
        // Fallback path (legacy / test harness): the WSS round-trip.
        // This path is known broken in production — the relay logs
        // "ignoring unexpected bridge.command from phone" and drops the
        // envelope. The fallback exists so unit tests that don't wire a
        // localBridgeDispatcher still get a graceful no-op instead of an
        // NPE. Synthetic result so callers still get typed feedback.
        val mux = multiplexer
        if (mux == null) {
            Log.w(TAG, "dispatch: both localBridgeDispatcher AND multiplexer are null, dropping ${envelope.type}")
            return LocalDispatchResult(
                status = 503,
                errorMessage = "no dispatcher or multiplexer wired",
                errorCode = "dispatcher_missing",
                resultJson = null,
            )
        }
        Log.w(TAG, "dispatch: localBridgeDispatcher missing — falling back to multiplexer.send (will be dropped by relay as 'unexpected bridge.command from phone'). Wire localBridgeDispatcher in RelayApp.")
        return try {
            mux.send(envelope)
            // The WSS fallback is fire-and-forget: the relay drops the
            // envelope so there's no real "success" to report, but we
            // don't have a synthetic failure code for that either. Return
            // an unknown-success so the follow-up chat trace at least
            // exists rather than silently dropping.
            LocalDispatchResult(
                status = 202,
                errorMessage = null,
                errorCode = "wss_fallback_fire_and_forget",
                resultJson = null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "dispatch fallback (multiplexer) failed: ${e.message}")
            LocalDispatchResult(
                status = 500,
                errorMessage = e.message ?: "multiplexer.send threw",
                errorCode = "fallback_exception",
                resultJson = null,
            )
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
     * Pre-flight SEND_SMS runtime permission check. Returns null if
     * granted, or a ready-to-speak error message if denied or if the
     * a11y service isn't connected (no context to query against).
     *
     * Why here and not deferred to `ActionExecutor.sendSms`: that inner
     * check IS what catches real-world failures, but only after the
     * 5-second confirmation countdown and the safety-modal flow have
     * already run. Failing that far into the dispatch means the user
     * sees the preview, hears the countdown, taps Allow on the modal,
     * and THEN gets silent failure when the SmsManager call returns
     * "permission denied". Pulling the check forward lets us short-
     * circuit with an accurate spoken message before any of that fires.
     */
    private fun checkSmsPermission(): String? {
        val service = HermesAccessibilityService.instance
            ?: return "I can't reach the bridge service. Make sure Hermes " +
                "accessibility is enabled in Settings."
        val granted = ContextCompat.checkSelfPermission(
            service, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) {
            null
        } else {
            "I need SMS permission to send a text. Tap the Bridge tab to grant it."
        }
    }

    /**
     * C4 resolver: voice contact name → classified resolution result, via
     * the same `ActionExecutor.searchContacts` code path that backs the
     * `/search_contacts` bridge route.
     *
     * Pre-v0.4.0 this returned `String?` and collapsed every failure mode
     * (service missing / permission missing / no match / no phone number)
     * to `null`. The voice handler then hardcoded "I couldn't find a
     * contact called X" which was honest only in the last case — users
     * who hadn't granted READ_CONTACTS heard "not found" and went looking
     * for a typo instead of the real permission gap. Bailey hit this
     * 2026-04-15. Returning a [ContactResolution] lets the caller speak
     * an accurate message per category.
     *
     * `ActionExecutor.searchContacts` itself pre-checks the READ_CONTACTS
     * runtime permission and returns a distinctive error string ("Grant
     * contacts permission...") when it's missing. We substring-match that
     * string here to classify — not the cleanest contract but pragmatic,
     * and it avoids a breaking change to the ActionResult shape that the
     * LLM tool-calling path also depends on.
     *
     * Implementation note: `searchContacts` returns the contact's phones
     * as a comma-joined string ("+15551234567, +15559876543"). For voice
     * we only need a single number to dial, so we split on `,` and take
     * the first non-blank entry.
     */
    private suspend fun resolveContactPhone(contactName: String): ContactResolution {
        val service = HermesAccessibilityService.instance ?: run {
            Log.w(TAG, "C4 resolveContactPhone: a11y service not connected")
            return ContactResolution.ServiceMissing
        }
        // ContactsContract queries hit the on-device content provider —
        // wrap in IO dispatcher so the voice coroutine doesn't block on
        // disk under load.
        val result = withContext(Dispatchers.IO) {
            service.actionExecutor.searchContacts(contactName, limit = 5)
        }
        if (!result.ok) {
            val err = result.error.orEmpty()
            Log.i(TAG, "C4 searchContacts failed: $err")
            // ActionExecutor.searchContacts uses specific phrasing for the
            // permission-denied paths ("Grant contacts permission" for the
            // upfront check, "Contacts permission revoked" for the mid-query
            // SecurityException). Either phrase → permission missing.
            val lower = err.lowercase()
            return if ("contacts permission" in lower || "permission revoked" in lower) {
                ContactResolution.PermissionMissing
            } else {
                ContactResolution.OtherError(err.ifBlank { "unknown error" })
            }
        }
        @Suppress("UNCHECKED_CAST")
        val contacts = result.data["contacts"] as? List<Map<String, Any?>>
            ?: return ContactResolution.NotFound
        if (contacts.isEmpty()) return ContactResolution.NotFound
        val firstContact = contacts.first()

        // ActionExecutor.searchContacts returns `phones` as a structured
        // List<Map<String,Any?>> with {number, type, label} entries per
        // 0.4.0. Pre-0.4.0 it was a comma-joined String — Bailey hit a
        // case where Hannah Dixon had a Galaxy Watch and a mobile and
        // we auto-picked the Watch. The new shape lets us prefer mobile
        // > main > home > work > other > custom/watch.
        @Suppress("UNCHECKED_CAST")
        val phonesList = firstContact["phones"] as? List<Map<String, Any?>>
            ?: return ContactResolution.NoPhoneNumber
        if (phonesList.isEmpty()) return ContactResolution.NoPhoneNumber

        val selected = pickPreferredPhone(phonesList)
            ?: return ContactResolution.NoPhoneNumber

        // Capture the display name (ActionExecutor.searchContacts returns
        // it under the "name" key, alongside "phones"). Fall back to the
        // raw query if somehow absent so downstream formatting never has
        // to handle null.
        val matchedName = (firstContact["name"] as? String)?.trim().orEmpty()
            .ifEmpty { contactName }
        return ContactResolution.Found(
            number = selected.number,
            matchedName = matchedName,
            totalMatches = contacts.size,
            phoneType = selected.type,
            phoneLabel = selected.label,
            totalPhones = phonesList.size,
        )
    }

    /**
     * Pick the best phone from a structured contact phones list.
     *
     * Post-0.4.0 ActionExecutor.searchContacts already sorts phones by
     * preference server-side (mobile > main > home > work > other >
     * custom/watch), so this function just returns the first entry
     * with a non-blank number. The centralized server-side ranker
     * benefits BOTH the voice fast-path (via this function) and the
     * LLM tool-calling path (which reads the pre-sorted list directly
     * from the search_contacts tool response) without duplicating the
     * ranking logic in two places.
     *
     * Returns null if no phone in the list has a non-blank `number`
     * field, which can happen on contacts partially edited in the
     * Contacts app.
     */
    private fun pickPreferredPhone(
        phones: List<Map<String, Any?>>,
    ): PickedPhone? {
        if (phones.isEmpty()) return null
        for (entry in phones) {
            val number = (entry["number"] as? String)?.trim()
            if (number.isNullOrBlank()) continue
            val type = (entry["type"] as? String)?.lowercase() ?: "other"
            val label = (entry["label"] as? String).orEmpty()
            return PickedPhone(number = number, type = type, label = label)
        }
        return null
    }

    private data class PickedPhone(
        val number: String,
        val type: String,
        val label: String,
    )

    /** Result of the C4 contact → phone number lookup. */
    private sealed class ContactResolution {
        /**
         * Successfully resolved to a phone number.
         *
         * @property number The first non-blank phone number from the
         *   matched contact's record.
         * @property matchedName The display name of the contact we ended
         *   up using. May differ from the spoken query in case or spelling
         *   — "john" spoken → "John Smith" in the address book.
         * @property totalMatches The number of contacts whose search hit
         *   the query string. When `totalMatches > 1` the UI should show a
         *   disambiguation hint so the user knows we had to choose. Full
         *   multi-turn picker is Wave 3.
         */
        data class Found(
            val number: String,
            val matchedName: String,
            val totalMatches: Int,
            /**
             * Canonical type key of the selected phone — `mobile` /
             * `home` / `work` / `main` / `other` / `custom` / etc.
             * Used by the voice trace formatter to say "texting Hannah's
             * mobile" instead of just "texting Hannah" so the user can
             * verify we picked the right number at a glance when the
             * contact has multiple entries (e.g. Galaxy Watch + mobile).
             */
            val phoneType: String = "unknown",
            /**
             * Human-readable label as stored in the Contacts app. For
             * standard types matches the framework-localized string
             * ("Mobile", "Work"). For `TYPE_CUSTOM` this carries the
             * user's custom string (often "Watch" on Samsung).
             */
            val phoneLabel: String = "",
            /**
             * Total number of phones on the matched contact record.
             * When > 1 we picked one via [pickPreferredPhone] — the UI
             * should mention this so the user can correct if wrong.
             */
            val totalPhones: Int = 1,
        ) : ContactResolution()
        /** a11y service not connected — whole bridge pipeline is offline. */
        data object ServiceMissing : ContactResolution()
        /** READ_CONTACTS runtime permission not granted. */
        data object PermissionMissing : ContactResolution()
        /** Query succeeded but no contact matched the spoken name. */
        data object NotFound : ContactResolution()
        /** Matched a contact but the record has no stored phone number. */
        data object NoPhoneNumber : ContactResolution()
        /** Everything else — surface the raw error so the user gets a hint. */
        data class OtherError(val message: String) : ContactResolution()
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
    private suspend fun resolveAppPackage(appName: String): AppResolution {
        val service = HermesAccessibilityService.instance ?: run {
            Log.w(TAG, "H5 resolveAppPackage: a11y service not connected")
            return AppResolution.ServiceMissing
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
            // Distinctive failure mode: PackageManager returned nothing
            // because the <queries> manifest declaration is missing. The
            // user can't fix this themselves — it's a build bug — so
            // report as OtherError rather than NotFound so the spoken
            // confirmation doesn't blame the user's vocabulary.
            Log.w(TAG, "H5 candidate list empty — missing <queries> in manifest?")
            return AppResolution.OtherError("Launcher app list empty. This is a build issue — please report it.")
        }

        val needle = appName.trim().lowercase()
        // Tier 1: exact label match.
        candidates.firstOrNull { it.second.lowercase() == needle }
            ?.let { return AppResolution.Found(it.first, it.second, "exact") }
        // Tier 2: label starts with the needle.
        candidates.firstOrNull { it.second.lowercase().startsWith(needle) }
            ?.let { return AppResolution.Found(it.first, it.second, "prefix") }
        // Tier 3: label contains the needle anywhere.
        candidates.firstOrNull { it.second.lowercase().contains(needle) }
            ?.let { return AppResolution.Found(it.first, it.second, "contains") }
        return AppResolution.NotFound
    }

    /** Result of the H5 launcher-inventory fuzzy match. */
    private sealed class AppResolution {
        data class Found(
            val packageName: String,
            val label: String,
            /** Which matcher tier won — `exact` | `prefix` | `contains`. */
            val matchTier: String,
        ) : AppResolution()
        /** a11y service not connected. */
        data object ServiceMissing : AppResolution()
        /** PackageManager returned candidates but none matched. */
        data object NotFound : AppResolution()
        /** Unexpected condition (e.g. empty candidate list due to manifest bug). */
        data class OtherError(val message: String) : AppResolution()
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
