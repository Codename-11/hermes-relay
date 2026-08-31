package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DEFAULT_AUTO_DISMISS_MILLIS = 10_000L
private const val EXIT_ANIMATION_MILLIS = 180L
private const val SWIPE_DISMISS_FRACTION = 0.28f

/**
 * Non-blocking post-update notice. It stays outside the dialog window so the
 * app remains usable, then hands off to the full [WhatsNewDialog] only when the
 * user chooses to expand it.
 */
@Composable
fun WhatsNewToast(
    onDismiss: () -> Unit,
    onExpand: () -> Unit,
    autoDismissMillis: Long = DEFAULT_AUTO_DISMISS_MILLIS,
) {
    val context = LocalContext.current
    val entry = remember { ChangelogStore.load(context).versions.firstOrNull() } ?: return
    val progress = remember { Animatable(1f) }
    val horizontalOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val accessibilityManager = LocalAccessibilityManager.current
    var visible by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(1) }

    fun leave(afterExit: () -> Unit) {
        if (!visible) return
        scope.launch {
            visible = false
            delay(EXIT_ANIMATION_MILLIS)
            afterExit()
        }
    }

    LaunchedEffect(Unit) {
        visible = true
        val recommendedTimeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = autoDismissMillis,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: autoDismissMillis
        progress.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = recommendedTimeout.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                easing = LinearEasing,
            ),
        )
        leave(onDismiss)
    }

    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = false),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 2 },
            exit = fadeOut(tween(EXIT_ANIMATION_MILLIS.toInt())) +
                slideOutVertically(tween(EXIT_ANIMATION_MILLIS.toInt())) { -it / 3 },
        ) {
            val swipeProgress = (abs(horizontalOffset.value) / widthPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .offset { IntOffset(horizontalOffset.value.roundToInt(), 0) }
                    .alpha(1f - (swipeProgress * 0.7f))
                    .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                    .pointerInput(onDismiss) {
                        var dragTarget = horizontalOffset.value
                        detectHorizontalDragGestures(
                            onDragStart = { dragTarget = horizontalOffset.value },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                dragTarget += amount
                                scope.launch { horizontalOffset.snapTo(dragTarget) }
                            },
                            onDragEnd = {
                                if (abs(horizontalOffset.value) >= widthPx * SWIPE_DISMISS_FRACTION) {
                                    scope.launch {
                                        horizontalOffset.animateTo(
                                            targetValue = if (horizontalOffset.value < 0) -widthPx.toFloat() else widthPx.toFloat(),
                                            animationSpec = tween(EXIT_ANIMATION_MILLIS.toInt()),
                                        )
                                        onDismiss()
                                    }
                                } else {
                                    scope.launch { horizontalOffset.animateTo(0f, tween(160)) }
                                }
                            },
                        )
                    },
            ) {
                WhatsNewToastContent(
                    entry = entry,
                    progress = progress.value,
                    onExpand = { leave(onExpand) },
                    onDismiss = { leave(onDismiss) },
                )
            }
        }
    }
}

@Composable
internal fun WhatsNewToastContent(
    entry: ChangelogVersion,
    progress: Float,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.whats_new_title)
    val highlight = entry.highlight
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = appearanceRoundedCornerShape(18.dp),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = title,
                            onClick = onExpand,
                        )
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$title · v${entry.version}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = highlight?.title ?: entry.title.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        highlight?.summary?.takeIf(String::isNotBlank)?.let { summary ->
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.changelog_close),
                    )
                }
            }
            entry.toastDigest?.takeIf {
                it.additionalFeatureCount > 0 || it.fixCount > 0
            }?.let { digest ->
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.changelog_view_all),
                            onClick = onExpand,
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val counts = buildList {
                            if (digest.additionalFeatureCount > 0) {
                                add(
                                    pluralStringResource(
                                        R.plurals.changelog_additional_feature_count,
                                        digest.additionalFeatureCount,
                                        digest.additionalFeatureCount,
                                    ),
                                )
                            }
                            if (digest.fixCount > 0) {
                                add(
                                    pluralStringResource(
                                        R.plurals.changelog_fix_count,
                                        digest.fixCount,
                                        digest.fixCount,
                                    ),
                                )
                            }
                        }
                        Text(
                            text = stringResource(
                                R.string.changelog_toast_also,
                                counts.joinToString(" · "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = digest.preview.joinToString(", ") + "…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.changelog_view_all),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
