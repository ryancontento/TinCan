package io.github.ryancontento.tincan.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerKeyTest {

    @Test
    fun a_key_never_contains_the_address_it_came_from() {
        val key = ServerKey.derive("http://macbook.example.internal:11434", "salt").value
        assertTrue(key.startsWith("srv-"))
        assertFalse(key.contains("macbook"))
        assertFalse(key.contains("example"))
        assertFalse(key.contains("11434"))
    }

    @Test
    fun one_server_typed_two_ways_is_one_key() {
        assertEquals(
            ServerKey.derive("http://Host:11434/", "salt"),
            ServerKey.derive("  http://host:11434  ", "salt"),
        )
    }

    @Test
    fun different_servers_get_different_keys() {
        assertNotEquals(
            ServerKey.derive("http://localhost:11434", "salt"),
            ServerKey.derive("http://other:11434", "salt"),
        )
    }

    /** The salt is what stops a copied database being tested against guessed names. */
    @Test
    fun the_same_address_under_two_salts_does_not_match() {
        assertNotEquals(
            ServerKey.derive("http://localhost:11434", ServerKey.newSalt()),
            ServerKey.derive("http://localhost:11434", ServerKey.newSalt()),
        )
    }

    @Test
    fun only_stored_addresses_are_swept() {
        assertTrue(ServerKey.looksLikeAddress("http://localhost:11434"))
        assertTrue(ServerKey.looksLikeAddress("HTTPS://host"))
        assertFalse(ServerKey.looksLikeAddress("srv-0123456789ab"))
    }

    @Test
    fun the_salt_is_generated_once_and_reused() = runTest {
        withSettings { settings, _ ->
            assertEquals(
                settings.serverKeyFor("http://localhost:11434"),
                settings.serverKeyFor("http://localhost:11434"),
            )
        }
    }

    @Test
    fun the_startup_sweep_replaces_addresses_already_on_disk() = runTest {
        withRepo { repo, dir ->
            val url = "http://macbook.example.internal:11434"
            // Written the way an earlier build wrote it: the raw address.
            val conversation = repo.createConversation(ServerKey(url), "phi4", null)
            repo.appendUserMessage(conversation, "hello", ServerKey(url))
            assertTrue(dir.databaseContains("macbook.example.internal"))

            repo.redactStoredServerAddresses { ServerKey.derive(it, "salt") }

            assertEquals(
                ServerKey.derive(url, "salt").value,
                repo.observeMessages(conversation).first().single().backendId,
            )
            // An UPDATE alone would leave the old bytes in the file.
            assertFalse(dir.databaseContains("macbook.example.internal"))
        }
    }
}
