package com.hermesandroid.relay.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaCacheWriterTest {

    @Test
    fun cacheFile_promotesStreamedFileWithoutByteArrayHandoff() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val source = File.createTempFile("hermes-media-test-", ".part", context.cacheDir)
            .apply { writeText("streamed-media") }
        val writer = MediaCacheWriter(context) { 32 }

        val cached = writer.cacheFile(source, "audio/mpeg", "voice reply.mp3")

        assertFalse(source.exists())
        assertEquals("streamed-media", cached.readText())
        writer.clear()
    }
}
