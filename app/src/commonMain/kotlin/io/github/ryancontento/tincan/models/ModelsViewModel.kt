package io.github.ryancontento.tincan.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ryancontento.tincan.chat.describe
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.llm.LlmBackend
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.LoadedModel
import io.github.ryancontento.tincan.llm.ModelInfo
import io.github.ryancontento.tincan.llm.PullEvent
import io.github.ryancontento.tincan.llm.ollama.OllamaException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PullState(
    val model: String,
    val status: String = "Starting…",
    val fraction: Float? = null,
    val running: Boolean = true,
    val error: String? = null,
)

data class ModelsUiState(
    val serverUrl: String = "",
    val serverName: String? = null,
    val installed: List<ModelInfo> = emptyList(),
    val loaded: List<LoadedModel> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val pull: PullState? = null,
    /** Asked before deleting: a pull can be many gigabytes to get back. */
    val confirmDelete: String? = null,
) {
    fun loadedFor(id: String): LoadedModel? = loaded.firstOrNull { it.id == id }
}

/** What is on the configured server and what is in its memory; pulls, unloads and deletes. */
class ModelsViewModel(
    private val settings: SettingsRepository,
    private val backends: LlmBackendProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    private var pullJob: Job? = null

    init {
        viewModelScope.launch {
            settings.settings
                .map { it.serverUrl to it.activeServerName }
                .distinctUntilChanged()
                .collect { (url, name) ->
                    _state.update { it.copy(serverUrl = url, serverName = name) }
                    refresh()
                }
        }
    }

    private fun backend(): LlmBackend = backends.create(_state.value.serverUrl)

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(refreshing = true) }
        val backend = backend()
        val installed = backend.listModels()
        val loaded = backend.loadedModels()
        _state.update {
            it.copy(
                refreshing = false,
                installed = installed.getOrDefault(it.installed),
                loaded = loaded.getOrDefault(it.loaded),
                error = (installed.exceptionOrNull() ?: loaded.exceptionOrNull())?.let(::describe),
            )
        }
    }

    fun unload(id: String) = viewModelScope.launch {
        backend().unloadModel(id).onFailure { e -> _state.update { it.copy(error = describe(e)) } }
        refresh().join()
    }

    fun requestDelete(id: String) = _state.update { it.copy(confirmDelete = id) }

    fun cancelDelete() = _state.update { it.copy(confirmDelete = null) }

    fun confirmDelete() = viewModelScope.launch {
        val id = _state.value.confirmDelete ?: return@launch
        _state.update { it.copy(confirmDelete = null) }
        backend().deleteModel(id).onFailure { e -> _state.update { it.copy(error = describe(e)) } }
        refresh().join()
    }

    fun pull(name: String) {
        val model = name.trim()
        if (model.isEmpty() || _state.value.pull?.running == true) return

        _state.update { it.copy(pull = PullState(model), error = null) }
        pullJob = viewModelScope.launch {
            backend().pullModel(model).collect { event ->
                when (event) {
                    is PullEvent.Progress -> _state.update {
                        it.copy(pull = it.pull?.copy(status = event.status, fraction = event.fraction))
                    }
                    PullEvent.Done -> {
                        _state.update { it.copy(pull = it.pull?.copy(status = "Pulled", fraction = 1f, running = false)) }
                        refresh()
                    }
                    is PullEvent.Failed -> _state.update {
                        it.copy(pull = it.pull?.copy(running = false, error = event.error.describe()))
                    }
                }
            }
        }
    }

    /** Ollama keeps the layers already downloaded, so pulling again resumes. */
    fun cancelPull() {
        pullJob?.cancel()
        pullJob = null
        _state.update { it.copy(pull = it.pull?.copy(running = false, error = "Cancelled. Pull again to resume.")) }
    }

    fun dismissPull() = _state.update { it.copy(pull = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun describe(e: Throwable): String =
        ((e as? OllamaException)?.error ?: LlmError.Unknown(e)).describe()
}
