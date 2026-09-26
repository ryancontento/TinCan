package io.github.ryancontento.tincan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.ryancontento.tincan.attach.FileDrops
import io.github.ryancontento.tincan.attach.acceptFileDrops
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

/** Keep this thin: anything not tied to the desktop belongs in commonMain. */
fun main() {
    // Compose Desktop swallows AWT-thread exceptions; without this the UI just dies.
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        System.err.println("Uncaught exception on ${thread.name}:")
        error.printStackTrace()
    }

    // Before Koin: claim the directory before Room and DataStore open files in it.
    val dataDirectory = appDataDir()
    if (claimDataDirectory(dataDirectory) is SingleInstance.AlreadyRunning) {
        reportAlreadyRunning()
        return
    }
    // After the lock: a second copy must not truncate the first one's log.
    startErrorLog(dataDirectory)

    // Here, not in App(): window size is read before any composition, and a second graph
    // would mean a second DataStore over one file.
    val koin = startKoin { modules(appModule(dataDirectory), platformModule()) }.koin
    val settings: SettingsRepository = koin.get()
    val fileDrops: FileDrops = koin.get()

    val saved = runBlocking { runCatching { settings.settings.first().window }.getOrNull() }
    val savedX = saved?.x
    val savedY = saved?.y
    val initialSize = DpSize((saved?.width ?: DEFAULT_WIDTH).dp, (saved?.height ?: DEFAULT_HEIGHT).dp)
    val initialPosition =
        if (savedX != null && savedY != null) WindowPosition(savedX.dp, savedY.dp) else WindowPosition.PlatformDefault

    val trayAvailable = systemTrayAvailable()

    application {
        val appSettings by settings.settings.collectAsState(initial = null)
        var windowVisible by remember { mutableStateOf(true) }
        val useTray = trayAvailable && appSettings?.closeToTray == true
        val windowState = rememberWindowState(size = initialSize, position = initialPosition)

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
            // A blank window is a renderer that failed silently; this says which one it was.
            LaunchedEffect(Unit) { System.err.println("Renderer: ${window.renderApi}") }
            Box(Modifier.fillMaxSize().acceptFileDrops(fileDrops)) { App() }
        }
    }
}

/** A silent exit looks like a crash, and a copy started from an icon has no console. */
private fun reportAlreadyRunning() {
    System.err.println("TinCan is already running.")
    if (GraphicsEnvironment.isHeadless()) return
    runCatching {
        JOptionPane.showMessageDialog(null, "TinCan is already running.", "TinCan", JOptionPane.INFORMATION_MESSAGE)
    }
}

private const val DEFAULT_WIDTH = 1100
private const val DEFAULT_HEIGHT = 800
