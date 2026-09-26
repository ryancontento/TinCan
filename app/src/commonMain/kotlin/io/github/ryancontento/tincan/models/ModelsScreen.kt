package io.github.ryancontento.tincan.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ryancontento.tincan.llm.LoadedModel
import io.github.ryancontento.tincan.llm.ModelInfo
import io.github.ryancontento.tincan.ui.MonoStyle
import io.github.ryancontento.tincan.ui.TinButton
import io.github.ryancontento.tincan.ui.TinDivider
import io.github.ryancontento.tincan.ui.TinField
import io.github.ryancontento.tincan.ui.TinIcon
import io.github.ryancontento.tincan.ui.TinIconButton
import io.github.ryancontento.tincan.ui.TinOutlinedButton
import io.github.ryancontento.tincan.ui.TinSectionLabel
import io.github.ryancontento.tincan.ui.TinToolbarButton
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    viewModel: ModelsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme

    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TinIconButton(onClick = onBack, icon = TinIcon.CHEVRON_LEFT, description = "Back to chat")
                Text("Models", style = MaterialTheme.typography.titleMedium)
                Text(
                    "on ${state.serverName ?: state.serverUrl}",
                    style = MaterialTheme.typography.labelMedium.merge(MonoStyle),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TinToolbarButton(
                    onClick = { viewModel.refresh() },
                    label = if (state.refreshing) "Refreshing…" else "Refresh",
                    enabled = !state.refreshing,
                )
            }
            TinDivider()

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.widthIn(max = 720.dp).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    state.error?.let { error ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                                modifier = Modifier.weight(1f),
                            )
                            TinToolbarButton(onClick = viewModel::dismissError, label = "Dismiss")
                        }
                    }

                    PullSection(
                        pull = state.pull,
                        onPull = viewModel::pull,
                        onCancel = viewModel::cancelPull,
                        onDismiss = viewModel::dismissPull,
                    )

                    TinDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TinSectionLabel("In memory")
                        if (state.loaded.isEmpty()) {
                            Hint("Nothing loaded. A model loads when it is first asked something.")
                        }
                        state.loaded.forEach { LoadedRow(it, onUnload = { viewModel.unload(it.id) }) }
                    }

                    TinDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TinSectionLabel("Installed")
                        if (state.installed.isEmpty() && !state.refreshing) Hint("No models on this server yet.")
                        state.installed.forEach { model ->
                            InstalledRow(
                                model = model,
                                loaded = state.loadedFor(model.id) != null,
                                onUnload = { viewModel.unload(model.id) },
                                onDelete = { viewModel.requestDelete(model.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    state.confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            shape = MaterialTheme.shapes.large,
            containerColor = colors.surface,
            title = { Text("Delete $id?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "This removes it from the server. Getting it back means pulling it again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TinButton(onClick = { viewModel.confirmDelete() }, label = "Delete") },
            dismissButton = { TinToolbarButton(onClick = viewModel::cancelDelete, label = "Cancel") },
        )
    }
}

@Composable
private fun PullSection(
    pull: PullState?,
    onPull: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TinSectionLabel("Pull a model")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TinField(
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. qwen3:8b",
                textStyle = MaterialTheme.typography.bodyMedium.merge(MonoStyle),
                modifier = Modifier.widthIn(max = 280.dp),
            )
            if (pull?.running == true) {
                TinOutlinedButton(onClick = onCancel, label = "Cancel")
            } else {
                TinButton(onClick = { onPull(name) }, enabled = name.isNotBlank(), label = "Pull")
            }
        }
        Hint("Names come from ollama.com/library. Add a tag such as :8b to pick a size.")

        pull?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(it.model, style = MaterialTheme.typography.bodyMedium.merge(MonoStyle))
                it.fraction?.let { fraction ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.width(160.dp).height(4.dp),
                        color = colors.primary,
                        trackColor = colors.surfaceVariant,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    it.error ?: it.status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (it.error != null) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!it.running) TinToolbarButton(onClick = onDismiss, label = "Dismiss")
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun LoadedRow(model: LoadedModel, onUnload: () -> Unit) {
    ModelRow(
        name = model.id,
        detail = listOfNotNull(
            memoryDescription(model),
            model.contextLength?.let { "$it ctx" },
            unloadsIn(model.expiresAt),
        ).joinToString("  ·  "),
    ) {
        TinToolbarButton(onClick = onUnload, label = "Unload")
    }
}

@Composable
private fun InstalledRow(model: ModelInfo, loaded: Boolean, onUnload: () -> Unit, onDelete: () -> Unit) {
    ModelRow(
        name = model.id,
        detail = listOfNotNull(
            model.parameterSize,
            model.quantization,
            model.sizeBytes?.let(::formatBytes),
            "vision".takeIf { model.supportsImages },
        ).joinToString("  ·  "),
    ) {
        if (loaded) TinToolbarButton(onClick = onUnload, label = "Unload")
        TinToolbarButton(onClick = onDelete, label = "Delete")
    }
}

@Composable
private fun ModelRow(name: String, detail: String, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium.merge(MonoStyle))
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Part on the GPU and part in system memory runs far slower, so say which. */
internal fun memoryDescription(model: LoadedModel): String? {
    val size = model.sizeBytes ?: return null
    val vram = model.vramBytes ?: return formatBytes(size)
    return when {
        vram >= size -> "${formatBytes(size)} on GPU"
        vram <= 0 -> "${formatBytes(size)} on CPU"
        else -> "${formatBytes(size)}, ${vram * 100 / size}% on GPU"
    }
}

@OptIn(ExperimentalTime::class)
internal fun unloadsIn(expiresAt: String?, now: Instant = Clock.System.now()): String? {
    val expiry = expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val minutes = (expiry - now).inWholeMinutes
    return when {
        // keep_alive -1 shows up as an expiry centuries away.
        minutes > 60 * 24 * 365 -> "stays loaded"
        minutes >= 60 -> "unloads in ${minutes / 60} h"
        minutes >= 1 -> "unloads in $minutes min"
        else -> "unloading soon"
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "${(bytes / 100_000_000) / 10.0} GB"
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
