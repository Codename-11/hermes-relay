package com.hermesandroid.relay.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hermesandroid.relay.R

/**
 * Returns the string resource for a tool name (toolCall.name), or null when the
 * name does not map to a known tool. Pure mapping so it can be unit-tested.
 */
fun localizeToolNameKey(name: String): Int? = when {
    name.contains("terminal") -> R.string.tool_name_terminal
    name.contains("execute") -> R.string.tool_name_execute_code
    name.contains("read_file") -> R.string.tool_name_read_file
    name.contains("write_file") || name.contains("patch") -> R.string.tool_name_write_file
    name.contains("session_search") -> R.string.tool_name_session
    name.contains("web_search") || name.contains("search") -> R.string.tool_name_web_search
    name.contains("web_extract") -> R.string.tool_name_web_extract
    name.contains("memory") || name.contains("mnemosyne") -> R.string.tool_name_memory
    name.contains("skill") -> R.string.tool_name_skill
    name.contains("delegate") -> R.string.tool_name_delegate
    name.contains("cron") -> R.string.tool_name_cron
    name.contains("todo") -> R.string.tool_name_todo
    name.contains("process") -> R.string.tool_name_process
    name.contains("vision") || name.contains("image") -> R.string.tool_name_vision
    name.contains("computer_use") -> R.string.tool_name_computer
    name.contains("android") || name.contains("phone") -> R.string.tool_name_android
    name.contains("speech") || name.contains("tts") -> R.string.tool_name_tts
    name.contains("file") -> R.string.tool_name_file
    else -> null
}

/**
 * Returns the string resource for a badge label (message.badges entries), or
 * null when the badge does not map to a known resource. Pure mapping so it can
 * be unit-tested.
 */
fun localizeBadgeKey(badge: String): Int? = when (badge) {
    "Tool failed" -> R.string.badge_tool_failed
    "Memory" -> R.string.badge_memory
    "Skill" -> R.string.badge_skill
    "Artifact" -> R.string.badge_artifact
    "Response interrupted" -> R.string.badge_response_interrupted
    "Unknown error" -> R.string.badge_unknown_error
    "Error" -> R.string.badge_error
    "Stopped" -> R.string.badge_stopped
    "Model changed" -> R.string.badge_model_changed
    "Background work completed" -> R.string.badge_bg_work_completed
    "Continued after an interrupted turn" -> R.string.badge_continued
    "Realtime Agent" -> R.string.bubble_realtime_agent
    "Voice" -> R.string.bubble_voice
    else -> null
}

/**
 * Returns the string resource for an agent name (message.agentName), or null
 * when the name does not map to a known resource. Pure mapping so it can be
 * unit-tested.
 */
fun localizeAgentNameKey(name: String): Int? = when (name) {
    "Send SMS" -> R.string.agent_send_sms
    "Call" -> R.string.agent_call
    "Search Contacts" -> R.string.agent_search_contacts
    "Open App" -> R.string.agent_open_app
    "Return to Hermes" -> R.string.agent_return_hermes
    "Screenshot" -> R.string.agent_screenshot
    "Key Press" -> R.string.agent_key_press
    "Bridge Setup" -> R.string.agent_bridge_setup
    else -> null
}

/** Localizes a tool name (toolCall.name) for display. Unknown names are returned as-is. */
@Composable
fun localizeToolName(name: String): String = localizeToolNameKey(name)?.let {
    stringResource(it)
} ?: name

/** Localizes a badge (message.badges entries) for display. Unknown badges are returned as-is. */
@Composable
fun localizeBadge(badge: String): String = localizeBadgeKey(badge)?.let {
    stringResource(it)
} ?: badge

/** Localizes an agent name (message.agentName) for display. Unknown names are returned as-is. */
@Composable
fun localizeAgentName(name: String): String = localizeAgentNameKey(name)?.let {
    stringResource(it)
} ?: name

/**
 * Splits a timeline event title of the form "toolName · status" and returns the
 * resource key for the tool-name segment when it is known. Returns null when
 * the title has no separator or the name is not mapped, so the render site
 * falls back to the original title.
 */
fun localizeTimelineTitleName(title: String): Pair<String, Int?>? {
    val sep = " · "
    val idx = title.indexOf(sep)
    if (idx <= 0) return null
    val name = title.substring(0, idx)
    return name to localizeToolNameKey(name)
}

/**
 * Localizes a timeline event title of the form "toolName · status" at the
 * render site (TimelineRow). The data layer (buildTimelineEvents) keeps the
 * English keys.
 */
@Composable
fun localizeTimelineTitle(title: String): String {
    val parts = localizeTimelineTitleName(title) ?: return title
    val (name, key) = parts
    if (key == null) return title
    val sep = " · "
    val idx = title.indexOf(sep)
    return stringResource(key) + title.substring(idx)
}
