package com.hermesandroid.relay.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScreenReaderTest {
    private val reader = ScreenReader()
    private val zeroBounds = ScreenReader.Bounds(0, 0, 0, 0)

    @Test
    fun readScreenWithoutBoundsPreservesNodesAndSerializedContent() {
        val root = node(bounds = Rect(0, 0, 100, 200))
        val label = node(text = "Document", bounds = Rect(5, 10, 90, 30))
        val action = node(bounds = Rect(5, 40, 90, 60)).apply {
            contentDescription = "Open"
            isClickable = true
        }
        val input = node(bounds = Rect(5, 70, 90, 90)).apply {
            isEditable = true
            isFocused = true
        }
        shadowOf(root).addChild(label)
        shadowOf(root).addChild(action)
        shadowOf(root).addChild(input)

        val full = reader.readScreen(root, includeBounds = true)
        val compact = reader.readScreen(root, includeBounds = false)

        assertEquals(3, full.nodeCount)
        assertEquals(full.nodeCount, compact.nodeCount)
        assertFalse(compact.truncated)
        assertEquals(full.nodes.map { it.copy(bounds = zeroBounds) }, compact.nodes)
        // Root geometry and all non-bounds fields retain the existing wire contract.
        assertEquals(
            Json.encodeToString(ScreenReader.ScreenContent.serializer(), full.copy(nodes = compact.nodes)),
            Json.encodeToString(ScreenReader.ScreenContent.serializer(), compact),
        )
    }

    @Test
    fun boundsFlagDoesNotChangeMultiWindowIdsOrRootUnion() {
        val roots = listOf(
            node(text = "First", bounds = Rect(10, 10, 100, 100)),
            node(text = "Empty", bounds = Rect(20, 20, 20, 20)),
            node(text = "Second", bounds = Rect(150, 200, 300, 400)),
        )
        val full = reader.readAllWindows(roots)
        val compact = reader.readAllWindows(roots, includeBounds = false)

        assertEquals(listOf("w0:0", "w2:1"), compact.nodes.map { it.nodeId })
        assertEquals(ScreenReader.Bounds(10, 10, 300, 400), compact.rootBounds)
        assertEquals(full.copy(nodes = full.nodes.map { it.copy(bounds = zeroBounds) }), compact)
    }

    @Test
    fun emptyOrInvertedRealBoundsRemainIneligibleInEitherMode() {
        val roots = listOf(
            node(text = "Zero width", bounds = Rect(10, 10, 10, 20)),
            node(text = "Zero height", bounds = Rect(10, 10, 20, 10)),
            node(text = "Inverted", bounds = Rect(20, 20, 10, 10)),
            node(text = "Visible", bounds = Rect(10, 10, 20, 20)),
        )
        for (includeBounds in listOf(true, false)) {
            val result = reader.readAllWindows(roots, includeBounds)
            assertEquals(listOf("Visible"), result.nodes.map { it.text })
            assertEquals(listOf("w3:0"), result.nodes.map { it.nodeId })
        }
    }

    @Test
    fun boundsFlagPreservesNodeCapAndTruncation() {
        val roots = List(ScreenReader.MAX_NODES + 1) { index ->
            node(text = "Item $index", bounds = Rect(0, 0, 10, 10))
        }
        val full = reader.readAllWindows(roots)
        val compact = reader.readAllWindows(roots, includeBounds = false)

        assertEquals(ScreenReader.MAX_NODES, compact.nodeCount)
        assertTrue(compact.truncated)
        assertEquals(full.copy(nodes = full.nodes.map { it.copy(bounds = zeroBounds) }), compact)
    }

    @Suppress("DEPRECATION")
    private fun node(text: String? = null, bounds: Rect): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            packageName = "example.app"
            className = "android.view.View"
            this.text = text
            isVisibleToUser = true
            isEnabled = true
            setBoundsInScreen(bounds)
        }
}
