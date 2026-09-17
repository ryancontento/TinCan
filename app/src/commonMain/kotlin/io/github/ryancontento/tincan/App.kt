package io.github.ryancontento.tincan

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.llm.Role

/**
 * Lives in commonMain so v2's MainActivity can call exactly this. The desktop
 * entry point in desktopMain does nothing except open a window around it.
 */
@Composable
fun App() {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        val state = remember { ChatState(scope) }

        LaunchedEffect(Unit) { state.refreshModels() }

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                ConnectionBar(state)
                state.status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                }
                Transcript(state, Modifier.weight(1f))
                Composer(state)
            }
        }
    }
}

@Composable
private fun ConnectionBar(state: ChatState) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = { state.serverUrl = it },
            label = { Text("Server") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        var expanded by remember { mutableStateOf(false) }
        TextButton(onClick = { expanded = true }) {
            Text(state.selectedModel ?: "No model")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.availableModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName) },
                    onClick = { state.selectedModel = model.id; expanded = false },
                )
            }
        }
        TextButton(onClick = { state.refreshModels() }) { Text("Reload") }
    }
}

@Composable
private fun Transcript(state: ChatState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Autoscroll only while already pinned to the bottom, so scrolling up to
    // read does not get yanked back by every incoming chunk.
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

            // The in-flight reply is its own item reading its own state, so
            // appending recomposes only this bubble.
            state.streaming?.let { partial ->
                item(key = "streaming") {
                    Bubble(
                        UiMessage(
                            id = -1,
                            role = Role.ASSISTANT,
                            content = partial.ifEmpty { "…" },
                            modelId = state.selectedModel,
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
        Card(Modifier.widthIn(max = 720.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    // Markdown rendering lands at M4. Plain text until then,
                    // deliberately — parsing on every chunk is the jank trap.
                )
                val footer = buildList {
                    message.modelId?.takeIf { !isUser }?.let { add(it) }
                    message.stats?.tokensPerSecond?.let { add("${it.toInt()} tok/s") }
                    if (message.incomplete) add("incomplete")
                }
                if (footer.isNotEmpty()) {
                    Text(
                        footer.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(state: ChatState) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = { state.draft = it },
            label = { Text("Message") },
            modifier = Modifier.weight(1f),
        )
        if (state.isGenerating) {
            Button(onClick = { state.stop() }) { Text("Stop") }
        } else {
            Button(onClick = { state.send() }, enabled = state.selectedModel != null) { Text("Send") }
        }
    }
}
