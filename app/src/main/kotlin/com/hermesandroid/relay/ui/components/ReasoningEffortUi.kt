package com.hermesandroid.relay.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hermesandroid.relay.R
import com.hermesandroid.relay.network.upstream.ReasoningEfforts

/** One localized label source shared by the composer and Agent Passport. */
@Composable
fun reasoningEffortLabel(value: String?): String = when (ReasoningEfforts.normalize(value)) {
    "none" -> stringResource(R.string.chat_reasoning_none)
    "minimal" -> stringResource(R.string.chat_reasoning_minimal)
    "low" -> stringResource(R.string.chat_reasoning_low)
    "medium" -> stringResource(R.string.chat_reasoning_medium)
    "high" -> stringResource(R.string.chat_reasoning_high)
    "xhigh" -> stringResource(R.string.chat_reasoning_xhigh)
    "max" -> stringResource(R.string.chat_reasoning_max)
    "ultra" -> stringResource(R.string.chat_reasoning_ultra)
    else -> error("ReasoningEfforts.normalize returned a non-canonical value")
}
