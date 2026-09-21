package io.github.ryancontento.tincan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/**
 * Stops the collectors a view model starts in its constructor, and waits until
 * they have actually stopped.
 *
 * A test that skips this leaves them running after it finishes. The next thing
 * they touch is Dispatchers.Main, which the test has reset by then, so the
 * failure lands on whatever happens to be running at the time — and only on a
 * machine slow enough for the timing to line up, which is to say on CI and
 * never here.
 */
internal fun ViewModel.stop() = runBlocking {
    viewModelScope.coroutineContext.job.cancelAndJoin()
}
