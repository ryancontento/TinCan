package io.github.ryancontento.tincan.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedServersTest {

    @Test
    fun saved_servers_survive_a_reopen() = runTest {
        withSettings { settings, dir ->
            settings.saveServer("Local", "http://localhost:11434")
            settings.saveServer("MacBook", "http://macbook:11434")
            settings.close()

            val reopened = createSettingsRepository(dir.absolutePath)
            val restored = reopened.settings.first().savedServers
            reopened.close()

            assertEquals(
                listOf(SavedServer("Local", "http://localhost:11434"), SavedServer("MacBook", "http://macbook:11434")),
                restored,
            )
        }
    }

    @Test
    fun saving_an_address_already_in_the_list_renames_it() = runTest {
        withSettings { settings, _ ->
            settings.saveServer("Mac", "http://macbook:11434")
            // Typed differently, same server.
            settings.saveServer("MacBook", "HTTP://macbook:11434/")

            assertEquals(listOf("MacBook"), settings.settings.first().savedServers.map { it.name })
        }
    }

    @Test
    fun removing_a_server_leaves_the_others() = runTest {
        withSettings { settings, _ ->
            settings.saveServer("Local", "http://localhost:11434")
            settings.saveServer("MacBook", "http://macbook:11434")
            settings.removeServer("http://macbook:11434/")

            assertEquals(listOf("Local"), settings.settings.first().savedServers.map { it.name })
        }
    }

    @Test
    fun the_current_address_shows_its_saved_name() = runTest {
        withSettings { settings, _ ->
            settings.saveServer("MacBook", "http://macbook:11434")
            assertNull(settings.settings.first().activeServerName)

            settings.setServerUrl("http://macbook:11434/")
            assertEquals("MacBook", settings.settings.first().activeServerName)
        }
    }

    @Test
    fun a_blank_name_falls_back_to_the_address() {
        assertEquals("http://macbook:11434", emptyList<SavedServer>().withServer("  ", " http://macbook:11434 ").single().name)
    }

    @Test
    fun a_corrupt_stored_list_reads_as_empty_rather_than_crashing() {
        assertTrue(decodeServers("{not json").isEmpty())
        assertTrue(decodeServers(null).isEmpty())
    }
}
