package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotChatScreen(
    route: BotGatewayRoute,
    bot: BotRosterEntry,
    sessionId: String,
    gatewayClient: GatewayChatClient,
    dashboardClient: DashboardApiClient,
    chatViewModel: ChatViewModel,
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val handler = remember(route.key) { ChatHandler() }
    val context = LocalContext.current
    val messages by chatViewModel.messages.collectAsState()
    val isStreaming by chatViewModel.isStreaming.collectAsState()
    val isLoading by chatViewModel.isLoadingHistory.collectAsState()
    val error by chatViewModel.error.collectAsState()
    val iconPath by connectionViewModel
        .profileIconFlow(route.connectionId, route.profileName)
        .collectAsState(initial = null)
    val listState = rememberLazyListState()
    var composer by remember(route.key, sessionId) { mutableStateOf("") }

    DisposableEffect(chatViewModel, gatewayClient, dashboardClient, route.key) {
        chatViewModel.initialize(apiClient = null, chatHandler = handler)
        chatViewModel.initializeGatewayOnly(context)
        chatViewModel.streamingEndpoint = "gateway"
        chatViewModel.sseFallbackEndpoint = "sessions"
        chatViewModel.setSelectedProfileProvider { bot.profile }
        chatViewModel.setSessionProfileNameProvider { route.profileName }
        chatViewModel.setEffectiveProfileProvider { bot.profile }
        chatViewModel.setDisplayProfileProvider { bot.profile }
        chatViewModel.setDisplayAliasProvider { bot.displayName }
        chatViewModel.setIsolatedProfileApiProvider { false }
        chatViewModel.setProfileSelectionHandler { selected ->
            selected?.name == route.profileName
        }
        chatViewModel.setProfileMessageLoaderWithMode { _, storedSessionId, mode ->
            dashboardClient.getSessionMessages(
                sessionId = storedSessionId,
                profile = route.profileName,
                mode = mode,
            )
        }
        chatViewModel.updateApiClient(null)
        chatViewModel.updateGatewayClient(gatewayClient)
        chatViewModel.setCanonicalBotChatMode(true)
        chatViewModel.setChatVisible(true)
        chatViewModel.openProfileSession(
            profileName = route.profileName,
            profile = bot.profile,
            contextKey = AgentDisplay.profileContextKey(route.connectionId, route.profileName),
            sessionId = sessionId,
        )
        onDispose {
            chatViewModel.setChatVisible(false)
            chatViewModel.setCanonicalBotChatMode(false)
            chatViewModel.updateGatewayClient(null)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        containerColor = RelayRefresh.Background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.bot_mode_back_to_bots),
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = RelayRefresh.Navy3,
                            border = BorderStroke(1.dp, RelayRefresh.LineStrong),
                        ) {
                            if (!iconPath.isNullOrBlank()) {
                                AsyncImage(
                                    model = File(iconPath.orEmpty()),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        bot.displayName.firstOrNull()?.uppercase() ?: "H",
                                        color = RelayRefresh.Relay,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                bot.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${route.connectionLabel} · @${bot.handle}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (bot.stale) {
                                    MaterialTheme.colorScheme.error
                                } else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RelayRefresh.Background,
                ),
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                modifier = Modifier.imePadding(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = composer,
                        onValueChange = { composer = it },
                        placeholder = { Text(stringResource(R.string.chat_placeholder_message)) },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 6,
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            if (isStreaming) {
                                chatViewModel.cancelStream()
                            } else {
                                val text = composer.trim()
                                if (text.isNotEmpty()) {
                                    composer = ""
                                    chatViewModel.sendMessage(text)
                                }
                            }
                        },
                        enabled = isStreaming || composer.isNotBlank(),
                    ) {
                        Icon(
                            if (isStreaming) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(
                                if (isStreaming) R.string.chat_input_stop_streaming
                                else R.string.chat_input_send_message,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                isLoading && messages.isEmpty() -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp),
                    strokeWidth = 2.dp,
                )
                messages.isEmpty() -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.bot_mode_no_messages),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        bot.profile.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp,
                        vertical = 10.dp,
                    ),
                ) {
                    itemsIndexed(messages, key = { _, message -> message.uiKey }) { index, message ->
                        val first = index == 0 || messages[index - 1].role != message.role
                        val last = index == messages.lastIndex || messages[index + 1].role != message.role
                        MessageBubble(
                            message = message,
                            modifier = Modifier.padding(top = if (first) 6.dp else 1.dp),
                            maxBubbleWidth = 344.dp,
                            isFirstInGroup = first,
                            isLastInGroup = last,
                            onAttachmentRetry = chatViewModel::manualFetchAttachment,
                            onAttachmentManualFetch = chatViewModel::manualFetchAttachment,
                            onCardAction = chatViewModel::dispatchCardAction,
                            onCardInput = chatViewModel::answerAsk,
                        )
                    }
                }
            }
            error?.takeIf(String::isNotBlank)?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                ) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
}
