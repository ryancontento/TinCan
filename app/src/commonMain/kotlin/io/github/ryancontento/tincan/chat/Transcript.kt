package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.data.db.AttachmentEntity
import io.github.ryancontento.tincan.data.db.MessageEntity
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.data.db.MessageStatus
import io.github.ryancontento.tincan.ui.MonoStyle
import io.github.ryancontento.tincan.ui.TinButton
import io.github.ryancontento.tincan.ui.TinField
import io.github.ryancontento.tincan.ui.TinOutlinedButton
import io.github.ryancontento.tincan.ui.TinToolbarButton

@Composable
fun Transcript(
    state: ChatUiState,
    editingMessageId: Long?,
    onStartEdit: (Long) -> Unit,
    onCancelEdit: () -> Unit,
    onSubmitEdit: (Long, String) -> Unit,
    onContinue: () -> Unit,
    onRegenerate: () -> Unit,
    onScrolledToTarget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val modelChips = remember(state.messages) { messagesShowingModel(state.messages) }

    // Follow the stream only while already at the bottom, so scrolling up to read is not yanked back.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(state.messages.size, state.streamingText) {
        // A pending jump to a search hit outranks following the stream.
        if (atBottom && state.scrollToMessageId == null) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }
    LaunchedEffect(state.scrollToMessageId, state.messages) {
        val index = state.messages.indexOfFirst { it.id == state.scrollToMessageId }
        if (index >= 0) {
            listState.scrollToItem(index)
            onScrolledToTarget()
        }
    }

    if (state.messages.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Ask it something.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // The modifier belongs on this container: weight() only counts on a direct child of the Column.
    SelectionContainer(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                // The streaming row is saved only every half second, so its live text is overlaid on the same row.
                val streaming = message.id == state.streamingMessageId
                MessageRow(
                    message = message,
                    images = state.attachments[message.id].orEmpty(),
                    liveText = if (streaming) state.streamingText.ifEmpty { "…" } else null,
                    thinking = if (streaming) state.streamingThinking else message.thinking.orEmpty(),
                    showModel = message.id in modelChips,
                    highlighted = message.id == state.scrollToMessageId,
                    editable = message.role == MessageRole.USER && !state.isGenerating,
                    isEditing = message.id == editingMessageId,
                    onStartEdit = { onStartEdit(message.id) },
                    onCancelEdit = onCancelEdit,
                    onSubmitEdit = { onSubmitEdit(message.id, it) },
                )
            }
            // Buttons in the transcript, not just in a notice: an unfinished reply is still unfinished after a restart.
            if (state.canContinue || state.canRegenerate) {
                item(key = "reply-actions") {
                    Row(Modifier.fillMaxWidth().padding(start = SPEAKER_GUTTER, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.canContinue) TinOutlinedButton(onClick = onContinue, label = "Continue this reply")
                        if (state.canRegenerate) TinOutlinedButton(onClick = onRegenerate, label = "Regenerate")
                    }
                }
            }
        }
    }
}

/** Replies whose model differs from the reply before, so a switch is visible and a one-model thread stays quiet. */
internal fun messagesShowingModel(messages: List<MessageEntity>): Set<Long> {
    var previous: String? = null
    return buildSet {
        messages.filter { it.role == MessageRole.ASSISTANT }.forEach { reply ->
            if (reply.modelId != null && reply.modelId != previous) add(reply.id)
            previous = reply.modelId
        }
    }
}

/** A document, not chat bubbles: bubbles waste most of a wide window and make long replies hard to scan. */
@Composable
private fun MessageRow(
    message: MessageEntity,
    images: List<AttachmentEntity>,
    liveText: String?,
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isUser = message.role == MessageRole.USER

    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            // Questions sit on a raised band, or a long thread is one undifferentiated column.
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
            if (!isUser) ReasoningTrace(thinking = thinking, isStreaming = liveText != null)
            if (images.isNotEmpty()) {
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    images.forEach { Thumbnail(it.bytes, "Attached image", Modifier.height(140.dp)) }
                }
            }
            when {
                // Verbatim: rendering a question as markdown would eat its asterisks and hashes.
                isUser -> if (message.content.isNotEmpty()) {
                    Text(message.content, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                }
                else -> MessageContent(text = liveText ?: message.content, isStreaming = liveText != null)
            }
            MessageFooter(
                meta = messageMeta(message, showModel),
                hovered = hovered,
                editable = editable,
                copyText = liveText ?: message.content,
                onStartEdit = onStartEdit,
            )
        }
    }
}

/** Actions appear on hover, in a row of fixed height, so rows do not jump as the pointer crosses them. */
@Composable
private fun MessageFooter(meta: String, hovered: Boolean, editable: Boolean, copyText: String, onStartEdit: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(meta, style = MaterialTheme.typography.labelMedium.merge(MonoStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (hovered) {
            if (editable) TinToolbarButton(onClick = onStartEdit, label = "Edit")
            TinToolbarButton(
                onClick = { clipboard.setText(AnnotatedString(copyText)); copied = true },
                label = if (copied) "Copied" else "Copy",
            )
        }
    }
}

internal fun messageMeta(message: MessageEntity, showModel: Boolean): String = buildList {
    if (showModel) message.modelId?.let(::add)
    tokenCount(message)?.let(::add)
    message.tokensPerSecond?.let { add("${it.toInt()} tok/s") }
    if (message.status == MessageStatus.INCOMPLETE) add("incomplete")
    if (message.status == MessageStatus.PENDING) add("queued")
}.joinToString("  ·  ")

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

/** The warning is part of the control, not a surprise afterwards. */
@Composable
private fun MessageEditor(original: String, onCancel: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember(original) { mutableStateOf(original) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TinField(value = text, onValueChange = { text = it }, singleLine = false, minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TinButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank(), label = "Resend")
            TinToolbarButton(onClick = onCancel, label = "Cancel")
            Text("Discards the replies after it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val SPEAKER_GUTTER = 44.dp
