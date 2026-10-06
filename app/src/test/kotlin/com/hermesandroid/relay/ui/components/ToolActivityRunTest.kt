package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolActivityRunTest {
    @Test
    fun `routine calls group in source order around attention surfaces`() {
        val read = call("read-1", "read_file")
        val search = call("search-1", "web_search")
        val edit = call("edit-1", "apply_patch")
        val command = call("command-1", "terminal")
        val failed = call("failed-1", "browser", success = false, error = "blocked")

        val items = groupTranscriptTools(listOf(read, search, edit, command, failed))

        assertEquals(4, items.size)
        assertEquals(listOf(read, search), (items[0] as ToolTranscriptItem.ActivityRun).calls)
        assertEquals(edit, (items[1] as ToolTranscriptItem.Standalone).call)
        assertEquals(listOf(command), (items[2] as ToolTranscriptItem.ActivityRun).calls)
        assertEquals(failed, (items[3] as ToolTranscriptItem.Standalone).call)
    }

    @Test
    fun `risk media approval and delegation tools never enter routine runs`() {
        val standalone = listOf(
            call("risk", "read_file").copy(outputRisk = "high"),
            call("image", "image_generate"),
            call("profile-image", "willow_create_image"),
            call("approval", "request_user_input"),
            call("delegate", "delegate_task"),
        )

        val items = groupTranscriptTools(standalone)

        assertEquals(standalone.size, items.size)
        assertTrue(items.all { it is ToolTranscriptItem.Standalone })
        assertTrue(items.all { it.isVisibleForToolDisplay("off") })
    }

    @Test
    fun `clean turn consolidation folds routine calls across messages into one run`() {
        val read = call("read-1", "read_file")
        val search = call("search-1", "web_search")
        val delegate = call("delegate-1", "delegate_task")
        val command = call("command-1", "terminal")

        val items = consolidateTurnTools(
            listOf(listOf(read), listOf(search), listOf(delegate, command), emptyList()),
        )

        assertEquals(4, items.size)
        assertEquals(
            listOf(read, search, command),
            (items[0].single() as ToolTranscriptItem.ActivityRun).calls,
        )
        assertTrue(items[1].isEmpty())
        assertEquals(delegate, (items[2].single() as ToolTranscriptItem.Standalone).call)
        assertTrue(items[3].isEmpty())
    }

    @Test
    fun `clean turn consolidation places the run at the first routine position`() {
        val edit = call("edit-1", "apply_patch")
        val read = call("read-1", "read_file")
        val grep = call("grep-1", "grep")

        val items = consolidateTurnTools(listOf(listOf(edit), listOf(read), listOf(grep)))

        assertEquals(edit, (items[0].single() as ToolTranscriptItem.Standalone).call)
        assertEquals(listOf(read, grep), (items[1].single() as ToolTranscriptItem.ActivityRun).calls)
        assertTrue(items[2].isEmpty())
    }

    @Test
    fun `off hides only ordinary activity scaffolding`() {
        val run = ToolTranscriptItem.ActivityRun(listOf(call("read", "read_file")))
        val failure = ToolTranscriptItem.Standalone(
            call("failed", "terminal", success = false, error = "denied"),
        )

        assertEquals(false, run.isVisibleForToolDisplay("off"))
        assertEquals(true, run.isVisibleForToolDisplay("compact"))
        assertEquals(true, failure.isVisibleForToolDisplay("off"))
    }

    @Test
    fun `summary counts retain ordinary activity categories`() {
        val counts = countToolActivity(
            listOf(
                call("read", "read_file"),
                call("search", "grep"),
                call("command", "terminal"),
                call("browser", "browser_navigate"),
                call("device", "android_tap"),
                call("other", "memory_lookup"),
            ),
        )

        assertEquals(ToolActivityCounts(1, 1, 1, 1, 1, 1), counts)
    }

    private fun call(
        id: String,
        name: String,
        success: Boolean? = true,
        error: String? = null,
    ) = ToolCall(
        id = id,
        name = name,
        args = null,
        result = null,
        success = success,
        isComplete = success != null,
        error = error,
        uiKey = id,
    )
}
