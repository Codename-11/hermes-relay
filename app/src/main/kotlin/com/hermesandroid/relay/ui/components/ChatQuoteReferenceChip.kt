package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ChatQuoteReference

internal data class ChatQuoteReferenceColors(
    val background: Color,
    val author: Color,
    val excerpt: Color,
    val accent: Color,
)

internal fun chatQuoteReferenceColors(colorScheme: ColorScheme) = ChatQuoteReferenceColors(
    background = colorScheme.surfaceContainerHigh,
    author = colorScheme.onSurface,
    excerpt = colorScheme.onSurfaceVariant,
    accent = colorScheme.primary,
)

/** Attachment-like quote reference used in both the composer and sent bubbles. */
@Composable
fun ChatQuoteReferenceChip(
    reference: ChatQuoteReference,
    modifier: Modifier = Modifier,
    onOpenOriginal: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val openDescription = stringResource(R.string.chat_quote_open, reference.authorLabel)
    val colors = chatQuoteReferenceColors(MaterialTheme.colorScheme)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.background,
    ) {
        Row(
            modifier = Modifier
                .then(
                    onOpenOriginal?.let { open ->
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = openDescription,
                            onClick = open,
                        )
                    } ?: Modifier,
                )
                .semantics { contentDescription = "$openDescription. ${reference.excerpt}" }
                .padding(start = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(colors.accent),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "@${reference.authorLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.author,
                )
                Text(
                    text = reference.excerpt,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.excerpt,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_quote_remove),
                        tint = colors.excerpt,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
