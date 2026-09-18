package io.github.ryancontento.tincan.ui

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Three operating systems, three unrelated answers, and no common API.
 *
 * Every branch falls back to dark, which is both this app's previous behaviour
 * and the safer guess: light text on a dark ground stays readable if the guess
 * is wrong, where the reverse does not.
 */
actual fun systemPrefersDark(): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    return when {
        os.contains("win") -> windowsPrefersDark()
        os.contains("mac") -> readCommand("defaults", "read", "-g", "AppleInterfaceStyle")
            ?.contains("dark", ignoreCase = true) ?: false
        else -> linuxPrefersDark()
    }
}

/** 0 means "do not use the light theme for apps". The key is absent on older builds. */
private fun windowsPrefersDark(): Boolean {
    val output = readCommand(
        "reg", "query",
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "/v", "AppsUseLightTheme",
    ) ?: return true

    val value = Regex("0x([0-9a-fA-F]+)").find(output)?.groupValues?.get(1)
    return value?.toIntOrNull(16) == 0
}

/**
 * color-scheme is the modern answer and the one a GNOME dark-mode toggle sets.
 * Older desktops only name the theme, where "-dark" is the convention.
 */
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
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroy()
        return null
    }
    output.takeIf { process.exitValue() == 0 }
}.getOrNull()

private const val PROBE_TIMEOUT_SECONDS = 2L
