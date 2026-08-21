package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ProviderUsageLandingMode
import com.hermesandroid.relay.data.ProviderUsagePreferences
import com.hermesandroid.relay.data.ProviderUsagePreferencesRepository
import com.hermesandroid.relay.network.usage.ProviderUsageProvider
import com.hermesandroid.relay.network.usage.ProviderUsageCredential
import com.hermesandroid.relay.network.usage.ProviderUsageBalance
import com.hermesandroid.relay.network.usage.ProviderUsageRepository
import com.hermesandroid.relay.network.usage.ProviderUsageResponse
import com.hermesandroid.relay.network.usage.ProviderUsageWindow
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.ui.components.RelaySkeletonLine
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface UsageLoadState {
    data object Loading : UsageLoadState
    data object Unsupported : UsageLoadState
    data class Loaded(val response: ProviderUsageResponse) : UsageLoadState
    data object Error : UsageLoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageLimitsScreen(
    connectionViewModel: ConnectionViewModel,
    chatViewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val selectedProfile by connectionViewModel.selectedProfile.collectAsState()
    val currentSessionId by chatViewModel.currentSessionId.collectAsState()
    val preferencesRepository = remember(context) { ProviderUsagePreferencesRepository(context) }
    val preferences by preferencesRepository.preferences.collectAsState(
        initial = ProviderUsagePreferences(),
    )
    val repository = remember(connectionViewModel) {
        ProviderUsageRepository(
            gatewayClientProvider = connectionViewModel::activeGatewayChatClient,
            dashboardClientProvider = {
                connectionViewModel.activeDashboardUrl()?.let(
                    connectionViewModel::dashboardClientForActive,
                )
            },
            relayHttpClient = connectionViewModel.relayHttpClient,
            profileProvider = { connectionViewModel.selectedProfile.value?.name },
            sessionProvider = { chatViewModel.currentSessionId.value },
        )
    }
    var refreshKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<UsageLoadState>(UsageLoadState.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(activeConnection?.id, selectedProfile?.name, currentSessionId, refreshKey) {
        val hadContent = state is UsageLoadState.Loaded
        if (!hadContent) state = UsageLoadState.Loading else refreshing = true
        val next = repository.fetch().fold(
            onSuccess = { result ->
                result?.let(UsageLoadState::Loaded) ?: UsageLoadState.Unsupported
            },
            onFailure = { UsageLoadState.Error },
        )
        if (!hadContent || next is UsageLoadState.Loaded) state = next
        refreshing = false
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300_000)
            refreshKey++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.provider_usage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.provider_usage_back),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }, enabled = !refreshing) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.provider_usage_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = activeConnection?.label ?: stringResource(R.string.settings_no_connection),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.provider_usage_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val current = state) {
                UsageLoadState.Loading -> ProviderUsageLoading()
                UsageLoadState.Unsupported -> ProviderUsageMessage(
                    text = stringResource(R.string.provider_usage_not_available),
                )
                UsageLoadState.Error -> ProviderUsageError(onRetry = { refreshKey++ })
                is UsageLoadState.Loaded -> {
                    val providers = current.response.providers
                    if (providers.none { it.available }) {
                        ProviderUsageMessage(
                            text = stringResource(R.string.provider_usage_none_configured),
                        )
                    }
                    providers.forEach { provider ->
                        ProviderUsageCard(
                            provider = provider,
                            detailed = true,
                        )
                    }
                }
            }

            ProviderUsageDisplaySettings(
                preferences = preferences,
                providers = (state as? UsageLoadState.Loaded)?.response?.providers.orEmpty(),
                onModeChanged = { mode ->
                    scope.launch { preferencesRepository.setLandingMode(mode) }
                },
                onProviderVisibilityChanged = { providerId, visible ->
                    scope.launch {
                        preferencesRepository.setProviderVisible(providerId, visible)
                    }
                },
            )
        }
    }
}

@Composable
private fun ProviderUsageLoading() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RelaySkeletonLine(width = 112.dp, height = 18.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        RelaySkeletonLine(width = 92.dp)
                        RelaySkeletonLine(width = 62.dp)
                    }
                    RelaySkeletonLine(width = 280.dp, height = 6.dp)
                    RelaySkeletonLine(width = 98.dp, height = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun ProviderUsageMessage(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderUsageError(onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.provider_usage_error),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.provider_usage_retry))
            }
        }
    }
}

@Composable
fun ProviderUsageCard(
    provider: ProviderUsageProvider,
    detailed: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        ProviderUsageContent(
            provider = provider,
            detailed = detailed,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
fun ProviderUsageContent(
    provider: ProviderUsageProvider,
    detailed: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                provider.plan?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!provider.available) {
                Text(
                    text = providerUnavailableText(provider),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            if (provider.balances.isNotEmpty()) {
                ProviderBalanceUsage(provider, detailed)
            } else if (provider.credentials.isNotEmpty()) {
                val shownCredentials = if (detailed) {
                    provider.credentials
                } else {
                    provider.credentials.filter { it.active }.take(1)
                }
                if (shownCredentials.isEmpty()) {
                    Text(
                        text = stringResource(R.string.provider_usage_active_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    shownCredentials.forEach { credential ->
                        ProviderCredentialUsage(credential, detailed)
                    }
                }
            } else {
                val windows = if (detailed) provider.windows else provider.windows.take(1)
                windows.forEach { ProviderUsageWindowRow(it) }
            }
            if (detailed && provider.credentials.isEmpty() && provider.balances.isEmpty()) {
                provider.details.forEach { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
}

@Composable
private fun ProviderBalanceUsage(
    provider: ProviderUsageProvider,
    detailed: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    val total = provider.balances.firstOrNull { it.id == "total" }
        ?: provider.balances.first()
    val supporting = provider.balances.filterNot { it.id == total.id }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = formatBalance(total),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = total.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (detailed) {
            supporting.forEach { balance ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = balance.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatBalance(balance),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        formatRenewal(provider.renewsAt)?.let { renewal ->
            Text(
                text = stringResource(R.string.provider_usage_renews_on, renewal),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (detailed && !provider.actionUrl.isNullOrBlank()) {
            TextButton(onClick = { uriHandler.openUri(provider.actionUrl) }) {
                Text(stringResource(R.string.provider_usage_manage_credits))
            }
        }
        if (detailed) {
            provider.details.forEach { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ProviderCredentialUsage(
    credential: ProviderUsageCredential,
    detailed: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = credential.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (credential.active) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = when {
                    credential.active && credential.status == ProviderUsageCredential.STATUS_AVAILABLE ->
                        stringResource(R.string.provider_usage_active_available)
                    credential.active && credential.status == ProviderUsageCredential.STATUS_AT_LIMIT ->
                        stringResource(R.string.provider_usage_active_at_limit)
                    credential.active -> stringResource(R.string.provider_usage_active)
                    credential.status == ProviderUsageCredential.STATUS_AVAILABLE ->
                        stringResource(R.string.provider_usage_available)
                    credential.status == ProviderUsageCredential.STATUS_AT_LIMIT ->
                        stringResource(R.string.provider_usage_at_limit)
                    else -> stringResource(R.string.provider_usage_unavailable_status)
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (credential.status) {
                    ProviderUsageCredential.STATUS_AT_LIMIT -> MaterialTheme.colorScheme.error
                    ProviderUsageCredential.STATUS_AVAILABLE -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        val windows = if (detailed) credential.windows else credential.windows.take(1)
        windows.forEach { ProviderUsageWindowRow(it) }
        if (detailed) {
            credential.details.forEach { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProviderUsageWindowRow(window: ProviderUsageWindow) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(window.resetAt) {
        while (window.resetAt != null) {
            delay(60_000)
            now = Instant.now()
        }
    }
    val percent = window.usedPercent?.coerceIn(0.0, 100.0)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(window.label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = percent?.let { stringResource(R.string.provider_usage_percent, it.toInt()) }
                    ?: window.detail.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (percent != null) {
            LinearProgressIndicator(
                progress = { (percent / 100.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = when {
                    percent >= 90 -> MaterialTheme.colorScheme.error
                    percent >= 75 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        formatReset(window.resetAt, now)?.let { reset ->
            Text(
                text = stringResource(R.string.provider_usage_resets, reset),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (percent != null && !window.detail.isNullOrBlank()) {
            Text(
                text = window.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderUsageDisplaySettings(
    preferences: ProviderUsagePreferences,
    providers: List<ProviderUsageProvider>,
    onModeChanged: (ProviderUsageLandingMode) -> Unit,
    onProviderVisibilityChanged: (String, Boolean) -> Unit,
) {
    Text(
        text = stringResource(R.string.provider_usage_display_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.provider_usage_display_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val modes = ProviderUsageLandingMode.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = preferences.landingMode == mode,
                        onClick = { onModeChanged(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) {
                        Text(
                            when (mode) {
                                ProviderUsageLandingMode.Summary -> stringResource(R.string.provider_usage_mode_summary)
                                ProviderUsageLandingMode.Expanded -> stringResource(R.string.provider_usage_mode_expanded)
                                ProviderUsageLandingMode.Hidden -> stringResource(R.string.provider_usage_mode_hidden)
                            }
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.provider_usage_providers_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.provider_usage_providers_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val rows = if (providers.isEmpty()) {
                listOf(
                    "openai-codex" to "Codex",
                    "nous" to "Nous",
                    "opencode-go" to "OpenCode Go",
                )
            } else {
                providers.map { it.id to it.displayName }
            }
            rows.forEach { (id, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = id in preferences.visibleProviders,
                        onCheckedChange = { onProviderVisibilityChanged(id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun providerUnavailableText(provider: ProviderUsageProvider): String =
    if (provider.status == ProviderUsageProvider.STATUS_NOT_CONFIGURED) {
        stringResource(R.string.provider_usage_provider_not_configured)
    } else {
        stringResource(R.string.provider_usage_provider_unavailable)
    }

private fun formatReset(raw: String?, now: Instant): String? = runCatching {
    val reset = Instant.parse(raw ?: return null)
    val duration = Duration.between(now, reset)
    if (duration.isNegative || duration.isZero) return "now"
    val days = duration.toDays()
    val hours = duration.toHours() % 24
    val minutes = duration.toMinutes() % 60
    when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}.getOrNull()

private fun formatBalance(balance: ProviderUsageBalance): String = runCatching {
    NumberFormat.getCurrencyInstance().apply {
        currency = Currency.getInstance(balance.currency)
    }.format(balance.amount)
}.getOrElse { "${balance.amount} ${balance.currency}" }

private fun formatRenewal(raw: String?): String? = runCatching {
    val instant = Instant.parse(raw ?: return null)
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(instant)
}.getOrNull()
