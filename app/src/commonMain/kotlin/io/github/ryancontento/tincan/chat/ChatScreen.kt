package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.github.ryancontento.tincan.llm.Role
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var draft by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
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
                onSend = { viewModel.send(draft); draft = "" },
                onStop = viewModel::stop,
            )
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
    val color = when (notice.severity) {
        Notice.Severity.ERROR -> MaterialTheme.colorScheme.error
        Notice.Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        notice.text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
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
    LaunchedEffect(state.messages.size, state.streaming) {
        if (pinned) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id }) { Bubble(it) }

            state.streaming?.let { partial ->
                item(key = "streaming") {
                    Bubble(
                        UiMessage(
                            id = -1,
                            role = Role.ASSISTANT,
                            content = partial.ifEmpty { "…" },
                            modelId = state.settings.selectedModel,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Bubble(message: UiMessage) {
    val isUser = message.role == Role.USER
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
                // Markdown rendering lands at M4. Plain text until then,
                // deliberately — re-parsing on every chunk is the jank trap.
                Text(message.content, style = MaterialTheme.typography.bodyMedium)

                val footer = buildList {
                    if (!isUser) message.modelId?.let { add(it) }
                    message.stats?.tokensPerSecond?.let { add("${it.toInt()} tok/s") }
                    if (message.incomplete) add("incomplete")
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
    draft: String,
    onDraftChange: (String) -> Unit,
    isGenerating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text("Message") },
            modifier = Modifier.weight(1f),
        )
        if (isGenerating) {
            Button(onClick = onStop) { Text("Stop") }
        } else {
            Button(onClick = onSend, enabled = canSend && draft.isNotBlank()) { Text("Send") }
        }
    }
}
