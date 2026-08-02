package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.hermesandroid.relay.ui.components.pet.PetFootprint
import com.hermesandroid.relay.ui.components.pet.PetMeasuredPerch
import com.hermesandroid.relay.ui.components.pet.PetObstacle
import com.hermesandroid.relay.ui.components.pet.PetPoint
import com.hermesandroid.relay.ui.components.pet.PetRoamingRail
import com.hermesandroid.relay.ui.components.pet.PetRoute
import com.hermesandroid.relay.ui.components.pet.PetSafeBounds

/**
 * Immutable input for the developer-only pet terrain overlay. All coordinates
 * use the root overlay coordinate space already consumed by the pet router.
 * The caller owns feature gating and updates this model from live pet state.
 */
internal data class PetTerrainDebugModel(
    val routeLabel: String?,
    val safeBounds: PetSafeBounds,
    val perches: List<PetMeasuredPerch>,
    val rails: List<PetRoamingRail>,
    val touchdownRails: List<PetRoamingRail> = emptyList(),
    val activeRailKey: String?,
    val expandedObstacles: List<PetObstacle>,
    val footprint: PetFootprint,
    val petCenter: PetPoint,
    val latestPlannedRoute: PetRoute?,
    val locomotionLabel: String,
    val gateLabel: String,
) {
    val activeRail: PetRoamingRail?
        get() = (rails + touchdownRails).firstOrNull { it.key == activeRailKey }

    val plannedPoints: List<PetPoint>
        get() = latestPlannedRoute?.points.orEmpty()
}

internal fun petTerrainLegendLines(model: PetTerrainDebugModel): List<String> = listOf(
    "route ${model.routeLabel ?: "none"}",
    "rail ${model.activeRailKey ?: "none"}  move ${model.locomotionLabel}",
    "gate ${model.gateLabel}",
    "perches ${model.perches.size}  rails ${model.rails.size}  hops ${model.touchdownRails.size}  " +
        "obstacles ${model.expandedObstacles.size}",
)

internal data class PetTerrainPerchLabel(
    val text: String,
    val perch: PetMeasuredPerch,
    val hasRail: Boolean,
    val hasTouchdown: Boolean,
)

internal data class PetTerrainRailLabel(
    val text: String,
    val rail: PetRoamingRail,
)

internal fun petTerrainCompactPerchKey(key: String): String {
    val compact = when {
        key.startsWith("$CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX$CHAT_PET_STEP_MESSAGE_MARKER") ->
            "A*:" + key.removePrefix(
                "$CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX$CHAT_PET_STEP_MESSAGE_MARKER",
            )
        key.startsWith(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX) ->
            "A:" + key.removePrefix(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX)
        key.startsWith("$CHAT_PET_USER_MESSAGE_PERCH_PREFIX$CHAT_PET_STEP_MESSAGE_MARKER") ->
            "U*:" + key.removePrefix(
                "$CHAT_PET_USER_MESSAGE_PERCH_PREFIX$CHAT_PET_STEP_MESSAGE_MARKER",
            )
        key.startsWith(CHAT_PET_USER_MESSAGE_PERCH_PREFIX) ->
            "U:" + key.removePrefix(CHAT_PET_USER_MESSAGE_PERCH_PREFIX)
        key == CHAT_PET_WALK_REGION -> "composer"
        else -> key
    }
    return if (compact.length <= 22) compact else compact.take(21) + "…"
}

internal fun petTerrainPerchLabels(model: PetTerrainDebugModel): List<PetTerrainPerchLabel> =
    model.perches.mapIndexed { index, perch ->
        val hasRail = model.rails.any { rail -> rail.perchKey == perch.key }
        val hasTouchdown = model.touchdownRails.any { rail -> rail.perchKey == perch.key }
        PetTerrainPerchLabel(
            text = "P$index ${petTerrainCompactPerchKey(perch.key)}" +
                when {
                    hasRail -> ""
                    hasTouchdown -> " HOP"
                    else -> " NO-RAIL"
                },
            perch = perch,
            hasRail = hasRail,
            hasTouchdown = hasTouchdown,
        )
    }

internal fun petTerrainRailLabels(model: PetTerrainDebugModel): List<PetTerrainRailLabel> {
    val perchIndices = model.perches.mapIndexed { index, perch -> perch.key to index }.toMap()
    return model.rails.mapIndexed { index, rail ->
        val perchIndex = perchIndices[rail.perchKey]?.let { "P$it" } ?: "P?"
        PetTerrainRailLabel(
            text = "R$index→$perchIndex" + if (rail.key == model.activeRailKey) " ACT" else "",
            rail = rail,
        )
    }
}

internal fun petTerrainTouchdownLabels(model: PetTerrainDebugModel): List<PetTerrainRailLabel> {
    val perchIndices = model.perches.mapIndexed { index, perch -> perch.key to index }.toMap()
    return model.touchdownRails.mapIndexed { index, rail ->
        val perchIndex = perchIndices[rail.perchKey]?.let { "P$it" } ?: "P?"
        PetTerrainRailLabel(
            text = "H$index→$perchIndex" + if (rail.key == model.activeRailKey) " ACT" else "",
            rail = rail,
        )
    }
}

/**
 * Pointer-transparent diagnostic paint layer. This composable intentionally
 * adds no pointer-input, clickable, focus, or semantics modifier; when placed
 * before the pet target in the overlay Box, all input continues to reach the
 * pet or the app content beneath it.
 */
@Composable
internal fun PetTerrainDebugOverlay(
    model: PetTerrainDebugModel,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 16)
    val legendLines = remember(model) { petTerrainLegendLines(model) }
    val legendStyle = TextStyle(
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
    )
    val legendLayouts = remember(legendLines, textMeasurer) {
        legendLines.map { textMeasurer.measure(it, legendStyle) }
    }
    val perchLabels = remember(model.perches, model.rails) { petTerrainPerchLabels(model) }
    val railLabels = remember(model.perches, model.rails, model.activeRailKey) {
        petTerrainRailLabels(model)
    }
    val touchdownLabels = remember(model.perches, model.touchdownRails, model.activeRailKey) {
        petTerrainTouchdownLabels(model)
    }
    val terrainLabelStyle = TextStyle(
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
    )
    val perchLabelLayouts = remember(perchLabels, textMeasurer) {
        perchLabels.map { label -> textMeasurer.measure(label.text, terrainLabelStyle) }
    }
    val railLabelLayouts = remember(railLabels, textMeasurer) {
        railLabels.map { label -> textMeasurer.measure(label.text, terrainLabelStyle) }
    }
    val touchdownLabelLayouts = remember(touchdownLabels, textMeasurer) {
        touchdownLabels.map { label -> textMeasurer.measure(label.text, terrainLabelStyle) }
    }

    Canvas(modifier = modifier) {
        val thinStroke = 1.25f * density
        val railStroke = 2f * density
        val activeRailStroke = 3f * density

        drawRect(
            color = TerrainSafeBlue,
            topLeft = Offset(model.safeBounds.left, model.safeBounds.top),
            size = Size(model.safeBounds.width, model.safeBounds.height),
            style = Stroke(width = railStroke),
        )

        model.perches.forEach { perch ->
            drawObstacleOutline(perch.bounds, TerrainPerchCyan, thinStroke)
        }

        model.expandedObstacles.forEach { obstacle ->
            drawObstacleFill(obstacle, TerrainObstacleRed)
            drawObstacleOutline(obstacle, TerrainObstacleRedOutline, thinStroke)
        }

        val plannedPoints = model.plannedPoints
        plannedPoints.zipWithNext().forEach { (start, end) ->
            drawLine(
                color = TerrainRouteOrange,
                start = Offset(start.x, start.y),
                end = Offset(end.x, end.y),
                strokeWidth = railStroke,
            )
        }
        plannedPoints.forEach { point ->
            drawCircle(
                color = TerrainRouteOrange,
                radius = 3f * density,
                center = Offset(point.x, point.y),
            )
        }

        model.rails.forEach { rail ->
            val active = rail.key == model.activeRailKey
            drawLine(
                color = if (active) TerrainActiveYellow else TerrainRailGreen,
                start = Offset(rail.bounds.left, rail.bounds.top),
                end = Offset(rail.bounds.right, rail.bounds.top),
                strokeWidth = if (active) activeRailStroke else railStroke,
            )
        }
        model.touchdownRails.forEach { rail ->
            val active = rail.key == model.activeRailKey
            drawCircle(
                color = if (active) TerrainActiveYellow else TerrainRouteOrange,
                radius = if (active) 5f * density else 4f * density,
                center = Offset(rail.bounds.left, rail.bounds.top),
            )
        }

        perchLabels.zip(perchLabelLayouts).forEach { (label, layout) ->
            val topLeft = Offset(
                label.perch.bounds.left.coerceIn(
                    0f,
                    (size.width - layout.size.width).coerceAtLeast(0f),
                ),
                (label.perch.bounds.top - layout.size.height).coerceIn(0f, size.height),
            )
            drawRect(
                color = TerrainLabelBackground,
                topLeft = topLeft,
                size = Size(layout.size.width.toFloat(), layout.size.height.toFloat()),
            )
            drawText(
                textLayoutResult = layout,
                color = if (label.hasRail) TerrainPerchCyan else TerrainObstacleRedOutline,
                topLeft = topLeft,
            )
        }
        railLabels.zip(railLabelLayouts).forEach { (label, layout) ->
            val topLeft = Offset(
                label.rail.bounds.left.coerceIn(
                    0f,
                    (size.width - layout.size.width).coerceAtLeast(0f),
                ),
                label.rail.bounds.top.coerceIn(
                    0f,
                    (size.height - layout.size.height).coerceAtLeast(0f),
                ),
            )
            drawRect(
                color = TerrainLabelBackground,
                topLeft = topLeft,
                size = Size(layout.size.width.toFloat(), layout.size.height.toFloat()),
            )
            drawText(
                textLayoutResult = layout,
                color = if (label.rail.key == model.activeRailKey) {
                    TerrainActiveYellow
                } else {
                    TerrainRailGreen
                },
                topLeft = topLeft,
            )
        }
        touchdownLabels.zip(touchdownLabelLayouts).forEach { (label, layout) ->
            val topLeft = Offset(
                label.rail.bounds.left.coerceIn(
                    0f,
                    (size.width - layout.size.width).coerceAtLeast(0f),
                ),
                label.rail.bounds.top.coerceIn(
                    0f,
                    (size.height - layout.size.height).coerceAtLeast(0f),
                ),
            )
            drawRect(
                color = TerrainLabelBackground,
                topLeft = topLeft,
                size = Size(layout.size.width.toFloat(), layout.size.height.toFloat()),
            )
            drawText(
                textLayoutResult = layout,
                color = if (label.rail.key == model.activeRailKey) {
                    TerrainActiveYellow
                } else {
                    TerrainRouteOrange
                },
                topLeft = topLeft,
            )
        }

        val footprintLeft = model.petCenter.x - model.footprint.horizontalRadius
        val footprintTop = model.petCenter.y - model.footprint.verticalRadius
        drawRect(
            color = TerrainFootprintWhite,
            topLeft = Offset(footprintLeft, footprintTop),
            size = Size(
                model.footprint.horizontalRadius * 2f,
                model.footprint.verticalRadius * 2f,
            ),
            style = Stroke(width = thinStroke),
        )
        drawCircle(
            color = TerrainFootprintWhite,
            radius = 2.5f * density,
            center = Offset(model.petCenter.x, model.petCenter.y),
        )

        val legendPadding = 6f * density
        val legendGap = 2f * density
        val legendWidth = legendLayouts.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
        val legendHeight = legendLayouts.sumOf { it.size.height }.toFloat() +
            legendGap * (legendLayouts.size - 1).coerceAtLeast(0)
        val legendLeft = 8f * density
        val legendTop = 8f * density
        drawRoundRect(
            color = TerrainLegendBackground,
            topLeft = Offset(legendLeft, legendTop),
            size = Size(legendWidth + legendPadding * 2f, legendHeight + legendPadding * 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * density),
        )
        var lineTop = legendTop + legendPadding
        legendLayouts.forEach { layout ->
            drawText(
                textLayoutResult = layout,
                color = Color.White,
                topLeft = Offset(legendLeft + legendPadding, lineTop),
            )
            lineTop += layout.size.height + legendGap
        }
    }
}

private fun DrawScope.drawObstacleFill(obstacle: PetObstacle, color: Color) {
    drawRect(
        color = color,
        topLeft = Offset(obstacle.left, obstacle.top),
        size = Size(obstacle.right - obstacle.left, obstacle.bottom - obstacle.top),
    )
}

private fun DrawScope.drawObstacleOutline(obstacle: PetObstacle, color: Color, strokeWidth: Float) {
    val width = obstacle.right - obstacle.left
    val height = obstacle.bottom - obstacle.top
    if (height == 0f) {
        drawLine(
            color = color,
            start = Offset(obstacle.left, obstacle.top),
            end = Offset(obstacle.right, obstacle.top),
            strokeWidth = strokeWidth,
        )
    } else {
        drawRect(
            color = color,
            topLeft = Offset(obstacle.left, obstacle.top),
            size = Size(width, height),
            style = Stroke(width = strokeWidth),
        )
    }
}

private val TerrainSafeBlue = Color(0xFF3B82F6)
private val TerrainPerchCyan = Color(0xFF22D3EE)
private val TerrainRailGreen = Color(0xFF22C55E)
private val TerrainActiveYellow = Color(0xFFFACC15)
private val TerrainObstacleRed = Color(0x40EF4444)
private val TerrainObstacleRedOutline = Color(0xCCEF4444)
private val TerrainRouteOrange = Color(0xFFF97316)
private val TerrainFootprintWhite = Color(0xFFF8FAFC)
private val TerrainLegendBackground = Color(0xD914172A)
private val TerrainLabelBackground = Color(0xB814172A)
