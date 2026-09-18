package io.github.ryancontento.tincan.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ryancontento.tincan.chat.describe
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.ThemePreference
import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.llm.BackendHealth
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ProbeState {
    data object Idle : ProbeState
    data object Checking : ProbeState
    data class Reachable(val roundTripMillis: Long) : ProbeState
    data class Unreachable(val reason: String) : ProbeState
}

data class SettingsUiState(
    val settings: TinCanSettings = TinCanSettings(),
    val probe: ProbeState = ProbeState.Idle,
)

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val backends: LlmBackendProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
    }

    fun setServerUrl(value: String) = viewModelScope.launch { repository.setServerUrl(value) }
    fun setSystemPrompt(value: String) = viewModelScope.launch { repository.setSystemPrompt(value) }
    fun setKeepAlive(value: String) = viewModelScope.launch { repository.setKeepAlive(value) }
    fun setTheme(value: ThemePreference) = viewModelScope.launch { repository.setTheme(value) }

    /** Blank clears the override and lets the server pick. */
    fun setNumCtx(raw: String) = viewModelScope.launch {
        repository.setNumCtx(raw.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 })
    }

    fun setTemperature(raw: String) = viewModelScope.launch {
        repository.setTemperature(raw.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull())
    }

    fun setLoadingThreshold(raw: String) = viewModelScope.launch {
        raw.trim().toLongOrNull()?.let { repository.setModelLoadingThreshold(it) }
    }

    /**
     * Explicit connection check. Uses probe() rather than listModels() because
     * the question here is "is anything alive at this address", which should
     * stay cheap and short-timeout.
     */
    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(probe = ProbeState.Checking) }
            val backend = backends.create(baseUrl = _state.value.settings.serverUrl)
            val result = when (val health = backend.probe()) {
                is BackendHealth.Available -> ProbeState.Reachable(health.roundTripMillis)
                is BackendHealth.Unavailable -> ProbeState.Unreachable(health.error.describe())
            }
            _state.update { it.copy(probe = result) }
        }
    }
}
