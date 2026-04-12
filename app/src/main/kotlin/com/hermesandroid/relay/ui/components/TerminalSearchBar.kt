package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

/**
 * Inline search row that appears below the terminal top app bar.
 *
 * Owns its own query state — the parent only deals in find-next /
 * find-previous events plus a close-and-clear callback. The query is
 * not persisted across show/hide cycles, mirroring how Chrome's find
 * bar resets on close.
 *
 *  - Auto-focuses the text field when the bar appears so the user can
 *    start typing immediately. The keyboard pops up via the IME without
 *    needing an extra tap.
 *  - Pressing Enter (IME action Search) triggers find-next, matching
 *    Chrome / VS Code conventions.
 *  - Up / Down arrow IconButtons step through prev / next results.
 *  - Close button hides the bar AND fires `onClose` so the parent can
 *    invoke `window.clearSearch()` on the active tab to wipe decorations.
 *
 * The search itself is JS-side via `window.searchNext('text')` etc on the
 * active tab's WebView. This component knows nothing about WebViews — it
 * just sends string queries upward.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSearchBar(
    onSearchNext: (String) -> Unit,
    onSearchPrev: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // Defer one frame so the OutlinedTextField has been attached before
        // requesting focus — otherwise the call no-ops on first composition.
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) { /* not yet attached */ }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = { Text("Search scrollback") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { if (query.isNotEmpty()) onSearchNext(query) }
            ),
        )

        IconButton(
            onClick = { if (query.isNotEmpty()) onSearchPrev(query) },
            enabled = query.isNotEmpty(),
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = "Previous match",
            )
        }
        IconButton(
            onClick = { if (query.isNotEmpty()) onSearchNext(query) },
            enabled = query.isNotEmpty(),
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Next match",
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close search",
            )
        }
    }
}
