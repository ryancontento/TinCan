package io.github.ryancontento.tincan.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import io.github.ryancontento.tincan.data.SavedServer
import io.github.ryancontento.tincan.data.SendKey
import io.github.ryancontento.tincan.data.ThemePreference
import io.github.ryancontento.tincan.ui.MonoStyle
import io.github.ryancontento.tincan.ui.TinDivider
import io.github.ryancontento.tincan.ui.TinField
import io.github.ryancontento.tincan.ui.TinFormRow
import io.github.ryancontento.tincan.ui.TinIcon
import io.github.ryancontento.tincan.ui.TinIconButton
import io.github.ryancontento.tincan.ui.TinOutlinedButton
import io.github.ryancontento.tincan.ui.TinSectionLabel
import io.github.ryancontento.tincan.ui.TinSegmented
import io.github.ryancontento.tincan.ui.TinToolbarButton
import io.github.ryancontento.tincan.ui.systemTrayAvailable
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val settings = state.settings
    val trayAvailable = remember { systemTrayAvailable() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TinIconButton(onClick = onBack, icon = TinIcon.CHEVRON_LEFT, description = "Back to chat")
                Text("Settings", style = MaterialTheme.typography.titleMedium)
            }
            TinDivider()

            // The cap goes on the inner column: applied after fillMaxSize it is
            // simply overruled, and a settings form spanning a wide window is
            // unreadable.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .widthIn(max = 620.dp)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Section("Appearance") {
                        TinFormRow(
                            label = "Theme",
                            hint = "System follows the desktop's own setting, which is read once " +
                                "at launch.",
                        ) {
                            TinSegmented(
                                options = ThemePreference.entries,
                                selected = settings.theme,
                                onSelect = viewModel::setTheme,
                                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                            )
                        }
                        TinFormRow(
                            label = "Send with",
                            hint = "The other one adds a new line. Shift+Enter always adds a new line.",
                        ) {
                            TinSegmented(
                                options = SendKey.entries,
                                selected = settings.sendKey,
                                onSelect = viewModel::setSendKey,
                                label = {
                                    when (it) {
                                        SendKey.ENTER -> "Enter"
                                        SendKey.CTRL_ENTER -> "Ctrl+Enter"
                                    }
                                },
                            )
                        }
                        if (trayAvailable) {
                            TinFormRow(
                                label = "Closing the window",
                                hint = "Hidden, TinCan keeps running, so queued messages still send. " +
                                    "Quit from the tray icon's menu.",
                            ) {
                                TinSegmented(
                                    options = listOf(false, true),
                                    selected = settings.closeToTray,
                                    onSelect = viewModel::setCloseToTray,
                                    label = { if (it) "Hide to tray" else "Quit" },
                                )
                            }
                        }
                    }

                    TinDivider()

                    Section("Server") {
                        TinFormRow(
                            label = "Ollama address",
                            hint = "A MagicDNS name travels better than a raw IP — it survives the " +
                                "network changing under you.",
                        ) {
                            TinField(
                                value = settings.serverUrl,
                                onValueChange = viewModel::setServerUrl,
                                textStyle = MaterialTheme.typography.bodyMedium.merge(MonoStyle),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TinOutlinedButton(onClick = viewModel::testConnection, label = "Test connection")
                            ProbeLabel(state.probe)
                        }
                        SavedServers(
                            servers = settings.savedServers,
                            currentName = settings.activeServerName,
                            onSaveCurrent = viewModel::saveCurrentServer,
                            onUse = viewModel::setServerUrl,
                            onRemove = viewModel::removeServer,
                        )
                    }

                    TinDivider()

                    Section("Generation") {
                        TinFormRow(
                            label = "Default system prompt",
                            hint = "Copied into each new conversation. Changing it leaves existing " +
                                "conversations alone — edit those from the Conversation button.",
                        ) {
                            TinField(
                                value = settings.systemPrompt,
                                onValueChange = viewModel::setSystemPrompt,
                                singleLine = false,
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TinFormRow(label = "Temperature", hint = "Blank uses the model's own default.") {
                            TinField(
                                value = settings.temperature?.toString().orEmpty(),
                                onValueChange = viewModel::setTemperature,
                                textStyle = MaterialTheme.typography.bodyMedium.merge(MonoStyle),
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                        }
                        TinFormRow(
                            label = "Context window (num_ctx)",
                            hint = "Blank lets the server decide — and Ollama silently drops the oldest " +
                                "turns once a conversation passes it, without telling the client.",
                        ) {
                            TinField(
                                value = settings.numCtx?.toString().orEmpty(),
                                onValueChange = viewModel::setNumCtx,
                                textStyle = MaterialTheme.typography.bodyMedium.merge(MonoStyle),
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                        }
                    }

                    TinDivider()

                    Section("Timing") {
                        TinFormRow(
                            label = "Keep model loaded (keep_alive)",
                            hint = "How long the server holds the model in memory after a reply. " +
                                "Overrides its own setting.",
                        ) {
                            TinField(
                                value = settings.keepAlive,
                                onValueChange = viewModel::setKeepAlive,
                                textStyle = MaterialTheme.typography.bodyMedium.merge(MonoStyle),
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                        }
                        TinFormRow(
                            label = "\"Loading model\" threshold (ms)",
                            hint = "How long to wait for a first token before saying the model is loading. " +
                                "Metal on a Mac wants ~2500; CPU inference on a large local model needs far more.",
                        ) {
                            TinField(
                                value = settings.modelLoadingThresholdMillis.toString(),
                                onValueChange = viewModel::setLoadingThreshold,
                                textStyle = MaterialTheme.typography.bodyMedium.merge(MonoStyle),
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TinSectionLabel(title)
        content()
    }
}

/** Named servers the top bar can switch between. Saving the current address again renames it. */
@Composable
private fun SavedServers(
    servers: List<SavedServer>,
    currentName: String?,
    onSaveCurrent: (String) -> Unit,
    onUse: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName.orEmpty()) }
    val colors = MaterialTheme.colorScheme

    TinFormRow(
        label = "Saved servers",
        hint = "Switch between these from the address in the top bar.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            servers.forEach { server ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(server.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        server.url,
                        style = MaterialTheme.typography.labelMedium.merge(MonoStyle),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TinToolbarButton(onClick = { onUse(server.url) }, label = "Use")
                    TinToolbarButton(onClick = { onRemove(server.url) }, label = "Remove")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TinField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Name, e.g. MacBook",
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                TinOutlinedButton(
                    onClick = { onSaveCurrent(name) },
                    label = if (currentName != null) "Rename this address" else "Save this address",
                )
            }
        }
    }
}

@Composable
private fun ProbeLabel(probe: ProbeState) {
    when (probe) {
        ProbeState.Idle -> Unit
        ProbeState.Checking -> Box(Modifier.size(14.dp)) {
            CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp))
        }
        is ProbeState.Reachable -> Text(
            "Answered in ${probe.roundTripMillis} ms",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        is ProbeState.Unreachable -> Text(
            probe.reason,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
