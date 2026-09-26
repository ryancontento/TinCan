package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
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
import io.github.ryancontento.tincan.data.db.ConversationEntity
import io.github.ryancontento.tincan.ui.MonoStyle
import io.github.ryancontento.tincan.ui.TinButton
import io.github.ryancontento.tincan.ui.TinCaret
import io.github.ryancontento.tincan.ui.TinField
import io.github.ryancontento.tincan.ui.TinFormRow
import io.github.ryancontento.tincan.ui.TinOutlinedButton
import io.github.ryancontento.tincan.ui.TinToolbarButton

/** What belongs to one conversation rather than the app: name, model, prompt, temperature and context window. */
@Composable
fun ConversationSettingsDialog(
    conversation: ConversationEntity,
    models: List<String>,
    globalSystemPrompt: String,
    globalTemperature: Float?,
    globalNumCtx: Int?,
    onSave: (title: String, systemPrompt: String, temperature: String, numCtx: String) -> Unit,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(conversation.id) { mutableStateOf(conversation.title) }
    var prompt by remember(conversation.id) {
        mutableStateOf(conversation.systemPrompt ?: globalSystemPrompt)
    }
    var temperature by remember(conversation.id) { mutableStateOf(conversation.temperature?.toString().orEmpty()) }
    var numCtx by remember(conversation.id) { mutableStateOf(conversation.numCtx?.toString().orEmpty()) }
    var modelMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Conversation", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TinFormRow(label = "Name") {
                    TinField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                TinFormRow(label = "Model") {
                    Box {
                        TinOutlinedButton(
                            onClick = { modelMenuOpen = true },
                            label = conversation.defaultModelId ?: "Use the selected model",
                            leading = { TinCaret(MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                        DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                            if (models.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No models found") },
                                    onClick = { modelMenuOpen = false },
                                )
                            }
                            models.forEach { id ->
                                DropdownMenuItem(
                                    text = {
                                        Text(id, style = MaterialTheme.typography.bodyMedium.merge(MonoStyle))
                                    },
                                    onClick = { onSelectModel(id); modelMenuOpen = false },
                                )
                            }
                        }
                    }
                }

                TinFormRow(
                    label = "System prompt",
                    hint = "Applies to this conversation only. Blank means none.",
                ) {
                    TinField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        singleLine = false,
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TinToolbarButton(onClick = { prompt = globalSystemPrompt }, label = "Use the default prompt")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TinFormRow(label = "Temperature", hint = "Blank follows Settings.") {
                        TinField(
                            value = temperature,
                            onValueChange = { temperature = it },
                            // Shows what blank means, rather than leaving it to guesswork.
                            placeholder = globalTemperature?.toString() ?: "model default",
                            textStyle = MaterialTheme.typography.bodyMedium.merge(MonoStyle),
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                    }
                    TinFormRow(label = "Context window", hint = "Blank follows Settings.") {
                        TinField(
                            value = numCtx,
                            onValueChange = { numCtx = it },
                            placeholder = globalNumCtx?.toString() ?: "server decides",
                            textStyle = MaterialTheme.typography.bodyMedium.merge(MonoStyle),
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TinButton(onClick = { onSave(title, prompt, temperature, numCtx); onDismiss() }, label = "Save")
        },
        dismissButton = { TinToolbarButton(onClick = onDismiss, label = "Cancel") },
    )
}
