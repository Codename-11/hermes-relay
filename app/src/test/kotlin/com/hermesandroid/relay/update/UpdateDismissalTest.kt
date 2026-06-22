package com.hermesandroid.relay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the unified update banner's per-version dismissal +
 * the dismiss-key derivation. No DataStore / Android involved — exercises the
 * exact decision `rememberUpdateAvailability` makes to hide/show the banner.
 */
class UpdateDismissalTest {

    // ── dismissKey derivation ─────────────────────────────────────────────

    @Test fun `dismissKey prefers numeric versionCode when present (Play)`() {
        val s = UpdateStatus.Available(versionLabel = "1.3.0", versionCode = 17L)
        assertEquals("17", s.dismissKey)
    }

    @Test fun `dismissKey falls back to versionLabel when no code (sideload)`() {
        val s = UpdateStatus.Available(versionLabel = "1.3.0", versionCode = null)
        assertEquals("1.3.0", s.dismissKey)
    }

    @Test fun `dismissKey is null for non-actionable statuses`() {
        assertNull(UpdateStatus.UpToDate.dismissKey)
        assertNull(UpdateStatus.Unsupported.dismissKey)
    }

    @Test fun `dismissKey covers Downloading and Downloaded`() {
        assertEquals("9", UpdateStatus.Downloading("1.1.0", 9L).dismissKey)
        assertEquals("1.2.0", UpdateStatus.Downloaded("1.2.0", null).dismissKey)
    }

    // ── per-version dismissal: never dismissed when nothing stored ────────

    @Test fun `not dismissed when no dismissed key stored`() {
        val s = UpdateStatus.Available(versionLabel = "1.3.0", versionCode = 17L)
        assertFalse(UpdateDismissalPreferences.isDismissed(s, dismissed = null))
        assertFalse(UpdateDismissalPreferences.isDismissed(s, dismissed = ""))
    }

    @Test fun `non-actionable status is never dismissed`() {
        assertFalse(UpdateDismissalPreferences.isDismissed(UpdateStatus.UpToDate, "17"))
        assertFalse(UpdateDismissalPreferences.isDismissed(UpdateStatus.Unsupported, "17"))
    }

    // ── per-version dismissal: Play (numeric versionCode) ─────────────────

    @Test fun `same versionCode stays dismissed (Play)`() {
        val s = UpdateStatus.Available(versionLabel = "1.3.0", versionCode = 17L)
        assertTrue(UpdateDismissalPreferences.isDismissed(s, dismissed = "17"))
    }

    @Test fun `older offer than dismissed stays hidden (Play)`() {
        // Edge case: an older code than the one already dismissed should not
        // re-nag — only a strictly newer one re-shows.
        val s = UpdateStatus.Available(versionLabel = "1.2.0", versionCode = 16L)
        assertTrue(UpdateDismissalPreferences.isDismissed(s, dismissed = "17"))
    }

    @Test fun `newer versionCode re-shows the banner (Play)`() {
        val s = UpdateStatus.Available(versionLabel = "1.4.0", versionCode = 18L)
        assertFalse(UpdateDismissalPreferences.isDismissed(s, dismissed = "17"))
    }

    // ── per-version dismissal: sideload (version string) ──────────────────

    @Test fun `same version string stays dismissed (sideload)`() {
        val s = UpdateStatus.Available(versionLabel = "1.3.0", versionCode = null)
        assertTrue(UpdateDismissalPreferences.isDismissed(s, dismissed = "1.3.0"))
    }

    @Test fun `newer version string re-shows the banner (sideload)`() {
        val s = UpdateStatus.Available(versionLabel = "1.4.0", versionCode = null)
        assertFalse(UpdateDismissalPreferences.isDismissed(s, dismissed = "1.3.0"))
    }

    @Test fun `older version string stays hidden (sideload)`() {
        val s = UpdateStatus.Available(versionLabel = "1.2.0", versionCode = null)
        assertTrue(UpdateDismissalPreferences.isDismissed(s, dismissed = "1.3.0"))
    }

    @Test fun `Downloaded status respects per-version dismissal logic too`() {
        // (The UI never suppresses Downloaded, but the pure predicate is
        // consistent: a dismissed-then-downloaded same version reads dismissed.)
        val downloaded = UpdateStatus.Downloaded(versionLabel = "1.3.0", versionCode = 17L)
        assertTrue(UpdateDismissalPreferences.isDismissed(downloaded, dismissed = "17"))
        val newer = UpdateStatus.Downloaded(versionLabel = "1.4.0", versionCode = 18L)
        assertFalse(UpdateDismissalPreferences.isDismissed(newer, dismissed = "17"))
    }

    // ── isStrictlyNewer direct coverage ───────────────────────────────────

    @Test fun `isStrictlyNewer numeric`() {
        assertTrue(UpdateDismissalPreferences.isStrictlyNewer("18", "17"))
        assertFalse(UpdateDismissalPreferences.isStrictlyNewer("17", "17"))
        assertFalse(UpdateDismissalPreferences.isStrictlyNewer("16", "17"))
    }

    @Test fun `isStrictlyNewer semver string`() {
        assertTrue(UpdateDismissalPreferences.isStrictlyNewer("1.4.0", "1.3.0"))
        assertFalse(UpdateDismissalPreferences.isStrictlyNewer("1.3.0", "1.3.0"))
        assertFalse(UpdateDismissalPreferences.isStrictlyNewer("1.2.0", "1.3.0"))
    }

    @Test fun `isStrictlyNewer mixed-parse falls back to string semver`() {
        // One numeric, one not → both routed through compareVersions, which
        // tokenizes leading digits. "abc" → 0, so "1.0.0" is newer.
        assertTrue(UpdateDismissalPreferences.isStrictlyNewer("1.0.0", "abc"))
    }
}
