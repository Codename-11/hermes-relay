package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDirectionHeuristicsTest {
    @Test fun firstStrongCharacterDeterminesDirection() {
        assertTrue(isRtlText("مرحبا بالعالم"))
        assertFalse(isRtlText("Hello world"))
        assertTrue(isRtlText("مرحبا hello"))
        assertFalse(isRtlText("Hello مرحبا"))
        assertTrue(isRtlText("123 ! 😀 مرحبا"))
        assertFalse(isRtlText("123 ! 😀 hello"))
        assertFalse(isRtlText("😀 123 !"))
        assertFalse(isRtlText(""))
        assertTrue(isRtlText("\uD83A\uDD00 text")) // supplementary RTL character
    }
}
