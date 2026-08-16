package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.relay.ui.components.pet.PetFootprint
import com.hermesandroid.relay.ui.components.pet.PetMeasuredPerch
import com.hermesandroid.relay.ui.components.pet.PetObstacle
import com.hermesandroid.relay.ui.components.pet.PetPoint
import com.hermesandroid.relay.ui.components.pet.PetRoamingRail
import com.hermesandroid.relay.ui.components.pet.PetRoute
import com.hermesandroid.relay.ui.components.pet.PetSafeBounds
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class PetDebugRouteKind(val label: String) {
    Autonomous("autonomous"),
    Recovery("recovery"),
    DirectManipulation("drag/drop"),
}

internal data class PetDebugActiveRoute(
    val route: PetRoute,
    val kind: PetDebugRouteKind,
)

/**
 * The exact outbound route segments selected by the behavior planner. The
 * return trip reuses these segments in reverse, so the overlay draws the
 * geometry once and exposes the complete out-and-back order in [loopLabel].
 */
internal data class PetDebugPlannedRoute(
    val outboundRoutes: List<PetRoute>,
    val stops: List<PetPoint>,
    val targetLabel: String,
) {
    init {
        require(outboundRoutes.isNotEmpty()) { "A debug plan needs at least one route." }
        require(stops.size >= 2) { "A debug plan needs an origin and destination." }
    }

    val loopStopIndices: List<Int>
        get() = stops.indices.toList() + (stops.lastIndex - 1 downTo 0).toList()

    val loopLabel: String
        get() = loopStopIndices.joinToString("→")
}

/** Builds numbered planner stops from contiguous, non-stationary route legs. */
internal fun petDebugPlannedRoute(
    targetLabel: String,
    routes: List<PetRoute>,
): PetDebugPlannedRoute? {
    val outboundRoutes = routes.filter { route ->
        route.points.size > 1 && route.start != route.destination
    }
    if (outboundRoutes.isEmpty()) return null

    val stops = buildList {
        add(outboundRoutes.first().start)
        outboundRoutes.forEach { route ->
            if (last() != route.start) add(route.start)
            if (last() != route.destination) add(route.destination)
        }
    }
    if (stops.size < 2) return null
    return PetDebugPlannedRoute(outboundRoutes, stops, targetLabel)
}

internal data class PetDebugStopBadge(
    val point: PetPoint,
    val stopIndices: List<Int>,
) {
    val label: String
        get() = stopIndices.joinToString("/")
}

/** Coalesces labels whose marker circles would overlap while preserving every stop number. */
internal fun petDebugStopBadges(
    stops: List<PetPoint>,
    clusterDistance: Float,
): List<PetDebugStopBadge> {
    val maximumDistanceSquared = clusterDistance * clusterDistance
    val badges = mutableListOf<PetDebugStopBadge>()
    stops.forEachIndexed { index, point ->
        val badgeIndex = badges.indexOfFirst { badge ->
            badge.point.distanceSquaredTo(point) <= maximumDistanceSquared
        }
        if (badgeIndex < 0) {
            badges += PetDebugStopBadge(point, listOf(index))
        } else {
            val badge = badges[badgeIndex]
            badges[badgeIndex] = badge.copy(stopIndices = badge.stopIndices + index)
        }
    }
    return badges
}

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
    val possibleRoutes: List<PetRoute> = emptyList(),
    val plannedRoute: PetDebugPlannedRoute? = null,
    val activeRoute: PetDebugActiveRoute? = null,
    val locomotionLabel: String,
    val gateLabel: String,
) {
    val activeRail: PetRoamingRail?
        get() = (rails + touchdownRails).firstOrNull { it.key == activeRailKey }

    val activePoints: List<PetPoint>
        get() = activeRoute?.route?.points.orEmpty()
}

internal enum class PetTerrainDebugViewMode(val label: String) {
    Plan("Plan"),
    Terrain("Terrain"),
    Full("Full"),
}

internal data class PetTerrainDebugLayerVisibility(
    val safeBounds: Boolean,
    val perches: Boolean,
    val obstacles: Boolean,
    val candidates: Boolean,
    val rails: Boolean,
    val touchdowns: Boolean,
    val compactRailLabels: Boolean,
    val rawLabels: Boolean,
    val footprint: Boolean,
)

internal fun petTerrainDebugLayerVisibility(
    mode: PetTerrainDebugViewMode,
): PetTerrainDebugLayerVisibility = when (mode) {
    PetTerrainDebugViewMode.Plan -> PetTerrainDebugLayerVisibility(
        safeBounds = false,
        perches = false,
        obstacles = false,
        candidates = false,
        rails = false,
        touchdowns = false,
        compactRailLabels = false,
        rawLabels = false,
        footprint = false,
    )
    PetTerrainDebugViewMode.Terrain -> PetTerrainDebugLayerVisibility(
        safeBounds = false,
        perches = true,
        obstacles = true,
        candidates = true,
        rails = true,
        touchdowns = true,
        compactRailLabels = true,
        rawLabels = false,
        footprint = false,
    )
    PetTerrainDebugViewMode.Full -> PetTerrainDebugLayerVisibility(
        safeBounds = true,
        perches = true,
        obstacles = true,
        candidates = true,
        rails = true,
        touchdowns = true,
        compactRailLabels = false,
        rawLabels = true,
        footprint = true,
    )
}

internal fun petTerrainInspectorSummary(model: PetTerrainDebugModel): String {
    val plan = model.plannedRoute
    val origin = plan?.stops?.firstOrNull()?.let { start ->
        (model.rails + model.touchdownRails).firstOrNull { rail ->
            start.x in rail.bounds.left..rail.bounds.right &&
                kotlin.math.abs(start.y - rail.bounds.top) <= 1f
        }?.perchKey
    }?.let(::petTerrainSurfaceName) ?: model.activeRail?.perchKey?.let(::petTerrainSurfaceName)
        ?: model.routeLabel?.replaceFirstChar(Char::uppercase)
        ?: "No surface"
    val target = plan?.targetLabel?.let(::petTerrainSurfaceName) ?: "No selected target"
    val stopCount = plan?.stops?.size ?: 0
    return if (plan == null) {
        "$origin · no selected route"
    } else {
        "$origin → $target · $stopCount ${if (stopCount == 1) "stop" else "stops"}"
    }
}

private fun petTerrainSurfaceName(key: String): String = when {
    key == CHAT_PET_WALK_REGION || key.equals("composer", ignoreCase = true) -> "Composer"
    key.startsWith(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX) || key.startsWith("A:") -> "Assistant"
    key.startsWith(CHAT_PET_USER_MESSAGE_PERCH_PREFIX) || key.startsWith("U:") -> "User"
    key.startsWith("settings-card:") || key.startsWith("settings-category:") -> "Settings card"
    key.startsWith("appearance-card:") -> "Appearance card"
    key == "app-status-footer" -> "Status rail"
    else -> petTerrainCompactPerchKey(key)
}

internal data class PetTerrainInspectorPlacement(
    val x: Float,
    val y: Float,
    val horizontalRange: Float,
    val verticalRange: Float,
)

internal fun petTerrainInspectorPlacement(
    viewportWidth: Float,
    viewportHeight: Float,
    panelWidth: Float,
    panelHeight: Float,
    safeTop: Float,
    bottomInset: Float,
    margin: Float,
    horizontalFraction: Float,
    verticalFraction: Float,
): PetTerrainInspectorPlacement {
    val horizontalRange = (viewportWidth - panelWidth - margin * 2f).coerceAtLeast(0f)
    val verticalRange = (
        viewportHeight - panelHeight - safeTop - bottomInset - margin
    ).coerceAtLeast(0f)
    return PetTerrainInspectorPlacement(
        x = margin + horizontalRange * horizontalFraction.coerceIn(0f, 1f),
        y = safeTop + verticalRange * verticalFraction.coerceIn(0f, 1f),
        horizontalRange = horizontalRange,
        verticalRange = verticalRange,
    )
}

internal fun petTerrainInspectorDraggedFraction(
    currentPosition: Float,
    delta: Float,
    rangeStart: Float,
    range: Float,
): Float {
    if (range <= 0f) return 0.5f
    return ((currentPosition + delta - rangeStart) / range).coerceIn(0f, 1f)
}

internal fun petTerrainInspectorSnappedHorizontalFraction(fraction: Float): Float =
    if (fraction < 0.5f) 0f else 1f

internal fun petTerrainLegendLines(model: PetTerrainDebugModel): List<String> = listOf(
    "route ${model.routeLabel ?: "none"}",
    "routes possible ${model.possibleRoutes.size}  active ${model.activeRoute?.kind?.label ?: "none"}",
    "plan ${model.plannedRoute?.loopLabel ?: "none"}" +
        (model.plannedRoute?.targetLabel?.let { "  target $it" } ?: ""),
    "blue dashed=possible  yellow=plan  orange=auto  pink=recovery  teal=drag",
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
 * Developer-only path inspector. The terrain canvas remains pointer-transparent;
 * only the compact inspector panel accepts input for mode selection and freezing
 * the displayed snapshot. Freeze never pauses or mutates the live pet planner.
 */
@Composable
internal fun PetTerrainDebugOverlay(
    model: PetTerrainDebugModel,
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(PetTerrainDebugViewMode.Terrain) }
    var inspectorExpanded by rememberSaveable { mutableStateOf(false) }
    var passThrough by rememberSaveable { mutableStateOf(false) }
    var horizontalFraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    var verticalFraction by rememberSaveable { mutableFloatStateOf(0f) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    var frozenModel by remember { mutableStateOf<PetTerrainDebugModel?>(null) }
    val displayModel = frozenModel ?: model
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val margin = with(density) { 8.dp.toPx() }
        val safeTop = WindowInsets.statusBars.getTop(density) + with(density) { 68.dp.toPx() }
        val bottomInset = WindowInsets.navigationBars.getBottom(density).toFloat()
        val placement = petTerrainInspectorPlacement(
            viewportWidth = constraints.maxWidth.toFloat(),
            viewportHeight = constraints.maxHeight.toFloat(),
            panelWidth = panelSize.width.toFloat(),
            panelHeight = panelSize.height.toFloat(),
            safeTop = safeTop,
            bottomInset = bottomInset,
            margin = margin,
            horizontalFraction = horizontalFraction,
            verticalFraction = verticalFraction,
        )
        val usableWidth = (maxWidth - 16.dp).coerceAtLeast(1.dp)
        val panelWidth = if (inspectorExpanded) {
            minOf(usableWidth, 420.dp)
        } else {
            minOf(usableWidth, 300.dp)
        }

        PetTerrainDebugCanvas(
            model = displayModel,
            mode = mode,
            modifier = Modifier.fillMaxSize(),
        )
        PetTerrainInspectorPanel(
            model = displayModel,
            mode = mode,
            expanded = inspectorExpanded,
            passThrough = passThrough,
            frozen = frozenModel != null,
            onModeChanged = { mode = it },
            onExpandedChanged = { inspectorExpanded = it },
            onPassThroughChanged = { enabled ->
                passThrough = enabled
                if (enabled) inspectorExpanded = false
            },
            onDrag = { delta ->
                horizontalFraction = petTerrainInspectorDraggedFraction(
                    currentPosition = placement.x,
                    delta = delta.x,
                    rangeStart = margin,
                    range = placement.horizontalRange,
                )
                verticalFraction = petTerrainInspectorDraggedFraction(
                    currentPosition = placement.y,
                    delta = delta.y,
                    rangeStart = safeTop,
                    range = placement.verticalRange,
                )
            },
            onDragEnd = {
                horizontalFraction = petTerrainInspectorSnappedHorizontalFraction(horizontalFraction)
            },
            onResetPosition = {
                horizontalFraction = 0.5f
                verticalFraction = 0f
            },
            onFreezeChanged = { frozen -> frozenModel = if (frozen) model else null },
            onExit = onExit,
            modifier = Modifier
                .offset {
                    IntOffset(placement.x.roundToInt(), placement.y.roundToInt())
                }
                .width(panelWidth)
                .onSizeChanged { panelSize = it },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PetTerrainInspectorPanel(
    model: PetTerrainDebugModel,
    mode: PetTerrainDebugViewMode,
    expanded: Boolean,
    passThrough: Boolean,
    frozen: Boolean,
    onModeChanged: (PetTerrainDebugViewMode) -> Unit,
    onExpandedChanged: (Boolean) -> Unit,
    onPassThroughChanged: (Boolean) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onResetPosition: () -> Unit,
    onFreezeChanged: (Boolean) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = TerrainInspectorBackground,
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TerrainInspectorBorder),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!passThrough) {
                    PetTerrainInspectorDragHandle(
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                    )
                }
                Text(
                    text = when {
                        passThrough -> "Pet paths"
                        expanded -> "Pet path inspector"
                        else -> "Pet paths"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Surface(
                    color = when {
                        passThrough -> TerrainPassThroughSurface
                        frozen -> TerrainFrozenSurface
                        else -> TerrainLiveSurface
                    },
                    contentColor = when {
                        passThrough -> TerrainPassThroughText
                        frozen -> TerrainFrozenText
                        else -> TerrainLiveText
                    },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        1.dp,
                        when {
                            passThrough -> TerrainPassThroughText
                            frozen -> TerrainFrozenText
                            else -> TerrainLiveText
                        },
                    ),
                ) {
                    Text(
                        text = when {
                            passThrough -> "PASS"
                            frozen -> "FROZEN"
                            else -> "LIVE"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                IconButton(
                    onClick = { onPassThroughChanged(!passThrough) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (passThrough) Icons.Filled.LockOpen else Icons.Filled.Lock,
                        contentDescription = if (passThrough) {
                            "Enable pet inspector controls"
                        } else {
                            "Make pet inspector click-through"
                        },
                    )
                }
                if (!passThrough) {
                    IconButton(
                        onClick = { onExpandedChanged(!expanded) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) {
                                Icons.Filled.ExpandLess
                            } else {
                                Icons.Filled.ExpandMore
                            },
                            contentDescription = if (expanded) {
                                "Collapse pet path inspector"
                            } else {
                                "Expand pet path inspector"
                            },
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        PetTerrainDebugViewMode.entries.forEachIndexed { index, candidate ->
                            SegmentedButton(
                                selected = mode == candidate,
                                onClick = { onModeChanged(candidate) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = PetTerrainDebugViewMode.entries.size,
                                ),
                                label = { Text(candidate.label) },
                            )
                        }
                    }

                    if (mode != PetTerrainDebugViewMode.Plan) {
                        PetTerrainLayerLegend()
                    }

                    HorizontalDivider(color = TerrainInspectorDivider)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = petTerrainInspectorSummary(model),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TerrainInspectorSecondaryText,
                            maxLines = 2,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { onFreezeChanged(!frozen) },
                            modifier = Modifier.height(34.dp),
                            border = BorderStroke(1.dp, TerrainInspectorControlBorder),
                            shape = RoundedCornerShape(9.dp),
                        ) {
                            Text(
                                text = if (frozen) "Resume" else "Freeze",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    if (mode == PetTerrainDebugViewMode.Full) {
                        Text(
                            text = "${model.possibleRoutes.size} candidate routes · gate ${model.gateLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TerrainInspectorTertiaryText,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = "rail ${model.activeRailKey ?: "none"} · move ${model.locomotionLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TerrainInspectorTertiaryText,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onResetPosition) {
                            Text("Reset position")
                        }
                        TextButton(onClick = onExit) {
                            Text(
                                text = "Exit inspector",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PetTerrainInspectorDragHandle(
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Box(
        modifier = Modifier
            .size(40.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = currentOnDragEnd,
                    onDragCancel = currentOnDragEnd,
                ) { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.DragIndicator,
            contentDescription = "Move pet path inspector",
            tint = TerrainInspectorSecondaryText,
        )
    }
}

private enum class PetTerrainLegendKind {
    Selected,
    Active,
    Candidate,
    Rail,
    Obstacle,
    Touchdown,
}

@Composable
private fun PetTerrainLayerLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(
            ("Selected route" to PetTerrainLegendKind.Selected) to
                ("Walk rails" to PetTerrainLegendKind.Rail),
            ("Active segment" to PetTerrainLegendKind.Active) to
                ("Collision bounds" to PetTerrainLegendKind.Obstacle),
            ("Candidate hops" to PetTerrainLegendKind.Candidate) to
                ("Touchdown" to PetTerrainLegendKind.Touchdown),
        ).forEach { (left, right) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PetTerrainLegendItem(left.first, left.second, Modifier.weight(1f))
                PetTerrainLegendItem(right.first, right.second, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PetTerrainLegendItem(
    label: String,
    kind: PetTerrainLegendKind,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 28.dp, height = 12.dp)) {
            val centerY = size.height / 2f
            when (kind) {
                PetTerrainLegendKind.Selected -> drawLine(
                    TerrainPlannedRouteYellow,
                    Offset(0f, centerY),
                    Offset(size.width, centerY),
                    strokeWidth = 2.dp.toPx(),
                )
                PetTerrainLegendKind.Active -> drawLine(
                    TerrainActiveRouteOrange,
                    Offset(0f, centerY),
                    Offset(size.width, centerY),
                    strokeWidth = 2.dp.toPx(),
                )
                PetTerrainLegendKind.Candidate -> drawLine(
                    TerrainCandidateRouteBlue,
                    Offset(0f, centerY),
                    Offset(size.width, centerY),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                )
                PetTerrainLegendKind.Rail -> drawLine(
                    TerrainRailGreen,
                    Offset(0f, centerY),
                    Offset(size.width, centerY),
                    strokeWidth = 2.dp.toPx(),
                )
                PetTerrainLegendKind.Obstacle -> drawRect(
                    color = TerrainObstacleRedOutline,
                    topLeft = Offset(5.dp.toPx(), 1.dp.toPx()),
                    size = Size(18.dp.toPx(), 10.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
                PetTerrainLegendKind.Touchdown -> drawCircle(
                    color = TerrainTouchdownViolet,
                    radius = 4.dp.toPx(),
                    center = Offset(size.width / 2f, centerY),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TerrainInspectorSecondaryText,
            maxLines = 1,
        )
    }
}

@Composable
private fun PetTerrainDebugCanvas(
    model: PetTerrainDebugModel,
    mode: PetTerrainDebugViewMode,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 16)
    val localDensity = LocalDensity.current
    val layers = petTerrainDebugLayerVisibility(mode)
    val perchLabels = remember(model.perches, model.rails, model.touchdownRails) {
        petTerrainPerchLabels(model)
    }
    val fullRailLabels = remember(model.perches, model.rails, model.activeRailKey) {
        petTerrainRailLabels(model)
    }
    val terrainRailLabels = remember(model.rails, model.activeRailKey) {
        model.rails.mapIndexed { index, rail ->
            PetTerrainRailLabel(
                text = "R${index + 1}" + if (rail.key == model.activeRailKey) " ACT" else "",
                rail = rail,
            )
        }
    }
    val railLabels = if (layers.rawLabels) fullRailLabels else terrainRailLabels
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
    val plannedStopStyle = TextStyle(
        color = TerrainPlannedRouteText,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 8.sp,
    )
    val plannedStopBadges = remember(model.plannedRoute?.stops, localDensity) {
        petDebugStopBadges(
            stops = model.plannedRoute?.stops.orEmpty(),
            clusterDistance = with(localDensity) { 14.dp.toPx() },
        )
    }
    val plannedStopLabels = plannedStopBadges.map(PetDebugStopBadge::label)
    val plannedStopLayouts = remember(plannedStopLabels, textMeasurer) {
        plannedStopLabels.map { label -> textMeasurer.measure(label, plannedStopStyle) }
    }

    Canvas(modifier = modifier) {
        val thinStroke = 1.25f * density
        val railStroke = 2f * density
        val activeRailStroke = 3f * density
        val candidateRouteStroke = 1.25f * density
        val candidateRouteDash = PathEffect.dashPathEffect(
            floatArrayOf(5f * density, 4f * density),
        )

        if (layers.safeBounds) {
            drawRect(
                color = TerrainSafeBlue,
                topLeft = Offset(model.safeBounds.left, model.safeBounds.top),
                size = Size(model.safeBounds.width, model.safeBounds.height),
                style = Stroke(width = railStroke),
            )
        }
        if (layers.perches) {
            model.perches.forEach { perch ->
                drawObstacleOutline(perch.bounds, TerrainPerchCyan, thinStroke)
            }
        }
        if (layers.obstacles) {
            model.expandedObstacles.forEach { obstacle ->
                drawObstacleFill(obstacle, TerrainObstacleRed)
                drawObstacleOutline(obstacle, TerrainObstacleRedOutline, thinStroke)
            }
        }
        if (layers.candidates) {
            model.possibleRoutes.forEach { route ->
                route.points.zipWithNext().forEach { (start, end) ->
                    drawLine(
                        color = TerrainCandidateRouteBlue,
                        start = Offset(start.x, start.y),
                        end = Offset(end.x, end.y),
                        strokeWidth = candidateRouteStroke,
                        pathEffect = candidateRouteDash,
                    )
                }
            }
        }
        if (layers.rails) {
            model.rails.forEach { rail ->
                val active = rail.key == model.activeRailKey
                drawLine(
                    color = if (active) TerrainActiveYellow else TerrainRailGreen,
                    start = Offset(rail.bounds.left, rail.bounds.top),
                    end = Offset(rail.bounds.right, rail.bounds.top),
                    strokeWidth = if (active) activeRailStroke else railStroke,
                )
            }
        } else {
            model.activeRail?.let { rail ->
                drawLine(
                    color = TerrainActiveYellow,
                    start = Offset(rail.bounds.left, rail.bounds.top),
                    end = Offset(rail.bounds.right, rail.bounds.top),
                    strokeWidth = activeRailStroke,
                )
            }
        }
        if (layers.touchdowns) {
            model.touchdownRails.forEach { rail ->
                val active = rail.key == model.activeRailKey
                drawCircle(
                    color = if (active) TerrainActiveYellow else TerrainTouchdownViolet,
                    radius = if (active) 5f * density else 4f * density,
                    center = Offset(rail.bounds.left, rail.bounds.top),
                    style = Stroke(width = thinStroke),
                )
            }
        }

        model.plannedRoute?.outboundRoutes.orEmpty().forEach { route ->
            route.points.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = TerrainPlannedRouteYellow,
                    start = Offset(start.x, start.y),
                    end = Offset(end.x, end.y),
                    strokeWidth = railStroke,
                )
                drawRouteArrow(start, end, TerrainPlannedRouteYellow, railStroke)
            }
        }

        val activeRouteColor = when (model.activeRoute?.kind) {
            PetDebugRouteKind.Autonomous -> TerrainActiveRouteOrange
            PetDebugRouteKind.Recovery -> TerrainRecoveryRoutePink
            PetDebugRouteKind.DirectManipulation -> TerrainDirectRouteTeal
            null -> TerrainActiveRouteOrange
        }
        model.activePoints.zipWithNext().forEach { (start, end) ->
            drawLine(
                color = activeRouteColor,
                start = Offset(start.x, start.y),
                end = Offset(end.x, end.y),
                strokeWidth = activeRailStroke,
            )
            drawRouteArrow(start, end, activeRouteColor, activeRailStroke)
        }
        model.activePoints.forEach { point ->
            drawCircle(activeRouteColor, 4f * density, Offset(point.x, point.y))
        }

        if (layers.rawLabels) {
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
        }
        if (layers.compactRailLabels || layers.rawLabels) {
            railLabels.zip(railLabelLayouts).forEach { (label, layout) ->
                val centerX = (label.rail.bounds.left + label.rail.bounds.right) / 2f
                val topLeft = Offset(
                    (centerX - layout.size.width / 2f).coerceIn(
                        0f,
                        (size.width - layout.size.width).coerceAtLeast(0f),
                    ),
                    (label.rail.bounds.top - layout.size.height - 2f * density).coerceIn(
                        0f,
                        (size.height - layout.size.height).coerceAtLeast(0f),
                    ),
                )
                drawRoundRect(
                    color = TerrainLabelBackground,
                    topLeft = topLeft,
                    size = Size(layout.size.width.toFloat(), layout.size.height.toFloat()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * density),
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
        }
        if (layers.rawLabels) {
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
                        TerrainTouchdownViolet
                    },
                    topLeft = topLeft,
                )
            }
        }

        plannedStopBadges.zip(plannedStopLayouts).forEach { (badge, layout) ->
            val center = Offset(badge.point.x, badge.point.y)
            drawCircle(TerrainPlannedRouteText, 8f * density, center)
            drawCircle(TerrainPlannedRouteYellow, 6.5f * density, center)
            drawText(
                textLayoutResult = layout,
                color = TerrainPlannedRouteText,
                topLeft = Offset(
                    center.x - layout.size.width / 2f,
                    center.y - layout.size.height / 2f,
                ),
            )
        }

        if (layers.footprint) {
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

private fun DrawScope.drawRouteArrow(
    start: PetPoint,
    end: PetPoint,
    color: Color,
    strokeWidth: Float,
) {
    val deltaX = end.x - start.x
    val deltaY = end.y - start.y
    val length = sqrt(deltaX * deltaX + deltaY * deltaY)
    if (length < 16f * density) return

    val directionX = deltaX / length
    val directionY = deltaY / length
    val arrowCenter = Offset(
        x = start.x + deltaX * 0.58f,
        y = start.y + deltaY * 0.58f,
    )
    val arrowLength = 6f * density
    val arrowWidth = 4f * density
    val base = Offset(
        x = arrowCenter.x - directionX * arrowLength,
        y = arrowCenter.y - directionY * arrowLength,
    )
    val perpendicularX = -directionY
    val perpendicularY = directionX
    drawLine(
        color = color,
        start = Offset(
            x = base.x + perpendicularX * arrowWidth,
            y = base.y + perpendicularY * arrowWidth,
        ),
        end = arrowCenter,
        strokeWidth = strokeWidth,
    )
    drawLine(
        color = color,
        start = Offset(
            x = base.x - perpendicularX * arrowWidth,
            y = base.y - perpendicularY * arrowWidth,
        ),
        end = arrowCenter,
        strokeWidth = strokeWidth,
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
private val TerrainObstacleRed = Color(0x18EF4444)
private val TerrainObstacleRedOutline = Color(0x8CEF4444)
private val TerrainTouchdownViolet = Color(0xFFA855F7)
private val TerrainCandidateRouteBlue = Color(0x7060A5FA)
private val TerrainPlannedRouteYellow = Color(0xFFFFD54F)
private val TerrainPlannedRouteText = Color(0xFF111827)
private val TerrainActiveRouteOrange = Color(0xFFFF6D00)
private val TerrainRecoveryRoutePink = Color(0xFFF472B6)
private val TerrainDirectRouteTeal = Color(0xFF2DD4BF)
private val TerrainFootprintWhite = Color(0xFFF8FAFC)
private val TerrainLabelBackground = Color(0xB814172A)
private val TerrainInspectorBackground = Color(0xE8171A22)
private val TerrainInspectorBorder = Color(0x665A6070)
private val TerrainInspectorControlBorder = Color(0x99666D7D)
private val TerrainInspectorDivider = Color(0x335A6070)
private val TerrainInspectorSecondaryText = Color(0xFFD4D7DE)
private val TerrainInspectorTertiaryText = Color(0xFF9BA1AF)
private val TerrainLiveSurface = Color(0x2622C55E)
private val TerrainLiveText = Color(0xFF69E58C)
private val TerrainFrozenSurface = Color(0x26FACC15)
private val TerrainFrozenText = Color(0xFFFDE36D)
private val TerrainPassThroughSurface = Color(0x2638BDF8)
private val TerrainPassThroughText = Color(0xFF7DD3FC)
