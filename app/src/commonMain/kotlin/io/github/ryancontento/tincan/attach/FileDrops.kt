package io.github.ryancontento.tincan.attach

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Carries window drops from the platform layer to the chat screen; a drop while chat is hidden is ignored. */
class FileDrops {
    private val _files = MutableSharedFlow<List<PickedFile>>(extraBufferCapacity = 1)
    val files: SharedFlow<List<PickedFile>> = _files.asSharedFlow()

    private val _hovering = MutableStateFlow(false)
    /** True while files are dragged over the window, so the screen can say where they will go. */
    val hovering: StateFlow<Boolean> = _hovering.asStateFlow()

    fun hover(over: Boolean) { _hovering.value = over }

    fun drop(files: List<PickedFile>) {
        _hovering.value = false
        if (files.isNotEmpty()) _files.tryEmit(files)
    }
}
