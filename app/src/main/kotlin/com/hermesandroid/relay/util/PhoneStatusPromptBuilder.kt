package com.hermesandroid.relay.util

// === PHASE3-status: dynamic phone-status system prompt block ===
//
// Replaces the static one-sentence "app context prompt" with a transparent,
// granular block that reflects real bridge/permission state. Each enabled
// sub-category adds one short line; the master toggle gates all of them.
//
// Design goals:
//   - Pure function, no side effects, no suspend, no I/O. Snapshot is built
//     by the caller from already-cached state (no `getRunningTasks`, no
//     reflection in the hot path beyond what ChatViewModel already does).
//   - Returns `null` when there's literally nothing to say, so the system
//     message is omitted entirely from the request (no `""` payloads).
//   - Output capped under ~100 words (~150 tokens). Everything beyond the
//     minimal preamble lives behind the `android_phone_status` tool the
//     agent can call on demand.
//   - Privacy-sensitive fields (current_app, battery) default OFF and only
//     add lines when the user explicitly opted in via sub-toggle.
//
// Output shape examples live in the task brief; the gold-master text is
// reproduced exactly by `buildPromptBlock` + preview card in ChatSettingsScreen.

/**
 * User-facing toggle state for the phone-status prompt block. Mirrors the
 * five DataStore keys in `ConnectionViewModel`:
 *   - [master]        — master enable (KEY_APP_CONTEXT)
 *   - [bridgeState]   — advertise bridge channel + permission summary
 *   - [currentApp]    — advertise foregrounded package name (privacy-sensitive)
 *   - [battery]       — advertise battery percent (privacy-sensitive)
 *   - [safetyStatus]  — advertise blocklist / destructive-verb / auto-disable
 *
 * `master` off means everything returns `null`, regardless of sub-toggles.
 */
data class AppContextSettings(
    val master: Boolean = true,
    val bridgeState: Boolean = true,
    val currentApp: Boolean = false,
    val battery: Boolean = false,
    val safetyStatus: Boolean = true,
)

/**
 * Snapshot of the phone-side state that can go into a system prompt. All
 * fields are nullable because the caller populates them from sources that
 * may not exist yet (Phase 3 accessibility service, bridge safety manager,
 * media-projection holder). A null field is elided from the output; the
 * builder never emits "unknown" or "N/A" text.
 *
 * None of these fields should be expensive to read — the caller constructs
 * a PhoneSnapshot on every send() call and we want that to be microseconds,
 * not milliseconds.
 */
data class PhoneSnapshot(
    // --- Bridge / accessibility ---
    /** True iff HermesAccessibilityService has a live `instance`. */
    val bridgeBound: Boolean = false,
    /** True iff the user's master-enable DataStore key is set. */
    val masterEnabled: Boolean = false,
    /** True iff `rootInActiveWindow` is reachable (a11y permission granted). */
    val accessibilityGranted: Boolean = false,
    /** True iff `MediaProjectionHolder.projection != null`. */
    val screenCaptureGranted: Boolean = false,
    /** True iff `Settings.canDrawOverlays(context)`. */
    val overlayGranted: Boolean = false,
    /** True iff the notification listener companion is bound. */
    val notificationsGranted: Boolean = false,

    // --- Privacy-sensitive (only populated when user opted in) ---
    /** Foreground package name, e.g. "com.android.chrome". */
    val currentApp: String? = null,
    /** Battery percent 0..100. */
    val batteryPercent: Int? = null,

    // --- Safety manager summary ---
    /** Count of blocked package names in `BridgeSafetyManager.settings`. */
    val blocklistCount: Int? = null,
    /** Count of destructive verbs in `BridgeSafetyManager.settings`. */
    val destructiveVerbCount: Int? = null,
    /** Configured auto-disable idle timer in minutes. */
    val autoDisableMinutes: Int? = null,
)

private const val PREAMBLE =
    "The user is chatting via the Hermes-Relay Android app. " +
    "Keep responses mobile-friendly and concise when possible."

/**
 * Build the system-prompt block from [settings] + [snapshot].
 *
 * Returns `null` iff:
 *   1. [AppContextSettings.master] is false, OR
 *   2. All sub-toggles are off (nothing would be appended beyond the
 *      preamble AND there's no reason to advertise the mobile client).
 *
 * Rule (2) is technically lenient — we always include the preamble when
 * `master` is on, even if every sub-toggle is off, because the preamble
 * itself is useful (it's the original v0.1.0 behavior). The "everything
 * off → null" case in the brief is the `master=false` branch.
 */
fun buildPromptBlock(settings: AppContextSettings, snapshot: PhoneSnapshot): String? {
    if (!settings.master) return null

    val lines = mutableListOf<String>()
    lines += PREAMBLE

    if (settings.bridgeState) {
        lines += buildBridgeLine(snapshot)
    }

    if (settings.currentApp && snapshot.currentApp != null) {
        lines += "Foreground app: ${snapshot.currentApp}."
    }

    if (settings.battery && snapshot.batteryPercent != null) {
        lines += "Battery: ${snapshot.batteryPercent}%."
    }

    if (settings.safetyStatus) {
        buildSafetyLine(snapshot)?.let { lines += it }
    }

    return lines.joinToString("\n\n")
}

/**
 * Bridge-channel summary. Always emitted when `bridgeState` is on, even if
 * the bridge isn't bound — "Phone bridge: not installed" is itself useful
 * context (tells the agent not to try tool calls into the phone).
 */
private fun buildBridgeLine(snapshot: PhoneSnapshot): String {
    if (!snapshot.bridgeBound) {
        return "Phone bridge: not connected. Tool calls into the phone are unavailable."
    }
    if (!snapshot.masterEnabled) {
        return "Phone bridge: connected but disabled by user. Ask before requesting control."
    }
    val perms = mutableListOf<String>()
    if (snapshot.accessibilityGranted) perms += "accessibility"
    if (snapshot.screenCaptureGranted) perms += "screen capture"
    if (snapshot.overlayGranted) perms += "overlay"
    if (snapshot.notificationsGranted) perms += "notifications"

    val permsText = if (perms.isEmpty()) "no permissions granted yet" else "Permissions: ${perms.joinToString(", ")}"
    return "Phone bridge: enabled. $permsText. " +
        "For full phone status (current app, battery, blocklist), call the android_phone_status tool."
}

/**
 * Safety-rails one-liner. Only emitted when at least one safety field is
 * non-null; returns null if the safety manager hasn't been initialized yet.
 */
private fun buildSafetyLine(snapshot: PhoneSnapshot): String? {
    val parts = mutableListOf<String>()
    snapshot.blocklistCount?.let { parts += "$it blocked apps" }
    snapshot.destructiveVerbCount?.let { parts += "$it destructive verbs" }
    snapshot.autoDisableMinutes?.let { parts += "${it}m auto-disable" }
    if (parts.isEmpty()) return null
    return "Safety rails: ${parts.joinToString(", ")}."
}
// === END PHASE3-status ===
