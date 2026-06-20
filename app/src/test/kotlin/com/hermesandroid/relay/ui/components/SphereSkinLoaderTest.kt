package com.hermesandroid.relay.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SphereSkinLoaderTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File =
        createTempDirectory("sphere-skin-test").toFile().also { tempDirs.add(it) }

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /** Write [json] to `<parent>/<fileName>` and return the file. */
    private fun writeSkin(parent: File, fileName: String, json: String): File =
        File(parent, fileName).also { it.writeText(json) }

    @Test
    fun `valid minimal skin loads one entry`() {
        val dir = tempDir()
        writeSkin(
            dir,
            "ember.json",
            """{ "id": "ember", "label": "Ember", "defaults": { "color1": "#FFC24B", "color2": "#FF7A3C" } }""",
        )

        val skins = SphereSkinLoader.loadUserSkins(dir)

        assertEquals(1, skins.size)
        val skin = skins.single()
        assertEquals("ember", skin.id)
        assertEquals("Ember", skin.label)
        assertEquals(SphereSkinSource.USER, skin.source)
    }

    @Test
    fun `blank id falls back to the filename`() {
        val dir = tempDir()
        writeSkin(
            dir,
            "myorb.json",
            """{ "id": "", "label": "My Orb", "defaults": { "color1": "#FFC24B", "color2": "#FF7A3C" } }""",
        )

        val skins = SphereSkinLoader.loadUserSkins(dir)

        assertEquals(1, skins.size)
        assertEquals("myorb", skins.single().id)
    }

    @Test
    fun `a malformed file does not break a valid one`() {
        val dir = tempDir()
        writeSkin(
            dir,
            "good.json",
            """{ "id": "good", "label": "Good", "defaults": { "color1": "#FFC24B", "color2": "#FF7A3C" } }""",
        )
        writeSkin(dir, "bad.json", """not json {""")

        val skins = SphereSkinLoader.loadUserSkins(dir)

        assertEquals(1, skins.size)
        assertEquals("good", skins.single().id)
    }

    @Test
    fun `non-json files are ignored`() {
        val dir = tempDir()
        writeSkin(
            dir,
            "ember.json",
            """{ "id": "ember", "label": "Ember", "defaults": { "color1": "#FFC24B", "color2": "#FF7A3C" } }""",
        )
        writeSkin(dir, "notes.txt", """{ "id": "txt", "defaults": { "color1": "#FFFFFF", "color2": "#000000" } }""")
        writeSkin(dir, "readme.md", "ignore me")

        val ids = SphereSkinLoader.loadUserSkins(dir).map { it.id }

        assertEquals(listOf("ember"), ids)
    }

    @Test
    fun `an empty directory yields no skins`() {
        assertTrue(SphereSkinLoader.loadUserSkins(tempDir()).isEmpty())
    }

    @Test
    fun `skins are sorted by filename`() {
        val dir = tempDir()
        writeSkin(
            dir,
            "zebra.json",
            """{ "id": "zebra", "label": "Zebra", "defaults": { "color1": "#FFC24B", "color2": "#FF7A3C" } }""",
        )
        writeSkin(
            dir,
            "apple.json",
            """{ "id": "apple", "label": "Apple", "defaults": { "color1": "#3BE0C2", "color2": "#8A6BFF" } }""",
        )

        val ids = SphereSkinLoader.loadUserSkins(dir).map { it.id }

        assertEquals(listOf("apple", "zebra"), ids)
    }
}
