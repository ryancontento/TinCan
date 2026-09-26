package io.github.ryancontento.tincan.ui

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Windows and Linux fall back to dark, which stays readable if the guess is wrong. macOS only sets
 * AppleInterfaceStyle in dark mode, so there a missing key genuinely means light.
 */
actual fun systemPrefersDark(): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    return when {
        os.contains("win") -> windowsPrefersDark()
        os.contains("mac") ->
            readCommand("defaults", "read", "-g", "AppleInterfaceStyle")?.contains("dark", ignoreCase = true) == true
        else -> linuxPrefersDark()
    }
}

/** 0 means "do not use the light theme for apps". The key is absent on older builds. */
private fun windowsPrefersDark(): Boolean {
    // Full path: a bare "reg" lets Windows pick up a reg.exe from the working directory first.
    val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
    val output = readCommand(
        "$systemRoot\\System32\\reg.exe", "query",
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "/v", "AppsUseLightTheme",
    ) ?: return true

    val value = Regex("0x([0-9a-fA-F]+)").find(output)?.groupValues?.get(1)
    return value?.toIntOrNull(16) == 0
}

/** color-scheme is what GNOME's dark toggle sets; older desktops only name the theme, "-dark" by convention. */
private fun linuxPrefersDark(): Boolean {
    readCommand("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")?.let {
        if (it.contains("prefer-dark")) return true
        if (it.contains("prefer-light")) return false
    }
    readCommand("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")?.let {
        return it.contains("dark", ignoreCase = true)
    }
    return true
}

/** Null on anything that goes wrong: a missing tool must not stop the app starting. */
private fun readCommand(vararg command: String): String? = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    // waitFor before readText, which blocks until exit and would make the timeout dead code.
    // The output is a line or two, so the child cannot stall on a full pipe.
    if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    output.takeIf { process.exitValue() == 0 }
}.getOrNull()

private const val PROBE_TIMEOUT_SECONDS = 2L
