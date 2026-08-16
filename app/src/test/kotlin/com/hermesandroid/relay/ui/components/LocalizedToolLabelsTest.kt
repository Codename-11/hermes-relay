package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalizedToolLabelsTest {

    @Test
    fun knownToolNamesMapToStringResources() {
        assertEquals(R.string.tool_name_terminal, localizeToolNameKey("terminal_execute"))
        assertEquals(R.string.tool_name_web_search, localizeToolNameKey("web_search"))
        assertEquals(R.string.tool_name_execute_code, localizeToolNameKey("execute_code"))
        assertEquals(R.string.tool_name_read_file, localizeToolNameKey("read_file"))
        assertEquals(R.string.tool_name_write_file, localizeToolNameKey("write_file"))
        assertEquals(R.string.tool_name_write_file, localizeToolNameKey("apply_patch"))
        assertEquals(R.string.tool_name_session, localizeToolNameKey("session_search"))
        assertEquals(R.string.tool_name_memory, localizeToolNameKey("mnemosyne_store"))
        assertEquals(R.string.tool_name_android, localizeToolNameKey("android_phone_action"))
        assertEquals(R.string.tool_name_android, localizeToolNameKey("android_search_contacts"))
        assertEquals(R.string.tool_name_computer, localizeToolNameKey("computer_use"))
    }

    @Test
    fun unknownToolNamesReturnNull() {
        assertNull(localizeToolNameKey("totally_unknown_tool"))
        assertNull(localizeToolNameKey(""))
    }

    @Test
    fun knownBadgesMapToStringResources() {
        assertEquals(R.string.badge_tool_failed, localizeBadgeKey("Tool failed"))
        assertEquals(R.string.badge_memory, localizeBadgeKey("Memory"))
        assertEquals(R.string.badge_skill, localizeBadgeKey("Skill"))
        assertEquals(R.string.badge_artifact, localizeBadgeKey("Artifact"))
        assertEquals(R.string.badge_response_interrupted, localizeBadgeKey("Response interrupted"))
        assertEquals(R.string.badge_unknown_error, localizeBadgeKey("Unknown error"))
        assertEquals(R.string.badge_stopped, localizeBadgeKey("Stopped"))
        assertEquals(R.string.bubble_realtime_agent, localizeBadgeKey("Realtime Agent"))
        assertEquals(R.string.bubble_voice, localizeBadgeKey("Voice"))
    }

    @Test
    fun unknownBadgesReturnNull() {
        assertNull(localizeBadgeKey("Some other badge"))
    }

    @Test
    fun knownAgentNamesMapToStringResources() {
        assertEquals(R.string.agent_send_sms, localizeAgentNameKey("Send SMS"))
        assertEquals(R.string.agent_call, localizeAgentNameKey("Call"))
        assertEquals(R.string.agent_search_contacts, localizeAgentNameKey("Search Contacts"))
        assertEquals(R.string.agent_open_app, localizeAgentNameKey("Open App"))
        assertEquals(R.string.agent_bridge_setup, localizeAgentNameKey("Bridge Setup"))
    }

    @Test
    fun unknownAgentNamesReturnNull() {
        assertNull(localizeAgentNameKey("Custom agent"))
    }

    @Test
    fun timelineTitlesSplitToolNameFromStatus() {
        val parts = localizeTimelineTitleName("web_search · running")
        assertEquals("web_search", parts?.first)
        assertEquals(R.string.tool_name_web_search, parts?.second)

        val unknown = localizeTimelineTitleName("unknown_tool · running")
        assertEquals("unknown_tool", unknown?.first)
        assertNull(unknown?.second)
    }

    @Test
    fun timelineTitlesWithoutSeparatorReturnNull() {
        assertNull(localizeTimelineTitleName("no separator here"))
    }
}
