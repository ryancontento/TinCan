package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.MessageStatus
import io.github.ryancontento.tincan.export.ExportFormat
import io.github.ryancontento.tincan.export.FileSaver
import io.github.ryancontento.tincan.llm.CONTEXT_WARNING_THRESHOLD
import io.github.ryancontento.tincan.ui.Metrics
import io.github.ryancontento.tincan.ui.MonoStyle
import io.github.ryancontento.tincan.ui.TinButton
import io.github.ryancontento.tincan.ui.TinCaret
import io.github.ryancontento.tincan.ui.TinDivider
import io.github.ryancontento.tincan.ui.TinField
import io.github.ryancontento.tincan.ui.TinOutlinedButton
import io.github.ryancontento.tincan.ui.TinToolbarButton
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
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
    // The stored width is the truth until the pointer touches the handle, which
    // is what lets the saved value arrive asynchronously without a flicker.
    var draggedWidth by remember { mutableStateOf<Dp?>(null) }
    val sidebarWidth = draggedWidth ?: state.settings.sidebarWidth.dp
    var editingMessageId by remember { mutableStateOf<Long?>(null) }
    var settingsForConversation by remember { mutableStateOf<Long?>(null) }

    val fileSaver: FileSaver = koinInject()
    val scope = rememberCoroutineScope()

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
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(Modifier.fillMaxSize()) {
            ConversationSidebar(
                width = sidebarWidth,
                onWidthChange = { draggedWidth = it },
                onWidthCommit = { viewModel.setSidebarWidth(it.value.toInt()) },
                conversations = state.conversations,
                activeId = state.activeConversationId,
                // Switching threads mid-stream would orphan the reply being
                // written, so the whole rail is inert while generating.
                enabled = !state.isGenerating,
                search = state.search,
                onSearchChange = viewModel::search,
                onOpenHit = viewModel::openSearchHit,
                onSelect = viewModel::select,
                onNew = viewModel::newConversation,
                onDelete = viewModel::deleteConversation,
                onEditConversation = { viewModel.select(it); settingsForConversation = it },
            )

            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(
                    modelLabel = state.activeModel ?: "No model",
                    models = state.availableModels.map { it.id },
                    serverUrl = state.settings.serverUrl,
                    onSelectModel = viewModel::selectModel,
                    menuOpen = modelMenuOpen,
                    onMenuOpenChange = { modelMenuOpen = it },
                    onOpenSettings = onOpenSettings,
                    onOpenConversation = { state.activeConversationId?.let { settingsForConversation = it } },
                    onExport = { format ->
                        scope.launch {
                            viewModel.buildExport(format)?.let {
                                viewModel.reportExported(fileSaver.save(it))
                            }
                        }
                    },
                    hasConversation = state.activeConversationId != null,
                    onReload = viewModel::refreshModels,
                    connection = state.connection,
                    queuedCount = state.queuedCount,
                )
                TinDivider()

                // weight, not fillMaxHeight: inside a Column the latter asks for
                // the whole parent again and pushes the composer off the bottom.
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
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

                    Transcript(
                        state = state,
                        onContinue = viewModel::continueReply,
                        onRegenerate = viewModel::regenerateLastReply,
                        editingMessageId = editingMessageId,
                        onStartEdit = { editingMessageId = it },
                        onCancelEdit = { editingMessageId = null },
                        onSubmitEdit = { id, text -> editingMessageId = null; viewModel.editAndResend(id, text) },
                        onScrolledToTarget = viewModel::scrolledToTarget,
                        modifier = Modifier.weight(1f),
                    )

                    Composer(
                        draft = draft,
                        onDraftChange = { draft = it },
                        isGenerating = state.isGenerating,
                        canSend = state.activeModel != null,
                        onSend = { viewModel.send(draft.text); draft = TextFieldValue("") },
                        onStop = viewModel::stop,
                    )
                }
            }
        }
    }

    // Resolved from the live list so the dialog follows a rename it just made.
    settingsForConversation
        ?.let { id -> state.conversations.firstOrNull { it.id == id } }
        ?.let { conversation ->
            ConversationSettingsDialog(
                conversation = conversation,
                models = state.availableModels.map { it.id },
                globalSystemPrompt = state.settings.systemPrompt,
                onSave = { title, prompt ->
                    viewModel.renameConversation(conversation.id, title)
                    viewModel.setConversationSystemPrompt(conversation.id, prompt)
                },
                onSelectModel = viewModel::selectModel,
                onDismiss = { settingsForConversation = null },
            )
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
    onOpenConversation: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    hasConversation: Boolean,
    onReload: () -> Unit,
    connection: ConnectionState,
    queuedCount: Int,
) {
    var exportMenuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(TOOLBAR_HEIGHT)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TinOutlinedButton(
                onClick = { onMenuOpenChange(true) },
                label = modelLabel,
                leading = { TinCaret(MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                if (models.isEmpty()) {
                    DropdownMenuItem(text = { Text("No models found") }, onClick = { onMenuOpenChange(false) })
                }
                models.forEach { id ->
                    DropdownMenuItem(
                        text = { Text(id, style = MaterialTheme.typography.bodyMedium.merge(MonoStyle)) },
                        onClick = { onSelectModel(id); onMenuOpenChange(false) },
                    )
                }
            }
        }

        Spacer(Modifier.width(4.dp))
        Text(
            serverUrl,
            style = MaterialTheme.typography.labelMedium.merge(MonoStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ConnectionPill(connection, queuedCount)

        if (hasConversation) {
            TinToolbarButton(onClick = onOpenConversation, label = "Conversation")
            Box {
                TinToolbarButton(onClick = { exportMenuOpen = true }, label = "Export")
                DropdownMenu(expanded = exportMenuOpen, onDismissRequest = { exportMenuOpen = false }) {
                    ExportFormat.entries.forEach { format ->
                        DropdownMenuItem(
                            text = { Text(format.label, style = MaterialTheme.typography.bodyMedium) },
                            onClick = { exportMenuOpen = false; onExport(format) },
                        )
                    }
                }
            }
        }
        TinToolbarButton(onClick = onReload, label = "Reload")
        TinToolbarButton(onClick = onOpenSettings, label = "Settings")
    }
}

/**
 * A dot plus a word. Colour alone would not survive a colour-blind reader or a
 * screenshot, and the word alone reads as noise in a crowded toolbar.
 */
@Composable
private fun ConnectionPill(connection: ConnectionState, queuedCount: Int) {
    val colors = MaterialTheme.colorScheme
    val (label, tint) = when (connection) {
        ConnectionState.ONLINE -> "Connected" to colors.primary
        ConnectionState.OFFLINE -> "Unreachable" to colors.error
        ConnectionState.CHECKING -> "Checking" to colors.onSurfaceVariant
        ConnectionState.UNKNOWN -> "" to colors.onSurfaceVariant
    }
    if (label.isEmpty() && queuedCount == 0) return

    Row(
        Modifier.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.width(6.dp).height(6.dp).background(tint, MaterialTheme.shapes.extraSmall))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        if (queuedCount > 0) {
            Text("· queued", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun NoticeBar(notice: Notice, onAction: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = when (notice.severity) {
        Notice.Severity.ERROR -> colors.error
        Notice.Severity.INFO -> colors.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(colors.surface, MaterialTheme.shapes.small)
            .border(Metrics.hairline, colors.outlineVariant, MaterialTheme.shapes.small)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(3.dp).height(14.dp).background(tint, MaterialTheme.shapes.extraSmall))
        Text(
            notice.text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        // One action, the one most likely to fix this particular failure —
        // a dropped stream offers Continue, not Retry, because starting over
        // would throw away the reply so far.
        notice.action?.let { action ->
            TinToolbarButton(
                onClick = onAction,
                accent = true,
                label = when (action) {
                    NoticeAction.RETRY -> "Retry"
                    NoticeAction.CONTINUE -> "Continue"
                    NoticeAction.OPEN_SETTINGS -> "Settings"
                },
            )
        }
        TinToolbarButton(onClick = onDismiss, label = "Dismiss")
    }
}

@Composable
private fun Transcript(
    state: ChatUiState,
    onContinue: () -> Unit,
    onRegenerate: () -> Unit,
    editingMessageId: Long?,
    onStartEdit: (Long) -> Unit,
    onCancelEdit: () -> Unit,
    onSubmitEdit: (Long, String) -> Unit,
    onScrolledToTarget: () -> Unit,
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
        // A pending jump to a search hit outranks following the stream.
        if (pinned && state.scrollToMessageId == null) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    // Opening a search hit lands on the message, not just the conversation.
    LaunchedEffect(state.scrollToMessageId, state.messages) {
        val target = state.scrollToMessageId ?: return@LaunchedEffect
        val index = state.messages.indexOfFirst { it.id == target }
        if (index >= 0) {
            listState.scrollToItem(index)
            onScrolledToTarget()
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

    // The modifier goes on the container, not the list: weight() is parent data
    // and only counts on a direct child of the Column. Applied to the list
    // inside, it was silently ignored and the transcript sized to its content.
    SelectionContainer(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                // The row being streamed into is persisted only every half
                // second, so its live text is overlaid here. Same row, same key
                // — no duplicate bubble and nothing to swap at the end.
                val isStreaming = message.id == state.streamingMessageId
                MessageRow(
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
                    highlighted = message.id == state.scrollToMessageId,
                    editable = message.role == MessageRole.USER && !state.isGenerating,
                    isEditing = message.id == editingMessageId,
                    onStartEdit = { onStartEdit(message.id) },
                    onCancelEdit = onCancelEdit,
                    onSubmitEdit = { onSubmitEdit(message.id, it) },
                )
            }

            // Durable affordances, not just transient notices: an unfinished
            // reply is still unfinished after a restart, long after the error
            // message that produced it has gone.
            if (state.canContinue || state.canRegenerate) {
                item(key = "reply-actions") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = SPEAKER_GUTTER, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (state.canContinue) {
                            TinOutlinedButton(onClick = onContinue, label = "Continue this reply")
                        }
                        if (state.canRegenerate) {
                            TinOutlinedButton(onClick = onRegenerate, label = "Regenerate")
                        }
                    }
                }
            }
        }
    }
}

/**
 * One turn, laid out as a document rather than as chat bubbles: a speaker in
 * the left gutter and the text in a single column. Bubbles alternating left and
 * right waste most of a desktop window's width and make long replies hard to
 * scan.
 */
@Composable
private fun MessageRow(
    message: MessageEntity,
    overrideContent: String?,
    thinking: String,
    showModel: Boolean,
    highlighted: Boolean,
    editable: Boolean,
    isEditing: Boolean,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSubmitEdit: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var copied by remember { mutableStateOf(false) }
    val isUser = message.role == MessageRole.USER

    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            // Questions sit on a slightly raised band. Without it a long thread
            // is one undifferentiated column of text.
            .background(
                when {
                    highlighted -> colors.tertiaryContainer
                    isUser -> colors.surface
                    else -> colors.background
                },
                MaterialTheme.shapes.small,
            )
            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            if (isUser) "You" else "AI",
            style = MaterialTheme.typography.labelSmall,
            color = if (isUser) colors.onSurfaceVariant else colors.primary,
            modifier = Modifier.width(SPEAKER_GUTTER).padding(top = 2.dp),
        )

        Column(Modifier.weight(1f).widthIn(max = 780.dp)) {
            if (isEditing) {
                MessageEditor(message.content, onCancelEdit, onSubmitEdit)
                return@Column
            }

            if (!isUser) {
                ReasoningTrace(thinking = thinking, isStreaming = overrideContent != null)
            }
            if (isUser) {
                // What the user typed is shown verbatim. Rendering it as
                // markdown would silently eat their asterisks and hashes.
                Text(message.content, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            } else {
                MessageContent(
                    text = overrideContent ?: message.content,
                    isStreaming = overrideContent != null,
                )
            }

            val meta = buildList {
                if (showModel) message.modelId?.let { add(it) }
                tokenCount(message)?.let { add(it) }
                message.tokensPerSecond?.let { add("${it.toInt()} tok/s") }
                if (message.status == MessageStatus.INCOMPLETE) add("incomplete")
                if (message.status == MessageStatus.PENDING) add("queued")
            }

            // Actions appear on hover so a scrolled transcript is text, not a
            // column of buttons. The height is fixed to the button rather than
            // to the text, or every row would jump as the pointer crossed it.
            Row(
                Modifier.fillMaxWidth().height(META_ROW_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    meta.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium.merge(MonoStyle),
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (hovered) {
                    if (editable) TinToolbarButton(onClick = onStartEdit, label = "Edit")
                    TinToolbarButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(overrideContent ?: message.content))
                            copied = true
                        },
                        label = if (copied) "Copied" else "Copy",
                    )
                }
            }
        }
    }
}

/**
 * Editing a question rewrites the conversation from that point, so the warning
 * is part of the control rather than a surprise afterwards.
 */
@Composable
private fun MessageEditor(original: String, onCancel: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember(original) { mutableStateOf(original) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TinField(
            value = text,
            onValueChange = { text = it },
            singleLine = false,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TinButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank(), label = "Resend")
            TinToolbarButton(onClick = onCancel, label = "Cancel")
            Text(
                "Discards the replies after it",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Prompt and completion counts read as one figure, so they are shown as one. */
private fun tokenCount(message: MessageEntity): String? {
    val prompt = message.promptTokens
    val completion = message.completionTokens
    return when {
        prompt != null && completion != null -> "$prompt in / $completion out"
        completion != null -> "$completion out"
        prompt != null -> "$prompt in"
        else -> null
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

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TinDivider(Modifier.padding(bottom = 8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TinField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = "Message",
                minLines = 2,
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
                TinOutlinedButton(onClick = onStop, label = "Stop")
            } else {
                TinButton(onClick = onSend, enabled = submittable, label = "Send")
            }
        }
        Text(
            "Enter sends · Ctrl+Enter for a new line",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

    val colors = MaterialTheme.colorScheme
    val fraction = plan.fractionUsed
    val model = state.availableModels.firstOrNull { it.id == state.activeModel }

    // Bound locally: budgetTokens is a nullable property from another module,
    // so the compiler will not smart-cast it inside the branches.
    val budget = plan.budgetTokens
    val used = plan.estimatedTokens.formatTokens()

    val (text, tint) = when {
        budget == null -> {
            val supported = model?.contextLength
            val hint = if (supported != null) ", model supports ${supported.formatTokens()}" else ""
            "~$used used · no limit set, so the server silently drops old turns$hint" to colors.error
        }
        plan.trimmed ->
            "~$used of ${budget.formatTokens()} · oldest ${plan.droppedCount} trimmed to fit" to colors.error
        fraction != null && fraction >= CONTEXT_WARNING_THRESHOLD ->
            "~$used of ${budget.formatTokens()} · near the limit" to colors.error
        else ->
            "~$used of ${budget.formatTokens()}" to colors.onSurfaceVariant
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.width(90.dp).height(3.dp),
                color = tint,
                trackColor = colors.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** 8192 reads better as 8.2k. */
private fun Int.formatTokens(): String = when {
    this >= 1_000_000 -> "${this / 1_000_000}M"
    this >= 1_000 -> "${(this / 100) / 10.0}k"
    else -> toString()
}

private val TOOLBAR_HEIGHT = 40.dp
private val SPEAKER_GUTTER = 44.dp
private val META_ROW_HEIGHT = 26.dp
