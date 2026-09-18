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
import io.github.ryancontento.tincan.di.platformModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

/**
 * The entire desktop entry point. Everything below App() is shared with v2's
 * MainActivity — if platform-specific logic starts accumulating here, it
 * probably belongs in commonMain instead.
 */
fun main() {
    // Compose Desktop swallows AWT-thread exceptions; without this the UI just dies.
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        System.err.println("Uncaught exception on ${thread.name}:")
        error.printStackTrace()
    }

    // Started here, not in App(): window size must be read before any composition
    // exists, and a second graph would mean a second DataStore over one file.
    // v2 does the same in Application.onCreate.
    val koin = startKoin { modules(appModule(), platformModule()) }.koin
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
                // On close, not on drag: resizing fires continuously. Never block exit.
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
