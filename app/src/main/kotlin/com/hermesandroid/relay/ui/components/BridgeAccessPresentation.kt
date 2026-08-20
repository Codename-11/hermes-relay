package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.bridge.BridgeCapability
import com.hermesandroid.relay.bridge.BridgeCapabilityPolicy
import com.hermesandroid.relay.viewmodel.BridgePermissionStatus

enum class BridgeAccessPreset {
    READ_ONLY,
    READ_CONFIRMED,
    CUSTOM,
}

val READ_ONLY_BRIDGE_CAPABILITIES: Set<BridgeCapability> = setOf(
    BridgeCapability.DEVICE_INFO,
    BridgeCapability.CONTACTS_READ,
    BridgeCapability.LOCATION_READ,
    BridgeCapability.CLIPBOARD_READ,
)

val READ_CONFIRMED_BRIDGE_CAPABILITIES: Set<BridgeCapability> =
    READ_ONLY_BRIDGE_CAPABILITIES + setOf(
        BridgeCapability.COMMUNICATIONS,
        BridgeCapability.OUTBOUND_SHARING,
    )

enum class BridgeAndroidRequirement {
    ACCESSIBILITY,
    CONTACTS,
    LOCATION,
    SMS,
    PHONE,
    OVERLAY,
}

data class BridgeAndroidAccessSummary(
    val required: Set<BridgeAndroidRequirement>,
    val ready: Set<BridgeAndroidRequirement>,
) {
    val missing: Set<BridgeAndroidRequirement> get() = required - ready
    val allReady: Boolean get() = missing.isEmpty()
}

fun BridgeCapabilityPolicy.hasAnyGrant(nowMs: Long): Boolean =
    permanentGrants.isNotEmpty() || timedExpiriesMs.any { (capability, expiry) ->
        capability.timed && expiry > nowMs
    }

fun BridgeCapabilityPolicy.activeTimedCapabilities(nowMs: Long): Set<BridgeCapability> =
    timedExpiriesMs.filterValues { it > nowMs }.keys

fun BridgeCapabilityPolicy.displayPreset(): BridgeAccessPreset? = when (permanentGrants) {
    READ_ONLY_BRIDGE_CAPABILITIES -> BridgeAccessPreset.READ_ONLY
    READ_CONFIRMED_BRIDGE_CAPABILITIES -> BridgeAccessPreset.READ_CONFIRMED
    else -> if (permanentGrants.isEmpty()) null else BridgeAccessPreset.CUSTOM
}

fun bridgeAndroidAccessSummary(
    policy: BridgeCapabilityPolicy,
    status: BridgePermissionStatus,
    nowMs: Long,
): BridgeAndroidAccessSummary {
    val timed = policy.activeTimedCapabilities(nowMs)
    val required = buildSet {
        // Current BridgeCommandHandler is service-owned even for passive
        // commands. Keep this visible until non-screen executors are split.
        if (policy.permanentGrants.isNotEmpty() || timed.isNotEmpty()) {
            add(BridgeAndroidRequirement.ACCESSIBILITY)
        }
        if (BridgeCapability.CONTACTS_READ in policy.permanentGrants) {
            add(BridgeAndroidRequirement.CONTACTS)
        }
        if (BridgeCapability.LOCATION_READ in policy.permanentGrants) {
            add(BridgeAndroidRequirement.LOCATION)
        }
        if (BridgeCapability.COMMUNICATIONS in policy.permanentGrants) {
            add(BridgeAndroidRequirement.SMS)
            add(BridgeAndroidRequirement.PHONE)
        }
        if (BridgeCapability.SCREEN_CONTROL in timed ||
            BridgeCapability.COMMUNICATIONS in policy.permanentGrants ||
            BridgeCapability.OUTBOUND_SHARING in policy.permanentGrants
        ) {
            add(BridgeAndroidRequirement.OVERLAY)
        }
    }
    val ready = required.filterTo(linkedSetOf()) { requirement ->
        when (requirement) {
            BridgeAndroidRequirement.ACCESSIBILITY -> status.accessibilityServiceEnabled
            BridgeAndroidRequirement.CONTACTS -> status.contactsPermitted
            BridgeAndroidRequirement.LOCATION -> status.locationPermitted
            BridgeAndroidRequirement.SMS -> status.smsPermitted
            BridgeAndroidRequirement.PHONE -> status.phonePermitted
            BridgeAndroidRequirement.OVERLAY -> status.overlayPermitted
        }
    }
    return BridgeAndroidAccessSummary(required = required, ready = ready)
}
