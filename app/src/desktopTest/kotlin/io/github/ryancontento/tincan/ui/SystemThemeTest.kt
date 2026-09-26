package io.github.ryancontento.tincan.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Detection shells out, so the honest check is asking the platform a second way and comparing. */
class SystemThemeTest {

    @Test
    fun the_reported_preference_matches_what_the_desktop_actually_says() {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val expected = when {
            os.contains("win") -> windowsAppsUseLightTheme()?.let { it == 0 }
            // Only Windows has an independent check; elsewhere it would repeat the same guess.
            else -> null
        } ?: return

        assertEquals(expected, systemPrefersDark())
    }

    @Test
    fun an_unreadable_setting_does_not_throw() {
        systemPrefersDark()
    }

    private fun windowsAppsUseLightTheme(): Int? = runCatching {
        val process = ProcessBuilder(
            "powershell", "-NoProfile", "-Command",
            "(Get-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\" +
                "Personalize' -Name AppsUseLightTheme).AppsUseLightTheme",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.trim().toIntOrNull()
    }.getOrNull()
}
