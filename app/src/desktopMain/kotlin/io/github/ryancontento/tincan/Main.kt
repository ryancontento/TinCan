package io.github.ryancontento.tincan

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.appDataDir
import io.github.ryancontento.tincan.di.appModule
import io.github.ryancontento.tincan.di.platformModule
import io.github.ryancontento.tincan.ui.systemTrayAvailable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane

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

    // Before Koin, because the whole point is to claim the directory before Room
    // and DataStore open files inside it.
    val dataDirectory = appDataDir()
    if (acquireSingleInstance(dataDirectory) is SingleInstance.AlreadyRunning) {
        reportAlreadyRunning()
        return
    }

    // Started here, not in App(): window size must be read before any composition
    // exists, and a second graph would mean a second DataStore over one file.
    // v2 does the same in Application.onCreate.
    val koin = startKoin { modules(appModule(dataDirectory), platformModule()) }.koin
    val settings: SettingsRepository = koin.get()

    val saved = runBlocking {
        runCatching { settings.settings.first().window }.getOrNull()
    }
    val savedX = saved?.x
    val savedY = saved?.y

    val trayAvailable = systemTrayAvailable()

    application {
        val appSettings by settings.settings.collectAsState(initial = null)
        var windowVisible by remember { mutableStateOf(true) }
        val useTray = trayAvailable && appSettings?.closeToTray == true

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

        // On close or hide, not on drag: resizing fires continuously. Never block exit.
        fun saveGeometry() = runCatching {
            runBlocking {
                settings.setWindowGeometry(
                    width = windowState.size.width.value.toInt(),
                    height = windowState.size.height.value.toInt(),
                    x = windowState.position.x.value.toInt(),
                    y = windowState.position.y.value.toInt(),
                )
            }
        }

        val icon = painterResource("tincan.png")

        if (useTray) {
            Tray(
                icon = icon,
                tooltip = "TinCan",
                onAction = { windowVisible = true },
                menu = {
                    Item("Open TinCan", onClick = { windowVisible = true })
                    Item("Quit", onClick = { saveGeometry(); exitApplication() })
                },
            )
        }

        Window(
            onCloseRequest = {
                saveGeometry()
                if (useTray) windowVisible = false else exitApplication()
            },
            visible = windowVisible,
            state = windowState,
            title = "TinCan",
            icon = icon,
        ) {
            // Reopening from the tray should land in front, not behind the window that had focus.
            LaunchedEffect(windowVisible) { if (windowVisible) window.toFront() }
            App()
        }
    }
}

/**
 * A launch that exits silently looks like a crash, and a copy started from a
 * desktop icon or a Start menu entry has no console to read — so say it in a
 * dialog when there is a screen to put one on, and on stderr either way.
 */
private fun reportAlreadyRunning() {
    System.err.println("TinCan is already running.")
    if (GraphicsEnvironment.isHeadless()) return
    runCatching {
        JOptionPane.showMessageDialog(
            null,
            "TinCan is already running.",
            "TinCan",
            JOptionPane.INFORMATION_MESSAGE,
        )
    }
}

private const val DEFAULT_WIDTH = 1100
private const val DEFAULT_HEIGHT = 800
