package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the bundled-changelog parser ([ChangelogStore.parse]) and
 * the [ChangelogVersion] view helpers. No Android dependency — only the
 * kotlinx.serialization decode path and string formatting are exercised, so the
 * Android asset stream ([ChangelogStore.load]) is intentionally out of scope.
 */
class ChangelogParserTest {

    @Test
    fun parsesVersionsInFileOrder() {
        val raw = """
            {
              "versions": [
                {"version": "1.2.0", "title": "Latest", "date": "2026-06-20",
                 "sections": [{"header": "New", "bullets": ["a", "b"]}]},
                {"version": "1.1.0", "title": "Older", "date": "2026-06-16",
                 "sections": [{"header": "Fixed", "bullets": ["c"]}]}
              ]
            }
        """.trimIndent()

        val changelog = ChangelogStore.parse(raw)

        assertEquals(2, changelog.versions.size)
        // File order is authored newest-first and must be preserved verbatim.
        assertEquals("1.2.0", changelog.versions[0].version)
        assertEquals("1.1.0", changelog.versions[1].version)
        assertEquals("Latest", changelog.versions[0].title)
        assertEquals(listOf("a", "b"), changelog.versions[0].sections.first().bullets)
    }

    @Test
    fun blankInputYieldsEmptyChangelog() {
        assertTrue(ChangelogStore.parse("").versions.isEmpty())
        assertTrue(ChangelogStore.parse("   \n  ").versions.isEmpty())
    }

    @Test
    fun malformedJsonFallsBackToEmptyInsteadOfThrowing() {
        // The dialog falls back to whats_new.txt when this returns empty, so a
        // garbled asset must never crash the parse.
        assertTrue(ChangelogStore.parse("{ this is not json").versions.isEmpty())
        assertTrue(ChangelogStore.parse("[]").versions.isEmpty())
    }

    @Test
    fun ignoresUnknownTopLevelAndSectionKeys() {
        // Future authored fields (e.g. a "summary") must not break older apps.
        val raw = """
            {
              "schema": 2,
              "versions": [
                {"version": "1.0.0", "summary": "ignored",
                 "sections": [{"header": "H", "bullets": ["x"], "icon": "star"}]}
              ]
            }
        """.trimIndent()

        val changelog = ChangelogStore.parse(raw)

        assertEquals("1.0.0", changelog.versions.single().version)
        assertEquals(listOf("x"), changelog.versions.single().sections.single().bullets)
    }

    @Test
    fun optionalFieldsDefaultGracefully() {
        // Only `version` is required; title/date/sections may be absent.
        val raw = """{"versions": [{"version": "0.9.0"}]}"""

        val entry = ChangelogStore.parse(raw).versions.single()

        assertNull(entry.title)
        assertNull(entry.date)
        assertTrue(entry.sections.isEmpty())
        assertTrue(entry.toGroups().isEmpty())
    }

    @Test
    fun subtitleJoinsVersionTitleAndDate() {
        val entry = ChangelogVersion(
            version = "1.2.0",
            title = "Make it yours",
            date = "2026-06-20",
        )
        assertEquals("v1.2.0 — Make it yours · 2026-06-20", entry.subtitle())
    }

    @Test
    fun subtitleOmitsMissingTokens() {
        assertEquals("v1.2.0", ChangelogVersion(version = "1.2.0").subtitle())
        assertEquals(
            "v1.2.0 — Title",
            ChangelogVersion(version = "1.2.0", title = "Title").subtitle(),
        )
        assertEquals(
            "v1.2.0 · 2026-06-20",
            ChangelogVersion(version = "1.2.0", date = "2026-06-20").subtitle(),
        )
    }

    @Test
    fun toGroupsDropsBlankHeaders() {
        val entry = ChangelogVersion(
            version = "1.0.0",
            sections = listOf(
                ChangelogSection(header = "  ", bullets = listOf("a")),
                ChangelogSection(header = "Real", bullets = listOf("b")),
            ),
        )

        val groups = entry.toGroups()

        assertNull("blank header should normalize to null", groups[0].header)
        assertEquals("Real", groups[1].header)
    }

    @Test
    fun toNotesUsesSubtitleAsVersionLine() {
        val notes = ChangelogVersion(
            version = "1.2.0",
            title = "Make it yours",
            date = "2026-06-20",
            sections = listOf(ChangelogSection(header = "New", bullets = listOf("a"))),
        ).toNotes()

        assertEquals("v1.2.0 — Make it yours · 2026-06-20", notes.version)
        assertEquals(1, notes.groups.size)
        assertEquals("New", notes.groups.single().header)
        assertEquals(listOf("a"), notes.groups.single().bullets)
    }
}
