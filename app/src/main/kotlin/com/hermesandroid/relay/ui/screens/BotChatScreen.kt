package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.BusyMessageAction
import com.hermesandroid.relay.data.canCorrectBusyMessage
import com.hermesandroid.relay.ui.components.ChatComposerLayers
import com.hermesandroid.relay.ui.components.ChatMessageQueue
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.InputChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ChatComposerDraft
import com.hermesandroid.relay.data.ChatComposerDraftKey
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.SessionMessageLoadMode
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.ui.UiMessageBus
import java.io.IOException
import com.hermesandroid.relay.ui.components.ChatMediaViewerHost
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.flow.Flow

internal typealias BotChatHistoryLoader = suspend (
    profileName: String,
    sessionId: String,
    mode: SessionMessageLoadMode,
) -> Result<List<MessageItem>>
internal typealias BotChatProfileIconFlow = (connectionId: String, profileName: String) -> Flow<String?>

/** Reading position belongs to the conversation, including while Bots is visible. */
class BotChatScrollState : ViewModel() {
    val listState = LazyListState()
    var followingLatest by mutableStateOf(true)
    var positioned by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotChatScreen(
    route: BotGatewayRoute,
    bot: BotRosterEntry,
    sessionId: String,
    gatewayClient: GatewayChatClient,
    dashboardClient: DashboardApiClient,
    chatViewModel: ChatViewModel,
    scrollState: BotChatScrollState,
    connectionViewModel: ConnectionViewModel,
    onNewChat: (() -> Unit)? = null,
    onVoiceSessionChanged: (VoiceViewModel, Boolean) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val busyMessageAction by connectionViewModel.busyMessageAction.collectAsState()
    BotChatVoiceHost(route, sessionId, dashboardClient, chatViewModel, onVoiceSessionChanged) { onVoice ->
        ChatMediaViewerHost(route.key, sessionId) {
            BotChatScreen(
                route = route,
                bot = bot,
                sessionId = sessionId,
                gatewayClient = gatewayClient,
                dashboardClient = dashboardClient,
                chatViewModel = chatViewModel,
                onBack = onBack,
                handlerFactory = { chatViewModel.boundHandler ?: ChatHandler() },
                scrollState = scrollState,
                historyLoader = { profileName, storedSessionId, mode ->
                    dashboardClient.getSessionMessages(
                        sessionId = storedSessionId,
                        profile = profileName,
                        mode = mode,
                    )
                },
                profileIconFlow = connectionViewModel::profileIconFlow,
                busyMessageAction = busyMessageAction,
                maxAttachmentMbFlow = connectionViewModel.maxAttachmentMb,
                onVoice = onVoice,
                onNewChat = onNewChat,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotChatScreen(
    route: BotGatewayRoute,
    bot: BotRosterEntry,
    sessionId: String,
    gatewayClient: GatewayChatClient,
    dashboardClient: DashboardApiClient,
    chatViewModel: ChatViewModel,
    onBack: () -> Unit,
    scrollState: BotChatScrollState = remember { BotChatScrollState() },
    handlerFactory: () -> ChatHandler,
    historyLoader: BotChatHistoryLoader,
    profileIconFlow: BotChatProfileIconFlow,
    busyMessageAction: BusyMessageAction = BusyMessageAction.CorrectNow,
    maxAttachmentMbFlow: StateFlow<Int> = MutableStateFlow(DEFAULT_BOT_ATTACHMENT_MB),
    onVoice: (() -> Unit)? = null,
    onNewChat: (() -> Unit)? = null,
) {
    val handler = remember(route.key) { handlerFactory() }
    val context = LocalContext.current
    // Observe the handler directly: initial history can arrive before Compose
    // sees the ViewModel's binding, while reentry reuses its existing handler.
    val messages by handler.messages.collectAsState()
    val isStreaming by handler.isStreaming.collectAsState()
    val isLoading by chatViewModel.isLoadingHistory.collectAsState()
    val error by handler.error.collectAsState()
    val boundSessionId by handler.currentSessionId.collectAsState()
    val iconPath by profileIconFlow(route.connectionId, route.profileName)
        .collectAsState(initial = null)
    val listState = scrollState.listState
    var composerInput by remember(route.key, sessionId) { mutableStateOf(TextFieldValue()) }
    val composer = composerInput.text
    var editingQueuedMessage by remember(route.key, sessionId) { mutableStateOf(false) }
    var busyActionOverride by remember(route.key, sessionId, busyMessageAction) {
        mutableStateOf<BusyMessageAction?>(null)
    }
    val steerable by chatViewModel.steerableTurn.collectAsState()
    val attachments by chatViewModel.pendingAttachments.collectAsState()
    val pendingAsk by chatViewModel.pendingAsk.collectAsState()
    val turnStatus by handler.turnStatus.collectAsState()
    val queue by chatViewModel.queuedMessages.collectAsState()
    val queuePaused by chatViewModel.queuePaused.collectAsState()
    val correctionAvailable = canCorrectBusyMessage(
        steerable, attachments.isNotEmpty(), pendingAsk != null, turnStatus, composer,
    )
    val effectiveBusyAction = if (correctionAvailable && !editingQueuedMessage) busyActionOverride ?: busyMessageAction
        else BusyMessageAction.QueueNext

    DisposableEffect(chatViewModel, gatewayClient, dashboardClient, route.key) {
        if (chatViewModel.boundHandler !== handler) {
            chatViewModel.initialize(apiClient = null, chatHandler = handler)
        }
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
            historyLoader(route.profileName, storedSessionId, mode)
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

    var userScrolling by remember(listState) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                userScrolling = true
                scrollState.followingLatest = false
            } else if (interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) {
                if (!listState.isScrollInProgress) {
                    scrollState.followingLatest = !listState.canScrollForward
                    userScrolling = false
                }
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && userScrolling) {
                scrollState.followingLatest = !listState.canScrollForward
                userScrolling = false
            }
        }
    }
    // Rows grow after measurement (markdown, images); keep following without waiting for a new message.
    LaunchedEffect(listState, scrollState.followingLatest) {
        if (!scrollState.followingLatest) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.sumOf { it.size } to listState.canScrollForward }
            .collect { (_, canScrollForward) ->
                if (canScrollForward && scrollState.positioned && !listState.isScrollInProgress) {
                    listState.scrollToConversationBottom(animated = false, slopPx = 0)
                }
            }
    }
    LaunchedEffect(messages, scrollState.followingLatest) {
        if (messages.isNotEmpty() && scrollState.followingLatest) {
            listState.scrollToConversationBottom(animated = scrollState.positioned, slopPx = 0)
            scrollState.positioned = true
        }
    }

    val scope = rememberCoroutineScope()
    val draftKey = remember(route.key, sessionId) {
        ChatComposerDraftKey(route.connectionId, route.profileName, sessionId)
    }
    var draftReady by remember(draftKey) { mutableStateOf(false) }
    val draftError = stringResource(R.string.error_classify_generic)
    LaunchedEffect(draftKey) {
        try {
            val draft = chatViewModel.composerDraftStore.snapshot(draftKey)
            composerInput = TextFieldValue(draft.text, TextRange(draft.selectionStart, draft.selectionEnd))
            chatViewModel.replacePendingAttachments(draft.attachments)
            draftReady = true
        } catch (_: IOException) {
            UiMessageBus.error(draftError)
        }
    }
    suspend fun saveDraft(): Boolean = try {
        chatViewModel.composerDraftStore.save(draftKey, ChatComposerDraft(
            text = composer, selectionStart = composerInput.selection.start,
            selectionEnd = composerInput.selection.end, attachments = attachments,
        ))
        true
    } catch (_: IOException) {
        UiMessageBus.error(draftError)
        false
    }
    LaunchedEffect(draftKey, draftReady, composer, attachments) {
        if (draftReady) {
            kotlinx.coroutines.delay(200)
            saveDraft()
        }
    }
    val leaveChat: (() -> Unit) -> Unit = { navigate ->
        if (!draftReady) navigate()
        else scope.launch { if (saveDraft()) navigate() }
    }
    BackHandler { leaveChat(onBack) }
    val maxAttachmentMb by maxAttachmentMbFlow.collectAsState()
    var showAttachSheet by remember { mutableStateOf(false) }
    val ingest: (List<Uri>) -> Unit = { uris ->
        scope.launch {
            uris.forEach { uri ->
                ingestAttachmentFromUri(context, uri, maxAttachmentMb) { chatViewModel.addAttachment(it) }
            }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), ingest)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ingest)
    val canSend = composer.isNotBlank() || attachments.isNotEmpty()
    val attachmentPlaceholder = stringResource(R.string.chat_attachment_placeholder)
    val stopMode = isStreaming && !canSend

    Scaffold(
        containerColor = RelayRefresh.Background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BotChatCircleButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.bot_mode_back_to_bots),
                    onClick = { leaveChat(onBack) },
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Surface(
                        onClick = { onNewChat?.let(leaveChat) },
                        enabled = onNewChat != null,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BotAvatar(iconPath, bot.displayName, 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f, fill = false)) {
                                Text(
                                    bot.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (bot.stale) Text(
                                    stringResource(R.string.bot_mode_offline),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                if (onNewChat != null) BotChatCircleButton(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.bot_mode_new_chat),
                    onClick = { leaveChat(onNewChat) },
                ) else Spacer(Modifier.size(48.dp))
            }
        },
        bottomBar = {
            ChatComposerLayers(
                action = effectiveBusyAction.takeIf { isStreaming },
                onActionChange = {
                    editingQueuedMessage = false
                    busyActionOverride = it
                },
                correctionAvailable = correctionAvailable,
                onStop = if (composer.isNotBlank()) chatViewModel::cancelStream else null,
                modifier = Modifier.imePadding().navigationBarsPadding(),
            ) {
            Column(Modifier.background(RelayRefresh.Background)) {
                ChatMessageQueue(
                    messages = queue,
                    paused = queuePaused,
                    onResume = chatViewModel::resumeQueue,
                    onClear = chatViewModel::clearQueue,
                    onRemove = chatViewModel::removeQueuedAt,
                    canEdit = composer.isBlank() && attachments.isEmpty(),
                    onEdit = { index ->
                        chatViewModel.takeQueuedForEdit(index)?.let {
                            composerInput = TextFieldValue(it, TextRange(it.length))
                            editingQueuedMessage = true
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                if (attachments.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(attachments) { index, attachment ->
                            InputChip(
                                selected = false,
                                onClick = { chatViewModel.removeAttachment(index) },
                                label = {
                                    Text(
                                        attachment.fileName ?: attachment.contentType,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 160.dp),
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.cd_remove),
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    BotChatCircleButton(
                        icon = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.chat_input_add_attachment),
                        onClick = { showAttachSheet = true },
                    )
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 18.dp, top = 12.dp, bottom = 12.dp),
                            ) {
                                if (composer.isEmpty()) {
                                    Text(
                                        stringResource(R.string.bot_chat_message_placeholder, bot.displayName),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                BasicTextField(
                                    value = composerInput,
                                    enabled = draftReady,
                                    onValueChange = { composerInput = it },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    maxLines = 6,
                                    modifier = Modifier.fillMaxWidth().testTag(BOT_CHAT_COMPOSER_TAG),
                                )
                            }
                            if (!canSend && !isStreaming && onVoice != null) {
                                IconButton(
                                    onClick = onVoice,
                                    enabled = botVoiceReady(sessionId, boundSessionId, isLoading, error),
                                    modifier = Modifier.testTag(BOT_CHAT_VOICE_TAG),
                                ) {
                                    Icon(Icons.Filled.Mic, stringResource(R.string.bot_chat_voice_start))
                                }
                            }
                            if (canSend || isStreaming) {
                                IconButton(
                                    onClick = {
                                        if (stopMode) {
                                            chatViewModel.cancelStream()
                                        } else {
                                            // Matches Chat: an attachment-only send carries a placeholder body.
                                            val text = composer.trim().ifBlank { attachmentPlaceholder }
                                            composerInput = TextFieldValue()
                                            scrollState.followingLatest = true
                                            chatViewModel.sendMessage(text, effectiveBusyAction)
                                            editingQueuedMessage = false
                                            busyActionOverride = null
                                        }
                                    },
                                    modifier = Modifier.padding(end = 4.dp),
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                if (stopMode) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                                                contentDescription = stringResource(
                                                    when {
                                                        stopMode -> R.string.chat_input_stop_streaming
                                                        isStreaming && effectiveBusyAction == BusyMessageAction.CorrectNow -> R.string.chat_correct_now
                                                        isStreaming -> R.string.chat_queue_next
                                                        else -> R.string.chat_input_send_message
                                                    },
                                                ),
                                                tint = MaterialTheme.colorScheme.surface,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                    modifier = Modifier.fillMaxSize().testTag("bot-chat-messages"),
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
                    item(key = "bot-chat-bottom") { Spacer(Modifier.height(1.dp)) }
                }
            }
            if (!scrollState.followingLatest && messages.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { scrollState.followingLatest = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Icon(Icons.Filled.ArrowDownward, stringResource(R.string.cd_scroll_to_bottom))
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

    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }) {
            BotChatSheetItem(Icons.Filled.PhotoLibrary, stringResource(R.string.chat_input_photos)) {
                showAttachSheet = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            BotChatSheetItem(Icons.Filled.InsertDriveFile, stringResource(R.string.chat_input_files)) {
                showAttachSheet = false
                filePicker.launch(arrayOf("*/*"))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

internal const val BOT_CHAT_VOICE_TAG = "botChatVoice"
internal const val BOT_CHAT_COMPOSER_TAG = "botChatComposer"
private const val DEFAULT_BOT_ATTACHMENT_MB = 10

@Composable
private fun BotChatCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}


@Composable
private fun BotChatSheetItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
        }
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
