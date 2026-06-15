package com.hermesandroid.relay.network

import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Attachment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal fun buildSessionChatStreamPayload(
    message: String,
    systemMessage: String? = null,
    attachments: List<Attachment>? = null,
    voiceIntentMessages: JsonArray? = null,
    modelOverride: String? = null,
    profileName: String? = null,
): JsonObject = buildJsonObject {
    put("message", message)
    if (!systemMessage.isNullOrBlank()) {
        put("system_message", systemMessage)
    }
    if (!modelOverride.isNullOrBlank()) {
        put("model", modelOverride)
    }
    AgentDisplay.profileRequestName(profileName)?.let { put("profile", it) }
    if (!attachments.isNullOrEmpty()) {
        putJsonArray("attachments") {
            attachments.forEach { att ->
                addJsonObject {
                    put("contentType", att.contentType)
                    put("content", att.content)
                }
            }
        }
    }
    if (voiceIntentMessages != null && voiceIntentMessages.isNotEmpty()) {
        put("messages", voiceIntentMessages)
    }
}

internal fun buildRunStreamPayload(
    message: String,
    model: String? = null,
    systemMessage: String? = null,
    attachments: List<Attachment>? = null,
    voiceIntentMessages: JsonArray? = null,
    modelOverride: String? = null,
    profileName: String? = null,
): JsonObject {
    val resolvedModel = when {
        !modelOverride.isNullOrBlank() -> modelOverride
        !model.isNullOrBlank() -> model
        else -> "default"
    }
    return buildJsonObject {
        put("model", resolvedModel)
        put("input", message)
        put("stream", true)
        if (!systemMessage.isNullOrBlank()) {
            put("system_message", systemMessage)
        }
        AgentDisplay.profileRequestName(profileName)?.let { put("profile", it) }
        if (!attachments.isNullOrEmpty()) {
            putJsonArray("attachments") {
                attachments.forEach { att ->
                    addJsonObject {
                        put("contentType", att.contentType)
                        put("content", att.content)
                    }
                }
            }
        }
        if (voiceIntentMessages != null && voiceIntentMessages.isNotEmpty()) {
            put("messages", voiceIntentMessages)
        }
    }
}

internal fun buildChatCompletionsStreamPayload(
    message: String,
    model: String? = null,
    systemMessage: String? = null,
    attachments: List<Attachment>? = null,
    voiceIntentMessages: JsonArray? = null,
    modelOverride: String? = null,
    profileName: String? = null,
): JsonObject {
    val resolvedModel = when {
        !modelOverride.isNullOrBlank() -> modelOverride
        !model.isNullOrBlank() -> model
        else -> "default"
    }
    return buildJsonObject {
        put("model", resolvedModel)
        put("stream", true)
        AgentDisplay.profileRequestName(profileName)?.let { put("profile", it) }
        putJsonArray("messages") {
            if (!systemMessage.isNullOrBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", systemMessage)
                }
            }
            if (voiceIntentMessages != null && voiceIntentMessages.isNotEmpty()) {
                voiceIntentMessages.forEach { add(it) }
            }
            addJsonObject {
                put("role", "user")
                if (!attachments.isNullOrEmpty() && attachments.any { it.isImage }) {
                    put("content", buildJsonArray {
                        addJsonObject {
                            put("type", "text")
                            put("text", message)
                        }
                        attachments.filter { it.isImage }.forEach { att ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:${att.contentType};base64,${att.content}")
                                }
                            }
                        }
                    })
                } else {
                    put("content", message)
                }
            }
        }
        if (!attachments.isNullOrEmpty() && attachments.any { !it.isImage }) {
            putJsonArray("attachments") {
                attachments.filter { !it.isImage }.forEach { att ->
                    addJsonObject {
                        put("contentType", att.contentType)
                        put("content", att.content)
                    }
                }
            }
        }
    }
}
