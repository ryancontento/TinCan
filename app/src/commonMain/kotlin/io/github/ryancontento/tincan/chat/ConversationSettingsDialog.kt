package io.github.ryancontento.tincan.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * Everything that belongs to one conversation rather than to the app: its name,
 * the model it defaults to, and the system prompt it runs under.
 *
 * The prompt is a copy taken when the conversation was created, so editing the
 * global default never reaches back into threads already under way.
 */
@Composable
fun ConversationSettingsDialog(
    conversation: ConversationEntity,
    models: List<String>,
    globalSystemPrompt: String,
    onSave: (title: String, systemPrompt: String) -> Unit,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(conversation.id) { mutableStateOf(conversation.title) }
    var prompt by remember(conversation.id) {
        mutableStateOf(conversation.systemPrompt ?: globalSystemPrompt)
    }
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
            }
        },
        confirmButton = { TinButton(onClick = { onSave(title, prompt); onDismiss() }, label = "Save") },
        dismissButton = { TinToolbarButton(onClick = onDismiss, label = "Cancel") },
    )
}
