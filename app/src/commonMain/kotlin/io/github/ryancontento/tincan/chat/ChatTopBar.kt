package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.data.SavedServer
import io.github.ryancontento.tincan.export.ExportFormat
import io.github.ryancontento.tincan.ui.MonoStyle
import io.github.ryancontento.tincan.ui.TinCaret
import io.github.ryancontento.tincan.ui.TinOutlinedButton
import io.github.ryancontento.tincan.ui.TinToolbarButton

@Composable
fun ChatTopBar(
    state: ChatUiState,
    modelMenuOpen: Boolean,
    onModelMenuOpenChange: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectServer: (String) -> Unit,
    onOpenConversation: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onReload: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelPicker(
            label = state.activeModel ?: "No model",
            models = state.availableModels.map { it.id },
            open = modelMenuOpen,
            onOpenChange = onModelMenuOpenChange,
            onSelect = onSelectModel,
        )
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f)) {
            ServerPicker(
                serverUrl = state.settings.serverUrl,
                serverName = state.settings.activeServerName,
                savedServers = state.settings.savedServers,
                // Switching mid-reply would leave the stream writing into a thread now pointed elsewhere.
                enabled = !state.isGenerating,
                onSelect = onSelectServer,
                onManage = onOpenSettings,
            )
        }
        ConnectionPill(state.connection, state.queuedCount)

        if (state.activeConversationId != null) {
            TinToolbarButton(onClick = onOpenConversation, label = "Conversation")
            ExportMenu(onExport)
        }
        TinToolbarButton(onClick = onReload, label = "Reload")
        TinToolbarButton(onClick = onOpenModels, label = "Models")
        TinToolbarButton(onClick = onOpenSettings, label = "Settings")
    }
}

/** Open state is hoisted so Ctrl+K can open it from anywhere in the window. */
@Composable
private fun ModelPicker(
    label: String,
    models: List<String>,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    Box {
        TinOutlinedButton(
            onClick = { onOpenChange(true) },
            label = label,
            leading = { TinCaret(MaterialTheme.colorScheme.onSurfaceVariant) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            if (models.isEmpty()) DropdownMenuItem(text = { Text("No models found") }, onClick = { onOpenChange(false) })
            models.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id, style = MaterialTheme.typography.bodyMedium.merge(MonoStyle)) },
                    onClick = { onSelect(id); onOpenChange(false) },
                )
            }
        }
    }
}

/** The current server, by name if it is saved, opening the list of saved ones. */
@Composable
private fun ServerPicker(
    serverUrl: String,
    serverName: String?,
    savedServers: List<SavedServer>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    TinToolbarButton(onClick = { open = true }, label = serverName ?: serverUrl, enabled = enabled, mono = serverName == null)
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        savedServers.forEach { server ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(server.name, style = MaterialTheme.typography.bodyMedium)
                        Text(server.url, style = MaterialTheme.typography.labelSmall.merge(MonoStyle), color = colors.onSurfaceVariant)
                    }
                },
                onClick = { open = false; onSelect(server.url) },
            )
        }
        DropdownMenuItem(
            text = {
                Text(
                    if (savedServers.isEmpty()) "Save servers to switch here…" else "Manage servers…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            },
            onClick = { open = false; onManage() },
        )
    }
}

@Composable
private fun ExportMenu(onExport: (ExportFormat) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TinToolbarButton(onClick = { open = true }, label = "Export")
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExportFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text(format.label, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { open = false; onExport(format) },
                )
            }
        }
    }
}

/** A dot and a word: colour alone fails colour-blind readers and screenshots. */
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
        Box(Modifier.size(6.dp).background(tint, MaterialTheme.shapes.extraSmall))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        if (queuedCount > 0) Text("· queued", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}
