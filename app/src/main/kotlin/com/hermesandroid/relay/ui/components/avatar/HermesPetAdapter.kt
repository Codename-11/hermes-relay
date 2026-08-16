package com.hermesandroid.relay.ui.components.avatar

import com.hermesandroid.relay.viewmodel.connection.ProfileController
import java.io.File

/** Adapt upstream `pet.info` geometry to the existing bounded sprite renderer. */
fun ProfileController.HermesPetPresentation.toAvatar(): PetAvatar? {
    val sheet = File(spritesheetPath)
    if (!sheet.isFile || frameWidth <= 0 || frameHeight <= 0 || framesPerState <= 0) return null
    val fps = (framesPerState * 1000f / loopMs.coerceAtLeast(1)).coerceIn(1f, 60f)
    val clips = stateRows.mapIndexedNotNull { row, name ->
        val normalized = name.trim().takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
        val count = (framesByRow[normalized] ?: framesByState[normalized] ?: framesPerState)
            .coerceIn(1, framesPerState)
        normalized to PetClipSpec(
            sheet = sheet.name,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            frameCount = count,
            startFrame = row * framesPerState,
            fps = fps,
        )
    }.toMap()
    if (clips["idle"] == null) return null
    return runCatching {
        PetSpec(
            id = "hermes:$slug",
            label = displayName,
            description = "Profile-scoped Hermes animated pet",
            reactive = PetReactiveSpec(voice = false, tools = true, intensity = true),
            states = clips,
        ).toAvatar(sheet.parentFile ?: return null)
    }.getOrNull()
}
