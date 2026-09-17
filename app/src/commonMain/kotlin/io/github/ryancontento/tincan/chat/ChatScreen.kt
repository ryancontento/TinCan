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

    Surface(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            ConversationSidebar(
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
                    onOpenSettings = onOpenSettings,
                    onReload = viewModel::refreshModels,
                )

                state.notice?.let { NoticeBar(it) }

                Transcript(state, Modifier.weight(1f))

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
    onOpenSettings: () -> Unit,
    onReload: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(modelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (models.isEmpty()) {
                    DropdownMenuItem(text = { Text("No models found") }, onClick = { expanded = false })
                }
                models.forEach { id ->
                    DropdownMenuItem(
                        text = { Text(id) },
                        onClick = { onSelectModel(id); expanded = false },
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
        TextButton(onClick = onReload) { Text("Reload") }
        TextButton(onClick = onOpenSettings) { Text("Settings") }
    }
}

@Composable
private fun NoticeBar(notice: Notice) {
    Text(
        notice.text,
        style = MaterialTheme.typography.bodySmall,
        color = when (notice.severity) {
            Notice.Severity.ERROR -> MaterialTheme.colorScheme.error
            Notice.Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Transcript(state: ChatUiState, modifier: Modifier = Modifier) {
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
                    // A model chip only where it changes, so a single-model
                    // thread stays quiet and a switch is obvious.
                    showModel = message.modelId != null &&
                        message.modelId != state.messages
                            .takeWhile { it.id != message.id }
                            .lastOrNull { it.role == MessageRole.ASSISTANT }
                            ?.modelId,
                )
            }
        }
    }
}

@Composable
private fun Bubble(
    message: MessageEntity,
    overrideContent: String?,
    showModel: Boolean,
) {
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
                if (footer.isNotEmpty()) {
                    Text(
                        footer.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
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
