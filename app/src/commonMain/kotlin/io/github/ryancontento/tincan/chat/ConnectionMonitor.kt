package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.llm.BackendHealth
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import io.github.ryancontento.tincan.llm.LlmError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ConnectionState { UNKNOWN, CHECKING, ONLINE, OFFLINE }

/**
 * Owns "is the server there", so the view model does not have to.
 *
 * While offline it re-probes on a timer, which is what lets a queued message
 * send itself once the machine wakes.
 */
class ConnectionMonitor(
    private val scope: CoroutineScope,
    private val backends: LlmBackendProvider,
    private val serverUrl: () -> String,
    private val pollMillis: Long = DEFAULT_POLL_MILLIS,
) {
    private val _state = MutableStateFlow(ConnectionState.UNKNOWN)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _error = MutableStateFlow<LlmError?>(null)
    val error: StateFlow<LlmError?> = _error.asStateFlow()

    private var watch: Job? = null

    val isOffline: Boolean get() = _state.value == ConnectionState.OFFLINE

    fun check() {
        scope.launch {
            _state.value = ConnectionState.CHECKING
            probe()
        }
    }

    /** Called when a request failed for connectivity reasons, skipping a redundant probe. */
    fun reportUnreachable(error: LlmError) {
        _error.value = error
        _state.value = ConnectionState.OFFLINE
        startWatch()
    }

    /** A different address is a different server; nothing known carries over. */
    fun reset() {
        watch?.cancel()
        watch = null
        _error.value = null
        _state.value = ConnectionState.UNKNOWN
    }

    private suspend fun probe(): Boolean =
        when (val health = backends.create(serverUrl()).probe()) {
            is BackendHealth.Available -> {
                watch?.cancel()
                watch = null
                _error.value = null
                _state.value = ConnectionState.ONLINE
                true
            }
            is BackendHealth.Unavailable -> {
                _error.value = health.error
                _state.value = ConnectionState.OFFLINE
                startWatch()
                false
            }
        }

    private fun startWatch() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            while (isActive && _state.value == ConnectionState.OFFLINE) {
                delay(pollMillis)
                if (probe()) return@launch
            }
        }
    }

    private companion object {
        const val DEFAULT_POLL_MILLIS = 5_000L
    }
}
