package io.github.ryancontento.tincan

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.di.appModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

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

    // Koin starts here rather than inside App() because the window size has to
    // be known before the window is created, and that means reading settings
    // before any composition exists.
    //
    // It also has to be exactly one graph: DataStore refuses to open a second
    // instance over the same file in one process, and Room holds a file lock.
    // Building a throwaway repository for this read would break both.
    //
    // v2's Android Application class does the same thing in onCreate.
    val koin = startKoin { modules(appModule) }.koin
    val settings: SettingsRepository = koin.get()

    val saved = runBlocking {
        runCatching { settings.settings.first().window }.getOrNull()
    }
    val savedX = saved?.x
    val savedY = saved?.y

    application {
        val windowState = rememberWindowState(
            size = DpSize(
                (saved?.width ?: DEFAULT_WIDTH).dp,
                (saved?.height ?: DEFAULT_HEIGHT).dp,
            ),
            position = if (savedX != null && savedY != null) {
                WindowPosition(savedX.dp, savedY.dp)
            } else {
                WindowPosition.PlatformDefault
            },
        )

        Window(
            onCloseRequest = {
                // Saved on the way out rather than on every drag: resizing fires
                // continuously, and writing each frame to disk would be a lot of
                // churn for a value read once per launch.
                //
                // Wrapped because failing to remember a window size is never a
                // reason to prevent the app from closing.
                runCatching {
                    runBlocking {
                        settings.setWindowGeometry(
                            width = windowState.size.width.value.toInt(),
                            height = windowState.size.height.value.toInt(),
                            x = windowState.position.x.value.toInt(),
                            y = windowState.position.y.value.toInt(),
                        )
                    }
                }
                exitApplication()
            },
            state = windowState,
            title = "TinCan",
            icon = painterResource("tincan.png"),
        ) {
            App()
        }
    }
}

private const val DEFAULT_WIDTH = 1100
private const val DEFAULT_HEIGHT = 800
