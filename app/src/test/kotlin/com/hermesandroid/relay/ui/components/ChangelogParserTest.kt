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
    fun parsesCompleteReleaseAndDerivesToastDigest() {
        val raw = """
            {
              "schema": 3,
              "versions": [
                {
                  "version": "1.2.0",
                  "title": "A useful release",
                  "date": "2026-06-20",
                  "summary": "A plain-language explanation.",
                  "changes": [
                    {"id": "new-one", "kind": "added", "title": "New one",
                     "summary": "Use the new thing.", "highlight": true},
                    {"id": "better-one", "kind": "improved", "title": "Better one",
                     "summary": "The old thing is easier."},
                    {"id": "fixed-one", "kind": "fixed", "title": "Fixed one",
                     "summary": "The broken thing works."}
                  ],
                  "compatibility": ["Existing connections keep working."],
                  "playNotes": "Concise Play copy."
                }
              ]
            }
        """.trimIndent()

        val entry = ChangelogStore.parse(raw).versions.single()
        val digest = entry.resolvedToastDigest()

        assertEquals("A plain-language explanation.", entry.summary)
        assertEquals(listOf("New one"), entry.highlightedChanges().map { it.title })
        assertEquals(0, digest?.additionalFeatureCount)
        assertEquals(1, digest?.improvementCount)
        assertEquals(1, digest?.fixCount)
        assertEquals(listOf("Better one", "Fixed one"), digest?.preview)
        assertEquals(listOf("Existing connections keep working."), entry.compatibility)
        assertEquals("Concise Play copy.", entry.playNotes)
        assertEquals("v1.2.0 · 2026-06-20", entry.versionLine())
        assertEquals(
            listOf("Highlights", "Improved", "Fixed", "Compatibility"),
            entry.toGroups().map { it.header },
        )
        assertEquals(3, entry.toGroups().sumOf { it.bullets.size } - entry.compatibility.size)
    }

    @Test
    fun completeReleaseRendersEveryChangeOnce() {
        val entry = ChangelogVersion(
            version = "1.2.0",
            title = "Complete notes",
            summary = "Everything users need to know.",
            changes = listOf(
                ChangelogChange("a", CHANGE_KIND_ADDED, "First", "First detail", highlight = true),
                ChangelogChange("b", CHANGE_KIND_FIXED, "Second", "Second detail"),
            ),
        )

        val rendered = entry.toGroups().flatMap { it.bullets }

        assertEquals(2, rendered.size)
        assertEquals(1, rendered.count { it.startsWith("First") })
        assertEquals(1, rendered.count { it.startsWith("Second") })
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
