package com.hermesandroid.relay.ui.components

import androidx.compose.material3.Badge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.hermesandroid.relay.R

@Composable
internal fun UnreadConversationBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val label = stringResource(R.string.chat_unread_conversations, count)
    Badge(modifier.clearAndSetSemantics { contentDescription = label }) {
        Text(if (count > 99) "99+" else count.toString())
    }
}
