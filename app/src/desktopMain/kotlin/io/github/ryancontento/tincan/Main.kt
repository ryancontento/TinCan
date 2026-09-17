package io.github.ryancontento.tincan

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * The entire desktop entry point. Everything below App() is shared with v2's
 * MainActivity — if platform-specific logic starts accumulating here, it
 * probably belongs in commonMain instead.
 */
fun main() {
    // Compose Desktop loses exceptions thrown on the AWT event thread much more
    // quietly than Android does. Installing this before the window opens is the
    // difference between a stack trace and a silently dead UI.
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        System.err.println("Uncaught exception on ${thread.name}:")
        error.printStackTrace()
    }

    application {
        val windowState = rememberWindowState(size = DpSize(1100.dp, 800.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "TinCan",
        ) {
            App()
        }
    }
}
