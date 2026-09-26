package io.github.ryancontento.tincan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/**
 * Cancels the collectors a view model starts in init, and waits. Left running, they hit the reset
 * Dispatchers.Main and fail whatever test runs next, but only on a slow machine such as CI.
 */
internal fun ViewModel.stop() = runBlocking {
    viewModelScope.coroutineContext.job.cancelAndJoin()
}
