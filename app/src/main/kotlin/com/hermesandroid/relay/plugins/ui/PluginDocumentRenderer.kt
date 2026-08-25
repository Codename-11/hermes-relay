package com.hermesandroid.relay.plugins.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.plugins.document.PluginAction
import com.hermesandroid.relay.plugins.document.PluginAnimation
import com.hermesandroid.relay.plugins.document.PluginAnimationKind
import com.hermesandroid.relay.plugins.document.PluginAnimationSpeed
import com.hermesandroid.relay.plugins.document.PluginAssetReference
import com.hermesandroid.relay.plugins.document.PluginButtonStyle
import com.hermesandroid.relay.plugins.document.PluginDirection
import com.hermesandroid.relay.plugins.document.PluginDocument
import com.hermesandroid.relay.plugins.document.PluginDocumentState
import com.hermesandroid.relay.plugins.document.PluginElement
import com.hermesandroid.relay.plugins.document.PluginPage
import com.hermesandroid.relay.plugins.document.PluginSpacing
import com.hermesandroid.relay.plugins.document.PluginTextStyle
import com.hermesandroid.relay.plugins.document.PluginTone
import com.hermesandroid.relay.plugins.document.PluginValue
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.ui.components.rememberAccessibleMotionState

sealed interface PluginInteraction {
    data class ActionInvoked(
        val elementId: String,
        val action: PluginAction,
    ) : PluginInteraction

    data class ValueChanged(
        val elementId: String,
        val key: String,
        val value: PluginValue,
    ) : PluginInteraction
}

typealias PluginAssetContent = @Composable (
    reference: PluginAssetReference,
    contentDescription: String,
    modifier: Modifier,
) -> Unit

/**
 * Renders one complete plugin page using host-owned Compose components.
 *
 * [state] is controlled by the caller. Every local edit is emitted as a [PluginInteraction],
 * making server reconciliation and permission checks explicit rather than hidden in UI code.
 */
@Composable
fun PluginPageRenderer(
    document: PluginDocument,
    pageId: String,
    state: PluginDocumentState,
    onInteraction: (PluginInteraction) -> Unit,
    modifier: Modifier = Modifier,
    assetContent: PluginAssetContent = { _, description, assetModifier ->
        DefaultPluginAsset(description, assetModifier)
    },
) {
    val page = document.pages.firstOrNull { it.id == pageId }
    if (page == null) {
        return
    }
    PluginPageRenderer(
        page = page,
        state = state,
        onInteraction = onInteraction,
        modifier = modifier,
        assetContent = assetContent,
    )
}

@Composable
fun PluginPageRenderer(
    page: PluginPage,
    state: PluginDocumentState,
    onInteraction: (PluginInteraction) -> Unit,
    modifier: Modifier = Modifier,
    assetContent: PluginAssetContent = { _, description, assetModifier ->
        DefaultPluginAsset(description, assetModifier)
    },
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = state.resolve(page.title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        RenderElement(
            element = page.content,
            state = state,
            onInteraction = onInteraction,
            assetContent = assetContent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RenderElement(
    element: PluginElement,
    state: PluginDocumentState,
    onInteraction: (PluginInteraction) -> Unit,
    assetContent: PluginAssetContent,
    modifier: Modifier = Modifier,
) {
    val visible = state.matches(element.visibleWhen)
    val duration = element.animation.speed.milliseconds
    val accessibleMotion = rememberAccessibleMotionState()
    val animation = if (accessibleMotion.osAnimations && !accessibleMotion.touchExploration) {
        element.animation
    } else {
        PluginAnimation()
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = animation.enter.enterTransition(duration),
        exit = animation.enter.exitTransition(duration),
    ) {
        val animatedModifier = Modifier.pluginChangeAnimation(
            animation = animation,
            stateFingerprint = state.values.hashCode(),
        )
        when (element) {
            is PluginElement.Group -> PluginGroup(
                element = element,
                state = state,
                onInteraction = onInteraction,
                assetContent = assetContent,
                modifier = animatedModifier,
            )
            is PluginElement.Card -> Card(modifier = animatedModifier.fillMaxWidth()) {
                RenderElement(
                    element = element.child,
                    state = state,
                    onInteraction = onInteraction,
                    assetContent = assetContent,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            is PluginElement.Text -> Text(
                text = state.resolve(element.text),
                style = when (element.style) {
                    PluginTextStyle.BODY -> MaterialTheme.typography.bodyLarge
                    PluginTextStyle.LABEL -> MaterialTheme.typography.labelLarge
                    PluginTextStyle.TITLE -> MaterialTheme.typography.titleMedium
                    PluginTextStyle.HEADLINE -> MaterialTheme.typography.headlineSmall
                },
                modifier = animatedModifier,
            )
            is PluginElement.Badge -> PluginBadge(element, state, animatedModifier)
            is PluginElement.Button -> PluginButton(element, state, onInteraction, animatedModifier)
            is PluginElement.TextInput -> OutlinedTextField(
                value = (state[element.binding] as? PluginValue.StringValue)?.value.orEmpty(),
                onValueChange = { value ->
                    onInteraction(
                        PluginInteraction.ValueChanged(
                            elementId = element.id,
                            key = element.binding,
                            value = PluginValue.StringValue(value.take(element.maxLength)),
                        ),
                    )
                },
                label = { Text(state.resolve(element.label)) },
                placeholder = element.placeholder?.let { placeholder ->
                    { Text(state.resolve(placeholder)) }
                },
                singleLine = false,
                modifier = animatedModifier.fillMaxWidth(),
            )
            is PluginElement.Toggle -> Row(
                modifier = animatedModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.resolve(element.label), modifier = Modifier.weight(1f))
                Switch(
                    checked = (state[element.binding] as? PluginValue.BooleanValue)?.value == true,
                    enabled = state.matches(element.enabledWhen),
                    onCheckedChange = { checked ->
                        onInteraction(
                            PluginInteraction.ValueChanged(
                                elementId = element.id,
                                key = element.binding,
                                value = PluginValue.BooleanValue(checked),
                            ),
                        )
                    },
                )
            }
            is PluginElement.Progress -> PluginProgress(element, state, animatedModifier)
            is PluginElement.Image -> assetContent(
                element.asset,
                state.resolve(element.contentDescription),
                animatedModifier.fillMaxWidth().aspectRatio(element.aspectRatio.coerceIn(0.25f, 4f)),
            )
            is PluginElement.Divider -> HorizontalDivider(modifier = animatedModifier.fillMaxWidth())
            is PluginElement.Spacer -> Spacer(modifier = animatedModifier.height(element.size.dp))
        }
    }
}

@Composable
private fun PluginGroup(
    element: PluginElement.Group,
    state: PluginDocumentState,
    onInteraction: (PluginInteraction) -> Unit,
    assetContent: PluginAssetContent,
    modifier: Modifier,
) {
    if (element.direction == PluginDirection.COLUMN) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(element.spacing.dp)) {
            element.children.forEach { child ->
                RenderElement(child, state, onInteraction, assetContent, Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(element.spacing.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            element.children.forEach { child -> RenderElement(child, state, onInteraction, assetContent) }
        }
    }
}

@Composable
private fun PluginBadge(
    element: PluginElement.Badge,
    state: PluginDocumentState,
    modifier: Modifier,
) {
    val color = when (element.tone) {
        PluginTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        PluginTone.INFO -> MaterialTheme.colorScheme.primaryContainer
        PluginTone.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
        PluginTone.WARNING -> MaterialTheme.colorScheme.secondaryContainer
        PluginTone.DANGER -> MaterialTheme.colorScheme.errorContainer
    }
    Surface(modifier = modifier, color = color, shape = RoundedCornerShape(999.dp)) {
        Text(
            text = state.resolve(element.text),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun PluginButton(
    element: PluginElement.Button,
    state: PluginDocumentState,
    onInteraction: (PluginInteraction) -> Unit,
    modifier: Modifier,
) {
    val onClick = { onInteraction(PluginInteraction.ActionInvoked(element.id, element.action)) }
    val enabled = state.matches(element.enabledWhen)
    when (element.style) {
        PluginButtonStyle.PRIMARY -> Button(onClick, modifier, enabled) { Text(state.resolve(element.label)) }
        PluginButtonStyle.SECONDARY -> OutlinedButton(onClick, modifier, enabled) { Text(state.resolve(element.label)) }
        PluginButtonStyle.TEXT -> TextButton(onClick, modifier, enabled) { Text(state.resolve(element.label)) }
    }
}

@Composable
private fun PluginProgress(
    element: PluginElement.Progress,
    state: PluginDocumentState,
    modifier: Modifier,
) {
    val bound = element.valueBinding
        ?.let(state::get)
        ?.let { it as? PluginValue.NumberValue }
        ?.value
    val progress = (bound ?: element.value ?: 0.0).toFloat().coerceIn(0f, 1f)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        element.label?.let { Text(state.resolve(it), style = MaterialTheme.typography.labelMedium) }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DefaultPluginAsset(contentDescription: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(appearanceRoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Image,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun Modifier.pluginChangeAnimation(
    animation: PluginAnimation,
    stateFingerprint: Int,
): Modifier {
    val progress = remember { Animatable(1f) }
    var composed by remember { mutableStateOf(false) }
    LaunchedEffect(stateFingerprint, animation.change) {
        if (animation.change == PluginAnimationKind.NONE) {
            progress.snapTo(1f)
        } else if (composed) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(animation.speed.milliseconds))
        } else {
            composed = true
        }
    }
    if (animation.change == PluginAnimationKind.NONE) return this
    val p = progress.value
    return graphicsLayer {
        when (animation.change) {
            PluginAnimationKind.NONE -> Unit
            PluginAnimationKind.FADE -> alpha = 0.65f + (0.35f * p)
            PluginAnimationKind.SCALE -> {
                scaleX = 0.96f + (0.04f * p)
                scaleY = scaleX
            }
            PluginAnimationKind.SLIDE_VERTICAL -> translationY = 8.dp.toPx() * (1f - p)
            PluginAnimationKind.HIGHLIGHT -> {
                alpha = 0.82f + (0.18f * p)
                scaleX = 0.98f + (0.02f * p)
                scaleY = scaleX
            }
        }
    }
}

private fun PluginAnimationKind.enterTransition(duration: Int): EnterTransition = when (this) {
    PluginAnimationKind.NONE -> EnterTransition.None
    PluginAnimationKind.FADE -> fadeIn(tween(duration))
    PluginAnimationKind.SCALE -> fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = 0.96f)
    PluginAnimationKind.SLIDE_VERTICAL -> fadeIn(tween(duration)) +
        slideInVertically(tween(duration)) { height -> height.coerceAtMost(48) / 3 }
    PluginAnimationKind.HIGHLIGHT -> fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = 0.98f)
}

private fun PluginAnimationKind.exitTransition(duration: Int): ExitTransition = when (this) {
    PluginAnimationKind.NONE -> ExitTransition.None
    PluginAnimationKind.FADE -> fadeOut(tween(duration))
    PluginAnimationKind.SCALE -> fadeOut(tween(duration)) + scaleOut(tween(duration), targetScale = 0.96f)
    PluginAnimationKind.SLIDE_VERTICAL -> fadeOut(tween(duration)) +
        slideOutVertically(tween(duration)) { height -> height.coerceAtMost(48) / 3 }
    PluginAnimationKind.HIGHLIGHT -> fadeOut(tween(duration)) + scaleOut(tween(duration), targetScale = 0.98f)
}

private val PluginAnimationSpeed.milliseconds: Int
    get() = when (this) {
        PluginAnimationSpeed.FAST -> 120
        PluginAnimationSpeed.NORMAL -> 220
        PluginAnimationSpeed.SLOW -> 360
    }

private val PluginSpacing.dp
    get() = when (this) {
        PluginSpacing.NONE -> 0.dp
        PluginSpacing.SMALL -> 8.dp
        PluginSpacing.MEDIUM -> 16.dp
        PluginSpacing.LARGE -> 24.dp
    }
