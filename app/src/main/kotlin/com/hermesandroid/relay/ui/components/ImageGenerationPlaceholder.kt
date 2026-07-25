package com.hermesandroid.relay.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.annotation.RequiresApi
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ToolCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val IMAGE_GENERATION_TOOL = "image_generate"
private const val GRID_COLUMNS = 42
private const val GRID_ROWS = 24
private const val DEFAULT_ANIMATION_DURATION_MS = 4_800
private const val IMAGE_REVEAL_SHADER = """
uniform shader image;
uniform float2 resolution;
uniform float progress;

float randomCell(float2 cell) {
    return fract(sin(dot(cell, float2(127.1, 311.7))) * 43758.5453);
}

half4 main(float2 coordinate) {
    float2 safeResolution = max(resolution, float2(1.0));
    float2 uv = coordinate / safeResolution;
    float2 gridSize = float2(42.0, 24.0);
    float2 gridCoordinate = uv * gridSize;
    float2 cell = floor(gridCoordinate);
    float2 cellUv = fract(gridCoordinate) - 0.5;
    float diagonal = (uv.x + uv.y) * 0.5;
    float cellDiagonal = (
        (cell.x + 0.5) / gridSize.x +
        (cell.y + 0.5) / gridSize.y
    ) * 0.5;

    float front = mix(1.22, -0.22, progress);
    float jitter = (randomCell(cell) - 0.5) * 0.085;
    float solid = smoothstep(front, front + 0.055, diagonal);
    float cellProgress = smoothstep(
        front - 0.15,
        front + 0.015,
        cellDiagonal + jitter
    );

    float cellScale = mix(0.12, 1.06, cellProgress);
    float corner = 0.075 * cellScale;
    float2 roundedBox = abs(cellUv) - float2(0.43 * cellScale - corner);
    float cellDistance = length(max(roundedBox, float2(0.0))) - corner;
    float pixel = 1.0 - smoothstep(-0.015, 0.025, cellDistance);

    float softWave = smoothstep(front - 0.09, front + 0.035, diagonal) * 0.42;
    float reveal = max(solid, max(pixel * cellProgress, softWave));
    half4 source = image.eval(coordinate);
    return half4(source.rgb, source.a * half(reveal));
}
"""

enum class ImageGenerationVisualStyle {
    LatentGrid,
    ParticleOrb,
    Constellation,
}

internal fun resolveImageGenerationVisualStyle(
    preference: String,
    rotationIndex: Int,
): ImageGenerationVisualStyle = when (preference) {
    "grid" -> ImageGenerationVisualStyle.LatentGrid
    "sphere" -> ImageGenerationVisualStyle.ParticleOrb
    "nodes" -> ImageGenerationVisualStyle.Constellation
    else -> when (rotationIndex.mod(3)) {
        0 -> ImageGenerationVisualStyle.LatentGrid
        1 -> ImageGenerationVisualStyle.ParticleOrb
        else -> ImageGenerationVisualStyle.Constellation
    }
}

internal fun ToolCall.showsImageGenerationPlaceholder(): Boolean =
    !isComplete && name.trim().lowercase() == IMAGE_GENERATION_TOOL

internal fun imageGenerationStartedAt(toolCalls: List<ToolCall>): Long? =
    toolCalls.lastOrNull {
        it.name.trim().lowercase() == IMAGE_GENERATION_TOOL
    }?.startedAt

internal fun formatGenerationDuration(elapsedMillis: Long): String =
    String.format(Locale.ROOT, "%.1fs", elapsedMillis.coerceAtLeast(0L) / 1_000.0)

/**
 * Keep the image canvas alive across the short tool-complete → media-arrival
 * handoff. Once a result surface exists it can crossfade into the same bubble;
 * a failed or fully-finished turn never leaves a stale canvas behind.
 */
internal fun shouldShowImageGenerationPlaceholder(
    toolCalls: List<ToolCall>,
    isStreaming: Boolean,
    hasMediaResult: Boolean,
): Boolean {
    val imageCalls = toolCalls.filter {
        it.name.trim().lowercase() == IMAGE_GENERATION_TOOL
    }
    if (imageCalls.any { !it.isComplete }) return true
    return !hasMediaResult &&
        isStreaming &&
        imageCalls.any { it.isComplete && it.success != false }
}

/**
 * Image generation is a user-visible result lifecycle, not generic tool
 * diagnostics. Keep its active canvas visible even when upstream
 * `display.tool_progress` hides ordinary tool cards.
 */
internal fun ToolCall.isVisibleForToolDisplay(toolDisplay: String): Boolean =
    toolDisplay != "off" || showsImageGenerationPlaceholder()

/**
 * Theme-aware latent diffusion preview for an active Hermes image-generation
 * tool. It specializes the generic tool lifecycle already emitted by vanilla
 * Hermes; no Relay-only protocol or server patch is required.
 */
@Composable
fun ImageGenerationPlaceholder(
    modifier: Modifier = Modifier,
    startedAtMillis: Long? = null,
    animationDurationMillis: Int = DEFAULT_ANIMATION_DURATION_MS,
    visualStyle: ImageGenerationVisualStyle = ImageGenerationVisualStyle.LatentGrid,
    phaseOverride: Float? = null,
    elapsedOverrideMillis: Long? = null,
) {
    val description = stringResource(R.string.image_generation_rendering)
    val transition = rememberInfiniteTransition(label = "imageGenerationDiffusion")
    val animatedPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = animationDurationMillis.coerceAtLeast(800),
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "imageGenerationDiffusionPhase",
    )
    val phase = phaseOverride?.let { it - floor(it) } ?: animatedPhase
    var liveElapsedMillis by remember(startedAtMillis) {
        mutableLongStateOf(startedAtMillis?.let { System.currentTimeMillis() - it } ?: 0L)
    }
    LaunchedEffect(startedAtMillis, elapsedOverrideMillis) {
        if (elapsedOverrideMillis != null || startedAtMillis == null) return@LaunchedEffect
        while (true) {
            liveElapsedMillis = (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L)
            delay(100)
        }
    }
    val elapsedLabel = (elapsedOverrideMillis ?: liveElapsedMillis)
        .takeIf { elapsedOverrideMillis != null || startedAtMillis != null }
        ?.let(::formatGenerationDuration)
    val background = MaterialTheme.colorScheme.surfaceVariant
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        val canvasModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
        when (visualStyle) {
            ImageGenerationVisualStyle.LatentGrid -> DiffusionCanvas(
                phase = phase,
                background = background,
                foreground = foreground,
                primary = primary,
                tertiary = tertiary,
                modifier = canvasModifier,
            )
            ImageGenerationVisualStyle.ParticleOrb -> ParticleOrbCanvas(
                phase = phase,
                background = background,
                primary = primary,
                tertiary = tertiary,
                modifier = canvasModifier,
            )
            ImageGenerationVisualStyle.Constellation -> ConstellationCanvas(
                phase = phase,
                background = background,
                foreground = foreground,
                primary = primary,
                tertiary = tertiary,
                modifier = canvasModifier,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = foreground.copy(alpha = 0.72f),
            )
            elapsedLabel?.let { elapsed ->
                Text(
                    text = elapsed,
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground.copy(alpha = 0.82f),
                )
            }
        }
    }
}

/**
 * Keeps the active generation canvas mounted beneath the finished result and
 * reveals the result along a feathered 45-degree denoising front.
 */
@Composable
fun ImageGenerationResultTransition(
    generating: Boolean,
    startedAtMillis: Long?,
    modifier: Modifier = Modifier,
    animationDurationMillis: Int = DEFAULT_ANIMATION_DURATION_MS,
    visualStyle: ImageGenerationVisualStyle = ImageGenerationVisualStyle.LatentGrid,
    resultContent: @Composable () -> Unit,
) {
    val transitionPhase = remember { Animatable(0f) }
    var handoffReady by remember { mutableStateOf(false) }
    LaunchedEffect(generating, animationDurationMillis, visualStyle) {
        if (generating) {
            handoffReady = false
            while (isActive) {
                val remaining = (1f - transitionPhase.value).coerceAtLeast(0.001f)
                transitionPhase.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = (
                            animationDurationMillis.coerceAtLeast(800) * remaining
                            ).toInt().coerceAtLeast(1),
                        easing = LinearEasing,
                    ),
                )
                transitionPhase.snapTo(0f)
            }
        } else {
            val current = transitionPhase.value - floor(transitionPhase.value)
            val target = imageGenerationHandoffPhase(visualStyle)
            val forwardDistance = if (target >= current) {
                target - current
            } else {
                1f - current + target
            }
            transitionPhase.animateTo(
                targetValue = transitionPhase.value + forwardDistance,
                animationSpec = tween(
                    durationMillis = (
                        animationDurationMillis * forwardDistance
                        ).toInt().coerceIn(180, 450),
                    easing = FastOutSlowInEasing,
                ),
            )
            handoffReady = true
        }
    }
    val revealProgress by animateFloatAsState(
        targetValue = if (!generating && handoffReady) 1f else 0f,
        animationSpec = tween(durationMillis = 1_650, easing = FastOutSlowInEasing),
        label = "imageGenerationPixelReveal",
    )

    Box(
        modifier = modifier.clip(RoundedCornerShape(18.dp)),
    ) {
        if (generating || revealProgress < 0.999f) {
            ImageGenerationPlaceholder(
                startedAtMillis = startedAtMillis,
                animationDurationMillis = animationDurationMillis,
                visualStyle = visualStyle,
                phaseOverride = transitionPhase.value,
            )
        }
        if (!generating || revealProgress > 0.001f) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AgslImageReveal(progress = revealProgress) {
                    resultContent()
                }
            } else {
                Box(modifier = Modifier.pixelGenerationReveal(revealProgress)) {
                    resultContent()
                }
            }
        }
    }
}

internal fun imageGenerationHandoffPhase(style: ImageGenerationVisualStyle): Float = when (style) {
    ImageGenerationVisualStyle.LatentGrid -> 0.8f
    ImageGenerationVisualStyle.ParticleOrb -> 0.68f
    ImageGenerationVisualStyle.Constellation -> 0.6f
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AgslImageReveal(
    progress: Float,
    content: @Composable () -> Unit,
) {
    var layerSize by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    val shader = remember { RuntimeShader(IMAGE_REVEAL_SHADER) }
    Box(
        modifier = Modifier
            .onSizeChanged { layerSize = it }
            .graphicsLayer {
                if (layerSize.width > 0 && layerSize.height > 0) {
                    shader.setFloatUniform(
                        "resolution",
                        layerSize.width.toFloat(),
                        layerSize.height.toFloat(),
                    )
                    shader.setFloatUniform("progress", progress.coerceIn(0f, 1f))
                    renderEffect = RenderEffect
                        .createRuntimeShaderEffect(shader, "image")
                        .asComposeRenderEffect()
                }
            },
    ) {
        content()
    }
}

internal fun Modifier.pixelGenerationReveal(progress: Float): Modifier =
    drawWithContent {
        val renderContent = { drawContent() }
        val normalized = progress.coerceIn(0f, 1f)
        if (normalized >= 0.999f) {
            renderContent()
            return@drawWithContent
        }

        val columns = 22
        val rows = 13
        val cellWidth = size.width / columns
        val cellHeight = size.height / rows
        val revealPath = Path()
        val solidThreshold = 2.42f - normalized * 2.84f
        revealPath.addDiagonalRevealRegion(
            width = size.width,
            height = size.height,
            threshold = solidThreshold,
        )
        repeat(rows) { row ->
            repeat(columns) { column ->
                val diagonal = (
                    column / (columns - 1f) +
                        row / (rows - 1f)
                    )
                val jitter = (hash01(column + 47, row - 29) - 0.5f) * 0.16f
                val localReveal = smoothstep(
                    solidThreshold - 0.22f,
                    solidThreshold + 0.02f,
                    diagonal + jitter,
                )
                if (localReveal <= 0f) return@repeat

                val cellScale = 0.24f + localReveal * 0.82f
                val width = cellWidth * cellScale
                val height = cellHeight * cellScale
                val center = Offset(
                    x = (column + 0.5f) * cellWidth,
                    y = (row + 0.5f) * cellHeight,
                )
                revealPath.addRoundRect(
                    RoundRect(
                        left = center.x - width * 0.5f,
                        top = center.y - height * 0.5f,
                        right = center.x + width * 0.5f,
                        bottom = center.y + height * 0.5f,
                        cornerRadius = CornerRadius(
                            x = width * 0.24f,
                            y = height * 0.24f,
                        ),
                    ),
                )
            }
        }
        clipPath(revealPath) {
            renderContent()
        }
    }

private fun Path.addDiagonalRevealRegion(
    width: Float,
    height: Float,
    threshold: Float,
) {
    when {
        threshold <= 0f -> addRect(
            androidx.compose.ui.geometry.Rect(0f, 0f, width, height),
        )
        threshold < 1f -> {
            moveTo(threshold * width, 0f)
            lineTo(width, 0f)
            lineTo(width, height)
            lineTo(0f, height)
            lineTo(0f, threshold * height)
            close()
        }
        threshold < 2f -> {
            moveTo(width, (threshold - 1f) * height)
            lineTo(width, height)
            lineTo((threshold - 1f) * width, height)
            close()
        }
    }
}

@Composable
private fun DiffusionCanvas(
    phase: Float,
    background: Color,
    foreground: Color,
    primary: Color,
    tertiary: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawNousBackdrop(phase, background, primary, tertiary)
        val cellWidth = size.width / GRID_COLUMNS
        val cellHeight = size.height / GRID_ROWS
        val denoise = diffusionDenoise(phase)
        val time = phase * 9f

        repeat(GRID_ROWS) { row ->
            repeat(GRID_COLUMNS) { column ->
                val signal = diffusionSignal(column, row, time, denoise)
                if (signal < 0.2f) return@repeat

                val x = column * cellWidth + cellWidth * 0.5f
                val y = row * cellHeight + cellHeight * 0.5f
                val radius = (cellWidth.coerceAtMost(cellHeight) * (0.14f + signal * 0.24f))
                    .coerceAtLeast(0.7.dp.toPx())
                val warmMix = ((signal - 0.35f) / 0.65f).coerceIn(0f, 1f)
                val base = lerpColor(foreground, primary, warmMix)
                val color = lerpColor(base, tertiary, hash01(column + 17, row - 11) * 0.32f)

                drawRoundRect(
                    color = color.copy(alpha = (0.08f + signal * 0.76f).coerceAtMost(0.84f)),
                    topLeft = Offset(x - radius, y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * 0.45f),
                )
            }
        }
    }
}

@Composable
private fun ParticleOrbCanvas(
    phase: Float,
    background: Color,
    primary: Color,
    tertiary: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawNousBackdrop(
            phase = phase,
            background = background,
            primary = primary,
            tertiary = tertiary,
            driftScale = 0f,
        )
        val time = phase * Math.PI.toFloat() * 2f
        val meridians = 20
        val latitudes = 15
        val globalResolve = orbStructureResolve(phase)
        val sliceAngles = rubiksSliceAngles(phase)
        val tilt = -0.24f
        val cosTilt = cos(tilt)
        val sinTilt = sin(tilt)
        val orbRadius = size.minDimension * 0.35f
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        drawOrbMaterialDust(
            resolve = globalResolve,
            center = center,
            radius = orbRadius,
            cosTilt = cosTilt,
            sinTilt = sinTilt,
            primary = primary,
            tertiary = tertiary,
        )
        val projected = List(meridians) { meridian ->
            List(latitudes) { latitude ->
                val latitudeFraction = latitude / (latitudes - 1f)
                val latitudeAngle = -Math.PI.toFloat() * 0.5f +
                    latitudeFraction * Math.PI.toFloat()
                val longitude = meridian / meridians.toFloat() *
                    Math.PI.toFloat() * 2f
                val latitudeRadius = cos(latitudeAngle)
                val originalX = cos(longitude) * latitudeRadius
                val originalZ = sin(longitude) * latitudeRadius
                val originalY = sin(latitudeAngle)
                var sphereX = originalX
                var sphereY = originalY
                var sphereZ = originalZ

                if (originalY > 1f / 3f) {
                    val rotated = rotateAroundY(
                        x = sphereX,
                        y = sphereY,
                        z = sphereZ,
                        angle = sliceAngles.topY,
                    )
                    sphereX = rotated.x
                    sphereY = rotated.y
                    sphereZ = rotated.z
                }
                if (originalX > 1f / 3f) {
                    val rotated = rotateAroundX(
                        x = sphereX,
                        y = sphereY,
                        z = sphereZ,
                        angle = sliceAngles.rightX,
                    )
                    sphereX = rotated.x
                    sphereY = rotated.y
                    sphereZ = rotated.z
                }
                if (originalZ > 1f / 3f) {
                    val rotated = rotateAroundZ(
                        x = sphereX,
                        y = sphereY,
                        z = sphereZ,
                        angle = sliceAngles.frontZ,
                    )
                    sphereX = rotated.x
                    sphereY = rotated.y
                    sphereZ = rotated.z
                }
                val projectedY = sphereY * cosTilt - sphereZ * sinTilt
                val projectedZ = sphereY * sinTilt + sphereZ * cosTilt
                val perspective = 0.8f + (projectedZ + 1f) * 0.18f
                val structuredOffset = Offset(
                    x = center.x + sphereX * orbRadius * perspective,
                    y = center.y + projectedY * orbRadius * perspective,
                )
                val pointIndex = meridian * latitudes + latitude
                val latentOffset = Offset(
                    x = size.width * (0.1f + hash01(pointIndex * 7 + 3, 41) * 0.8f),
                    y = size.height * (0.12f + hash01(pointIndex * 11 - 9, 67) * 0.76f),
                )
                val stagger = hash01(pointIndex, 83) * 0.16f
                val pointResolve = smoothstep(
                    stagger,
                    0.82f + stagger,
                    globalResolve,
                )
                ProjectedOrbPoint(
                    offset = Offset(
                        x = latentOffset.x +
                            (structuredOffset.x - latentOffset.x) * pointResolve,
                        y = latentOffset.y +
                            (structuredOffset.y - latentOffset.y) * pointResolve,
                    ),
                    depth = ((projectedZ + 1f) * 0.5f).coerceIn(0f, 1f),
                    resolve = pointResolve,
                    section = (
                        thirdIndex(originalX) +
                            thirdIndex(originalY) +
                            thirdIndex(originalZ)
                        ) / 6f,
                )
            }
        }

        listOf(5, 9).forEach { latitude ->
            repeat(meridians) { meridian ->
                val start = projected[meridian][latitude]
                val end = projected[(meridian + 1) % meridians][latitude]
                val depth = (start.depth + end.depth) * 0.5f
                drawLine(
                    color = lerpColor(primary, tertiary, latitude / (latitudes - 1f))
                        .copy(alpha = globalResolve * globalResolve * (0.03f + depth * 0.18f)),
                    start = start.offset,
                    end = end.offset,
                    strokeWidth = (0.35f + depth * 0.55f).dp.toPx(),
                )
            }
        }
        listOf(0, 5, 10, 15).forEach { meridian ->
            repeat(latitudes - 1) { latitude ->
                val start = projected[meridian][latitude]
                val end = projected[meridian][latitude + 1]
                val depth = (start.depth + end.depth) * 0.5f
                drawLine(
                    color = lerpColor(primary, tertiary, meridian / (meridians - 1f))
                        .copy(alpha = globalResolve * globalResolve * (0.025f + depth * 0.14f)),
                    start = start.offset,
                    end = end.offset,
                    strokeWidth = (0.3f + depth * 0.45f).dp.toPx(),
                )
            }
        }
        projected.forEachIndexed { meridian, strand ->
            strand.forEachIndexed { latitude, point ->
                val pulse = sin(time * 3f + meridian * 0.45f + latitude * 0.5f) *
                    0.5f + 0.5f
                val color = lerpColor(primary, tertiary, point.section)
                drawCircle(
                    color = color.copy(
                        alpha = 0.025f + point.resolve * (0.03f + point.depth * 0.09f),
                    ),
                    radius = (
                        1.4f + point.resolve * (1.1f + point.depth * 1.8f) + pulse * 0.55f
                        ).dp.toPx(),
                    center = point.offset,
                )
                drawCircle(
                    color = color.copy(
                        alpha = 0.28f + point.resolve * point.depth * 0.66f,
                    ),
                    radius = (
                        0.5f + point.resolve * point.depth * 1.05f + pulse * 0.22f
                        ).dp.toPx(),
                    center = point.offset,
                )
            }
        }
    }
}

private fun DrawScope.drawOrbMaterialDust(
    resolve: Float,
    center: Offset,
    radius: Float,
    cosTilt: Float,
    sinTilt: Float,
    primary: Color,
    tertiary: Color,
) {
    repeat(48) { index ->
        val latent = Offset(
            x = size.width * (0.04f + hash01(index * 13 + 7, 31) * 0.92f),
            y = size.height * (0.06f + hash01(index * 17 - 3, 47) * 0.88f),
        )
        val y = 1f - (index + 0.5f) / 48f * 2f
        val radial = kotlin.math.sqrt((1f - y * y).coerceAtLeast(0f))
        val angle = index * 2.3999632f
        val x = cos(angle) * radial
        val z = sin(angle) * radial
        val projectedY = y * cosTilt - z * sinTilt
        val projectedZ = y * sinTilt + z * cosTilt
        val perspective = 0.8f + (projectedZ + 1f) * 0.18f
        val destination = Offset(
            x = center.x + x * radius * perspective,
            y = center.y + projectedY * radius * perspective,
        )
        val stagger = hash01(index, 101) * 0.24f
        val travel = smoothstep(stagger, 0.84f + stagger, resolve)
        val position = Offset(
            x = latent.x + (destination.x - latent.x) * travel,
            y = latent.y + (destination.y - latent.y) * travel,
        )
        val unresolved = 1f - travel
        val color = lerpColor(primary, tertiary, hash01(index, 113))
        drawCircle(
            color = color.copy(alpha = 0.04f + unresolved * 0.2f),
            radius = (0.55f + unresolved * 1.25f).dp.toPx(),
            center = position,
        )
    }
}

private data class ProjectedOrbPoint(
    val offset: Offset,
    val depth: Float,
    val resolve: Float,
    val section: Float,
)

internal fun orbStructureResolve(phase: Float): Float {
    val normalized = phase - floor(phase)
    return when {
        normalized < 0.3f -> smoothstep(0f, 0.3f, normalized)
        normalized < 0.7f -> 1f
        else -> 1f - smoothstep(0.7f, 1f, normalized)
    }
}

internal fun rubiksSliceAngles(phase: Float): RubiksSliceAngles {
    val normalized = phase - floor(phase)
    val quarterTurn = Math.PI.toFloat() * 0.5f
    return RubiksSliceAngles(
        topY = quarterTurn * turnPulse(normalized, 0.3f, 0.34f, 0.36f, 0.4f),
        frontZ = quarterTurn * turnPulse(normalized, 0.42f, 0.46f, 0.48f, 0.52f),
        rightX = -quarterTurn * turnPulse(normalized, 0.54f, 0.58f, 0.6f, 0.64f),
    )
}

internal data class RubiksSliceAngles(
    val topY: Float,
    val rightX: Float,
    val frontZ: Float,
)

private data class OrbPoint3d(
    val x: Float,
    val y: Float,
    val z: Float,
)

private fun turnPulse(
    phase: Float,
    turnStart: Float,
    turnEnd: Float,
    returnStart: Float,
    returnEnd: Float,
): Float = when {
    phase <= turnStart || phase >= returnEnd -> 0f
    phase < turnEnd -> smoothstep(turnStart, turnEnd, phase)
    phase <= returnStart -> 1f
    else -> 1f - smoothstep(returnStart, returnEnd, phase)
}

private fun rotateAroundX(x: Float, y: Float, z: Float, angle: Float): OrbPoint3d =
    OrbPoint3d(
        x = x,
        y = y * cos(angle) - z * sin(angle),
        z = y * sin(angle) + z * cos(angle),
    )

private fun rotateAroundY(x: Float, y: Float, z: Float, angle: Float): OrbPoint3d =
    OrbPoint3d(
        x = x * cos(angle) + z * sin(angle),
        y = y,
        z = -x * sin(angle) + z * cos(angle),
    )

private fun rotateAroundZ(x: Float, y: Float, z: Float, angle: Float): OrbPoint3d =
    OrbPoint3d(
        x = x * cos(angle) - y * sin(angle),
        y = x * sin(angle) + y * cos(angle),
        z = z,
    )

private fun thirdIndex(value: Float): Int = when {
    value < -1f / 3f -> 0
    value > 1f / 3f -> 2
    else -> 1
}

@Composable
private fun ConstellationCanvas(
    phase: Float,
    background: Color,
    foreground: Color,
    primary: Color,
    tertiary: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawNousBackdrop(
            phase = phase,
            background = background,
            primary = primary,
            tertiary = tertiary,
            driftScale = 0f,
        )
        val time = phase * Math.PI.toFloat() * 2f
        val denoise = structureResolve(phase)
        val successSweep = nodeSuccessSweep(phase)
        val successSettle = nodeSuccessSettle(phase)
        drawNodeMaterialFragments(
            resolve = denoise,
            primary = primary,
            tertiary = tertiary,
        )
        val nodes = List(32) { index ->
            val latent = Offset(
                x = size.width * (0.06f + hash01(index * 7 + 3, 11) * 0.88f),
                y = size.height * (0.09f + hash01(index * 11 - 5, 23) * 0.82f),
            )
            val row = index / 8
            val column = index % 8
            val target = Offset(
                x = size.width * (0.16f + column / 7f * 0.68f),
                y = size.height * (0.22f + row / 3f * 0.56f),
            )
            val resolve = smoothstep(
                -0.12f,
                0.92f,
                denoise + (hash01(index, 71) - 0.5f) * 0.18f,
            )
            Offset(
                x = latent.x + (target.x - latent.x) * resolve +
                    sin(time + index * 0.77f) * (1f - resolve) * 5.dp.toPx(),
                y = latent.y + (target.y - latent.y) * resolve +
                    sin(time * 2f + index * 1.13f) * (1f - resolve) * 5.dp.toPx(),
            )
        }
        val edges = buildList {
            repeat(4) { row ->
                repeat(8) { column ->
                    val index = row * 8 + column
                    if (column < 7) add(index to index + 1)
                    if (row < 3) add(index to index + 8)
                }
            }
        }
        edges.forEachIndexed { edgeIndex, (startIndex, endIndex) ->
            val edgeOrder = edgeIndex / edges.lastIndex.toFloat()
            val edgeResolve = smoothstep(
                edgeOrder * 0.66f,
                edgeOrder * 0.66f + 0.34f,
                denoise,
            )
            if (edgeResolve <= 0f) return@forEachIndexed

            val start = nodes[startIndex]
            val end = nodes[endIndex]
            val startRow = startIndex / 8
            val startColumn = startIndex % 8
            val endRow = endIndex / 8
            val endColumn = endIndex % 8
            val edgePosition = (
                (startColumn + endColumn) / 14f +
                    (startRow + endRow) / 6f
                ) * 0.5f
            val sweepHighlight = if (successSweep >= 0f) {
                (1f - abs(edgePosition - successSweep) * 7f).coerceIn(0f, 1f)
            } else {
                0f
            }
            val color = lerpColor(primary, tertiary, edgePosition)
            drawLine(
                color = color.copy(
                    alpha = edgeResolve * (
                        0.18f + sweepHighlight * 0.54f + successSettle * 0.18f
                        ),
                ),
                start = start,
                end = end,
                strokeWidth = (
                    0.55f + edgeResolve * 0.75f +
                        sweepHighlight * 0.75f + successSettle * 0.28f
                    ).dp.toPx(),
            )
        }
        nodes.forEachIndexed { index, node ->
            val pulse = (sin(time * 1.4f + index * 0.9f) * 0.5f + 0.5f)
            val color = lerpColor(primary, tertiary, hash01(index, index + 9))
            val row = index / 8
            val column = index % 8
            val nodePosition = (column / 7f + row / 3f) * 0.5f
            val sweepHighlight = if (successSweep >= 0f) {
                (1f - abs(nodePosition - successSweep) * 7f).coerceIn(0f, 1f)
            } else {
                0f
            }
            drawCircle(
                color = color.copy(
                    alpha = 0.05f + denoise * 0.12f + pulse * 0.08f +
                        sweepHighlight * 0.16f + successSettle * 0.08f,
                ),
                radius = (
                    3f + denoise * 5f + pulse * 2f +
                        sweepHighlight * 2.5f + successSettle * 1.2f
                    ).dp.toPx(),
                center = node,
            )
            drawCircle(
                color = lerpColor(foreground, color, 0.72f)
                    .copy(
                        alpha = 0.5f + denoise * 0.35f + pulse * 0.12f +
                            sweepHighlight * 0.18f,
                    ),
                radius = (
                    1.1f + denoise * 1.5f + pulse * 0.7f +
                        sweepHighlight * 0.85f + successSettle * 0.35f
                    ).dp.toPx(),
                center = node,
            )
        }
    }
}

private fun DrawScope.drawNodeMaterialFragments(
    resolve: Float,
    primary: Color,
    tertiary: Color,
) {
    repeat(28) { index ->
        val latent = Offset(
            x = size.width * (0.04f + hash01(index * 19 + 5, 131) * 0.92f),
            y = size.height * (0.06f + hash01(index * 23 - 7, 149) * 0.88f),
        )
        val targetIndex = (index * 13) % 32
        val row = targetIndex / 8
        val column = targetIndex % 8
        val target = Offset(
            x = size.width * (0.16f + column / 7f * 0.68f),
            y = size.height * (0.22f + row / 3f * 0.56f),
        )
        val stagger = hash01(index, 157) * 0.28f
        val travel = smoothstep(stagger, 0.86f + stagger, resolve)
        val position = Offset(
            x = latent.x + (target.x - latent.x) * travel,
            y = latent.y + (target.y - latent.y) * travel,
        )
        val unresolved = 1f - travel
        if (unresolved <= 0.02f) return@repeat

        val direction = target - position
        val length = direction.getDistance().coerceAtLeast(1f)
        val unit = direction / length
        val fragmentLength = (3.5f + hash01(index, 163) * 8f).dp.toPx()
        val color = lerpColor(primary, tertiary, hash01(index, 167))
        drawLine(
            color = color.copy(alpha = unresolved * 0.16f),
            start = position,
            end = position + unit * fragmentLength,
            strokeWidth = (0.45f + unresolved * 0.45f).dp.toPx(),
        )
        drawCircle(
            color = color.copy(alpha = 0.05f + unresolved * 0.28f),
            radius = (0.6f + unresolved * 1.1f).dp.toPx(),
            center = position,
        )
    }
}

internal fun structureResolve(phase: Float): Float {
    val normalized = phase - floor(phase)
    return when {
        normalized < 0.34f -> smoothstep(0f, 0.34f, normalized)
        normalized < 0.62f -> 1f
        else -> 1f - smoothstep(0.62f, 1f, normalized)
    }
}

internal fun nodeSuccessSweep(phase: Float): Float {
    val normalized = phase - floor(phase)
    return if (normalized in 0.36f..0.56f) {
        smoothstep(0.36f, 0.56f, normalized)
    } else {
        -1f
    }
}

internal fun nodeSuccessSettle(phase: Float): Float {
    val normalized = phase - floor(phase)
    return if (normalized in 0.52f..0.62f) {
        sin(((normalized - 0.52f) / 0.1f) * Math.PI.toFloat())
            .coerceIn(0f, 1f)
    } else {
        0f
    }
}

private fun DrawScope.drawNousBackdrop(
    phase: Float,
    background: Color,
    primary: Color,
    tertiary: Color,
    driftScale: Float = 1f,
) {
    val time = phase * Math.PI.toFloat() * 2f
    val drift = Offset(
        x = size.width * (0.5f + cos(time) * 0.14f * driftScale),
        y = size.height * (0.46f + sin(time) * 0.1f * driftScale),
    )
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                background,
                lerpColor(background, primary, 0.12f),
                lerpColor(background, tertiary, 0.09f),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                primary.copy(alpha = 0.18f),
                tertiary.copy(alpha = 0.07f),
                Color.Transparent,
            ),
            center = drift,
            radius = size.minDimension * 0.68f,
        ),
        radius = size.minDimension * 0.68f,
        center = drift,
    )
}

internal fun diffusionDenoise(phase: Float): Float {
    val normalized = phase - floor(phase)
    return if (normalized < 0.82f) {
        smoothstep(0.02f, 0.82f, normalized)
    } else {
        1f - smoothstep(0.82f, 1f, normalized)
    }
}

internal fun diffusionSignal(column: Int, row: Int, time: Float, denoise: Float): Float {
    val nx = (column + 0.5f) / GRID_COLUMNS - 0.52f
    val ny = (row + 0.5f) / GRID_ROWS - 0.5f
    val radius = kotlin.math.sqrt(nx * nx * 1.35f + ny * ny)
    val bloom = (1f - radius * 2.35f).coerceIn(0f, 1f)
    val ring = (1f - abs(radius - (0.23f + sin(time * 0.44f) * 0.025f)) * 15f)
        .coerceIn(0f, 1f)
    val latent = (bloom * 0.75f + ring * 0.5f).coerceIn(0f, 1f)
    val staticNoise = hash01(column + floor(time * 3f).toInt() * 19, row - floor(time * 3f).toInt() * 11)
    val livingNoise = hash01(column + floor(time * 7f).toInt(), row + floor(time * 5f).toInt())
    return (
        staticNoise * (1f - denoise) +
            latent * denoise +
            (livingNoise - 0.5f) * (0.42f - denoise * 0.2f)
        ).coerceIn(0f, 1f)
}

private fun hash01(x: Int, y: Int): Float {
    val value = sin(x * 127.1 + y * 311.7) * 43_758.5453
    return (value - floor(value)).toFloat()
}

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpColor(from: Color, to: Color, amount: Float): Color = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
    alpha = from.alpha + (to.alpha - from.alpha) * amount,
)
