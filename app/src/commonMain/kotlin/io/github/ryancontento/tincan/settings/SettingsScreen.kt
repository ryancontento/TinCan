package io.github.ryancontento.tincan.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val settings = state.settings

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }

            Section("Server") {
                OutlinedTextField(
                    value = settings.serverUrl,
                    onValueChange = viewModel::setServerUrl,
                    label = { Text("Ollama address") },
                    supportingText = { Text("A MagicDNS name travels better than a raw IP — it survives the network changing under you.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = viewModel::testConnection) { Text("Test connection") }
                    ProbeLabel(state.probe)
                }
            }

            HorizontalDivider()

            Section("Generation") {
                OutlinedTextField(
                    value = settings.systemPrompt,
                    onValueChange = viewModel::setSystemPrompt,
                    label = { Text("System prompt") },
                    supportingText = { Text("Prepended to every conversation. Leave blank for none.") },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                )
                OutlinedTextField(
                    value = settings.temperature?.toString().orEmpty(),
                    onValueChange = viewModel::setTemperature,
                    label = { Text("Temperature") },
                    supportingText = { Text("Blank uses the model's own default.") },
                    singleLine = true,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
                OutlinedTextField(
                    value = settings.numCtx?.toString().orEmpty(),
                    onValueChange = viewModel::setNumCtx,
                    label = { Text("Context window (num_ctx)") },
                    supportingText = {
                        Text(
                            "Blank lets the server decide — and Ollama silently drops the oldest " +
                                "turns once a conversation passes it, without telling the client.",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                )
            }

            HorizontalDivider()

            Section("Timing") {
                OutlinedTextField(
                    value = settings.keepAlive,
                    onValueChange = viewModel::setKeepAlive,
                    label = { Text("Keep model loaded (keep_alive)") },
                    supportingText = { Text("How long the server holds the model in memory after a reply. Overrides its own setting.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                )
                OutlinedTextField(
                    value = settings.modelLoadingThresholdMillis.toString(),
                    onValueChange = viewModel::setLoadingThreshold,
                    label = { Text("\"Loading model\" threshold (ms)") },
                    supportingText = {
                        Text(
                            "How long to wait for a first token before saying the model is loading. " +
                                "Metal on a Mac wants ~2500; CPU inference on a large local model needs far more.",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun ProbeLabel(probe: ProbeState) {
    when (probe) {
        ProbeState.Idle -> Unit
        ProbeState.Checking -> CircularProgressIndicator(Modifier.padding(4.dp))
        is ProbeState.Reachable -> Text(
            "Answered in ${probe.roundTripMillis} ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is ProbeState.Unreachable -> Text(
            probe.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
