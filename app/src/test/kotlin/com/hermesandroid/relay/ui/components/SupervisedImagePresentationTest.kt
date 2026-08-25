package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisedImagePresentationTest {
    @Test
    fun `disabled assistant images strip markdown without exposing a fetchable source`() {
        val content = "Here it is ![result](https://example.com/private.png) and ![local](/tmp/result.png)"

        val (body, images) = assistantImageContent(content, showImages = false)

        assertEquals("Here it is  and", body)
        assertTrue(images.isEmpty())
    }

    @Test
    fun `enabled assistant images preserve all supported sources`() {
        val content = "![remote](https://example.com/a.png) ![local](/tmp/b.png)"

        val (_, images) = assistantImageContent(content, showImages = true)

        assertEquals(listOf("https://example.com/a.png", "/tmp/b.png"), images.map { it.src })
    }
}
