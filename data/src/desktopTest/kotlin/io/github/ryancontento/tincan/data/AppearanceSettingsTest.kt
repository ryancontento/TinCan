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

            // Closing and reopening over the same folder is what a relaunch is.
            // DataStore refuses two instances on one file, so the close is the
            // part that makes this a real round trip rather than a cache read.
            //
            // Repeated, because releasing the file is asynchronous and a single
            // pass hides the race: one platform wins it and another does not.
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

    /**
     * A value written by a newer build must not stop an older one starting,
     * which is the failure mode of mapping an enum by ordinal or by valueOf().
     */
    @Test
    fun an_unrecognised_theme_name_falls_back_instead_of_throwing() {
        assertEquals(ThemePreference.SYSTEM, themeFrom("SOLARIZED"))
        assertEquals(ThemePreference.SYSTEM, themeFrom(null))
        assertEquals(ThemePreference.LIGHT, themeFrom("LIGHT"))
    }

    private companion object {
        const val REOPEN_ATTEMPTS = 20
    }
}
