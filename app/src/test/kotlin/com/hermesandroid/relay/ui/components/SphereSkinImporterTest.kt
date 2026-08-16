package com.hermesandroid.relay.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class SphereSkinImporterTest {
    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File = createTempDirectory("sphere-import-test").toFile().also(tempDirs::add)

    @After
    fun cleanup() = tempDirs.forEach { it.deleteRecursively() }

    @Test
    fun `valid skin is installed and loadable`() {
        val dir = tempDir()
        val result = SphereSkinImporter.importStream(
            ByteArrayInputStream(
                """{"id":"ocean","label":"Ocean","defaults":{"color1":"#00AACC","color2":"#6655FF"}}"""
                    .encodeToByteArray(),
            ),
            dir,
        )

        assertEquals(SphereSkinImportResult.Success("ocean", "Ocean"), result)
        assertEquals(listOf("ocean"), SphereSkinLoader.loadUserSkins(dir).map { it.id })
    }

    @Test
    fun `invalid json installs nothing`() {
        val dir = tempDir()
        val result = SphereSkinImporter.importStream(ByteArrayInputStream("not json".encodeToByteArray()), dir)

        assertTrue(result is SphereSkinImportResult.Failure)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `oversized input is rejected`() {
        val dir = tempDir()
        val result = SphereSkinImporter.importStream(ByteArrayInputStream(ByteArray(256 * 1024 + 1)), dir)

        assertTrue(result is SphereSkinImportResult.Failure)
        assertFalse(dir.resolve("custom-sphere.json").exists())
    }
}
