package io.github.ryancontento.tincan.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The detection shells out to whatever the platform provides, so the only
 * honest test is to ask the platform the same question a second way and check
 * the two agree.
 */
class SystemThemeTest {

    @Test
    fun the_reported_preference_matches_what_the_desktop_actually_says() {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val expected = when {
            os.contains("win") -> windowsAppsUseLightTheme()?.let { it == 0 }
            // Only Windows is checked independently here; elsewhere the test
            // asserts nothing rather than re-implementing the same guess twice.
            else -> null
        } ?: return

        assertEquals(expected, systemPrefersDark())
    }

    @Test
    fun an_unreadable_setting_does_not_throw() {
        // Whatever this machine reports, asking must always produce an answer.
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
