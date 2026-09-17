package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.llm.CONTEXT_WARNING_THRESHOLD
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.MessageStatus
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // TextFieldValue rather than String because Ctrl+Enter has to insert a
    // newline at the caret, which means knowing where the caret is.
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    // Hoisted so Ctrl+K can open it from outside the top bar.
    var modelMenuOpen by remember { mutableStateOf(false) }
    var sidebarWidth by remember { mutableStateOf(DEFAULT_SIDEBAR_WIDTH) }

    Surface(
        Modifier
            .fillMaxSize()
            // Window-level shortcuts. Preview so they win before a focused
            // text field swallows the key.
            .onPreviewKeyEvent { event ->
                val key = when (event.key) {
                    Key.Escape -> ShortcutKey.ESCAPE
                    Key.N -> ShortcutKey.N
                    Key.K -> ShortcutKey.K
                    else -> null
                }

                when (
                    key?.let {
                        appShortcutFor(
                            key = it,
                            isKeyDown = event.type == KeyEventType.KeyDown,
                            isCtrlPressed = event.isCtrlPressed,
                            isMetaPressed = event.isMetaPressed,
                            isShiftPressed = event.isShiftPressed,
                            isAltPressed = event.isAltPressed,
                        )
                    }
                ) {
                    AppShortcut.NEW_CONVERSATION -> { viewModel.newConversation(); draft = TextFieldValue(""); true }
                    AppShortcut.FOCUS_MODEL_PICKER -> { modelMenuOpen = true; true }
                    // Only claimed while generating, so Escape stays available
                    // for dismissing menus the rest of the time.
                    AppShortcut.STOP_GENERATION ->
                        if (state.isGenerating) { viewModel.stop(); true } else false
                    null -> false
                }
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            ConversationSidebar(
                width = sidebarWidth,
                onWidthChange = { sidebarWidth = it },
                conversations = state.conversations,
                activeId = state.activeConversationId,
                // Switching threads mid-stream would orphan the reply being
                // written, so the whole rail is inert while generating.
                enabled = !state.isGenerating,
                onSelect = viewModel::select,
                onNew = viewModel::newConversation,
                onDelete = viewModel::deleteConversation,
            )
            VerticalDivider()

            Column(Modifier.fillMaxHeight().padding(16.dp)) {
                TopBar(
                    modelLabel = state.settings.selectedModel ?: "No model",
                    models = state.availableModels.map { it.id },
                    serverUrl = state.settings.serverUrl,
                    onSelectModel = viewModel::selectModel,
                    menuOpen = modelMenuOpen,
                    onMenuOpenChange = { modelMenuOpen = it },
                    onOpenSettings = onOpenSettings,
                    onReload = viewModel::refreshModels,
                    connection = state.connection,
                    queuedCount = state.queuedCount,
                )

                ContextMeter(state)

                state.notice?.let { notice ->
                    NoticeBar(
                        notice = notice,
                        onAction = {
                            when (notice.action) {
                                NoticeAction.RETRY -> viewModel.checkConnection()
                                NoticeAction.CONTINUE -> viewModel.continueReply()
                                NoticeAction.OPEN_SETTINGS -> onOpenSettings()
                                null -> Unit
                            }
                        },
                        onDismiss = viewModel::dismissNotice,
                    )
                }

                Transcript(state, viewModel::continueReply, Modifier.weight(1f))

                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    isGenerating = state.isGenerating,
                    canSend = state.settings.selectedModel != null,
                    onSend = { viewModel.send(draft.text); draft = TextFieldValue("") },
                    onStop = viewModel::stop,
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    modelLabel: String,
    models: List<String>,
    serverUrl: String,
    onSelectModel: (String) -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onReload: () -> Unit,
    connection: ConnectionState,
    queuedCount: Int,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            OutlinedButton(onClick = { onMenuOpenChange(true) }) {
                Text(modelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                if (models.isEmpty()) {
                    DropdownMenuItem(text = { Text("No models found") }, onClick = { onMenuOpenChange(false) })
                }
                models.forEach { id ->
                    DropdownMenuItem(
                        text = { Text(id) },
                        onClick = { onSelectModel(id); onMenuOpenChange(false) },
                    )
                }
            }
        }
        Text(
            serverUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ConnectionPill(connection, queuedCount)
        TextButton(onClick = onReload) { Text("Reload") }
        TextButton(onClick = onOpenSettings) { Text("Settings") }
    }
}

@Composable
private fun ConnectionPill(connection: ConnectionState, queuedCount: Int) {
    // Colour alone would not survive a colour-blind reader or a screenshot, so
    // the state is spelled out as well.
    val (label, tint) = when (connection) {
        ConnectionState.ONLINE -> "Connected" to MaterialTheme.colorScheme.primary
        ConnectionState.OFFLINE -> "Unreachable" to MaterialTheme.colorScheme.error
        ConnectionState.CHECKING -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.UNKNOWN -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (label.isEmpty() && queuedCount == 0) return

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        if (queuedCount > 0) {
            Text(
                "·  queued",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeBar(notice: Notice, onAction: () -> Unit, onDismiss: () -> Unit) {
    val tint = when (notice.severity) {
        Notice.Severity.ERROR -> MaterialTheme.colorScheme.error
        Notice.Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            notice.text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        // One action, the one most likely to fix this particular failure —
        // a dropped stream offers Continue, not Retry, because starting over
        // would throw away the reply so far.
        notice.action?.let { action ->
            TextButton(onClick = onAction) {
                Text(
                    when (action) {
                        NoticeAction.RETRY -> "Retry"
                        NoticeAction.CONTINUE -> "Continue"
                        NoticeAction.OPEN_SETTINGS -> "Settings"
                    },
                )
            }
        }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun Transcript(
    state: ChatUiState,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Autoscroll only while already pinned to the bottom, so scrolling up to
    // read is not yanked back by every incoming chunk.
    val pinned by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(state.messages.size, state.streamingText) {
        if (pinned) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    if (state.messages.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Ask it something.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                // The row being streamed into is persisted only every half
                // second, so its live text is overlaid here. Same row, same key
                // — no duplicate bubble and nothing to swap at the end.
                val isStreaming = message.id == state.streamingMessageId
                Bubble(
                    message = message,
                    overrideContent = if (isStreaming) state.streamingText.ifEmpty { "…" } else null,
                    thinking = if (isStreaming) state.streamingThinking else message.thinking.orEmpty(),
                    // A model chip only where it changes, so a single-model
                    // thread stays quiet and a switch is obvious.
                    showModel = message.modelId != null &&
                        message.modelId != state.messages
                            .takeWhile { it.id != message.id }
                            .lastOrNull { it.role == MessageRole.ASSISTANT }
                            ?.modelId,
                )
            }

            // A durable affordance, not just the transient notice: an
            // unfinished reply is still unfinished after a restart, long after
            // the error message that produced it has gone.
            if (state.canContinue) {
                item(key = "continue") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        OutlinedButton(onClick = onContinue) { Text("Continue this reply") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(
    message: MessageEntity,
    overrideContent: String?,
    thinking: String,
    showModel: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val isUser = message.role == MessageRole.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 720.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (!isUser) {
                    ReasoningTrace(thinking = thinking, isStreaming = overrideContent != null)
                }
                if (isUser) {
                    // What the user typed is shown verbatim. Rendering it as
                    // markdown would silently eat their asterisks and hashes.
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                } else {
                    MessageContent(
                        text = overrideContent ?: message.content,
                        isStreaming = overrideContent != null,
                    )
                }

                val footer = buildList {
                    if (showModel) message.modelId?.let { add(it) }
                    message.tokensPerSecond?.let { add("${it.toInt()} tok/s") }
                    if (message.status == MessageStatus.INCOMPLETE) add("incomplete")
                    if (message.status == MessageStatus.PENDING) add("queued")
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        footer.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Per-message copy, not just per code block: the prose is
                    // often the part worth keeping.
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(overrideContent ?: message.content))
                        copied = true
                    }) {
                        Text(
                            if (copied) "Copied" else "Copy",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    isGenerating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val submittable = canSend && !isGenerating && draft.text.isNotBlank()

    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text("Message") },
            supportingText = { Text("Enter sends · Ctrl+Enter or Shift+Enter for a new line") },
            modifier = Modifier
                .weight(1f)
                // Preview, not onKeyEvent: the text field consumes Enter to
                // insert its own newline, so the press has to be intercepted on
                // the way down or Send never sees it.
                .onPreviewKeyEvent { event ->
                    when (
                        composerAction(
                            isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter,
                            isKeyDown = event.type == KeyEventType.KeyDown,
                            isCtrlPressed = event.isCtrlPressed,
                            isShiftPressed = event.isShiftPressed,
                            isMetaPressed = event.isMetaPressed,
                            isAltPressed = event.isAltPressed,
                        )
                    ) {
                        ComposerAction.SEND -> {
                            // Consumed either way. Letting an unsendable Enter
                            // through would drop a stray newline into a message
                            // the user thought they had just sent.
                            if (submittable) onSend()
                            true
                        }

                        ComposerAction.NEWLINE -> {
                            onDraftChange(draft.withNewlineAtCaret())
                            true
                        }

                        ComposerAction.IGNORE -> false
                    }
                },
        )
        if (isGenerating) {
            Button(onClick = onStop) { Text("Stop") }
        } else {
            Button(onClick = onSend, enabled = submittable) { Text("Send") }
        }
    }
}

/** Replaces the selection with a line break and leaves the caret after it. */
private fun TextFieldValue.withNewlineAtCaret(): TextFieldValue {
    val start = selection.min
    val end = selection.max
    return TextFieldValue(
        text = text.substring(0, start) + "\n" + text.substring(end),
        selection = TextRange(start + 1),
    )
}

/**
 * How much of the context window the next request will use.
 *
 * Shown because Ollama truncates at num_ctx without telling the client. The
 * "no limit set" case is called out explicitly rather than drawn as an empty
 * bar: an unset num_ctx is the state most likely to lose history, and a
 * reassuring-looking gauge would be a lie.
 */
@Composable
private fun ContextMeter(state: ChatUiState) {
    val plan = state.context ?: return
    if (state.messages.isEmpty()) return

    val fraction = plan.fractionUsed
    val model = state.availableModels.firstOrNull { it.id == state.settings.selectedModel }

    // Bound locally: budgetTokens is a nullable property from another module,
    // so the compiler will not smart-cast it inside the branches.
    val budget = plan.budgetTokens
    val used = plan.estimatedTokens.formatTokens()

    val (text, tint) = when {
        budget == null -> {
            val supported = model?.contextLength
            val hint = if (supported != null) " This model supports ${supported.formatTokens()}." else ""
            "~$used used · no context limit set, so the server decides and will " +
                "drop old turns silently.$hint" to MaterialTheme.colorScheme.error
        }
        plan.trimmed ->
            "~$used of ${budget.formatTokens()} · oldest ${plan.droppedCount} message(s) trimmed to fit" to
                MaterialTheme.colorScheme.error
        fraction != null && fraction >= CONTEXT_WARNING_THRESHOLD ->
            "~$used of ${budget.formatTokens()} · approaching the limit" to MaterialTheme.colorScheme.error
        else ->
            "~$used of ${budget.formatTokens()}" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = tint,
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** 8192 reads better than 8192 when it is 8.2k. */
private fun Int.formatTokens(): String = when {
    this >= 1_000_000 -> "${this / 1_000_000}M"
    this >= 1_000 -> "${(this / 100) / 10.0}k"
    else -> toString()
}

private val DEFAULT_SIDEBAR_WIDTH = 260.dp
