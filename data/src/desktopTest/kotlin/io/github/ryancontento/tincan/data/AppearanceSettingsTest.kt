package io.github.ryancontento.tincan.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppearanceSettingsTest {

    @Test
    fun the_theme_and_sidebar_width_survive_a_reopen() = runTest {
        withSettings { settings, dir ->
            settings.setTheme(ThemePreference.LIGHT)
            settings.setSidebarWidth(312)

            // Close-and-reopen is a relaunch. Repeated because the file is released asynchronously:
            // a single pass passed on Windows but hid a race that failed on Linux CI.
            settings.close()
            repeat(REOPEN_ATTEMPTS) {
                val reopened = createSettingsRepository(dir.absolutePath)
                val restored = reopened.settings.first()
                reopened.close()

                assertEquals(ThemePreference.LIGHT, restored.theme)
                assertEquals(312, restored.sidebarWidth)
            }
        }
    }

    @Test
    fun a_fresh_install_follows_the_desktop() = runTest {
        withSettings { settings, _ ->
            assertEquals(ThemePreference.SYSTEM, settings.settings.first().theme)
        }
    }

    /** A stored width outside the range would hide the rail or bury the transcript. */
    @Test
    fun the_sidebar_width_is_clamped_on_the_way_in() = runTest {
        withSettings { settings, _ ->
            settings.setSidebarWidth(5)
            assertEquals(TinCanSettings.MIN_SIDEBAR_WIDTH, settings.settings.first().sidebarWidth)

            settings.setSidebarWidth(9_000)
            assertEquals(TinCanSettings.MAX_SIDEBAR_WIDTH, settings.settings.first().sidebarWidth)
        }
    }

    /** A value written by a newer build must not stop an older one starting. */
    @Test
    fun an_unrecognised_theme_name_falls_back_instead_of_throwing() {
        assertEquals(ThemePreference.SYSTEM, themeFrom("SOLARIZED"))
        assertEquals(ThemePreference.SYSTEM, themeFrom(null))
        assertEquals(ThemePreference.LIGHT, themeFrom("LIGHT"))
    }

    @Test
    fun enter_sends_until_the_user_says_otherwise() = runTest {
        withSettings { settings, _ ->
            assertEquals(SendKey.ENTER, settings.settings.first().sendKey)

            settings.setSendKey(SendKey.CTRL_ENTER)
            assertEquals(SendKey.CTRL_ENTER, settings.settings.first().sendKey)
        }
    }

    @Test
    fun closing_the_window_quits_until_the_tray_is_chosen() = runTest {
        withSettings { settings, _ ->
            assertEquals(false, settings.settings.first().closeToTray)

            settings.setCloseToTray(true)
            assertEquals(true, settings.settings.first().closeToTray)
        }
    }

    @Test
    fun an_unrecognised_send_key_falls_back_to_enter() {
        assertEquals(SendKey.ENTER, sendKeyFrom("SHIFT_ENTER"))
        assertEquals(SendKey.ENTER, sendKeyFrom(null))
        assertEquals(SendKey.CTRL_ENTER, sendKeyFrom("CTRL_ENTER"))
    }

    private companion object {
        const val REOPEN_ATTEMPTS = 20
    }
}
