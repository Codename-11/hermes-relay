package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.AttachmentState
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentGalleryLayoutTest {

    @Test
    fun `one image keeps the existing single attachment renderer`() {
        val items = attachmentLayoutItems(listOf(image("one.png")))

        assertEquals(listOf(AttachmentLayoutItem.Single(0)), items)
    }

    @Test
    fun `two loaded images collapse into one gallery`() {
        val items = attachmentLayoutItems(
            listOf(image("one.png"), image("two.jpg")),
        )

        assertEquals(listOf(AttachmentLayoutItem.Gallery(listOf(0, 1))), items)
    }

    @Test
    fun `files split image runs so mixed attachment order is preserved`() {
        val items = attachmentLayoutItems(
            listOf(
                file("notes.pdf", "application/pdf"),
                image("one.png"),
                file("readme.txt", "text/plain"),
                image("two.jpg"),
            ),
        )

        assertEquals(
            listOf(
                AttachmentLayoutItem.Single(0),
                AttachmentLayoutItem.Single(1),
                AttachmentLayoutItem.Single(2),
                AttachmentLayoutItem.Single(3),
            ),
            items,
        )
    }

    @Test
    fun `loading and failed images split runs and remain retryable standalone cards`() {
        val items = attachmentLayoutItems(
            listOf(
                image("ready.png"),
                image("loading.png", AttachmentState.LOADING),
                image("failed.png", AttachmentState.FAILED),
                image("ready-too.png"),
            ),
        )

        assertEquals(
            listOf(
                AttachmentLayoutItem.Single(0),
                AttachmentLayoutItem.Single(1),
                AttachmentLayoutItem.Single(2),
                AttachmentLayoutItem.Single(3),
            ),
            items,
        )
    }

    @Test
    fun `gallery rows use two columns and leave an odd final image spanning the row`() {
        assertEquals(listOf(listOf(0, 1)), galleryRows(2))
        assertEquals(listOf(listOf(0, 1), listOf(2)), galleryRows(3))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), galleryRows(4))
    }

    @Test
    fun `only four bounded thumbnails are composed for a large gallery`() {
        assertEquals(listOf(0, 1, 2, 3), galleryPreviewIndices(12))
    }

    @Test
    fun `separate contiguous image runs become separate galleries`() {
        val items = attachmentLayoutItems(
            listOf(
                image("one.png"),
                image("two.png"),
                file("notes.pdf", "application/pdf"),
                image("three.png"),
                image("four.png"),
            ),
        )

        assertEquals(
            listOf(
                AttachmentLayoutItem.Gallery(listOf(0, 1)),
                AttachmentLayoutItem.Single(2),
                AttachmentLayoutItem.Gallery(listOf(3, 4)),
            ),
            items,
        )
    }

    private fun image(
        name: String,
        state: AttachmentState = AttachmentState.LOADED,
    ) = Attachment(
        contentType = if (name.endsWith(".jpg")) "image/jpeg" else "image/png",
        content = "bytes",
        fileName = name,
        state = state,
    )

    private fun file(name: String, mime: String) = Attachment(
        contentType = mime,
        content = "bytes",
        fileName = name,
    )
}
